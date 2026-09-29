package dev.twme.sculpt.editor.tool;

import java.util.List;

/** Creates one set of tools for a new editor session. */
public final class Tools {

    private Tools() {
    }

    public static List<Tool> create() {
        return List.of(
            new SculptTool(),
            new BrushTool(),
            new SmoothTool(),
            new PaintTool(),
            new SelectTool(),
            new TransformTool(),
            new ShapeTool(),
            new BlueprintTool(),
            new SettingsTool());
    }
}
