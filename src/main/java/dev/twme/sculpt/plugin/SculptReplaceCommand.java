package dev.twme.sculpt.plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Slab;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.StringUtil;
import org.joml.Quaternionf;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.assets.shape.BlockVisualShapeCache;
import dev.twme.sculpt.assets.shape.BlockVisualShapeResolver;
import dev.twme.sculpt.assets.shape.VisualShape;
import dev.twme.sculpt.assets.shape.VoxelMask;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.OctreeVoxelShape;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.core.VariantResolution;
import dev.twme.sculpt.editor.RegionSelection;
import dev.twme.sculpt.plugin.VisualShapeReplacePlanner.Bounds;
import dev.twme.sculpt.plugin.VisualShapeReplacePlanner.Position;
import dev.twme.sculpt.plugin.VisualShapeReplacePlanner.Source;
import dev.twme.sculpt.transport.bukkit.BukkitTransportSession;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

/** Implements {@code /sculpt replace <block-data>} for wand selections. */
public final class SculptReplaceCommand {

    static final int MAX_BLOCKS_PER_BATCH = 512;
    static final long DEFAULT_MAX_VOLUME = 32_768L;
    static final long DEFAULT_MAX_GENERATED_LEAVES = 131_072L;
    private static final String MAX_VOLUME_PATH =
        "regionOperations.replace.maxVolume";
    private static final String MAX_GENERATED_LEAVES_PATH =
        "regionOperations.replace.maxGeneratedLeaves";

    private final Sculpt plugin;
    private final Set<UUID> activeOperations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SculptReplaceCommand(final Sculpt plugin) {
        this.plugin = plugin;
    }

