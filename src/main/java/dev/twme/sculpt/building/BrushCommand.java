package dev.twme.sculpt.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.MessageUtil;

/** {@code /sculpt brush} — configure the sculpting brush. */
public final class BrushCommand {

    private static final List<String> SETTINGS = List.of("size", "shape", "mode", "material");

    private final BuildToolkit toolkit;

    public BrushCommand(final BuildToolkit toolkit) {
        this.toolkit = toolkit;
    }

    public boolean execute(final CommandSender sender, final String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.sendTranslated(sender, "building.player_only");
            return true;
        }
        if (!player.hasPermission(SculptPermissions.BRUSH)) {
            MessageUtil.sendTranslated(player, "general.no_permission");
            MessageUtil.sendTranslated(player, "general.required_perm", SculptPermissions.BRUSH);
            return true;
        }
        final BuildPlayerState.BrushSettings current =
            toolkit.state().brush(player.getUniqueId());
        if (args.length == 0) {
            show(player, current);
            return true;
        }
        if (args.length != 2) {
            MessageUtil.sendTranslated(player, "building.brush.usage");
            return true;
        }
        final String value = args[1].toLowerCase(Locale.ROOT);
        final BuildPlayerState.BrushSettings updated = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "size" -> parseSize(player, value, current);
            case "shape" -> parseShape(player, value, current);
            case "mode" -> parseMode(player, value, current);
            case "material" -> parseMaterial(player, args[1], current);
            default -> {
                MessageUtil.sendTranslated(player, "building.brush.usage");
                yield null;
            }
        };
        if (updated == null) return true;
        toolkit.state().setBrush(player.getUniqueId(), updated);
        show(player, updated);
        return true;
    }

    private BuildPlayerState.BrushSettings parseSize(
            final Player player,
            final String value,
            final BuildPlayerState.BrushSettings current) {
        final int max = toolkit.limits().maxBrushRadius();
        try {
            final int radius = Integer.parseInt(value);
            if (radius >= 0 && radius <= max) return current.withRadius(radius);
        } catch (final NumberFormatException ignored) {
            // Reported below.
        }
        MessageUtil.sendTranslated(player, "building.brush.invalid_size", max);
        return null;
    }

    private static BuildPlayerState.BrushSettings parseShape(
            final Player player,
            final String value,
            final BuildPlayerState.BrushSettings current) {
        for (final ShapeRasterizer.BrushShape shape : ShapeRasterizer.BrushShape.values()) {
            if (shape.name().toLowerCase(Locale.ROOT).equals(value)) {
                return current.withShape(shape);
            }
        }
        MessageUtil.sendTranslated(player, "building.brush.usage");
        return null;
    }

    private static BuildPlayerState.BrushSettings parseMode(
            final Player player,
            final String value,
            final BuildPlayerState.BrushSettings current) {
        for (final BuildPlayerState.BrushMode mode : BuildPlayerState.BrushMode.values()) {
            if (mode.name().toLowerCase(Locale.ROOT).equals(value)) {
                return current.withMode(mode);
            }
        }
        MessageUtil.sendTranslated(player, "building.brush.usage");
        return null;
    }

    private BuildPlayerState.BrushSettings parseMaterial(
            final Player player,
            final String value,
            final BuildPlayerState.BrushSettings current) {
        if (value.equalsIgnoreCase("hand")) return current.withMaterial(null);
        final BuildToolkit.MaterialChoice choice = toolkit.resolveMaterial(player, value, null);
        if (!choice.ok()) {
            MessageUtil.sendTranslated(player, choice.errorKey(), choice.errorArgument());
            return null;
        }
        return current.withMaterial(choice.material());
    }

    static void show(final Player player, final BuildPlayerState.BrushSettings settings) {
        MessageUtil.sendTranslated(player, "building.brush.settings",
            settings.radius(),
            settings.shape().name().toLowerCase(Locale.ROOT),
            settings.mode().name().toLowerCase(Locale.ROOT),
            settings.material() == null ? "hand" : settings.material().getAsString());
    }

    public List<String> complete(final CommandSender sender, final String[] args) {
        if (!sender.hasPermission(SculptPermissions.BRUSH)) return List.of();
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SETTINGS, new ArrayList<>());
        }
        if (args.length != 2) return List.of();
        final List<String> values = switch (args[0].toLowerCase(Locale.ROOT)) {
            case "size" -> sizes();
            case "shape" -> List.of("sphere", "cube");
            case "mode" -> List.of("sculpt", "smooth", "paint");
            case "material" -> List.of("hand");
            default -> List.of();
        };
        return StringUtil.copyPartialMatches(args[1], values, new ArrayList<>());
    }

    private List<String> sizes() {
        final List<String> sizes = new ArrayList<>();
        for (int radius = 0; radius <= toolkit.limits().maxBrushRadius(); radius++) {
            sizes.add(Integer.toString(radius));
        }
        return sizes;
    }
}
