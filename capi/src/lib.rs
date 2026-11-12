//! A C ABI over refkv's zarrify pipeline and phkv reader, for JavaCPP.
//!
//! Conventions:
//! - Functions that can fail return a status: `REFKV_OK` (0), or a failure, whose message
//!   `refkv_last_error` gives (valid on the calling thread until its next call into this library).
//! - Objects are opaque handles, each freed by its own `*_free`/`*_close`; freeing NULL is a no-op.
//! - Strings in are NUL-terminated UTF-8. Strings out are owned by their handle or, for
//!   `char *` results, by the caller, who frees them with `refkv_string_free`.
//! - A panic never crosses the boundary: it becomes `REFKV_ERROR`.
#![allow(dead_code, clippy::missing_safety_doc)]

#[path = "../../refkv/rust/refkv-cli/src/http.rs"]
mod http;
#[path = "../../refkv/rust/refkv-cli/src/output.rs"]
mod output;
#[path = "../../refkv/rust/refkv-cli/src/policy.rs"]
mod policy;
#[path = "../../refkv/rust/refkv-cli/src/pyjson.rs"]
mod pyjson;
#[path = "../../refkv/rust/refkv-cli/src/run.rs"]
mod run;
#[path = "../../refkv/rust/refkv-cli/src/store.rs"]
mod store;
#[path = "../../refkv/rust/refkv-cli/src/transport.rs"]
mod transport;
#[path = "../../refkv/rust/refkv-cli/src/vsi.rs"]
mod vsi;
#[path = "../../refkv/rust/refkv-cli/src/xml.rs"]
mod xml;
mod convert;
mod resolver;

use refkv_phkv::{EntryInfo, Fetched, Kind, Request, Store};
use std::cell::RefCell;
use std::ffi::{CStr, CString, c_char};
use std::fs::File;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::Mutex;

/// How a conversion fails (the status `vzip convert` exits with, as the module `refkv-cli` has it).
#[derive(Debug)]
pub enum Fail {
    Rejected(String),
    Refused(String),
    Usage(String),
    Other(String),
}

impl From<refkv_ir::seal::Refusal> for Fail {
    fn from(r: refkv_ir::seal::Refusal) -> Fail {
        match r {
            refkv_ir::seal::Refusal::Rejected(e) => Fail::Rejected(e),
            refkv_ir::seal::Refusal::Failed(e) => Fail::Other(e),
        }
    }
}

/// Success.
pub const REFKV_OK: i32 = 0;
/// Any other failure (an I/O or network error, a malformed store, a panic).
pub const REFKV_ERROR: i32 = 1;
/// A call the library cannot make as given (a bad argument).
pub const REFKV_USAGE: i32 = 2;
/// The input is not one the zarrifiers accept.
pub const REFKV_REJECTED: i32 = 3;
/// The reader policy refuses the input, or a source the archive would name.
pub const REFKV_REFUSED: i32 = 4;

/// `refkv_phkv_fetch`: the key is not in the store.
pub const REFKV_MISSING: i32 = 5;
/// `refkv_phkv_fetch`: the value is bytes.
pub const REFKV_BYTES: i32 = 6;
/// `refkv_phkv_fetch`: the value is a reference (1 `Range`, 2 `Concat`), its payload in the buffer.
pub const REFKV_REFERENCE: i32 = 7;

/// `refkv_options_set_format`: a vzip archive.
pub const REFKV_FORMAT_VZIP: i32 = 0;
/// `refkv_options_set_format`: a standalone phkv store.
pub const REFKV_FORMAT_PHKV: i32 = 1;
/// `refkv_options_set_format`: a vzip archive that also carries a phkv container.
pub const REFKV_FORMAT_HYBRID: i32 = 2;

/// `refkv_phkv_fetch`: the whole value.
pub const REFKV_REQUEST_WHOLE: i32 = 0;
/// `refkv_phkv_fetch`: bytes `[a, b)`.
pub const REFKV_REQUEST_RANGE: i32 = 1;
/// `refkv_phkv_fetch`: from `a` to the end.
pub const REFKV_REQUEST_OFFSET: i32 = 2;
/// `refkv_phkv_fetch`: the last `a` bytes.
pub const REFKV_REQUEST_SUFFIX: i32 = 3;

