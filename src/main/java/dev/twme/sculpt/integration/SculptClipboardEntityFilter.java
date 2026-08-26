package dev.twme.sculpt.integration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.math.Vector3;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.enginehub.linbus.tree.LinListTag;
import org.enginehub.linbus.tree.LinNumberTag;
import org.enginehub.linbus.tree.LinStringTag;

final class SculptClipboardEntityFilter {

    private static final String ITEM_DISPLAY_TYPE = "minecraft:item_display";
    private static final String TEXT_DISPLAY_TYPE = "minecraft:text_display";
    private static final String BLOCK_DISPLAY_TYPE = "minecraft:block_display";
    private static final String SHULKER_TYPE = "minecraft:shulker";
    private static final String PASSENGERS_KEY = "Passengers";
    private static final String POSITION_KEY = "Pos";
    private static final String SCULPT_TYPE_KEY = "sculpt:type";
    private static final String ORIGINAL_BLOCK_KEY = "sculpt:original_block";
    private static final String PATH_KEY = "sculpt:path";

    private SculptClipboardEntityFilter() {
    }

    static boolean isSculptRoot(BaseEntity state) {
        if (state == null || !ITEM_DISPLAY_TYPE.equals(state.getType().id())) {
            return false;
        }
        LinCompoundTag nbt = state.getNbt();
        return nbt != null && !markerValue(nbt, "root", ORIGINAL_BLOCK_KEY).isBlank();
    }

    static List<Entity> findRedundantPassengerSnapshots(
            Collection<? extends Entity> entities) {
        Map<EntityKey, Integer> expectedEntities = new HashMap<>();
        Map<EntityKey, List<Entity>> nullNbtEntities = new HashMap<>();

        for (Entity entity : entities) {
            BaseEntity state = entity.getState();
            if (state == null) continue;

            LinCompoundTag nbt = state.getNbt();
            Vector3 position = entity.getLocation().toVector();
            EntityKey key = new EntityKey(state.getType().id(), position);
            if (nbt == null) {
                nullNbtEntities.computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(entity);
            } else {
                for (EntityKey passenger : sculptPassengerEntities(key, nbt)) {
                    expectedEntities.merge(passenger, 1, Integer::sum);
                }
            }
        }

        if (expectedEntities.isEmpty()) return List.of();

        for (Map.Entry<EntityKey, Integer> expected : expectedEntities.entrySet()) {
            List<Entity> candidates = nullNbtEntities.get(expected.getKey());
            if (candidates == null || candidates.size() != expected.getValue()) {
                return List.of();
            }
        }

        List<Entity> redundant = new ArrayList<>();
        for (EntityKey key : expectedEntities.keySet()) {
            redundant.addAll(nullNbtEntities.get(key));
        }
        return redundant;
    }

    private static List<EntityKey> sculptPassengerEntities(
            EntityKey parent, LinCompoundTag nbt) {
        if (ITEM_DISPLAY_TYPE.equals(parent.type())
                && !markerValue(nbt, "root", ORIGINAL_BLOCK_KEY).isBlank()) {
            return sculptRootPassengers(parent, nbt);
        }
        if (!BLOCK_DISPLAY_TYPE.equals(parent.type())) return List.of();

        final String parentPath = pathMarkerValue(nbt, "shulker_seat");
        if (parentPath == null) return List.of();

        Object passengers = nbt.value().get(PASSENGERS_KEY);
        if (!(passengers instanceof LinListTag list) || list.value().isEmpty()) {
            return List.of();
        }
        if (list.value().size() != 1) return List.of();

        Vector3 savedRootPosition = positionFromNbt(nbt);
        List<EntityKey> passengerEntities = new ArrayList<>();
        for (Object passengerTag : list.value()) {
            if (!(passengerTag instanceof LinCompoundTag passenger)
                    || !SHULKER_TYPE.equals(stringValue(passenger.value().get("id")))) {
                return List.of();
            }
            final String passengerPath = pathMarkerValue(passenger, "shulker");
            if (passengerPath == null || !parentPath.equals(passengerPath)) {
                return List.of();
            }

            passengerEntities.add(new EntityKey(
                SHULKER_TYPE, clipboardPassengerPosition(
                    parent.position(), savedRootPosition, passenger)));
        }
        return passengerEntities;
    }

