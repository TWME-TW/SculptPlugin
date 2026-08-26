package dev.twme.sculpt.editor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.blueprint.BlueprintData;
import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.blueprint.BlueprintSelectorItem;
import dev.twme.sculpt.blueprint.PasteSettings;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.core.VariantResolution;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.render.TextBlockRenderer;
import dev.twme.sculpt.transport.bukkit.BukkitTransportSession;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;

public final class SculptEditListener implements Listener {

    private static final int WHOLE_BLOCK_GRID_SIZE = 1;
    private final Plugin plugin;
    /** Set after async registry load completes. Volatile for cross-thread visibility. */
    private volatile HeadResolver headResolver;
    private final SculptBlockRegistry registry;
    private final BlockPlaceBuildChecker buildChecker;
    private final HoverScheduler hoverScheduler;
    private final Map<Player, PlayerEditSession> sessions = new ConcurrentHashMap<>();
    private final Map<Player, SelectionHighlight> highlights = new ConcurrentHashMap<>();
    private final Map<Player, PreviewHighlight> previews = new ConcurrentHashMap<>();
    private final Set<Player> pendingHoverUpdates = ConcurrentHashMap.newKeySet();
    /** 最後右鍵點擊的 server tick（去重用）。 */
    private final Map<Player, Integer> lastRightClickTick = new ConcurrentHashMap<>();

    public interface SculptBlockRegistry {
        SculptBlock getActiveBlock(BlockPosKey key);
        boolean registerSculptBlock(BlockPosKey key, SculptBlock block);
        boolean replaceSculptBlock(BlockPosKey key, SculptBlock expected, SculptBlock replacement);
        void unregisterSculptBlock(BlockPosKey key);
        void unregisterSculptBlock(BlockPosKey key, SculptBlock block);
        int getPlayerGrid(Player player);
        boolean isSculptMode(Player player);
        default boolean isSculptModeActive(final Player player) {
            return isSculptMode(player);
        }
        BlockData heldBlockData(Player player);
        default CellMaterial heldCellMaterial(Player player) {
            final BlockData data = heldBlockData(player);
            return data == null ? null : CellMaterial.block(data);
        }
        default FillMode fillModeFor(final Player player) {
            return isShulkerMode(player) ? FillMode.SHULKER : FillMode.BARRIER;
        }
        /** Compatibility hook for integrations compiled around the old toggle. */
        @Deprecated
        default boolean isShulkerMode(final Player player) {
            return false;
        }
        default SculptDisplayMode displayModeFor(final Player player) {
            return SculptDisplayMode.HEAD;
        }
        default TextBlockRenderer textBlockRenderer() {
            return null;
        }
        boolean isNonBakeable(Material material);
        default boolean isMaterialSupported(
                final Material material,
                final SculptDisplayMode displayMode) {
            return !isNonBakeable(material);
        }
        boolean isHoverEnabled(Player player);
    }

    @FunctionalInterface
    interface HoverScheduler {
        void schedule(Player player, Runnable task);
    }

    public SculptEditListener(Plugin plugin, HeadResolver headResolver,
                               SculptBlockRegistry registry,
                               BlockPlaceBuildChecker buildChecker) {
        this(plugin, headResolver, registry, buildChecker,
            (player, task) -> FoliaScheduler.runEntityTaskLater(
                plugin, player, task, 1L));
    }

    SculptEditListener(Plugin plugin, HeadResolver headResolver,
                       SculptBlockRegistry registry,
                       BlockPlaceBuildChecker buildChecker,
                       HoverScheduler hoverScheduler) {
        this.plugin = plugin;
        this.headResolver = headResolver;
        this.registry = registry;
        this.buildChecker = buildChecker;
        this.hoverScheduler = hoverScheduler;
    }

    /**
     * Update the head resolver reference after async registry loading completes.
     * Called from the main thread.
     */
    public void setHeadResolver(HeadResolver resolver) {
        this.headResolver = resolver;
    }

    private PlayerEditSession getSession(Player player) {
        return sessions.computeIfAbsent(player, p -> {
            PlayerEditSession s = new PlayerEditSession(p, registry.getPlayerGrid(p));
            s.setPluginHooks(
                key -> registry.getActiveBlock(key),
                (key, block) -> registry.registerSculptBlock(key, block),
                (key, block) -> registry.unregisterSculptBlock(key, block),
                p2 -> registry.getPlayerGrid(p2),
                block -> canEdit(p, block),
                block -> block.configureStrategies(
                    registry.fillModeFor(p), registry.displayModeFor(p),
                    registry.textBlockRenderer()));
            highlights.put(p, new SelectionHighlight(plugin));
            previews.put(p, new PreviewHighlight(plugin));
            return s;
        });
    }