thread_local! {
    static LAST_ERROR: RefCell<CString> = RefCell::new(CString::default());
}

fn fail(code: i32, message: impl Into<String>) -> i32 {
    let m = message.into().replace('\0', "?");
    LAST_ERROR.with(|e| *e.borrow_mut() = CString::new(m).unwrap_or_default());
    code
}

impl From<Fail> for (i32, String) {
    fn from(f: Fail) -> (i32, String) {
        match f {
            Fail::Rejected(m) => (REFKV_REJECTED, m),
            Fail::Refused(m) => (REFKV_REFUSED, m),
            Fail::Usage(m) => (REFKV_USAGE, m),
            Fail::Other(m) => (REFKV_ERROR, m),
        }
    }
}

type Failure = (i32, String);

fn error(m: impl std::fmt::Display) -> Failure {
    (REFKV_ERROR, m.to_string())
}

fn usage(m: impl Into<String>) -> Failure {
    (REFKV_USAGE, m.into())
}

/// Runs `f`, turning its failure or panic into a status, and its message into the last error.
fn guard(f: impl FnOnce() -> Result<i32, Failure>) -> i32 {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(code)) => code,
        Ok(Err((code, m))) => fail(code, m),
        Err(p) => {
            let m = p.downcast_ref::<&str>().map(|s| s.to_string()).or_else(|| p.downcast_ref::<String>().cloned());
            fail(REFKV_ERROR, format!("internal panic: {}", m.unwrap_or_default()))
        }
    }
}

unsafe fn str_arg<'a>(p: *const c_char, what: &str) -> Result<&'a str, Failure> {
    if p.is_null() {
        return Err(usage(format!("{what} is NULL")));
    }
    unsafe { CStr::from_ptr(p) }.to_str().map_err(|_| usage(format!("{what} is not UTF-8")))
}

unsafe fn handle<'a, T>(p: *const T, what: &str) -> Result<&'a T, Failure> {
    unsafe { p.as_ref() }.ok_or_else(|| usage(format!("{what} is NULL")))
}

fn out_string(s: String) -> *mut c_char {
    CString::new(s.replace('\0', "\\u0000")).unwrap_or_default().into_raw()
}

/// The message of the last failure on this thread, "" if none. Valid until the thread's next call
/// into this library; do not free it.
#[unsafe(no_mangle)]
pub extern "C" fn refkv_last_error() -> *const c_char {
    LAST_ERROR.with(|e| e.borrow().as_ptr())
}

/// The version of this library.
#[unsafe(no_mangle)]
pub extern "C" fn refkv_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr().cast()
}

/// Frees a string a function of this library gave the caller.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_string_free(s: *mut c_char) {
    if !s.is_null() {
        drop(unsafe { CString::from_raw(s) });
    }
}

// ---- zarrify ----

/// The options of `refkv_zarrify`. Create with `refkv_options_new`; free with `refkv_options_free`.
pub struct RefkvOptions(convert::Args);

#[unsafe(no_mangle)]
pub extern "C" fn refkv_options_new() -> *mut RefkvOptions {
    Box::into_raw(Box::new(RefkvOptions(convert::Args { workers: store::WORKERS, ..Default::default() })))
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_free(o: *mut RefkvOptions) {
    if !o.is_null() {
        drop(unsafe { Box::from_raw(o) });
    }
}

unsafe fn opts<'a>(o: *mut RefkvOptions) -> Result<&'a mut convert::Args, Failure> {
    unsafe { o.as_mut() }.map(|o| &mut o.0).ok_or_else(|| usage("options is NULL"))
}

/// The URL the archive names as the source of the input, which the input is served from (default:
/// the input's location). A local directory input needs it.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_set_url(o: *mut RefkvOptions, url: *const c_char) -> i32 {
    guard(|| {
        let o = unsafe { opts(o) }?;
        o.url = if url.is_null() { None } else { Some(unsafe { str_arg(url, "url") }?.to_string()) };
        Ok(REFKV_OK)
    })
}

