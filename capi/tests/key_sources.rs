//! The C functions a host resolves references with (`refkv_phkv_sources`, `_key_found`, `_key_bytes`), on a phkv store
//! whose references use every kind of source. refkv never writes `key` sources when it zarrifies, so this store is
//! written here, and the file is committed for the Java tests (`java/src/test/resources/keysources.phkv`):
//! `REFKV_WRITE_FIXTURE=1 cargo test --test key_sources` rewrites it, and without that the test fails if the committed
//! file is not what the writer writes now.
use refkv_capi::*;
use refkv_phkv::{Codec, Options, Writer};
use refkv_vzip::pb::{self, Range, SourceKind};
use std::ffi::CString;
use std::ptr::{null, null_mut};

const SMALL: &[u8] = b"small-value";
const DATA: &[u8] = b"0123456789";

fn big() -> Vec<u8> {
    (0..10_000u32).map(|i| (i * 7 % 251) as u8).collect()
}

fn zstd() -> Vec<u8> {
    (0..8_000u32).map(|i| (i / 40) as u8).collect()
}

fn write_store() -> Vec<u8> {
    let mut w = Writer::new(Vec::new(), Options::default());
    let data = w.blob(DATA).unwrap();
    let (small, large, zst) = (w.internal("k/small").unwrap(), w.internal("k/big").unwrap(), w.internal("k/zstd").unwrap());
    w.add_bytes("k/small", SMALL).unwrap(); // short enough to be inline in its record: held whole
    w.add_bytes("k/big", &big()).unwrap(); // stored out of line, uncompressed: a window is read by itself
    w.add_bytes_with("k/zstd", &zstd(), Codec::Zstd).unwrap(); // compressed: held whole
    w.add_ranges("r/small", &[Range::of(small, 1, 5)]).unwrap();
    w.add_ranges("r/big", &[Range::of(large, 100, 40), Range::of(large, 9990, 10)]).unwrap();
    w.add_ranges("r/zstd", &[Range::of(zst, 10, 20)]).unwrap();
    let mut checked = Range::of(large, 200, 100);
    checked.crc32c = Some(crc32c::crc32c(&big()[200..300]));
    w.add_ranges("r/crc", &[checked]).unwrap();
    w.add_ranges(
        "r/mixed",
        &[
            Range::of(small, 0, 5),
            Range::literal(b"|".to_vec()),
            Range::of(large, 5000, 10),
            Range::of(zst, 0, 4),
            Range::of(data, 2, 4),
            Range::of(small, 6, 5),
        ],
    )
    .unwrap();
    w.close().unwrap()
}

fn fixture_path() -> std::path::PathBuf {
    std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../java/src/test/resources/keysources.phkv")
}

#[test]
fn the_committed_fixture_is_what_the_writer_writes() {
    let fresh = write_store();
    if std::env::var_os("REFKV_WRITE_FIXTURE").is_some() {
        std::fs::write(fixture_path(), &fresh).unwrap();
    }
    let committed = std::fs::read(fixture_path()).expect("the fixture; REFKV_WRITE_FIXTURE=1 writes it");
    assert!(committed == fresh, "the committed keysources.phkv is stale: rerun with REFKV_WRITE_FIXTURE=1");
}

struct Open(*mut RefkvPhkv);

impl Open {
    fn new(bytes: &[u8]) -> (Open, tempfile::TempDir) {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("s.phkv");
        std::fs::write(&path, bytes).unwrap();
        let c = CString::new(path.to_str().unwrap()).unwrap();
        let mut h = null_mut();
        assert_eq!(unsafe { refkv_phkv_open(c.as_ptr(), &mut h) }, REFKV_OK);
        (Open(h), dir)
    }
}

impl Drop for Open {
    fn drop(&mut self) {
        unsafe { refkv_phkv_close(self.0) }
    }
}

fn take(b: *mut RefkvBuffer) -> Vec<u8> {
    let v = unsafe { std::slice::from_raw_parts(refkv_buffer_data(b), refkv_buffer_len(b)) }.to_vec();
    unsafe { refkv_buffer_free(b) };
    v
}

fn found(s: &Open, key: &str) -> (i32, u64, u64) {
    let c = CString::new(key).unwrap();
    let (mut size, mut held) = (0u64, 0u64);
    let status = unsafe { refkv_phkv_key_found(s.0, c.as_ptr(), &mut size, &mut held) };
    (status, size, held)
}

