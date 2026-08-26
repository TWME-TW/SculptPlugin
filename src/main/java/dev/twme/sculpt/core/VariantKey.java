package dev.twme.sculpt.core;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Translates a blockstate key string (e.g. {@code "axis=y"},
 * {@code "facing=west,lit=false"}) into the canonical vanilla blockstate-JSON
 * variant key format so {@code HeadsRegistry.rotationFor(BlockKey, String)}
 * can pick the right world-space rotation at spawn time.
 *
 * <p>Vanilla blockstate variant keys list each property as
 * {@code <name>=<value>}, alphabetically sorted, comma-separated, with no
 * spaces. Bukkit's {@code BlockData.getAsString()} produces a similar format
 * but (a) is prefixed with the block id, (b) wrapped in square brackets, and
 * (c) includes <em>all</em> properties — most of which (like
 * {@code waterlogged}) don't actually change which variant is rendered.
 *
 * <p><b>Why this class is Bukkit-free</b> ({@code DEVELOPMENT_PLAN.md} §3.1
 * prohibits Bukkit imports in {@code core.*}): conversion of a
 * {@code org.bukkit.block.data.BlockData} into the {@code k=v,k=v} string
 * belongs to the {@code assemble} or {@code plugin} layer. The resulting
 * string is then fed into {@link #pickMatching} here, which is pure data
 * manipulation and trivially unit-testable.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Try the full property set first.</li>
 *   <li>If no match, try removing nuisance properties one at a time
 *       (preferring known "irrelevant" ones like {@code waterlogged}).</li>
 *   <li>If still no match, drop remaining properties one at a time.</li>
 * </ol>
 * Returns the matched key, or {@code ""} if nothing fits — caller should treat
 * that as identity rotation.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.core.VariantKey})
 * with the {@code fromBlockData(BlockData)} method intentionally removed.
 * See {@code DEVELOPMENT_PLAN.md} §4.5.
 */
public final class VariantKey {

    /**
     * Properties that vanilla blockstates almost never branch on. Tried
     * first when narrowing a state string against a variant map.
     */
    private static final Set<String> NUISANCE_PROPERTIES = Set.of(
            "waterlogged", "powered", "triggered", "occupied", "open",
            "in_wall", "snowy", "stage", "level", "leaves", "distance",
            "persistent", "age");

    private VariantKey() {}

    /**
     * Find the variant key in {@code candidates} that best matches the
     * full property set. Tries the full key first, then progressively drops
     * nuisance properties, then drops any remaining property until a match
     * is found. Returns {@code ""} if no match — caller should treat as
     * identity rotation.
     */
    public static String pickMatching(String fullKey, Set<String> candidates) {
        if (fullKey == null || candidates.isEmpty()) return "";
        if (candidates.contains(fullKey)) return fullKey;
        TreeMap<String, String> kv = parseKey(fullKey);

        // Drop nuisance properties first.
        for (String nuisance : NUISANCE_PROPERTIES) {
            if (kv.remove(nuisance) != null) {
                String trimmed = serialize(kv);
                if (candidates.contains(trimmed)) return trimmed;
            }
        }
        // Drop remaining properties one at a time until a match.
        // Prefer to drop later (alphabetically) properties first since
        // earlier ones (axis, facing) are usually the variant pivot.
        java.util.List<String> keys = new java.util.ArrayList<>(kv.keySet());
        java.util.Collections.reverse(keys);
        for (String k : keys) {
            kv.remove(k);
            String trimmed = serialize(kv);
            if (candidates.contains(trimmed)) return trimmed;
        }
        return "";
    }

    /**
     * Parse a {@code "k=v,k=v"} key into an alphabetically-sorted map.
     * Empty input → empty map (used for blocks with no properties).
     */
    public static TreeMap<String, String> parseKey(String key) {
        TreeMap<String, String> out = new TreeMap<>();
        if (key == null || key.isEmpty()) return out;
        for (String part : key.split(",")) {
            int eq = part.indexOf('=');
            if (eq >= 0) out.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return out;
    }

    /**
     * Serialize an alphabetically-sorted map back to the canonical
     * {@code "k=v,k=v"} form.
     */
    public static String serialize(TreeMap<String, String> kv) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : kv.entrySet()) {
            if (!sb.isEmpty()) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