/// Records the CRC-32C of every range of a url source, which reads every byte the archive references.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_set_checksums(o: *mut RefkvOptions, on: bool) -> i32 {
    guard(|| Ok({ unsafe { opts(o) }?.checksums = on; REFKV_OK }))
}

/// UNSAFE: reads hosts that are, or resolve to, loopback, private or link-local addresses.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_set_allow_private_hosts(o: *mut RefkvOptions, on: bool) -> i32 {
    guard(|| Ok({ unsafe { opts(o) }?.allow_private_hosts = on; REFKV_OK }))
}

/// Adds a path a file input may read besides itself (spec/zarrify.md section 1.9). With none, the
/// input's directory is in scope.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_add_scope(o: *mut RefkvOptions, path: *const c_char) -> i32 {
    guard(|| {
        let p = unsafe { str_arg(path, "path") }?.to_string();
        unsafe { opts(o) }?.scope.get_or_insert_with(Vec::new).push(p);
        Ok(REFKV_OK)
    })
}

/// Requests at a time to a remote file or store; 0 is the default.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_set_connections(o: *mut RefkvOptions, n: usize) -> i32 {
    guard(|| Ok({ unsafe { opts(o) }?.connections = (n > 0).then_some(n); REFKV_OK }))
}

/// Seconds per megabyte the read planner charges for bytes fetched; NaN or negative is the default.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_set_byte_cost(o: *mut RefkvOptions, seconds_per_mb: f64) -> i32 {
    guard(|| Ok({ unsafe { opts(o) }?.byte_cost = (seconds_per_mb >= 0.0).then(|| seconds_per_mb * 1e-6); REFKV_OK }))
}

/// Threads that read a store's objects; 0 is one.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_set_workers(o: *mut RefkvOptions, n: usize) -> i32 {
    guard(|| Ok({ unsafe { opts(o) }?.workers = n.max(1); REFKV_OK }))
}

/// The container written: `REFKV_FORMAT_VZIP` (the default), `_PHKV` or `_HYBRID`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_options_set_format(o: *mut RefkvOptions, format: i32) -> i32 {
    guard(|| {
        let f = match format {
            REFKV_FORMAT_VZIP => output::Container::Vzip,
            REFKV_FORMAT_PHKV => output::Container::Phkv,
            REFKV_FORMAT_HYBRID => output::Container::Hybrid,
            n => return Err(usage(format!("{n} is not a REFKV_FORMAT_*"))),
        };
        unsafe { opts(o) }?.format = f;
        Ok(REFKV_OK)
    })
}

/// Zarrifies `input` (a file or a store: a path or an http(s) URL) and writes the archive to `out`.
/// `options` may be NULL for the defaults. On success, `*summary_json` is a JSON object
/// (`{"format": ..., ...}`) the caller frees with `refkv_string_free`; `summary_json` may be NULL.
///
/// Blocks for as long as the conversion takes, and reads the network for a remote input.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_zarrify(input: *const c_char, out: *const c_char, options: *const RefkvOptions, summary_json: *mut *mut c_char) -> i32 {
    guard(|| {
        let mut a = match unsafe { options.as_ref() } {
            Some(o) => convert::Args { input: String::new(), out: String::new(), url: o.0.url.clone(), scope: o.0.scope.clone(), ..o.0 },
            None => convert::Args { workers: store::WORKERS, ..Default::default() },
        };
        a.input = unsafe { str_arg(input, "input") }?.to_string();
        a.out = unsafe { str_arg(out, "out") }?.to_string();
        let info = convert::convert(&a).map_err(Failure::from)?;
        if !summary_json.is_null() {
            unsafe { *summary_json = out_string(pyjson::dumps(&info)) };
        }
        Ok(REFKV_OK)
    })
}

// ---- buffers and string lists ----

/// Bytes a function of this library gives the caller. Free with `refkv_buffer_free`.
pub struct RefkvBuffer {
    data: Vec<u8>,
    kind: u8,
}

/// The bytes of a buffer; valid until the buffer is freed. NULL for a NULL buffer.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_buffer_data(b: *const RefkvBuffer) -> *const u8 {
    unsafe { b.as_ref() }.map_or(std::ptr::null(), |b| b.data.as_ptr())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_buffer_len(b: *const RefkvBuffer) -> usize {
    unsafe { b.as_ref() }.map_or(0, |b| b.data.len())
}

