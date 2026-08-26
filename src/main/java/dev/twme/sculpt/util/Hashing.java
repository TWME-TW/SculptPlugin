package dev.twme.sculpt.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * SHA-256 helpers used for content-addressed lookups:
 * <ul>
 *   <li>{@link #sha256OfStrings(List)} — dedup the 6-tile outward-face bundle
 *       of a chunk into a single skin upload key.</li>
 *   <li>{@link #sha256OfImage(BufferedImage)} — dedup the post-paint 64×64 PNG
 *       regardless of which {@link dev.twme.sculpt.core.BakeKey} produced it.</li>
 *   <li>{@link #sha256OfBytes(byte[])} — generic fallback for ad-hoc payloads.</li>
 * </ul>
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.util.Hashing}).
 */
public final class Hashing {

    private Hashing() {}

    /**
     * Hash a list of strings with {@code "|"} as the separator. Order matters —
     * callers should sort the parts deterministically before passing in.
     */
    public static String sha256OfStrings(List<String> parts) {
        MessageDigest md = sha256();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) md.update((byte) '|');
            md.update(parts.get(i).getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** Hash a {@link BufferedImage} by encoding it as PNG and digesting the bytes. */
    public static String sha256OfImage(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "PNG", baos);
            return HexFormat.of().formatHex(sha256().digest(baos.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to hash image", e);
        }
    }

    public static String sha256OfBytes(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 should always be available", e);
        }
    }
}
