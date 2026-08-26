package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.plugin.BlockPosKey;

class SculptMovePerformanceTest {

    @Test
    void samePoseRequiresBothPositionAndViewToRemainUnchanged() {
        final World world = proxy(World.class, Map.of());
        final Location original = new Location(world, 1.0, 64.0, 2.0, 30.0f, 10.0f);

        assertTrue(SculptEditListener.samePose(original, original.clone()));

        final Location turned = original.clone();
        turned.setYaw(31.0f);
        assertFalse(SculptEditListener.samePose(original, turned));

        final Location moved = original.clone().add(0.01, 0, 0);
        assertFalse(SculptEditListener.samePose(original, moved));
    }

    @Test
    void cancelledMoveDoesNotInspectPlayerStateOrQueueHover() {
        final CountingRegistry registry = new CountingRegistry(true, true);
        final AtomicInteger queued = new AtomicInteger();
        final World world = proxy(World.class, Map.of());
        final Player player = proxy(Player.class, Map.of());
        final PlayerMoveEvent event = new PlayerMoveEvent(
            player, new Location(world, 0, 64, 0), new Location(world, 1, 64, 0));
        event.setCancelled(true);

        listener(registry, (ignored, task) -> queued.incrementAndGet())
            .onPlayerMove(event);

        assertEquals(0, queued.get());
        assertEquals(0, registry.modeChecks.get());
    }

    @Test
    void disabledHoverReturnsBeforeSessionOrRayTrace() {
        final AtomicInteger rayTraces = new AtomicInteger();
        final World world = rayWorld(rayTraces);
        final Player player = rayPlayer(world);
        final CountingRegistry registry = new CountingRegistry(true, false);
        final AtomicInteger queued = new AtomicInteger();

        listener(registry, (ignored, task) -> queued.incrementAndGet())
            .onPlayerMove(move(player, world, 0, 1));

        assertEquals(0, queued.get());
        assertEquals(0, registry.gridReads.get());
        assertEquals(0, rayTraces.get());
    }

    @Test
    void severalMovesCoalesceIntoOneTickHover() {
        final AtomicInteger rayTraces = new AtomicInteger();
        final World world = rayWorld(rayTraces);
        final Player player = rayPlayer(world);
        final CountingRegistry registry = new CountingRegistry(true, true);
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        final SculptEditListener listener = listener(
            registry, (ignored, task) -> tasks.add(task));

        listener.onPlayerMove(move(player, world, 0, 1));
        listener.onPlayerMove(move(player, world, 1, 2));

        assertEquals(1, tasks.size());
        assertEquals(0, rayTraces.get());

        tasks.remove().run();

        assertEquals(1, rayTraces.get());

        listener.onPlayerMove(move(player, world, 2, 3));
        assertEquals(1, tasks.size());
    }

    private static SculptEditListener listener(
            final CountingRegistry registry,
            final SculptEditListener.HoverScheduler scheduler) {
        final Plugin plugin = proxy(Plugin.class, Map.of());
        final HeadResolver resolver = (node, block) -> null;
        return new SculptEditListener(plugin, resolver, registry, null, scheduler);
    }

    private static PlayerMoveEvent move(
            final Player player, final World world,
            final double fromX, final double toX) {
        return new PlayerMoveEvent(
            player,
            new Location(world, fromX, 64, 0),
            new Location(world, toX, 64, 0));
    }

    private static World rayWorld(final AtomicInteger rayTraces) {
        return proxy(World.class, Map.of(
            "getName", args -> "world",
            "rayTrace", args -> {
                rayTraces.incrementAndGet();
                return null;
            }));
    }

    private static Player rayPlayer(final World world) {
        final Location eye = new Location(world, 0.5, 65.5, 0.5) {
            @Override
            public Vector getDirection() {
                return new Vector(1, 0, 0);
            }
        };
        return proxy(Player.class, Map.of(
            "getWorld", args -> world,
            "getEyeLocation", args -> eye));
    }

    private static final class CountingRegistry
            implements SculptEditListener.SculptBlockRegistry {
        private final boolean sculptMode;
        private final boolean hoverEnabled;
        private final AtomicInteger modeChecks = new AtomicInteger();
        private final AtomicInteger gridReads = new AtomicInteger();

        private CountingRegistry(final boolean sculptMode, final boolean hoverEnabled) {
            this.sculptMode = sculptMode;
            this.hoverEnabled = hoverEnabled;
        }

        @Override
        public SculptBlock getActiveBlock(final BlockPosKey key) {
            return null;
        }

        @Override
        public boolean registerSculptBlock(
                final BlockPosKey key, final SculptBlock block) {
            return false;
        }

        @Override
        public boolean replaceSculptBlock(
                final BlockPosKey key, final SculptBlock expected,
                final SculptBlock replacement) {
            return false;
        }

        @Override
        public void unregisterSculptBlock(final BlockPosKey key) {
        }

        @Override
        public void unregisterSculptBlock(
                final BlockPosKey key, final SculptBlock block) {
        }

        @Override
        public int getPlayerGrid(final Player player) {
            gridReads.incrementAndGet();
            return 4;
        }

        @Override
        public boolean isSculptMode(final Player player) {
            modeChecks.incrementAndGet();
            return sculptMode;
        }

        @Override
        public BlockData heldBlockData(final Player player) {
            return null;
        }

        @Override
        public boolean isShulkerMode(final Player player) {
            return false;
        }

        @Override
        public boolean isNonBakeable(final Material material) {
            return false;
        }

        @Override
        public boolean isHoverEnabled(final Player player) {
            return hoverEnabled;
        }
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