/// For a reference fetched by `refkv_phkv_fetch`: 1 for a `Range`, 2 for a `Concat`; else 0.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_buffer_kind(b: *const RefkvBuffer) -> u8 {
    unsafe { b.as_ref() }.map_or(0, |b| b.kind)
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_buffer_free(b: *mut RefkvBuffer) {
    if !b.is_null() {
        drop(unsafe { Box::from_raw(b) });
    }
}

fn buffer(data: Vec<u8>, kind: u8) -> *mut RefkvBuffer {
    Box::into_raw(Box::new(RefkvBuffer { data, kind }))
}

/// A list of strings. Free with `refkv_strings_free`.
pub struct RefkvStrings(Vec<CString>);

#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_strings_len(s: *const RefkvStrings) -> usize {
    unsafe { s.as_ref() }.map_or(0, |s| s.0.len())
}

/// The `i`th string, valid until the list is freed; NULL if out of range.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_strings_get(s: *const RefkvStrings, i: usize) -> *const c_char {
    unsafe { s.as_ref() }.and_then(|s| s.0.get(i)).map_or(std::ptr::null(), |s| s.as_ptr())
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_strings_free(s: *mut RefkvStrings) {
    if !s.is_null() {
        drop(unsafe { Box::from_raw(s) });
    }
}

// ---- phkv ----

/// An opened phkv store (a standalone one, or a vzip archive that carries one), read from a file.
/// Its functions may be called from several threads; they take turns.
pub struct RefkvPhkv {
    store: Mutex<Store<File>>,
    /// the file's URI: what relative url sources are resolved against
    base: String,
    client: Mutex<std::sync::Arc<http::Client>>,
}

unsafe fn store_of<'a>(s: *const RefkvPhkv) -> Result<std::sync::MutexGuard<'a, Store<File>>, Failure> {
    let s = unsafe { handle(s, "store") }?;
    Ok(s.store.lock().unwrap_or_else(|e| e.into_inner()))
}

fn new_client(allow_private_hosts: bool) -> std::sync::Arc<http::Client> {
    http::Client::new(policy::Policy { allow_private_hosts }, std::time::Duration::from_secs(60))
}

fn read_err(e: refkv_phkv::ReadError) -> Failure {
    error(e)
}

/// Opens the phkv store in the file at `path`. On success, `*out` is the store, to be closed with
/// `refkv_phkv_close`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_open(path: *const c_char, out: *mut *mut RefkvPhkv) -> i32 {
    guard(|| {
        let p = unsafe { str_arg(path, "path") }?;
        if out.is_null() {
            return Err(usage("out is NULL"));
        }
        let f = File::open(p).map_err(|e| error(format!("{p}: {e}")))?;
        let s = Store::open(f).map_err(read_err)?;
        let base = refkv_vzip::uri::file_uri(std::path::Path::new(p)).map_err(error)?;
        let handle = RefkvPhkv { store: Mutex::new(s), base, client: Mutex::new(new_client(false)) };
        unsafe { *out = Box::into_raw(Box::new(handle)) };
        Ok(REFKV_OK)
    })
}

#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_close(s: *mut RefkvPhkv) {
    if !s.is_null() {
        drop(unsafe { Box::from_raw(s) });
    }
}

/// The number of keys, hidden ones included; 0 for a NULL store.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_key_count(s: *const RefkvPhkv) -> u64 {
    unsafe { store_of(s) }.map_or(0, |s| s.key_count())
}

/// UNSAFE: lets `refkv_phkv_get` read http(s) sources at loopback, private or link-local addresses.
/// By default it refuses them (spec/refkv.md section 6.7).
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_set_allow_private_hosts(s: *const RefkvPhkv, on: bool) -> i32 {
    guard(|| {
        let s = unsafe { handle(s, "store") }?;
        *s.client.lock().unwrap_or_else(|e| e.into_inner()) = new_client(on);
        Ok(REFKV_OK)
    })
}

