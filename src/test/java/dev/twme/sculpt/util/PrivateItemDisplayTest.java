package dev.twme.sculpt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PrivateItemDisplayTest {

    @Test
    void showUpdatesAndTeleportsAnExistingDisplayWithoutSpawningAnother()
            throws ReflectiveOperationException {
        final AtomicInteger spawns = new AtomicInteger();
        final AtomicInteger configures = new AtomicInteger();
        final AtomicInteger teleports = new AtomicInteger();
        final World world = proxy(World.class, Map.of(
            "spawn", args -> {
                spawns.incrementAndGet();
                return null;
            }));
        final ItemDisplay entity = proxy(ItemDisplay.class, Map.of(
            "teleport", args -> {
                teleports.incrementAndGet();
                return true;
            }));
        final PrivateItemDisplay display = new PrivateItemDisplay(
            proxy(Plugin.class, Map.of()));
        final Field current = PrivateItemDisplay.class.getDeclaredField("current");
        current.setAccessible(true);
        current.set(display, entity);

        display.show(
            new Location(world, 1.5, 64.5, 2.5),
            proxy(Player.class, Map.of()),
            ignored -> configures.incrementAndGet());

        assertEquals(0, spawns.get());
        assertEquals(1, configures.get());
        assertEquals(1, teleports.get());
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
            final Class<T> type, final Map<String, Invocation> methods) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) return type.getSimpleName();
                final Invocation invocation = methods.get(method.getName());
                if (invocation != null) {
                    return invocation.invoke(args == null ? new Object[0] : args);
                }
                return defaultValue(method.getReturnType());
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
}
