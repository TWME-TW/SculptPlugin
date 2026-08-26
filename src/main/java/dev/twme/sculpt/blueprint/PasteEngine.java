package dev.twme.sculpt.blueprint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.FaceAttachable;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.transport.bukkit.BukkitTransportSession;
import dev.twme.sculpt.util.BlockEditSounds;
import dev.twme.sculpt.util.FoliaRegionGuard;

/**
 * PasteEngine — 藍圖貼上演算法核心。
 * <p>
 * 處理：
 * - 固定貼上（預設）：完整覆蓋含空洞
 * - 融合貼上（--adhesive）：與既有 SculptBlock 合併
 * - 跨 gridN 自動細分（策略 B）
 * - Cell grid 旋轉映射（--rotate / --ry / --flip）
 */
public class PasteEngine {

    private static final String K = "command.sculpt.blueprint.";

    private final Sculpt plugin;

    public PasteEngine(Sculpt plugin) {
        this.plugin = plugin;
    }

    /**
     * 執行藍圖貼上。
     * <p>
     * 直接將藍圖的完整八元樹拓樸重建到目標 SculptBlock，而非逐 cell 操作演化。
     *
     * @param player   玩家（用於 session 和權限）
     * @param data     藍圖資料
     * @param targetLoc 目標位置（方塊座標）
     * @param settings 貼上設定（含旋轉）
     * @return 錯誤訊息（null = 成功）
     */
    @Nullable
    public String paste(Player player, BlueprintData data, Location targetLoc,
                         PasteSettings settings) {
        return paste(player, data, targetLoc, settings, null);
    }

    /**
     * 執行藍圖貼上，並提供玩家實際點擊面供 FACE 旋轉模式使用。
     */
    @Nullable
    public String paste(Player player, BlueprintData data, Location targetLoc,
                         PasteSettings settings, @Nullable BlockFace clickedFace) {
        World world = targetLoc.getWorld();
        if (world == null) return K + "paste.invalid_world";
        if (data == null || settings == null || !validSettings(settings)) {
            return K + "paste.invalid_settings";
        }
        if (!BlueprintValidator.validate(data).valid()) {
            return K + "paste.invalid_data";
        }
        String accessError = regionAccessError(
            player, data, targetLoc, settings, clickedFace);
        if (accessError != null) return accessError;
        final BlockEditSounds.Batch editSounds = BlockEditSounds.batch();
        final String error = data.hasBlockCollection()
            ? pasteBlockCollection(
                player, data, targetLoc, settings, clickedFace, editSounds)
            : pasteSingle(
                player, data, targetLoc, settings, clickedFace, null, editSounds);
        if (error == null) editSounds.play();
        return error;
    }

    /** Reject unsafe synchronous cross-region access before touching world state. */
    @Nullable
    private String regionAccessError(
            Player player, BlueprintData data, Location targetLoc,
            PasteSettings settings, @Nullable BlockFace clickedFace) {
        if (!FoliaRegionGuard.owns(targetLoc)) return K + "folia_cross_region";
        if (!data.hasBlockCollection()) return null;

        World world = targetLoc.getWorld();
        if (world == null) return K + "paste.invalid_world";
        int baseX = targetLoc.getBlockX();
        int baseY = targetLoc.getBlockY();
        int baseZ = targetLoc.getBlockZ();
        SculptBlock anchor = plugin.getActiveBlock(
            new BlockPosKey(world.getName(), baseX, baseY, baseZ));
        int rotationDegrees = resolveRotationDegrees(
            settings, player, targetLoc, data.referenceFacing(), clickedFace, anchor);
        int[] outputSize = transformedDimensions(
            data.sizeX(), data.sizeY(), data.sizeZ(), rotationDegrees);
        return FoliaRegionGuard.ownsCuboid(
            world, baseX, baseZ,
            baseX + outputSize[0] - 1, baseZ + outputSize[2] - 1)
            ? null : K + "folia_cross_region";
    }

