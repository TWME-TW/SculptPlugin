package dev.twme.sculpt.skin.bake;

import dev.twme.sculpt.assets.fetch.McAssetClient;
import dev.twme.sculpt.assets.model.BlockModel;
import dev.twme.sculpt.assets.model.ModelResolver;
import dev.twme.sculpt.core.BlockKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Material;

/**
 * Scans all known Minecraft 1.21.x block IDs through the bake pipeline's
 * {@link ModelResolver} and emits non-tinted blocks that have a supported
 * full-cube material source as {@code bake-blocks.txt}. Slabs use their
 * vanilla {@code type=double} model; stairs and other non-cube shapes remain
 * excluded.
 *
 * <p>Usage (from project root):
 * <pre>
 *   mvn test-compile exec:java -Dexec.mainClass=dev.twme.sculpt.skin.bake.BlockListGenerator \
 *       -Dexec.classpathScope=test -Dexec.args='--out bake-blocks-full.txt'
 * </pre>
 */
public final class BlockListGenerator {

    /**
     * Every known minecraft: block ID as of 1.21.x.  This list biases for
     * inclusion — items that turn out to be non-cube, tinted, or transparent
     * will be filtered out by the ModelResolver during the scan, so it's
     * safe to list blocks whose bakeability we are unsure about.
     *
     * <p>Generated from: Minecraft 1.21.4 built-in registry +
     * mcasset.cloud trial-and-error baseline.
     */
    private static final String[] CANDIDATES = {
            // ── Stone / Deepslate ────────────────────────────────────
            "stone", "granite", "diorite", "andesite", "deepslate",
            "cobblestone", "mossy_cobblestone", "smooth_stone",
            "stone_bricks", "mossy_stone_bricks", "cracked_stone_bricks",
            "chiseled_stone_bricks",
            "granite_slab", "diorite_slab", "andesite_slab",
            "cobbled_deepslate", "polished_deepslate",
            "deepslate_bricks", "cracked_deepslate_bricks",
            "deepslate_tiles", "cracked_deepslate_tiles",
            "chiseled_deepslate",
            "polished_granite", "polished_diorite", "polished_andesite",
            "tuff", "polished_tuff", "tuff_bricks", "chiseled_tuff",
            "calcite", "dripstone_block", "smooth_basalt",

            // ── Bricks ───────────────────────────────────────────────
            "bricks", "mud_bricks", "packed_mud",
            "prismarine", "prismarine_bricks", "dark_prismarine",
            "nether_bricks", "red_nether_bricks", "cracked_nether_bricks",
            "chiseled_nether_bricks",
            "end_stone", "end_stone_bricks",
            "purpur_block", "purpur_pillar",
            "quartz_block", "quartz_bricks", "quartz_pillar",
            "chiseled_quartz_block", "smooth_quartz",

            // ── Sands / Soils ────────────────────────────────────────
            "dirt", "coarse_dirt", "rooted_dirt", "mud",
            "sand", "red_sand", "soul_sand", "soul_soil",
            "gravel", "clay",
            "sandstone", "cut_sandstone", "chiseled_sandstone",
            "smooth_sandstone",
            "red_sandstone", "cut_red_sandstone", "chiseled_red_sandstone",
            "smooth_red_sandstone",

            // ── Ores ─────────────────────────────────────────────────
            "coal_ore", "deepslate_coal_ore",
            "iron_ore", "deepslate_iron_ore",
            "copper_ore", "deepslate_copper_ore",
            "gold_ore", "deepslate_gold_ore",
            "redstone_ore", "deepslate_redstone_ore",
            "emerald_ore", "deepslate_emerald_ore",
            "lapis_ore", "deepslate_lapis_ore",
            "diamond_ore", "deepslate_diamond_ore",
            "nether_quartz_ore", "nether_gold_ore",
            "ancient_debris", "gilded_blackstone",

            // ── Mineral / Metal blocks ───────────────────────────────
            "coal_block", "iron_block", "copper_block",
            "gold_block", "redstone_block",
            "emerald_block", "lapis_block", "diamond_block",
            "netherite_block",
            "raw_iron_block", "raw_copper_block", "raw_gold_block",
            "amethyst_block", "budding_amethyst",
            "exposed_copper", "weathered_copper", "oxidized_copper",
            "cut_copper", "exposed_cut_copper", "weathered_cut_copper",
            "oxidized_cut_copper",
            "waxed_copper_block", "waxed_exposed_copper",
            "waxed_weathered_copper", "waxed_oxidized_copper",
            "waxed_cut_copper", "waxed_exposed_cut_copper",
            "waxed_weathered_cut_copper", "waxed_oxidized_cut_copper",
            "chiseled_copper", "exposed_chiseled_copper",
            "weathered_chiseled_copper", "oxidized_chiseled_copper",
            "waxed_chiseled_copper", "waxed_exposed_chiseled_copper",
            "waxed_weathered_chiseled_copper", "waxed_oxidized_chiseled_copper",
            "copper_grate", "exposed_copper_grate", "weathered_copper_grate",
            "oxidized_copper_grate",
            "waxed_copper_grate", "waxed_exposed_copper_grate",
            "waxed_weathered_copper_grate", "waxed_oxidized_copper_grate",

            // ── Wood planks ──────────────────────────────────────────
            "oak_planks", "spruce_planks", "birch_planks",
            "jungle_planks", "acacia_planks", "dark_oak_planks",
            "mangrove_planks", "cherry_planks",
            "bamboo_planks", "bamboo_mosaic",
            "crimson_planks", "warped_planks",
            "pale_oak_planks",

            // ── Logs / stems (axis=variant → canonical baked) ────────
            "oak_log", "spruce_log", "birch_log",
            "jungle_log", "acacia_log", "dark_oak_log",
            "mangrove_log", "cherry_log", "pale_oak_log",
            "crimson_stem", "warped_stem",
            "stripped_oak_log", "stripped_spruce_log",
            "stripped_birch_log", "stripped_jungle_log",
            "stripped_acacia_log", "stripped_dark_oak_log",
            "stripped_mangrove_log", "stripped_cherry_log",
            "stripped_pale_oak_log",
            "stripped_crimson_stem", "stripped_warped_stem",
            "oak_wood", "spruce_wood", "birch_wood",
            "jungle_wood", "acacia_wood", "dark_oak_wood",
            "mangrove_wood", "cherry_wood", "pale_oak_wood",
            "crimson_hyphae", "warped_hyphae",
            "stripped_oak_wood", "stripped_spruce_wood",
            "stripped_birch_wood", "stripped_jungle_wood",
            "stripped_acacia_wood", "stripped_dark_oak_wood",
            "stripped_mangrove_wood", "stripped_cherry_wood",
            "stripped_pale_oak_wood",
            "stripped_crimson_hyphae", "stripped_warped_hyphae",
            "bamboo_block", "stripped_bamboo_block",

            // ── Terracotta ───────────────────────────────────────────
            "terracotta",
            "white_terracotta", "orange_terracotta", "magenta_terracotta",
            "light_blue_terracotta", "yellow_terracotta", "lime_terracotta",
            "pink_terracotta", "gray_terracotta", "light_gray_terracotta",
            "cyan_terracotta", "purple_terracotta", "blue_terracotta",
            "brown_terracotta", "green_terracotta", "red_terracotta",
            "black_terracotta",

            // ── Concrete ─────────────────────────────────────────────
            "white_concrete", "orange_concrete", "magenta_concrete",
            "light_blue_concrete", "yellow_concrete", "lime_concrete",
            "pink_concrete", "gray_concrete", "light_gray_concrete",
            "cyan_concrete", "purple_concrete", "blue_concrete",
            "brown_concrete", "green_concrete", "red_concrete",
            "black_concrete",

            // ── Wool ─────────────────────────────────────────────────
            "white_wool", "orange_wool", "magenta_wool",
            "light_blue_wool", "yellow_wool", "lime_wool",
            "pink_wool", "gray_wool", "light_gray_wool",
            "cyan_wool", "purple_wool", "blue_wool",
            "brown_wool", "green_wool", "red_wool", "black_wool",

            // ── Glazed terracotta (orientable) ──────────────────────
            "white_glazed_terracotta", "orange_glazed_terracotta",
            "magenta_glazed_terracotta", "light_blue_glazed_terracotta",
            "yellow_glazed_terracotta", "lime_glazed_terracotta",
            "pink_glazed_terracotta", "gray_glazed_terracotta",
            "light_gray_glazed_terracotta", "cyan_glazed_terracotta",
            "purple_glazed_terracotta", "blue_glazed_terracotta",
            "brown_glazed_terracotta", "green_glazed_terracotta",
            "red_glazed_terracotta", "black_glazed_terracotta",

            // ── Misc building blocks ─────────────────────────────────
            "obsidian", "crying_obsidian",
            "bedrock",
            "bookshelf", "chiseled_bookshelf",
            "note_block", "jukebox",
            "pumpkin", "carved_pumpkin", "jack_o_lantern",
            "melon", "hay_block",
            "bone_block",
            "dried_kelp_block",
            "target",
            "lodestone",
            "respawn_anchor",
            "sculk", "sculk_catalyst",
            "magma_block",
            "netherrack",
            "blackstone", "polished_blackstone",
            "polished_blackstone_bricks", "cracked_polished_blackstone_bricks",
            "chiseled_polished_blackstone",
            "basalt", "polished_basalt",
            "glowstone",
            "shroomlight",
            "ochre_froglight", "verdant_froglight", "pearlescent_froglight",
            "moss_block",
            "nether_wart_block", "warped_wart_block",

            // ── Crafting / utility ───────────────────────────────────
            "crafting_table", "fletching_table", "smithing_table",
            "cartography_table", "loom",
            "furnace", "blast_furnace", "smoker",
            "beehive", "bee_nest",
            "barrel",
            "composter",
            "cauldron",
            "dropper", "dispenser", "observer",
            "piston", "sticky_piston",
            "tnt",
            "enchanting_table",

            // ── Colored concrete powder (gravity-affected but cube) ──
            "white_concrete_powder", "orange_concrete_powder",
            "magenta_concrete_powder", "light_blue_concrete_powder",
            "yellow_concrete_powder", "lime_concrete_powder",
            "pink_concrete_powder", "gray_concrete_powder",
            "light_gray_concrete_powder", "cyan_concrete_powder",
            "purple_concrete_powder", "blue_concrete_powder",
            "brown_concrete_powder", "green_concrete_powder",
            "red_concrete_powder", "black_concrete_powder",

            // ── Other ────────────────────────────────────────────────
            "sponge", "wet_sponge",
            "mycelium", "podzol",
            "crimson_nylium", "warped_nylium",
            "snow_block", "ice", "packed_ice", "blue_ice",
            "mushroom_stem",
            "brown_mushroom_block", "red_mushroom_block",
    };

