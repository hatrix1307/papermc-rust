package dev.horizongen;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

/**
 * Reads the blocks a compare file lists from another, already generated world, and counts how
 * many match the file's Horizon column and how many its vanilla column. Only chunks the world
 * already has are read, so nothing new is generated.
 */
final class HorizonProbe {
    private HorizonProbe() {
    }

    static void run(HorizonGenPlugin plugin, String worldName, String file, boolean shutdown) {
        Logger log = plugin.getLogger();
        Path path = plugin.getDataFolder().toPath().resolve(file);
        List<String> lines;
        try {
            lines = Files.readAllLines(path);
        } catch (IOException e) {
            log.severe("probe: cannot read " + path + ": " + e);
            return;
        }
        World world = new WorldCreator(worldName).createWorld();
        int read = 0, likeHorizon = 0, likeVanilla = 0, neither = 0;
        for (String line : lines) {
            String[] p = line.split(" ");
            int cx = Integer.parseInt(p[0]), cz = Integer.parseInt(p[1]);
            if (!world.isChunkGenerated(cx, cz)) {
                continue;
            }
            String here = world.getBlockAt(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4]))
                    .getBlockData().getAsString();
            read++;
            if (here.equals(p[5])) {
                likeHorizon++;
            } else if (here.equals(p[6])) {
                likeVanilla++;
            } else {
                neither++;
                log.info("probe: " + line + " -> " + worldName + " has " + here);
            }
        }
        log.info(String.format("probe %s: %d of %d listed blocks are in generated chunks; %d match Horizon, "
                + "%d match the compare's vanilla side, %d neither", worldName, read, lines.size(),
                likeHorizon, likeVanilla, neither));
        if (shutdown) {
            Bukkit.shutdown();
        }
    }
}
