package dev.horizongen;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Terrain (structure starts, biomes, noise, surface, carvers) from Horizon's Rust port;
 * structures, features and mobs from Paper's vanilla code on top, so plugins that hook
 * those keep working.
 */
final class HorizonChunkGenerator extends ChunkGenerator {
    private final HorizonGenPlugin plugin;
    private final HorizonNative natives;
    private final Path resources;
    private final boolean rustBiomes;
    private final int timingEvery;
    private final boolean decorate;
    /** Write straight into chunk sections (FastChunkWriter) instead of through ChunkData. */
    static volatile boolean fastWrite = true;
    private final Map<UUID, WorldGen> worlds = new ConcurrentHashMap<>();
    // Shared by every instance, for the benchmark: native call and copy-into-Paper time.
    private static final AtomicLong chunks = new AtomicLong(), nanos = new AtomicLong(), copyNanos = new AtomicLong(),
            fastChunks = new AtomicLong();

    static void resetTiming() {
        chunks.set(0);
        fastChunks.set(0);
        nanos.set(0);
        copyNanos.set(0);
    }

    /** Average per-chunk times since the last reset, or "" when no Horizon chunk was made. */
    static String timingSummary() {
        long n = chunks.get();
        return n == 0 ? "" : String.format("  (%d Horizon chunks, %d direct: native %.2f ms, copy %.2f ms per chunk, "
                + "summed over threads)", n, fastChunks.get(), nanos.get() / 1e6 / n, copyNanos.get() / 1e6 / n);
    }

    HorizonChunkGenerator(HorizonGenPlugin plugin, HorizonNative natives, Path resources,
                          boolean rustBiomes, int timingEvery) {
        this(plugin, natives, resources, rustBiomes, timingEvery, true);
    }

    /** {@code decorate} false: terrain only, no features or structure pieces (for the self-test). */
    HorizonChunkGenerator(HorizonGenPlugin plugin, HorizonNative natives, Path resources,
                          boolean rustBiomes, int timingEvery, boolean decorate) {
        this.decorate = decorate;
        this.plugin = plugin;
        this.natives = natives;
        this.resources = resources;
        this.rustBiomes = rustBiomes;
        this.timingEvery = timingEvery;
    }

    /** One world's native generator and its Horizon-id to Paper lookups. */
    private record WorldGen(HorizonNative.Generator gen, BlockData[] states, boolean[] air, Biome[] biomes,
                            FastChunkWriter fast) {
    }

    private WorldGen world(WorldInfo info) {
        return worlds.computeIfAbsent(info.getUID(), uid -> {
            try {
                return open(info);
            } catch (Throwable t) {
                throw new IllegalStateException("HorizonGen could not open world " + info.getName(), t);
            }
        });
    }

    private WorldGen open(WorldInfo info) throws Throwable {
        String dimension = switch (info.getEnvironment()) {
            case NETHER -> "minecraft:the_nether";
            case THE_END -> "minecraft:the_end";
            default -> "minecraft:overworld";
        };
        Path datapacks = datapacksFolder();
        long start = System.nanoTime();
        HorizonNative.Generator gen = natives.open(resources, datapacks, info.getSeed(), dimension);
        int count = gen.stateCount();
        BlockData[] states = new BlockData[count];
        boolean[] air = new boolean[count];
        int unknown = 0;
        BlockData fallback = Bukkit.createBlockData("minecraft:stone");
        for (int id = 0; id < count; id++) {
            String name = gen.stateName(id);
            air[id] = name.equals("minecraft:air");
            try {
                states[id] = Bukkit.createBlockData(name);
            } catch (IllegalArgumentException e) {
                states[id] = fallback;
                if (unknown++ < 5) {
                    plugin.getLogger().warning("Paper has no block state " + name);
                }
            }
        }
        Registry<Biome> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        Biome[] biomes = new Biome[gen.biomeCount()];
        for (int id = 0; id < biomes.length; id++) {
            String name = gen.biomeName(id);
            Biome biome = registry.get(NamespacedKey.fromString(name));
            biomes[id] = biome != null ? biome : registry.getOrThrow(NamespacedKey.minecraft("plains"));
        }
        if (gen.minY != info.getMinHeight() || gen.minY + gen.height != info.getMaxHeight()) {
            plugin.getLogger().warning("Horizon's build range " + gen.minY + ".." + (gen.minY + gen.height)
                    + " differs from Paper's " + info.getMinHeight() + ".." + info.getMaxHeight()
                    + "; only the overlap is written");
        }
        plugin.getLogger().info(String.format("Opened %s (%s, seed %d): %d states (%d unknown), %d biomes, "
                        + "datapacks %s, in %.1f s", info.getName(), dimension, info.getSeed(), count, unknown,
                biomes.length, datapacks == null ? "none" : datapacks, (System.nanoTime() - start) / 1e9));
        FastChunkWriter fast = fastWrite ? FastChunkWriter.create(states, gen) : null;
        if (fastWrite && fast == null) {
            plugin.getLogger().warning("Paper's chunk internals are not the expected ones; using the plugin API copy");
        }
        return new WorldGen(gen, states, air, biomes, fast);
    }