    @Nullable
    private String pasteSingle(Player player, BlueprintData data, Location targetLoc,
                               PasteSettings settings, @Nullable BlockFace clickedFace,
                               @Nullable Integer fixedRotationDegrees,
                               BlockEditSounds.Batch editSounds) {
        World world = targetLoc.getWorld();
        if (world == null) return K + "paste.invalid_world";

        int baseX = targetLoc.getBlockX();
        int baseY = targetLoc.getBlockY();
        int baseZ = targetLoc.getBlockZ();
        int gridN = data.gridN();
        BlockData defaultBlockData = data.blockKey() != null
            ? parseBlockData(data.blockKey())
            : null;

        // 1. 反序列化藍圖八元樹
        OctreeNode bpRoot;
        try {
            bpRoot = OctreeNode.deserialize(data.octreeData(), data.maxDepth());
        } catch (IllegalArgumentException e) {
            return K + "paste.deserialize_error";
        }

        BlockPosKey posKey = new BlockPosKey(world.getName(), baseX, baseY, baseZ);
        Block block = world.getBlockAt(baseX, baseY, baseZ);
        if (!plugin.canPlayerBuild(player, block)) {
            return K + "paste.protected_region";
        }
        final BlockData blockBeforePaste = block.getBlockData().clone();
        SculptBlock existing = plugin.getActiveBlock(posKey);

        // 2. 先在記憶體中構建旋轉後的來源樹，避免驗證失敗時破壞既有方塊。
        Map<String, int[]> storedCoords = null;
        OctreeNode srcTree;
        int rotationDegrees = fixedRotationDegrees != null
            ? normalizeDegrees(fixedRotationDegrees)
            : resolveRotationDegrees(
                settings, player, targetLoc, data.referenceFacing(), clickedFace, existing);
        boolean needsRotation = rotationDegrees != 0 || settings.flipAxis() != null;

        if (needsRotation) {
            Map<String, int[]> rotatedCoords = new HashMap<>();
            PasteSettings resolvedRotation = new PasteSettings(
                settings.pasteAir(), settings.overwriteCells(), settings.overwriteBlocks(),
                settings.adhesive(), PasteSettings.RotateMode.NONE, rotationDegrees,
                normalizedFlip(settings.flipAxis()));
            srcTree = buildRotatedOctree(
                bpRoot, gridN, resolvedRotation, rotatedCoords, data.leafCoordinates());
            storedCoords = rotatedCoords;
        } else {
            srcTree = bpRoot;
            Map<String, int[]> leafCoordinates = data.leafCoordinates();
            if (leafCoordinates != null && !leafCoordinates.isEmpty()) {
                storedCoords = new HashMap<>(leafCoordinates);
            }
        }

        // 3. 根據目標內容和覆寫設定產生最終樹。所有工作先離線完成。
        OctreeNode resultTree;
        BlockData resultBlockData = defaultBlockData;
        String resultVariant = data.matchedVariantKey();
        Quaternionf resultRotation = new Quaternionf();
        Map<String, int[]> existingCoords = null;

        if (existing != null) {
            existingCoords = existing.storedCoords;

            if (settings.pasteAir() && settings.overwriteCells()) {
                resultTree = copyTree(srcTree);
            } else {
                resultBlockData = existing.originalBlockData;
                resultVariant = existing.matchedVariantKey;
                resultRotation = new Quaternionf(existing.blockRotation);
                resultTree = mergeTrees(
                    existing.root, srcTree, settings.pasteAir(), settings.overwriteCells());
            }
        } else if (block.getType().isSolid() && !block.getType().isAir()) {
            if (!settings.overwriteBlocks()) {
                return K + "paste.block_in_way";
            }
            if (settings.pasteAir()) {
                resultTree = copyTree(srcTree);
            } else {
                resultBlockData = block.getBlockData();
                resultVariant = null;
                OctreeNode solidBase = new OctreeNode();
                solidBase.setBlockData(block.getBlockData());
                resultTree = mergeTrees(solidBase, srcTree, false, true);
            }
        } else if (settings.pasteAir()) {
            resultTree = copyTree(srcTree);
        } else {
            OctreeNode emptyBase = new OctreeNode();
            emptyBase.remove();
            resultTree = mergeTrees(emptyBase, srcTree, false, true);
        }

        Map<String, int[]> resultCoords = buildResultCoordinates(
            resultTree, srcTree, storedCoords,
            existing != null ? existing.root : null, existingCoords);

        if (existing != null && Arrays.equals(existing.root.serialize(), resultTree.serialize())) {
            return null;
        }

        HeadResolver resolver = plugin.getHeadResolver();
        if (resolver == null) return K + "paste.create_failed";

        // 完全空白的結果代表刪除目標，不建立不可見的 BARRIER SculptBlock。
        if (allLeavesRemoved(resultTree)) {
            BlockData removedData = existing == null
                ? blockBeforePaste : firstNonRemovedBlockData(existing.root);
            if (removedData == null && existing != null) {
                removedData = existing.originalBlockData;
            }
            final boolean removedContent = existing != null
                || !blockBeforePaste.getMaterial().isAir();
            if (existing != null) {
                plugin.unregisterSculptBlock(posKey);
                existing.despawn();
            }
            block.setType(Material.AIR);
            if (removedContent) {
                editSounds.recordBreak(blockCenter(block), removedData);
            }
            return null;
        }

        BlockData constructorData = firstNonRemovedBlockData(resultTree);
        if (constructorData == null) constructorData = resultBlockData;
        if (constructorData == null) constructorData = org.bukkit.Bukkit.createBlockData("minecraft:stone");

        final SculptDisplayMode targetDisplay = plugin.displayModeFor(player);
        if (!supportsSculptMaterials(resultTree, constructorData, targetDisplay)) {
            return K + "paste.unsupported_display";
        }

        SculptBlock target = prepareSculptBlock(
            world, posKey, resultTree, constructorData, resultVariant,
            resultRotation, resultCoords, resolver);
        target.configureStrategies(
            plugin.fillModeFor(player), targetDisplay,
            plugin.getTextBlockRenderer());
        final SculptBlock rollback = existing == null ? null : prepareSculptBlock(
            world, posKey, copyTree(existing.root), existing.originalBlockData,
            existing.matchedVariantKey, new Quaternionf(existing.blockRotation),
            copyCoordinates(existing.storedCoords), resolver);
        if (rollback != null) {
            rollback.configureStrategies(
                existing.fillMode(), existing.displayMode(),
                plugin.getTextBlockRenderer());
        }

        // 4. Reserve the registry slot before mutating the world. Replacements
        // keep their existing slot, while new blocks obey the configured cap.
        final boolean registered = existing != null
            ? plugin.replaceSculptBlock(posKey, existing, target)
            : plugin.registerSculptBlock(posKey, target);
        if (!registered) {
            return existing == null ? K + "paste.limit_reached" : K + "paste.create_failed";
        }

        try {
            if (existing != null) existing.despawn();
            target.enterSculpted();
            target.syncPDC();
        } catch (RuntimeException e) {
            boolean rollbackRegistered = rollback != null
                && plugin.replaceSculptBlock(posKey, target, rollback);
            if (!rollbackRegistered && rollback != null
                    && plugin.getActiveBlock(posKey) == null) {
                rollbackRegistered = plugin.registerSculptBlock(posKey, rollback);
            }
            target.despawn();
            if (!rollbackRegistered) plugin.unregisterSculptBlock(posKey, target);
            if (rollbackRegistered) {
                try {
                    rollback.enterSculpted();
                } catch (RuntimeException rollbackError) {
                    rollback.despawn();
                    plugin.unregisterSculptBlock(posKey, rollback);
                    block.setBlockData(existing.originalBlockData);
                    plugin.getLogger().warning("[Sculpt] blueprint rollback failed at "
                        + posKey + ": " + rollbackError.getMessage());
                }
            } else {
                block.setBlockData(blockBeforePaste);
            }
            plugin.getLogger().warning("[Sculpt] blueprint paste failed at " + posKey + ": " + e.getMessage());
            return K + "paste.create_failed";
        }

        // enterSculpted already starts the block-wide pixel renderer. Keep the
        // legacy head refresh without immediately cancelling that async plan.
        if (target.displayMode() == SculptDisplayMode.HEAD) target.reRender();
        target.markPDCDirty();
        plugin.flushDirtyPDC();
        editSounds.recordPlace(target.centerLoc(), constructorData);

        return null; // 成功
    }

