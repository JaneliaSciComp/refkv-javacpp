//! The `file:` URL mapping (POSIX and Windows rules) and the pins of a file source. The same cases as the Java reader's
//! ComponentsTest, which implements the same rules.
use refkv_capi::filemap::{FilePins, file_path, read_file};

fn win(url: &str) -> Result<String, String> {
    file_path(url, true, false)
}

#[test]
fn maps_windows_drive_paths() {
    for (url, want) in [
        ("file:///C:/data/x", r"C:\data\x"),
        ("file:///c%7C/data/x", r"c:\data\x"), // RFC 8089 allows | for :
        ("file:///C:/my%20file.vzip", r"C:\my file.vzip"),
        ("file:///C:/%C3%A9", "C:\\\u{e9}"),
        ("file:///C:", r"C:\"),
        ("file:///C:/", r"C:\"),
        ("file:///C:/a/b/", r"C:\a\b\"),
        ("file://localhost/C:/x", r"C:\x"),
        ("FILE://LOCALHOST/C:/x", r"C:\x"),
        ("file:/C:/x", r"C:\x"),
        ("file:///C:/x#ignored", r"C:\x"),
        ("file:///%43:/x", r"C:\x"),
    ] {
        assert_eq!(win(url).as_deref(), Ok(want), "{url}");
    }
}

#[test]
fn refuses_what_windows_cannot_safely_name() {
    for url in [
        "file:///data/x", // no drive letter: it would name a file on whichever drive is current
        "file:///x",
        "file:///1:/x",
        "file:///CC:/x",
        "file:///C/x",
        "file:///:/x",
        "file:///C:/a%5Cb", // a backslash would be read as a separator
        "file:///C:/a%5cb",
        "file:///C:/a%2Fb",
        "file:///C:/a%00b",
        "file:///C:/%2E%2E/x", // dot segments after decoding
        "file:///C:/a/%2e/b",
        "file:///C:/a%FF", // not UTF-8
        "file:///C:/a%zz", // a malformed escape
        "file:///C:/a%2",
        "file:///C:/x?q",
        "file:x",
    ] {
        assert!(file_path(url, true, true).is_err(), "{url}");
    }
}

#[test]
fn maps_unc_paths_only_when_file_hosts_are_allowed() {
    let url = "file://server/share/dir/x.vzip";
    let e = file_path(url, true, false).unwrap_err();
    assert!(e.contains("allow_file_hosts"), "{e}");
    assert_eq!(file_path(url, true, true).as_deref(), Ok(r"\\server\share\dir\x.vzip"));
    assert_eq!(file_path("file://server/share", true, true).as_deref(), Ok(r"\\server\share"));
    assert_eq!(file_path("file://server/share/", true, true).as_deref(), Ok(r"\\server\share\"));
    assert_eq!(file_path("file://host.example.com/s/%C3%A9", true, true).as_deref(), Ok("\\\\host.example.com\\s\\\u{e9}"));
    for url in [
        "file://server/",
        "file://server",
        "file://user@server/share/x",
        "file://server:445/share/x",
        "file://%73erver/share/x",
        "file://[::1]/share/x",
        "file://server/share/%5C",
        "file://server/share/%2E%2E/x",
        "file://server/share/a%2Fb",
        "file://server/share/x?q",
    ] {
        assert!(file_path(url, true, true).is_err(), "{url}");
    }
}

#[test]
fn maps_posix_paths_and_refuses_hosts_whatever_is_allowed() {
    for (url, want) in [
        ("file:///data/my%20file.vzip", "/data/my file.vzip"),
        ("file:/x", "/x"),
        ("FILE://LocalHost/x", "/x"),
        ("file:///x#ignored", "/x"),
        ("file:///a%2e/b", "/a./b"), // a name that merely ends in a dot is a name
        ("file:///a%5Cb", "/a\\b"),  // a backslash is a character of a POSIX name
    ] {
        assert_eq!(file_path(url, false, false).as_deref(), Ok(want), "{url}");
    }
    for url in ["file://server/share/x", "file:///a%2Fb", "file:///a%00b", "file:///%2E%2E/b", "file:///a/%2e/b", "file:///x?q", "file:///a%FF", "file:x"] {
        assert!(file_path(url, false, true).is_err(), "{url}");
    }
}

fn url_of(path: &std::path::Path) -> String {
    refkv_vzip::uri::file_uri(path).unwrap()
}

#[cfg(unix)]
#[test]
fn checks_the_pins_of_a_file_and_fails_closed() {
    use std::time::{Duration, UNIX_EPOCH};
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("f.bin");
    std::fs::write(&path, b"0123456789").unwrap();
    std::fs::File::options().write(true).open(&path).unwrap().set_modified(UNIX_EPOCH + Duration::from_millis(1_700_000_000_999)).unwrap();
    let url = url_of(&path);
    let read = |pins: FilePins, off, len| read_file(&url, false, off, len, &pins);
    assert_eq!(read(FilePins::default(), 2, 4).unwrap(), b"2345");
    assert_eq!(read(FilePins { size: Some(10), ..Default::default() }, 0, 4).unwrap(), b"0123");
    assert!(read(FilePins { size: Some(11), ..Default::default() }, 0, 4).is_err());
    // the modification time is rounded down to whole seconds
    assert!(read(FilePins { modified_not_after: Some(1_700_000_000), ..Default::default() }, 0, 4).is_ok());
    assert!(read(FilePins { modified_not_after: Some(1_699_999_999), ..Default::default() }, 0, 4).is_err());
    // a file has no ETag, so an etag pin cannot be checked, and a pin that cannot be checked fails
    assert!(read(FilePins { etag: Some("\"abc\"".into()), ..Default::default() }, 0, 4).is_err());
    assert!(read(FilePins::default(), 8, 4).is_err(), "past the end");
    assert!(read(FilePins::default(), u64::MAX, 4).is_err(), "an offset that overflows");
    assert!(read_file(&url_of(&dir.path().join("absent")), false, 0, 1, &FilePins::default()).is_err());
    assert!(read_file(&url_of(dir.path()), false, 0, 1, &FilePins::default()).is_err(), "a directory is not a regular file");
    assert!(read_file(&format!("{}/%2E%2E/f.bin", url_of(dir.path())), false, 0, 1, &FilePins::default()).is_err(), "an encoded dot segment");
}
