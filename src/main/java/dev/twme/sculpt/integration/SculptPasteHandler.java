package dev.twme.sculpt.integration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.EmptyClipboardException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.SessionOwner;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.persistence.PersistentDataType;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.transport.bukkit.BukkitDisplayHandle;
import dev.twme.sculpt.util.FoliaScheduler;

/**
 * Detects SculptBlocks pasted via WorldEdit/FAWE and triggers reconstruction.
 *
 * <p>Three detection paths:</p>
 * <ol>
 *   <li><b>FAWE PasteEvent (reflective):</b> Removes only redundant,
 *   unserializable passenger snapshots from proven SculptBlock roots before
 *   FAWE creates destination entities, then verifies the transformed root
 *   positions because FAWE's queue bypasses Bukkit spawn events.</li>
 *   <li><b>{@link EntitySpawnEvent} (Bukkit):</b> Catches SculptBlock root
 *   {@code ItemDisplay} entities when they're spawned through the Bukkit
 *   event system (vanilla WE, FAWE direct path).</li>
 *   <li><b>{@code EditSessionEvent} (WE EventBus):</b>
 *   Wraps the extent chain to track BARRIER placements and successfully
 *   created Sculpt roots. After the edit session completes, delayed tasks
 *   reconcile those chunks — catching FAWE's queue path where Bukkit events
 *   are bypassed, including AIR-based shulker mode.</li>
 * </ol>
 */
public class SculptPasteHandler implements Listener {

    private static final String FAWE_PASTE_EVENT_CLASS =
            "com.fastasyncworldedit.core.event.extent.PasteEvent";
    private static final long[] PASTE_VERIFY_DELAYS = {1L, 5L, 20L};
    private static final NamespacedKey SCULPT_TYPE_KEY = new NamespacedKey("sculpt", "type");
    private static final Set<String> CLIPBOARD_COPY_COMMANDS = Set.of(
            "copy", "cut", "lazycopy", "lazycut");

    private final Sculpt plugin;
    private final Map<ChunkKey, Set<BlockPosition>> pendingChunkVerifications =
            new ConcurrentHashMap<>();
    private boolean weHandlerRegistered = false;

    public SculptPasteHandler(Sculpt plugin) {
        this.plugin = plugin;
    }

    // ========================================================================
    //  WorldEdit EventBus
    // ========================================================================

    /**
     * Register the WorldEdit EventBus handler.
     * Called once during {@link Sculpt#onEnable()} if WE is installed.
     */
    public void registerWorldEditHandler() {
        if (weHandlerRegistered) return;
        try {
            WorldEdit.getInstance().getEventBus().register(this);
            weHandlerRegistered = true;
            plugin.getLogger().info("[Sculpt] WorldEdit paste detection enabled");
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("[Sculpt] Failed to register WE handler: "
                    + e.getMessage());
        }
    }

    public void unregisterWorldEditHandler() {
        if (!weHandlerRegistered) return;
        try {
            WorldEdit.getInstance().getEventBus().unregister(this);
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().warning("[Sculpt] Failed to unregister WE handler: "
                    + e.getMessage());
        } finally {
            weHandlerRegistered = false;
        }
    }

    @Subscribe
    public void onEditSession(EditSessionEvent event) {
        if (event.getStage() != EditSession.Stage.BEFORE_CHANGE) return;
        if (event.getWorld() == null || event.getExtent() == null) return;

        String worldName = event.getWorld().getName();
        SculptPasteExtent tracker = new SculptPasteExtent(event.getExtent());
        event.setExtent(tracker);

        for (long delay : PASTE_VERIFY_DELAYS) {
            FoliaScheduler.runGlobalTaskLater(plugin, () -> {
                if (!tracker.isDirty()) return;
                Set<BlockVector3> positions = tracker.getTrackedPositions();
                if (!positions.isEmpty()) verifyPositions(worldName, positions);
            }, delay);
        }
    }