    private boolean supportsSculptMaterials(
            final OctreeNode root,
            @Nullable final BlockData fallback,
            final SculptDisplayMode display) {
        for (final OctreeNode leaf : root.collectLeaves()) {
            if (leaf.isRemoved() || leaf.playerHeadTexture() != null) continue;
            final BlockData data = leaf.blockData() == null ? fallback : leaf.blockData();
            if (data == null
                    || !plugin.isMaterialSupported(data.getMaterial(), display)) {
                return false;
            }
        }
        return true;
    }

    /** Paste a cuboid blueprint while keeping one shared transform for every block. */
    @Nullable
    private String pasteBlockCollection(Player player, BlueprintData data, Location targetLoc,
                                        PasteSettings settings,
                                        @Nullable BlockFace clickedFace,
                                        BlockEditSounds.Batch editSounds) {
        World world = targetLoc.getWorld();
        if (world == null) return K + "paste.invalid_world";

        int baseX = targetLoc.getBlockX();
        int baseY = targetLoc.getBlockY();
        int baseZ = targetLoc.getBlockZ();
        BlockPosKey anchorKey = new BlockPosKey(world.getName(), baseX, baseY, baseZ);
        SculptBlock anchor = plugin.getActiveBlock(anchorKey);
        int rotationDegrees = resolveRotationDegrees(
            settings, player, targetLoc, data.referenceFacing(), clickedFace, anchor);

        List<PlacedBlock> placements = new ArrayList<>();
        Set<BlockOffset> occupied = new HashSet<>();
        for (BlueprintBlockData source : data.blocks()) {
            BlockOffset offset = transformBlockPosition(
                source.x(), source.y(), source.z(), data.sizeX(), data.sizeY(), data.sizeZ(),
                rotationDegrees, settings.flipAxis());
            BlockData regularBlockData = null;
            if (source.isRegularBlock()) {
                regularBlockData = parseBlockData(source.blockData());
                if (regularBlockData == null) return K + "paste.invalid_data";
                try {
                    transformRegularBlockData(
                        regularBlockData, rotationDegrees, settings.flipAxis());
                } catch (RuntimeException e) {
                    return K + "paste.invalid_data";
                }
            }
            occupied.add(offset);
            placements.add(new PlacedBlock(source, offset, regularBlockData));
        }

        int[] outputSize = transformedDimensions(
            data.sizeX(), data.sizeY(), data.sizeZ(), rotationDegrees);
        Set<BlockPosKey> newSculptPositions = new HashSet<>();
        Set<BlockPosKey> removedSculptPositions = new HashSet<>();
        for (PlacedBlock placement : placements) {
            BlockOffset offset = placement.offset();
            Block target = world.getBlockAt(
                baseX + offset.x(), baseY + offset.y(), baseZ + offset.z());
            if (!plugin.canPlayerBuild(player, target)) return K + "paste.protected_region";

            BlockPosKey key = BlockPosKey.of(target.getLocation());
            SculptBlock existing = plugin.getActiveBlock(key);
            if (sourceIsRegular(placement) && existing != null
                    && !settings.overwriteBlocks()) {
                return K + "paste.block_in_way";
            }
            if (sourceIsRegular(placement) && existing != null) {
                removedSculptPositions.add(key);
            }
            if (existing == null) {
                boolean occupiedByWorldBlock = !target.getType().isAir()
                    && (sourceIsRegular(placement) || target.getType().isSolid());
                if (occupiedByWorldBlock && !settings.overwriteBlocks()) {
                    return K + "paste.block_in_way";
                }
                if (placement.source().isSculptBlock()) newSculptPositions.add(key);
            }
        }

        if (settings.pasteAir()) {
            for (int x = 0; x < outputSize[0]; x++) {
                for (int y = 0; y < outputSize[1]; y++) {
                    for (int z = 0; z < outputSize[2]; z++) {
                        if (occupied.contains(new BlockOffset(x, y, z))) continue;
                        Block gap = world.getBlockAt(baseX + x, baseY + y, baseZ + z);
                        if (!plugin.canPlayerBuild(player, gap)) {
                            return K + "paste.protected_region";
                        }
                    }
                }
            }
        }

        int activeLimit = plugin.sculptConfig().maxActiveSculptBlocks();
        if (activeLimit > 0
                && plugin.getActiveBlocks().size() - removedSculptPositions.size()
                    + newSculptPositions.size() > activeLimit) {
            return K + "paste.limit_reached";
        }
        if (placements.stream().anyMatch(p -> p.source().isSculptBlock())
                && plugin.getHeadResolver() == null) {
            return K + "paste.create_failed";
        }

        for (PlacedBlock placement : placements) {
            BlueprintBlockData source = placement.source();
            BlockOffset offset = placement.offset();
            Location location = new Location(world,
                baseX + offset.x(), baseY + offset.y(), baseZ + offset.z());
            if (source.isRegularBlock()) {
                pasteRegularBlock(
                    location.getBlock(), placement.regularBlockData(), editSounds);
                continue;
            }
            BlueprintData single = new BlueprintData(
                data.blueprintId(), data.name(), data.description(), data.createdTimestamp(),
                data.lastModifiedTimestamp(), data.minecraftVersion(), source.blockKey(),
                source.matchedVariantKey(), source.isMixed(), source.maxDepth(), source.gridN(),
                source.octreeData(), source.leafCoordinates(), data.referenceFacing(),
                data.visibility(), data.editToken());
            String error = pasteSingle(
                player, single, location, settings, clickedFace,
                rotationDegrees, editSounds);
            if (error != null) return error;
        }

        if (settings.pasteAir()) {
            clearUnoccupiedBlocks(
                world, baseX, baseY, baseZ, outputSize, occupied,
                settings.overwriteBlocks(), editSounds);
            plugin.flushDirtyPDC();
        }
        return null;
    }

