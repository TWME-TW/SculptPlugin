package dev.twme.sculpt.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Interaction;
import org.bukkit.persistence.PersistentDataContainer;
import org.junit.jupiter.api.Test;

class InteractionSpawnerTest {

    @Test
    void clickProxyUsesTheBlockBottomAsItsHitboxOrigin() {
        final AtomicReference<Location> spawnedAt = new AtomicReference<>();
        final PersistentDataContainer pdc = interfaceProxy(
            PersistentDataContainer.class, Map.of());
        final Interaction interaction = interfaceProxy(Interaction.class, Map.of(
            "getPersistentDataContainer", args -> pdc));
        final World world = interfaceProxy(World.class, Map.of(
            "spawn", args -> {
                spawnedAt.set(((Location) args[0]).clone());
                @SuppressWarnings("unchecked")
                final Consumer<Interaction> configure =
                    (Consumer<Interaction>) args[2];
                configure.accept(interaction);
                return interaction;
            }));

        InteractionSpawner.spawn(new Location(world, 10.5, 64.5, -2.5));

        assertEquals(10.5, spawnedAt.get().getX());
        assertEquals(64.0, spawnedAt.get().getY());
        assertEquals(-2.5, spawnedAt.get().getZ());
    }

    @Test
    void loadedClickProxyIsMovedFromTheOldCenterPosition() {
        final World world = interfaceProxy(World.class, Map.of());
        final AtomicReference<Location> teleportedTo = new AtomicReference<>();
        final PersistentDataContainer pdc = interfaceProxy(
            PersistentDataContainer.class, Map.of());
        final Interaction interaction = interfaceProxy(Interaction.class, Map.of(
            "isValid", args -> true,
            "getPersistentDataContainer", args -> pdc,
            "getLocation", args -> new Location(world, 10.5, 64.5, -2.5),
            "teleport", args -> {
                teleportedTo.set(((Location) args[0]).clone());
                return true;
            }));

        InteractionSpawner.align(
            interaction, new Location(world, 10.5, 64.5, -2.5));

        assertEquals(64.0, teleportedTo.get().getY());
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(Object[] args);
    }

    @SuppressWarnings("unchecked")
    private static <T> T interfaceProxy(
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
