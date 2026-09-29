package dev.twme.sculpt.editor.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

import dev.twme.sculpt.blueprint.BlueprintData;
import dev.twme.sculpt.blueprint.BlueprintManager;
import dev.twme.sculpt.blueprint.PasteSettings;
import dev.twme.sculpt.building.BlockPos;
import dev.twme.sculpt.editor.CellTarget;
import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.ui.EditorDialogs;
import dev.twme.sculpt.plugin.SculptPermissions;
import dev.twme.sculpt.util.MessageUtil;

/**
 * Place blueprints with a preview. Left click chooses a blueprint; right
 * click places a ghost of its footprint in front of the targeted face, and a
 * second right click at the same spot pastes it. Pastes are undoable.
 */
public final class BlueprintTool implements Tool {

    private static final String PREFIX = "blueprint.";

    private BlueprintData chosen;
    private Location pending;
    private BlockFace pendingFace;

    @Override
    public ToolId id() {
        return ToolId.BLUEPRINT;
    }

    public BlueprintData chosen() {
        return chosen;
    }

    /** Choose a blueprint by id from the player's private or public folder. */
    public void choose(final EditorSession session, final UUID blueprintId, final boolean isPublic) {
        final BlueprintManager manager = manager(session);
        if (manager == null) return;
        try {
            final BlueprintData data = manager.io().readBlueprint(
                session.player().getUniqueId(), blueprintId, isPublic);
            if (data == null) {
                MessageUtil.sendTranslated(session.player(),
                    "command.sculpt.blueprint.paste.not_found", blueprintId.toString());
                return;
            }
            chosen = data;
            pending = null;
            session.flash("editor.blueprint.chosen", data.name());
        } catch (final IOException failure) {
            MessageUtil.sendTranslated(session.player(),
                "command.sculpt.blueprint.paste.not_found", blueprintId.toString());
        }
    }

    @Override
    public void primary(final EditorSession session) {
        if (manager(session) != null) EditorDialogs.blueprints(session, this);
    }

    @Override
    public void secondary(final EditorSession session) {
        if (chosen == null) {
            primary(session);
            return;
        }
        final CellTarget target = session.target();
        if (target == null) return;
        final Block front = front(target);
        final Location anchor = front.getLocation().add(0.5, 0.5, 0.5);
        final BlockFace face = face(target);
        if (pending != null && pending.getBlock().equals(front)) {
            paste(session, anchor, face);
            return;
        }
        pending = anchor;
        pendingFace = face;
        session.flash("editor.blueprint.confirm");
    }

    private void paste(final EditorSession session, final Location anchor, final BlockFace face) {
        final Player player = session.player();
        if (!player.hasPermission(SculptPermissions.editorTool(id().id()))) return;
        final BlueprintManager manager = manager(session);
        if (manager == null || !session.begin()) return;
        try {
            final PasteSettings settings = manager.getPlayerSettings(player.getUniqueId());
            final int[] bounds = manager.pasteEngine().previewBounds(player, chosen, anchor, settings, face);
            final List<Block> blocks = new ArrayList<>();
            for (int x = 0; x < bounds[3]; x++) {
                for (int y = 0; y < bounds[4]; y++) {
                    for (int z = 0; z < bounds[5]; z++) {
                        blocks.add(player.getWorld().getBlockAt(bounds[0] + x, bounds[1] + y, bounds[2] + z));
                    }
                }
            }
            final String[] error = new String[1];
            final List<BlockPos> changed = session.service().engine().recordChange(player,
                "blueprint", blocks, () -> error[0] = manager.pasteBlueprint(player, chosen, anchor, settings, face));
            if (error[0] != null) {
                MessageUtil.sendTranslated(player, error[0]);
                return;
            }
            pending = null;
            session.pulseBlocks(changed, Colors.ADD);
            MessageUtil.sendTranslated(player, "command.sculpt.blueprint.paste.success", chosen.name());
        } finally {
            session.service().engine().release(player);
        }
    }

    @Override
    public boolean cancel(final EditorSession session) {
        if (pending != null) {
            pending = null;
            return true;
        }
        if (chosen != null) {
            chosen = null;
            session.flash("editor.blueprint.cleared");
            return true;
        }
        return false;
    }

    @Override
    public void openSettings(final EditorSession session) {
        session.player().performCommand("sculpt blueprint settings");
    }

    @Override
    public void preview(final EditorSession session) {
        final BlueprintManager manager = manager(session);
        final CellTarget target = session.target();
        if (chosen == null || manager == null || (pending == null && target == null)) {
            session.scene().removePrefix(PREFIX);
            return;
        }
        final Location anchor = pending != null ? pending : front(target).getLocation().add(0.5, 0.5, 0.5);
        final BlockFace face = pending != null ? pendingFace : face(target);
        final int[] bounds = manager.pasteEngine().previewBounds(session.player(), chosen, anchor,
            manager.getPlayerSettings(session.player().getUniqueId()), face);
        final Vector3f min = new Vector3f(bounds[0], bounds[1], bounds[2]);
        final Vector3f max = new Vector3f(bounds[0] + bounds[3], bounds[1] + bounds[4], bounds[2] + bounds[5]);
        final int color = pending != null ? Colors.ADD : Colors.CURSOR;
        session.scene().outline(PREFIX + "ghost", min, max, color);
        session.scene().faces(PREFIX + "ghost.faces", min, max, Colors.withAlpha(color, 0x28));
    }

    @Override
    public String status(final EditorSession session) {
        return chosen == null ? MessageUtil.getTranslated(session.player(), "editor.status.no_blueprint")
            : chosen.name();
    }

    private static Block front(final CellTarget target) {
        return target.block().getRelative(target.faceX(), target.faceY(), target.faceZ());
    }

    private static BlockFace face(final CellTarget target) {
        for (final BlockFace face : BlockFace.values()) {
            if (face.getModX() == target.faceX() && face.getModY() == target.faceY()
                    && face.getModZ() == target.faceZ() && face.isCartesian()) {
                return face;
            }
        }
        return BlockFace.UP;
    }

    private static BlueprintManager manager(final EditorSession session) {
        final BlueprintManager manager = session.plugin().getBlueprintManager();
        if (manager == null || !manager.isEnabled()) {
            session.flash("editor.blueprint.disabled");
            return null;
        }
        return manager;
    }
}