    @Subscribe
    public void onWorldEditEvent(Object event) {
        if (!FAWE_PASTE_EVENT_CLASS.equals(event.getClass().getName())) return;

        Object clipboard;
        try {
            clipboard = event.getClass().getMethod("getClipboard").invoke(event);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[Sculpt] Could not inspect FAWE clipboard; leaving paste unchanged", e);
            return;
        }
        if (!(clipboard instanceof Clipboard worldEditClipboard)) {
            plugin.getLogger().warning(
                    "[Sculpt] FAWE clipboard API is unavailable; leaving paste unchanged");
            return;
        }

        try {
            SculptClipboardNormalizer.Result result =
                    SculptClipboardNormalizer.normalize(worldEditClipboard);
            if (!result.safe()) {
                cancelFawePaste(event,
                        "could not remove " + result.remainingEntities()
                                + " proven Sculpt passenger snapshot(s)",
                        null);
                return;
            } else if (result.removedEntities() > 0) {
                plugin.getLogger().fine("[Sculpt] Removed " + result.removedEntities()
                        + " redundant passenger snapshot(s) from the FAWE clipboard");
            }
            scheduleFaweVerification(event, worldEditClipboard);
        } catch (SculptClipboardNormalizer.CleanupException e) {
            cancelFawePaste(event,
                    "failed to remove " + e.targetEntities()
                            + " proven Sculpt passenger snapshot(s)",
                    e.getCause());
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[Sculpt] Could not inspect FAWE entities; leaving paste unchanged", e);
        }
    }

