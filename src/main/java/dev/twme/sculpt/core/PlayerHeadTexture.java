package dev.twme.sculpt.core;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * The texture property carried by a player-head item.
 *
 * <p>Only the profile's {@code textures} value and optional signature are
 * retained. A player name or UUID is intentionally not stored: rendering a
 * cell must never trigger an account/profile lookup after placement.
 */
public record PlayerHeadTexture(String value, String signature) {

    public static final int MAX_VALUE_BYTES = 32_767;
    public static final int MAX_SIGNATURE_BYTES = 4_096;
    public static final int MAX_SIGNATURE_CHARS = 1_024;
    public PlayerHeadTexture {
        value = Objects.requireNonNull(value, "value");
        signature = signature == null ? "" : signature;
        if (value.isBlank()) {
            throw new IllegalArgumentException("Player-head texture value is empty");
        }
        final int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
        final int signatureBytes = signature.getBytes(StandardCharsets.UTF_8).length;
        if (valueBytes > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("Player-head texture value is too large");
        }
        if (signature.length() > MAX_SIGNATURE_CHARS
                || signatureBytes > MAX_SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Player-head texture signature is too large");
        }
    }

    public boolean isSigned() {
        return !signature.isEmpty();
    }
}
