package dev.twme.sculpt.editor.tool;

import dev.twme.sculpt.editor.EditorSession;
import dev.twme.sculpt.editor.ToolId;

/**
 * One editor tool. Every input keeps the same meaning across tools:
 * left click is the primary action, right click the secondary one,
 * {@code Shift}+scroll adjusts the tool's size, {@code Q} cancels, and
 * {@code Shift}+right click opens the tool's settings.
 *
 * <p>All methods run on the player's thread.</p>
 */
public interface Tool {

    ToolId id();

    /** The tool was selected. */
    default void activate(final EditorSession session) {
    }

    /** Another tool was selected or the editor closed; remove tool previews. */
    default void deactivate(final EditorSession session) {
        session.scene().removePrefix(id().id() + ".");
    }

    /** Left click. */
    default void primary(final EditorSession session) {
    }

    /** Right click. */
    default void secondary(final EditorSession session) {
    }

    /** {@code Shift}+scroll: grow ({@code delta > 0}) or shrink the tool. */
    default void adjust(final EditorSession session, final int delta) {
    }

    /** {@code Q}: cancel the tool's pending state. Returns whether anything was cancelled. */
    default boolean cancel(final EditorSession session) {
        return false;
    }

    /** {@code Shift}+right click. */
    default void openSettings(final EditorSession session) {
    }

    /** Update this tool's previews after the cursor was traced; runs every tick. */
    void preview(EditorSession session);

    /** Short status for the action bar, such as the brush radius; may be empty. */
    default String status(final EditorSession session) {
        return "";
    }
}