    public void endSession(Player player) {
        PlayerEditSession s = sessions.remove(player);
        if (s != null) s.end();
        SelectionHighlight hl = highlights.remove(player);
        if (hl != null) hl.clear();
        PreviewHighlight pv = previews.remove(player);
        if (pv != null) pv.clear();
        pendingHoverUpdates.remove(player);
        lastRightClickTick.remove(player);
    }

    private boolean isWandTool(Player player) {
        return dev.twme.sculpt.util.WandTool.isWandTool(mainHandItem(player));
    }

    @Nullable
    private static ItemStack mainHandItem(final Player player) {
        final org.bukkit.inventory.PlayerInventory inventory =
            player.getInventory();
        return inventory == null ? null : inventory.getItemInMainHand();
    }

    private static boolean isExplicitContentControl(final ItemStack item) {
        return dev.twme.sculpt.util.WandTool.isWandTool(item)
            || BlueprintSelectorItem.isSelectorTool(item)
            || BlueprintSelectorItem.isBoundItem(item);
    }

    private boolean canUseSculptControls(final Player player) {
        return registry.isSculptModeActive(player);
    }

    private boolean canEdit(final Player player, final Block block) {
        if (buildChecker.canBuild(player, block)) return true;
        MessageUtil.sendTranslatedActionBar(player, "command.sculpt_edit.protected_region");
        return false;
    }

    /** Synchronize an edited block with the player's independent strategies. */
    private boolean ensureStrategiesForPlayer(
            final Player player,
            final SculptBlock sculpt) {
        if (sculpt == null) return true;
        final FillMode fill = registry.fillModeFor(player);
        final SculptDisplayMode display = registry.displayModeFor(player);
        if (sculpt.fillMode() != fill || sculpt.displayMode() != display) {
            if (!canEdit(player, sculpt.pos.getBlock())) return false;
            if (sculpt.displayMode() != display) {
                final Material incompatible = firstUnsupportedMaterial(sculpt, display);
                if (incompatible != null) {
                    MessageUtil.sendTranslatedActionBar(player,
                        "command.sculpt_edit.display_incompatible",
                        incompatible.getKey());
                    return false;
                }
            }
            sculpt.configureStrategies(fill, display, registry.textBlockRenderer());
            sculpt.syncPDC();
        }
        return true;
    }

    @Nullable
    private Material firstUnsupportedMaterial(
            final SculptBlock sculpt,
            final SculptDisplayMode display) {
        for (final OctreeNode leaf : sculpt.root.collectLeaves()) {
            if (leaf.isRemoved() || leaf.playerHeadTexture() != null) continue;
            final BlockData data = leaf.blockData() == null
                ? sculpt.originalBlockData : leaf.blockData();
            if (!registry.isMaterialSupported(data.getMaterial(), display)) {
                return data.getMaterial();
            }
        }
        return null;
    }

    // ========================================================================
    //  Shulker mode: click proxy and collision entity handlers
    // ========================================================================

