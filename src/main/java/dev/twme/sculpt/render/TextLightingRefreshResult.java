package dev.twme.sculpt.render;

/** Result of clearing brightness overrides from one TextDisplay render. */
public record TextLightingRefreshResult(
    int displaysChecked,
    int displaysUpdated
) {

    public static final TextLightingRefreshResult EMPTY =
        new TextLightingRefreshResult(0, 0);

    public TextLightingRefreshResult {
        if (displaysChecked < 0 || displaysUpdated < 0
                || displaysUpdated > displaysChecked) {
            throw new IllegalArgumentException(
                "lighting refresh counts must satisfy 0 <= updated <= checked");
        }
    }
}