    private void scheduleFaweVerification(Object event, Clipboard clipboard) {
        try {
            Object actor = event.getClass().getMethod("getActor").invoke(event);
            Object destination = event.getClass().getMethod("getPosition").invoke(event);
            Object extent = event.getClass().getMethod("getExtent").invoke(event);
            if (!(actor instanceof SessionOwner sessionOwner)
                    || !(destination instanceof BlockVector3 pastePosition)
                    || !(extent instanceof EditSession editSession)
                    || editSession.getWorld() == null) {
                plugin.getLogger().warning(
                        "[Sculpt] Could not resolve FAWE paste target; using fallback detection");
                return;
            }

            LocalSession session = WorldEdit.getInstance()
                    .getSessionManager().get(sessionOwner);
            Transform transform = session.getClipboard().getTransform();
            Set<BlockVector3> rootPositions = new LinkedHashSet<>();
            for (Entity entity : clipboard.getEntities()) {
                if (SculptClipboardEntityFilter.isSculptRoot(entity.getState())) {
                    rootPositions.add(transformClipboardEntityPosition(
                            entity.getLocation().toVector(), clipboard.getOrigin(),
                            pastePosition, transform));
                }
            }
            scheduleVerification(editSession.getWorld().getName(), rootPositions);
        } catch (EmptyClipboardException | ReflectiveOperationException
                | LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING,
                    "[Sculpt] Could not resolve FAWE SculptBlock targets; "
                            + "using fallback detection",
                    e);
        }
    }

    static BlockVector3 transformClipboardEntityPosition(
            Vector3 entityPosition, BlockVector3 clipboardOrigin,
            BlockVector3 pastePosition, Transform transform) {
        Vector3 pivot = clipboardOrigin.toVector3().round().add(0.5, 0.5, 0.5);
        Vector3 relativePosition = transform.apply(entityPosition.subtract(pivot));
        return relativePosition.add(
                pastePosition.toVector3().round().add(0.5, 0.5, 0.5))
                .toBlockPoint();
    }

    /**
     * WorldEdit snapshots entity NBT while executing clipboard copy commands.
     * Flush pending SculptBlock PDC first so a copy made immediately after an
     * edit cannot capture stale octree state. Folia already flushes PDC at the
     * edit site and therefore does not need a cross-region synchronous flush.
     */
    @org.bukkit.event.EventHandler(
            priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!isClipboardCopyCommand(event.getMessage()) || FoliaScheduler.isFolia()) {
            return;
        }
        flushClipboardSelection(event);
    }

    private void flushClipboardSelection(PlayerCommandPreprocessEvent event) {
        try {
            Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Method adapt = adapter.getMethod("adapt", org.bukkit.entity.Player.class);
            Object actor = adapt.invoke(null, event.getPlayer());
            if (!(actor instanceof SessionOwner sessionOwner)) {
                plugin.flushDirtyPDC();
                return;
            }

            LocalSession session = WorldEdit.getInstance()
                    .getSessionManager().get(sessionOwner);
            com.sk89q.worldedit.world.World selectionWorld = session.getSelectionWorld();
            if (selectionWorld == null) return;
            Region selection = session.getSelection(selectionWorld);
            BlockVector3 min = selection.getMinimumPoint();
            BlockVector3 max = selection.getMaximumPoint();
            final java.util.List<SculptBlock> suspended =
                plugin.prepareClipboardCopy(selectionWorld.getName(),
                    min.x(), min.y(), min.z(), max.x(), max.y(), max.z(),
                    copiesEntities(event.getMessage()));
            if (!suspended.isEmpty()) {
                // Command preprocessing runs before WE/FAWE snapshots the
                // entities. Restore derived pixels on the following tick.
                FoliaScheduler.runGlobalTaskLater(plugin,
                    () -> plugin.resumeClipboardTextRendering(suspended), 1L);
            }
        } catch (com.sk89q.worldedit.IncompleteRegionException ignored) {
            // WorldEdit will reject the copy command; there is nothing to snapshot.
        } catch (ReflectiveOperationException | LinkageError | RuntimeException exception) {
            // Preserve correctness on an unfamiliar WE/FAWE bridge.
            plugin.flushDirtyPDC();
        }
    }

    static boolean isClipboardCopyCommand(String message) {
        if (message == null) return false;
        String commandLine = message.stripLeading().toLowerCase(Locale.ROOT);
        while (commandLine.startsWith("/")) commandLine = commandLine.substring(1);

        int separator = commandLine.indexOf(':');
        int whitespace = firstWhitespace(commandLine);
        if (separator >= 0 && (whitespace < 0 || separator < whitespace)) {
            commandLine = commandLine.substring(separator + 1);
            while (commandLine.startsWith("/")) commandLine = commandLine.substring(1);
        }

        whitespace = firstWhitespace(commandLine);
        String command = whitespace < 0
                ? commandLine : commandLine.substring(0, whitespace);
        return CLIPBOARD_COPY_COMMANDS.contains(command);
    }

    static boolean copiesEntities(final String message) {
        if (message == null) return false;
        for (final String token : message.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (token.length() > 1 && token.charAt(0) == '-'
                    && token.charAt(1) != '-'
                    && token.substring(1).indexOf('e') >= 0) {
                return true;
            }
        }
        return false;
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) return index;
        }
        return -1;
    }

    private void cancelFawePaste(Object event, String reason, Throwable cause) {
        try {
            event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException cancelError) {
            plugin.getLogger().log(Level.SEVERE,
                    "[Sculpt] Failed to cancel unsafe FAWE paste", cancelError);
        }

        String message = "[Sculpt] Cancelled unsafe FAWE paste: " + reason;
        if (cause == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().log(Level.WARNING, message, cause);
        }
    }

    // ========================================================================
    //  Bukkit EntitySpawnEvent (fallback)
    // ========================================================================

    @org.bukkit.event.EventHandler(priority = EventPriority.MONITOR)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof ItemDisplay display)) return;

        String type = display.getPersistentDataContainer()
                .get(SCULPT_TYPE_KEY, PersistentDataType.STRING);
        if (!"root".equals(type)) return;

        Location blockLoc = display.getLocation().toBlockLocation();
        World world = blockLoc.getWorld();
        if (world == null) return;

        for (long delay : PASTE_VERIFY_DELAYS) {
            FoliaScheduler.runRegionTaskLater(plugin, blockLoc,
                () -> queuePositionVerification(world,
                    new BlockPosition(blockLoc.getBlockX(),
                        blockLoc.getBlockY(), blockLoc.getBlockZ())),
                delay);
        }
    }

    // ========================================================================
    //  Position verification
    // ========================================================================

    /**
        * Verify positions where relevant blocks or Sculpt roots were created.
     * Uses reflection to read BlockVector3 coordinates.
     */
    private void scheduleVerification(String worldName, Set<BlockVector3> positions) {
        if (positions.isEmpty()) return;
        Set<BlockVector3> snapshot = Set.copyOf(positions);
        for (long delay : PASTE_VERIFY_DELAYS) {
            FoliaScheduler.runGlobalTaskLater(plugin,
                    () -> verifyPositions(worldName, snapshot), delay);
        }
    }

    private void verifyPositions(String worldName, Set<BlockVector3> positions) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;

        Map<ChunkKey, Set<BlockPosition>> positionsByChunk =
                groupPositionsByChunk(worldName, positions);
        for (Map.Entry<ChunkKey, Set<BlockPosition>> entry : positionsByChunk.entrySet()) {
            queueChunkVerification(world, entry.getKey(), entry.getValue());
        }
    }

    private void queuePositionVerification(World world, BlockPosition position) {
        ChunkKey chunk = new ChunkKey(
                world.getName(), position.x() >> 4, position.z() >> 4);
        queueChunkVerification(world, chunk, Set.of(position));
    }

    private void queueChunkVerification(
            World world, ChunkKey chunk, Collection<BlockPosition> positions) {
        AtomicBoolean schedule = new AtomicBoolean();
        pendingChunkVerifications.compute(chunk, (ignored, pending) -> {
            if (pending == null) {
                pending = ConcurrentHashMap.newKeySet();
                schedule.set(true);
            }
            pending.addAll(positions);
            return pending;
        });
        if (!schedule.get()) return;

        FoliaScheduler.runRegionTask(plugin, world, chunk.x(), chunk.z(),
                () -> reconcileChunk(world, chunk));
    }

    private void reconcileChunk(World world, ChunkKey chunkKey) {
        Set<BlockPosition> positions = pendingChunkVerifications.remove(chunkKey);
        if (positions == null || positions.isEmpty() || !world.getName().equals(chunkKey.world())) {
            return;
        }

        org.bukkit.Chunk chunk = world.getChunkAt(chunkKey.x(), chunkKey.z());
        plugin.reconcilePastedEntities(chunk);

        Collection<ItemDisplay> roots = new ArrayList<>();
        for (BlockPosition position : positions) {
            BlockPosKey key = new BlockPosKey(
                    world.getName(), position.x(), position.y(), position.z());
            var sculptBlock = plugin.getActiveBlock(key);
            if (sculptBlock != null
                    && sculptBlock.rootEntity instanceof BukkitDisplayHandle handle) {
                roots.add(handle.entity());
            }
        }
        SculptBlockCleaner.cleanOrphanedLeaves(chunk, roots);
    }

    static Map<ChunkKey, Set<BlockPosition>> groupPositionsByChunk(
            String worldName, Collection<BlockVector3> positions) {
        Map<ChunkKey, Set<BlockPosition>> grouped = new HashMap<>();
        for (BlockVector3 position : positions) {
            BlockPosition block = new BlockPosition(
                    position.x(), position.y(), position.z());
            ChunkKey chunk = new ChunkKey(
                    worldName, block.x() >> 4, block.z() >> 4);
            grouped.computeIfAbsent(chunk, ignored -> new LinkedHashSet<>()).add(block);
        }
        return grouped;
    }

    record ChunkKey(String world, int x, int z) {
    }

    record BlockPosition(int x, int y, int z) {
    }
}
