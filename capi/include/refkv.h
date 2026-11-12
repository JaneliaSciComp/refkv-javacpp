#ifndef REFKV_H
#define REFKV_H

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// Success.
#define REFKV_OK 0

// Any other failure (an I/O or network error, a malformed store, a panic).
#define REFKV_ERROR 1

// A call the library cannot make as given (a bad argument).
#define REFKV_USAGE 2

// The input is not one the zarrifiers accept.
#define REFKV_REJECTED 3

// The reader policy refuses the input, or a source the archive would name.
#define REFKV_REFUSED 4

// `refkv_phkv_fetch`: the key is not in the store.
#define REFKV_MISSING 5

// `refkv_phkv_fetch`: the value is bytes.
#define REFKV_BYTES 6

// `refkv_phkv_fetch`: the value is a reference (1 `Range`, 2 `Concat`), its payload in the buffer.
#define REFKV_REFERENCE 7

// `refkv_options_set_format`: a vzip archive.
#define REFKV_FORMAT_VZIP 0

// `refkv_options_set_format`: a standalone phkv store.
#define REFKV_FORMAT_PHKV 1

// `refkv_options_set_format`: a vzip archive that also carries a phkv container.
#define REFKV_FORMAT_HYBRID 2

// `refkv_phkv_fetch`: the whole value.
#define REFKV_REQUEST_WHOLE 0

// `refkv_phkv_fetch`: bytes `[a, b)`.
#define REFKV_REQUEST_RANGE 1

// `refkv_phkv_fetch`: from `a` to the end.
#define REFKV_REQUEST_OFFSET 2

// `refkv_phkv_fetch`: the last `a` bytes.
#define REFKV_REQUEST_SUFFIX 3

// Bytes a function of this library gives the caller. Free with `refkv_buffer_free`.
typedef struct RefkvBuffer RefkvBuffer;

// The options of `refkv_zarrify`. Create with `refkv_options_new`; free with `refkv_options_free`.
typedef struct RefkvOptions RefkvOptions;

// An opened phkv store (a standalone one, or a vzip archive that carries one), read from a file.
// Its functions may be called from several threads; they take turns.
typedef struct RefkvPhkv RefkvPhkv;

// A list of strings. Free with `refkv_strings_free`.
typedef struct RefkvStrings RefkvStrings;

