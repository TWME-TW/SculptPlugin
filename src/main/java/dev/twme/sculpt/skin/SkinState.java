package dev.twme.sculpt.skin;

/**
 * Lifecycle of a {@link HeadSkin} during MineSkin upload.
 *
 * <p>Transitions: {@code PENDING → SUBMITTED → COMPLETED} on success;
 * {@code PENDING → SUBMITTED → ERRORED} on MineSkin rejection. On plugin
 * restart, {@code SUBMITTED} entries are reset back to {@code PENDING}
 * (cheaper than chasing job IDs across the restart).
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.skin.SkinState}).
 */
public enum SkinState {
    /** Skin was packed locally; no MineSkin job created yet. */
    PENDING,
    /** Job submitted to MineSkin; {@code jobId} is set; awaiting completion. */
    SUBMITTED,
    /** MineSkin returned a texture value/signature; {@link HeadSkin#textureValue} / {@link HeadSkin#textureSignature} populated. */
    COMPLETED,
    /** MineSkin rejected the upload, or local I/O failed. Eligible for retry. */
    ERRORED
}