    /**
     * Left-click on either the click proxy or a collision Shulker.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (headResolver == null) return; // registries still loading
        final Location location = SculptClickTarget.blockLocation(event.getEntity());
        if (location == null) return;

        if (!(event.getDamager() instanceof Player player)) return;

        final ItemStack handItem = mainHandItem(player);
        // Wand tool → let WandListener handle it
        if (dev.twme.sculpt.util.WandTool.isWandTool(handItem)) return;
        final boolean selectorItem = BlueprintSelectorItem.isSelectorTool(
            handItem);
        final BlueprintManager blueprintManager = selectorItem
            && plugin instanceof Sculpt sculpt
                ? sculpt.getBlueprintManager() : null;
        final boolean blueprintSelector = blueprintManager != null
            && blueprintManager.isEnabled();
        if (selectorItem && !blueprintSelector) return;
        if (!blueprintSelector
                && BlueprintSelectorItem.isBoundItem(handItem)) return;
        if (!blueprintSelector && !canUseSculptControls(player)) return;

        final SculptBlock parent = registry.getActiveBlock(BlockPosKey.of(location));
        if (parent == null || !parent.usesEntityInteraction()) return;

        event.setCancelled(true);

        // Blueprint selector tool → select this SculptBlock instead of editing
        if (blueprintSelector) {
            BlueprintManager.SelectionResult result = blueprintManager
                .selectFirstCorner(player, location, parent);
            reportSelectionResult(player, result, location);
            return;
        }

        handleSculptEntityEdit(player, parent, true);
    }

    /**
     * Right-click on either the click proxy or a collision Shulker.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (headResolver == null) return; // registries still loading
        if (event.getHand() != EquipmentSlot.HAND) return;
        final Location location = SculptClickTarget.blockLocation(event.getRightClicked());
        if (location == null) return;

        Player player = event.getPlayer();
        // Wand tool → let WandListener handle it
        if (isWandTool(player)) return;
        final ItemStack handItem = mainHandItem(player);
        final boolean selectorItem = BlueprintSelectorItem.isSelectorTool(handItem);
        final boolean boundItem = BlueprintSelectorItem.isBoundItem(handItem);
        final BlueprintManager blueprintManager = (selectorItem || boundItem)
            && plugin instanceof Sculpt sculpt ? sculpt.getBlueprintManager() : null;
        final boolean blueprintEnabled = blueprintManager != null
            && blueprintManager.isEnabled();
        if ((selectorItem || boundItem) && !blueprintEnabled) return;
        final boolean blueprintSelector = blueprintEnabled
            && selectorItem;
        final boolean boundBlueprint = blueprintEnabled
            && boundItem;
        if (!blueprintSelector && !boundBlueprint && !canUseSculptControls(player)) return;

        final SculptBlock parent = registry.getActiveBlock(BlockPosKey.of(location));
        if (parent == null || !parent.usesEntityInteraction()) return;

        event.setCancelled(true);

        if (blueprintSelector || boundBlueprint) {
            if (!claimRightClick(player)) return;
            if (blueprintSelector
                    && blueprintManager.getSelectionMode(player)
                        == BlueprintManager.SelectionMode.CUBOID) {
                reportSelectionResult(
                    player, blueprintManager.selectSecondCorner(player, location), location);
            } else {
                BlockFace face = sculptClickFace(player, location);
                Location target = adjacentBlockCenter(location, face);
                if (blueprintSelector) {
                    pasteSelectedBlueprint(player, target, face);
                } else {
                    handleBlueprintItemPaste(player, handItem, target, face);
                }
            }
            return;
        }

        handleSculptEntityEdit(player, parent, false);
    }

    private void handleSculptEntityEdit(
            final Player player,
            final SculptBlock clickedSculpt,
            final boolean leftClick) {
        final PlayerEditSession session = getSession(player);

        if (!ensureStrategiesForPlayer(player, clickedSculpt)) return;
        if (session.getPlayerGrid() != WHOLE_BLOCK_GRID_SIZE
                && !clickedSculpt.usesEntityInteraction()) {
            return;
        }

        session.tickHover();
        final VirtualGridHit hit = session.getHoveredHit();
        if (hit == null) return;

        final BlockEditContext context = new BlockEditContext(
            player, session, hit, session.getHoveredSculpt(),
            registry.isSculptModeActive(player));
        if (handleNonBakeableBlock(context, leftClick)) return;

        if (leftClick) {
            handleLeftClick(context);
        } else {
            handleRightClick(context);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (handleBlueprintInteraction(event)) return;

        final Player player = event.getPlayer();
        // Explicit content controls always win over Sculpt mode. Their own
        // listener may handle this event, or vanilla behavior remains intact
        // when the corresponding feature is unavailable.
        if (isExplicitContentControl(mainHandItem(player))) return;
        final boolean sculptMode = registry.isSculptModeActive(player);
        if (headResolver == null) return;
        if (!sculptMode) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        final boolean leftClick = event.getAction() == Action.LEFT_CLICK_BLOCK;
        final boolean rightClick = isRightClick(event.getAction());
        if (!leftClick && !rightClick) return;

        final PlayerEditSession session = getSession(player);
        session.tickHover();
        final VirtualGridHit hit = session.getHoveredHit();
        final SculptBlock sculpt = session.getHoveredSculpt();

        // Fully occupied adaptive blocks remain BARRIER and use this block event.
        // Resolution 1 edits existing SculptBlocks only. Normal blocks retain
        // their vanilla break, place, and interaction behavior.
        if (session.getPlayerGrid() == WHOLE_BLOCK_GRID_SIZE && sculpt == null) return;

        event.setCancelled(true);
        if (sculpt != null && sculpt.usesEntityInteraction()) return;
        if (!prepareStrategies(player, session, sculpt)) return;

        if (hit == null) return;

        final BlockEditContext context = new BlockEditContext(
            player, session, hit, sculpt, sculptMode);
        if (handleNonBakeableBlock(context, leftClick)) return;

        if (leftClick) {
            handleLeftClick(context);
        } else {
            handleRightClick(context);
        }
    }

    private boolean handleBlueprintInteraction(final PlayerInteractEvent event) {
        final BlueprintManager manager = ((Sculpt) plugin).getBlueprintManager();
        if (manager == null || !manager.isEnabled()) return false;
        if (event.getHand() != EquipmentSlot.HAND) return false;

        final Player player = event.getPlayer();
        final ItemStack handItem = mainHandItem(player);
        final Action action = event.getAction();
        if (isRightClick(action) && BlueprintSelectorItem.isBoundItem(handItem)) {
            if (!claimRightClick(player)) return true;
            event.setCancelled(true);
            handleBlueprintItemPaste(player, handItem, event);
            return true;
        }

        if (!BlueprintSelectorItem.isSelectorTool(handItem)) return false;
        event.setCancelled(true);
        if (action == Action.LEFT_CLICK_BLOCK) {
            handleSelectorSelect(player, event);
        } else if (isRightClick(action) && claimRightClick(player)) {
            handleSelectorPaste(player, event);
        }
        return true;
    }

    private boolean prepareStrategies(
            final Player player,
            final PlayerEditSession session,
            @Nullable final SculptBlock sculpt) {
        if (sculpt == null || session.getPlayerGrid() == WHOLE_BLOCK_GRID_SIZE) {
            return true;
        }
        if (!ensureStrategiesForPlayer(player, sculpt)) return false;
        // A block event may have been fired before switching to an AIR-backed
        // strategy. Let its Interaction entity own the next click.
        return !sculpt.usesEntityInteraction();
    }

    private boolean handleNonBakeableBlock(
            final BlockEditContext context,
            final boolean leftClick) {
        if (!leftClick && context.sculptMode()) {
            final CellMaterial held = registry.heldCellMaterial(context.player());
            final String rejection = heldMaterialRejection(
                held, context.session().getPlayerGrid(), registry::isNonBakeable,
                material -> registry.isMaterialSupported(
                    material, registry.displayModeFor(context.player())));
            if (rejection != null) {
                MessageUtil.sendTranslatedActionBar(
                    context.player(), rejection);
                return true;
            }
        }

        if (context.sculpt() != null
                || registry.isMaterialSupported(context.hit().block().getType(),
                    registry.displayModeFor(context.player()))) {
            return false;
        }

        if (leftClick) {
            if (context.sculptMode()) {
                MessageUtil.sendTranslatedActionBar(
                    context.player(), "command.sculpt_edit.non_bakeable_block");
            }
            return true;
        }

        if (!context.sculptMode()) return true;
        final CellMaterial held = registry.heldCellMaterial(context.player());
        if (held != null && claimRightClick(context.player())) {
            edgePlace(
                context.hit().block(), context.hit(), held, context.session());
        }
        return true;
    }

    @Nullable
    static String heldMaterialRejection(
            @Nullable final CellMaterial material,
            final int gridSize,
            final java.util.function.Predicate<Material> isNonBakeable) {
        return heldMaterialRejection(material, gridSize, isNonBakeable,
            candidate -> !isNonBakeable.test(candidate));
    }

    @Nullable
    static String heldMaterialRejection(
            @Nullable final CellMaterial material,
            final int gridSize,
            final java.util.function.Predicate<Material> isNonBakeable,
            final java.util.function.Predicate<Material> isMaterialSupported) {
        if (material == null) return null;
        if (material.blockData().getMaterial() == Material.PLAYER_HEAD) {
            if (gridSize <= WHOLE_BLOCK_GRID_SIZE) {
                return "command.sculpt_edit.player_head_grid_required";
            }
            return material.isTexturedPlayerHead()
                ? null : "command.sculpt_edit.player_head_without_texture";
        }
        final Material blockMaterial = material.blockData().getMaterial();
        return isMaterialSupported.test(blockMaterial)
            ? null : "command.sculpt_edit.non_bakeable_block";
    }

    private void handleLeftClick(final BlockEditContext context) {
        if (context.sculpt() == null
                && !((Sculpt) plugin).sculptConfig().blockBreakListenerEnabled()) {
            return;
        }

        context.session().onLeftClick(headResolver);
        if (context.session().getPlayerGrid() == WHOLE_BLOCK_GRID_SIZE) {
            return;
        }

        final SculptBlock editedBlock = registry.getActiveBlock(
            BlockPosKey.of(context.hit().block()));
        if (editedBlock != null) ensureStrategiesForPlayer(context.player(), editedBlock);
    }

    private void handleRightClick(final BlockEditContext context) {
        if (context.sculpt() != null
                && context.session().getPlayerGrid() == WHOLE_BLOCK_GRID_SIZE) {
            context.session().restoreWholeBlock();
            return;
        }

        final CellMaterial replacement = context.sculptMode()
            ? registry.heldCellMaterial(context.player()) : null;
        if (context.sculptMode() && replacement == null) return;
        if (!claimRightClick(context.player())) return;

        if (context.session().hasGapRestoreTarget()) {
            context.session().onRightClickCell(replacement);
            return;
        }

        if (context.sculpt() != null) {
            handleSculptRightClick(
                context.session(), context.hit(), context.sculpt(),
                context.sculptMode(), replacement);
            return;
        }

        final Block hitBlock = context.hit().block();
        if (!hitBlock.getType().isSolid()) return;
        final CellMaterial sourceMaterial = context.sculptMode()
            ? replacement : CellMaterial.block(hitBlock.getBlockData());
        edgePlace(
            hitBlock, context.hit(), sourceMaterial, context.session());
    }

    private void handleSculptRightClick(
            final PlayerEditSession session,
            final VirtualGridHit hit,
            final SculptBlock sculpt,
            final boolean sculptMode,
            @Nullable final CellMaterial replacement) {
        final int gridSize = session.getPlayerGrid();
        final GridCell adjacentCell = GridCell.adjacentTo(hit);
        final CellMaterial material = sculptMode ? replacement : null;

        if (adjacentCell.isInside(gridSize)) {
            session.onRightClickCell(material);
            return;
        }

        final Block neighborPosition = hit.block().getRelative(
            hit.face().dx, hit.face().dy, hit.face().dz);
        final SculptBlock neighbor = registry.getActiveBlock(
            BlockPosKey.of(neighborPosition));
        if (neighbor != null && neighbor.state == SculptBlock.State.SCULPTED) {
            if (!ensureStrategiesForPlayer(session.player, neighbor)) return;
            final GridCell neighborCell = adjacentCell.wrapped(gridSize);
            session.restoreCellMaterialAt(
                neighbor,
                neighborCell.centerX(gridSize),
                neighborCell.centerY(gridSize),
                neighborCell.centerZ(gridSize),
                gridSize,
                material);
            return;
        }

        final CellMaterial sourceMaterial = material != null
            ? material : sourceMaterialAt(sculpt, hit, gridSize);
        edgePlace(hit.block(), hit, sourceMaterial, session);
    }

    private static CellMaterial sourceMaterialAt(
            final SculptBlock sculpt,
            final VirtualGridHit hit,
            final int gridSize) {
        final OctreeNode leaf = sculpt.leafAt(
            hit.grid16CenterX(gridSize),
            hit.grid16CenterY(gridSize),
            hit.grid16CenterZ(gridSize));
        final BlockData blockData = leaf != null && leaf.blockData() != null
            ? leaf.blockData() : sculpt.originalBlockData;
        return new CellMaterial(blockData,
            leaf == null ? null : leaf.playerHeadTexture());
    }

    private static boolean isRightClick(final Action action) {
        return action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR;
    }

    private record BlockEditContext(
        Player player,
        PlayerEditSession session,
        VirtualGridHit hit,
        @Nullable SculptBlock sculpt,
        boolean sculptMode
    ) {}

    /**
     * 從現有 SculptBlock 邊界面向外延伸，在相鄰方塊建立一個
     * 只含一個 playerGrid cell 的 SculptBlock。
     *
     */
    private void edgePlace(
            final Block sourceBlock,
            final VirtualGridHit hit,
            final CellMaterial sourceMaterial,
            final PlayerEditSession session) {
        final Block targetBlock = sourceBlock.getRelative(
            hit.face().dx, hit.face().dy, hit.face().dz);
        final BlockPosKey targetKey = BlockPosKey.of(targetBlock);
        final SculptBlock existing = registry.getActiveBlock(targetKey);
        if (!canReplaceEdgeTarget(targetBlock, existing)) return;
        if (!canEdit(session.player, targetBlock)) return;

        final int gridSize = session.getPlayerGrid();
        final GridCell targetCell = GridCell.adjacentTo(hit).wrapped(gridSize);
        final SculptBlock replacement = createEdgeBlock(
            targetBlock, targetKey, sourceMaterial, targetCell, gridSize,
            session.player);
        final boolean registered = existing != null
            ? registry.replaceSculptBlock(targetKey, existing, replacement)
            : registry.registerSculptBlock(targetKey, replacement);
        if (!registered) {
            MessageUtil.sendTranslatedActionBar(
                session.player, "command.sculpt_edit.limit_reached");
            return;
        }
        if (existing != null) existing.despawn();
        replacement.enterSculpted();

    }

