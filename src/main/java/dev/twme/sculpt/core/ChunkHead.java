package dev.twme.sculpt.core;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;

/**
 * 一個 chunk 的頭顱資訊：ItemStack（PLAYER_HEAD + 材質）、Transformation
 *（位置/旋轉/縮放），以及是否只是等待紋理時的 placeholder。
 */
public record ChunkHead(
        ItemStack head,
        Transformation transformation,
        boolean placeholder) {

    public ChunkHead(
            final ItemStack head,
            final Transformation transformation) {
        this(head, transformation, false);
    }
}