    private static boolean sourceIsRegular(PlacedBlock placement) {
        return placement.source().isRegularBlock();
    }

    private void pasteRegularBlock(
            Block block,
            BlockData data,
            BlockEditSounds.Batch editSounds) {
        BlockPosKey key = BlockPosKey.of(block);
        SculptBlock existing = plugin.getActiveBlock(key);
        final boolean changed = existing != null || !block.getBlockData().equals(data);
        if (existing != null) {
            plugin.unregisterSculptBlock(key, existing);
            existing.despawn();
        }
        block.setBlockData(data, false);
        if (changed) editSounds.recordPlace(blockCenter(block), data);
    }

    private void clearUnoccupiedBlocks(World world, int baseX, int baseY, int baseZ,
                                       int[] outputSize, Set<BlockOffset> occupied,
                                       boolean overwriteBlocks,
                                       BlockEditSounds.Batch editSounds) {
        for (int x = 0; x < outputSize[0]; x++) {
            for (int y = 0; y < outputSize[1]; y++) {
                for (int z = 0; z < outputSize[2]; z++) {
                    BlockOffset offset = new BlockOffset(x, y, z);
                    if (occupied.contains(offset)) continue;
                    Block block = world.getBlockAt(baseX + x, baseY + y, baseZ + z);
                    BlockPosKey key = BlockPosKey.of(block.getLocation());
                    SculptBlock existing = plugin.getActiveBlock(key);
                    if (existing != null) {
                        BlockData removedData = firstNonRemovedBlockData(existing.root);
                        if (removedData == null) removedData = existing.originalBlockData;
                        plugin.unregisterSculptBlock(key);
                        existing.despawn();
                        block.setType(Material.AIR);
                        editSounds.recordBreak(blockCenter(block), removedData);
                    } else if (overwriteBlocks && !block.getType().isAir()) {
                        final BlockData removedData = block.getBlockData().clone();
                        block.setType(Material.AIR);
                        editSounds.recordBreak(blockCenter(block), removedData);
                    }
                }
            }
        }
    }

