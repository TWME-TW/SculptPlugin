package dev.twme.sculpt.integration;

import java.util.Map;
import java.util.Set;

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.entity.EntityType;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SculptPasteExtentTest {

    private static final TestBlock BARRIER = new TestBlock("minecraft:barrier");
    private static final TestBlock STONE = new TestBlock("minecraft:stone");

    @Test
    void tracksStandardWorldEditSetBlock() throws WorldEditException {
        SculptPasteExtent tracker = new SculptPasteExtent(new NullExtent());
        BlockVector3 position = BlockVector3.at(3, 64, 7);

        tracker.setBlock(position, BARRIER);

        assertTrue(tracker.isDirty());
        assertEquals(Set.of(position), tracker.getTrackedPositions());
        assertTrue(tracker.isDirty());
    }

    @Test
    void tracksFaweIntegerSetBlockOverload() throws WorldEditException {
        SculptPasteExtent tracker = new SculptPasteExtent(new NullExtent());

        tracker.setBlock(4, 65, 8, BARRIER);

        assertEquals(Set.of(BlockVector3.at(4, 65, 8)), tracker.getTrackedPositions());
    }

    @Test
    void ignoresNonBarrierBlocks() throws WorldEditException {
        SculptPasteExtent tracker = new SculptPasteExtent(new NullExtent());

        tracker.setBlock(BlockVector3.ZERO, STONE);

        assertFalse(tracker.isDirty());
    }

    @Test
    void tracksSuccessfullyCreatedSculptRootEntity() {
        SculptPasteExtent tracker = new SculptPasteExtent(new EntityCreatingExtent());
        Location location = new Location(tracker, 3.5, 64.5, 7.5);

        tracker.createEntity(location, sculptRoot());

        assertEquals(Set.of(BlockVector3.at(3, 64, 7)), tracker.getTrackedPositions());
    }

    @Test
    void ignoresCreatedNonSculptEntity() {
        SculptPasteExtent tracker = new SculptPasteExtent(new EntityCreatingExtent());
        BaseEntity state = new BaseEntity(new EntityType("minecraft:item_display"),
                LazyReference.computed(LinCompoundTag.builder().build()));

        tracker.createEntity(new Location(tracker, 3.5, 64.5, 7.5), state);

        assertFalse(tracker.isDirty());
    }

    @Test
    void ignoresSculptRootWhenEntityCreationFails() {
        SculptPasteExtent tracker = new SculptPasteExtent(new NullExtent());

        tracker.createEntity(new Location(tracker, 3.5, 64.5, 7.5), sculptRoot());

        assertFalse(tracker.isDirty());
    }

    private static BaseEntity sculptRoot() {
        LinCompoundTag nbt = LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "root")
                        .putString("sculpt:original_block", "minecraft:stone")
                        .build())
                .build();
        return new BaseEntity(new EntityType("minecraft:item_display"),
                LazyReference.computed(nbt));
    }

    public static final class EntityCreatingExtent extends NullExtent {
        @Override
        public Entity createEntity(Location location, BaseEntity state) {
            return new TestEntity(location, state);
        }
    }

    private record TestEntity(Location location, BaseEntity state) implements Entity {
        @Override
        public BaseEntity getState() {
            return state;
        }

        @Override
        public Location getLocation() {
            return location;
        }

        @Override
        public Extent getExtent() {
            return location.getExtent();
        }

        @Override
        public boolean setLocation(Location location) {
            return false;
        }

        @Override
        public boolean remove() {
            return false;
        }

        @Override
        public <T> T getFacet(Class<? extends T> type) {
            return null;
        }
    }

    private static final class TestBlock implements BlockStateHolder<TestBlock> {
        private final BlockType type;

        private TestBlock(String id) {
            this.type = new BlockType(id);
        }

        @Override
        public BlockType getBlockType() {
            return type;
        }

        @Override
        public <V> TestBlock with(Property<V> property, V value) {
            return this;
        }

        @Override
        public <V> V getState(Property<V> property) {
            return null;
        }

        @Override
        public Map<Property<?>, Object> getStates() {
            return Map.of();
        }

        @Override
        public boolean equalsFuzzy(BlockStateHolder<?> other) {
            return type.equals(other.getBlockType());
        }

        @Override
        public BlockState toImmutableState() {
            return null;
        }

        @Override
        public BaseBlock toBaseBlock() {
            return null;
        }
    }
}
