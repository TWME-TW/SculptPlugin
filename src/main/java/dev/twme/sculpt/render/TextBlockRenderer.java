package dev.twme.sculpt.render;

import org.bukkit.Material;

import dev.twme.sculpt.core.AutoDisplayMaterialStatus;
import dev.twme.sculpt.core.SculptBlock;

/**
 * Asynchronously reconciles normal SculptBlock cells with TextDisplay pixels.
 * Implementations may reuse the block's current handle to preserve unchanged
 * entities across edits.
 */
@FunctionalInterface
public interface TextBlockRenderer {

    TextBlockRenderHandle render(SculptBlock block);

    /**
     * Restore native environment lighting on an existing TextDisplay render
     * without rebuilding its pixel entities.
     */
    default TextLightingRefreshResult refreshLighting(final SculptBlock block) {
        return TextLightingRefreshResult.EMPTY;
    }

    /**
     * Classify a material for automatic rendering without blocking the region
     * thread. Implementations may arrange a later block refresh when returning
     * {@link AutoDisplayMaterialStatus#LOADING}.
     */
    default AutoDisplayMaterialStatus autoMaterialStatus(
            final SculptBlock block,
            final Material material) {
        return AutoDisplayMaterialStatus.OPAQUE;
    }
}
