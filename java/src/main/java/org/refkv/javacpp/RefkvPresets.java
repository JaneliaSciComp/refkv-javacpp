package org.refkv.javacpp;

import org.bytedeco.javacpp.annotation.Platform;
import org.bytedeco.javacpp.annotation.Properties;
import org.bytedeco.javacpp.tools.Info;
import org.bytedeco.javacpp.tools.InfoMap;
import org.bytedeco.javacpp.tools.InfoMapper;

/**
 * JavaCPP configuration for {@code capi/include/refkv.h}, the C ABI over refkv's Rust core.
 * The parser generates {@code org.refkv.javacpp.global.refkv} from it (the raw bindings; the
 * classes beside it are the idiomatic API).
 *
 * <p>The header and library directories come from the build ({@code -Dorg.bytedeco.javacpp.*} or the
 * javacpp Maven plugin's {@code includePaths} and {@code linkPaths}), not from here.
 */
@Properties(
    target = "org.refkv.javacpp.global.refkv",
    value = @Platform(include = "refkv.h", link = "refkv_capi"))
public class RefkvPresets implements InfoMapper {
    @Override
    public void map(InfoMap infoMap) {
        infoMap
            .put(new Info("int32_t").cast().valueTypes("int").pointerTypes("IntPointer", "IntBuffer", "int[]"))
            .put(new Info("uint8_t").cast().valueTypes("byte").pointerTypes("BytePointer", "ByteBuffer", "byte[]"))
            .put(new Info("uint64_t").cast().valueTypes("long").pointerTypes("LongPointer", "LongBuffer", "long[]"));
    }
}
