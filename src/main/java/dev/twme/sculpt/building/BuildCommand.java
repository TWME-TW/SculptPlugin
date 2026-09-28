package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.joml.Vector3d;

import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.MessageUtil;

/**
 * {@code /sculpt build} — build multi-angle planes, curved surfaces, curves,
 * spheres, and cylinders from control points at the current resolution.
 */
public final class BuildCommand {

    /** Shapes and the number of control points each one uses. */
    enum Shape {
        PLANE(3, BuildLimits.MAX_POINTS),
        SURFACE(4, BuildLimits.MAX_POINTS),
        CURVE(2, BuildLimits.MAX_POINTS),
        SPHERE(2, 2),
        CYLINDER(3, 3);

        final int minPoints;
        final int maxPoints;

        Shape(final int minPoints, final int maxPoints) {
            this.minPoints = minPoints;
            this.maxPoints = maxPoints;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Shape parse(final String value) {
            for (final Shape shape : values()) {
                if (shape.id().equals(value.toLowerCase(Locale.ROOT))) return shape;
            }
            return null;
        }
    }

    private static final List<String> POINT_SUBCOMMANDS =
        List.of("point", "undo-point", "clear", "points");
    private static final List<String> FLAGS =
        List.of("--thickness", "--carve", "--hollow", "--material", "--rows");

    private final BuildToolkit toolkit;

    public BuildCommand(final BuildToolkit toolkit) {
        this.toolkit = toolkit;
    }

