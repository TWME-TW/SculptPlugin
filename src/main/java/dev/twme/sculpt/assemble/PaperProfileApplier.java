package dev.twme.sculpt.assemble;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.inventory.meta.SkullMeta;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

import dev.twme.sculpt.skin.HeadSkin;

/**
 * Paper-only implementation. Loaded by {@link ProfileApplier#create} via
 * reflection only when Paper is detected, so its
 * {@code com.destroystokyo.paper.profile.*} imports never link on Spigot.
 *
 * <p>Ported from Tessera ({@code org.inventivetalent.tessera.assemble.PaperProfileApplier}).
 */
final class PaperProfileApplier implements ProfileApplier {

    @Override
    public boolean apply(SkullMeta meta, HeadSkin head) {
        // NIL UUID prevents Paper from trying to fetch profile properties
        // from Mojang's session server (avoiding 429 rate-limit errors).
        PlayerProfile profile = Bukkit.createProfile(new UUID(0L, 0L), "sculpt");
        profile.setProperty(new ProfileProperty(
                "textures", head.textureValue(), head.textureSignature()));
        meta.setPlayerProfile(profile);
        return true;
    }
}
