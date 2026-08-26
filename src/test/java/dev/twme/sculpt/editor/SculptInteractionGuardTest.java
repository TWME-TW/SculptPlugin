package dev.twme.sculpt.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.core.HeadResolver;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.PlayerHeadTexture;
import dev.twme.sculpt.core.SculptBlock;
import dev.twme.sculpt.plugin.BlockPosKey;

class SculptInteractionGuardTest {

    @Test
    void heldPlayerHeadCellsRequireTextureAndGridAboveOne() {
        final CellMaterial texturedHead = new CellMaterial(
            blockData(Material.PLAYER_HEAD),
            new PlayerHeadTexture("texture", ""));
        final CellMaterial plainHead = CellMaterial.block(
            blockData(Material.PLAYER_HEAD));

        assertEquals("command.sculpt_edit.player_head_grid_required",
            SculptEditListener.heldMaterialRejection(
                texturedHead, 1, ignored -> true));
        assertEquals("command.sculpt_edit.player_head_without_texture",
            SculptEditListener.heldMaterialRejection(
                plainHead, 2, ignored -> true));
        assertNull(SculptEditListener.heldMaterialRejection(
            texturedHead, 2, ignored -> true));
    }

    @Test
    void ordinaryNonBakeableHeldBlocksRemainRejected() {
        assertEquals("command.sculpt_edit.non_bakeable_block",
            SculptEditListener.heldMaterialRejection(
                CellMaterial.block(blockData(Material.OAK_SLAB)),
                4, material -> material == Material.OAK_SLAB));
    }

    @Test
    void blueprintEntityTargetUsesTheSideTowardThePlayer() {
        Vector center = new Vector(10.5, 64.5, 20.5);

        assertEquals(BlockFace.WEST,
            SculptEditListener.faceTowardPoint(new Vector(8.0, 64.5, 20.5), center));
        assertEquals(BlockFace.EAST,
            SculptEditListener.faceTowardPoint(new Vector(13.0, 64.5, 20.5), center));
        assertEquals(BlockFace.NORTH,
            SculptEditListener.faceTowardPoint(new Vector(10.5, 64.5, 18.0), center));
        assertEquals(BlockFace.SOUTH,
            SculptEditListener.faceTowardPoint(new Vector(10.5, 64.5, 23.0), center));
    }

    @Test
    void blueprintEntityTargetSupportsPlayersAboveAndBelowTheBlock() {
        Vector center = new Vector(10.5, 64.5, 20.5);

        assertEquals(BlockFace.UP,
            SculptEditListener.faceTowardPoint(new Vector(10.5, 67.0, 20.5), center));
        assertEquals(BlockFace.DOWN,
            SculptEditListener.faceTowardPoint(new Vector(10.5, 62.0, 20.5), center));
    }

    @Test
    @SuppressWarnings("removal")
    void inactivePlayerLeftClickDoesNotReachSculptBlock() {
        final CountingRegistry registry = new CountingRegistry();
        final SculptEditListener listener = listener(registry);
        final EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
            inactivePlayer(), sculptInteraction(),
            DamageCause.ENTITY_ATTACK, interfaceProxy(DamageSource.class, Map.of()), 1.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
        assertEquals(0, registry.activeBlockLookups.get());
    }

    @Test
    void inactivePlayerRightClickDoesNotReachSculptBlock() {
        final CountingRegistry registry = new CountingRegistry();
        final SculptEditListener listener = listener(registry);
        final PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(
            inactivePlayer(), sculptInteraction());

        listener.onPlayerInteractEntity(event);

        assertFalse(event.isCancelled());
        assertEquals(0, registry.activeBlockLookups.get());
    }

    @Test
    @SuppressWarnings("removal")
    void activePlayerLeftClickOnSculptShulkerReachesItsParentBlock() {
        final CountingRegistry registry = new CountingRegistry(true);
        final SculptEditListener listener = listener(registry);
        final EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
            activePlayer(), sculptShulker(),
            DamageCause.ENTITY_ATTACK, interfaceProxy(DamageSource.class, Map.of()), 1.0);

        listener.onEntityDamage(event);

