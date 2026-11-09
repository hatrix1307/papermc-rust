package dev.horizongen;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;

import java.util.logging.Logger;

/**
 * Self-test: generates the same chunks in a Horizon world and in a vanilla world of the
 * same seed, in the same order, and compares every block and biome.
 */
final class HorizonCompare {
    private HorizonCompare() {
    }

    /** Vanilla noise, surface and carvers, without features, structure pieces or mobs. */
    static final class VanillaTerrain extends ChunkGenerator {
        @Override
        public boolean shouldGenerateNoise() {
            return true;
        }

        @Override
        public boolean shouldGenerateSurface() {
            return true;
        }

        @Override
        public boolean shouldGenerateCaves() {
            return true;
        }

        @Override
        public boolean shouldGenerateDecorations() {
            return false;
        }

        @Override
        public boolean shouldGenerateMobs() {
            return false;
        }

        @Override
        public boolean shouldGenerateStructures() {
            return true;
        }
    }

    private static World create(String name, World like, ChunkGenerator generator) {
        WorldCreator creator = new WorldCreator(name).seed(like.getSeed()).environment(like.getEnvironment());
        if (generator != null) {
            creator.generator(generator);
        }
        return creator.createWorld();
    }

    static void run(HorizonGenPlugin plugin, String horizonWorld, int radius, boolean shutdown,
                    String mode, int ox, int oz) {
        Logger log = plugin.getLogger();
        World horizon = Bukkit.getWorld(horizonWorld);
        if (horizon == null) {
            log.severe("compare: no world " + horizonWorld);
            return;
        }
        World vanilla;
        switch (mode) {
            case "control" -> {
                // Vanilla against vanilla: how much two vanilla worlds differ on their own.
                horizon = create(horizonWorld + "_vanilla_ctl", horizon, null);
                vanilla = create(horizon.getName() + "_vanilla_cmp", horizon, null);
            }
            case "terrain" -> {
                // No decoration on either side: Rust's terrain against vanilla's, block for block.
                vanilla = create(horizonWorld + "_terrain_vanilla", horizon, new VanillaTerrain());
                horizon = create(horizonWorld + "_terrain_horizon", horizon, plugin.terrainOnly());
            }
            case "terrain-control" -> {
                // Vanilla terrain against itself: is it exactly repeatable?
                vanilla = create(horizonWorld + "_tctl_a", horizon, new VanillaTerrain());
                horizon = create(horizonWorld + "_tctl_b", horizon, new VanillaTerrain());
            }
            default -> vanilla = create(horizonWorld + "_vanilla_cmp", horizon, null);
        }
        horizonWorld = horizon.getName() + " (" + mode + ")";
        if (vanilla == null) {
            log.severe("compare: could not create the vanilla world");
            return;
        }
        long tHorizon = 0, tVanilla = 0, blocks = 0, differ = 0, biomeDiffer = 0, chunks = 0;
        int shown = 0;
        java.util.List<String> diffs = new java.util.ArrayList<>();
        int minY = Math.max(horizon.getMinHeight(), vanilla.getMinHeight());
        int maxY = Math.min(horizon.getMaxHeight(), vanilla.getMaxHeight());
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                int cx = ox + dx, cz = oz + dz;
                long t0 = System.nanoTime();
                Chunk a = horizon.getChunkAt(cx, cz, true);
                long t1 = System.nanoTime();
                Chunk b = vanilla.getChunkAt(cx, cz, true);
                long t2 = System.nanoTime();
                tHorizon += t1 - t0;
                tVanilla += t2 - t1;
                chunks++;
                ChunkSnapshot sa = a.getChunkSnapshot(false, true, false);
                ChunkSnapshot sb = b.getChunkSnapshot(false, true, false);
                for (int y = minY; y < maxY; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            blocks++;
                            BlockData ba = sa.getBlockData(x, y, z), bb = sb.getBlockData(x, y, z);
                            if (!ba.equals(bb)) {
                                differ++;
                                diffs.add(String.format("%d %d %d %d %d %s %s", cx, cz,
                                        cx * 16 + x, y, cz * 16 + z, ba.getAsString(), bb.getAsString()));
                                if (shown++ < 15) {
                                    log.info(String.format("  differs at %d %d %d: horizon %s, vanilla %s",
                                            cx * 16 + x, y, cz * 16 + z, ba.getAsString(), bb.getAsString()));
                                }
                            }
                            if ((x & 3) == 0 && (y & 3) == 0 && (z & 3) == 0
                                    && !sa.getBiome(x, y, z).equals(sb.getBiome(x, y, z))) {
                                biomeDiffer++;
                            }
                        }
                    }
                }
            }
        }
        log.info(String.format("compare %s vs vanilla, seed %d: %d chunks, %d blocks, %d differ (%.4f%%), "
                        + "%d biome quarts differ", horizonWorld, horizon.getSeed(), chunks, blocks, differ,
                100.0 * differ / Math.max(1, blocks), biomeDiffer));
        log.info(String.format("compare timing (sync getChunkAt, every stage the mode runs): horizon %.1f ms/chunk, "
                + "vanilla %.1f ms/chunk", tHorizon / 1e6 / chunks, tVanilla / 1e6 / chunks));
        // Every difference, one line each: chunk x z, block x y z, horizon state, vanilla state.
        try {
            java.nio.file.Path out = plugin.getDataFolder().toPath().resolve("compare-" + mode + ".txt");
            java.nio.file.Files.write(out, diffs);
            log.info("compare: wrote " + diffs.size() + " differences to " + out);
        } catch (java.io.IOException e) {
            log.warning("compare: could not write the differences: " + e);
        }
        if (shutdown) {
            Bukkit.shutdown();
        }
    }
}
