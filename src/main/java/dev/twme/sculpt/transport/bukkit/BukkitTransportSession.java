package dev.twme.sculpt.transport.bukkit;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;

import dev.twme.sculpt.transport.DisplayHandle;
import dev.twme.sculpt.transport.TransportSession;

/**
 * Per-SculptBlock transport session (v2 ReDesign).
 *
 * <p>Owns a set of {@link BukkitDisplayHandle} references and a per-viewer
 * visibility map keyed by Player identity. All chunk-display entities
 * spawned by this session ride on a single root entity (see
 * {@link #spawnRoot} / {@link #spawnRiding}).
 *
 * <p><b>Visibility model</b>: handles are spawned with
 * {@code setVisibleByDefault(false)} so they are hidden from every online
 * player at creation. {@link #setVisible(Player, boolean)} is the only
 * path to expose them.
 *
 * <p>Paper 1.21+ exposes {@code Player.showEntity(Plugin, Entity)} and
 * {@code hideEntity(Plugin, Entity)} as plain API methods.
 */
public final class BukkitTransportSession implements TransportSession {

    private static final org.bukkit.NamespacedKey TYPE_KEY =
        new org.bukkit.NamespacedKey("sculpt", "type");
    private static final int PASSENGER_ATTACH_ATTEMPTS = 2;

    private final Plugin plugin;
    private final World world;
    /* Entity operations are confined to the owning Paper/Folia region. A
       write-efficient identity set avoids COW's O(n^2) spawn cost while still
       giving destroy() constant-time membership removal. */
    private final Set<BukkitDisplayHandle> handles =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Player> visibleViewers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private BukkitDisplayHandle rootHandle;  // 直接追蹤 root entity（避免 O(N) 掃描）

    // 所有實體預設對所有人可見。visibleViewers / setVisible 保留供未來使用。

    public BukkitTransportSession(World world) {
        this.plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("Sculpt");
        this.world = world;
    }

    @Override
    public DisplayHandle spawn(Location loc, ItemStack head, Transformation transformation) {
        if (loc.getWorld() != world) {
            throw new IllegalArgumentException(
                    "spawn location world " + loc.getWorld().getName()
                            + " does not match session world " + world.getName());
        }
        ItemDisplay display = world.spawn(loc, ItemDisplay.class, d -> {
            d.setItemStack(head);
            d.setTransformation(transformation);
            d.setPersistent(true);
        });
        BukkitDisplayHandle handle = new BukkitDisplayHandle(display);
        handles.add(handle);
        return handle;
    }

    @Override
    public void destroy(DisplayHandle handle) {
        if (handle instanceof BukkitDisplayHandle bukkit) {
            try {
                bukkit.despawn();
            } finally {
                // Even a failed Bukkit remove must not retain a dead wrapper
                // in a long-lived SculptBlock session.
                handles.remove(bukkit);
                if (rootHandle == bukkit) rootHandle = null;
            }
        }
    }

    @Override
    public void track(DisplayHandle handle) {
        if (!(handle instanceof BukkitDisplayHandle bukkit)) return;
        handles.add(bukkit);
        if ("root".equals(bukkit.getPDC(TYPE_KEY))) rootHandle = bukkit;
    }

    @Override
    public void destroyAll() {
        for (BukkitDisplayHandle h : handles) {
            h.despawn();
        }
        handles.clear();
        visibleViewers.clear();
        rootHandle = null;
    }

    @Override
    public void setVisible(Player viewer, boolean visible) {
        if (visible) {
            visibleViewers.add(viewer);
            for (BukkitDisplayHandle h : handles) {
                h.showFor(viewer, plugin);
            }
        } else {
            visibleViewers.remove(viewer);
            for (BukkitDisplayHandle h : handles) {
                h.hideFor(viewer, plugin);
            }
        }
    }

    // ====================================================================
    // v2 新增方法
    // ====================================================================

    @Override
    public DisplayHandle spawnRoot(Location blockCenter) {
        // 基底實體：純車輛用途，不可見、無碰撞
        // 使用 VALID 非 AIR 物品（AIR 在 Paper 重載 chunk 時會被 entity cleanup 清除，
        // 導致所有乘客消失）。 BARRIER 在 scale(0,0,0) + displayWidth/Height=0 下完全不可見。
        ItemDisplay display = world.spawn(blockCenter, ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(org.bukkit.Material.BARRIER));
            d.setTransformation(new org.bukkit.util.Transformation(
                new org.joml.Vector3f(), new org.joml.Quaternionf(),
                new org.joml.Vector3f(0, 0, 0), new org.joml.Quaternionf()));
            d.setDisplayWidth(0);
            d.setDisplayHeight(0);
            d.setPersistent(true);
        });
        BukkitDisplayHandle handle = new BukkitDisplayHandle(display);
        handle.setPDC(TYPE_KEY, "root");
        handles.add(handle);
        this.rootHandle = handle;
        return handle;
    }

    @Override
    public DisplayHandle spawnRiding(DisplayHandle vehicle, Location loc,
                                      ItemStack head, Transformation transform) {
        if (!(vehicle instanceof BukkitDisplayHandle bukkitVehicle)) {
            throw new IllegalArgumentException("vehicle must be a Bukkit display handle");
        }
        final org.bukkit.entity.Entity vehicleEntity = bukkitVehicle.entity();
        if (vehicleEntity == null || !vehicleEntity.isValid()) {
            throw new IllegalStateException("Cannot attach a leaf to an invalid Sculpt root");
        }

        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < PASSENGER_ATTACH_ATTEMPTS; attempt++) {
            final BukkitDisplayHandle child = (BukkitDisplayHandle) spawn(
                loc, head, transform);
            child.setPDC(TYPE_KEY, "leaf");
            final org.bukkit.entity.Entity childEntity = child.entity();
            try {
                final boolean attached = childEntity != null
                    && childEntity.isValid()
                    && vehicleEntity.addPassenger(childEntity);
                if (attached
                        && childEntity.getVehicle() == vehicleEntity
                        && vehicleEntity.getPassengers().contains(childEntity)) {
                    return child;
                }
            } catch (final RuntimeException exception) {
                lastFailure = exception;
            }
            destroy(child);
            if (!vehicleEntity.isValid()) break;
        }

        throw new IllegalStateException(
            "Failed to attach Sculpt leaf to its root at " + loc.toBlockLocation(),
            lastFailure);
    }

    @Override
    public void removePassenger(DisplayHandle vehicle, DisplayHandle child) {
        org.bukkit.entity.Entity ve = ((BukkitDisplayHandle) vehicle).entity();
        org.bukkit.entity.Entity ce = ((BukkitDisplayHandle) child).entity();
        if (ve != null && ce != null && ve.isValid() && ce.isValid()) {
            ve.removePassenger(ce);
        }
    }

    @Override
    public DisplayHandle getRootEntity() {
        return rootHandle;
    }

    @Override
    public Map<String, DisplayHandle> getPassengerMap() {
        Map<String, DisplayHandle> map = new java.util.HashMap<>();
        DisplayHandle root = getRootEntity();
        if (root == null) return map;
        org.bukkit.entity.Entity ve = ((BukkitDisplayHandle) root).entity();
        for (org.bukkit.entity.Entity passenger : ve.getPassengers()) {
            if (passenger instanceof ItemDisplay disp) {
                BukkitDisplayHandle h = new BukkitDisplayHandle(disp);
                String path = h.getPDC(new org.bukkit.NamespacedKey("sculpt", "path"));
                if (path != null) {
                    map.put(path, h);
                }
            }
        }
        return map;
    }
}
