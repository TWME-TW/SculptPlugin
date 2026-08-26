package dev.twme.sculpt.util;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Owns one viewer-private display and creates it on the destination region.
 * Consecutive {@link #show} calls reuse the live entity instead of causing
 * remove/spawn churn.
 * A generation token prevents delayed Folia tasks from resurrecting a display
 * after it has been replaced or cleared.
 */
public final class PrivateItemDisplay {

    private final Plugin plugin;
    private ItemDisplay current;
    private long generation;

    public PrivateItemDisplay(final Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void show(final Location location, final Player viewer,
                     final Consumer<ItemDisplay> configure) {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(configure, "configure");
        final World world = Objects.requireNonNull(location.getWorld(), "location world");
        final Location spawnLocation = location.clone();

        final ItemDisplay existing;
        final long request;
        synchronized (this) {
            request = ++generation;
            existing = current;
        }

        if (existing != null) {
            update(existing, spawnLocation, configure, request);
            return;
        }

        final Runnable spawn = () -> {
            final ItemDisplay created;
            try {
                created = world.spawn(spawnLocation, ItemDisplay.class, configure);
            } catch (final RuntimeException error) {
                plugin.getLogger().log(Level.WARNING,
                    "Failed to spawn a private ItemDisplay at " + spawnLocation, error);
                return;
            }

            final boolean keep;
            synchronized (this) {
                keep = request == generation;
                if (keep) current = created;
            }
            if (!keep) {
                created.remove();
                return;
            }

            final Runnable reveal = () -> {
                synchronized (PrivateItemDisplay.this) {
                    if (request != generation || current != created) return;
                }
                if (viewer.isOnline()) viewer.showEntity(plugin, created);
            };
            if (FoliaScheduler.isFolia()) {
                FoliaScheduler.runEntityTask(plugin, viewer, reveal);
            } else {
                reveal.run();
            }
        };

        if (FoliaScheduler.isFolia()) {
            FoliaScheduler.runRegionTask(plugin, spawnLocation, spawn);
        } else {
            spawn.run();
        }
    }

    private void update(final ItemDisplay display, final Location location,
                        final Consumer<ItemDisplay> configure,
                        final long request) {
        final Runnable update = () -> {
            synchronized (PrivateItemDisplay.this) {
                if (request != generation || current != display) return;
            }
            try {
                configure.accept(display);
                if (FoliaScheduler.isFolia()) {
                    display.teleportAsync(location);
                } else {
                    display.teleport(location);
                }
            } catch (final RuntimeException error) {
                plugin.getLogger().log(Level.WARNING,
                    "Failed to update a private ItemDisplay at " + location, error);
            }
        };
        if (FoliaScheduler.isFolia()) {
            FoliaScheduler.runEntityTask(plugin, display, update);
        } else {
            update.run();
        }
    }

    public void clear() {
        final ItemDisplay previous;
        synchronized (this) {
            generation++;
            previous = current;
            current = null;
        }
        remove(previous);
    }

    private void remove(final ItemDisplay display) {
        if (display != null) {
            FoliaScheduler.runEntityTask(plugin, display, display::remove);
        }
    }
}
