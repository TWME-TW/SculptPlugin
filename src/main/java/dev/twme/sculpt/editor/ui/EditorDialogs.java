package dev.twme.sculpt.editor.ui;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import dev.twme.sculpt.Sculpt;
import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.building.BuildLimits;
import dev.twme.sculpt.building.ShapeRasterizer;
import dev.twme.sculpt.core.CellMaterial;
import dev.twme.sculpt.core.FillMode;
import dev.twme.sculpt.core.SculptDisplayMode;
import dev.twme.sculpt.editor.EditorService;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.tool.BlueprintTool;
import dev.twme.sculpt.editor.tool.BrushTool;
import dev.twme.sculpt.editor.tool.SelectionActions;
import dev.twme.sculpt.editor.tool.ShapeTool;
import dev.twme.sculpt.editor.tool.SmoothTool;
import dev.twme.sculpt.editor.tool.TransformTool;
import dev.twme.sculpt.gui.HeadBrowserGUI;
import dev.twme.sculpt.plugin.SculptCommand;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.FoliaScheduler;
import dev.twme.sculpt.util.MessageUtil;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;

/**
 * Native Paper dialogs that replace the old settings commands. Every
 * callback is re-dispatched to the player's own thread.
 */
public final class EditorDialogs {

    private static final int WIDE = 200;
    private static final int NARROW = 98;
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder()
        .uses(1).lifetime(Duration.ofMinutes(10)).build();

    private EditorDialogs() {
    }

    // =====================================================================
    //  Main menu (inside or outside the editor)
    // =====================================================================

