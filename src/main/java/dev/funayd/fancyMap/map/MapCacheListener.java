package dev.funayd.fancyMap.map;

import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Invalidates rendered chunks when Bukkit reports world mutations.
 */
public final class MapCacheListener implements Listener {
    private final PersistentChunkRenderCache cache;

    /**
     * Creates a world-change listener.
     *
     * @param cache cache to invalidate
     */
    public MapCacheListener(PersistentChunkRenderCache cache) {
        this.cache = cache;
    }

    /** Invalidates a chunk after a block is broken. */
    @EventHandler
    private void onBlockBreak(BlockBreakEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates a chunk after a block is placed. */
    @EventHandler
    private void onBlockPlace(BlockPlaceEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates all chunks affected by a block explosion. */
    @EventHandler
    private void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::invalidate);
    }

    /** Invalidates all chunks affected by an entity explosion. */
    @EventHandler
    private void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::invalidate);
    }

    /** Invalidates blocks moved by a piston extension. */
    @EventHandler
    private void onPistonExtend(BlockPistonExtendEvent event) {
        event.getBlocks().forEach(block -> {
            invalidate(block);
            invalidate(block.getRelative(event.getDirection()));
        });
    }

    /** Invalidates blocks moved by a piston retraction. */
    @EventHandler
    private void onPistonRetract(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(block -> {
            invalidate(block);
            invalidate(block.getRelative(event.getDirection()));
        });
    }

    /** Invalidates a chunk after a fluid level change. */
    @EventHandler
    private void onFluidLevelChange(FluidLevelChangeEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates a chunk after a block burns. */
    @EventHandler
    private void onBlockBurn(BlockBurnEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates a chunk after a block fades. */
    @EventHandler
    private void onBlockFade(BlockFadeEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates a chunk after a block forms. */
    @EventHandler
    private void onBlockForm(BlockFormEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates a chunk after a block grows. */
    @EventHandler
    private void onBlockGrow(BlockGrowEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates a chunk after leaves decay. */
    @EventHandler
    private void onLeavesDecay(LeavesDecayEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates a chunk after an entity changes a block. */
    @EventHandler
    private void onEntityChangeBlock(EntityChangeBlockEvent event) {
        invalidate(event.getBlock());
    }

    /** Invalidates all chunks changed by a structure growth event. */
    @EventHandler
    private void onStructureGrow(StructureGrowEvent event) {
        event.getBlocks().stream()
                .map(BlockState::getBlock)
                .forEach(this::invalidate);
    }

    /** Invalidates the chunk containing a changed block. */
    private void invalidate(Block block) {
        cache.invalidate(
                block.getWorld(),
                block.getChunk().getX(),
                block.getChunk().getZ()
        );
    }
}
