package dev.twme.sculpt.editor.tool;

import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;
import dev.twme.sculpt.editor.preview.Colors;
import dev.twme.sculpt.editor.ui.EditorDialogs;

/** Left click opens the material palette, right click the editor settings. */
final class SettingsTool implements Tool {

    @Override
    public ToolId id() {
        return ToolId.SETTINGS;
    }

    @Override
    public void primary(final EditorSession session) {
        EditorDialogs.palette(session);
    }

    @Override
    public void secondary(final EditorSession session) {
        EditorDialogs.settings(session);
    }

    @Override
    public void openSettings(final EditorSession session) {
        EditorDialogs.settings(session);
    }

    @Override
    public void preview(final EditorSession session) {
        Previews.cell(session, "settings.cursor", false, Colors.CURSOR);
    }
}