fn key_bytes(s: &Open, key: &str, start: u64, end: u64) -> Result<Vec<u8>, i32> {
    let c = CString::new(key).unwrap();
    let mut out = null_mut();
    match unsafe { refkv_phkv_key_bytes(s.0, c.as_ptr(), start, end, &mut out) } {
        REFKV_OK => Ok(take(out)),
        e => Err(e),
    }
}

#[test]
fn gives_a_host_the_source_table() {
    let (s, _dir) = Open::new(&write_store());
    let mut out = null_mut();
    assert_eq!(unsafe { refkv_phkv_sources(s.0, &mut out) }, REFKV_OK);
    let (sources, _) = pb::decode_source_table(&take(out)).unwrap();
    let kinds: Vec<_> = sources.iter().map(|s| s.kind.clone()).collect();
    assert_eq!(
        kinds,
        vec![
            SourceKind::Data(DATA.to_vec()),
            SourceKind::Key("k/small".into()),
            SourceKind::Key("k/big".into()),
            SourceKind::Key("k/zstd".into()),
        ]
    );
    assert_eq!(unsafe { refkv_phkv_sources(s.0, null_mut()) }, REFKV_USAGE);
}

#[test]
fn looks_up_a_key_source_and_what_holding_it_takes() {
    let (s, _dir) = Open::new(&write_store());
    // an inline value is held whole, and so is a compressed one; an uncompressed one stored out of line is read by window
    assert_eq!(found(&s, "k/small"), (REFKV_OK, SMALL.len() as u64, SMALL.len() as u64));
    assert_eq!(found(&s, "k/big"), (REFKV_OK, 10_000, u64::MAX));
    assert_eq!(found(&s, "k/zstd"), (REFKV_OK, 8_000, 8_000));
    // a reference is no bytes value, and neither is a key that is not there
    assert_eq!(found(&s, "r/small").0, REFKV_MISSING);
    assert_eq!(found(&s, "nope").0, REFKV_MISSING);
    let c = CString::new("k/small").unwrap();
    assert_eq!(unsafe { refkv_phkv_key_found(s.0, c.as_ptr(), null_mut(), null_mut()) }, REFKV_USAGE);
    assert_eq!(unsafe { refkv_phkv_key_found(s.0, null(), &mut 0, &mut 0) }, REFKV_USAGE);
}

#[test]
fn reads_windows_of_key_sources() {
    let (s, _dir) = Open::new(&write_store());
    assert_eq!(key_bytes(&s, "k/small", 1, 6).unwrap(), b"mall-");
    assert_eq!(key_bytes(&s, "k/big", 100, 140).unwrap(), big()[100..140]);
    assert_eq!(key_bytes(&s, "k/big", 0, 10_000).unwrap(), big());
    assert_eq!(key_bytes(&s, "k/zstd", 10, 30).unwrap(), zstd()[10..30]);
    assert_eq!(key_bytes(&s, "k/zstd", 0, 8_000).unwrap(), zstd());
    assert_eq!(key_bytes(&s, "k/big", 5, 5).unwrap(), b"");
    // past the end, a reference and a missing key are failures of the read
    assert!(key_bytes(&s, "k/big", 9_990, 10_001).is_err());
    assert!(key_bytes(&s, "k/small", 0, 12).is_err());
    assert!(key_bytes(&s, "r/small", 0, 1).is_err());
    assert!(key_bytes(&s, "nope", 0, 1).is_err());
}

#[test]
fn the_native_resolver_agrees_on_the_values_the_java_tests_expect() {
    let (s, _dir) = Open::new(&write_store());
    let get = |key: &str| {
        let c = CString::new(key).unwrap();
        let mut out = null_mut();
        assert_eq!(unsafe { refkv_phkv_get(s.0, c.as_ptr(), REFKV_REQUEST_WHOLE, 0, 0, &mut out) }, REFKV_BYTES, "{key}");
        take(out)
    };
    let (b, z) = (big(), zstd());
    assert_eq!(get("r/small"), b"mall-");
    assert_eq!(get("r/big"), [&b[100..140], &b[9990..10000]].concat());
    assert_eq!(get("r/zstd"), z[10..30]);
    assert_eq!(get("r/crc"), b[200..300]);
    assert_eq!(get("r/mixed"), [&b"small"[..], b"|", &b[5000..5010], &z[0..4], b"2345", b"value"].concat());
}