        assertEquals(1, registry.activeBlockLookups.get());
    }

    @Test
    void activePlayerRightClickOnSculptShulkerReachesItsParentBlock() {
        final CountingRegistry registry = new CountingRegistry(true);
        final SculptEditListener listener = listener(registry);
        final PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(
            activePlayer(), sculptShulker(), EquipmentSlot.HAND);

        listener.onPlayerInteractEntity(event);

        assertEquals(1, registry.activeBlockLookups.get());
    }

    @Test
    void offHandEntityInteractionIsNotProcessedAgain() {
        final CountingRegistry registry = new CountingRegistry(true);
        final SculptEditListener listener = listener(registry);
        final PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(
            activePlayer(), sculptInteraction(), EquipmentSlot.OFF_HAND);

        listener.onPlayerInteractEntity(event);

        assertFalse(event.isCancelled());
        assertEquals(0, registry.activeBlockLookups.get());
    }

    @Test
    void rightClickDebounceOnlyRejectsDuplicateEventsInTheSameTick()
            throws ReflectiveOperationException {
        final SculptEditListener listener = listener(new CountingRegistry());
        final AtomicInteger ticksLived = new AtomicInteger(100);
        final Player player = interfaceProxy(Player.class, Map.of(
            "getTicksLived", args -> ticksLived.get()));
        final Method claim = SculptEditListener.class.getDeclaredMethod(
            "claimRightClick", Player.class);
        claim.setAccessible(true);

        assertTrue((boolean) claim.invoke(listener, player));
        assertFalse((boolean) claim.invoke(listener, player));

        ticksLived.incrementAndGet();
        assertTrue((boolean) claim.invoke(listener, player));
    }

    @Test
    @SuppressWarnings("removal")
    void vanillaShulkerIsNotTreatedAsSculptCollision() {
        final CountingRegistry registry = new CountingRegistry(true);
        final SculptEditListener listener = listener(registry);
        final EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
            activePlayer(), shulker(null),
            DamageCause.ENTITY_ATTACK, interfaceProxy(DamageSource.class, Map.of()), 1.0);

        listener.onEntityDamage(event);

        assertFalse(event.isCancelled());
        assertEquals(0, registry.activeBlockLookups.get());
    }

    private static SculptEditListener listener(final CountingRegistry registry) {
        final HeadResolver resolver = (node, block) -> null;
        return new SculptEditListener(null, resolver, registry, null);
    }

    private static BlockData blockData(final Material material) {
        return interfaceProxy(BlockData.class, Map.of(
            "getMaterial", args -> material,
            "getAsString", args -> material.getKey().toString(),
            "clone", args -> blockData(material)));
    }

    private static Player inactivePlayer() {
        final PlayerInventory inventory = interfaceProxy(PlayerInventory.class, Map.of(
            "getItemInMainHand", args -> null));
        return interfaceProxy(Player.class, Map.of(
            "getInventory", args -> inventory));
    }

    private static Player activePlayer() {
        return inactivePlayer();
    }

    private static Interaction sculptInteraction() {
        final PersistentDataContainer pdc = interfaceProxy(
            PersistentDataContainer.class, Map.of(
                "get", args -> "click_proxy"));
        final World world = interfaceProxy(World.class, Map.of(
            "getName", args -> "world"));
        return interfaceProxy(Interaction.class, Map.of(
            "getPersistentDataContainer", args -> pdc,
            "getLocation", args -> new Location(world, 3.5, 64.0, 7.5)));
    }

    private static Shulker sculptShulker() {
        return shulker("shulker");
    }

    private static Shulker shulker(final String marker) {
        final PersistentDataContainer pdc = interfaceProxy(
            PersistentDataContainer.class, Map.of(
                "get", args -> marker));
        final World world = interfaceProxy(World.class, Map.of(
            "getName", args -> "world"));
        return interfaceProxy(Shulker.class, Map.of(
            "getPersistentDataContainer", args -> pdc,
            "getLocation", args -> new Location(world, 3.5, 64.0, 7.5)));
    }

    private static final class CountingRegistry
            implements SculptEditListener.SculptBlockRegistry {
        private final AtomicInteger activeBlockLookups = new AtomicInteger();
        private final boolean sculptMode;

        CountingRegistry() {
            this(false);
        }

        CountingRegistry(final boolean sculptMode) {
            this.sculptMode = sculptMode;
        }

        @Override
        public SculptBlock getActiveBlock(final BlockPosKey key) {
            activeBlockLookups.incrementAndGet();
            return null;
        }

        @Override
        public boolean registerSculptBlock(final BlockPosKey key, final SculptBlock block) {
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
        public void unregisterSculptBlock(final BlockPosKey key, final SculptBlock block) {
        }

        @Override
        public int getPlayerGrid(final Player player) {
            return 16;
        }

        @Override
        public boolean isSculptMode(final Player player) {
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
            return false;
        }
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
