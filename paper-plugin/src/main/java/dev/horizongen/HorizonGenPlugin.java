package dev.horizongen;

import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Registers the {@code HorizonGen} world generator. Use it in bukkit.yml:
 * <pre>
 * worlds:
 *   world:
 *     generator: HorizonGen
 * </pre>
 */
public final class HorizonGenPlugin extends JavaPlugin {
    private HorizonNative natives;
    private HorizonChunkGenerator generator, terrainOnly;

    HorizonChunkGenerator terrainOnly() {
        return terrainOnly;
    }

    HorizonChunkGenerator fullGenerator() {
        return generator;
    }

    void resetTiming() {
        HorizonChunkGenerator.resetTiming();
    }

    String timingSummary() {
        return HorizonChunkGenerator.timingSummary();
    }

    @Override
    public void onLoad() {
        saveDefaultConfig();
        try {
            natives = new HorizonNative(libraryPath());
        } catch (Throwable t) {
            getLogger().severe("Could not load horizon-bridge: " + t);
            return;
        }
        String resources = getConfig().getString("resources", "");
        if (resources.isEmpty()) {
            getLogger().severe("Set 'resources' in config.yml to horizon-prepare's output folder");
            return;
        }
        boolean rustBiomes = getConfig().getBoolean("rust-biomes", false);
        HorizonChunkGenerator.fastWrite = getConfig().getBoolean("fast-write", true);
        int timingEvery = getConfig().getInt("timing-every", 200);
        generator = new HorizonChunkGenerator(this, natives, Path.of(resources), rustBiomes, timingEvery);
        terrainOnly = new HorizonChunkGenerator(this, natives, Path.of(resources), rustBiomes, timingEvery, false);
        getLogger().info("horizon-bridge loaded; worlds using generator 'HorizonGen' get Rust terrain");
    }

    @Override
    public void onEnable() {
        int radius = getConfig().getInt("compare-on-start.radius", -1);
        if (generator != null && radius >= 0) {
            String world = getConfig().getString("compare-on-start.world", "world");
            boolean shutdown = getConfig().getBoolean("compare-on-start.shutdown", true);
            String mode = getConfig().getString("compare-on-start.mode", "full");
            // Far from spawn by default, so neither world has these chunks already.
            int ox = getConfig().getInt("compare-on-start.chunk-x", 2000);
            int oz = getConfig().getInt("compare-on-start.chunk-z", -1500);
            // Once the server has finished starting and loaded its worlds.
            getServer().getScheduler().runTask(this, () -> {
                if (mode.equals("probe")) {
                    HorizonProbe.run(this, world, getConfig().getString("compare-on-start.probe-file",
                            "compare-terrain.txt"), shutdown);
                } else if (mode.equals("bench")) {
                    HorizonBench.run(this, getServer().getWorlds().getFirst().getSeed(), radius, shutdown);
                } else {
                    HorizonCompare.run(this, world, radius, shutdown, mode, ox, oz);
                }
            });
        }
    }

    @Override
    public void onDisable() {
        if (generator != null) {
            generator.closeAll();
            terrainOnly.closeAll();
        }
    }

    @Override
    public ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (generator == null) {
            throw new IllegalStateException("HorizonGen failed to start; see the log above");
        }
        return generator;
    }

    private Path libraryPath() throws IOException {
        String configured = getConfig().getString("library", "");
        if (!configured.isEmpty()) {
            return Path.of(configured).toAbsolutePath();
        }
        String file = System.mapLibraryName("horizon_bridge");
        Path target = getDataFolder().toPath().resolve(file).toAbsolutePath();
        try (InputStream in = getResource("native/" + file)) {
            if (in != null) {
                Files.createDirectories(target.getParent());
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!Files.exists(target)) {
            throw new IOException("no " + file + " in the plugin jar or " + target);
        }
        return target;
    }
}
