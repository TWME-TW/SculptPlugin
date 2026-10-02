package dev.twme.sculpt.editor.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.Location;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

class PreviewSceneTest {

    @Test
    void previewOriginDropsViewRotation() {
        final Location player = new Location(null, 12.5, 64.0, -3.25, 135f, -27f);
        final Location origin = PreviewScene.aligned(player);

        assertEquals(0f, origin.getYaw());
        assertEquals(0f, origin.getPitch());
        assertEquals(12.5, origin.getX());
        assertEquals(64.0, origin.getY());
        assertEquals(-3.25, origin.getZ());
        assertEquals(135f, player.getYaw(), "the player location must stay untouched");
        assertEquals(-27f, player.getPitch());
    }

    @Test
    void pointComparisonUsesValuesNotIdentity() {
        final Vector3f first = new Vector3f(1f, 2f, 3f);
        final Vector3f second = new Vector3f(-4f, 5f, 6f);

        assertTrue(PreviewScene.same(List.of(first, second),
            List.of(new Vector3f(1f, 2f, 3f), new Vector3f(-4f, 5f, 6f))));
        assertFalse(PreviewScene.same(List.of(first, second),
            List.of(new Vector3f(1f, 2f, 3f), new Vector3f(-4f, 5f, 7f))));
        assertFalse(PreviewScene.same(List.of(first, second), List.of(first)));
        assertFalse(PreviewScene.same(List.of(first, second), List.of(second, first)));
    }
}
