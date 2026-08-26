package dev.twme.sculpt.core;

import java.util.Objects;

import javax.annotation.Nullable;

import org.bukkit.block.data.BlockData;

/** Block data plus an optional, indivisible player-head texture. */
public record CellMaterial(
        BlockData blockData,
        @Nullable PlayerHeadTexture playerHeadTexture) {

    public CellMaterial {
        Objects.requireNonNull(blockData, "blockData");
    }

    public static CellMaterial block(final BlockData blockData) {
        return new CellMaterial(blockData, null);
    }

    public boolean isTexturedPlayerHead() {
        return playerHeadTexture != null;
    }

    public void applyTo(final OctreeNode node) {
        node.setBlockData(blockData);
        node.setPlayerHeadTexture(playerHeadTexture);
    }
}
