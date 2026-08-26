package dev.twme.sculpt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Shulker;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.persistence.PersistentDataType;

import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.integration.SculptBlockCleaner;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.transport.bukkit.BukkitDisplayHandle;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.InteractionSpawner;
import dev.twme.sculpt.util.ShulkerSpawner;

/** Reconstructs and disconnects Sculpt-owned entities with their chunks. */
public final class SculptChunkListener implements Listener {

    private static final NamespacedKey SCULPT_TYPE_KEY = Sculpt.key("sculpt", "type");
    private static final NamespacedKey HOVER_KEY = Sculpt.key("sculpt", "hover");
    private static final NamespacedKey PATH_KEY = Sculpt.key("sculpt", "path");

    private final Sculpt plugin;
    private final Set<Chunk> pendingChunks = ConcurrentHashMap.newKeySet();

    public SculptChunkListener(final Sculpt plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChunkLoad(final ChunkLoadEvent event) {
        final Chunk chunk = event.getChunk();
        if (!plugin.isHeadResolverReady()) {
            pendingChunks.add(chunk);
            return;
        }
        reconcileChunk(chunk);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onChunkUnload(final ChunkUnloadEvent event) {
        final Chunk chunk = event.getChunk();
        pendingChunks.remove(chunk);
        plugin.unloadChunk(chunk);
        removeHoverDisplays(chunk);
    }

    /** Schedule reconciliation after the asynchronous head registries become ready. */
    void scheduleLoadedChunkReconciliation() {
        final Set<Chunk> chunks = new HashSet<>(pendingChunks);
        pendingChunks.clear();
        for (final org.bukkit.World world : plugin.getServer().getWorlds()) {
            Collections.addAll(chunks, world.getLoadedChunks());
        }
        for (final Chunk chunk : chunks) {
            FoliaScheduler.runRegionTask(
                plugin, chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
                    if (chunk.isLoaded() && !plugin.isDisabling()) {
                        reconcileChunk(chunk);
                    }
                });
        }
        if (!chunks.isEmpty()) {
            plugin.getLogger().info("[Sculpt] scheduled reconciliation for "
                + chunks.size() + " loaded chunk(s)");
        }
    }

    void clearPendingChunks() {
        pendingChunks.clear();
    }

    /** Idempotently reconnect every Sculpt-owned entity in one chunk. */
    void reconcileChunk(final Chunk chunk) {
        final Map<Interaction, Location> pendingInteractions = new LinkedHashMap<>();
        final Map<Shulker, String> pendingShulkers = new LinkedHashMap<>();
        final List<BlockDisplay> pendingSeats = new ArrayList<>();
        final Set<SculptBlock> sculptBlocks = new LinkedHashSet<>();

        scanChunkEntities(
            chunk, pendingInteractions, pendingShulkers, pendingSeats, sculptBlocks);
        attachInteractions(pendingInteractions);
        attachShulkers(pendingShulkers);
        repairSculptBlocks(sculptBlocks);
        removeOrphanLeaves(chunk, sculptBlocks);
        removeOrphanTextPixels(chunk);
        removeOrphanSeats(pendingSeats);
    }

    private void scanChunkEntities(
            final Chunk chunk,
            final Map<Interaction, Location> pendingInteractions,
            final Map<Shulker, String> pendingShulkers,
            final List<BlockDisplay> pendingSeats,
            final Set<SculptBlock> sculptBlocks) {
        for (final Entity entity : chunk.getEntities()) {
            if (entity instanceof ItemDisplay display) {
                scanItemDisplay(display, sculptBlocks);
            } else if (entity instanceof BlockDisplay seat) {
                if (hasType(seat, "shulker_seat")) pendingSeats.add(seat);
            } else if (entity instanceof Interaction interaction) {
                if (InteractionSpawner.isSculptInteraction(interaction)) {
                    pendingInteractions.put(
                        interaction, interaction.getLocation().toBlockLocation());
                }
            } else if (entity instanceof Shulker shulker) {
                scanShulker(shulker, pendingShulkers);
            }
        }
    }

    private void scanItemDisplay(
            final ItemDisplay display, final Set<SculptBlock> sculptBlocks) {
        final String hoverTag = display.getPersistentDataContainer()
            .get(HOVER_KEY, PersistentDataType.STRING);
        if ("glass".equals(hoverTag)) {
            display.remove();
        } else if (hasType(display, "root")) {
            plugin.reconstructSculptBlock(display);
            final SculptBlock block = plugin.getActiveBlock(
                BlockPosKey.of(display.getLocation()));
            if (block != null) sculptBlocks.add(block);
        }
    }

    private void scanShulker(
            final Shulker shulker,
            final Map<Shulker, String> pendingShulkers) {
        if (!hasType(shulker, "shulker")) return;
        final String path = shulker.getPersistentDataContainer()
            .get(PATH_KEY, PersistentDataType.STRING);
        if (path == null) {
            ShulkerSpawner.remove(shulker);
        } else {
            pendingShulkers.put(shulker, path);
        }
    }

    private void attachInteractions(
            final Map<Interaction, Location> pendingInteractions) {
        for (final Map.Entry<Interaction, Location> entry
                : pendingInteractions.entrySet()) {
            final Interaction interaction = entry.getKey();
            final SculptBlock parent = plugin.getActiveBlock(
                BlockPosKey.of(entry.getValue()));
            if (parent == null || !parent.usesEntityInteraction()) {
                interaction.remove();
            } else if (parent.clickProxy() == interaction) {
                continue;
            } else if (parent.clickProxy() != null && parent.clickProxy().isValid()) {
                interaction.remove();
            } else {
                parent.attachClickProxy(interaction);
            }
        }
    }

    private void attachShulkers(final Map<Shulker, String> pendingShulkers) {
        for (final Map.Entry<Shulker, String> entry : pendingShulkers.entrySet()) {
            final Shulker shulker = entry.getKey();
            final SculptBlock parent = plugin.getActiveBlock(
                BlockPosKey.of(shulker.getLocation().toBlockLocation()));
            if (parent == null || !parent.usesEntityCollision()
                    || !parent.attachCollisionShulker(entry.getValue(), shulker)) {
                ShulkerSpawner.remove(shulker);
            }
        }
    }

    private void repairSculptBlocks(final Set<SculptBlock> sculptBlocks) {
        for (final SculptBlock block : sculptBlocks) {
            if (block.state != SculptBlock.State.SCULPTED) continue;

            block.repairDisplayEntities();
            block.reconcileFillState();
        }
    }

    private static void removeOrphanLeaves(
            final Chunk chunk,
            final Set<SculptBlock> sculptBlocks) {
        final List<ItemDisplay> roots = new ArrayList<>();
        for (final SculptBlock block : sculptBlocks) {
            if (block.rootEntity instanceof BukkitDisplayHandle handle
                    && handle.entity().isValid()) {
                roots.add(handle.entity());
            }
        }
        SculptBlockCleaner.cleanOrphanedLeaves(chunk, roots);
    }

    private void removeOrphanTextPixels(final Chunk chunk) {
        for (final Entity entity : chunk.getEntities()) {
            if (!(entity instanceof TextDisplay display)
                    || !hasType(display, "text_pixel")) continue;
            if (!(display.getVehicle() instanceof ItemDisplay root)
                    || !hasType(root, "root")) {
                display.remove();
                continue;
            }
            final SculptBlock block = plugin.getActiveBlock(
                BlockPosKey.of(root.getLocation()));
            if (block == null || block.rootEntity == null
                    || !block.displayMode().usesTextRenderer()) {
                display.remove();
            }
        }
    }

    private static void removeOrphanSeats(final List<BlockDisplay> pendingSeats) {
        for (final BlockDisplay seat : pendingSeats) {
            final boolean hasSculptShulker = seat.getPassengers().stream()
                .anyMatch(passenger -> passenger instanceof Shulker shulker
                    && ShulkerSpawner.isSculptShulker(shulker));
            if (!hasSculptShulker && seat.isValid()) seat.remove();
        }
    }

    private static void removeHoverDisplays(final Chunk chunk) {
        for (final Entity entity : chunk.getEntities()) {
            if (!(entity instanceof ItemDisplay display)) continue;
            final String tag = display.getPersistentDataContainer()
                .get(HOVER_KEY, PersistentDataType.STRING);
            if ("glass".equals(tag)) display.remove();
        }
    }

    private static boolean hasType(final Entity entity, final String expectedType) {
        final String type = entity.getPersistentDataContainer()
            .get(SCULPT_TYPE_KEY, PersistentDataType.STRING);
        return expectedType.equals(type);
    }
}
