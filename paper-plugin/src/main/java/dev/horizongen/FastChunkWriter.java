package dev.horizongen;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.craftbukkit.generator.CraftChunkData;
import org.bukkit.generator.ChunkGenerator.ChunkData;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.EnumSet;
import java.util.Set;

/**
 * Loads Horizon's chunks straight into the proto chunk's sections, as vanilla's noise stage
 * leaves them: each section's palette and packed longs, encoded by Rust, go in with one
 * {@code PalettedContainer.read}, instead of one {@code ChunkData.setBlock} (BlockPos,
 * heightmap and light bookkeeping) per block. The only class that touches Paper internals:
 * if they change, {@link #create} returns null and the generator falls back to the plugin API.
 */
final class FastChunkWriter {
    /** The heightmaps vanilla's noise stage leaves behind (NoiseBasedChunkGenerator.doFill). */
    private static final Set<Heightmap.Types> WORLDGEN_HEIGHTMAPS =
            EnumSet.of(Heightmap.Types.OCEAN_FLOOR_WG, Heightmap.Types.WORLD_SURFACE_WG);

    private final BlockState[] states;

    private FastChunkWriter(BlockState[] states) {
        this.states = states;
    }

    /**
     * A writer for these Horizon-id states, having told Rust the server's id of each, or null when
     * Paper's internals are not the expected ones.
     */
    static FastChunkWriter create(BlockData[] bukkit, HorizonNative.Generator gen) throws Throwable {
        BlockState[] states;
        int[] ids;
        try {
            states = new BlockState[bukkit.length];
            ids = new int[bukkit.length];
            for (int i = 0; i < bukkit.length; i++) {
                states[i] = ((CraftBlockData) bukkit[i]).getState();
                ids[i] = Block.BLOCK_STATE_REGISTRY.getId(states[i]);
            }
        } catch (LinkageError | ClassCastException e) {
            return null;
        }
        return gen.setServerIds(ids) == 0 ? new FastChunkWriter(states) : null;
    }

    /**
     * Loads a packed Horizon chunk into {@code data}'s chunk. Sections Rust could not pack are
     * written from {@code blocks} ({@code ((y - minY) * 16 + z) * 16 + x}). Returns false, having
     * written nothing, when {@code data} is not Paper's own chunk data.
     */
    boolean write(ChunkData data, MemorySegment packed, MemorySegment lens, MemorySegment blocks,
                  int minY, int height, boolean[] air) {
        if (!(data instanceof CraftChunkData craft)) {
            return false;
        }
        ChunkAccess chunk = craft.getHandle();
        if (chunk.getMinY() != minY || chunk.getHeight() != height) {
            return false;
        }
        LevelChunkSection[] sections = chunk.getSections();
        long offset = 0;
        for (int i = 0; i < height / 16; i++) {
            int len = lens.getAtIndex(ValueLayout.JAVA_INT, i);
            if (len == 0) {
                continue; // All air: proto chunks start empty.
            }
            LevelChunkSection section = sections[i];
            if (len > 0) {
                FriendlyByteBuf buf = new FriendlyByteBuf(
                        Unpooled.wrappedBuffer(packed.asSlice(offset, len).asByteBuffer()));
                section.getStates().read(buf);
                offset += len;
            } else {
                writeSection(section, blocks, (long) i * 4096, air);
            }
            section.recalcBlockCounts();
        }
        Heightmap.primeHeightmaps(chunk, WORLDGEN_HEIGHTMAPS);
        chunk.markUnsaved();
        return true;
    }

    /** One section block by block, for one with more states than a hashmap palette holds. */
    private void writeSection(LevelChunkSection section, MemorySegment blocks, long base, boolean[] air) {
        long index = base;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int id = blocks.getAtIndex(ValueLayout.JAVA_INT, index++);
                    if (!air[id]) {
                        // No locking: nothing else touches a chunk during its noise stage.
                        section.setBlockState(x, y, z, states[id], false);
                    }
                }
            }
        }
    }
}
