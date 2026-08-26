package dev.twme.sculpt.render.text;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.kyori.adventure.text.Component;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.core.AutoDisplayMaterialStatus;
import dev.twme.sculpt.core.BakeKey;
import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.ChunkCoord;
import dev.twme.sculpt.core.ChunkSpec;
import dev.twme.sculpt.core.FaceDir;
import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.OctreeNode;
import dev.twme.sculpt.core.PlayerHeadTexture;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.plugin.BlockPosKey;
import dev.twme.sculpt.render.TextBlockRenderHandle;
import dev.twme.sculpt.render.TextBlockRenderer;
import dev.twme.sculpt.render.TextLightingRefreshResult;
import dev.twme.sculpt.transport.bukkit.BukkitDisplayHandle;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.textdisplayshape.util.TRSResult;
import dev.twme.textdisplayshape.util.TextDisplayUtil;

/**
 * Renders the exposed pixels of one SculptBlock with one-sided TextDisplay
 * parallelograms. Texture loading and raster planning stay off the region
 * thread; only entity creation runs on the owning region.
 */
public final class TextDisplayBlockRenderer implements TextBlockRenderer {

    public static final String TEXT_PIXEL_TYPE = "text_pixel";
    private static final NamespacedKey TYPE_KEY =
        new NamespacedKey("sculpt", "type");
    private static final float SURFACE_EPSILON = 0.0002f;
    /**
     * In-plane overlap at each independently transformed cell-face envelope.
     * One power-of-two fraction keeps the value exactly representable as a
     * float; it is just larger than the outward surface offset and less than
     * 0.4% of one vanilla texture pixel (1/16 block).
     */
    static final float SEAM_OVERLAP = 1f / 4096f;

    private final Sculpt plugin;
    private final TextDisplayTextureCache textures;
    private final Executor executor;
    private final int maxEntitiesPerBlock;
    private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();
    private final Set<AutoRefreshRequest> pendingAutoRefreshes =
        ConcurrentHashMap.newKeySet();

    public TextDisplayBlockRenderer(
            final Sculpt plugin,
            final TextDisplayTextureCache textures,
            final Executor executor,
            final int maxEntitiesPerBlock) {
        this.plugin = plugin;
        this.textures = textures;
        this.executor = executor;
        this.maxEntitiesPerBlock = Math.max(1, maxEntitiesPerBlock);
    }

    @Override
    public TextBlockRenderHandle render(final SculptBlock block) {
        final TextBlockRenderHandle current = block.textRenderHandle();
        final ActiveHandle handle;
        if (current instanceof ActiveHandle active
                && active.owner == this && !active.isCancelled()) {
            handle = active;
        } else {
            handle = new ActiveHandle(this);
        }
        final long revision = handle.beginUpdate();
        // Octree edits commonly perform several subdivisions/removals in one
        // tick. Deferring the snapshot coalesces those calls naturally: every
        // superseded revision is ignored before its scheduled task runs, so
        // only the final tree state reaches texture loading and pixel planning.
        FoliaScheduler.runRegionTaskLater(plugin, block.pos,
            () -> beginRender(block, handle, revision), 1L);
        return handle;
    }

    @Override
    public AutoDisplayMaterialStatus autoMaterialStatus(
            final SculptBlock block,
            final Material material) {
        final TextDisplayMaterialSupport.ModelStatus status =
            textures.modelStatus(material, true);
        if (status == TextDisplayMaterialSupport.ModelStatus.LOADING) {
            scheduleAutoRefresh(block, material);
        }
        return switch (status) {
            case UNKNOWN -> AutoDisplayMaterialStatus.UNKNOWN;
            case LOADING -> AutoDisplayMaterialStatus.LOADING;
            case OPAQUE -> AutoDisplayMaterialStatus.OPAQUE;
            case TRANSPARENT -> AutoDisplayMaterialStatus.TRANSPARENT;
            case UNSUPPORTED -> AutoDisplayMaterialStatus.UNSUPPORTED;
        };
    }

