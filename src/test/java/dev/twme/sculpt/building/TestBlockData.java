package dev.twme.sculpt.building;

import java.lang.reflect.Proxy;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Immutable BlockData fakes that compare by their serialized form. */
final class TestBlockData {

    private TestBlockData() {}

    static BlockData of(final Material material) {
        final String serialized = "minecraft:" + material.name().toLowerCase(java.util.Locale.ROOT);
        final BlockData[] self = new BlockData[1];
        self[0] = (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(),
            new Class<?>[]{BlockData.class}, (proxy, method, args) -> switch (method.getName()) {
                case "equals" -> args[0] instanceof BlockData other
                    && serialized.equals(other.getAsString());
                case "hashCode" -> serialized.hashCode();
                case "toString", "getAsString" -> serialized;
                case "clone" -> self[0];
                case "getMaterial" -> material;
                default -> throw new UnsupportedOperationException(method.getName());
            });
        return self[0];
    }
}