/// The kind of the value at `key`: `REFKV_MISSING`, `REFKV_BYTES` or `REFKV_REFERENCE`, or a failure.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_classify(s: *const RefkvPhkv, key: *const c_char) -> i32 {
    guard(|| {
        let key = unsafe { str_arg(key, "key") }?;
        let s = unsafe { store_of(s) }?;
        Ok(match s.classify(key).map_err(read_err)? {
            None => REFKV_MISSING,
            Some(Kind::Bytes) => REFKV_BYTES,
            Some(Kind::Reference) => REFKV_REFERENCE,
        })
    })
}

/// Reads the value at `key`, `request` being a `REFKV_REQUEST_*` with its arguments `a` and `b`.
/// Returns `REFKV_MISSING` (no buffer), `REFKV_BYTES` (`*out` holds the requested window) or
/// `REFKV_REFERENCE` (`*out` holds the reference's payload, its kind by `refkv_buffer_kind`; this
/// library does not resolve references), or a failure. Free the buffer with `refkv_buffer_free`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_fetch(s: *const RefkvPhkv, key: *const c_char, request: i32, a: u64, b: u64, out: *mut *mut RefkvBuffer) -> i32 {
    guard(|| {
        let key = unsafe { str_arg(key, "key") }?;
        if out.is_null() {
            return Err(usage("out is NULL"));
        }
        let req = match request {
            REFKV_REQUEST_WHOLE => Request::Whole,
            REFKV_REQUEST_RANGE => Request::Range(a, b),
            REFKV_REQUEST_OFFSET => Request::Offset(a),
            REFKV_REQUEST_SUFFIX => Request::Suffix(a),
            n => return Err(usage(format!("{n} is not a REFKV_REQUEST_*"))),
        };
        let s = unsafe { store_of(s) }?;
        Ok(match s.fetch(key, req).map_err(read_err)? {
            None => REFKV_MISSING,
            Some(Fetched::Bytes(v)) => {
                unsafe { *out = buffer(v, 0) };
                REFKV_BYTES
            }
            Some(Fetched::Reference { kind, payload }) => {
                unsafe { *out = buffer(payload, kind) };
                REFKV_REFERENCE
            }
        })
    })
}

/// The stored bytes of `key` (a reference's payload), undecoded; hidden keys included.
/// `REFKV_MISSING` or `REFKV_BYTES` with `*out` set, or a failure.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_raw(s: *const RefkvPhkv, key: *const c_char, out: *mut *mut RefkvBuffer) -> i32 {
    guard(|| {
        let key = unsafe { str_arg(key, "key") }?;
        if out.is_null() {
            return Err(usage("out is NULL"));
        }
        let s = unsafe { store_of(s) }?;
        Ok(match s.raw(key).map_err(read_err)? {
            None => REFKV_MISSING,
            Some(v) => {
                unsafe { *out = buffer(v, 0) };
                REFKV_BYTES
            }
        })
    })
}

/// The keys that are not hidden and start with `prefix` ("" for all), in UTF-8 order.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_list(s: *const RefkvPhkv, prefix: *const c_char, out: *mut *mut RefkvStrings) -> i32 {
    guard(|| {
        let prefix = unsafe { str_arg(prefix, "prefix") }?;
        if out.is_null() {
            return Err(usage("out is NULL"));
        }
        let keys = unsafe { store_of(s) }?.list(prefix).map_err(read_err)?;
        let keys = keys.into_iter().map(|k| CString::new(k).map_err(|_| error("a key holds a NUL"))).collect::<Result<Vec<_>, _>>()?;
        unsafe { *out = Box::into_raw(Box::new(RefkvStrings(keys))) };
        Ok(REFKV_OK)
    })
}