    @Override
    public TextLightingRefreshResult refreshLighting(final SculptBlock block) {
        final TextBlockRenderHandle current = block.textRenderHandle();
        if (!(current instanceof ActiveHandle handle)
                || handle.owner != this
                || handle.isCancelled()
                || !block.canRefreshDisplays()
                || !block.displayMode().usesTextRenderer()) {
            return TextLightingRefreshResult.EMPTY;
        }
        synchronized (handle) {
            if (block.textRenderHandle() != handle || handle.isCancelled()) {
                return TextLightingRefreshResult.EMPTY;
            }
            handle.discardInvalidGroups();
            return clearBrightnessOverrides(handle.groups.values());
        }
    }

    private void scheduleAutoRefresh(
            final SculptBlock block,
            final Material material) {
        final AutoRefreshRequest request = new AutoRefreshRequest(block, material);
        if (!pendingAutoRefreshes.add(request)) return;
        try {
            textures.resolveStatus(material).whenComplete((status, failure) -> {
                pendingAutoRefreshes.remove(request);
                if (plugin.isDisabling()) return;
                FoliaScheduler.runRegionTask(plugin, block.pos, () -> {
                    if (plugin.getActiveBlock(BlockPosKey.of(block.pos)) == block
                            && !block.despawned
                            && block.state == SculptBlock.State.SCULPTED
                            && block.displayMode() == SculptDisplayMode.AUTO) {
                        block.refreshAutoDisplay(material);
                    }
                });
            });
        } catch (final RuntimeException failure) {
            pendingAutoRefreshes.remove(request);
            throw failure;
        }
    }

