package dev.twme.sculpt.util;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Interaction;
import org.bukkit.persistence.PersistentDataType;

/**
 * Utility for spawning and removing Interaction click-proxy entities.
 *
 * <p>Each non-empty SculptBlock whose fill keeps the backing block as AIR gets
 * one Interaction entity covering the whole block. BARRIER-backed blocks use
 * the block interaction directly and do not need a click proxy.
 */
public final class InteractionSpawner {

    private static final NamespacedKey TYPE_KEY = new NamespacedKey("sculpt", "type");
    private static final String CLICK_PROXY_TYPE = "click_proxy";
    private static final String LEGACY_TYPE = "shulker_interaction";

    private InteractionSpawner() {
    }

    /**
     * Spawn an Interaction click-proxy covering the given block position.
     * Interaction entity locations are the bottom center of their hitbox, while
     * SculptBlock locations passed here are the geometric block center.
     *
     * @param center the block center location
     * @return the spawned Interaction entity
     */
    public static Interaction spawn(final Location center) {
        final Location hitboxOrigin = hitboxOrigin(center);
        final Interaction interaction = hitboxOrigin.getWorld().spawn(hitboxOrigin,
            Interaction.class, e -> {
                e.setInteractionWidth(1.0f);
                e.setInteractionHeight(1.0f);
                e.setInvulnerable(true);
                e.setResponsive(true);
                e.getPersistentDataContainer().set(
                    TYPE_KEY,
                    PersistentDataType.STRING, CLICK_PROXY_TYPE);
            });
        return interaction;
    }

    /** Reposition a loaded proxy created by an older version. */
    public static void align(final Interaction interaction, final Location center) {
        if (interaction == null || !interaction.isValid()) return;
        interaction.getPersistentDataContainer().set(
            TYPE_KEY, PersistentDataType.STRING, CLICK_PROXY_TYPE);
        final Location expected = hitboxOrigin(center);
        final Location current = interaction.getLocation();
        if (current.getWorld() != expected.getWorld()
                || current.distanceSquared(expected) > 1e-8) {
            interaction.teleport(expected);
        }
    }

    private static Location hitboxOrigin(final Location center) {
        return center.clone().subtract(0, 0.5, 0);
    }

    /**
     * Remove an Interaction entity.
     *
     * @param interaction the Interaction to remove
     */
    public static void remove(final Interaction interaction) {
        if (interaction != null && interaction.isValid()) {
            interaction.remove();
        }
    }

    /**
     * Check whether an Interaction entity belongs to the sculpt system.
     *
     * @param interaction the entity to check
     * @return true if it has the current or legacy click-proxy PDC marker
     */
    public static boolean isSculptInteraction(final Interaction interaction) {
        final String type = interaction.getPersistentDataContainer()
            .get(TYPE_KEY, PersistentDataType.STRING);
        return CLICK_PROXY_TYPE.equals(type) || LEGACY_TYPE.equals(type);
    }
}