    public boolean execute(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender, "command.sculpt.replace.player_only");
            return true;
        }
        if (!checkPermission(sender)) return true;
        if (args.length != 1) {
            MessageUtil.sendTranslated(sender, "command.sculpt.replace.usage");
            return true;
        }

        final RegionSelection selection = plugin.getWandListener() == null
            ? null : plugin.getWandListener().getSelection(player);
        if (selection == null || !selection.isValid()) {
            MessageUtil.sendTranslated(sender, "command.sculpt.replace.no_selection");
            return true;
        }

        final long maximumVolume = Math.max(1L, plugin.getConfig().getLong(
            MAX_VOLUME_PATH, DEFAULT_MAX_VOLUME));
        if (selection.volume() > maximumVolume) {
            MessageUtil.sendTranslated(sender, "command.sculpt.replace.too_large",
                selection.volume(), maximumVolume);
            return true;
        }

        final BlockData replacement;
        try {
            replacement = normalizeTargetBlockData(
                Bukkit.createBlockData(normalizeBlockData(args[0])));
        } catch (final IllegalArgumentException invalidBlockData) {
            MessageUtil.sendTranslated(sender,
                "command.sculpt.replace.invalid_material", args[0]);
            return true;
        }

        final SculptDisplayMode displayMode = plugin.displayModeFor(player);
        final Material material = replacement.getMaterial();
        if (!isBakeableTarget(material, candidate ->
                !plugin.isMaterialSupported(candidate, displayMode))) {
            MessageUtil.sendTranslated(sender,
                "command.sculpt.replace.non_bakeable", material.getKey());
            return true;
        }

        final BlockVisualShapeCache shapeCache = plugin.getVisualShapeCache();
        if (plugin.getHeadResolver() == null || shapeCache == null) {
            MessageUtil.sendTranslated(sender, "command.sculpt.replace.not_ready");
            return true;
        }
        if (!activeOperations.add(player.getUniqueId())) {
            MessageUtil.sendTranslated(sender,
                "command.sculpt.replace.already_running");
            return true;
        }

        final VariantResolution rawVariant = plugin.getHeadResolver().resolveVariant(
            replacement, plugin.gridSizeFor(player));
        final VariantResolution variant = new VariantResolution(
            new Quaternionf(rawVariant.rotation()), rawVariant.matchedVariant());
        final ItemStack probeItem = material.isItem()
            ? new ItemStack(material)
            : player.getInventory().getItemInMainHand().clone();
        final long maximumGeneratedLeaves = Math.max(1L,
            plugin.getConfig().getLong(MAX_GENERATED_LEAVES_PATH,
                DEFAULT_MAX_GENERATED_LEAVES));

        MessageUtil.sendTranslated(sender, "command.sculpt.replace.started",
            replacement.getAsString(), selection.volume());
        new ReplaceOperation(player, selection, replacement, variant,
            probeItem, plugin.fillModeFor(player), displayMode,
            maximumGeneratedLeaves, shapeCache).scheduleNextSnapshot();
        return true;
    }

    public List<String> complete(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(SculptPermissions.REPLACE) || args.length != 1) {
            return List.of();
        }
        final List<String> materials = new ArrayList<>();
        final SculptDisplayMode displayMode = sender instanceof Player player
            ? plugin.displayModeFor(player) : SculptDisplayMode.HEAD;
        for (final Material material : Material.values()) {
            if (material.isLegacy()
                    || !isBakeableTarget(material, candidate ->
                        !plugin.isMaterialSupportedIfKnown(candidate, displayMode))) {
                continue;
            }
            materials.add(material.getKey().getNamespace().equals("minecraft")
                ? material.getKey().getKey() : material.getKey().toString());
        }
        materials.sort(Comparator.naturalOrder());
        return StringUtil.copyPartialMatches(
            args[0].toLowerCase(Locale.ROOT), materials, new ArrayList<>());
    }

    private final class ReplaceOperation {
        private final Player player;
        private final RegionSelection selection;
        private final World world;
        private final BlockData replacement;
        private final VariantResolution variant;
        private final ItemStack probeItem;
        private final FillMode fillMode;
        private final SculptDisplayMode displayMode;
        private final long maximumGeneratedLeaves;
        private final BlockVisualShapeCache shapeCache;
        private final Bounds bounds;
        private final ArrayDeque<BatchCursor> snapshotPending = new ArrayDeque<>();
        private final Map<Position, SourceSnapshot> snapshots = new LinkedHashMap<>();
        private final ArrayDeque<ApplyCursor> applyPending = new ArrayDeque<>();
        private final OperationResult result = new OperationResult();
        private Sculpt.ActiveBlockReservation reservation;

        private ReplaceOperation(
                final Player player,
                final RegionSelection selection,
                final BlockData replacement,
                final VariantResolution variant,
                final ItemStack probeItem,
                final FillMode fillMode,
                final SculptDisplayMode displayMode,
                final long maximumGeneratedLeaves,
                final BlockVisualShapeCache shapeCache) {
            this.player = player;
            this.selection = selection;
            this.world = selection.world();
            this.replacement = replacement.clone();
            this.variant = new VariantResolution(
                new Quaternionf(variant.rotation()), variant.matchedVariant());
            this.probeItem = probeItem.clone();
            this.fillMode = fillMode;
            this.displayMode = displayMode;
            this.maximumGeneratedLeaves = maximumGeneratedLeaves;
            this.shapeCache = shapeCache;
            this.bounds = new Bounds(selection.minX(), selection.maxX(),
                selection.minY(), selection.maxY(),
                selection.minZ(), selection.maxZ());
            for (final RegionBatch batch : createBatches(selection)) {
                snapshotPending.addLast(new BatchCursor(batch));
            }
        }

        private void scheduleNextSnapshot() {
            if (!player.isOnline() || plugin.isDisabling()) {
                snapshotPending.clear();
                finishOperation();
                return;
            }
            while (!snapshotPending.isEmpty()) {
                final BatchCursor cursor = snapshotPending.getFirst();
                final RegionBatch batch = cursor.batch;
                final Location owner = new Location(world,
                    batch.minX(), batch.minY(), batch.minZ());
                try {
                    FoliaScheduler.runRegionTaskLater(plugin, owner,
                        () -> runSnapshotSlice(cursor), 1L);
                    return;
                } catch (final RuntimeException schedulingFailure) {
                    recordFailure(cursor.x(), cursor.y(), cursor.z(),
                        cursor.remaining(), schedulingFailure);
                    snapshotPending.removeFirst();
                }
            }
            resolveShapes();
        }

        private void runSnapshotSlice(final BatchCursor cursor) {
            if (snapshotPending.peekFirst() != cursor) return;
            if (!player.isOnline() || plugin.isDisabling()) {
                snapshotPending.clear();
                finishOperation();
                return;
            }
            try {
                world.getChunkAt(cursor.batch.chunkX(), cursor.batch.chunkZ());
                processSnapshotSlice(cursor);
            } catch (final RuntimeException batchFailure) {
                recordFailure(cursor.x(), cursor.y(), cursor.z(),
                    cursor.remaining(), batchFailure);
                cursor.finish();
            }
            if (cursor.finished()) snapshotPending.removeFirst();
            scheduleNextSnapshot();
        }

        private void processSnapshotSlice(final BatchCursor cursor) {
            int budget = MAX_BLOCKS_PER_BATCH;
            boolean processedAny = false;
            while (!cursor.finished()) {
                final int x = cursor.x();
                final int y = cursor.y();
                final int z = cursor.z();
                int work = 1;
                try {
                    final Block block = world.getBlockAt(x, y, z);
                    final BlockPosKey key = new BlockPosKey(world.getName(), x, y, z);
                    final SculptBlock sculpt = activeSculpt(key);
                    work = sculpt == null ? 1 : OctreeVoxelShape.allLeafCount(sculpt.root);
                    if (processedAny && work > budget) return;
                    snapshots.put(new Position(x, y, z),
                        snapshot(block, sculpt));
                } catch (final RuntimeException failure) {
                    recordFailure(x, y, z, 1L, failure);
                }
                cursor.advance();
                processedAny = true;
                budget -= Math.min(budget, Math.max(1, work));
                if (budget <= 0) return;
            }
        }

        private SourceSnapshot snapshot(
                final Block block,
                final SculptBlock sculpt) {
            final BlockData worldData = block.getBlockData().clone();
            final boolean writable = plugin.canPlayerBuild(player, block, probeItem);
            if (sculpt != null) {
                return new SourceSnapshot(SourceKind.SCULPT,
                    worldData.getAsString(), null, writable, false,
                    sculpt, sculpt.root.serialize(),
                    OctreeVoxelShape.maskOf(sculpt.root));
            }
            if (worldData.getMaterial().isAir()) {
                return new SourceSnapshot(SourceKind.AIR,
                    worldData.getAsString(), null, writable, true,
                    null, null, null);
            }
            final boolean noCollision = block.getCollisionShape()
                .getBoundingBoxes().isEmpty();
            return new SourceSnapshot(SourceKind.REGULAR,
                worldData.getAsString(), worldData.getAsString(), writable,
                noCollision, null, null, null);
        }

        private void resolveShapes() {
            if (!player.isOnline() || plugin.isDisabling()) {
                finishOperation();
                return;
            }
            final Set<String> states = new HashSet<>();
            for (final SourceSnapshot snapshot : snapshots.values()) {
                if (snapshot.kind() == SourceKind.REGULAR
                        && snapshot.writable() && !snapshot.noCollision()) {
                    states.add(snapshot.visualState());
                }
            }

            try {
                shapeCache.resolveAllAndApply(states, this::buildPreflight)
                    .whenCompleteAsync((plan, failure) -> {
                    if (failure != null) {
                        result.firstFailure.compareAndSet(null,
                            new OperationFailure("shape-preflight", failure));
                        result.failed.increment();
                        abort("command.sculpt.replace.preflight_failed");
                        return;
                    }
                    acceptPreflight(plan);
                }, FoliaScheduler.globalExecutor(plugin));
            } catch (final RuntimeException schedulingFailure) {
                result.firstFailure.compareAndSet(null,
                    new OperationFailure("shape-preflight", schedulingFailure));
                result.failed.increment();
                abort("command.sculpt.replace.preflight_failed");
            }
        }

        private PreflightPlan buildPreflight(
                final Map<String, BlockVisualShapeResolver.Resolution> resolved) {
            final Map<Position, Candidate> candidates = new LinkedHashMap<>();
            final Set<Position> blocked = new HashSet<>();
            long protectedSources = 0L;
            long noCollisionSources = 0L;
            long unsupportedSources = 0L;

            for (final Map.Entry<Position, SourceSnapshot> entry : snapshots.entrySet()) {
                final Position position = entry.getKey();
                final SourceSnapshot snapshot = entry.getValue();
                if (!snapshot.writable()) {
                    blocked.add(position);
                    if (snapshot.kind() != SourceKind.AIR) protectedSources++;
                    continue;
                }
                if (snapshot.kind() == SourceKind.AIR) continue;
                if (snapshot.kind() == SourceKind.SCULPT) {
                    candidates.put(position, new Candidate(position,
                        VisualShape.builder().addBlock(
                            VisualShape.BlockOffset.ORIGIN, snapshot.sculptMask()).build()));
                    continue;
                }
                if (snapshot.noCollision()) {
                    blocked.add(position);
                    noCollisionSources++;
                    continue;
                }
                final BlockVisualShapeResolver.Resolution resolution =
                    resolved.get(snapshot.visualState());
                if (resolution == null || !resolution.supported()) {
                    blocked.add(position);
                    unsupportedSources++;
                    continue;
                }
                candidates.put(position, new Candidate(position, resolution.shape()));
            }

            for (final Candidate candidate : candidates.values()) {
                for (final VisualShape.BlockOffset offset
                        : candidate.shape().blocks().keySet()) {
                    final Position target = offset(candidate.position(), offset);
                    if (!bounds.contains(target)) {
                        return PreflightPlan.failure(PreflightFailure.OUT_OF_BOUNDS,
                            target, protectedSources, noCollisionSources,
                            unsupportedSources, 0L);
                    }
                }
            }

            final Set<Position> rejected = new HashSet<>();
            boolean changed;
            do {
                changed = false;
                for (final Candidate candidate : candidates.values()) {
                    if (rejected.contains(candidate.position())) continue;
                    boolean touchesBlocked = false;
                    for (final VisualShape.BlockOffset offset
                            : candidate.shape().blocks().keySet()) {
                        final Position target = offset(candidate.position(), offset);
                        if (!snapshots.containsKey(target) || blocked.contains(target)) {
                            touchesBlocked = true;
                            break;
                        }
                    }
                    if (touchesBlocked) {
                        rejected.add(candidate.position());
                        blocked.add(candidate.position());
                        changed = true;
                    }
                }
            } while (changed);

            final List<Source> sources = new ArrayList<>();
            final Set<Position> participating = new HashSet<>();
            for (final Candidate candidate : candidates.values()) {
                if (rejected.contains(candidate.position())) continue;
                sources.add(new Source(candidate.position(), candidate.shape()));
                participating.add(candidate.position());
            }
            final VisualShapeReplacePlanner.Plan worldPlan =
                VisualShapeReplacePlanner.plan(bounds, sources);
            if (worldPlan.outOfBounds()) {
                return PreflightPlan.failure(PreflightFailure.OUT_OF_BOUNDS,
                    worldPlan.outOfBoundsPosition(), protectedSources,
                    noCollisionSources, unsupportedSources, rejected.size());
            }
            if (worldPlan.generatedLeaves() > maximumGeneratedLeaves) {
                return PreflightPlan.failure(PreflightFailure.TOO_MANY_LEAVES,
                    null, protectedSources, noCollisionSources,
                    unsupportedSources, rejected.size(),
                    worldPlan.generatedLeaves());
            }

            participating.addAll(worldPlan.masks().keySet());
            final List<PlannedChange> changes = new ArrayList<>();
            int newSculptBlocks = 0;
            for (final Position position : participating) {
                final SourceSnapshot snapshot = snapshots.get(position);
                if (snapshot == null) continue;
                final VoxelMask mask = worldPlan.masks()
                    .getOrDefault(position, VoxelMask.empty());
                changes.add(new PlannedChange(position, snapshot, mask));
                if (!mask.isEmpty() && !mask.isFull()
                        && snapshot.kind() != SourceKind.SCULPT) {
                    newSculptBlocks++;
                }
            }
            changes.sort(Comparator
                .comparingInt((PlannedChange change) -> change.position().x())
                .thenComparingInt(change -> change.position().z())
                .thenComparingInt(change -> change.position().y()));
            return PreflightPlan.success(changes, newSculptBlocks,
                worldPlan.generatedLeaves(), protectedSources,
                noCollisionSources, unsupportedSources, rejected.size());
        }

        private void acceptPreflight(final PreflightPlan plan) {
            if (!player.isOnline() || plugin.isDisabling()) {
                finishOperation();
                return;
            }
            result.protectedBlocks.add(plan.protectedSources());
            result.noCollision.add(plan.noCollisionSources());
            result.unsupported.add(plan.unsupportedSources());
            result.blockedSources.add(plan.blockedSources());

            if (plan.failure() == PreflightFailure.OUT_OF_BOUNDS) {
                final Position position = plan.failurePosition();
                abort("command.sculpt.replace.out_of_bounds",
                    formatLocation(world, position.x(), position.y(), position.z()));
                return;
            }
            if (plan.failure() == PreflightFailure.TOO_MANY_LEAVES) {
                abort("command.sculpt.replace.too_many_leaves",
                    plan.generatedLeaves(), maximumGeneratedLeaves);
                return;
            }

            reservation = plugin.reserveSculptBlockSlots(plan.newSculptBlocks());
            if (reservation == null) {
                abort("command.sculpt.replace.limit_reached",
                    plan.newSculptBlocks());
                return;
            }
            for (final ApplyBatch batch : createApplyBatches(plan.changes())) {
                applyPending.addLast(new ApplyCursor(batch));
            }
            scheduleNextApply();
        }

        private void scheduleNextApply() {
            if (!player.isOnline() || plugin.isDisabling()) {
                applyPending.clear();
                finishOperation();
                return;
            }
            while (!applyPending.isEmpty()) {
                final ApplyCursor cursor = applyPending.getFirst();
                final ApplyBatch batch = cursor.batch;
                final PlannedChange first = batch.changes().get(cursor.index);
                final Position position = first.position();
                try {
                    FoliaScheduler.runRegionTaskLater(plugin,
                        new Location(world, position.x(), position.y(), position.z()),
                        () -> runApplySlice(cursor), 1L);
                    return;
                } catch (final RuntimeException schedulingFailure) {
                    recordFailure(position.x(), position.y(), position.z(),
                        cursor.remaining(), schedulingFailure);
                    applyPending.removeFirst();
                }
            }
            finishOperation();
        }

        private void runApplySlice(final ApplyCursor cursor) {
            if (applyPending.peekFirst() != cursor) return;
            if (!player.isOnline() || plugin.isDisabling()) {
                applyPending.clear();
                finishOperation();
                return;
            }
            try {
                world.getChunkAt(cursor.batch.chunkX(), cursor.batch.chunkZ());
                processApplySlice(cursor);
            } catch (final RuntimeException batchFailure) {
                final Position position = cursor.current().position();
                recordFailure(position.x(), position.y(), position.z(),
                    cursor.remaining(), batchFailure);
                cursor.finish();
            }
            if (cursor.finished()) applyPending.removeFirst();
            scheduleNextApply();
        }

        private void processApplySlice(final ApplyCursor cursor) {
            int budget = MAX_BLOCKS_PER_BATCH;
            boolean processedAny = false;
            while (!cursor.finished()) {
                final PlannedChange change = cursor.current();
                final int work = estimateWork(change);
                if (processedAny && work > budget) return;
                try {
                    applyAt(change);
                } catch (final RuntimeException failure) {
                    final Position position = change.position();
                    recordFailure(position.x(), position.y(), position.z(), 1L, failure);
                }
                cursor.advance();
                processedAny = true;
                budget -= Math.min(budget, Math.max(1, work));
                if (budget <= 0) return;
            }
        }

        private int estimateWork(final PlannedChange change) {
            if (!change.mask().isEmpty() && !change.mask().isFull()) {
                return Math.max(1, change.mask().compressedOccupiedLeafCount());
            }
            final SculptBlock existing = change.snapshot().expectedSculpt();
            return existing == null ? 1 : OctreeVoxelShape.allLeafCount(existing.root);
        }

        private void applyAt(final PlannedChange change) {
            final Position position = change.position();
            final BlockPosKey key = new BlockPosKey(
                world.getName(), position.x(), position.y(), position.z());
            final Block block = world.getBlockAt(position.x(), position.y(), position.z());
            final SourceSnapshot snapshot = change.snapshot();
            final SculptBlock existing = snapshot.expectedSculpt();
            if (!stillMatches(block, key, snapshot)) {
                result.stale.increment();
                return;
            }
            if (!plugin.canPlayerBuild(player, block, probeItem)) {
                result.protectedBlocks.increment();
                return;
            }

            if (change.mask().isEmpty()) {
                applyEmpty(block, existing);
            } else if (change.mask().isFull()) {
                applyFull(block, existing);
            } else {
                applyPartial(block, key, existing, change.mask());
            }
        }

        private boolean stillMatches(
                final Block block,
                final BlockPosKey key,
                final SourceSnapshot snapshot) {
            if (snapshot.kind() == SourceKind.SCULPT) {
                final SculptBlock current = activeSculpt(key);
                return current == snapshot.expectedSculpt()
                    && Arrays.equals(current.root.serialize(), snapshot.expectedTree());
            }
            return plugin.getActiveBlock(key) == null
                && block.getBlockData().getAsString().equals(snapshot.worldData());
        }

        private void applyEmpty(final Block block, final SculptBlock existing) {
            if (existing != null) {
                block.setType(Material.AIR, false);
                existing.despawn();
                result.sculptBlocks.increment();
                return;
            }
            if (block.getType().isAir()) {
                result.unchanged.increment();
                return;
            }
            block.setType(Material.AIR, false);
            result.regularBlocks.increment();
        }

        private void applyFull(final Block block, final SculptBlock existing) {
            if (existing != null) {
                final VoxelMask current = OctreeVoxelShape.maskOf(existing.root);
                if (current.isFull()) {
                    existing.configureStrategies(fillMode, displayMode,
                        plugin.getTextBlockRenderer());
                    if (existing.replaceAllMaterials(replacement, variant)) {
                        result.sculptBlocks.increment();
                    } else {
                        result.unchanged.increment();
                    }
                    return;
                }
                block.setBlockData(replacement.clone(), false);
                existing.despawn();
                result.sculptBlocks.increment();
                return;
            }
            if (block.getBlockData().equals(replacement)) {
                result.unchanged.increment();
                return;
            }
            block.setBlockData(replacement.clone(), false);
            result.regularBlocks.increment();
        }

        private void applyPartial(
                final Block block,
                final BlockPosKey key,
                final SculptBlock existing,
                final VoxelMask mask) {
            if (existing != null
                    && OctreeVoxelShape.maskOf(existing.root).equals(mask)) {
                existing.configureStrategies(fillMode, displayMode,
                    plugin.getTextBlockRenderer());
                if (existing.replaceAllMaterials(replacement, variant)) {
                    result.sculptBlocks.increment();
                } else {
                    result.unchanged.increment();
                }
                return;
            }

            final SculptBlock target = prepareTarget(key, mask);
            if (existing != null) {
                if (replaceExisting(block, key, existing, target)) {
                    result.sculptBlocks.increment();
                }
                return;
            }
            if (reservation == null || !reservation.register(key, target)) {
                result.stale.increment();
                return;
            }
            final BlockData before = block.getBlockData().clone();
            try {
                target.enterSculpted();
                result.convertedBlocks.increment();
            } catch (final RuntimeException failure) {
                target.despawn();
                plugin.unregisterSculptBlock(key, target);
                block.setBlockData(before, false);
                throw failure;
            }
        }

        private SculptBlock prepareTarget(
                final BlockPosKey key,
                final VoxelMask mask) {
            final Location location = new Location(
                world, key.x(), key.y(), key.z());
            final SculptBlock target = new SculptBlock(
                world, location, replacement.clone(),
                variant.matchedVariant(), new Quaternionf(variant.rotation()),
                new BukkitTransportSession(world), plugin.getHeadResolver());
            target.configureStrategies(fillMode, displayMode,
                plugin.getTextBlockRenderer());
            OctreeVoxelShape.initialize(target.root, mask, replacement);
            target.rebuildCollisionTopology();
            target.setOnCleared(() -> plugin.unregisterSculptBlock(key, target));
            return target;
        }

        private boolean replaceExisting(
                final Block block,
                final BlockPosKey key,
                final SculptBlock existing,
                final SculptBlock target) {
            final SculptBlock rollback = copyForRollback(key, existing);
            final BlockData before = block.getBlockData().clone();
            if (!plugin.replaceSculptBlock(key, existing, target)) {
                result.stale.increment();
                return false;
            }
            try {
                existing.despawn();
                target.enterSculpted();
            } catch (final RuntimeException failure) {
                boolean rollbackRegistered =
                    plugin.replaceSculptBlock(key, target, rollback);
                if (!rollbackRegistered && plugin.getActiveBlock(key) == null) {
                    rollbackRegistered = plugin.restoreSculptBlock(key, rollback);
                }
                target.despawn();
                if (!rollbackRegistered) plugin.unregisterSculptBlock(key, target);
                if (rollbackRegistered) {
                    try {
                        rollback.enterSculpted();
                    } catch (final RuntimeException rollbackFailure) {
                        rollback.despawn();
                        plugin.unregisterSculptBlock(key, rollback);
                        block.setBlockData(before, false);
                        failure.addSuppressed(rollbackFailure);
                    }
                } else {
                    block.setBlockData(before, false);
                }
                throw failure;
            }
            return true;
        }

        private SculptBlock copyForRollback(
                final BlockPosKey key,
                final SculptBlock source) {
            final SculptBlock copy = new SculptBlock(
                world, new Location(world, key.x(), key.y(), key.z()),
                source.originalBlockData.clone(), source.matchedVariantKey,
                new Quaternionf(source.blockRotation),
                new BukkitTransportSession(world), plugin.getHeadResolver(),
                source.tintArgb);
            copyTree(copy.root, source.root);
            copy.rebuildCollisionTopology();
            copy.storedCoords = copyCoordinates(source.storedCoords);
            copy.setMixed(source.isMixed());
            copy.configureStrategies(source.fillMode(), source.displayMode(),
                plugin.getTextBlockRenderer());
            copy.setOnCleared(() -> plugin.unregisterSculptBlock(key, copy));
            return copy;
        }

        private void abort(final String messageKey, final Object... arguments) {
            result.abortMessage.compareAndSet(null,
                new AbortMessage(messageKey, arguments.clone()));
            finishOperation();
        }

        private void finishOperation() {
            if (!result.finished.compareAndSet(false, true)) return;
            activeOperations.remove(player.getUniqueId());
            if (reservation != null) {
                reservation.close();
                reservation = null;
            }

            final OperationFailure failure = result.firstFailure.get();
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING,
                    "[Sculpt] visual-shape replacement failed at "
                        + failure.location() + " (" + result.failed.sum()
                        + " failed positions; showing first error)",
                    failure.cause());
            }

            FoliaScheduler.runEntityTask(plugin, player, () -> {
                if (!player.isOnline()) return;
                final AbortMessage abort = result.abortMessage.get();
                if (abort != null) {
                    MessageUtil.sendTranslated(player,
                        abort.messageKey(), abort.arguments());
                    return;
                }

                final long changed = result.regularBlocks.sum()
                    + result.sculptBlocks.sum() + result.convertedBlocks.sum();
                if (changed > 0) {
                    MessageUtil.sendTranslated(player,
                        "command.sculpt.replace.completed",
                        result.regularBlocks.sum(), result.sculptBlocks.sum(),
                        result.convertedBlocks.sum());
                } else {
                    MessageUtil.sendTranslated(player,
                        "command.sculpt.replace.no_changes");
                }
                sendCount("command.sculpt.replace.protected",
                    result.protectedBlocks.sum());
                sendCount("command.sculpt.replace.no_collision",
                    result.noCollision.sum());
                sendCount("command.sculpt.replace.unsupported_source",
                    result.unsupported.sum());
                sendCount("command.sculpt.replace.blocked_source",
                    result.blockedSources.sum());
                sendCount("command.sculpt.replace.stale", result.stale.sum());
                sendCount("command.sculpt.replace.failed", result.failed.sum());
            });
        }

        private void sendCount(final String key, final long count) {
            if (count > 0) MessageUtil.sendTranslated(player, key, count);
        }

        private void recordFailure(
                final int x,
                final int y,
                final int z,
                final long failedPositions,
                final Throwable failure) {
            result.failed.add(failedPositions);
            result.firstFailure.compareAndSet(null,
                new OperationFailure(formatLocation(world, x, y, z), failure));
        }
    }

    private SculptBlock activeSculpt(final BlockPosKey key) {
        final SculptBlock sculpt = plugin.getActiveBlock(key);
        return sculpt != null && sculpt.state == SculptBlock.State.SCULPTED
                && !sculpt.despawned ? sculpt : null;
    }

    private static Position offset(
            final Position source,
            final VisualShape.BlockOffset offset) {
        return new Position(source.x() + offset.x(),
            source.y() + offset.y(), source.z() + offset.z());
    }

    private static void copyTree(
            final OctreeNode destination,
            final OctreeNode source) {
        if (source.blockData() != null) {
            destination.setBlockData(source.blockData().clone());
        }
        destination.setTextureCoord(source.textureCoord());
        if (source.isBranch()) {
            destination.subdivide();
            for (int index = 0; index < 8; index++) {
                copyTree(destination.children()[index], source.children()[index]);
            }
            return;
        }
        destination.setPlayerHeadTexture(source.playerHeadTexture());
        if (source.isRemoved()) destination.remove();
    }

    private static Map<String, int[]> copyCoordinates(
            final Map<String, int[]> coordinates) {
        if (coordinates == null) return null;
        final Map<String, int[]> copy = new HashMap<>();
        coordinates.forEach((path, value) -> copy.put(path, value.clone()));
        return copy;
    }

    static boolean isBakeableTarget(
            final Material material,
            final Predicate<Material> nonBakeable) {
        return material != null && isBakeableTarget(
            material, material.isBlock(), material.isAir(), nonBakeable);
    }

    static boolean isBakeableTarget(
            final Material material,
            final boolean block,
            final boolean air,
            final Predicate<Material> nonBakeable) {
        return material != null && block && !air && !nonBakeable.test(material);
    }

    static String normalizeBlockData(final String input) {
        final String normalized = input.trim().toLowerCase(Locale.ROOT);
        final int stateStart = normalized.indexOf('[');
        final String id = stateStart < 0
            ? normalized : normalized.substring(0, stateStart);
        return id.contains(":") ? normalized : "minecraft:" + normalized;
    }

    static BlockData normalizeTargetBlockData(final BlockData input) {
        final BlockData normalized = input.clone();
        if (normalized instanceof Slab slab) slab.setType(Slab.Type.DOUBLE);
        return normalized;
    }

    static List<RegionBatch> createBatches(final RegionSelection selection) {
        final List<RegionBatch> batches = new ArrayList<>();
        final int minChunkX = selection.minX() >> 4;
        final int maxChunkX = selection.maxX() >> 4;
        final int minChunkZ = selection.minZ() >> 4;
        final int maxChunkZ = selection.maxZ() >> 4;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            final int minX = Math.max(selection.minX(), chunkX << 4);
            final int maxX = Math.min(selection.maxX(), (chunkX << 4) + 15);
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                final int minZ = Math.max(selection.minZ(), chunkZ << 4);
                final int maxZ = Math.min(selection.maxZ(), (chunkZ << 4) + 15);
                final int layerSize = (maxX - minX + 1) * (maxZ - minZ + 1);
                final int layersPerBatch = Math.max(
                    1, MAX_BLOCKS_PER_BATCH / layerSize);
                for (int minY = selection.minY();
                        minY <= selection.maxY(); minY += layersPerBatch) {
                    final int maxY = Math.min(selection.maxY(),
                        minY + layersPerBatch - 1);
                    batches.add(new RegionBatch(chunkX, chunkZ,
                        minX, maxX, minY, maxY, minZ, maxZ));
                }
            }
        }
        return batches;
    }

    private static List<ApplyBatch> createApplyBatches(
            final List<PlannedChange> changes) {
        final Map<Long, List<PlannedChange>> byChunk = new TreeMap<>();
        for (final PlannedChange change : changes) {
            final int chunkX = change.position().x() >> 4;
            final int chunkZ = change.position().z() >> 4;
            final long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
            byChunk.computeIfAbsent(key, ignored -> new ArrayList<>()).add(change);
        }
        final List<ApplyBatch> batches = new ArrayList<>();
        for (final List<PlannedChange> chunkChanges : byChunk.values()) {
            chunkChanges.sort(Comparator
                .comparingInt((PlannedChange change) -> change.position().y())
                .thenComparingInt(change -> change.position().x())
                .thenComparingInt(change -> change.position().z()));
            final Position first = chunkChanges.getFirst().position();
            batches.add(new ApplyBatch(first.x() >> 4, first.z() >> 4,
                List.copyOf(chunkChanges)));
        }
        return batches;
    }

    private boolean checkPermission(final CommandSender sender) {
        if (sender.hasPermission(SculptPermissions.REPLACE)) return true;
        MessageUtil.sendTranslated(sender, "general.no_permission");
        MessageUtil.sendTranslated(sender, "general.required_perm",
            SculptPermissions.REPLACE);
        return false;
    }

    private static String formatLocation(
            final World world,
            final int x,
            final int y,
            final int z) {
        return world.getName() + "," + x + "," + y + "," + z;
    }

    record RegionBatch(
        int chunkX, int chunkZ,
        int minX, int maxX,
        int minY, int maxY,
        int minZ, int maxZ
    ) {
        long volume() {
            return (long) (maxX - minX + 1)
                * (maxY - minY + 1)
                * (maxZ - minZ + 1);
        }
    }

    private static final class BatchCursor {
        private final RegionBatch batch;
        private final int sizeX;
        private final int sizeZ;
        private final int volume;
        private int index;

        private BatchCursor(final RegionBatch batch) {
            this.batch = batch;
            this.sizeX = batch.maxX() - batch.minX() + 1;
            this.sizeZ = batch.maxZ() - batch.minZ() + 1;
            this.volume = Math.toIntExact(batch.volume());
        }

        private int x() {
            return batch.minX() + (index % (sizeX * sizeZ)) / sizeZ;
        }

        private int y() {
            return batch.minY() + index / (sizeX * sizeZ);
        }

        private int z() {
            return batch.minZ() + index % sizeZ;
        }

        private long remaining() {
            return volume - index;
        }

        private void advance() {
            if (index < volume) index++;
        }

        private void finish() {
            index = volume;
        }

        private boolean finished() {
            return index >= volume;
        }
    }

    private static final class ApplyCursor {
        private final ApplyBatch batch;
        private int index;

        private ApplyCursor(final ApplyBatch batch) {
            this.batch = batch;
        }

        private PlannedChange current() {
            return batch.changes().get(index);
        }

        private long remaining() {
            return batch.changes().size() - index;
        }

        private void advance() {
            if (index < batch.changes().size()) index++;
        }

        private void finish() {
            index = batch.changes().size();
        }

        private boolean finished() {
            return index >= batch.changes().size();
        }
    }

    private enum SourceKind { AIR, REGULAR, SCULPT }

    private record SourceSnapshot(
        SourceKind kind,
        String worldData,
        String visualState,
        boolean writable,
        boolean noCollision,
        SculptBlock expectedSculpt,
        byte[] expectedTree,
        VoxelMask sculptMask
    ) {}

    private record Candidate(Position position, VisualShape shape) {}

    private record PlannedChange(
        Position position,
        SourceSnapshot snapshot,
        VoxelMask mask
    ) {}

    private record ApplyBatch(
        int chunkX,
        int chunkZ,
        List<PlannedChange> changes
    ) {}

    private enum PreflightFailure { NONE, OUT_OF_BOUNDS, TOO_MANY_LEAVES }

    private record PreflightPlan(
        List<PlannedChange> changes,
        int newSculptBlocks,
        long generatedLeaves,
        PreflightFailure failure,
        Position failurePosition,
        long protectedSources,
        long noCollisionSources,
        long unsupportedSources,
        long blockedSources
    ) {
        private static PreflightPlan success(
                final List<PlannedChange> changes,
                final int newSculptBlocks,
                final long generatedLeaves,
                final long protectedSources,
                final long noCollisionSources,
                final long unsupportedSources,
                final long blockedSources) {
            return new PreflightPlan(List.copyOf(changes), newSculptBlocks,
                generatedLeaves, PreflightFailure.NONE, null,
                protectedSources, noCollisionSources,
                unsupportedSources, blockedSources);
        }

        private static PreflightPlan failure(
                final PreflightFailure failure,
                final Position position,
                final long protectedSources,
                final long noCollisionSources,
                final long unsupportedSources,
                final long blockedSources) {
            return failure(failure, position, protectedSources,
                noCollisionSources, unsupportedSources, blockedSources, 0L);
        }

        private static PreflightPlan failure(
                final PreflightFailure failure,
                final Position position,
                final long protectedSources,
                final long noCollisionSources,
                final long unsupportedSources,
                final long blockedSources,
                final long generatedLeaves) {
            return new PreflightPlan(List.of(), 0, generatedLeaves,
                failure, position, protectedSources, noCollisionSources,
                unsupportedSources, blockedSources);
        }
    }

    private static final class OperationResult {
        private final AtomicBoolean finished = new AtomicBoolean();
        private final LongAdder regularBlocks = new LongAdder();
        private final LongAdder sculptBlocks = new LongAdder();
        private final LongAdder convertedBlocks = new LongAdder();
        private final LongAdder unchanged = new LongAdder();
        private final LongAdder protectedBlocks = new LongAdder();
        private final LongAdder noCollision = new LongAdder();
        private final LongAdder unsupported = new LongAdder();
        private final LongAdder blockedSources = new LongAdder();
        private final LongAdder stale = new LongAdder();
        private final LongAdder failed = new LongAdder();
        private final AtomicReference<OperationFailure> firstFailure =
            new AtomicReference<>();
        private final AtomicReference<AbortMessage> abortMessage =
            new AtomicReference<>();
    }

    private record OperationFailure(String location, Throwable cause) {}

    private record AbortMessage(String messageKey, Object[] arguments) {
        private AbortMessage {
            arguments = arguments.clone();
        }

        @Override
        public Object[] arguments() {
            return arguments.clone();
        }
    }
}