    private boolean canReplaceEdgeTarget(
            final Block targetBlock,
            @Nullable final SculptBlock existing) {
        if (targetBlock.getType().isSolid()
                && targetBlock.getType() != Material.BARRIER) {
            return false;
        }
        return existing == null
            || existing.state != SculptBlock.State.SCULPTED
            || !hasVisibleLeaves(existing);
    }

    private static boolean hasVisibleLeaves(final SculptBlock block) {
        final List<OctreeNode> leaves = new ArrayList<>();
        block.root.collectAllLeaves(leaves);
        return leaves.stream().anyMatch(leaf -> !leaf.isRemoved());
    }

    private SculptBlock createEdgeBlock(
            final Block targetBlock,
            final BlockPosKey targetKey,
            final CellMaterial sourceMaterial,
            final GridCell targetCell,
            final int gridSize,
            final Player player) {
        final BlockData sourceData = sourceMaterial.blockData();
        final VariantResolution resolution = sourceMaterial.isTexturedPlayerHead()
            ? new VariantResolution(new org.joml.Quaternionf(), "")
            : headResolver.resolveVariant(sourceData, gridSize);
        final BukkitTransportSession transportSession =
            new BukkitTransportSession(targetBlock.getWorld());
        final SculptBlock block = new SculptBlock(
            targetBlock.getWorld(), targetBlock.getLocation(), sourceData.clone(),
            resolution.matchedVariant(), resolution.rotation(),
            transportSession, headResolver);
        block.configureStrategies(
            registry.fillModeFor(player),
            registry.displayModeFor(player),
            registry.textBlockRenderer());
        block.setOnCleared(() -> registry.unregisterSculptBlock(targetKey, block));
        block.initSingleCell(
            targetCell.centerX(gridSize),
            targetCell.centerY(gridSize),
            targetCell.centerZ(gridSize),
            Integer.bitCount(gridSize - 1),
            sourceMaterial.playerHeadTexture());
        return block;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;
        final Location to = event.getTo();
        if (to == null || samePose(event.getFrom(), to)) return;

        final Player player = event.getPlayer();
        if (!canUseSculptControls(player) || !registry.isHoverEnabled(player)) {
            pendingHoverUpdates.remove(player);
            clearAll(player);
            return;
        }

        if (!pendingHoverUpdates.add(player)) return;
        try {
            hoverScheduler.schedule(player, () -> updateHover(player));
        } catch (final RuntimeException error) {
            pendingHoverUpdates.remove(player);
            throw error;
        }
    }

