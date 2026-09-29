package dev.twme.sculpt.building;

import org.bukkit.entity.Player;

import dev.twme.sculpt.util.MessageUtil;

/** Chat and action-bar wording for {@link EditReport}s. */
public final class ReportMessages {

    private ReportMessages() {
    }

    /** Explain skipped positions, one line per reason. */
    public static void sendSkipped(final Player player, final EditReport report) {
        send(player, "building.result.protected", report.protectedBlocks);
        send(player, "building.result.obstructed", report.obstructed);
        send(player, "building.result.locked", report.locked);
        send(player, "building.result.limit_reached", report.limitReached);
        send(player, "building.result.stale", report.stale);
        send(player, "building.result.failed", report.failed);
        if (!report.historyRecorded) {
            MessageUtil.sendTranslated(player, "building.result.not_recorded");
        }
    }

    /** Result of {@code /sculpt undo} or {@code /sculpt redo}. */
    public static void sendRevert(final Player player, final boolean redo, final EditReport report) {
        final String prefix = redo ? "building.redo." : "building.undo.";
        if (report.entries == 0) {
            MessageUtil.sendTranslated(player, prefix + "nothing");
            return;
        }
        MessageUtil.sendTranslated(player, prefix + "completed", report.entries, report.changed);
        sendSkipped(player, report);
    }

    private static void send(final Player player, final String key, final int count) {
        if (count > 0) MessageUtil.sendTranslated(player, key, count);
    }
}
