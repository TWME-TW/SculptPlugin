package dev.twme.sculpt.core;

import java.util.Locale;

/** Physical representation used behind one SculptBlock. */
public enum FillMode {
    BARRIER,
    SHULKER,
    NONE;

    public String id() {
        return this == NONE ? "null" : name().toLowerCase(Locale.ROOT);
    }

    public static FillMode parse(final String value, final FillMode fallback) {
        if (value == null) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "barrier" -> BARRIER;
            case "shulker" -> SHULKER;
            case "null", "none" -> NONE;
            default -> fallback;
        };
    }
}