    public static void main(String[] argv) throws Exception {
        String outFile = "bake-blocks-full.txt";
        String mcVersion = "1.21.11";
        Path cacheRoot = Path.of("build/tessera-cache");

        // Simple arg parsing
        for (int i = 0; i < argv.length - 1; i += 2) {
            String k = argv[i].startsWith("--") ? argv[i].substring(2) : argv[i];
            switch (k) {
                case "out"     -> outFile = argv[i + 1];
                case "version" -> mcVersion = argv[i + 1];
                case "cache"   -> cacheRoot = Path.of(argv[i + 1]);
            }
        }

        Logger logger = Logger.getLogger("block-list-gen");
        logger.setUseParentHandlers(false);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setLevel(Level.INFO);
        logger.addHandler(ch);
        logger.setLevel(Level.INFO);

        // Filter out tinted-block name patterns early (grass_block, leaves, etc.)
        Set<String> tintedPatterns = Set.of(
                "grass_block", "leaves", "vine", "lily_pad",
                "water", "lava", "kelp", "seagrass",
                "sugar_cane", "bamboo", "cactus",
                "fire", "portal", "air", "void_air", "cave_air",
                "torch", "ladder", "wall_torch", "soul_torch",
                "redstone_wire", "repeater", "comparator",
                "rail", "powered_rail", "detector_rail", "activator_rail",
                "cobweb", "tripwire",
                "snow", "moss_carpet",
                "flower", "sapling", "mushroom", "fungus",
                "roots", "sprouts", "grass", "fern", "dead_bush",
                "tall_grass", "large_fern",
                "chorus_plant", "chorus_flower",
                "cocoa", "candle", "cake",
                "banner", "sign", "hanging_sign",
                "wall_sign", "wall_hanging_sign",
                "bell", "chain", "lantern", "soul_lantern",
                "campfire", "soul_campfire",
                "brewing_stand", "flower_pot", "potted_",
                "head", "skull",
                "door", "trapdoor",
                "fence", "fence_gate",
                "wall", "pane", "bars",
                "button", "pressure_plate", "lever",
                "stairs",
                "carpet", "bed",
                "end_rod", "lightning_rod",
                "anvil", "hopper", "beacon", "conduit",
                "coral", "sea_pickle",
                "azalea", "flowering_azalea",
                "dripleaf", "spore_blossom",
                "hanging_roots", "sculk_sensor", "sculk_shrieker", "sculk_vein",
                "frogspawn", "sniffer_egg", "turtle_egg",
                "decorated_pot", "pitcher_plant", "torchflower",
                "pointed_dripstone",
                "amethyst_cluster", "amethyst_bud",
                "shulker_box", "chest", "trapped_chest", "ender_chest",
                "daylight_detector",
                "farmland", "dirt_path",
                "mangrove_roots", "muddy_mangrove_roots",
                "crafter", "vault", "trial_spawner",
                "spawner", "command_block",
                "structure_block", "jigsaw",
                "light"
        );

        Set<String> seen = new LinkedHashSet<>();
        List<BlockKey> candidates = new ArrayList<>();
        for (String raw : CANDIDATES) {
            BlockKey key = BlockKey.of(raw);
            if (seen.add(key.toString())) {
                // Skip known tinted / non-cube patterns early
                boolean skip = false;
                for (String pattern : tintedPatterns) {
                    if (key.path().contains(pattern)) { skip = true; break; }
                }
                if (!skip) {
                    candidates.add(key);
                }
            }
        }
        // Keep slab coverage in sync with the Paper API instead of maintaining
        // another hand-written list. Missing assets for older target versions
        // are rejected normally by ModelResolver.
        for (final Material material : Material.values()) {
            if (material.isLegacy() || !material.name().endsWith("_SLAB")) continue;
            final BlockKey key = BlockKey.of(
                material.name().toLowerCase(Locale.ROOT));
            if (seen.add(key.toString())) candidates.add(key);
        }

        logger.info("Testing " + candidates.size() + " candidates against mcasset.cloud (v" + mcVersion + ")...");

        McAssetClient client = new McAssetClient(cacheRoot.resolve("assets"));
        ModelResolver resolver = new ModelResolver(client, logger, mcVersion);

        List<BlockKey> bakeable = new ArrayList<>();
        List<String> skippedNonCube = new ArrayList<>();
        List<String> skippedTinted = new ArrayList<>();

        for (BlockKey key : candidates) {
            try {
                Optional<BlockModel> modelOpt = resolver.resolve(key);
                if (modelOpt.isEmpty()) {
                    skippedNonCube.add(key.toString());
                    logger.fine(key + " → non-cube / asset missing");
                } else if (modelOpt.get().tinted()) {
                    skippedTinted.add(key.toString());
                    logger.fine(key + " → tinted (skipped)");
                } else {
                    bakeable.add(key);
                    logger.info("    ✓ " + key);
                }
            } catch (Exception e) {
                skippedNonCube.add(key.toString());
                logger.fine(key + " → error: " + e.getMessage());
            }
        }

        // Write output
        StringBuilder sb = new StringBuilder();
        sb.append("# bake-blocks-full.txt — auto-generated bakeable block list\n");
        sb.append("# Generated by BlockListGenerator against mcasset.cloud v")
          .append(mcVersion).append("\n");
        sb.append("# Total bakeable: ").append(bakeable.size()).append("\n");
        sb.append("#\n");
        sb.append("# Skipped (non-cube / asset missing): ")
          .append(skippedNonCube.size()).append("\n");
        if (!skippedNonCube.isEmpty()) {
            sb.append("#   ").append(String.join(", ", skippedNonCube)).append("\n");
        }
        sb.append("# Skipped (tinted): ").append(skippedTinted.size()).append("\n");
        if (!skippedTinted.isEmpty()) {
            sb.append("#   ").append(String.join(", ", skippedTinted)).append("\n");
        }
        sb.append("\n");

        for (BlockKey key : bakeable) {
            sb.append(key.toString()).append("\n");
        }

        Path outPath = Path.of(outFile);
        Files.writeString(outPath, sb.toString(), StandardCharsets.UTF_8);

        logger.info("Wrote " + bakeable.size() + " bakeable blocks to " + outPath.toAbsolutePath());
        logger.info("Skipped " + skippedNonCube.size() + " non-cube, "
                    + skippedTinted.size() + " tinted");
    }
}
