package dev.horizongen;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/** Bindings to horizon-bridge's C ABI. One instance per loaded library. */
final class HorizonNative {
    private static final ValueLayout SIZE_T = JAVA_LONG;

    private final MethodHandle open, close, minY, height, stateCount, stateName,
            biomeCount, biomeName, generate, noiseBiomes, setServerIds, generatePacked;

    HorizonNative(Path library) {
        Linker linker = Linker.nativeLinker();
        SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());
        open = bind(linker, lib, "hz_open",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, SIZE_T));
        close = bind(linker, lib, "hz_close", FunctionDescriptor.ofVoid(ADDRESS));
        minY = bind(linker, lib, "hz_min_y", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        height = bind(linker, lib, "hz_height", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        stateCount = bind(linker, lib, "hz_state_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        stateName = bind(linker, lib, "hz_state_name",
                FunctionDescriptor.of(SIZE_T, ADDRESS, JAVA_INT, ADDRESS, SIZE_T));
        biomeCount = bind(linker, lib, "hz_biome_count", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        biomeName = bind(linker, lib, "hz_biome_name",
                FunctionDescriptor.of(SIZE_T, ADDRESS, JAVA_INT, ADDRESS, SIZE_T));
        generate = bind(linker, lib, "hz_generate",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, SIZE_T, ADDRESS, SIZE_T));
        noiseBiomes = bind(linker, lib, "hz_noise_biomes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T, ADDRESS));
        setServerIds = bind(linker, lib, "hz_set_server_ids",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, SIZE_T));
        generatePacked = bind(linker, lib, "hz_generate_packed",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, SIZE_T, ADDRESS, SIZE_T,
                        ADDRESS, SIZE_T, ADDRESS, SIZE_T));
    }

    private static MethodHandle bind(Linker linker, SymbolLookup lib, String name, FunctionDescriptor fd) {
        MemorySegment symbol = lib.find(name).orElseThrow(() -> new IllegalStateException("missing symbol " + name));
        return linker.downcallHandle(symbol, fd);
    }

    /** A seeded generator for one dimension. Thread safe. */
    final class Generator implements AutoCloseable {
        private final MemorySegment handle;
        final int minY, height;

        private Generator(MemorySegment handle) throws Throwable {
            this.handle = handle;
            this.minY = (int) HorizonNative.this.minY.invokeExact(handle);
            this.height = (int) HorizonNative.this.height.invokeExact(handle);
        }

        int stateCount() throws Throwable {
            return (int) stateCount.invokeExact(handle);
        }

        String stateName(int id) throws Throwable {
            return string((buf, len) -> (long) stateName.invokeExact(handle, id, buf, len));
        }

        int biomeCount() throws Throwable {
            return (int) biomeCount.invokeExact(handle);
        }

        String biomeName(int id) throws Throwable {
            return string((buf, len) -> (long) biomeName.invokeExact(handle, id, buf, len));
        }

        /** Fills {@code blocks} (16*16*height) and {@code biomes} (16*height/4); returns the status. */
        int generate(int cx, int cz, MemorySegment blocks, MemorySegment biomes) throws Throwable {
            return (int) generate.invokeExact(handle, cx, cz,
                    blocks, blocks.byteSize() / 4, biomes, biomes.byteSize() / 2);
        }

        /** Tells Rust the server's block-state id of every Horizon state id, for {@link #generatePacked}. */
        int setServerIds(int[] ids) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment seg = arena.allocateFrom(ValueLayout.JAVA_INT, ids);
                return (int) setServerIds.invokeExact(handle, seg, (long) ids.length);
            }
        }

        /**
         * {@link #generate}, plus every section packed in PalettedContainer's network format into
         * {@code packed}; {@code lens} gets each section's byte length (0 all air, -1 not packed).
         */
        int generatePacked(int cx, int cz, MemorySegment blocks, MemorySegment biomes,
                           MemorySegment packed, MemorySegment lens) throws Throwable {
            return (int) generatePacked.invokeExact(handle, cx, cz, blocks, blocks.byteSize() / 4,
                    biomes, biomes.byteSize() / 2, packed, packed.byteSize(), lens, lens.byteSize() / 4);
        }

        /** Noise biome ids at quart positions ({@code xyz} holds 3*n ints). */
        int noiseBiomes(MemorySegment xyz, long n, MemorySegment out) throws Throwable {
            return (int) noiseBiomes.invokeExact(handle, xyz, n, out);
        }

        @Override
        public void close() {
            try {
                close.invokeExact(handle);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }

    Generator open(Path resources, Path datapacks, long seed, String dimension) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment err = arena.allocate(4096);
            MemorySegment res = arena.allocateFrom(resources.toString());
            MemorySegment packs = datapacks == null ? MemorySegment.NULL : arena.allocateFrom(datapacks.toString());
            MemorySegment dim = arena.allocateFrom(dimension);
            MemorySegment handle = (MemorySegment) open.invokeExact(res, packs, seed, dim, err, err.byteSize());
            if (handle.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("horizon: " + err.getString(0));
            }
            return new Generator(handle);
        }
    }

    @FunctionalInterface
    private interface StringCall {
        long call(MemorySegment buf, long len) throws Throwable;
    }

    private static String string(StringCall call) throws Throwable {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256);
            long n = call.call(buf, buf.byteSize());
            if (n > buf.byteSize()) {
                buf = arena.allocate(n);
                n = call.call(buf, n);
            }
            return new String(buf.asSlice(0, n).toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }
    }
}
