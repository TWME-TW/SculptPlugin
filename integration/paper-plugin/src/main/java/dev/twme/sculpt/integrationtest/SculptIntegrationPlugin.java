package dev.twme.sculpt.integrationtest;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Shulker;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

public final class SculptIntegrationPlugin extends JavaPlugin {

    private static final NamespacedKey SCULPT_TYPE =
            new NamespacedKey("sculpt", "type");
    private static final NamespacedKey SCULPT_PATH =
            new NamespacedKey("sculpt", "path");

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        try {
            if (args.length == 4 && "inspect".equals(args[0])) {
                inspect(sender, args);
                return true;
            }
            if (args.length == 4 && "fill".equals(args[0])) {
                fill(sender, args);
                return true;
            }
            if (args.length == 4 && "remove-cell".equals(args[0])) {
                removeCell(sender, args);
                return true;
            }
            if (args.length == 5 && "tint".equals(args[0])) {
                tint(sender, args);
                return true;
            }
            if (args.length == 4 && "orphan".equals(args[0])) {
                injectAndReconcileOrphan(sender, args);
                return true;
            }
            if ((args.length == 4 || args.length == 5) && "click".equals(args[0])
                    && sender instanceof Player player) {
                click(player, args);
                return true;
            }
            if (args.length == 1 && "folia-blueprint".equals(args[0])
                    && sender instanceof Player player) {
                foliaBlueprint(player);
                return true;
            }
            sender.sendMessage("SCULPT_TEST error=usage");
        } catch (ReflectiveOperationException exception) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Sculpt integration reflection failed", exception);
            sender.sendMessage("SCULPT_TEST error=reflection");
        } catch (RuntimeException exception) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "Sculpt integration inspection failed", exception);
            sender.sendMessage("SCULPT_TEST error=runtime");
        }
        return true;
    }

    /** Exercise both unsafe cuboid paths from a real Folia-owned player region. */
    private void foliaBlueprint(Player player) throws ReflectiveOperationException {
        World world = player.getWorld();
        int sourceX = player.getLocation().getBlockX();
        int sourceY = player.getLocation().getBlockY();
        int sourceZ = player.getLocation().getBlockZ() + 2;
        int remoteX = sourceX + 4_095;
        Location source = new Location(world, sourceX, sourceY, sourceZ);
        Location remote = new Location(world, remoteX, sourceY, sourceZ);
        world.getBlockAt(source).setType(Material.STONE, false);

        Object manager = invoke(sculpt(), "getBlueprintManager");
        if (!"CUBOID".equals(String.valueOf(
                invokeWithArgs(manager, "getSelectionMode", player)))) {
            invokeWithArgs(manager, "toggleSelectionMode", player);
        }

        invokeWithArgs(manager, "selectFirstCorner", player, source, null);
        invokeWithArgs(manager, "selectSecondCorner", player, source);
        Method create = method(manager, "createBlueprintFromSelection", 3);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object visibility = Enum.valueOf(
            (Class<? extends Enum>) create.getParameterTypes()[2], "PRIVATE");
        Object localBlueprint = create.invoke(manager, player, "folia-local", visibility);
        Object expandedBlueprint = replaceRecordComponent(
            localBlueprint, "sizeX", 4_096);
        Object settings = invoke(manager, "defaultPasteSettings");
        Object pasteError = method(manager, "pasteBlueprint", 4).invoke(
            manager, player, expandedBlueprint, source, settings);

        invokeWithArgs(manager, "clearSelection", player);
        invokeWithArgs(manager, "selectFirstCorner", player, source, null);
        invokeWithArgs(manager, "selectSecondCorner", player, remote);
        Object saveError = method(manager, "saveBlueprint", 4).invoke(
            manager, player, "folia-cross-region", false, null);
        invokeWithArgs(manager, "clearSelection", player);

        boolean localOwned = Bukkit.isOwnedByCurrentRegion(
            world, sourceX >> 4, sourceZ >> 4);
        boolean remoteOwned = Bukkit.isOwnedByCurrentRegion(
            world, remoteX >> 4, sourceZ >> 4);
        player.sendMessage("SCULPT_TEST foliaBlueprint=true"
            + ";localOwned=" + localOwned
            + ";remoteOwned=" + remoteOwned
            + ";pasteError=" + pasteError
            + ";saveError=" + saveError
            + ";source=" + world.getBlockAt(source).getType());
    }

    private void inspect(CommandSender sender, String[] args)
            throws ReflectiveOperationException {
        Target target = target(args);
        Object block = findBlock(target);
        if (block == null) {
            sender.sendMessage("SCULPT_TEST active=false;x=" + target.x
                    + ";y=" + target.y + ";z=" + target.z
                    + ";block=" + target.world.getBlockAt(
                            target.x, target.y, target.z).getType()
                    + ";raw=" + nearbyRawEntities(target));
            return;
        }

        Object root = publicField(block, "root").get(block);
        List<?> occupiedLeaves = list(invoke(root, "collectLeaves"));
        List<Object> leaves = allLeaves(root);
        int removed = 0;
        List<String> signatureParts = new ArrayList<>();
        for (Object leaf : leaves) {
            String path = String.valueOf(invoke(leaf, "pathAsString"));
            boolean isRemoved = (boolean) invoke(leaf, "isRemoved");
            if (isRemoved) removed++;
            Object blockData = invoke(leaf, "blockData");
            signatureParts.add(path + "=" + isRemoved + "="
                    + (blockData == null ? "<original>" : blockData));
        }
        signatureParts.sort(Comparator.naturalOrder());

        boolean shulkerMode = publicField(block, "shulkerMode").getBoolean(block);
        boolean fullCollision = (boolean) invoke(block, "usesFullBlockCollision");
        boolean entityCollision = (boolean) invoke(block, "usesEntityCollision");
        int collisionEntities = (int) invoke(block, "collisionEntityCount");
        Object clickProxy = invoke(block, "clickProxy");
        String clickProxyY = clickProxy instanceof Interaction interaction
                ? Double.toString(interaction.getLocation().getY()) : "none";
        Object rootHandle = publicField(block, "rootEntity").get(block);
        ItemDisplay rootEntity = null;
        if (rootHandle != null) {
            Object entity = invoke(rootHandle, "entity");
            if (entity instanceof ItemDisplay display) rootEntity = display;
        }

        Map<String, Integer> nearby = nearbySculptEntities(target);
        DisplayCounts displays = displayCounts(target, rootEntity);
        sender.sendMessage("SCULPT_TEST active=true"
                + ";x=" + target.x + ";y=" + target.y + ";z=" + target.z
                + ";state=" + publicField(block, "state").get(block)
                + ";shulker=" + shulkerMode
                + ";mixed=" + invoke(block, "isMixed")
                + ";tintArgb=" + String.format("%08X",
                        publicField(block, "tintArgb").getInt(block))
                + ";leaves=" + leaves.size()
                + ";occupied=" + occupiedLeaves.size()
                + ";removed=" + removed
                + ";signature=" + digest(signatureParts)
                + ";block=" + target.world.getBlockAt(
                        target.x, target.y, target.z).getType()
                + ";fullCollision=" + fullCollision
                + ";entityCollision=" + entityCollision
                + ";collisionEntities=" + collisionEntities
                + ";clickProxy=" + valid(clickProxy)
                + ";clickProxyY=" + clickProxyY
                + ";rootValid=" + valid(rootEntity)
                + ";rootPassengers="
                + (rootEntity == null ? -1 : rootEntity.getPassengers().size())
                + ";rootDisplays=" + displays.roots
                + ";leafDisplays=" + displays.leaves
                + ";orphanLeaves=" + displays.orphans
                + ";nearby=" + nearby);
    }

    private void injectAndReconcileOrphan(CommandSender sender, String[] args)
            throws ReflectiveOperationException {
        Target target = target(args);
        Object block = findBlock(target);
        if (block == null) {
            sender.sendMessage("SCULPT_TEST orphan=false;reason=missing");
            return;
        }
        Object rootHandle = publicField(block, "rootEntity").get(block);
        if (rootHandle == null || !(invoke(rootHandle, "entity") instanceof ItemDisplay root)) {
            sender.sendMessage("SCULPT_TEST orphan=false;reason=root");
            return;
        }
        ItemDisplay source = root.getPassengers().stream()
            .filter(ItemDisplay.class::isInstance)
            .map(ItemDisplay.class::cast)
            .findFirst()
            .orElse(null);
        if (source == null) {
            sender.sendMessage("SCULPT_TEST orphan=false;reason=leaf");
            return;
        }

        final String path = source.getPersistentDataContainer().get(
            SCULPT_PATH, PersistentDataType.STRING);
        final ItemDisplay orphan = target.world.spawn(
            source.getLocation(), ItemDisplay.class, display -> {
                display.setItemStack(source.getItemStack().clone());
                display.setTransformation(source.getTransformation());
                display.setPersistent(true);
                display.getPersistentDataContainer().set(
                    SCULPT_TYPE, PersistentDataType.STRING, "leaf");
                if (path != null) {
                    display.getPersistentDataContainer().set(
                        SCULPT_PATH, PersistentDataType.STRING, path);
                }
            });
        int before = displayCounts(target, root).orphans;
        sculpt().getClass().getMethod("reconcilePastedEntities", org.bukkit.Chunk.class)
            .invoke(sculpt(), target.world.getChunkAt(target.x >> 4, target.z >> 4));
        int after = displayCounts(target, root).orphans;
        sender.sendMessage("SCULPT_TEST orphan=true"
            + ";spawned=" + orphan.getUniqueId()
            + ";before=" + before
            + ";after=" + after);
    }

    private void fill(CommandSender sender, String[] args)
            throws ReflectiveOperationException {
        Target target = target(args);
        Object block = findBlock(target);
        if (block == null) {
            sender.sendMessage("SCULPT_TEST fill=false;reason=missing");
            return;
        }
        Object root = publicField(block, "root").get(block);
        Object recolored = list(invoke(root, "collectLeaves")).getFirst();
        recolored.getClass().getMethod("setBlockData",
                org.bukkit.block.data.BlockData.class)
                .invoke(recolored, Bukkit.createBlockData(Material.DIRT));
        block.getClass().getMethod("setMixed", boolean.class)
                .invoke(block, true);
        block.getClass().getMethod("restoreRange",
                int.class, int.class, int.class, int.class)
                .invoke(block, 0, 0, 0, 16);
        invoke(block, "repairDisplayEntities");
        invoke(block, "reRender");
        invoke(block, "reconcileCollisionState");
        invoke(block, "markPDCDirty");
        invoke(block, "syncPDC");
        sender.sendMessage("SCULPT_TEST fill=true");
    }

    private void removeCell(CommandSender sender, String[] args)
            throws ReflectiveOperationException {
        Target target = target(args);
        Object block = findBlock(target);
        if (block == null) {
            sender.sendMessage("SCULPT_TEST removeCell=false;reason=missing");
            return;
        }
        Object root = publicField(block, "root").get(block);
        List<?> leaves = list(invoke(root, "collectLeaves"));
        if (leaves.isEmpty()) {
            sender.sendMessage("SCULPT_TEST removeCell=false;reason=empty");
            return;
        }
        Object leaf = leaves.getFirst();
        block.getClass().getMethod("remove", leaf.getClass()).invoke(block, leaf);
        sender.sendMessage("SCULPT_TEST removeCell=true");
    }

    private void tint(CommandSender sender, String[] args)
            throws ReflectiveOperationException {
        Target target = target(args);
        Material renderedMaterial = Material.matchMaterial(args[4]);
        if (renderedMaterial == null) {
            sender.sendMessage("SCULPT_TEST tint=false;reason=material");
            return;
        }
        Class<?> reader = Class.forName(
                "dev.twme.sculpt.nms.BlockTintReader", true,
                sculpt().getClass().getClassLoader());
        int tintArgb = (int) reader.getMethod(
                "readAt", org.bukkit.block.Block.class, Material.class).invoke(
                    null,
                    target.world.getBlockAt(target.x, target.y, target.z),
                    renderedMaterial);
        sender.sendMessage("SCULPT_TEST tint=true;tintArgb="
                + String.format("%08X", tintArgb));
    }

    private static List<Object> allLeaves(Object root)
            throws ReflectiveOperationException {
        List<Object> leaves = new ArrayList<>();
        root.getClass().getMethod("collectAllLeaves", List.class)
                .invoke(root, leaves);
        return leaves;
    }

    private void click(Player player, String[] args) throws ReflectiveOperationException {
        Target target = target(args);
        final boolean shulkerLeft = args.length == 5
                && "shulker-left".equalsIgnoreCase(args[4]);
        final boolean shulkerRight = args.length == 5
                && ("shulker-right".equalsIgnoreCase(args[4])
                        || "shulker-sneak-right".equalsIgnoreCase(args[4]));
        final boolean sneakRight = args.length == 5
                && ("sneak-right".equalsIgnoreCase(args[4])
                        || "shulker-sneak-right".equalsIgnoreCase(args[4]));
        final boolean topRightNorthWest = args.length == 5
                && "top-right-nw".equalsIgnoreCase(args[4]);
        final boolean topRightSouthEast = args.length == 5
                && "top-right-se".equalsIgnoreCase(args[4]);
        final boolean shulkerRoute = shulkerLeft || shulkerRight;
        final Action action = args.length == 5
                && ("right".equalsIgnoreCase(args[4]) || shulkerRight || sneakRight
                        || topRightNorthWest || topRightSouthEast)
                ? Action.RIGHT_CLICK_BLOCK : Action.LEFT_CLICK_BLOCK;
        final boolean previouslySneaking = player.isSneaking();
        if (sneakRight) player.setSneaking(true);
        Location standing = topRightNorthWest || topRightSouthEast
                ? new Location(target.world,
                        target.x + (topRightNorthWest ? 0.25 : 0.75),
                        target.y + 3.0,
                        target.z + (topRightNorthWest ? 0.25 : 0.75))
                : new Location(target.world,
                        target.x + 0.5, target.y, target.z + 3.5);
        Vector eye = standing.toVector().add(
                new Vector(0, player.getEyeHeight(), 0));
        Vector direction = (topRightNorthWest || topRightSouthEast
                ? new Vector(
                        target.x + (topRightNorthWest ? 0.25 : 0.75),
                        topRightNorthWest ? target.y + 1.0 : target.y,
                        target.z + (topRightNorthWest ? 0.25 : 0.75))
                : new Vector(target.x + 0.5, target.y + 0.5, target.z + 0.5))
                .subtract(eye);
        standing.setDirection(direction);
        player.teleport(standing);

        org.bukkit.block.Block aimed = player.getTargetBlockExact(5);
        Plugin sculpt = sculpt();
        boolean sculptMode = (boolean) sculpt.getClass()
                .getMethod("isSculptMode", Player.class).invoke(sculpt, player);
        final Entity clickEntity = shulkerRoute
                ? findShulker(target) : findInteraction(target);
        final boolean cancelled;
        final String route;
        try {
            if (clickEntity != null) {
                route = shulkerRoute ? "shulker" : "entity";
                if (action == Action.RIGHT_CLICK_BLOCK) {
                    PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(
                            player, clickEntity, EquipmentSlot.HAND);
                    Bukkit.getPluginManager().callEvent(event);
                    cancelled = event.isCancelled();
                } else {
                    DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                            .withCausingEntity(player)
                            .withDirectEntity(player)
                            .build();
                    EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(
                            player, clickEntity, DamageCause.ENTITY_ATTACK, source, 1.0);
                    Bukkit.getPluginManager().callEvent(event);
                    cancelled = event.isCancelled();
                }
            } else {
                route = "block";
                PlayerInteractEvent event = new PlayerInteractEvent(
                        player, action,
                        player.getInventory().getItemInMainHand(),
                        target.world.getBlockAt(target.x, target.y, target.z),
                        BlockFace.UP, EquipmentSlot.HAND);
                Bukkit.getPluginManager().callEvent(event);
                cancelled = event.isCancelled();
            }
        } finally {
            if (sneakRight) player.setSneaking(previouslySneaking);
        }
        player.sendMessage("SCULPT_TEST click=true;action=" + action
                + ";route=" + route
                + ";cancelled=" + cancelled
                + ";sneaking=" + sneakRight
                + ";mode=" + sculptMode
                + ";aimed=" + (aimed == null ? "null"
                        : aimed.getX() + "," + aimed.getY() + "," + aimed.getZ()
                                + ":" + aimed.getType()));
    }

    private Interaction findInteraction(Target target) {
        Location center = new Location(target.world,
                target.x + 0.5, target.y + 0.5, target.z + 0.5);
        for (Entity entity : target.world.getNearbyEntities(center, 0.75, 0.75, 0.75)) {
            if (entity instanceof Interaction interaction
                    && "shulker_interaction".equals(interaction
                            .getPersistentDataContainer()
                            .get(SCULPT_TYPE, PersistentDataType.STRING))
                    && interaction.getLocation().getBlockX() == target.x
                    && interaction.getLocation().getBlockY() == target.y
                    && interaction.getLocation().getBlockZ() == target.z) {
                return interaction;
            }
        }
        return null;
    }

    private Shulker findShulker(Target target) {
        Location center = new Location(target.world,
                target.x + 0.5, target.y + 0.5, target.z + 0.5);
        for (Entity entity : target.world.getNearbyEntities(center, 0.75, 0.75, 0.75)) {
            if (entity instanceof Shulker shulker
                    && "shulker".equals(shulker.getPersistentDataContainer()
                            .get(SCULPT_TYPE, PersistentDataType.STRING))
                    && shulker.getLocation().getBlockX() == target.x
                    && shulker.getLocation().getBlockY() == target.y
                    && shulker.getLocation().getBlockZ() == target.z) {
                return shulker;
            }
        }
        return null;
    }

    private Object findBlock(Target target) throws ReflectiveOperationException {
        Plugin sculpt = sculpt();
        Collection<?> blocks = collection(invoke(sculpt, "getActiveBlocks"));
        for (Object block : blocks) {
            Location location = (Location) publicField(block, "pos").get(block);
            if (location.getWorld() == target.world
                    && location.getBlockX() == target.x
                    && location.getBlockY() == target.y
                    && location.getBlockZ() == target.z) {
                return block;
            }
        }
        return null;
    }

    private Plugin sculpt() {
        Plugin sculpt = Bukkit.getPluginManager().getPlugin("Sculpt");
        if (sculpt == null) throw new IllegalStateException("Sculpt is not loaded");
        return sculpt;
    }

    private Map<String, Integer> nearbySculptEntities(Target target) {
        Map<String, Integer> counts = new TreeMap<>();
        Location center = new Location(target.world,
                target.x + 0.5, target.y + 0.5, target.z + 0.5);
        for (Entity entity : target.world.getNearbyEntities(center, 1.0, 1.0, 1.0)) {
            String type = entity.getPersistentDataContainer().get(
                    SCULPT_TYPE, PersistentDataType.STRING);
            if (type != null) counts.merge(type, 1, Integer::sum);
        }
        return counts;
    }

    private DisplayCounts displayCounts(Target target, ItemDisplay expectedRoot) {
        int roots = 0;
        int leaves = 0;
        int orphans = 0;
        for (Entity entity : target.world.getChunkAt(target.x >> 4, target.z >> 4).getEntities()) {
            if (!(entity instanceof ItemDisplay display)) continue;
            if (display.getLocation().getBlockX() != target.x
                    || display.getLocation().getBlockY() != target.y
                    || display.getLocation().getBlockZ() != target.z) {
                continue;
            }
            String type = display.getPersistentDataContainer().get(
                SCULPT_TYPE, PersistentDataType.STRING);
            if ("root".equals(type)) {
                roots++;
            } else if ("leaf".equals(type)) {
                leaves++;
                if (expectedRoot == null
                        || display.getVehicle() != expectedRoot
                        || !expectedRoot.getPassengers().contains(display)) {
                    orphans++;
                }
            }
        }
        return new DisplayCounts(roots, leaves, orphans);
    }

    private List<String> nearbyRawEntities(Target target) {
        List<String> entities = new ArrayList<>();
        Location center = new Location(target.world,
                target.x + 0.5, target.y + 0.5, target.z + 0.5);
        for (Entity entity : target.world.getNearbyEntities(center, 2.0, 2.0, 2.0)) {
            String type = entity.getPersistentDataContainer().get(
                    SCULPT_TYPE, PersistentDataType.STRING);
            entities.add(entity.getType() + "@"
                    + entity.getLocation().getBlockX() + ","
                    + entity.getLocation().getBlockY() + ","
                    + entity.getLocation().getBlockZ() + ":" + type);
        }
        entities.sort(Comparator.naturalOrder());
        return entities;
    }

    private Target target(String[] args) {
        World world = Bukkit.getWorlds().getFirst();
        return new Target(world, Integer.parseInt(args[1]),
                Integer.parseInt(args[2]), Integer.parseInt(args[3]));
    }

    private static String digest(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean valid(Object value) throws ReflectiveOperationException {
        return value != null && (boolean) invoke(value, "isValid");
    }

    private static Object invoke(Object target, String method)
            throws ReflectiveOperationException {
        Method match = null;
        for (Method candidate : target.getClass().getMethods()) {
            if (candidate.getName().equals(method)
                    && candidate.getParameterCount() == 0) {
                match = candidate;
                break;
            }
        }
        if (match == null) throw new NoSuchMethodException(
                target.getClass().getName() + "." + method + "()");
        return match.invoke(target);
    }

    private static Object invokeWithArgs(Object target, String name, Object... arguments)
            throws ReflectiveOperationException {
        return method(target, name, arguments.length).invoke(target, arguments);
    }

    private static Method method(Object target, String name, int parameterCount)
            throws NoSuchMethodException {
        Method match = null;
        for (Method candidate : target.getClass().getMethods()) {
            if (candidate.getName().equals(name)
                    && candidate.getParameterCount() == parameterCount) {
                if (match != null) {
                    throw new NoSuchMethodException(
                        target.getClass().getName() + "." + name
                            + " has multiple " + parameterCount + "-argument overloads");
                }
                match = candidate;
            }
        }
        if (match == null) throw new NoSuchMethodException(
            target.getClass().getName() + "." + name
                + "(" + parameterCount + " arguments)");
        return match;
    }

    private static Object replaceRecordComponent(
            Object source, String componentName, Object replacement)
            throws ReflectiveOperationException {
        RecordComponent[] components = source.getClass().getRecordComponents();
        if (components == null) {
            throw new IllegalArgumentException(source.getClass().getName() + " is not a record");
        }
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        boolean replaced = false;
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            parameterTypes[index] = component.getType();
            arguments[index] = component.getAccessor().invoke(source);
            if (component.getName().equals(componentName)) {
                arguments[index] = replacement;
                replaced = true;
            }
        }
        if (!replaced) throw new NoSuchFieldException(componentName);
        return source.getClass().getConstructor(parameterTypes).newInstance(arguments);
    }

    private static Field publicField(Object target, String name)
            throws NoSuchFieldException {
        return target.getClass().getField(name);
    }

    @SuppressWarnings("unchecked")
    private static List<?> list(Object value) {
        return (List<?>) value;
    }

    @SuppressWarnings("unchecked")
    private static Collection<?> collection(Object value) {
        return (Collection<?>) value;
    }

    private record Target(World world, int x, int y, int z) {
    }

    private record DisplayCounts(int roots, int leaves, int orphans) {
    }
}
