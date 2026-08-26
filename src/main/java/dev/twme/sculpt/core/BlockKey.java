package dev.twme.sculpt.core;

import java.util.Locale;
import java.util.Objects;

/**
 * Namespaced block identifier — e.g. {@code minecraft:stone}. Used as the
 * stable lookup key for asset resolution, head packing, and the heads registry.
 * Insulates downstream code from {@code org.bukkit.Material} so the
 * splitting/skin pipeline can run in the bake task (which has no Bukkit
 * runtime).
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.core.BlockKey}).
 * See {@code DEVELOPMENT_PLAN.md} §4.1.
 */
public record BlockKey(String namespace, String path) {

    public BlockKey {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (namespace.isEmpty() || path.isEmpty()) {
            throw new IllegalArgumentException("namespace and path must be non-empty");
        }
    }

    /**
     * Parse a {@code "namespace:path"} string. If no namespace is present,
     * defaults to {@code "minecraft"}. The input is lower-cased so the
     * resulting key is stable regardless of caller formatting.
     * <p>Blockstate properties ({@code [snowy=false]}) are stripped so the
     * path is always a clean identifier usable in file paths / URLs.
     */
    public static BlockKey of(String namespaced) {
        String s = namespaced.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        String path;
        if (colon < 0) {
            path = s;
        } else {
            path = s.substring(colon + 1);
        }
        int bracket = path.indexOf('[');
        if (bracket >= 0) path = path.substring(0, bracket);
        String ns = (colon < 0) ? "minecraft" : s.substring(0, colon);
        return new BlockKey(ns, path);
    }

    /**
     * Extract a BlockKey from a Bukkit {@link BlockData} string.
     * E.g. {@code "minecraft:oak_log[axis=y]"} → {@code minecraft:oak_log}.
     */
    public static BlockKey from(org.bukkit.block.data.BlockData data) {
        return of(data.getAsString());
    }

    /** Canonical {@code "namespace:path"} representation. */
    public String asString() {
        return namespace + ":" + path;
    }

    @Override
    public String toString() {
        return asString();
    }
}