    private static List<EntityKey> sculptRootPassengers(
            final EntityKey parent,
            final LinCompoundTag nbt) {
        final Object passengers = nbt.value().get(PASSENGERS_KEY);
        if (!(passengers instanceof LinListTag list) || list.value().isEmpty()) {
            return List.of();
        }

        final Vector3 savedRootPosition = positionFromNbt(nbt);
        final List<EntityKey> result = new ArrayList<>();
        for (final Object passengerTag : list.value()) {
            if (!(passengerTag instanceof LinCompoundTag passenger)) {
                return List.of();
            }
            final String entityType = stringValue(passenger.value().get("id"));
            if (ITEM_DISPLAY_TYPE.equals(entityType)) {
                if (markerValue(passenger, "leaf", PATH_KEY).isBlank()) {
                    return List.of();
                }
            } else if (TEXT_DISPLAY_TYPE.equals(entityType)) {
                if (!hasMarkerType(passenger, "text_pixel")) return List.of();
            } else {
                return List.of();
            }
            result.add(new EntityKey(entityType, clipboardPassengerPosition(
                parent.position(), savedRootPosition, passenger)));
        }
        return result;
    }

    private static Vector3 clipboardPassengerPosition(
            final Vector3 clipboardRootPosition,
            final Vector3 savedRootPosition,
            final LinCompoundTag passenger) {
        final Vector3 savedPassengerPosition = positionFromNbt(passenger);
        if (savedRootPosition != null && savedPassengerPosition != null) {
            return clipboardRootPosition.add(
                savedPassengerPosition.subtract(savedRootPosition));
        }
        return clipboardRootPosition;
    }

    private static boolean hasMarkerType(
            final Object tag,
            final String expectedType) {
        if (tag instanceof LinCompoundTag compound) {
            final Map<String, ?> values = compound.value();
            if (stringValue(values.get(SCULPT_TYPE_KEY)).equals(expectedType)) {
                return true;
            }
            for (final Map.Entry<String, ?> entry : values.entrySet()) {
                if (!PASSENGERS_KEY.equals(entry.getKey())
                        && hasMarkerType(entry.getValue(), expectedType)) {
                    return true;
                }
            }
        } else if (tag instanceof LinListTag list) {
            for (final Object value : list.value()) {
                if (hasMarkerType(value, expectedType)) return true;
            }
        }
        return false;
    }

    private static String markerValue(
            Object tag, String expectedType, String requiredKey) {
        if (tag instanceof LinCompoundTag compound) {
            Map<String, ?> values = compound.value();
            if (stringValue(values.get(SCULPT_TYPE_KEY)).equals(expectedType)
                    && !stringValue(values.get(requiredKey)).isBlank()) {
                return stringValue(values.get(requiredKey));
            }

            for (Map.Entry<String, ?> entry : values.entrySet()) {
                if (PASSENGERS_KEY.equals(entry.getKey())) continue;
                String value = markerValue(entry.getValue(), expectedType, requiredKey);
                if (!value.isBlank()) return value;
            }
        } else if (tag instanceof LinListTag list) {
            for (Object value : list.value()) {
                String marker = markerValue(value, expectedType, requiredKey);
                if (!marker.isBlank()) return marker;
            }
        }
        return "";
    }

    /** Return null when absent; keep an empty legacy root path distinct for cleanup. */
    private static String pathMarkerValue(Object tag, String expectedType) {
        if (tag instanceof LinCompoundTag compound) {
            Map<String, ?> values = compound.value();
            if (stringValue(values.get(SCULPT_TYPE_KEY)).equals(expectedType)
                    && values.get(PATH_KEY) instanceof LinStringTag path) {
                return path.value();
            }

            for (Map.Entry<String, ?> entry : values.entrySet()) {
                if (PASSENGERS_KEY.equals(entry.getKey())) continue;
                String value = pathMarkerValue(entry.getValue(), expectedType);
                if (value != null) return value;
            }
        } else if (tag instanceof LinListTag list) {
            for (Object value : list.value()) {
                String marker = pathMarkerValue(value, expectedType);
                if (marker != null) return marker;
            }
        }
        return null;
    }

    private static Vector3 positionFromNbt(LinCompoundTag nbt) {
        Object value = nbt.value().get(POSITION_KEY);
        if (!(value instanceof LinListTag list) || list.value().size() != 3) {
            return null;
        }

        double[] coordinates = new double[3];
        for (int index = 0; index < coordinates.length; index++) {
            Object coordinate = list.value().get(index);
            if (!(coordinate instanceof LinNumberTag<?> number)) return null;
            coordinates[index] = number.value().doubleValue();
        }
        return Vector3.at(coordinates[0], coordinates[1], coordinates[2]);
    }

    private static String stringValue(Object tag) {
        return tag instanceof LinStringTag stringTag ? stringTag.value() : "";
    }

    private record EntityKey(String type, Vector3 position) {
    }
}
