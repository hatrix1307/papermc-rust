package dev.horizongen;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.generator.ChunkGenerator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Throughput benchmark: generates a fresh (2r+1)^2 area through Paper's async chunk system in
 * one world per case, one case after another, and logs chunks per second.
 */
final class HorizonBench {
    private HorizonBench() {
    }

    private record Case(String label, ChunkGenerator generator) {
    }

    static void run(HorizonGenPlugin plugin, long seed, int radius, boolean shutdown) {
        Logger log = plugin.getLogger();
        String stamp = Long.toString(System.currentTimeMillis() % 1_000_000);
        Deque<Case> cases = new ArrayDeque<>(List.of(
                new Case("vanilla-terrain", new HorizonCompare.VanillaTerrain()),
                new Case("horizon-terrain", plugin.terrainOnly()),
                new Case("vanilla-full", null),
                new Case("horizon-full", plugin.fullGenerator())));
        List<String> results = new ArrayList<>();
        next(plugin, log, seed, radius, stamp, cases, results, shutdown);
    }

    private static void next(HorizonGenPlugin plugin, Logger log, long seed, int radius, String stamp,
                             Deque<Case> cases, List<String> results, boolean shutdown) {
        Case c = cases.poll();
        if (c == null) {
            log.info("bench results (" + (2 * radius + 1) * (2 * radius + 1) + " chunks each, fresh worlds):");
            results.forEach(r -> log.info("  " + r));
            if (shutdown) {
                Bukkit.shutdown();
            }
            return;
        }
        WorldCreator creator = new WorldCreator("bench_" + stamp + "_" + c.label()).seed(seed);
        if (c.generator() != null) {
            creator.generator(c.generator());
        }
        World world = creator.createWorld();
        // Warm up both sides (JIT, Rust caches) away from the measured area.
        List<CompletableFuture<?>> warm = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int z = 0; z < 6; z++) {
                warm.add(world.getChunkAtAsync(-4000 + x, -4000 + z, true));
            }
        }
        CompletableFuture.allOf(warm.toArray(CompletableFuture[]::new)).thenRun(() -> {
            plugin.resetTiming();
            long t0 = System.nanoTime();
            List<CompletableFuture<?>> all = new ArrayList<>();
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    all.add(world.getChunkAtAsync(3000 + dx, 3000 + dz, true));
                }
            }
            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).thenRun(() -> {
                double s = (System.nanoTime() - t0) / 1e9;
                String r = String.format("%-16s %6.2f s  %7.1f chunks/s%s", c.label(), s, all.size() / s,
                        plugin.timingSummary());
                log.info("bench " + r);
                results.add(r);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Bukkit.unloadWorld(world, false);
                    next(plugin, log, seed, radius, stamp, cases, results, shutdown);
                });
            });
        });
    }
}