    private static Location blockCenter(final Block block) {
        return new Location(
            block.getWorld(),
            block.getX() + 0.5,
            block.getY() + 0.5,
            block.getZ() + 0.5);
    }

    // ====================== 輔助方法 ======================

    /**
     * 套用旋轉與鏡像映射。
     * 根據 §5.6.4 的座標映射表實現。
     */
    private int[] applyRotation(int gx, int gy, int gz, int gridN, PasteSettings settings) {
        int rx = gx, ry = gy, rz = gz;

        int ryDeg = normalizeDegrees(settings.ry());
        if (ryDeg == 90) {
            int tmp = rx;
            rx = gridN - 1 - rz;
            rz = tmp;
        } else if (ryDeg == 180) {
            rx = gridN - 1 - rx;
            rz = gridN - 1 - rz;
        } else if (ryDeg == 270) {
            int tmp = rx;
            rx = rz;
            rz = gridN - 1 - tmp;
        }

        // 鏡像翻轉
        String flip = normalizedFlip(settings.flipAxis());
        if ("x".equals(flip)) {
            rx = gridN - 1 - rx;
        } else if ("z".equals(flip)) {
            rz = gridN - 1 - rz;
        } else if ("y".equals(flip)) {
            ry = gridN - 1 - ry;
        }

        return new int[]{rx, ry, rz};
    }

    static BlockOffset transformBlockPosition(
            int x, int y, int z, int sizeX, int sizeY, int sizeZ,
            int rotationDegrees, @Nullable String flipAxis) {
        int rotation = normalizeDegrees(rotationDegrees);
        int rx = x;
        int rz = z;
        int outputX = sizeX;
        int outputZ = sizeZ;
        if (rotation == 90) {
            rx = sizeZ - 1 - z;
            rz = x;
            outputX = sizeZ;
            outputZ = sizeX;
        } else if (rotation == 180) {
            rx = sizeX - 1 - x;
            rz = sizeZ - 1 - z;
        } else if (rotation == 270) {
            rx = z;
            rz = sizeX - 1 - x;
            outputX = sizeZ;
            outputZ = sizeX;
        }

        int ry = y;
        String flip = normalizedFlip(flipAxis);
        if ("x".equals(flip)) rx = outputX - 1 - rx;
        if ("y".equals(flip)) ry = sizeY - 1 - ry;
        if ("z".equals(flip)) rz = outputZ - 1 - rz;
        return new BlockOffset(rx, ry, rz);
    }

    static int[] transformedDimensions(int sizeX, int sizeY, int sizeZ, int rotationDegrees) {
        int rotation = normalizeDegrees(rotationDegrees);
        return rotation == 90 || rotation == 270
            ? new int[]{sizeZ, sizeY, sizeX}
            : new int[]{sizeX, sizeY, sizeZ};
    }

    private boolean validSettings(PasteSettings settings) {
        if (settings.rotateMode() == null) return false;
        if (normalizeDegrees(settings.ry()) % 90 != 0) return false;
        String flip = normalizedFlip(settings.flipAxis());
        return settings.flipAxis() == null || flip != null;
    }

    private int resolveRotationDegrees(PasteSettings settings, @Nullable Player player,
                                        Location targetLoc,
                                        @Nullable String referenceFacing,
                                        @Nullable BlockFace clickedFace,
                                        @Nullable SculptBlock existing) {
        BlockFace playerFacing = facingFromPlayerPosition(player, targetLoc);
        BlockFace sourceFacing = parseHorizontalFacing(referenceFacing);
        int playerRelative = sourceFacing != null
            ? relativeDegrees(sourceFacing, playerFacing)
            : degreesForFacing(playerFacing);
        int base = switch (settings.rotateMode()) {
            case NONE -> 0;
            case PLAYER -> playerRelative;
            case FACE -> degreesForFacing(isHorizontal(clickedFace) ? clickedFace : playerFacing);
            case AUTO -> settings.adhesive() && existing != null
                ? degreesForRotation(existing.blockRotation)
                : relativeDegrees(sourceFacing, playerFacing);
        };
        return normalizeDegrees(base + settings.ry());
    }

    /**
     * Resolve the horizontal direction from the player's position toward the
     * target. This is more stable than yaw alone when the player clicks from
     * the edge of a block; yaw remains the fallback when both positions align.
     */
    static BlockFace facingFromPlayerPosition(@Nullable Player player, Location targetLoc) {
        if (player == null) return BlockFace.SOUTH;
        Location eye = player.getEyeLocation();
        return facingToward(
            eye.getX(), eye.getZ(), targetLoc.getX(), targetLoc.getZ(), player.getFacing());
    }

