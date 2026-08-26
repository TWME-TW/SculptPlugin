package dev.twme.sculpt.util;

import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/**
 * Folia / Paper / Spigot scheduling abstraction.
 *
 * <p>On Folia the global-region scheduler replaces the single-threaded
 * "main thread" concept.  On regular Paper/Spigot we fall back to
 * {@code Bukkit.getScheduler()} as before.
 *
 * <p>Detection is done once at class-load by probing for the Folia
 * marker class {@code io.papermc.paper.threadedregions.RegionizedServer}.
 *
 * <p>All methods in this class are safe to call on any platform; the
 * correct underlying scheduler is chosen automatically.
 *
 * @see PlatformDetector#FOLIA
 */
public final class FoliaScheduler {

    /** Cached Folia detection result (same probe as PlatformDetector.FOLIA). */
    private static final boolean FOLIA = PlatformDetector.FOLIA;

    private FoliaScheduler() {}

    public static boolean isFolia() {
        return FOLIA;
    }

    // ====================================================================
    //  One-shot task scheduling
    // ====================================================================

    /**
     * Schedule {@code task} on the global region / main thread.
     *
     * @return a cancellable task handle ({@code ScheduledTask} on Folia,
     *         {@code BukkitTask} on Paper)
     */
    public static Object runGlobalTask(Plugin plugin, Runnable task) {
        if (FOLIA) {
            return Bukkit.getGlobalRegionScheduler()
                    .run(plugin, wrap(task));
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Schedule {@code task} on the global region / main thread after
     * {@code delay} ticks.
     *
     * @return a cancellable task handle
     */
    public static Object runGlobalTaskLater(Plugin plugin, Runnable task, long delay) {
        if (FOLIA) {
            return Bukkit.getGlobalRegionScheduler()
                    .runDelayed(plugin, wrap(task), delay);
        }
        return Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }

    /**
     * Schedule a repeating {@code task} on the global region / main thread.
     *
     * @return a cancellable task handle
     */
    public static Object runGlobalTaskTimer(Plugin plugin, Runnable task,
                                       long initialDelay, long period) {
        if (FOLIA) {
            return Bukkit.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, wrap(task), initialDelay, period);
        }
        return Bukkit.getScheduler().runTaskTimer(plugin, task, initialDelay, period);
    }

    /** Schedule work that owns or communicates with a specific entity. */
    public static Object runEntityTask(Plugin plugin, Entity entity, Runnable task) {
        if (FOLIA) {
            return entity.getScheduler().run(plugin, wrap(task), null);
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Schedule delayed work owned by a specific entity. */
    public static Object runEntityTaskLater(Plugin plugin, Entity entity,
                                            Runnable task, long delay) {
        if (FOLIA) {
            return entity.getScheduler().runDelayed(plugin, wrap(task), null, delay);
        }
        return Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }

    /** Schedule work owned by a block/chunk region. */
    public static Object runRegionTask(Plugin plugin, Location location, Runnable task) {
        if (location.getWorld() == null) throw new IllegalArgumentException("Location has no world");
        return runRegionTask(plugin, location.getWorld(), location.getBlockX() >> 4,
            location.getBlockZ() >> 4, task);
    }

    public static Object runRegionTask(Plugin plugin, World world, int chunkX, int chunkZ,
                                       Runnable task) {
        if (FOLIA) {
            return Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, wrap(task));
        }
        return Bukkit.getScheduler().runTask(plugin, task);
    }

    /** Schedule delayed work owned by a block/chunk region. */
    public static Object runRegionTaskLater(Plugin plugin, Location location,
                                            Runnable task, long delay) {
        if (location.getWorld() == null) throw new IllegalArgumentException("Location has no world");
        if (FOLIA) {
            return Bukkit.getRegionScheduler().runDelayed(plugin, location, wrap(task), delay);
        }
        return Bukkit.getScheduler().runTaskLater(plugin, task, delay);
    }

    // ====================================================================
    //  Cancellation
    // ====================================================================

    /**
     * Cancel a previously scheduled task.  Accepts the opaque handle
     * returned by any {@code run*} method in this class.  No-op if the
     * handle is {@code null}.
     */
    public static void cancelTask(Object taskHandle) {
        if (taskHandle == null) return;
        if (FOLIA) {
            ((io.papermc.paper.threadedregions.scheduler.ScheduledTask) taskHandle)
                    .cancel();
        } else {
            ((org.bukkit.scheduler.BukkitTask) taskHandle).cancel();
        }
    }

    // ====================================================================
    //  Executor adapter (for CompletableFuture.thenRunAsync)
    // ====================================================================

    /**
     * Returns a {@link java.util.concurrent.Executor} that runs tasks on
     * the global region / main thread.  Useful as the second argument to
     * {@code CompletableFuture.thenRunAsync(runnable, executor)}.
     */
    public static java.util.concurrent.Executor globalExecutor(Plugin plugin) {
        return task -> {
            if (FOLIA) {
                Bukkit.getGlobalRegionScheduler().run(plugin, wrap(task));
            } else {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        };
    }

    // ====================================================================
    //  Internals
    // ====================================================================

    /** Wrap a plain {@link Runnable} into the Folia {@code Consumer<ScheduledTask>} shape. */
    private static Consumer<io.papermc.paper.threadedregions.scheduler.ScheduledTask> wrap(
            Runnable task) {
        return _task -> task.run();
    }
}
