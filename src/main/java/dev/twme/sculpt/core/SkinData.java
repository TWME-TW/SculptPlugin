package dev.twme.sculpt.core;

import java.util.UUID;

/**
 * 一條 MineSkin 記錄：texture value + signature + mineskin uuid。
 */
public record SkinData(String value, String signature, UUID mineskinUuid) {}
