package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.plugin.SculptPermissions;

class BlockPlaceBuildCheckerTest {

    @Test
    void bypassUsesDedicatedRegionProtectionPermission() {
        final AtomicReference<String> checkedPermission =
            new AtomicReference<>();
        final Player player = (Player) Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] { Player.class },
            (proxy, method, args) -> {
                if (method.getName().equals("hasPermission")) {
                    checkedPermission.set((String) args[0]);
                    return true;
                }
                throw new UnsupportedOperationException(method.getName());
            });

        assertTrue(BlockPlaceBuildChecker.hasBypassPermission(player));
        assertEquals(SculptPermissions.BYPASS_REGION_PROTECTION,
            checkedPermission.get());
    }

    @Test
    void probeFailsClosedUntilItsEventIsObserved() {
        final Object event = new Object();
        final BlockPlaceBuildChecker.Probe probe =
            new BlockPlaceBuildChecker.Probe(event);

        assertTrue(probe.matches(event));
        assertFalse(probe.matches(new Object()));
        assertFalse(probe.result());
    }

    @Test
    void probeRequiresBothUncancelledAndCanBuild() {
        final BlockPlaceBuildChecker.Probe cancelled =
            new BlockPlaceBuildChecker.Probe(new Object());
        cancelled.capture(true, true);
        assertFalse(cancelled.result());

        final BlockPlaceBuildChecker.Probe denied =
            new BlockPlaceBuildChecker.Probe(new Object());
        denied.capture(false, false);
        assertFalse(denied.result());

        final BlockPlaceBuildChecker.Probe allowed =
            new BlockPlaceBuildChecker.Probe(new Object());
        allowed.capture(false, true);
        assertTrue(allowed.result());
    }
}