    public static void main(final Player player, final EditorService service) {
        final boolean editing = service.isEditing(player);
        final List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(player, editing ? "editor.menu.exit" : "editor.menu.enter", WIDE, view -> {
            if (service.isEditing(player)) service.exit(player, true);
            else service.enter(player);
        }));
        buttons.add(button(player, "editor.menu.knife", NARROW, view -> {
            player.getInventory().addItem(service.createKnife(player));
            MessageUtil.sendTranslated(player, "editor.knife.given");
        }));
        buttons.add(button(player, "editor.menu.help", NARROW, view -> help(player)));
        if (player.hasPermission(SculptPermissions.UNDO)) {
            buttons.add(button(player, "editor.menu.undo", NARROW, view -> service.revert(player, false, 1)));
            buttons.add(button(player, "editor.menu.redo", NARROW, view -> service.revert(player, true, 1)));
        }
        if (editing) {
            final EditorSession session = service.session(player);
            buttons.add(button(player, "editor.menu.settings", NARROW, view -> settings(session)));
            buttons.add(button(player, "editor.menu.palette", NARROW, view -> palette(session)));
        }
        if (player.hasPermission(SculptPermissions.HEADS)) {
            buttons.add(button(player, "editor.menu.heads", WIDE, view -> {
                if (service.isEditing(player)) service.exit(player, false);
                new HeadBrowserGUI(service.plugin(), player).open();
            }));
        }
        show(player, "editor.menu.title", List.of(body(player, "editor.menu.body")), List.of(), buttons, 2);
    }

    public static void help(final Player player) {
        final List<DialogBody> lines = new ArrayList<>();
        for (int line = 1; line <= 8; line++) {
            final String key = "editor.help.line" + line;
            final String text = MessageUtil.getTranslated(player, key);
            if (!text.equals(key)) lines.add(DialogBody.plainMessage(parse(text), 300));
        }
        show(player, "editor.help.title", lines, List.of(), List.of(), 1);
    }

    // =====================================================================
    //  Editor settings and palette
    // =====================================================================

    public static void settings(final EditorSession session) {
        final Player player = session.player();
        final Sculpt plugin = session.plugin();
        final List<SingleOptionDialogInput.OptionEntry> grids = new ArrayList<>();
        for (final String grid : SculptCommand.allowedGridSizes(player)) {
            grids.add(SingleOptionDialogInput.OptionEntry.create(grid, Component.text(grid),
                grid.equals(Integer.toString(session.grid()))));
        }
        final List<SingleOptionDialogInput.OptionEntry> fills = new ArrayList<>();
        for (final FillMode fill : FillMode.values()) {
            if (!player.hasPermission(SculptPermissions.fill(fill.id()))) continue;
            fills.add(SingleOptionDialogInput.OptionEntry.create(fill.id(),
                text(player, "editor.fill." + fill.id()), fill == plugin.fillModeFor(player)));
        }
        final List<SingleOptionDialogInput.OptionEntry> displays = new ArrayList<>();
        for (final SculptDisplayMode display : SculptDisplayMode.values()) {
            if (!player.hasPermission(SculptPermissions.display(display.id()))) continue;
            displays.add(SingleOptionDialogInput.OptionEntry.create(display.id(),
                text(player, "editor.display." + display.id()), display == plugin.displayModeFor(player)));
        }
        final List<DialogInput> inputs = new ArrayList<>();
        if (!grids.isEmpty()) {
            inputs.add(DialogInput.singleOption("grid", text(player, "editor.settings.resolution"), grids).build());
        }
        if (!fills.isEmpty()) {
            inputs.add(DialogInput.singleOption("fill", text(player, "editor.settings.fill"), fills).build());
        }
        if (!displays.isEmpty()) {
            inputs.add(DialogInput.singleOption("display", text(player, "editor.settings.display"), displays).build());
        }
        inputs.add(DialogInput.bool("animations", text(player, "editor.settings.animations"))
            .initial(session.scene() != null && session.scene().animations()).build());
        show(player, "editor.settings.title", List.of(), inputs, List.of(
            button(player, "editor.dialog.apply", WIDE, view -> {
                final String grid = view.getText("grid");
                if (grid != null && SculptCommand.canUseGrid(player, Integer.parseInt(grid))) {
                    plugin.setGridSizeFor(player, Integer.parseInt(grid));
                }
                final FillMode fill = FillMode.parse(view.getText("fill"), null);
                if (fill != null && player.hasPermission(SculptPermissions.fill(fill.id()))) {
                    plugin.setFillMode(player, fill);
                }
                final SculptDisplayMode display = SculptDisplayMode.parse(view.getText("display"), null);
                if (display != null && player.hasPermission(SculptPermissions.display(display.id()))) {
                    plugin.setDisplayMode(player, display);
                }
                final Boolean animations = view.getBoolean("animations");
                if (animations != null) session.scene().setAnimations(animations);
                session.flash("editor.settings.applied");
            })), 1);
    }

    public static void palette(final EditorSession session) {
        final Player player = session.player();
        final List<ActionButton> buttons = new ArrayList<>();
        for (final CellMaterial entry : session.palette()) {
            buttons.add(ActionButton.builder(Component.text(EditorSession.describe(entry)))
                .width(NARROW)
                .action(action(player, view -> choose(session, entry)))
                .build());
        }
        buttons.add(button(player, "editor.palette.typed", NARROW, view -> {
            final String typed = view.getText("block");
            if (typed == null || typed.isBlank()) return;
            try {
                choose(session, CellMaterial.block(Bukkit.createBlockData(normalize(typed))));
            } catch (final IllegalArgumentException invalid) {
                MessageUtil.sendTranslated(player, "building.material.invalid", typed);
            }
        }));
        buttons.add(button(player, "editor.palette.off_hand", NARROW, view -> {
            final CellMaterial held = session.plugin().cellMaterialOf(player.getInventory().getItemInOffHand());
            if (held == null) {
                MessageUtil.sendTranslated(player, "editor.palette.off_hand_empty");
            } else {
                choose(session, held);
            }
        }));
        show(player, "editor.palette.title",
            List.of(body(player, "editor.palette.body", EditorSession.describe(session.material()))),
            List.of(DialogInput.text("block", text(player, "editor.palette.input")).maxLength(256).build()),
            buttons, 3);
    }

    private static void choose(final EditorSession session, final CellMaterial material) {
        final String error = session.service().materialError(session.player(), material, session.grid());
        if (error != null) {
            MessageUtil.sendTranslated(session.player(), error, material.blockData().getMaterial().getKey().getKey());
            return;
        }
        session.setMaterial(material);
        session.flash("editor.material.picked", EditorSession.describe(material));
    }

    // =====================================================================
    //  Tool settings
    // =====================================================================

    public static void brush(final EditorSession session, final BrushTool brush) {
        final Player player = session.player();
        final int max = session.service().engine().limits().maxBrushRadius();
        final List<SingleOptionDialogInput.OptionEntry> shapes = new ArrayList<>();
        for (final ShapeRasterizer.BrushShape shape : ShapeRasterizer.BrushShape.values()) {
            final String id = shape.name().toLowerCase(Locale.ROOT);
            shapes.add(SingleOptionDialogInput.OptionEntry.create(id, text(player, "editor.brush_shape." + id),
                shape == brush.shape()));
        }
        show(player, "editor.brush.title", List.of(), List.of(
            DialogInput.numberRange("radius", text(player, "editor.brush.radius"), 0, Math.max(1, max))
                .step(1f).initial((float) brush.radius()).build(),
            DialogInput.singleOption("shape", text(player, "editor.brush.shape"), shapes).build()
        ), List.of(button(player, "editor.dialog.apply", WIDE, view -> {
            final Float radius = view.getFloat("radius");
            final ShapeRasterizer.BrushShape shape = ShapeRasterizer.BrushShape.valueOf(
                view.getText("shape").toUpperCase(Locale.ROOT));
            brush.configure(session, radius == null ? brush.radius() : Math.round(radius), shape);
            session.flash("editor.settings.applied");
        })), 1);
    }

    public static void smooth(final EditorSession session, final SmoothTool smooth) {
        final Player player = session.player();
        final BuildLimits limits = session.service().engine().limits();
        final int max = Math.max(1, limits.maxSmoothPasses());
        final List<SingleOptionDialogInput.OptionEntry> shapes = new ArrayList<>();
        for (final ShapeRasterizer.BrushShape shape : ShapeRasterizer.BrushShape.values()) {
            final String id = shape.name().toLowerCase(Locale.ROOT);
            shapes.add(SingleOptionDialogInput.OptionEntry.create(id, text(player, "editor.brush_shape." + id),
                shape == smooth.shape()));
        }
        show(player, "editor.smooth.title", List.of(), List.of(
            DialogInput.numberRange("radius", text(player, "editor.brush.radius"), 0, Math.max(1, limits.maxBrushRadius()))
                .step(1f).initial((float) smooth.radius()).build(),
            DialogInput.singleOption("shape", text(player, "editor.brush.shape"), shapes).build(),
            DialogInput.numberRange("passes", text(player, "editor.smooth.passes"), 1, max)
                .step(1f).initial((float) smooth.passes()).build()
        ), List.of(button(player, "editor.dialog.apply", WIDE, view -> {
            final Float radius = view.getFloat("radius");
            final ShapeRasterizer.BrushShape shape = ShapeRasterizer.BrushShape.valueOf(
                view.getText("shape").toUpperCase(Locale.ROOT));
            smooth.configure(session,
                radius == null ? smooth.radius() : Math.round(radius), shape,
                Math.round(orDefault(view.getFloat("passes"), smooth.passes())));
            session.flash("editor.settings.applied");
        })), 1);
    }

    public static void shape(final EditorSession session, final ShapeTool tool) {
        final Player player = session.player();
        final List<SingleOptionDialogInput.OptionEntry> types = new ArrayList<>();
        for (final ShapeTool.Type type : ShapeTool.Type.values()) {
            types.add(SingleOptionDialogInput.OptionEntry.create(type.id(),
                text(player, "editor.shape_type." + type.id()), type == tool.type()));
        }
        final int maxThickness = session.service().engine().limits().maxThickness();
        show(player, "editor.shape.title", List.of(body(player, "editor.shape.body")), List.of(
            DialogInput.singleOption("type", text(player, "editor.shape.type"), types).build(),
            DialogInput.numberRange("thickness", text(player, "editor.shape.thickness"), 1, Math.max(2, maxThickness))
                .step(1f).initial((float) tool.thickness()).build(),
            DialogInput.bool("hollow", text(player, "editor.shape.hollow")).initial(tool.hollow()).build(),
            DialogInput.bool("carve", text(player, "editor.shape.carve")).initial(tool.carve()).build(),
            DialogInput.numberRange("rows", text(player, "editor.shape.rows"), 0, 4)
                .step(1f).initial((float) tool.rows()).build()
        ), List.of(
            button(player, "editor.dialog.apply", NARROW, view -> {
                tool.configure(session, ShapeTool.Type.valueOf(view.getText("type").toUpperCase(Locale.ROOT)),
                    Math.round(orDefault(view.getFloat("thickness"), tool.thickness())),
                    Boolean.TRUE.equals(view.getBoolean("hollow")),
                    Boolean.TRUE.equals(view.getBoolean("carve")),
                    Math.round(orDefault(view.getFloat("rows"), tool.rows())));
                session.flash("editor.settings.applied");
            }),
            button(player, "editor.shape.clear", NARROW, view -> tool.cancel(session))), 2);
    }

    public static void transform(final EditorSession session, final TransformTool tool) {
        final Player player = session.player();
        final List<SingleOptionDialogInput.OptionEntry> rotations = new ArrayList<>();
        for (int turns = 0; turns < 4; turns++) {
            rotations.add(SingleOptionDialogInput.OptionEntry.create(Integer.toString(turns),
                Component.text(turns * 90 + "°"), turns == tool.quarterTurns()));
        }
        final Consumer<DialogResponseView> configure = view -> tool.configure(
            Boolean.TRUE.equals(view.getBoolean("copy")),
            Boolean.TRUE.equals(view.getBoolean("mirror_x")),
            Boolean.TRUE.equals(view.getBoolean("mirror_z")),
            Integer.parseInt(view.getText("rotation")));
        show(player, "editor.transform.title", List.of(body(player, "editor.transform.body")), List.of(
            DialogInput.bool("copy", text(player, "editor.transform.copy")).initial(tool.copy()).build(),
            DialogInput.bool("mirror_x", text(player, "editor.transform.mirror_x")).initial(tool.mirrorX()).build(),
            DialogInput.bool("mirror_z", text(player, "editor.transform.mirror_z")).initial(tool.mirrorZ()).build(),
            DialogInput.singleOption("rotation", text(player, "editor.transform.rotation"), rotations).build()
        ), List.of(
            button(player, "editor.transform.apply", NARROW, view -> {
                configure.accept(view);
                tool.apply(session);
            }),
            button(player, "editor.dialog.save", NARROW, view -> {
                configure.accept(view);
                session.flash("editor.settings.applied");
            }),
            button(player, "editor.transform.reset", WIDE, view -> tool.reset())), 2);
    }

    // =====================================================================
    //  Selection actions
    // =====================================================================

    public static void selectionActions(final EditorSession session) {
        final Player player = session.player();
        final List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(player, "editor.actions.delete", NARROW, view -> SelectionActions.delete(session)));
        buttons.add(button(player, "editor.actions.fill", NARROW, view -> SelectionActions.fill(session)));
        buttons.add(button(player, "editor.actions.paint", NARROW, view -> SelectionActions.paint(session)));
        buttons.add(button(player, "editor.actions.transform", NARROW,
            view -> session.selectToolFromDialog(ToolId.TRANSFORM)));
        final List<DialogInput> inputs = new ArrayList<>();
        if (player.hasPermission(SculptPermissions.REPLACE)) {
            inputs.add(DialogInput.text("replace", text(player, "editor.actions.replace_input"))
                .initial(session.material().blockData().getAsString()).maxLength(256).build());
            buttons.add(button(player, "editor.actions.replace", NARROW, view -> {
                final String typed = view.getText("replace");
                if (typed != null && !typed.isBlank()) SelectionActions.replaceVisual(session, typed);
            }));
        }
        if (player.hasPermission(SculptPermissions.CONVERT)) {
            final List<SingleOptionDialogInput.OptionEntry> fills = new ArrayList<>();
            for (final FillMode fill : FillMode.values()) {
                fills.add(SingleOptionDialogInput.OptionEntry.create(fill.id(),
                    text(player, "editor.fill." + fill.id()), fill == FillMode.SHULKER));
            }
            inputs.add(DialogInput.singleOption("fill", text(player, "editor.actions.convert_input"), fills).build());
            buttons.add(button(player, "editor.actions.convert", NARROW, view -> {
                final FillMode fill = FillMode.parse(view.getText("fill"), null);
                if (fill != null) SelectionActions.convert(session, fill);
            }));
        }
        if (player.hasPermission(SculptPermissions.RELIGHT)) {
            buttons.add(button(player, "editor.actions.relight", NARROW, view -> SelectionActions.relight(session)));
        }
        if (player.hasPermission(SculptPermissions.BLUEPRINT_SAVE)) {
            inputs.add(DialogInput.text("name", text(player, "editor.actions.blueprint_name")).maxLength(64).build());
            inputs.add(DialogInput.bool("public", text(player, "editor.actions.blueprint_public")).build());
            buttons.add(button(player, "editor.actions.save", NARROW, view -> {
                final String name = view.getText("name");
                if (name == null || name.isBlank()) {
                    MessageUtil.sendTranslated(player, "command.sculpt.blueprint.save.invalid_name");
                    return;
                }
                SelectionActions.saveBlueprint(session, name.trim(), Boolean.TRUE.equals(view.getBoolean("public")));
            }));
        }
        buttons.add(button(player, "editor.actions.clear", NARROW, view -> {
            session.setSelection(null);
            session.flash("editor.select.cleared");
        }));
        show(player, "editor.actions.title", List.of(), inputs, buttons, 2);
    }

    // =====================================================================
    //  Blueprints
    // =====================================================================

    public static void blueprints(final EditorSession session, final BlueprintTool tool) {
        final Player player = session.player();
        final BlueprintManager manager = session.plugin().getBlueprintManager();
        final List<ActionButton> buttons = new ArrayList<>();
        try {
            addBlueprints(buttons, session, tool, manager.listAllBlueprints(player.getUniqueId(), false), false);
            addBlueprints(buttons, session, tool, manager.listAllBlueprints(player.getUniqueId(), true), true);
        } catch (final IOException failure) {
            MessageUtil.sendTranslated(player, "command.sculpt.blueprint.list.read_error");
            return;
        }
        if (buttons.isEmpty()) {
            MessageUtil.sendTranslated(player, "editor.blueprint.none");
            return;
        }
        show(player, "editor.blueprint.title", List.of(body(player, "editor.blueprint.body")),
            List.of(), buttons, 2);
    }

    private static void addBlueprints(
            final List<ActionButton> buttons,
            final EditorSession session,
            final BlueprintTool tool,
            final List<BlueprintManager.BlueprintSummary> summaries,
            final boolean isPublic) {
        for (final BlueprintManager.BlueprintSummary summary : summaries) {
            if (buttons.size() >= 40) return;
            buttons.add(ActionButton.builder(Component.text((isPublic ? "☁ " : "") + summary.name()))
                .width(NARROW)
                .action(action(session.player(), view -> tool.choose(session, summary.blueprintId(), isPublic)))
                .build());
        }
    }

    // =====================================================================
    //  Helpers
    // =====================================================================

    private static void show(
            final Player player,
            final String titleKey,
            final List<? extends DialogBody> body,
            final List<? extends DialogInput> inputs,
            final List<ActionButton> buttons,
            final int columns) {
        final DialogBase base = DialogBase.builder(text(player, titleKey))
            .canCloseWithEscape(true)
            .pause(false)
            .afterAction(DialogBase.DialogAfterAction.CLOSE)
            .body(body)
            .inputs(inputs)
            .build();
        final ActionButton close = button(player, "editor.dialog.close", NARROW, view -> { });
        final Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(base)
            .type(buttons.isEmpty()
                ? DialogType.notice(close)
                : DialogType.multiAction(buttons).columns(columns).exitAction(close).build()));
        player.showDialog(dialog);
    }

    private static ActionButton button(
            final Player player,
            final String key,
            final int width,
            final Consumer<DialogResponseView> onClick) {
        return ActionButton.builder(text(player, key)).width(width).action(action(player, onClick)).build();
    }

    private static DialogAction action(final Player player, final Consumer<DialogResponseView> onClick) {
        return DialogAction.customClick((view, audience) -> FoliaScheduler.runEntityTask(
            Sculpt.getPlugin(Sculpt.class), player, () -> {
                if (player.isOnline()) onClick.accept(view);
            }), ONCE);
    }

    private static DialogBody body(final Player player, final String key, final Object... arguments) {
        return DialogBody.plainMessage(text(player, key, arguments), 300);
    }

    private static Component text(final Player player, final String key, final Object... arguments) {
        return parse(MessageUtil.getTranslated(player, key, arguments));
    }

    private static Component parse(final String miniMessage) {
        return MiniMessage.miniMessage().deserialize(miniMessage);
    }

    private static float orDefault(final Float value, final int fallback) {
        return value == null ? fallback : value;
    }

    private static String normalize(final String input) {
        final String normalized = input.trim().toLowerCase(Locale.ROOT);
        final int stateStart = normalized.indexOf('[');
        final String id = stateStart < 0 ? normalized : normalized.substring(0, stateStart);
        return id.contains(":") ? normalized : "minecraft:" + normalized;
    }
}
