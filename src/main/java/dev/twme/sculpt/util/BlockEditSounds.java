package dev.twme.sculpt.util;

import java.util.function.BiPredicate;

import javax.annotation.Nullable;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.SoundGroup;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** Plays material-aware block feedback for Sculpt editing operations. */
public final class BlockEditSounds {

    private static final float PITCH_SCALE = 0.8f;

    private BlockEditSounds() {
    }

    /** Collects a multi-block edit into one representative sound. */
    public static Batch batch() {
        return new Batch(BlockEditSounds::playPlace, BlockEditSounds::playBreak);
    }

    public static boolean playPlace(
            final Location location,
            @Nullable final BlockData blockData) {
        return play(location, blockData, true);
    }

    public static boolean playBreak(
            final Location location,
            @Nullable final BlockData blockData) {
        return play(location, blockData, false);
    }

    private static boolean play(
            final Location location,
            @Nullable final BlockData blockData,
            final boolean placing) {
        if (location == null || blockData == null) return false;
        final World world = location.getWorld();
        if (world == null) return false;

        final SoundGroup group = blockData.getSoundGroup();
        if (group == null) return false;
        final Sound sound = placing ? group.getPlaceSound() : group.getBreakSound();
        if (sound == null) return false;

        world.playSound(
            location, sound, SoundCategory.BLOCKS,
            (group.getVolume() + 1.0f) / 2.0f,
            group.getPitch() * PITCH_SCALE);
        return true;
    }

    /**
     * Keeps only the first placement and first removal in an operation. A
     * placement takes precedence when both happen, so a cuboid paste emits one
     * concise placement sound instead of one sound per affected block.
     */
    public static final class Batch {
        private final BiPredicate<Location, BlockData> placePlayer;
        private final BiPredicate<Location, BlockData> breakPlayer;
        private Cue placement;
        private Cue breaking;

        Batch(
                final BiPredicate<Location, BlockData> placePlayer,
                final BiPredicate<Location, BlockData> breakPlayer) {
            this.placePlayer = placePlayer;
            this.breakPlayer = breakPlayer;
        }

        public void recordPlace(
                @Nullable final Location location,
                @Nullable final BlockData blockData) {
            if (placement == null && location != null && blockData != null) {
                placement = new Cue(location, blockData);
            }
        }

        public void recordBreak(
                @Nullable final Location location,
                @Nullable final BlockData blockData) {
            if (breaking == null && location != null && blockData != null) {
                breaking = new Cue(location, blockData);
            }
        }

        public boolean play() {
            if (placement != null) {
                return placePlayer.test(placement.location(), placement.blockData());
            }
            return breaking != null
                && breakPlayer.test(breaking.location(), breaking.blockData());
        }
    }

    private record Cue(Location location, BlockData blockData) {
    }
}