    static BlockFace facingToward(double fromX, double fromZ, double toX, double toZ,
                                  @Nullable BlockFace fallback) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        if (Math.max(Math.abs(dx), Math.abs(dz)) < 0.125) {
            return isHorizontal(fallback) ? fallback : BlockFace.SOUTH;
        }
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx >= 0 ? BlockFace.EAST : BlockFace.WEST;
        }
        return dz >= 0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    static int relativeDegrees(@Nullable BlockFace sourceFacing, BlockFace targetFacing) {
        if (sourceFacing == null) return 0;
        return normalizeDegrees(degreesForFacing(targetFacing) - degreesForFacing(sourceFacing));
    }

    @Nullable
    private static BlockFace parseHorizontalFacing(@Nullable String facing) {
        if (facing == null || facing.isBlank()) return null;
        try {
            BlockFace parsed = BlockFace.valueOf(facing.toUpperCase(java.util.Locale.ROOT));
            return isHorizontal(parsed) ? parsed : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static int degreesForFacing(@Nullable BlockFace facing) {
        if (facing == null) return 0;
        return switch (facing) {
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0;
        };
    }

    private static int degreesForRotation(Quaternionf rotation) {
        Vector3f front = new Vector3f(0, 0, 1);
        new Quaternionf(rotation).transform(front);
        if (Math.abs(front.x) > Math.abs(front.z)) {
            return front.x >= 0 ? degreesForFacing(BlockFace.EAST)
                : degreesForFacing(BlockFace.WEST);
        }
        return front.z >= 0 ? degreesForFacing(BlockFace.SOUTH)
            : degreesForFacing(BlockFace.NORTH);
    }

    private static boolean isHorizontal(@Nullable BlockFace face) {
        return face == BlockFace.NORTH || face == BlockFace.SOUTH
            || face == BlockFace.EAST || face == BlockFace.WEST;
    }

    private static int normalizeDegrees(int degrees) {
        return Math.floorMod(degrees, 360);
    }

    @Nullable
    private static String normalizedFlip(@Nullable String flipAxis) {
        if (flipAxis == null || flipAxis.isBlank()) return null;
        String normalized = flipAxis.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("x") || normalized.equals("y") || normalized.equals("z")
            ? normalized : null;
    }

    record BlockOffset(int x, int y, int z) {}

    private record PlacedBlock(
        BlueprintBlockData source,
        BlockOffset offset,
        @Nullable BlockData regularBlockData
    ) {}

    @Nullable
    private BlockData parseBlockData(@Nullable String blockKey) {
        if (blockKey == null) return null;
        try {
            return org.bukkit.Bukkit.createBlockData(blockKey);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static void transformRegularBlockData(
            BlockData data, int rotationDegrees, @Nullable String flipAxis) {
        data.rotate(switch (normalizeDegrees(rotationDegrees)) {
            case 90 -> StructureRotation.CLOCKWISE_90;
            case 180 -> StructureRotation.CLOCKWISE_180;
            case 270 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        });

        String flip = normalizedFlip(flipAxis);
        if ("x".equals(flip)) data.mirror(Mirror.FRONT_BACK);
        if ("z".equals(flip)) data.mirror(Mirror.LEFT_RIGHT);
        if (!"y".equals(flip)) return;

        if (data instanceof Directional directional) {
            if (directional.getFacing() == BlockFace.UP) {
                directional.setFacing(BlockFace.DOWN);
            } else if (directional.getFacing() == BlockFace.DOWN) {
                directional.setFacing(BlockFace.UP);
            }
        }
        if (data instanceof Bisected bisected) {
            bisected.setHalf(bisected.getHalf() == Bisected.Half.TOP
                ? Bisected.Half.BOTTOM : Bisected.Half.TOP);
        }
        if (data instanceof Slab slab && slab.getType() != Slab.Type.DOUBLE) {
            slab.setType(slab.getType() == Slab.Type.TOP
                ? Slab.Type.BOTTOM : Slab.Type.TOP);
        }
        if (data instanceof FaceAttachable attachable) {
            if (attachable.getAttachedFace() == FaceAttachable.AttachedFace.FLOOR) {
                attachable.setAttachedFace(FaceAttachable.AttachedFace.CEILING);
            } else if (attachable.getAttachedFace() == FaceAttachable.AttachedFace.CEILING) {
                attachable.setAttachedFace(FaceAttachable.AttachedFace.FLOOR);
            }
        }
    }

    /** 建立 SculptBlock 的記憶體狀態；呼叫端確認後才生成世界實體。 */
    private SculptBlock prepareSculptBlock(World world, BlockPosKey posKey,
                                            OctreeNode srcTree,
                                            BlockData originalBlockData,
                                            @Nullable String matchedVariant,
                                            Quaternionf blockRotation,
                                            @Nullable Map<String, int[]> storedCoords,
                                            HeadResolver resolver) {
        Location loc = new Location(world, posKey.x(), posKey.y(), posKey.z());
        SculptBlock sb = new SculptBlock(world, loc, originalBlockData,
            matchedVariant, blockRotation,
            new BukkitTransportSession(world), resolver);

        rebuildTree(sb.root, srcTree);
        sb.rebuildCollisionTopology();
        sb.storedCoords = storedCoords;
        sb.setOnCleared(() -> plugin.unregisterSculptBlock(posKey, sb));
        return sb;
    }

    /**
     * 遞迴複製 src 的八元樹結構到 dest。
     * <p>
     * 複製內容包含：子節點拓樸、blockData、removed 狀態。
     * 使用 OctreeNode 純資料操作（不產生實體）。
     */
    private static void rebuildTree(OctreeNode dest, OctreeNode src) {
        if (src.blockData() != null) {
            dest.setBlockData(src.blockData());
        }
        if (src.textureCoord() != null) {
            dest.setTextureCoord(src.textureCoord());
        }
        dest.setPlayerHeadTexture(src.playerHeadTexture());
        if (src.isRemoved()) {
            dest.remove();
        }
        if (src.isBranch()) {
            dest.subdivide();
            for (int i = 0; i < 8; i++) {
                rebuildTree(dest.children()[i], src.children()[i]);
            }
        }
    }

    static OctreeNode copyTree(OctreeNode source) {
        OctreeNode copy = new OctreeNode();
        rebuildTree(copy, source);
        return copy;
    }

    @Nullable
    private static Map<String, int[]> copyCoordinates(@Nullable Map<String, int[]> source) {
        if (source == null) return null;
        final Map<String, int[]> copy = new HashMap<>();
        source.forEach((path, coords) -> copy.put(path,
            coords == null ? null : coords.clone()));
        return copy;
    }

    /**
     * 將來源樹套用到基底樹，保留兩者的混合深度拓樸。
     * 此方法只操作純資料，世界中的方塊與實體不會在規劃階段被修改。
     */
    static OctreeNode mergeTrees(OctreeNode base, OctreeNode source,
                                  boolean pasteAir, boolean overwriteCells) {
        OctreeNode result = copyTree(base);
        overlay(result, source, pasteAir, overwriteCells);
        return result;
    }

    private static void overlay(OctreeNode dest, OctreeNode src,
                                boolean pasteAir, boolean overwriteCells) {
        if (src.isBranch()) {
            if (dest.isLeaf()) {
                // An occupied held-head cell is atomic during adhesive merges.
                // Explicit overwrite may replace it with the source subtree.
                if (dest.playerHeadTexture() != null && !dest.isRemoved()
                        && !overwriteCells) return;
                dest.setPlayerHeadTexture(null);
                subdividePreservingState(dest);
            }
            for (int i = 0; i < 8; i++) {
                overlay(dest.children()[i], src.children()[i], pasteAir, overwriteCells);
            }
            return;
        }

        if (src.isRemoved() && !pasteAir) return;

        if (dest.isBranch()) {
            // A held player-head texture represents this whole source leaf.
            // Adhesive paste may place it only when the complete destination
            // region is empty; applying it to each descendant would split one
            // head into several smaller, duplicated textures.
            if (src.playerHeadTexture() != null) {
                if (!overwriteCells && !allLeavesRemoved(dest)) return;
                dest.coarsen();
                applyLeaf(dest, src, true);
                return;
            }
            if (overwriteCells) {
                dest.coarsen();
                applyLeaf(dest, src, true);
            } else {
                for (OctreeNode child : dest.children()) {
                    overlay(child, src, pasteAir, false);
                }
            }
            return;
        }

        applyLeaf(dest, src, overwriteCells);
    }

    private static void applyLeaf(OctreeNode dest, OctreeNode src,
                                  boolean overwriteCells) {
        if (!overwriteCells && !dest.isRemoved()) return;

        if (src.isRemoved()) {
            dest.remove();
            return;
        }

        if (dest.isRemoved()) dest.restore();
        if (src.blockData() != null) dest.setBlockData(src.blockData());
        dest.setTextureCoord(src.textureCoord());
        dest.setPlayerHeadTexture(src.playerHeadTexture());
    }

    private static void subdividePreservingState(OctreeNode node) {
        boolean removed = node.isRemoved();
        BlockData data = node.blockData();
        ChunkCoord textureCoord = node.textureCoord();
        node.setPlayerHeadTexture(null);
        node.subdivide();
        for (OctreeNode child : node.children()) {
            if (data != null) child.setBlockData(data);
            if (textureCoord != null) child.setTextureCoord(textureCoord);
            if (removed) child.remove();
        }
    }

    private static boolean allLeavesRemoved(OctreeNode root) {
        List<OctreeNode> leaves = new ArrayList<>();
        root.collectAllLeaves(leaves);
        return !leaves.isEmpty() && leaves.stream().allMatch(OctreeNode::isRemoved);
    }

    @Nullable
    private static BlockData firstNonRemovedBlockData(OctreeNode root) {
        for (OctreeNode leaf : root.collectLeaves()) {
            if (!leaf.isRemoved() && leaf.blockData() != null) return leaf.blockData();
        }
        return null;
    }

    @Nullable
    private static Map<String, int[]> buildResultCoordinates(
            OctreeNode result, OctreeNode source, @Nullable Map<String, int[]> sourceCoords,
            @Nullable OctreeNode existing, @Nullable Map<String, int[]> existingCoords) {
        if ((sourceCoords == null || sourceCoords.isEmpty())
                && (existingCoords == null || existingCoords.isEmpty())) {
            return null;
        }

        Map<String, int[]> resultCoords = new HashMap<>();
        List<OctreeNode> leaves = new ArrayList<>();
        result.collectAllLeaves(leaves);
        for (OctreeNode leaf : leaves) {
            String path = leaf.pathAsString();
            OctreeNode sourceLeaf = exactLeafAtPath(source, path);
            if (sourceLeaf != null && sameLeafContent(leaf, sourceLeaf)
                    && sourceCoords != null && sourceCoords.containsKey(path)) {
                resultCoords.put(path, sourceCoords.get(path).clone());
                continue;
            }
            OctreeNode existingLeaf = exactLeafAtPath(existing, path);
            if (existingLeaf != null && sameLeafContent(leaf, existingLeaf)
                    && existingCoords != null && existingCoords.containsKey(path)) {
                resultCoords.put(path, existingCoords.get(path).clone());
            }
        }
        return resultCoords.isEmpty() ? null : resultCoords;
    }

    @Nullable
    private static OctreeNode exactLeafAtPath(@Nullable OctreeNode root, String path) {
        if (root == null) return null;
        try {
            OctreeNode node = OctreeNode.fromPath(root, path);
            return node != null && node.isLeaf() ? node : null;
        } catch (IllegalArgumentException | ArrayIndexOutOfBoundsException e) {
            return null;
        }
    }

    private static boolean sameLeafContent(OctreeNode left, OctreeNode right) {
        if (left.isRemoved() != right.isRemoved()) return false;
        if (!java.util.Objects.equals(
                left.playerHeadTexture(), right.playerHeadTexture())) return false;
        if (left.blockData() == null || right.blockData() == null) {
            return left.blockData() == right.blockData();
        }
        return left.blockData().equals(right.blockData());
    }

    /**
     * 從原始藍圖八元樹建立旋轉後的複本。
     * <p>
     * 走訪原始樹的所有葉子，套用座標旋轉映射後在目標樹對應位置建立葉子。
     * 同時建立旋轉後路徑 → 原始規範化座標的映射（storedCoords）。
     *
     * @param src              原始藍圖八元樹根節點
     * @param gridN            grid 大小
     * @param settings         貼上設定（含旋轉參數）
     * @param rotatedCoordsOut 輸出：旋轉後路徑 → 原始規範化座標的映射
     * @param srcLeafCoords    原始藍圖的 leafCoordinates（查詢用）
     * @return 旋轉後的八元樹根節點
     */
    private OctreeNode buildRotatedOctree(OctreeNode src, int gridN, PasteSettings settings,
                                           @Nullable Map<String, int[]> rotatedCoordsOut,
                                           @Nullable Map<String, int[]> srcLeafCoords) {
        OctreeNode rotated = new OctreeNode();

        List<OctreeNode> leaves = new ArrayList<>();
        src.collectAllLeaves(leaves);

        for (OctreeNode leaf : leaves) {
            int cellGrid = gridN / leaf.side();
            int gx = leaf.minX() / leaf.side();
            int gy = leaf.minY() / leaf.side();
            int gz = leaf.minZ() / leaf.side();

            int[] rot = applyRotation(gx, gy, gz, cellGrid, settings);
            int rx = rot[0] * leaf.side();
            int ry = rot[1] * leaf.side();
            int rz = rot[2] * leaf.side();

            OctreeNode placed = placeLeaf(rotated, leaf, rx, ry, rz, leaf.depth());

            // 記錄旋轉後路徑 → 原始規範化座標的映射
            if (rotatedCoordsOut != null && srcLeafCoords != null) {
                int[] canonicalCoord = srcLeafCoords.get(leaf.pathAsString());
                if (canonicalCoord != null) {
                    rotatedCoordsOut.put(placed.pathAsString(), canonicalCoord);
                }
            }
        }

        return rotated;
    }

    /** 在旋轉後樹的指定座標放置葉子。沿路細分到正確深度。回傳放置後的葉子節點。 */
    private OctreeNode placeLeaf(OctreeNode root, OctreeNode srcLeaf,
                                  int gx, int gy, int gz, int targetDepth) {
        OctreeNode node = root;
        int depth = 0;
        while (depth < targetDepth) {
            if (node.isLeaf()) {
                node.subdivide();
            }
            int childSide = 16 >> (depth + 1);
            int ox = (gx / childSide) & 1;
            int oy = (gy / childSide) & 1;
            int oz = (gz / childSide) & 1;
            int idx = (ox << 2) | (oy << 1) | oz;
            node = node.children()[idx];
            depth++;
        }
        if (srcLeaf.blockData() != null) {
            node.setBlockData(srcLeaf.blockData());
        }
        if (srcLeaf.textureCoord() != null) {
            node.setTextureCoord(srcLeaf.textureCoord());
        }
        node.setPlayerHeadTexture(srcLeaf.playerHeadTexture());
        if (srcLeaf.isRemoved()) {
            node.remove();
        }
        return node;
    }

}