    private void beginRender(
            final SculptBlock block,
            final ActiveHandle handle,
            final long revision) {
        if (!handle.isCurrent(revision)) return;
        if (plugin.isDisabling()
                || plugin.getActiveBlock(BlockPosKey.of(block.pos)) != block
                || block.despawned
                || block.state != SculptBlock.State.SCULPTED
                || !block.displayMode().usesTextRenderer()
                || block.textRenderHandle() != handle) {
            handle.despawn();
            return;
        }

        final Snapshot snapshot;
        try {
            snapshot = Snapshot.capture(block);
        } catch (final RuntimeException failure) {
            reportOnce(block, "octree snapshot failed", failure);
            return;
        }
        final List<CompletableFuture<ResolvedLeaf>> requests = new ArrayList<>();
        for (final LeafSnapshot leaf : snapshot.leaves) {
            final CompletableFuture<ResolvedLeaf> request = textures.get(
                leaf.bakeKey, leaf.gridN, leaf.textureCoord)
                .thenApply(tile -> new ResolvedLeaf(leaf, tile));
            requests.add(request);
        }

        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new))
            .thenApplyAsync(ignored -> !handle.isCurrent(revision)
                ? null : buildPlan(snapshot, requests), executor)
            .whenComplete((plan, failure) -> {
                if (!handle.isCurrent(revision) || plugin.isDisabling()) return;
                FoliaScheduler.runRegionTask(plugin, block.pos,
                    () -> complete(block, handle, revision, plan, failure));
            });
    }

    public void clearCache() {
        textures.clear();
    }

    private RenderPlan buildPlan(
            final Snapshot snapshot,
        final List<CompletableFuture<ResolvedLeaf>> requests) {
        final List<PixelQuad> quads = new ArrayList<>();
        final Set<String> unsupported = new LinkedHashSet<>();
        for (final CompletableFuture<ResolvedLeaf> request : requests) {
            final ResolvedLeaf resolved = request.join();
            if (resolved.tile.isEmpty()) {
                unsupported.add(resolved.leaf.bakeKey.block().toString());
                continue;
            }
            appendLeafQuads(snapshot, resolved.leaf, resolved.tile.get(), quads);
            if (quads.size() > maxEntitiesPerBlock) {
                return new RenderPlan(List.of(), List.copyOf(unsupported), true);
            }
        }
        return new RenderPlan(List.copyOf(quads), List.copyOf(unsupported), false);
    }

    private void complete(
            final SculptBlock block,
            final ActiveHandle handle,
            final long revision,
            final RenderPlan plan,
            final Throwable failure) {
        if (!handle.isCurrent(revision)) return;
        if (plugin.isDisabling()
                || plugin.getActiveBlock(BlockPosKey.of(block.pos)) != block
                || block.despawned
                || block.state != SculptBlock.State.SCULPTED
                || !block.displayMode().usesTextRenderer()
                || block.textRenderHandle() != handle) {
            handle.despawn();
            return;
        }
        if (failure != null) {
            reportOnce(block, "texture load failed", failure);
            return;
        }
        if (plan.tooComplex) {
            reportOnce(block, "requires more than " + maxEntitiesPerBlock
                + " TextDisplay entities; rendering was skipped", null);
            return;
        }
        if (!plan.unsupported.isEmpty()) {
            reportOnce(block, "unsupported full-cube texture(s): "
                + String.join(", ", plan.unsupported), null);
        }

        final ItemDisplay root = rootEntity(block);
        if (root == null) return;
        applyPlan(block, root, handle, revision, plan.quads);
    }

    /**
     * Apply only the changed pixel planes. Unchanged TextDisplays remain the
     * same entities, so an edit does not resend the whole SculptBlock to every
     * viewer. New planes are prepared first; if spawning fails, the previous
     * complete render remains visible.
     */
    private void applyPlan(
            final SculptBlock block,
            final ItemDisplay root,
            final ActiveHandle handle,
            final long revision,
            final List<PixelQuad> desiredQuads) {
        synchronized (handle) {
            if (!handle.isCurrent(revision)) return;
            handle.discardInvalidGroups();
            final RenderDelta<PixelQuad> delta = computeDelta(
                handle.groups.keySet(), desiredQuads);
            if (delta.additions.isEmpty() && delta.removals.isEmpty()) {
                clearBrightnessOverrides(handle.groups.values());
                return;
            }

            try {
                applyDeltaAdditionsFirst(
                    delta,
                    handle.groups,
                    quad -> spawnQuad(block, root, quad),
                    TextDisplayBlockRenderer::removeGroup,
                    () -> handle.isCurrent(revision));
                clearBrightnessOverrides(handle.groups.values());
            } catch (final RuntimeException updateFailure) {
                reportOnce(block, "pixel entity update failed", updateFailure);
            }
        }
    }

    private List<TextDisplay> spawnQuad(
            final SculptBlock block,
            final ItemDisplay root,
            final PixelQuad quad) {
        final List<TextDisplay> spawned = new ArrayList<>();
        try {
            final Transformation transformation = quadTransformation(quad);
            final TextDisplay spawnedDisplay = block.world.spawn(
                block.centerLoc(), TextDisplay.class, entity -> {
                    entity.text(Component.text(" "));
                    entity.setBackgroundColor(Color.fromARGB(quad.argb));
                    entity.setTransformation(transformation);
                    entity.setSeeThrough(false);
                    entity.setViewRange(1.0f);
                });
            spawned.add(spawnedDisplay);
            for (final TextDisplay display : spawned) {
                // Pixels are a cache of the octree + vanilla texture, not
                // authoritative world data. Rebuild them after a chunk reload
                // instead of bloating entity-region files.
                display.setPersistent(false);
                display.getPersistentDataContainer().set(
                    TYPE_KEY, PersistentDataType.STRING, TEXT_PIXEL_TYPE);
                if (!root.addPassenger(display)) {
                    throw new IllegalStateException(
                        "failed to attach TextDisplay pixel to Sculpt root");
                }
            }
            return List.copyOf(spawned);
        } catch (final RuntimeException spawnFailure) {
            removeGroup(spawned);
            throw spawnFailure;
        }
    }

    /**
     * Remove legacy brightness overrides without recreating pixel entities.
     * A null override makes the client continuously use the TextDisplay's
     * actual entity position in its normal environmental-lighting path.
     */
    static TextLightingRefreshResult clearBrightnessOverrides(
            final Collection<? extends Collection<TextDisplay>> groups) {
        int checked = 0;
        int updated = 0;
        for (final Collection<TextDisplay> group : groups) {
            for (final TextDisplay display : group) {
                if (display == null || !display.isValid()) continue;
                checked++;
                if (display.getBrightness() == null) continue;
                display.setBrightness(null);
                updated++;
            }
        }
        return new TextLightingRefreshResult(checked, updated);
    }

    /**
     * Compute the shape entirely in the root entity's local coordinate frame.
     * TextDisplayShapes accepts float points; adding a large world coordinate
     * before subtracting the entity origin would discard grid=16 fractions at
     * sufficiently distant locations. Keeping every input near [-0.5, 0.5]
     * preserves the same precision everywhere in the world.
     */
    static Transformation quadTransformation(final PixelQuad quad) {
        final TRSResult trs = TextDisplayUtil.computeParallelogramTRS(
            rootLocalPoint(quad.p1),
            rootLocalPoint(quad.p2),
            rootLocalPoint(quad.p3));
        return new Transformation(
            trs.translation(), trs.leftRotation(), trs.scale(), trs.rightRotation());
    }

    static Vector3f rootLocalPoint(final Vector3f blockLocal) {
        return new Vector3f(blockLocal).sub(0.5f, 0.5f, 0.5f);
    }

    static <T> RenderDelta<T> computeDelta(
            final Collection<T> current,
            final Collection<T> desired) {
        final LinkedHashSet<T> currentSet = new LinkedHashSet<>(current);
        final LinkedHashSet<T> desiredSet = new LinkedHashSet<>(desired);
        final List<T> additions = desiredSet.stream()
            .filter(value -> !currentSet.contains(value))
            .toList();
        final List<T> removals = currentSet.stream()
            .filter(value -> !desiredSet.contains(value))
            .toList();
        return new RenderDelta<>(additions, removals);
    }

    /**
     * Stage every addition before removing anything from the active render.
     * A failed or superseded update rolls back only its staged additions and
     * leaves the previous complete render untouched.
     */
    static <K, V> boolean applyDeltaAdditionsFirst(
            final RenderDelta<K> delta,
            final Map<K, V> current,
            final Function<K, V> creator,
            final Consumer<V> destroyer,
            final BooleanSupplier stillCurrent) {
        final Map<K, V> staged = new LinkedHashMap<>();
        try {
            for (final K addition : delta.additions) {
                if (!stillCurrent.getAsBoolean()) {
                    staged.values().forEach(destroyer);
                    return false;
                }
                staged.put(addition, creator.apply(addition));
            }
        } catch (final RuntimeException failure) {
            staged.values().forEach(destroyer);
            throw failure;
        }
        if (!stillCurrent.getAsBoolean()) {
            staged.values().forEach(destroyer);
            return false;
        }
        current.putAll(staged);
        for (final K removal : delta.removals) {
            destroyer.accept(current.remove(removal));
        }
        return true;
    }

    private static void removeGroups(
            final Collection<List<TextDisplay>> groups) {
        for (final List<TextDisplay> group : groups) removeGroup(group);
    }

    private static void removeGroup(final List<TextDisplay> group) {
        if (group == null) return;
        for (final TextDisplay display : group) {
            if (display != null && display.isValid()) display.remove();
        }
    }

    private void reportOnce(
            final SculptBlock block,
            final String message,
            final Throwable failure) {
        final String location = block.world.getName() + ","
            + block.pos.getBlockX() + "," + block.pos.getBlockY() + ","
            + block.pos.getBlockZ();
        if (!reportedFailures.add(location + ":" + message)) return;
        final String full = "[textdisplay] " + location + " - " + message;
        if (failure == null) {
            plugin.getLogger().warning(full);
        } else {
            plugin.getLogger().log(Level.WARNING, full, failure);
        }
    }

    private static ItemDisplay rootEntity(final SculptBlock block) {
        if (block.rootEntity instanceof BukkitDisplayHandle handle
                && handle.entity().isValid()) {
            return handle.entity();
        }
        return null;
    }

    private static void appendLeafQuads(
            final Snapshot snapshot,
            final LeafSnapshot leaf,
            final ChunkSpec tile,
            final List<PixelQuad> output) {
        for (final FaceDir canonicalFace : FaceDir.values()) {
            final BufferedImage image = tile.tile(canonicalFace);
            final FaceDir worldFace = rotateFace(canonicalFace, snapshot.rotation);
            final int[][] colors = new int[image.getHeight()][image.getWidth()];
            for (int py = 0; py < image.getHeight(); py++) {
                for (int px = 0; px < image.getWidth(); px++) {
                    final int argb = image.getRGB(px, py);
                    if ((argb >>> 24) == 0) continue;
                    if (!isExposed(snapshot, leaf, canonicalFace,
                            worldFace, px, py, image.getWidth(), image.getHeight())) {
                        continue;
                    }
                    colors[py][px] = shade(argb, worldFace.shade());
                }
            }
            appendMergedRectangles(
                leaf, canonicalFace, snapshot.rotation, colors, output);
        }
    }

    static boolean isExposed(
            final Snapshot snapshot,
            final LeafSnapshot leaf,
            final FaceDir canonicalFace,
            final FaceDir worldFace,
            final int px,
            final int py,
            final int width,
            final int height) {
        Vector3f surface = facePoint(leaf, canonicalFace,
            (px + 0.5f) / width, (py + 0.5f) / height);
        surface.add(-canonicalFace.dx * 0.5f / 16f,
            -canonicalFace.dy * 0.5f / 16f,
            -canonicalFace.dz * 0.5f / 16f);
        rotateAroundLeafCenter(surface, leaf, snapshot.rotation);
        final int x = clampVoxel((int) Math.floor(surface.x * 16f));
        final int y = clampVoxel((int) Math.floor(surface.y * 16f));
        final int z = clampVoxel((int) Math.floor(surface.z * 16f));
        final int nx = x + worldFace.dx;
        final int ny = y + worldFace.dy;
        final int nz = z + worldFace.dz;
        if (nx < 0 || nx >= 16 || ny < 0 || ny >= 16 || nz < 0 || nz >= 16) {
            return true;
        }
        // Occlude only an identical visual material. Different materials need
        // their shared plane so translucent or mixed-material cells retain
        // the boundary visible from inside the SculptBlock.
        final SurfaceMaterial neighbor = snapshot.materials[index(nx, ny, nz)];
        return neighbor == null || !leaf.material.equals(neighbor);
    }

    private static void appendMergedRectangles(
            final LeafSnapshot leaf,
            final FaceDir face,
            final Quaternionf rotation,
            final int[][] colors,
            final List<PixelQuad> output) {
        final int height = colors.length;
        final int width = height == 0 ? 0 : colors[0].length;
        final boolean[][] used = new boolean[height][width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int color = colors[y][x];
                if (color == 0 || used[y][x]) continue;
                int endX = x + 1;
                while (endX < width && !used[y][endX]
                        && colors[y][endX] == color) endX++;
                int endY = y + 1;
                rows: while (endY < height) {
                    for (int scanX = x; scanX < endX; scanX++) {
                        if (used[endY][scanX]
                                || colors[endY][scanX] != color) break rows;
                    }
                    endY++;
                }
                for (int markY = y; markY < endY; markY++) {
                    for (int markX = x; markX < endX; markX++) {
                        used[markY][markX] = true;
                    }
                }
                output.add(pixelQuad(leaf, face, rotation,
                    (float) x / width, (float) endX / width,
                    (float) y / height, (float) endY / height, color));
            }
        }
    }

    private static PixelQuad pixelQuad(
            final LeafSnapshot leaf,
            final FaceDir face,
            final Quaternionf rotation,
            final float u0,
            final float u1,
            final float v0,
            final float v1,
            final int argb) {
        Vector3f a = facePoint(leaf, face, u0, v0);
        Vector3f b = facePoint(leaf, face, u1, v0);
        Vector3f c = facePoint(leaf, face, u0, v1);
        rotateAroundLeafCenter(a, leaf, rotation);
        rotateAroundLeafCenter(b, leaf, rotation);
        rotateAroundLeafCenter(c, leaf, rotation);
        // Only miter the cell/face envelope. Internal texture rectangles stay
        // exactly partitioned, which avoids double-blending translucent pixels.
        final QuadPoints expanded = overlapInPlane(
            a, b, c, SEAM_OVERLAP,
            u0 <= 0f, u1 >= 1f, v0 <= 0f, v1 >= 1f);
        a = expanded.p1;
        b = expanded.p2;
        c = expanded.p3;
        final FaceDir worldFace = rotateFace(face, rotation);
        offsetSurface(a, worldFace);
        offsetSurface(b, worldFace);
        offsetSurface(c, worldFace);
        // TextDisplayShapes treats p2-p1 cross p3-p1 as the front normal.
        final Vector3f cross = new Vector3f(b).sub(a)
            .cross(new Vector3f(c).sub(a));
        final float facing = cross.x * worldFace.dx
            + cross.y * worldFace.dy + cross.z * worldFace.dz;
        return facing >= 0f
            ? new PixelQuad(a, b, c, argb)
            : new PixelQuad(a, c, b, argb);
    }

    /** Expand all four edges of a parallelogram without moving its plane. */
    static QuadPoints overlapInPlane(
            final Vector3f p1,
            final Vector3f p2,
            final Vector3f p3,
            final float overlap) {
        return overlapInPlane(
            p1, p2, p3, overlap, true, true, true, true);
    }

    static QuadPoints overlapInPlane(
            final Vector3f p1,
            final Vector3f p2,
            final Vector3f p3,
            final float overlap,
            final boolean minU,
            final boolean maxU,
            final boolean minV,
            final boolean maxV) {
        if (!Float.isFinite(overlap) || overlap < 0f) {
            throw new IllegalArgumentException(
                "overlap must be finite and non-negative");
        }
        final Vector3f u = new Vector3f(p2).sub(p1).normalize(overlap);
        final Vector3f v = new Vector3f(p3).sub(p1).normalize(overlap);
        final Vector3f result1 = new Vector3f(p1);
        final Vector3f result2 = new Vector3f(p2);
        final Vector3f result3 = new Vector3f(p3);
        if (minU) {
            result1.sub(u);
            result3.sub(u);
        }
        if (maxU) result2.add(u);
        if (minV) {
            result1.sub(v);
            result2.sub(v);
        }
        if (maxV) result3.add(v);
        return new QuadPoints(result1, result2, result3);
    }

    /** Local block coordinates in [0,1], before the blockstate rotation. */
    private static Vector3f facePoint(
            final LeafSnapshot leaf,
            final FaceDir face,
            final float u,
            final float v) {
        final float minX = leaf.minX / 16f;
        final float minY = leaf.minY / 16f;
        final float minZ = leaf.minZ / 16f;
        final float maxX = (leaf.minX + leaf.side) / 16f;
        final float maxY = (leaf.minY + leaf.side) / 16f;
        final float maxZ = (leaf.minZ + leaf.side) / 16f;
        return switch (face) {
            case UP -> new Vector3f(lerp(minX, maxX, u), maxY,
                lerp(minZ, maxZ, v));
            case DOWN -> new Vector3f(lerp(minX, maxX, u), minY,
                lerp(minZ, maxZ, v));
            case NORTH -> new Vector3f(lerp(maxX, minX, u),
                lerp(maxY, minY, v), minZ);
            case SOUTH -> new Vector3f(lerp(minX, maxX, u),
                lerp(maxY, minY, v), maxZ);
            case EAST -> new Vector3f(maxX, lerp(maxY, minY, v),
                lerp(maxZ, minZ, u));
            case WEST -> new Vector3f(minX, lerp(maxY, minY, v),
                lerp(minZ, maxZ, u));
        };
    }

    private static void rotateAroundLeafCenter(
            final Vector3f point,
            final LeafSnapshot leaf,
            final Quaternionf rotation) {
        final float cx = (leaf.minX + leaf.side / 2f) / 16f;
        final float cy = (leaf.minY + leaf.side / 2f) / 16f;
        final float cz = (leaf.minZ + leaf.side / 2f) / 16f;
        point.sub(cx, cy, cz);
        new Quaternionf(rotation).transform(point);
        point.add(cx, cy, cz);
    }

    static FaceDir rotateFace(
            final FaceDir face,
            final Quaternionf rotation) {
        final Vector3f normal = new Vector3f(face.dx, face.dy, face.dz);
        new Quaternionf(rotation).transform(normal);
        FaceDir best = face;
        float bestDot = -Float.MAX_VALUE;
        for (final FaceDir candidate : FaceDir.values()) {
            final float dot = normal.x * candidate.dx
                + normal.y * candidate.dy + normal.z * candidate.dz;
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best;
    }

    private static void offsetSurface(final Vector3f point, final FaceDir face) {
        point.add(face.dx * SURFACE_EPSILON,
            face.dy * SURFACE_EPSILON, face.dz * SURFACE_EPSILON);
    }

    static int shade(final int argb, final float shade) {
        final int alpha = argb >>> 24;
        final int red = Math.clamp(Math.round(((argb >>> 16) & 0xFF) * shade), 0, 255);
        final int green = Math.clamp(Math.round(((argb >>> 8) & 0xFF) * shade), 0, 255);
        final int blue = Math.clamp(Math.round((argb & 0xFF) * shade), 0, 255);
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private static float lerp(final float start, final float end, final float t) {
        return start + (end - start) * t;
    }

    private static int clampVoxel(final int value) {
        return Math.clamp(value, 0, 15);
    }

    private static int index(final int x, final int y, final int z) {
        return (y << 8) | (z << 4) | x;
    }

    private static final class ActiveHandle implements TextBlockRenderHandle {
        private final TextDisplayBlockRenderer owner;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicLong revision = new AtomicLong();
        private final Map<PixelQuad, List<TextDisplay>> groups =
            new LinkedHashMap<>();

        private ActiveHandle(final TextDisplayBlockRenderer owner) {
            this.owner = owner;
        }

        private long beginUpdate() {
            return revision.incrementAndGet();
        }

        private boolean isCurrent(final long expectedRevision) {
            return !cancelled.get() && revision.get() == expectedRevision;
        }

        private void discardInvalidGroups() {
            final var iterator = groups.entrySet().iterator();
            while (iterator.hasNext()) {
                final List<TextDisplay> displays = iterator.next().getValue();
                if (displays.isEmpty()
                        || displays.stream().anyMatch(
                            display -> display == null || !display.isValid())) {
                    removeGroup(displays);
                    iterator.remove();
                }
            }
        }

        @Override
        public synchronized void despawn() {
            if (!cancelled.compareAndSet(false, true)) return;
            removeGroups(groups.values());
            groups.clear();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }

        @Override
        public synchronized int entityCount() {
            return groups.values().stream().mapToInt(List::size).sum();
        }
    }

    record RenderDelta<T>(List<T> additions, List<T> removals) {}

    record Snapshot(
        List<LeafSnapshot> leaves,
        SurfaceMaterial[] materials,
        Quaternionf rotation
    ) {
        private static Snapshot capture(final SculptBlock block) {
            final List<LeafSnapshot> leaves = new ArrayList<>();
            final SurfaceMaterial[] materials =
                new SurfaceMaterial[16 * 16 * 16];
            // Biome tint lookup crosses the Bukkit/NMS boundary. A SculptBlock
            // can contain thousands of leaves but only a handful of materials,
            // so resolve each material once while taking the region snapshot.
            final Map<Material, BakeKey> bakeKeys = new HashMap<>();
            final Map<BakeKey, SurfaceMaterial> blockMaterials =
                new HashMap<>();
            final Map<PlayerHeadTexture, SurfaceMaterial> headMaterials =
                new HashMap<>();
            for (final OctreeNode leaf : block.root.collectLeaves()) {
                if (leaf.isRemoved()) continue;
                final org.bukkit.block.data.BlockData data = leaf.blockData() == null
                    ? block.originalBlockData : leaf.blockData();
                final PlayerHeadTexture heldTexture = leaf.playerHeadTexture();
                final SurfaceMaterial material;
                if (heldTexture != null) {
                    material = headMaterials.computeIfAbsent(
                        heldTexture, SurfaceMaterial::heldHead);
                } else {
                    final BakeKey bakeKey = bakeKeys.computeIfAbsent(
                        data.getMaterial(), ignored -> {
                            final BlockKey blockKey = BlockKey.from(data);
                            final int tint = block.tintFor(data);
                            return tint == 0 ? BakeKey.untinted(blockKey)
                                : new BakeKey(blockKey, tint);
                        });
                    material = blockMaterials.computeIfAbsent(
                        bakeKey, SurfaceMaterial::block);
                }
                for (int y = leaf.minY(); y < leaf.minY() + leaf.side(); y++) {
                    for (int z = leaf.minZ(); z < leaf.minZ() + leaf.side(); z++) {
                        for (int x = leaf.minX(); x < leaf.minX() + leaf.side(); x++) {
                            materials[index(x, y, z)] = material;
                        }
                    }
                }
                if (!block.rendersLeafWithTextDisplay(leaf)) continue;
                leaves.add(new LeafSnapshot(
                    material.bakeKey,
                    1 << leaf.depth(), HeadResolver.textureCoordFor(leaf, block),
                    leaf.minX(), leaf.minY(), leaf.minZ(), leaf.side(), material));
            }
            return new Snapshot(
                List.copyOf(leaves), materials, new Quaternionf(block.blockRotation));
        }
    }

    record LeafSnapshot(
        BakeKey bakeKey,
        int gridN,
        ChunkCoord textureCoord,
        int minX,
        int minY,
        int minZ,
        int side,
        SurfaceMaterial material
    ) {}

    record SurfaceMaterial(
        BakeKey bakeKey,
        PlayerHeadTexture playerHeadTexture
    ) {
        SurfaceMaterial {
            if ((bakeKey == null) == (playerHeadTexture == null)) {
                throw new IllegalArgumentException(
                    "exactly one surface material identity is required");
            }
        }

        private static SurfaceMaterial block(final BakeKey bakeKey) {
            return new SurfaceMaterial(Objects.requireNonNull(bakeKey), null);
        }

        private static SurfaceMaterial heldHead(final PlayerHeadTexture texture) {
            return new SurfaceMaterial(null, Objects.requireNonNull(texture));
        }
    }

    private record ResolvedLeaf(
        LeafSnapshot leaf,
        Optional<ChunkSpec> tile
    ) {}

    record QuadPoints(Vector3f p1, Vector3f p2, Vector3f p3) {}

    record PixelQuad(
        Vector3f p1,
        Vector3f p2,
        Vector3f p3,
        int argb
    ) {}

    private record RenderPlan(
        List<PixelQuad> quads,
        List<String> unsupported,
        boolean tooComplex
    ) {}

    private record AutoRefreshRequest(
        SculptBlock block,
        Material material
    ) {}
}
