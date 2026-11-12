//! The mapping of a resolved `file:` URL to a local path, and reading a range of the file with its pins checked
//! (spec/refkv.md sections 5 and 5.1): the POSIX rules, and the Windows rules of RFC 8089 appendix E.
//!
//! The mapping is a pure function of strings, so that both systems' rules are tested on every system.
use refkv_vzip::uri;
use std::fs::File;

/// The pins of a `file:` source (spec/refkv.md section 5.1).
#[derive(Clone, Debug, Default)]
pub struct FilePins {
    pub size: Option<u64>,
    pub etag: Option<String>,
    pub modified_not_after: Option<i64>,
}

/// The local path a resolved `file:` URL names, on a Windows system if `windows`, else a POSIX one.
///
/// - Both: no query; a fragment is ignored; the path is absolute; it is percent-decoded to UTF-8 holding no
///   NUL and no `/` from a decoded `%2F`; and after decoding no segment is `.` or `..`.
/// - POSIX: a URL with a host has no mapping, whatever is allowed.
/// - Windows: the decoded path holds no `\`; a local path's first segment is a drive letter and `:` (or `|`),
///   so `file:///data/x`, which would name a file on whichever drive is current, is an error; a host names a UNC
///   server (`file://server/share/x` is `\\server\share\x`), which is an error unless `allow_file_hosts`, since
///   reading it connects to the server, which may send it the user's credentials. The host must be a name alone,
///   and the path must name a share.
pub fn file_path(url: &str, windows: bool, allow_file_hosts: bool) -> Result<String, String> {
    let p = uri::split(url);
    let host = p.authority.filter(|a| !a.is_empty() && !a.eq_ignore_ascii_case("localhost"));
    if !p.path.starts_with('/') {
        return Err(format!("{url}: a file: URL's path is not absolute"));
    }
    if p.query.is_some() {
        return Err(format!("{url}: a file: URL has a query"));
    }
    let decoded = decode(p.path, windows).map_err(|e| format!("{url}: {e}"))?;
    let segments: Vec<&str> = decoded[1..].split('/').collect();
    let dot_free = |segs: &[&str]| -> Result<(), String> {
        match segs.iter().find(|s| **s == "." || **s == "..") {
            Some(_) => Err(format!("{url}: a file: URL's path has a dot segment after decoding")),
            None => Ok(()),
        }
    };
    if !windows {
        if let Some(h) = host {
            return Err(format!("{url}: a file: URL with a host ({h}) has no mapping on this system"));
        }
        dot_free(&segments)?;
        return Ok(decoded);
    }
    if let Some(h) = host {
        if !allow_file_hosts {
            return Err(format!("{url}: the UNC server {h} is not read unless the unsafe setting allow_file_hosts is on"));
        }
        if h.contains(['@', ':', '%', '[']) {
            return Err(format!("{url}: a UNC server must be a host name alone, with no userinfo, port or percent-encoding"));
        }
        if segments[0].is_empty() {
            return Err(format!("{url}: a file: URL with a host must name a share"));
        }
        dot_free(&segments)?;
        return Ok(format!("\\\\{h}\\{}", segments.join("\\")));
    }
    let first = segments[0].as_bytes();
    if first.len() != 2 || !first[0].is_ascii_alphabetic() || !(first[1] == b':' || first[1] == b'|') {
        return Err(format!(
            "{url}: a file: URL's path on Windows must start with a drive letter, not {:?}: it would name a file on whichever drive is current",
            segments[0]
        ));
    }
    dot_free(&segments[1..])?;
    Ok(format!("{}:\\{}", first[0] as char, segments[1..].join("\\")))
}

/// Percent-decodes a path to UTF-8; an encoded `/`, a NUL, a malformed escape and (on Windows) a `\` are errors.
fn decode(path: &str, windows: bool) -> Result<String, String> {
    let b = path.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        let c = if b[i] == b'%' {
            let hex = path.get(i + 1..i + 3).filter(|h| h.bytes().all(|d| d.is_ascii_hexdigit())).ok_or("a malformed percent-escape")?;
            i += 3;
            let v = u8::from_str_radix(hex, 16).map_err(|_| "a malformed percent-escape")?;
            if v == b'/' {
                return Err("a file: URL's path has an encoded '/'".into());
            }
            v
        } else {
            i += 1;
            b[i - 1]
        };
        if c == 0 {
            return Err("a file: URL's path has a NUL".into());
        }
        if windows && c == b'\\' {
            return Err("a file: URL's path has a backslash, which Windows would read as a separator".into());
        }
        out.push(c);
    }
    String::from_utf8(out).map_err(|_| "a file: URL's path is not UTF-8, which this reader needs".into())
}

/// Seconds since the epoch of a modification time, rounded down (towards negative infinity).
fn whole_seconds(t: std::time::SystemTime) -> i64 {
    match t.duration_since(std::time::UNIX_EPOCH) {
        Ok(d) => d.as_secs() as i64,
        Err(e) => {
            let d = e.duration();
            -(d.as_secs() as i64) - i64::from(d.subsec_nanos() > 0)
        }
    }
}

/// Bytes `[offset, offset + length)` of the file a resolved `file:` URL names, every pin checked first: the size
/// against the file's, `modified_not_after` against its modification time in whole seconds rounded down, and an
/// `etag`, which a file has none of, fails (pins fail closed, spec/refkv.md section 5.1).
pub fn read_file(url: &str, allow_file_hosts: bool, offset: u64, length: u64, pins: &FilePins) -> Result<Vec<u8>, String> {
    let path = file_path(url, cfg!(windows), allow_file_hosts)?;
    if pins.etag.is_some() {
        return Err(format!("{url}: an etag pin cannot be checked for a file: URL"));
    }
    let f = File::open(&path).map_err(|e| format!("{path}: {e}"))?;
    let meta = f.metadata().map_err(|e| format!("{path}: {e}"))?;
    if !meta.is_file() {
        return Err(format!("{path} is not a regular file"));
    }
    let size = meta.len();
    if let Some(p) = pins.size
        && p != size
    {
        return Err(format!("{url}: pinned to {p} bytes, but has {size}"));
    }
    if let Some(p) = pins.modified_not_after {
        let m = whole_seconds(meta.modified().map_err(|e| format!("{path}: the modification time cannot be read: {e}"))?);
        if m > p {
            return Err(format!("{url}: modified at {m}, after the pinned {p}"));
        }
    }
    let end = offset.checked_add(length).ok_or("the range ends past 2^64 - 1")?;
    if end > size {
        return Err(format!("{url}: the file has {size} bytes, short of the {end} needed"));
    }
    let mut buf = vec![0u8; usize::try_from(length).map_err(|_| "the range is too long".to_string())?];
    #[cfg(unix)]
    std::os::unix::fs::FileExt::read_exact_at(&f, &mut buf, offset).map_err(|e| format!("{path}: {e}"))?;
    #[cfg(not(unix))]
    {
        use std::io::{Read, Seek, SeekFrom};
        let mut f = f;
        f.seek(SeekFrom::Start(offset)).and_then(|_| f.read_exact(&mut buf)).map_err(|e| format!("{path}: {e}"))?;
    }
    Ok(buf)
}
