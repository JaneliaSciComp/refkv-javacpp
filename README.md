# refkv-javacpp

Java bindings to [refkv](https://github.com/d-v-b/refkv), built with [JavaCPP](https://github.com/bytedeco/javacpp),
and a read-only [N5](https://github.com/saalfeldlab/n5) `KeyValueAccess` over refkv stores, so that N5 readers
(Zarr, OME-Zarr) read foreign file formats (TIFF, HDF5, MRC, N5, CZI, ND2, ...) without copying them.

refkv *zarrifies* a file into a Zarr hierarchy whose chunks are references to byte ranges of the original file,
saved as one small archive. N5 reads that hierarchy and the chunks come from the original file on demand.

```java
// 1. zarrify (a path or http(s) URL of a file or store) into a phkv store
Zarrify.convert("volume.mrc", "volume.phkv", Zarrify.options().format(Zarrify.Container.PHKV));

// 2. open it, and give N5 a KeyValueAccess over it
try (PhkvStore store = PhkvStore.open("volume.phkv")) {
    N5Reader reader = new ZarrV3KeyValueReader(new RefkvKeyValueAccess(store), "", new GsonBuilder(), false);
    DatasetAttributes attrs = reader.getDatasetAttributes("0");
    DataBlock<?> block = reader.readBlock("0", attrs, 0, 0);   // read from volume.mrc
}
```

## Layout

| | |
|---|---|
| `refkv/` | git submodule: refkv itself, pinned to a commit |
| `capi/` | Rust crate: a C ABI over refkv's zarrify pipeline and phkv reader (refkv has no C API of its own). `build.rs` generates `capi/include/refkv.h` with cbindgen |
| `java/` | Maven project: JavaCPP presets, the generated raw bindings (`global/refkv.java`, committed), the Java API (`Zarrify`, `PhkvStore`) and `RefkvKeyValueAccess` |

JavaCPP binds C and C++, and refkv is Rust, so the path is Rust core, `capi` (`extern "C"`), `refkv.h`,
JavaCPP-generated JNI, and Java. `capi` reuses the modules of refkv's `vzip` CLI by path, since that crate is a
binary with nothing to depend on; its `convert` driver is a copy of the CLI's, and needs checking when the
submodule moves.

## Build

Everything comes from [pixi](https://pixi.sh) (OpenJDK 21, Maven, Rust, clang, CMake):

```sh
git submodule update --init
pixi run test          # builds capi, then the JNI library, then runs the Java tests
pixi run java-gen      # after changing capi's C API: regenerate global/refkv.java from the header
```

## What reads what

- **Zarrify** writes a vzip archive (the default), a standalone phkv store, or a *hybrid* (a vzip archive that
  also carries a phkv container).
- **`PhkvStore`** reads phkv stores and hybrids, not plain vzip archives: refkv's Rust core has a vzip writer but
  no reader. Zarrify to `PHKV` or `HYBRID` for Java to read.
- References are resolved from `file:` and `http(s):` sources (relative ones against the store's own file), other
  keys of the store and literal data, with pins and checksums checked. http(s) reads are under refkv's reader
  policy: loopback, private and link-local hosts are refused unless `allowPrivateHosts`.
- `RefkvKeyValueAccess` is read-only. Directories are key prefixes.

## Status

macOS arm64 only so far (`pixi.toml`'s `platforms`); the C ABI is portable, but only that platform is built and tested.
refkv itself is a proof of concept with no stability promise before 1.0.
