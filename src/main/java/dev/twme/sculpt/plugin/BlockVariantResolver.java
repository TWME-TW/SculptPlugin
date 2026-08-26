package dev.twme.sculpt.plugin;

import dev.twme.sculpt.core.BlockKey;
import dev.twme.sculpt.core.VariantKey;
import dev.twme.sculpt.skin.HeadsRegistry;
import org.bukkit.block.data.BlockData;
import org.joml.Quaternionf;

import java.util.Set;

/**
 * Glue between Bukkit's {@link BlockData} (full property string with id
 * prefix and square brackets, e.g. {@code "minecraft:oak_log[axis=y]"})
 * and {@link VariantKey#pickMatching} which only knows about the
 * {@code k=v,k=v} property string.
 *
 * <p>Used by every SculptBlock creation path to derive the actual
 * orientation quaternion for orientable
 * blocks (logs, stairs, furnaces, observers, …). Without this, all
 * SculptBlocks render the canonical (x=0,y=0) variant regardless of
 * the actual world block's facing / axis.
 */
public final class BlockVariantResolver {

    private BlockVariantResolver() {}

    /**
     * Result of resolving a live {@link BlockData} against the
     * pre-baked variant map for a block.
     *
     * @param matchedVariantKey the canonical variant key that the block
     *                          matched (may be empty if no variant
     *                          matched — caller should treat as identity)
     * @param blockRotation     the world-space rotation that aligns the
     *                          canonical model to the matched variant
     *                          (identity if no variant matched)
     */
    public record Result(String matchedVariantKey, Quaternionf blockRotation) {}

    /**
     * Resolve a live {@link BlockData} against the registry's variant
     * map for {@code key}.
     *
     * @param data     the actual Bukkit block data (e.g. oak_log[axis=y])
     * @param key      the block key (e.g. minecraft:oak_log)
     * @param registry the heads registry, providing the variant map
     * @return the matched variant key + rotation, or identity if
     *         the registry has no variants for this block
     */
    public static Result resolve(BlockData data, BlockKey key, HeadsRegistry registry) {
        if (data == null || key == null || registry == null) {
            return new Result("", new Quaternionf());
        }
        // Variant indexes may be cold on a region thread. Use only the
        // resident view here; RegistryHeadResolver schedules the background
        // prefetch and a later edit will pick up the full orientation.
        Set<String> variants = registry.variantsForIfLoaded(key).keySet();
        if (variants.isEmpty() && registry.hasKnownBlock(key)
                && registry.variantsLoadedIfPresent(key) == null) {
            registry.prefetchIndex(key);
        }
        if (variants.isEmpty()) {
            // No variant map → block is non-orientable; identity rotation.
            return new Result("", new Quaternionf());
        }
        String full = stripBlockDataPrefix(data.getAsString());
        String matched = VariantKey.pickMatching(full, variants);
        Quaternionf rot = matched.isEmpty()
                ? new Quaternionf()
                : registry.rotationForIfLoaded(key, matched);
        return new Result(matched, rot);
    }

    /**
     * Convert {@code "minecraft:oak_log[axis=y]"} into
     * {@code "axis=y"} (or empty if the block has no properties).
     * Brackets / spaces are tolerated; missing prefix too.
     */
    private static String stripBlockDataPrefix(String raw) {
        if (raw == null) return "";
        int lbracket = raw.indexOf('[');
        if (lbracket < 0) return "";
        int rbracket = raw.lastIndexOf(']');
        if (rbracket <= lbracket) return "";
        return raw.substring(lbracket + 1, rbracket);
    }
}
