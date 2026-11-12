//! Resolution of `url` sources for `refkv_phkv_get`: `file:` URLs from disk, `http(s)` URLs by range
//! requests under the reader policy (spec/refkv.md section 6.7), a url source's relative reference
//! against the archive's own URI (section 5), and its size pin checked (spec/refkv.md section 5.1).
use crate::{http, store};
use refkv_phkv::Resolver;
use refkv_vzip::pb::Source;
use refkv_vzip::uri;
use std::fs::File;
use std::sync::Arc;

pub struct HostResolver {
    /// the archive's URI, which relative references are resolved against
    pub base: String,
    pub client: Arc<http::Client>,
}

impl HostResolver {
    fn read_file(&self, url: &str, offset: u64, length: u64, pin: Option<u64>) -> Result<Vec<u8>, String> {
        let parts = uri::split(url);
        if parts.authority.is_some_and(|a| !a.is_empty() && a != "localhost") {
            return Err(format!("{url}: a file URL with a host"));
        }
        let path = String::from_utf8(store::percent_decode(parts.path)).map_err(|_| format!("{url}: a path that is not UTF-8"))?;
        let f = File::open(&path).map_err(|e| format!("{path}: {e}"))?;
        let size = f.metadata().map_err(|e| format!("{path}: {e}"))?.len();
        if let Some(p) = pin
            && p != size
        {
            return Err(format!("{url}: pinned to {p} bytes, but has {size}"));
        }
        let mut buf = vec![0u8; usize::try_from(length).map_err(|_| "range too long".to_string())?];
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

    fn read_http(&self, url: &str, offset: u64, length: u64, pin: Option<u64>) -> Result<Vec<u8>, String> {
        if length == 0 {
            return Ok(Vec::new());
        }
        let range = format!("bytes={}-{}", offset, offset + length - 1);
        let r = self.client.get(url, &[("Range", &range)], |s, _| s == 200 || s == 206).map_err(|e| match e {
            http::SendError::Refused(m) | http::SendError::Network(m) => m,
        })?;
        let total = r.header("content-range").and_then(|v| v.rsplit('/').next().and_then(|t| t.trim().parse::<u64>().ok()));
        if let (Some(p), Some(t)) = (pin, total)
            && p != t
        {
            return Err(format!("{url}: pinned to {p} bytes, but has {t}"));
        }
        match r.status {
            206 if r.body.len() as u64 == length => Ok(r.body),
            200 if offset + length <= r.body.len() as u64 => Ok(r.body[offset as usize..(offset + length) as usize].to_vec()),
            s => Err(format!("{url}: status {s}, {} bytes for a range of {length}", r.body.len())),
        }
    }
}

impl Resolver for HostResolver {
    fn read(&self, source: &Source, offset: u64, length: u64) -> Result<Vec<u8>, String> {
        let reference = source.url_str().ok_or("not a url source")?;
        let url = uri::resolve(&self.base, reference)?;
        match uri::split(&url).scheme.map(|s| s.to_ascii_lowercase()).as_deref() {
            Some("file") => self.read_file(&url, offset, length, source.size),
            Some("http" | "https") => self.read_http(&url, offset, length, source.size),
            _ => Err(format!("{url}: only file, http and https sources are read")),
        }
    }
}