#ifdef __cplusplus
extern "C" {
#endif // __cplusplus

// The message of the last failure on this thread, "" if none. Valid until the thread's next call
// into this library; do not free it.
const char *refkv_last_error(void);

// The version of this library.
const char *refkv_version(void);

// Frees a string a function of this library gave the caller.
void refkv_string_free(char *s);

RefkvOptions *refkv_options_new(void);

void refkv_options_free(RefkvOptions *o);

// The URL the archive names as the source of the input, which the input is served from (default:
// the input's location). A local directory input needs it.
int32_t refkv_options_set_url(RefkvOptions *o, const char *url);

// Records the CRC-32C of every range of a url source, which reads every byte the archive references.
int32_t refkv_options_set_checksums(RefkvOptions *o,
                                    bool on);

// UNSAFE: reads hosts that are, or resolve to, loopback, private or link-local addresses.
int32_t refkv_options_set_allow_private_hosts(RefkvOptions *o, bool on);

// Adds a path a file input may read besides itself (spec/zarrify.md section 1.9). With none, the
// input's directory is in scope.
int32_t refkv_options_add_scope(RefkvOptions *o, const char *path);

// Requests at a time to a remote file or store; 0 is the default.
int32_t refkv_options_set_connections(RefkvOptions *o, size_t n);

// Seconds per megabyte the read planner charges for bytes fetched; NaN or negative is the default.
int32_t refkv_options_set_byte_cost(RefkvOptions *o, double seconds_per_mb);

// Threads that read a store's objects; 0 is one.
int32_t refkv_options_set_workers(RefkvOptions *o, size_t n);

// The container written: `REFKV_FORMAT_VZIP` (the default), `_PHKV` or `_HYBRID`.
int32_t refkv_options_set_format(RefkvOptions *o, int32_t format);

// Zarrifies `input` (a file or a store: a path or an http(s) URL) and writes the archive to `out`.
// `options` may be NULL for the defaults. On success, `*summary_json` is a JSON object
// (`{"format": ..., ...}`) the caller frees with `refkv_string_free`; `summary_json` may be NULL.
//
// Blocks for as long as the conversion takes, and reads the network for a remote input.
int32_t refkv_zarrify(const char *input,
                      const char *out,
                      const RefkvOptions *options,
                      char **summary_json);

// The bytes of a buffer; valid until the buffer is freed. NULL for a NULL buffer.
const uint8_t *refkv_buffer_data(const RefkvBuffer *b);

size_t refkv_buffer_len(const RefkvBuffer *b);

// For a reference fetched by `refkv_phkv_fetch`: 1 for a `Range`, 2 for a `Concat`; else 0.
uint8_t refkv_buffer_kind(const RefkvBuffer *b);

void refkv_buffer_free(RefkvBuffer *b);

size_t refkv_strings_len(const RefkvStrings *s);

// The `i`th string, valid until the list is freed; NULL if out of range.
const char *refkv_strings_get(const RefkvStrings *s, size_t i);

void refkv_strings_free(RefkvStrings *s);

// Opens the phkv store in the file at `path`. On success, `*out` is the store, to be closed with
// `refkv_phkv_close`.
int32_t refkv_phkv_open(const char *path, RefkvPhkv **out);

void refkv_phkv_close(RefkvPhkv *s);

// The number of keys, hidden ones included; 0 for a NULL store.
uint64_t refkv_phkv_key_count(const RefkvPhkv *s);

// UNSAFE: lets `refkv_phkv_get` read http(s) sources at loopback, private or link-local addresses.
// By default it refuses them (spec/refkv.md section 6.7).
int32_t refkv_phkv_set_allow_private_hosts(const RefkvPhkv *s, bool on);

// UNSAFE, Windows only: lets `refkv_phkv_get` read a `file:` source that names a UNC server (`file://server/share/x`).
// Reading one connects to that server, which may send it the user's credentials. By default it refuses them
// (spec/refkv.md section 5). On other systems such a URL has no mapping and is refused whatever this is set to.
int32_t refkv_phkv_set_allow_file_hosts(const RefkvPhkv *s,
                                        bool on);

// The kind of the value at `key`: `REFKV_MISSING`, `REFKV_BYTES` or `REFKV_REFERENCE`, or a failure.
int32_t refkv_phkv_classify(const RefkvPhkv *s,
                            const char *key);

// Reads the value at `key`, `request` being a `REFKV_REQUEST_*` with its arguments `a` and `b`.
// Returns `REFKV_MISSING` (no buffer), `REFKV_BYTES` (`*out` holds the requested window) or
// `REFKV_REFERENCE` (`*out` holds the reference's payload, its kind by `refkv_buffer_kind`; this
// library does not resolve references), or a failure. Free the buffer with `refkv_buffer_free`.
int32_t refkv_phkv_fetch(const RefkvPhkv *s,
                         const char *key,
                         int32_t request,
                         uint64_t a,
                         uint64_t b,
                         RefkvBuffer **out);

// The stored bytes of `key` (a reference's payload), undecoded; hidden keys included.
// `REFKV_MISSING` or `REFKV_BYTES` with `*out` set, or a failure.
int32_t refkv_phkv_raw(const RefkvPhkv *s, const char *key, RefkvBuffer **out);

// The keys that are not hidden and start with `prefix` ("" for all), in UTF-8 order.
int32_t refkv_phkv_list(const RefkvPhkv *s, const char *prefix, RefkvStrings **out);

// How `key`'s value is stored, as a JSON object (`{"kind": "bytes", "codec": ..., "storedLength": ...,
// "size": ..., "crc32c": ..., "hidden": ...}` or `{"kind": "reference", "payloadLength": ..., "hidden": ...}`),
// in `*json` for the caller to free with `refkv_string_free`; `REFKV_MISSING` if there is no such key.
int32_t refkv_phkv_entry_info(const RefkvPhkv *s,
                              const char *key,
                              char **json);

// UNCHECKED, for cross-checking a host's own resolution: no reader policy applies to `file:` sources (any a store names
// is read), and over http(s) only a size pin is checked, and only if the response gives a total; `etag` and
// `modified_not_after` pins are ignored, redirects are not followed and `Content-Range` is not validated. A host
// should resolve references itself, from `refkv_phkv_fetch`, `refkv_phkv_sources` and `refkv_phkv_key_bytes`.
//
// Like `refkv_phkv_fetch`, but a reference is resolved: its ranges are read from the sources the
// store names (`file:` and `http(s)` URLs, relative ones against the store's file, other keys of
// the store, and literal data), and checked against their pins and checksums. Returns
// `REFKV_MISSING` (no buffer) or `REFKV_BYTES` (`*out` holds the value's requested window), or a
// failure. Reading a remote source takes a network round trip, so this can block.
int32_t refkv_phkv_get(const RefkvPhkv *s,
                       const char *key,
                       int32_t request,
                       uint64_t a,
                       uint64_t b,
                       RefkvBuffer **out);

// The store's source table (spec/refkv.md section 5), encoded as a `SourceTable` message, for a host that resolves
// references itself: with `refkv_phkv_fetch`, which gives a reference's payload, and `refkv_phkv_key_found` and
// `refkv_phkv_key_bytes`, which read the `key` sources it names. Free the buffer with `refkv_buffer_free`.
int32_t refkv_phkv_sources(const RefkvPhkv *s,
                           RefkvBuffer **out);

// Looks up the bytes value that a `key` source names, hidden or not, for one `get` (spec/refkv.md section 6.7 rule 5).
// `REFKV_OK`, with `*size` its size and `*held` the bytes that reading a window of it holds in memory (the larger of
// its size and its stored bytes) when the reader holds it whole, which it does for a compressed or inline value, or
// `UINT64_MAX` when a window is read by itself; or `REFKV_MISSING` if the key is missing or is a reference.
int32_t refkv_phkv_key_found(const RefkvPhkv *s,
                             const char *key,
                             uint64_t *size,
                             uint64_t *held);

// Bytes `[start, end)` of the bytes value at `key`, hidden or not: what a `key` source of a range reads. Every failure
// is a failure of the read, which the host reports as a resolution error. Free the buffer with `refkv_buffer_free`.
int32_t refkv_phkv_key_bytes(const RefkvPhkv *s,
                             const char *key,
                             uint64_t start,
                             uint64_t end,
                             RefkvBuffer **out);

// The size in bytes of the value at `key`, as `refkv_phkv_get` would give it whole, without reading
// it: a bytes value's size, or the sum of a reference's ranges. `REFKV_MISSING` (`*size` unset) or
// `REFKV_OK` with `*size` set, or a failure.
int32_t refkv_phkv_size(const RefkvPhkv *s, const char *key, uint64_t *size);

#ifdef __cplusplus
}  // extern "C"
#endif  // __cplusplus

#endif  /* REFKV_H */