    /** The main level's datapacks folder, which every dimension shares. */
    private Path datapacksFolder() {
        Path container = Bukkit.getWorldContainer().toPath();
        String level = "world";
        try (InputStream in = Files.newInputStream(container.resolve("server.properties"))) {
            Properties props = new Properties();
            props.load(in);
            level = props.getProperty("level-name", level);
        } catch (IOException ignored) {
            // Not at the expected place: use the default level name.
        }
        Path dir = container.resolve(level).resolve("datapacks");
        return Files.isDirectory(dir) ? dir : null;
    }

    private static final class Buffers {
        final MemorySegment blocks, biomes, packed, lens;

        Buffers(int height) {
            Arena arena = Arena.ofAuto();
            blocks = arena.allocate(ValueLayout.JAVA_INT, 256L * height);
            biomes = arena.allocate(ValueLayout.JAVA_SHORT, 16L * height / 4);
            // Worst case per section: bits byte, 256-entry palette of 5-byte varints, 8-bit data.
            packed = arena.allocate((1 + 5 + 256 * 5 + 4096) * (long) (height / 16));
            lens = arena.allocate(ValueLayout.JAVA_INT, height / 16);
        }
    }

    private final ThreadLocal<Buffers> buffers = new ThreadLocal<>();

    @Override
    public void generateNoise(WorldInfo info, Random random, int cx, int cz, ChunkData data) {
        WorldGen w = world(info);
        HorizonNative.Generator gen = w.gen();
        Buffers buf = buffers.get();
        if (buf == null || buf.blocks.byteSize() < 1024L * gen.height) {
            buf = new Buffers(gen.height);
            buffers.set(buf);
        }
        long t0 = System.nanoTime();
        int status;
        try {
            status = w.fast() != null
                    ? gen.generatePacked(cx, cz, buf.blocks, buf.biomes, buf.packed, buf.lens)
                    : gen.generate(cx, cz, buf.blocks, buf.biomes);
        } catch (Throwable t) {
            plugin.getLogger().log(Level.SEVERE, "horizon-bridge call failed", t);
            return;
        }
        if (status != 0) {
            plugin.getLogger().warning("Horizon could not generate chunk " + cx + " " + cz + " (" + status + ")");
            return;
        }
        long t1 = System.nanoTime();
        if (w.fast() != null
                && w.fast().write(data, buf.packed, buf.lens, buf.blocks, gen.minY, gen.height, w.air())) {
            fastChunks.incrementAndGet();
        } else {
            copyThroughApi(data, w, buf);
        }
        long t2 = System.nanoTime();
        record(t1 - t0, t2 - t1);
    }

    private void record(long nativeNanos, long copy) {
        nanos.addAndGet(nativeNanos);
        copyNanos.addAndGet(copy);
        long n = chunks.incrementAndGet();
        if (timingEvery > 0 && n % timingEvery == 0) {
            plugin.getLogger().info(String.format("%d chunks (%d by direct section writes): Rust %.2f ms/chunk avg; "
                    + "copy %.2f ms/chunk avg", n, fastChunks.get(), nanos.get() / 1e6 / n, copyNanos.get() / 1e6 / n));
        }
    }

    /** The plugin-API copy: one ChunkData.setBlock per non-air block. */
    private static void copyThroughApi(ChunkData data, WorldGen w, Buffers buf) {
        HorizonNative.Generator gen = w.gen();
        int lo = Math.max(gen.minY, data.getMinHeight());
        int hi = Math.min(gen.minY + gen.height, data.getMaxHeight());
        BlockData[] states = w.states();
        boolean[] air = w.air();
        MemorySegment blocks = buf.blocks;
        for (int y = lo; y < hi; y++) {
            long row = (long) (y - gen.minY) * 256;
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = blocks.getAtIndex(ValueLayout.JAVA_INT, row + z * 16 + x);
                    if (id >= 0 && id < states.length && !air[id]) {
                        data.setBlock(x, y, z, states[id]);
                    }
                }
            }
        }
    }

    // Horizon's output already holds the surface, bedrock and carved caves.
    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return decorate;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return decorate;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return true;
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo info) {
        return rustBiomes ? new RustBiomes() : null;
    }

    /** Noise biomes from Rust, one downcall per quart. */
    private final class RustBiomes extends BiomeProvider {
        private final ThreadLocal<MemorySegment[]> scratch = ThreadLocal.withInitial(() -> {
            Arena arena = Arena.ofAuto();
            return new MemorySegment[]{arena.allocate(ValueLayout.JAVA_INT, 3), arena.allocate(ValueLayout.JAVA_SHORT, 1)};
        });

        @Override
        public Biome getBiome(WorldInfo info, int x, int y, int z) {
            WorldGen w = world(info);
            MemorySegment[] s = scratch.get();
            s[0].setAtIndex(ValueLayout.JAVA_INT, 0, x >> 2);
            s[0].setAtIndex(ValueLayout.JAVA_INT, 1, y >> 2);
            s[0].setAtIndex(ValueLayout.JAVA_INT, 2, z >> 2);
            try {
                w.gen().noiseBiomes(s[0], 1, s[1]);
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            int id = Short.toUnsignedInt(s[1].get(ValueLayout.JAVA_SHORT, 0));
            return id < w.biomes().length ? w.biomes()[id] : w.biomes()[0];
        }

        @Override
        public List<Biome> getBiomes(WorldInfo info) {
            return new ArrayList<>(List.of(world(info).biomes()));
        }
    }

    void closeAll() {
        for (WorldGen w : worlds.values()) {
            try {
                w.gen().close();
            } catch (RuntimeException ignored) {
                // Shutting down.
            }
        }
        worlds.clear();
    }
}
