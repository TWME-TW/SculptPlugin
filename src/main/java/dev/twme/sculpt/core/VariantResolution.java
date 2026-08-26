package dev.twme.sculpt.core;

import org.joml.Quaternionf;

/**
 * BlockData 變體旋轉解析結果。
 * @param rotation       匹配到的旋轉（identity 若無匹配）
 * @param matchedVariant 匹配到的 variant key 字串
 */
public record VariantResolution(Quaternionf rotation, String matchedVariant) {}
