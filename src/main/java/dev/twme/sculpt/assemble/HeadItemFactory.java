package dev.twme.sculpt.assemble;

import dev.twme.sculpt.skin.HeadSkin;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Logger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Builds and caches {@link Material#PLAYER_HEAD} ItemStacks carrying the
 * MineSkin texture profile of a {@link HeadSkin}. Cache keyed by
 * {@link HeadSkin#id()}.
 *
 * <p>Profile application is delegated to a {@link ProfileApplier} picked at
 * startup: Paper-API on Paper, standard Bukkit-API on Spigot. The Paper
 * path preserves MineSkin signatures; the Bukkit path drops them, which is
 * fine for player-head item rendering.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.assemble.HeadItemFactory}).
 */
public final class HeadItemFactory {

    // Caffeine LRU cache — bounded at 512 entries to prevent unbounded
    // growth with many unique block types; old entries are evicted
    // automatically on access.
    private final Cache<UUID, ItemStack> cache = Caffeine.newBuilder()
            .maximumSize(512)
            .build();
    private final ProfileApplier applier;

    public HeadItemFactory(Logger logger) {
        this.applier = ProfileApplier.create(logger);
    }

    public ItemStack build(HeadSkin head) {
        if (head == null || head.textureValue() == null || head.textureSignature() == null) {
            return new ItemStack(Material.PLAYER_HEAD);
        }
        return cache.get(head.id(), id -> create(head));
    }

    public void clear() {
        cache.invalidateAll();
    }

    private ItemStack create(HeadSkin head) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (meta != null) {
            applier.apply(meta, head);
            item.setItemMeta(meta);
        }
        return item;
    }
}
