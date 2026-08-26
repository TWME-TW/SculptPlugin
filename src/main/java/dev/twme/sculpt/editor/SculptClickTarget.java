package dev.twme.sculpt.editor;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Shulker;

import dev.twme.sculpt.util.InteractionSpawner;
import dev.twme.sculpt.util.ShulkerSpawner;

/** Resolves the parent block of an entity used by adaptive collision. */
final class SculptClickTarget {

    private SculptClickTarget() {
    }

    @Nullable
    static Location blockLocation(final Entity entity) {
        if (entity instanceof Interaction interaction
                && InteractionSpawner.isSculptInteraction(interaction)) {
            return interaction.getLocation().toBlockLocation();
        }
        if (entity instanceof Shulker shulker
                && ShulkerSpawner.isSculptShulker(shulker)) {
            return shulker.getLocation().toBlockLocation();
        }
        return null;
    }
}