/// How `key`'s value is stored, as a JSON object (`{"kind": "bytes", "codec": ..., "storedLength": ...,
/// "size": ..., "crc32c": ..., "hidden": ...}` or `{"kind": "reference", "payloadLength": ..., "hidden": ...}`),
/// in `*json` for the caller to free with `refkv_string_free`; `REFKV_MISSING` if there is no such key.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_entry_info(s: *const RefkvPhkv, key: *const c_char, json: *mut *mut c_char) -> i32 {
    guard(|| {
        let key = unsafe { str_arg(key, "key") }?;
        if json.is_null() {
            return Err(usage("json is NULL"));
        }
        let s = unsafe { store_of(s) }?;
        Ok(match s.entry_info(key).map_err(read_err)? {
            None => REFKV_MISSING,
            Some(info) => {
                let v = match info {
                    EntryInfo::Bytes { codec, stored_length, size, crc32c, hidden } => serde_json::json!({
                        "kind": "bytes", "codec": format!("{codec:?}").to_lowercase(), "storedLength": stored_length,
                        "size": size, "crc32c": crc32c, "hidden": hidden }),
                    EntryInfo::Reference { payload_length, hidden } => serde_json::json!({
                        "kind": "reference", "payloadLength": payload_length, "hidden": hidden }),
                };
                unsafe { *json = out_string(v.to_string()) };
                REFKV_OK
            }
        })
    })
}

/// Like `refkv_phkv_fetch`, but a reference is resolved: its ranges are read from the sources the
/// store names (`file:` and `http(s)` URLs, relative ones against the store's file, other keys of
/// the store, and literal data), and checked against their pins and checksums. Returns
/// `REFKV_MISSING` (no buffer) or `REFKV_BYTES` (`*out` holds the value's requested window), or a
/// failure. Reading a remote source takes a network round trip, so this can block.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_get(s: *const RefkvPhkv, key: *const c_char, request: i32, a: u64, b: u64, out: *mut *mut RefkvBuffer) -> i32 {
    guard(|| {
        let key = unsafe { str_arg(key, "key") }?;
        if out.is_null() {
            return Err(usage("out is NULL"));
        }
        let req = match request {
            REFKV_REQUEST_WHOLE => Request::Whole,
            REFKV_REQUEST_RANGE => Request::Range(a, b),
            REFKV_REQUEST_OFFSET => Request::Offset(a),
            REFKV_REQUEST_SUFFIX => Request::Suffix(a),
            n => return Err(usage(format!("{n} is not a REFKV_REQUEST_*"))),
        };
        let h = unsafe { handle(s, "store") }?;
        let resolver = resolver::HostResolver { base: h.base.clone(), client: h.client.lock().unwrap_or_else(|e| e.into_inner()).clone() };
        let store = h.store.lock().unwrap_or_else(|e| e.into_inner());
        Ok(match store.get(key, req, &resolver).map_err(read_err)? {
            None => REFKV_MISSING,
            Some(v) => {
                unsafe { *out = buffer(v, 0) };
                REFKV_BYTES
            }
        })
    })
}

/// The size in bytes of the value at `key`, as `refkv_phkv_get` would give it whole, without reading
/// it: a bytes value's size, or the sum of a reference's ranges. `REFKV_MISSING` (`*size` unset) or
/// `REFKV_OK` with `*size` set, or a failure.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn refkv_phkv_size(s: *const RefkvPhkv, key: *const c_char, size: *mut u64) -> i32 {
    guard(|| {
        let key = unsafe { str_arg(key, "key") }?;
        if size.is_null() {
            return Err(usage("size is NULL"));
        }
        let s = unsafe { store_of(s) }?;
        if s.classify(key).map_err(read_err)?.is_none() {
            return Ok(REFKV_MISSING);
        }
        let n = match s.entry_info(key).map_err(read_err)? {
            None => return Ok(REFKV_MISSING),
            Some(EntryInfo::Bytes { size, .. }) => size,
            Some(EntryInfo::Reference { .. }) => {
                let Some(refkv_phkv::Fetched::Reference { kind, payload }) = s.fetch(key, Request::Whole).map_err(read_err)? else {
                    return Err(error("a reference that is not one"));
                };
                let ranges = if kind == 1 {
                    vec![refkv_vzip::pb::Range::decode(&payload).map_err(error)?]
                } else {
                    refkv_vzip::pb::decode_concat(&payload).map_err(error)?
                };
                ranges.iter().map(|r| r.size()).sum()
            }
        };
        unsafe { *size = n };
        Ok(REFKV_OK)
    })
}
