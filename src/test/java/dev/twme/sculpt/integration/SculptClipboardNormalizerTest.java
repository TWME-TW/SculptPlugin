package dev.twme.sculpt.integration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.entity.EntityType;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinTagType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SculptClipboardNormalizerTest {

    private static final EntityType ITEM_DISPLAY =
            new EntityType("minecraft:item_display");
    private static final EntityType BLOCK_DISPLAY =
            new EntityType("minecraft:block_display");
    private static final EntityType SHULKER =
            new EntityType("minecraft:shulker");

    @Test
    void removesEntitiesFromOwningClipboardBehindCopiedView()
            throws SculptClipboardNormalizer.CleanupException {
        MutableClipboard parent = new MutableClipboard();
        parent.add(1.5, 64.5, 2.5, sculptRootNbt());
        parent.add(1.5, 64.5, 2.5, null);
        CopiedEntityView clipboard = new CopiedEntityView(parent);

        SculptClipboardNormalizer.Result result =
                SculptClipboardNormalizer.normalize(clipboard);

        assertEquals(1, result.removedEntities());
        assertEquals(0, result.remainingEntities());
        assertTrue(result.safe());
        assertEquals(1, parent.getEntities().size());
        assertEquals(1, clipboard.getEntities().size());
    }

    @Test
    void reportsUnsafeEntityWhenOwningClipboardCannotRemoveIt()
            throws SculptClipboardNormalizer.CleanupException {
        MutableClipboard clipboard = new MutableClipboard() {
            @Override
            public void removeEntity(Entity entity) {
            }
        };
        clipboard.add(1.5, 64.5, 2.5, sculptRootNbt());
        clipboard.add(1.5, 64.5, 2.5, null);

        SculptClipboardNormalizer.Result result =
                SculptClipboardNormalizer.normalize(clipboard);

        assertEquals(0, result.removedEntities());
        assertEquals(1, result.remainingEntities());
        assertFalse(result.safe());
    }

    @Test
    void ignoresUnrelatedNullNbtEntity()
            throws SculptClipboardNormalizer.CleanupException {
        MutableClipboard clipboard = new MutableClipboard();
        clipboard.add(1.5, 64.5, 2.5, null);

        SculptClipboardNormalizer.Result result =
                SculptClipboardNormalizer.normalize(clipboard);

        assertEquals(0, result.removedEntities());
        assertEquals(0, result.remainingEntities());
        assertTrue(result.safe());
        assertEquals(1, clipboard.getEntities().size());
    }

    @Test
    void removesSculptPassengerButPreservesUnrelatedNullNbtEntity()
            throws SculptClipboardNormalizer.CleanupException {
        MutableClipboard clipboard = new MutableClipboard();
        clipboard.add(1.5, 64.5, 2.5, sculptRootNbt());
        clipboard.add(1.5, 64.5, 2.5, null);
        clipboard.add(8.5, 70.5, 9.5, null);

        SculptClipboardNormalizer.Result result =
                SculptClipboardNormalizer.normalize(clipboard);

        assertEquals(1, result.removedEntities());
        assertEquals(0, result.remainingEntities());
        assertTrue(result.safe());
        assertEquals(2, clipboard.getEntities().size());
    }

    @Test
    void preservesAllSamePositionCandidatesWhenOwnershipIsAmbiguous()
            throws SculptClipboardNormalizer.CleanupException {
        MutableClipboard clipboard = new MutableClipboard();
        clipboard.add(1.5, 64.5, 2.5, sculptRootNbt());
        clipboard.add(1.5, 64.5, 2.5, null);
        clipboard.add(1.5, 64.5, 2.5, null);

        SculptClipboardNormalizer.Result result =
                SculptClipboardNormalizer.normalize(clipboard);

        assertEquals(0, result.removedEntities());
        assertEquals(0, result.remainingEntities());
        assertTrue(result.safe());
        assertEquals(3, clipboard.getEntities().size());
    }

    @Test
    void removesMixedPassengerSnapshotsButPreservesSerializedOwners()
            throws SculptClipboardNormalizer.CleanupException {
        MutableClipboard clipboard = new MutableClipboard();
        clipboard.add(ITEM_DISPLAY, 1.5, 64.5, 2.5, sculptRootNbt());
        clipboard.add(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);
        clipboard.add(BLOCK_DISPLAY, 3.25, 65.0, 4.25,
                sculptSeatNbt("2.5", "2.5"));
        clipboard.add(SHULKER, 3.25, 65.0, 4.25, null);

        SculptClipboardNormalizer.Result result =
                SculptClipboardNormalizer.normalize(clipboard);

        assertEquals(2, result.removedEntities());
        assertEquals(0, result.remainingEntities());
        assertTrue(result.safe());
        assertEquals(Set.of(ITEM_DISPLAY, BLOCK_DISPLAY), clipboard.getEntities().stream()
                .map(entity -> entity.getState().getType())
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static LinCompoundTag sculptRootNbt() {
        LinCompoundTag passenger = LinCompoundTag.builder()
                .putString("id", "minecraft:item_display")
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "leaf")
                        .putString("sculpt:path", "0")
                        .build())
                .build();
        return LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "root")
                        .putString("sculpt:original_block", "minecraft:stone")
                        .build())
                .put("Passengers", LinListTag.of(
                        LinTagType.compoundTag(), List.of(passenger)))
                .build();
    }

    private static LinCompoundTag sculptSeatNbt(String seatPath, String shulkerPath) {
        LinCompoundTag shulker = LinCompoundTag.builder()
                .putString("id", "minecraft:shulker")
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "shulker")
                        .putString("sculpt:path", shulkerPath)
                        .build())
                .build();
        return LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "shulker_seat")
                        .putString("sculpt:path", seatPath)
                        .build())
                .put("Passengers", LinListTag.of(
                        LinTagType.compoundTag(), List.of(shulker)))
                .build();
    }

    private static final class CopiedEntityView extends MutableClipboard {
        private final MutableClipboard parent;

        private CopiedEntityView(MutableClipboard parent) {
            this.parent = parent;
        }

        @SuppressWarnings("unused")
        public Clipboard getParent() {
            return parent;
        }

        @Override
        public List<Entity> getEntities() {
            return parent.getEntities().stream()
                    .map(entity -> new TestEntity(
                            entity.getLocation(), entity.getState()))
                    .map(Entity.class::cast)
                    .toList();
        }

        @Override
        public void removeEntity(Entity entity) {
            parent.removeEntity(entity);
        }
    }

    private static class MutableClipboard extends NullExtent implements Clipboard {
        private final List<Entity> entities = new ArrayList<>();
        private final Region region = new CuboidRegion(BlockVector3.ZERO, BlockVector3.ZERO);
        private BlockVector3 origin = BlockVector3.ZERO;

        void add(double x, double y, double z, LinCompoundTag nbt) {
            add(ITEM_DISPLAY, x, y, z, nbt);
        }

        void add(EntityType type, double x, double y, double z, LinCompoundTag nbt) {
            BaseEntity state = nbt == null
                    ? new BaseEntity(type)
                    : new BaseEntity(type, LazyReference.computed(nbt));
            entities.add(new TestEntity(new Location(this, x, y, z), state));
        }

        @Override
        public Region getRegion() {
            return region;
        }

        @Override
        public BlockVector3 getOrigin() {
            return origin;
        }

        @Override
        public BlockVector3 getDimensions() {
            return BlockVector3.ONE;
        }

        @Override
        public void setOrigin(BlockVector3 origin) {
            this.origin = origin;
        }

        @Override
        public List<Entity> getEntities() {
            return new ArrayList<>(entities);
        }

        public void removeEntity(Entity entity) {
            entities.remove(entity);
        }

        @Override
        public <B extends BlockStateHolder<B>> boolean setBlock(
                BlockVector3 position, B block) {
            return false;
        }

        @Override
        public BlockState getBlock(BlockVector3 position) {
            return null;
        }

        @Override
        public BiomeType getBiome(BlockVector3 position) {
            return null;
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
}