    private void updateHover(final Player player) {
        if (!pendingHoverUpdates.remove(player)) return;
        if (!canUseSculptControls(player)
                || !registry.isHoverEnabled(player)
                || isExplicitContentControl(mainHandItem(player))) {
            clearAll(player);
            return;
        }

        final PlayerEditSession ses = getSession(player);
        final int playerGrid = ses.tickHoverAndGetGrid();

        final VirtualGridHit hit = ses.getHoveredHit();
        final SculptBlock sculpt = ses.getHoveredSculpt();

        if (sculpt != null && hit != null) {
            final SelectionHighlight hl = highlights.get(player);
            if (hl != null) hl.show(hit, playerGrid, player);
            clearPreview(player);
        } else if (hit != null) {
            final PreviewHighlight pv = previews.get(player);
            if (pv != null) pv.show(hit, playerGrid, player);
            clearHighlight(player);
        } else {
            clearAll(player);
        }
    }

    static boolean samePose(final Location from, final Location to) {
        return from.getWorld() == to.getWorld()
            && from.getX() == to.getX()
            && from.getY() == to.getY()
            && from.getZ() == to.getZ()
            && from.getYaw() == to.getYaw()
            && from.getPitch() == to.getPitch();
    }

    @EventHandler
    public void onItemHeldChange(PlayerItemHeldEvent event) {
        final Player player = event.getPlayer();
        final org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
        final ItemStack nextItem = inventory == null
            ? null : inventory.getItem(event.getNewSlot());
        if (!registry.isSculptModeActive(player)
                || isExplicitContentControl(nextItem)) {
            endSession(player);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        endSession(event.getPlayer());
    }

    private void clearAll(Player player) { clearHighlight(player); clearPreview(player); }
    private void clearHighlight(Player player) {
        SelectionHighlight hl = highlights.get(player);
        if (hl != null) hl.clear();
    }
    private void clearPreview(Player player) {
        PreviewHighlight pv = previews.get(player);
        if (pv != null) pv.clear();
    }

    // ====================== 藍圖互動輔助 ======================

    /**
     * 處理藍圖物品的右鍵貼上（已綁定 bluepint_id 的任意物品）。
     */
    private void handleBlueprintItemPaste(Player player, ItemStack item, PlayerInteractEvent event) {
        handleBlueprintItemPaste(
            player, item, getPasteTarget(player, event), pasteFace(event));
    }

    private void handleBlueprintItemPaste(Player player, ItemStack item,
                                          Location target, @Nullable BlockFace clickedFace) {
        UUID bpId = BlueprintSelectorItem.getBlueprintId(item);
        if (bpId == null) return;

        BlueprintData data = loadBlueprintById(player, bpId);
        if (data == null) {
            MessageUtil.sendTranslated(player, "command.sculpt.blueprint.paste.not_found", bpId.toString());
            return;
        }

        BlueprintManager bp = ((Sculpt) plugin).getBlueprintManager();
        PasteSettings settings = BlueprintSelectorItem.getPasteSettings(
            item, bp.getPlayerSettings(player.getUniqueId()));
        String err = bp.pasteBlueprint(player, data, target, settings, clickedFace);
        if (err != null) {
            MessageUtil.sendTranslated(player, err);
        } else {
            MessageUtil.sendTranslated(player, "command.sculpt.blueprint.paste.success", data.name());
            if (plugin.getConfig().getBoolean("blueprint.consumeItemAfterPaste", false)
                    && player.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                item.subtract(1);
            }
        }
    }

    /**
     * 處理選取工具左鍵：選取視線所指的 SculptBlock。
     */
    private void handleSelectorSelect(Player player, PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        BlockPosKey key = new BlockPosKey(clicked.getWorld().getName(), clicked.getX(), clicked.getY(), clicked.getZ());
        SculptBlock sb = registry.getActiveBlock(key);
        BlueprintManager.SelectionResult result = ((Sculpt) plugin).getBlueprintManager()
            .selectFirstCorner(player, clicked.getLocation(), sb);
        reportSelectionResult(player, result, clicked.getLocation());
    }

    /**
     * 處理選取工具右鍵：將已選取的 SculptBlock 作為隱式藍圖貼上。
     */
    private void handleSelectorPaste(Player player, PlayerInteractEvent event) {
        BlueprintManager bp = ((Sculpt) plugin).getBlueprintManager();
        if (bp.getSelectionMode(player) == BlueprintManager.SelectionMode.CUBOID) {
            Location corner = getSelectionTarget(player, event);
            reportSelectionResult(
                player, bp.selectSecondCorner(player, corner), corner);
            return;
        }
        pasteSelectedBlueprint(player, getPasteTarget(player, event), pasteFace(event));
    }

    private void pasteSelectedBlueprint(Player player, Location target,
                                        @Nullable BlockFace clickedFace) {
        BlueprintManager bp = ((Sculpt) plugin).getBlueprintManager();
        String accessError = bp.selectionAccessError(player);
        if (accessError != null) {
            MessageUtil.sendTranslated(player, accessError);
            return;
        }
        BlueprintData implicit = bp.createBlueprintFromSelection(
            player, "selected", BlueprintData.Visibility.PRIVATE);
        if (implicit == null) {
            MessageUtil.sendTranslatedActionBar(player,
                bp.hasSelection(player)
                    ? "command.sculpt.blueprint.save.empty_selection"
                    : "command.sculpt.blueprint.save.no_selection");
            return;
        }
        PasteSettings settings = bp.getPlayerSettings(player.getUniqueId());
        String err = bp.pasteBlueprint(player, implicit, target, settings, clickedFace);
        if (err != null) {
            MessageUtil.sendTranslated(player, err);
        } else {
            MessageUtil.sendTranslated(player, "command.sculpt.blueprint.paste.success", "selected");
        }
    }

    private Location getSelectionTarget(Player player, PlayerInteractEvent event) {
        if (event.getClickedBlock() != null) return event.getClickedBlock().getLocation();
        Block target = player.getTargetBlockExact(10);
        return target != null ? target.getLocation() : player.getLocation();
    }

    private void reportSelectionResult(
            Player player, BlueprintManager.SelectionResult result, Location location) {
        switch (result) {
            case SINGLE_SELECTED -> MessageUtil.sendTranslatedActionBar(player,
                "command.sculpt.blueprint.select.success",
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
            case FIRST_CORNER_SELECTED -> MessageUtil.sendTranslatedActionBar(player,
                "command.sculpt.blueprint.select.first_corner",
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
            case CUBOID_SELECTED -> MessageUtil.sendTranslatedActionBar(player,
                "command.sculpt.blueprint.select.cuboid_success",
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
            case NOT_SCULPT_BLOCK -> MessageUtil.sendTranslatedActionBar(player,
                "command.sculpt.blueprint.select.not_sculpt");
            case FIRST_CORNER_REQUIRED -> MessageUtil.sendTranslatedActionBar(player,
                "command.sculpt.blueprint.select.first_corner_required");
            case DIFFERENT_WORLD -> MessageUtil.sendTranslatedActionBar(player,
                "command.sculpt.blueprint.select.different_world");
            case SELECTION_TOO_LARGE -> MessageUtil.sendTranslatedActionBar(player,
                "command.sculpt.blueprint.select.too_large");
            case NOT_CUBOID_MODE -> { }
        }
    }

    /**
     * 從物品讀取藍圖 ID 並載入 BlueprintData。
     */
    @Nullable
    private BlueprintData loadBlueprintById(Player player, UUID blueprintId) {
        BlueprintManager bp = ((Sculpt) plugin).getBlueprintManager();
        try {
            BlueprintData data = bp.io().readBlueprint(player.getUniqueId(), blueprintId, false);
            if (data != null) return data;
            data = bp.io().readBlueprint(player.getUniqueId(), blueprintId, true);
            if (data != null) return data;
        } catch (IOException ignored) {}
        return null;
    }

    /** Claim a right-click so Bukkit's paired block/air events run only once. */
    private boolean claimRightClick(final Player player) {
        int currentTick = player.getTicksLived();
        Integer lastTick = lastRightClickTick.get(player);
        if (lastTick != null && currentTick == lastTick) return false;
        lastRightClickTick.put(player, currentTick);
        return true;
    }

    /**
     * 計算貼上目標位置：
     * - 右鍵方塊 → 點擊面外側方塊中心
     * - 右鍵空氣 → 視線所指方塊或玩家位置
     */
    private Location getPasteTarget(Player player, PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block clicked = event.getClickedBlock();
            BlockFace face = event.getBlockFace();
            return clicked.getRelative(face).getLocation().add(0.5, 0.5, 0.5);
        }
        Block target = player.getTargetBlockExact(10);
        if (target != null && target.getType().isSolid()) {
            return target.getLocation().add(0.5, 1, 0.5);
        }
        return player.getLocation();
    }

    @Nullable
    private BlockFace pasteFace(PlayerInteractEvent event) {
        return event.getAction() == Action.RIGHT_CLICK_BLOCK ? event.getBlockFace() : null;
    }

    static BlockFace sculptClickFace(Player player, Location blockLocation) {
        Location eye = player.getEyeLocation();
        Vector origin = eye.toVector();
        Vector direction = eye.getDirection();
        BoundingBox blockBounds = new BoundingBox(
            blockLocation.getBlockX(), blockLocation.getBlockY(), blockLocation.getBlockZ(),
            blockLocation.getBlockX() + 1.0, blockLocation.getBlockY() + 1.0,
            blockLocation.getBlockZ() + 1.0);
        RayTraceResult hit = blockBounds.rayTrace(origin, direction, 10.0);
        if (hit != null && hit.getHitBlockFace() != null) {
            return hit.getHitBlockFace();
        }
        return faceTowardPoint(origin, blockBounds.getCenter());
    }

    static BlockFace faceTowardPoint(Vector point, Vector center) {
        double dx = point.getX() - center.getX();
        double dy = point.getY() - center.getY();
        double dz = point.getZ() - center.getZ();
        if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) >= Math.abs(dz)) {
            return dy >= 0.0 ? BlockFace.UP : BlockFace.DOWN;
        }
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0.0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0.0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private static Location adjacentBlockCenter(Location blockLocation, BlockFace face) {
        return blockLocation.getBlock().getRelative(face).getLocation().add(0.5, 0.5, 0.5);
    }

    // ====================== Cell 座標收集 ======================

    /**
     * 收集 SculptBlock 每片葉子的規範化座標（已套用 blockRotation），
     * 用於隱式藍圖貼上時還原正確的 skin。
     */
    private static java.util.Map<String, int[]> collectLeafCoordinates(SculptBlock block) {
        java.util.Map<String, int[]> result = new java.util.HashMap<>();
        List<OctreeNode> leaves = new ArrayList<>();
        block.root.collectAllLeaves(leaves);
        int gridN = 1 << block.maxDepth;
        for (OctreeNode leaf : leaves) {
            int side = leaf.side();
            int gx = leaf.minX() / side;
            int gy = leaf.minY() / side;
            int gz = leaf.minZ() / side;
            ChunkCoord physical = new ChunkCoord(gx, gy, gz);
            ChunkCoord canonical = HeadResolver.rotateCoord(physical, block.blockRotation, gridN);
            result.put(leaf.pathAsString(), new int[]{canonical.x(), canonical.y(), canonical.z()});
        }
        return result;
    }
}
