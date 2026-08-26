package dev.twme.sculpt.core;

import java.util.Locale;

/** Visual representation used for normal SculptBlock cells. */
public enum SculptDisplayMode {
    HEAD,
    TEXT_DISPLAY,
    AUTO;

    public String id() {
        return switch (this) {
            case HEAD -> "head";
            case TEXT_DISPLAY -> "textdisplay";
            case AUTO -> "auto";
        };
    }

    /** Whether this mode may own derived TextDisplay pixel entities. */
    public boolean usesTextRenderer() {
        return this == TEXT_DISPLAY || this == AUTO;
    }

    public static SculptDisplayMode parse(
            final String value,
            final SculptDisplayMode fallback) {
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "head" -> HEAD;
            case "textdisplay", "text_display", "text-display" -> TEXT_DISPLAY;
            case "auto", "automatic" -> AUTO;
            default -> fallback;
        };
    }
}
