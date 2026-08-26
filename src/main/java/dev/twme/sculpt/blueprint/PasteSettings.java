package dev.twme.sculpt.blueprint;

import javax.annotation.Nullable;

/**
 * PasteSettings — 藍圖貼上的行為設定。
 * <p>
 * 控制貼上時的空洞處理、覆蓋策略、融合模式與旋轉。
 * 設定優先順序：Per-Item (PDC) > Per-Blueprint > Per-Player > Config 預設值。
 *
 * @param pasteAir        是否貼上藍圖中的空洞（removed cell）。預設 true
 * @param overwriteCells  是否覆蓋既有 SculptBlock 中 non-removed 的 cell。預設 true
 * @param overwriteBlocks 是否覆蓋一般方塊（非 SculptBlock 的實心方塊）。預設 true
 * @param adhesive        是否使用融合貼上（與既有 SculptBlock 合併而非取代）。預設 false
 * @param rotateMode      旋轉模式。預設 AUTO
 * @param ry              Y 軸旋轉補償（0 / 90 / 180 / 270）。預設 0
 * @param flipAxis        鏡像翻轉軸（null / "x" / "y" / "z"）。預設 null
 */
public record PasteSettings(
    boolean pasteAir,
    boolean overwriteCells,
    boolean overwriteBlocks,
    boolean adhesive,
    @Nullable RotateMode rotateMode,
    int ry,
    @Nullable String flipAxis
) {

    /** 預設值：依來源與目前玩家位置自動套用相對旋轉。 */
    public static final PasteSettings DEFAULTS = new PasteSettings(
        true, true, true, false, RotateMode.AUTO, 0, null
    );

    public enum RotateMode {
        NONE,
        FACE,
        PLAYER,
        AUTO
    }

    // ====================== 輔助建構 ======================

    /** 從三個基礎布林值建立（不含旋轉）。 */
    public static PasteSettings of(boolean pasteAir, boolean overwriteCells,
                                    boolean overwriteBlocks) {
        return new PasteSettings(pasteAir, overwriteCells, overwriteBlocks,
            false, RotateMode.NONE, 0, null);
    }

    /**
     * 從指令參數建構 PasteSettings。
     * 若參數為 null 則使用預設值。
     */
    public static PasteSettings fromCommand(
        @Nullable Boolean pasteAir,
        @Nullable Boolean overwrite,
        @Nullable Boolean adhesive
    ) {
        return fromCommand(pasteAir, overwrite, adhesive, null, null, null);
    }

    /**
     * 完整的指令參數建構（含旋轉）。
     */
    public static PasteSettings fromCommand(
        @Nullable Boolean pasteAir,
        @Nullable Boolean overwrite,
        @Nullable Boolean adhesive,
        @Nullable String rotateMode,
        @Nullable Integer ry,
        @Nullable String flipAxis
    ) {
        boolean pa = (pasteAir != null) ? pasteAir : DEFAULTS.pasteAir();
        boolean oc = (overwrite != null) ? overwrite : DEFAULTS.overwriteCells();
        boolean ob = (overwrite != null) ? overwrite : DEFAULTS.overwriteBlocks();
        boolean ad = (adhesive != null) ? adhesive : DEFAULTS.adhesive();
        RotateMode rm = DEFAULTS.rotateMode();
        if (rotateMode != null) {
            try {
                rm = RotateMode.valueOf(rotateMode.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        int r = (ry != null) ? ry : DEFAULTS.ry();
        String fa = (flipAxis != null) ? flipAxis : DEFAULTS.flipAxis();
        return new PasteSettings(pa, oc, ob, ad, rm, r, fa);
    }
}
