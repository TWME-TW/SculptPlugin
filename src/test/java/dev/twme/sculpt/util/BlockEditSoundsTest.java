package dev.twme.sculpt.util;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEditSoundsTest {

    @Test
    void batchPrefersOnePlacementOverAllOtherChanges() {
        final AtomicInteger placeCalls = new AtomicInteger();
        final AtomicInteger breakCalls = new AtomicInteger();
        final AtomicReference<Location> playedAt = new AtomicReference<>();
        final AtomicReference<BlockData> playedData = new AtomicReference<>();
        final BlockEditSounds.Batch batch = new BlockEditSounds.Batch(
            (location, data) -> {
                placeCalls.incrementAndGet();
                playedAt.set(location);
                playedData.set(data);
                return true;
            },
            (location, data) -> {
                breakCalls.incrementAndGet();
                return true;
            });
        final Location firstBreak = new Location(null, 0.5, 64.5, 0.5);
        final Location firstPlace = new Location(null, 1.5, 64.5, 0.5);
        final BlockData breakData = blockData();
        final BlockData placeData = blockData();

        batch.recordBreak(firstBreak, breakData);
        batch.recordBreak(new Location(null, 2.5, 64.5, 0.5), blockData());
        batch.recordPlace(firstPlace, placeData);
        batch.recordPlace(new Location(null, 3.5, 64.5, 0.5), blockData());

        assertTrue(batch.play());
        assertEquals(1, placeCalls.get());
        assertEquals(0, breakCalls.get());
        assertSame(firstPlace, playedAt.get());
        assertSame(placeData, playedData.get());
    }

    @Test
    void breakOnlyBatchPlaysOnceAndEmptyBatchStaysSilent() {
        final AtomicInteger breakCalls = new AtomicInteger();
        final BlockEditSounds.Batch batch = new BlockEditSounds.Batch(
            (location, data) -> false,
            (location, data) -> {
                breakCalls.incrementAndGet();
                return true;
            });

        batch.recordBreak(new Location(null, 0.5, 70.5, 0.5), blockData());

        assertTrue(batch.play());
        assertEquals(1, breakCalls.get());
        assertFalse(new BlockEditSounds.Batch((l, d) -> true, (l, d) -> true).play());
    }

    private static BlockData blockData() {
        return proxy(BlockData.class, (method, args) -> null);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final Invocation invocation) {
        return (T) Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) return type.getSimpleName();
                final Object value = invocation.invoke(
                    method.getName(), args == null ? new Object[0] : args);
                return value != null ? value : defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(final Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        return 0D;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }
}