    public boolean execute(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender, "building.player_only");
            return true;
        }
        if (!checkPermission(player)) return true;
        if (args.length == 0) {
            MessageUtil.sendTranslated(player, "building.usage");
            return true;
        }
        final String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "point" -> addPointAtCursor(player, args.length > 1
                && args[1].equalsIgnoreCase("surface"));
            case "undo-point" -> removeLastPoint(player);
            case "clear" -> {
                toolkit.state().clearPoints(player.getUniqueId());
                MessageUtil.sendTranslated(player, "building.points.cleared");
            }
            case "points" -> listPoints(player);
            default -> {
                final Shape shape = Shape.parse(subcommand);
                if (shape == null) {
                    MessageUtil.sendTranslated(player, "building.usage");
                } else {
                    build(player, shape, args);
                }
            }
        }
        return true;
    }

    // =====================================================================
    //  Control points
    // =====================================================================

    /**
     * Add the cell under the cursor. Without {@code surface}, the point is
     * placed in the empty cell in front of the targeted face, like placing a
     * block; with it, the targeted cell itself is used.
     */
    void addPointAtCursor(final Player player, final boolean surface) {
        final BuildToolkit.CellTarget target = toolkit.trace(player);
        if (target == null) {
            MessageUtil.sendTranslatedActionBar(player, "building.points.no_target");
            return;
        }
        final Vector3d point = target.center(!surface);
        final int count = toolkit.state().addPoint(
            player.getUniqueId(), target.world().getUID(), point);
        if (count < 0) {
            MessageUtil.sendTranslatedActionBar(player, "building.points.limit",
                BuildLimits.MAX_POINTS);
            return;
        }
        MessageUtil.sendTranslatedActionBar(player, "building.points.added",
            count, format(point.x), format(point.y), format(point.z));
    }

    void removeLastPoint(final Player player) {
        final int remaining = toolkit.state().removeLastPoint(player.getUniqueId());
        if (remaining < 0) {
            MessageUtil.sendTranslatedActionBar(player, "building.points.none");
        } else {
            MessageUtil.sendTranslatedActionBar(player, "building.points.removed", remaining);
        }
    }

    private void listPoints(final Player player) {
        final List<Vector3d> points = toolkit.state().points(
            player.getUniqueId(), player.getWorld().getUID());
        if (points.isEmpty()) {
            MessageUtil.sendTranslated(player, "building.points.none");
            return;
        }
        MessageUtil.sendTranslated(player, "building.points.header", points.size());
        for (int index = 0; index < points.size(); index++) {
            final Vector3d point = points.get(index);
            MessageUtil.sendTranslated(player, "building.points.entry", index + 1,
                format(point.x), format(point.y), format(point.z));
        }
    }

    // =====================================================================
    //  Shapes
    // =====================================================================

    /** Parsed {@code --flag} options. */
    record Options(double thickness, boolean carve, boolean hollow,
                   String material, int rows, String errorKey, String errorArgument) {
        static Options parse(final String[] args, final int from) {
            double thickness = 1.0;
            boolean carve = false;
            boolean hollow = false;
            String material = null;
            int rows = 0;
            for (int index = from; index < args.length; index++) {
                final String flag = args[index].toLowerCase(Locale.ROOT);
                switch (flag) {
                    case "--carve" -> carve = true;
                    case "--hollow" -> hollow = true;
                    case "--thickness", "--rows", "--material" -> {
                        if (index + 1 >= args.length) {
                            return error("building.options.missing_value", flag);
                        }
                        final String value = args[++index];
                        if (flag.equals("--material")) {
                            material = value;
                            continue;
                        }
                        try {
                            if (flag.equals("--thickness")) {
                                thickness = Double.parseDouble(value);
                                if (!(thickness >= 1.0)) {
                                    return error("building.options.invalid_number", value);
                                }
                            } else {
                                rows = Integer.parseInt(value);
                                if (rows < 2) {
                                    return error("building.options.invalid_number", value);
                                }
                            }
                        } catch (final NumberFormatException invalid) {
                            return error("building.options.invalid_number", value);
                        }
                    }
                    default -> {
                        return error("building.options.unknown", args[index]);
                    }
                }
            }
            return new Options(thickness, carve, hollow, material, rows, null, null);
        }

        private static Options error(final String key, final String argument) {
            return new Options(0, false, false, null, 0, key, argument);
        }
    }

    private void build(final Player player, final Shape shape, final String[] args) {
        final Options options = Options.parse(args, 1);
        if (options.errorKey() != null) {
            MessageUtil.sendTranslated(player, options.errorKey(), options.errorArgument());
            return;
        }
        final BuildLimits limits = toolkit.limits();
        if (options.thickness() > limits.maxThickness()) {
            MessageUtil.sendTranslated(player, "building.options.thickness_limit",
                limits.maxThickness());
            return;
        }
        if (!toolkit.isReady()) {
            MessageUtil.sendTranslated(player, "building.not_ready");
            return;
        }

        final List<Vector3d> points = toolkit.state().points(
            player.getUniqueId(), player.getWorld().getUID());
        if (points.size() < shape.minPoints || points.size() > shape.maxPoints) {
            MessageUtil.sendTranslated(player, "building.shape." + shape.id() + ".points",
                points.size());
            return;
        }
        final int rows = shape == Shape.SURFACE ? surfaceRows(points.size(), options.rows()) : 0;
        if (shape == Shape.SURFACE && rows < 0) {
            MessageUtil.sendTranslated(player, "building.shape.surface.grid", points.size());
            return;
        }

        BlockData material = null;
        if (!options.carve()) {
            final BuildToolkit.MaterialChoice choice =
                toolkit.resolveMaterial(player, options.material(), null);
            if (!choice.ok()) {
                MessageUtil.sendTranslated(player, choice.errorKey(), choice.errorArgument());
                return;
            }
            material = choice.material();
        }

        final int grid = toolkit.plugin().gridSizeFor(player);
        final Consumer<CellVolume> rasterizer = rasterizer(shape, points, options, rows);
        if (!toolkit.engine().tryBegin(player)) {
            MessageUtil.sendTranslated(player, "building.busy");
            return;
        }
        toolkit.runShape(player, player.getWorld(), shape.id(),
            options.carve() ? BlockCellEdit.Operation.CARVE : BlockCellEdit.Operation.ADD,
            material, grid, rasterizer, true);
    }

    static Consumer<CellVolume> rasterizer(
            final Shape shape,
            final List<Vector3d> points,
            final Options options,
            final int rows) {
        final double thickness = options.thickness();
        final double shell = options.hollow() ? thickness : 0.0;
        return switch (shape) {
            case PLANE -> volume -> ShapeRasterizer.polygon(points, thickness, volume);
            case SURFACE -> volume -> ShapeRasterizer.bezierSurface(
                points, rows, thickness, volume);
            case CURVE -> volume -> ShapeRasterizer.curve(points, thickness, volume);
            case SPHERE -> volume -> ShapeRasterizer.sphere(points.get(0),
                points.get(0).distance(points.get(1)), shell, volume);
            case CYLINDER -> volume -> ShapeRasterizer.cylinder(points.get(0), points.get(1),
                distanceToAxis(points.get(2), points.get(0), points.get(1)), shell, volume);
        };
    }

    /**
     * Rows of a surface control net. An explicit value must divide the point
     * count; otherwise square nets are preferred, then two rows.
     *
     * @return the row count, or {@code -1} when no valid grid exists
     */
    static int surfaceRows(final int points, final int requested) {
        if (requested > 0) {
            return points % requested == 0 && points / requested >= 2 ? requested : -1;
        }
        final int root = (int) Math.round(Math.sqrt(points));
        if (root >= 2 && root * root == points) return root;
        return points % 2 == 0 && points >= 4 ? 2 : -1;
    }

    static double distanceToAxis(final Vector3d point, final Vector3d start, final Vector3d end) {
        final Vector3d axis = new Vector3d(end).sub(start);
        final double length = axis.length();
        if (length < 1e-9) return point.distance(start);
        axis.div(length);
        final Vector3d offset = new Vector3d(point).sub(start);
        final double along = offset.dot(axis);
        return offset.sub(axis.mul(along)).length();
    }

    // =====================================================================
    //  Tab completion
    // =====================================================================

    public List<String> complete(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(SculptPermissions.BUILD) || args.length == 0) return List.of();
        if (args.length == 1) {
            final List<String> options = new ArrayList<>();
            for (final Shape shape : Shape.values()) options.add(shape.id());
            options.addAll(POINT_SUBCOMMANDS);
            return StringUtil.copyPartialMatches(args[0], options, new ArrayList<>());
        }
        if (args[0].equalsIgnoreCase("point") && args.length == 2) {
            return StringUtil.copyPartialMatches(args[1], List.of("surface"), new ArrayList<>());
        }
        if (Shape.parse(args[0]) == null) return List.of();
        final String previous = args[args.length - 2].toLowerCase(Locale.ROOT);
        if (previous.equals("--thickness") || previous.equals("--rows")) {
            return StringUtil.copyPartialMatches(args[args.length - 1],
                List.of("1", "2", "3", "4"), new ArrayList<>());
        }
        if (previous.equals("--material")) return List.of();
        return StringUtil.copyPartialMatches(args[args.length - 1], FLAGS, new ArrayList<>());
    }

    private static boolean checkPermission(final Player player) {
        if (player.hasPermission(SculptPermissions.BUILD)) return true;
        MessageUtil.sendTranslated(player, "general.no_permission");
        MessageUtil.sendTranslated(player, "general.required_perm", SculptPermissions.BUILD);
        return false;
    }

    static String format(final double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
