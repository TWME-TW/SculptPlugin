package dev.twme.sculpt.integration;

import java.util.List;

import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.NullExtent;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.concurrency.LazyReference;
import com.sk89q.worldedit.world.entity.EntityType;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinDoubleTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinTagType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SculptClipboardEntityFilterTest {

    private static final EntityType ITEM_DISPLAY =
            new EntityType("minecraft:item_display");
    private static final EntityType BLOCK_DISPLAY =
            new EntityType("minecraft:block_display");
    private static final EntityType TEXT_DISPLAY =
            new EntityType("minecraft:text_display");
    private static final EntityType SHULKER =
            new EntityType("minecraft:shulker");
    private static final EntityType ARMOR_STAND =
            new EntityType("minecraft:armor_stand");
    private static final Extent EXTENT = new NullExtent();

    @Test
    void selectsNullNbtItemDisplayAtSculptRootPosition() {
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, sculptRootNbt());
        TestEntity passenger = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);

        assertEquals(List.of(passenger), SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(passenger, root)));
    }

    @Test
    void preservesNullNbtItemDisplayAtAnotherPosition() {
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, sculptRootNbt());
        TestEntity unrelated = entity(ITEM_DISPLAY, 2.5, 64.5, 2.5, null);

        assertTrue(SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, unrelated)).isEmpty());
    }

    @Test
    void preservesNullNbtNonItemDisplayAtRootPosition() {
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, sculptRootNbt());
        TestEntity unrelated = entity(ARMOR_STAND, 1.5, 64.5, 2.5, null);

        assertTrue(SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, unrelated)).isEmpty());
    }

    @Test
    void preservesSerializedItemDisplayAtRootPosition() {
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, sculptRootNbt());
        TestEntity serialized = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5,
                LinCompoundTag.builder().putString("id", "minecraft:item_display").build());

        assertTrue(SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, serialized)).isEmpty());
    }

    @Test
    void preservesNullNbtDisplayWithoutProvenSculptRoot() {
        TestEntity ordinary = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5,
                LinCompoundTag.builder()
                        .put("Passengers", passengerList())
                        .build());
        TestEntity nullNbt = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);

        assertTrue(SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(ordinary, nullNbt)).isEmpty());
    }

    @Test
    void recognizesRootMarkersInsideArbitrarilyNamedContainer() {
        LinCompoundTag rootNbt = LinCompoundTag.builder()
                .put("PluginData", LinCompoundTag.builder()
                        .putString("sculpt:type", "root")
                        .putString("sculpt:original_block", "minecraft:stone")
                        .build())
            .put("Pos", position(100.5, 64.5, 200.5))
                .put("Passengers", passengerList())
                .build();
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, rootNbt);
        TestEntity passenger = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);

        assertEquals(List.of(passenger), SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, passenger)));
    }

    @Test
    void preservesCandidatesWhenNullNbtCountDoesNotMatchPassengers() {
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, sculptRootNbt());
        TestEntity passenger = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);
        TestEntity unrelated = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);

        assertTrue(SculptClipboardEntityFilter.findRedundantPassengerSnapshots(
                List.of(root, passenger, unrelated)).isEmpty());
    }

    @Test
    void preservesCandidatesWhenRootPassengerIsNotProvenSculptLeaf() {
        LinCompoundTag rootNbt = LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "root")
                        .putString("sculpt:original_block", "minecraft:stone")
                        .build())
                .put("Passengers", LinListTag.of(LinTagType.compoundTag(), List.of(
                        LinCompoundTag.builder()
                                .putString("id", "minecraft:item_display")
                                .build())))
                .build();
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, rootNbt);
        TestEntity candidate = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);

        assertTrue(SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, candidate)).isEmpty());
    }

    @Test
    void usesPassengerPositionRelativeToSavedRootPosition() {
        LinCompoundTag passengerNbt = sculptPassengerNbt(100.75, 64.5, 200.5);
        LinCompoundTag rootNbt = LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "root")
                        .putString("sculpt:original_block", "minecraft:stone")
                        .build())
                .put("Pos", position(100.5, 64.5, 200.5))
                .put("Passengers", LinListTag.of(
                        LinTagType.compoundTag(), List.of(passengerNbt)))
                .build();
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, rootNbt);
        TestEntity passenger = entity(ITEM_DISPLAY, 1.75, 64.5, 2.5, null);

        assertEquals(List.of(passenger), SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, passenger)));
    }

    @Test
    void preservesAllCandidatesWhenOnePassengerPositionIsIncomplete() {
        LinCompoundTag rootNbt = LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "root")
                        .putString("sculpt:original_block", "minecraft:stone")
                        .build())
                .put("Pos", position(100.5, 64.5, 200.5))
                .put("Passengers", LinListTag.of(LinTagType.compoundTag(), List.of(
                        sculptPassengerNbt("0", 100.5, 64.5, 200.5),
                        sculptPassengerNbt("1", 100.75, 64.5, 200.5))))
                .build();
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, rootNbt);
        TestEntity firstPassenger = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);

        assertTrue(SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, firstPassenger)).isEmpty());
    }

    @Test
    void selectsNullNbtShulkerOwnedByMarkedSeat() {
        TestEntity seat = entity(BLOCK_DISPLAY, 3.25, 65.0, 4.25,
                sculptSeatNbt("2.5", "2.5"));
        TestEntity shulker = entity(SHULKER, 3.25, 65.0, 4.25, null);

        assertEquals(List.of(shulker), SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(shulker, seat)));
    }

    @Test
    void selectsNullNbtShulkerAtCanonicalRootPath() {
        TestEntity seat = entity(BLOCK_DISPLAY, 3.5, 65.0, 4.5,
                sculptSeatNbt("", ""));
        TestEntity shulker = entity(SHULKER, 3.5, 65.0, 4.5, null);

        assertEquals(List.of(shulker), SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(shulker, seat)));
    }

    @Test
    void preservesNullNbtShulkerWhenSeatAndPassengerPathsDiffer() {
        TestEntity seat = entity(BLOCK_DISPLAY, 3.25, 65.0, 4.25,
                sculptSeatNbt("2.5", "2.6"));
        TestEntity shulker = entity(SHULKER, 3.25, 65.0, 4.25, null);

        assertTrue(SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(seat, shulker)).isEmpty());
    }

    @Test
    void selectsDisplayLeafAndShulkerPassengerTogether() {
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, sculptRootNbt());
        TestEntity leaf = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);
        TestEntity seat = entity(BLOCK_DISPLAY, 3.25, 65.0, 4.25,
                sculptSeatNbt("2.5", "2.5"));
        TestEntity shulker = entity(SHULKER, 3.25, 65.0, 4.25, null);

        List<Entity> redundant = SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, leaf, seat, shulker));

        assertEquals(2, redundant.size());
        assertTrue(redundant.contains(leaf));
        assertTrue(redundant.contains(shulker));
    }

    @Test
    void selectsTextPixelPassengerSnapshotsFromSculptRoot() {
        LinCompoundTag rootNbt = LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "root")
                        .putString("sculpt:original_block", "minecraft:glass")
                        .build())
                .put("Pos", position(100.5, 64.5, 200.5))
                .put("Passengers", LinListTag.of(LinTagType.compoundTag(), List.of(
                        sculptPassengerNbt(100.5, 64.5, 200.5),
                        textPixelPassengerNbt(100.75, 64.5, 200.5))))
                .build();
        TestEntity root = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, rootNbt);
        TestEntity leaf = entity(ITEM_DISPLAY, 1.5, 64.5, 2.5, null);
        TestEntity pixel = entity(TEXT_DISPLAY, 1.75, 64.5, 2.5, null);

        List<Entity> redundant = SculptClipboardEntityFilter
                .findRedundantPassengerSnapshots(List.of(root, leaf, pixel));

        assertEquals(2, redundant.size());
        assertTrue(redundant.contains(leaf));
        assertTrue(redundant.contains(pixel));
    }

    private static LinCompoundTag sculptRootNbt() {
        return LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "root")
                        .putString("sculpt:original_block", "minecraft:stone")
                        .build())
                .put("Pos", position(100.5, 64.5, 200.5))
                .put("Passengers", passengerList())
                .build();
    }

    private static LinListTag<LinCompoundTag> passengerList() {
        return LinListTag.of(LinTagType.compoundTag(), List.of(
                sculptPassengerNbt(100.5, 64.5, 200.5)));
    }

    private static LinCompoundTag sculptPassengerNbt(double x, double y, double z) {
        return sculptPassengerNbt("0", x, y, z);
    }

    private static LinCompoundTag sculptPassengerNbt(
            String path, double x, double y, double z) {
        return LinCompoundTag.builder()
                .putString("id", "minecraft:item_display")
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "leaf")
                        .putString("sculpt:path", path)
                        .build())
                .put("Pos", position(x, y, z))
                .build();
    }

    private static LinCompoundTag textPixelPassengerNbt(
            double x, double y, double z) {
        return LinCompoundTag.builder()
                .putString("id", "minecraft:text_display")
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "text_pixel")
                        .build())
                .put("Pos", position(x, y, z))
                .build();
    }

    private static LinCompoundTag sculptSeatNbt(String seatPath, String shulkerPath) {
        LinCompoundTag shulker = LinCompoundTag.builder()
                .putString("id", "minecraft:shulker")
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "shulker")
                        .putString("sculpt:path", shulkerPath)
                        .build())
                .put("Pos", position(100.25, 65.0, 200.25))
                .build();
        return LinCompoundTag.builder()
                .put("PersistentData", LinCompoundTag.builder()
                        .putString("sculpt:type", "shulker_seat")
                        .putString("sculpt:path", seatPath)
                        .build())
                .put("Pos", position(100.25, 65.0, 200.25))
                .put("Passengers", LinListTag.of(
                        LinTagType.compoundTag(), List.of(shulker)))
                .build();
    }

    private static LinListTag<LinDoubleTag> position(double x, double y, double z) {
        return LinListTag.of(LinTagType.doubleTag(), List.of(
                LinDoubleTag.of(x), LinDoubleTag.of(y), LinDoubleTag.of(z)));
    }

    private static TestEntity entity(EntityType type, double x, double y, double z,
                                     LinCompoundTag nbt) {
        BaseEntity state = nbt == null
                ? new BaseEntity(type)
                : new BaseEntity(type, LazyReference.computed(nbt));
        return new TestEntity(new Location(EXTENT, x, y, z), state);
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
