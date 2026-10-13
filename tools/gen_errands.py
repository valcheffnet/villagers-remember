"""Generates the built-in errand pools in src/main/resources/data/villageerrands/village_errands/.

Each profession has five packs (tiers T1-T5, TIERS.md section 4). A villager offers errands only from the pack of the
player's tier with that villager. Values are emerald equivalents per unit, loosely based on what vanilla villagers
pay or charge.
Run: python tools/gen_errands.py
"""
import json
import os
import zipfile

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "villageerrands", "village_errands")
JAR = os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/26.3/minecraft-common-deobf-26.3.jar")
NAMES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "villageerrands", "vanilla_names", "en_us.json")


def mc(i):
    return i if ":" in i else "minecraft:" + i


# ---- task helpers: the tier is filled in from PACKS ----

def D(i, lo, hi, value, weight=10):
    """Bring an item (turned into "do the action + bring" where an action fits, see to_actions)."""
    return {"deliver": mc(i), "count": [lo, hi], "value": value, "weight": weight}


def K(entity, lo, hi, value, weight=10):
    return {"kill": mc(entity), "count": [lo, hi], "value": value, "weight": weight}


def A(objectives, lo, hi, value, weight=10):
    return {"objectives": objectives, "count": [lo, hi], "value": value, "weight": weight}


def O(name):
    return ("order", name)


def R(i, count, levels):
    """Reforge: make `count` of an item and bring them, get one of the same, enchanted at `levels`."""
    return ("reforge", i, count, levels)


# ---- rewards: the packs they can come from (min_tier..max_tier), so iron stops before the diamond packs ----

def item(i, lo, hi, value, tiers=(2, 5)):
    return {"item": mc(i), "count": [lo, hi], "value": value, "min_tier": tiers[0], "max_tier": tiers[1]}


def loot(table, value, tiers=(3, 4)):
    return {"loot_table": table, "value": value, "min_tier": tiers[0], "max_tier": tiers[1]}


def enchant(i, lo, hi, value_per_level, tiers=(3, 5), max_levels=False):
    out = {"enchant": mc(i), "levels": [lo, hi], "value_per_level": value_per_level, "min_tier": tiers[0], "max_tier": tiers[1]}
    if max_levels:
        out["max_levels"] = True
    return out


T2, T23, T3, T34, T45, T5 = (2, 2), (2, 3), (3, 3), (3, 4), (4, 5), (5, 5)
REWARDS = {
    "farmer": [item("pumpkin_pie", 4, 8, 0.25, T23), item("golden_carrot", 3, 6, 0.6, T34),
               loot("minecraft:chests/village/village_plains_house", 3, T3), item("golden_apple", 1, 3, 3.0, T45),
               item("enchanted_golden_apple", 1, 1, 15.0, T5)],
    "fisherman": [item("cooked_salmon", 6, 12, 0.15, T23), loot("minecraft:chests/village/village_fisher", 3, T3),
                  enchant("fishing_rod", 10, 18, 0.3, T3), enchant("fishing_rod", 20, 30, 0.3, (4, 4)),
                  enchant("fishing_rod", 25, 30, 0.35, T5, True), item("heart_of_the_sea", 1, 1, 12.0, T5)],
    "shepherd": [item("shears", 1, 1, 1.0, T2), item("painting", 2, 4, 0.4, T23),
                 loot("minecraft:chests/village/village_shepherd", 3, T34), enchant("shears", 20, 30, 0.2, T45, True)],
    "librarian": [item("bookshelf", 2, 4, 1.0, T23), enchant("book", 10, 18, 0.4, T3),
                  item("experience_bottle", 4, 8, 0.4, T34), enchant("book", 20, 30, 0.4, T45),
                  item("ender_pearl", 2, 4, 1.0, T45), item("experience_bottle", 16, 32, 0.4, T5)],
    # Treasure maps are the cartographer's own: buried treasure from T3, monument and trial chambers from T4, mansion at T5.
    "cartographer": [item("map", 1, 3, 0.6, T2), item("compass", 1, 1, 1.0, T23), item("spyglass", 1, 1, 2.0, T3),
                     loot("villageerrands:top/map_buried_treasure", 3, T34), loot("villageerrands:top/map_buried_treasure", 3, T34),
                     loot("villageerrands:top/map_monument", 5, T45), loot("villageerrands:top/map_trial_chambers", 5, T45),
                     loot("villageerrands:top/map_mansion", 6, T5), item("ender_eye", 2, 4, 2.0, T45),
                     item("recovery_compass", 1, 1, 8.0, T5)],
    "cleric": [item("experience_bottle", 4, 10, 0.4, T23), loot("minecraft:chests/village/village_temple", 3, T34),
               item("ender_pearl", 1, 3, 1.0, T45), item("golden_apple", 1, 2, 3.0, T45),
               item("experience_bottle", 16, 32, 0.4, T5)],
    # Material by pack: T1 stone, T2 copper, T3 iron, T4 diamond, T5 netherite (user, 2026-10-13).
    "armorer": [item("shield", 1, 1, 1.5, T2), item("copper_chestplate", 1, 1, 1.0, T2),
                loot("minecraft:chests/village/village_armorer", 3, T3), enchant("iron_chestplate", 10, 18, 0.35, T3),
                enchant("diamond_helmet", 20, 30, 0.45, (4, 4)), enchant("diamond_boots", 20, 30, 0.45, (4, 4)),
                enchant("netherite_chestplate", 25, 30, 0.6, T5, True), enchant("netherite_leggings", 25, 30, 0.6, T5, True)],
    "weaponsmith": [item("copper_sword", 1, 1, 0.5, T2), item("copper_axe", 1, 1, 0.6, T2),
                    loot("minecraft:chests/village/village_weaponsmith", 3, T3), enchant("iron_sword", 10, 18, 0.3, T3),
                    enchant("diamond_sword", 20, 30, 0.45, (4, 4)), enchant("diamond_axe", 20, 30, 0.45, (4, 4)),
                    enchant("netherite_sword", 25, 30, 0.6, T5, True), enchant("netherite_axe", 25, 30, 0.6, T5, True)],
    "toolsmith": [item("copper_pickaxe", 1, 1, 0.6, T2), item("copper_shovel", 1, 1, 0.3, T2),
                  loot("minecraft:chests/village/village_toolsmith", 3, T3), enchant("iron_pickaxe", 10, 18, 0.3, T3),
                  enchant("diamond_pickaxe", 20, 30, 0.45, (4, 4)), enchant("diamond_shovel", 20, 30, 0.4, (4, 4)),
                  enchant("netherite_pickaxe", 25, 30, 0.6, T5, True), enchant("netherite_shovel", 25, 30, 0.6, T5, True)],
    "butcher": [item("cooked_beef", 6, 12, 0.2, T23), item("rabbit_stew", 2, 4, 0.6, T23),
                loot("minecraft:chests/village/village_butcher", 3, T34), item("golden_carrot", 8, 16, 0.6, T45),
                item("golden_apple", 1, 2, 3.0, T5)],
    "leatherworker": [item("bundle", 1, 1, 1.0, T23), item("saddle", 1, 1, 3.0, T34),
                      loot("minecraft:chests/village/village_tannery", 3, T34), item("diamond_horse_armor", 1, 1, 5.0, T45),
                      item("wolf_armor", 1, 1, 4.0, T45)],
    "mason": [item("bricks", 16, 32, 0.08, T23), item("quartz_block", 8, 16, 0.3, T34),
              loot("minecraft:chests/village/village_mason", 3, T34), item("sea_lantern", 4, 8, 1.0, T45),
              item("amethyst_block", 8, 16, 0.4, T45)],
    "fletcher": [item("arrow", 16, 32, 0.06, T23), loot("minecraft:chests/village/village_fletcher", 3, T34),
                 enchant("bow", 10, 18, 0.3, T3), item("spectral_arrow", 16, 32, 0.3, T45),
                 enchant("crossbow", 20, 30, 0.3, (4, 4)), enchant("bow", 25, 30, 0.35, T5, True),
                 enchant("crossbow", 25, 30, 0.35, T5, True)],
    "nitwit": [item("cookie", 4, 8, 0.1, (1, 5)), item("poppy", 1, 1, 0.05, (1, 5)), item("music_disc_cat", 1, 1, 2.0, (1, 5))],
}


# ---- the packs (TIERS.md section 4, plus actions, visits and taming placed where they fit) ----

PACKS = {
    "farmer": {
        1: [D("wheat", 32, 64, 0.05), D("potato", 32, 64, 0.04), D("carrot", 32, 64, 0.04), D("beetroot", 24, 48, 0.06),
            D("sugar_cane", 24, 48, 0.05), K("rabbit", 4, 8, 0.25, 6),
            A([{"plant": "minecraft:wheat_seeds"}], 16, 32, 0.03, 6), A([{"bonemeal": ""}], 16, 32, 0.04, 6)],
        2: [D("bread", 16, 32, 0.2), D("pumpkin", 8, 16, 0.25), D("melon", 8, 16, 0.25), D("egg", 16, 32, 0.08),
            D("hay_block", 4, 8, 0.5), D("apple", 4, 8, 0.15, 6), K("zombie", 8, 12, 0.35),
            A([{"breed": "minecraft:chicken"}], 2, 4, 0.4, 6)],
        3: [O("winter_stock"), D("cake", 2, 4, 2.0), D("hay_block", 8, 16, 0.5), D("pumpkin_pie", 6, 12, 0.4),
            K("spider", 8, 12, 0.35)],
        4: [D("golden_carrot", 8, 16, 1.0), D("glistering_melon_slice", 8, 16, 1.0), O("beekeeper"),
            K("pillager", 6, 10, 1.0)],
        5: [D("golden_apple", 4, 8, 3.0), O("ancient_seeds"), K("ravager", 1, 2, 8.0)],
    },
    "butcher": {
        1: [D("chicken", 12, 24, 0.08), D("porkchop", 12, 24, 0.15), D("beef", 12, 24, 0.12), D("mutton", 12, 24, 0.15),
            A([{"breed": "minecraft:cow"}], 2, 4, 0.5, 6), A([{"breed": "minecraft:pig"}], 2, 4, 0.5, 6)],
        2: [D("cooked_chicken", 16, 32, 0.15), D("cooked_beef", 16, 32, 0.2), D("cooked_porkchop", 16, 32, 0.2),
            D("cooked_mutton", 16, 32, 0.2), K("spider", 6, 10, 0.35)],
        3: [D("rabbit_stew", 3, 6, 0.8), D("mushroom_stew", 6, 12, 0.3), D("beetroot_soup", 6, 12, 0.3),
            O("smiths_lunch"), K("zombie", 10, 16, 0.35)],
        4: [O("feast"), D("golden_carrot", 16, 32, 1.0), D("suspicious_stew", 4, 8, 0.5), K("hoglin", 4, 8, 1.2)],
        5: [O("wedding"), K("hoglin", 10, 16, 1.2), D("golden_carrot", 32, 48, 1.0, 6)],
    },
    "fisherman": {
        1: [D("cod", 16, 32, 0.08), D("salmon", 12, 24, 0.1), D("kelp", 32, 64, 0.04), D("string", 16, 32, 0.06)],
        2: [D("cooked_cod", 16, 32, 0.12), D("cooked_salmon", 16, 32, 0.15), D("tropical_fish", 6, 12, 0.2),
            D("lily_pad", 8, 16, 0.12), K("drowned", 6, 10, 0.45), O("fish_market"),
            A([{"fill_bucket": "minecraft:tropical_fish_bucket"}, {"deliver": "minecraft:tropical_fish_bucket"}], 1, 2, 1.2, 6)],
        3: [D("pufferfish", 6, 12, 0.3), D("sea_pickle", 8, 16, 0.15), D("prismarine_shard", 16, 32, 0.25),
            D("ink_sac", 12, 24, 0.2), K("guardian", 4, 8, 1.5), A([{"visit_structure": "#minecraft:shipwreck"}], 1, 1, 3.0, 5)],
        4: [D("nautilus_shell", 2, 4, 3.0), D("wet_sponge", 2, 4, 3.0), D("prismarine_crystals", 16, 32, 0.3),
            K("guardian", 10, 16, 1.5), A([{"fill_bucket": "minecraft:axolotl_bucket"}], 1, 1, 3.0, 5)],
        5: [D("heart_of_the_sea", 1, 1, 12.0), D("trident", 1, 1, 15.0), K("elder_guardian", 1, 1, 20.0)],
    },
    "shepherd": {
        1: [D("#minecraft:wool", 32, 64, 0.05), D("white_dye", 12, 24, 0.15), D("wheat", 32, 64, 0.05),
            D("shears", 1, 2, 0.6, 6), A([{"breed": "minecraft:sheep"}], 2, 4, 0.5)],
        2: [D("#minecraft:wool_carpets", 16, 32, 0.05), D("#minecraft:beds", 2, 4, 1.0), O("color_set"),
            D("lead", 2, 4, 0.4), K("spider", 6, 10, 0.35)],
        3: [D("#minecraft:banners", 4, 8, 0.8), D("painting", 4, 8, 0.5), O("new_home"), K("zombie", 10, 16, 0.35),
            A([{"tame": "minecraft:wolf"}], 1, 1, 1.5, 5)],
        4: [D("pink_wool", 16, 32, 0.15), D("purple_wool", 16, 32, 0.15), D("light_blue_wool", 16, 32, 0.15),
            D("flower_banner_pattern", 1, 1, 2.0), K("phantom", 4, 8, 0.8)],
        5: [D("skull_banner_pattern", 1, 1, 8.0), D("creeper_banner_pattern", 1, 1, 6.0),
            A([{"breed": "minecraft:sheep"}], 8, 12, 0.5, 6)],
    },
    "librarian": {
        1: [D("paper", 32, 64, 0.05), D("feather", 16, 32, 0.08), D("ink_sac", 8, 16, 0.2), D("sugar_cane", 32, 64, 0.05)],
        2: [D("book", 8, 16, 0.3), D("candle", 6, 12, 0.2), D("lantern", 4, 8, 0.4),
            A([{"enchant": "", "min_level": 10}], 1, 2, 2.0, 6)],
        3: [D("bookshelf", 6, 12, 1.2), D("writable_book", 4, 8, 0.6), O("reading_room"), K("witch", 2, 4, 1.5)],
        4: [D("ender_pearl", 4, 8, 1.0), D("glow_ink_sac", 8, 16, 0.4), D("clock", 2, 4, 1.5), K("enderman", 4, 8, 1.2),
            A([{"enchant": "", "min_level": 30}], 1, 1, 5.0, 5)],
        5: [D("ender_eye", 6, 12, 2.0), K("evoker", 1, 2, 8.0), A([{"enchant": "", "min_level": 30}], 2, 3, 5.0, 6)],
    },
    "cartographer": {
        1: [D("paper", 32, 64, 0.05), D("glass", 16, 32, 0.1), D("sugar_cane", 32, 64, 0.05),
            A([{"visit_structure": "#minecraft:ruined_portal"}], 1, 1, 2.5, 6),
            A([{"visit_structure": "#minecraft:abandoned_camp"}], 1, 1, 2.5, 6),
            A([{"visit_biome": "minecraft:cherry_grove"}], 1, 1, 2.5, 5)],
        2: [D("compass", 2, 4, 1.0), D("map", 4, 8, 0.5), D("glass_pane", 16, 32, 0.1), K("phantom", 4, 6, 0.8),
            A([{"visit_structure": "minecraft:pillager_outpost"}], 1, 1, 4.0, 6),
            A([{"visit_structure": "minecraft:igloo"}], 1, 1, 4.0, 5),
            A([{"visit_structure": "minecraft:desert_pyramid"}], 1, 1, 4.0, 5),
            A([{"visit_biome": "minecraft:ice_spikes"}], 1, 1, 4.0, 5),
            A([{"visit_biome": "minecraft:badlands"}], 1, 1, 4.0, 5),
            A([{"tame": "minecraft:parrot"}], 1, 1, 3.0, 4), A([{"tame": "minecraft:horse"}], 1, 1, 3.0, 4)],
        3: [D("spyglass", 1, 2, 2.0), D("amethyst_shard", 8, 16, 0.3), O("expedition_kit"), K("pillager", 6, 10, 1.0),
            A([{"visit_structure": "minecraft:trail_ruins"}], 1, 1, 4.0, 6)],
        4: [D("clock", 2, 4, 1.5), D("copper_block", 16, 32, 0.9), K("witch", 2, 4, 1.5),
            A([{"visit_structure": "minecraft:monument"}], 1, 1, 8.0, 6),
            A([{"visit_structure": "minecraft:trial_chambers"}], 1, 1, 7.0, 6),
            A([{"visit_biome": "minecraft:mushroom_fields"}], 1, 1, 8.0, 5)],
        5: [D("recovery_compass", 1, 1, 12.0), K("evoker", 1, 1, 8.0),
            A([{"visit_structure": "minecraft:mansion"}], 1, 1, 12.0)],
    },
    "cleric": {
        1: [D("rotten_flesh", 32, 64, 0.04), D("bone", 16, 32, 0.08), D("redstone", 16, 32, 0.08), K("zombie", 8, 12, 0.35)],
        2: [D("spider_eye", 8, 16, 0.15), D("lapis_lazuli", 16, 32, 0.15), D("gold_ingot", 6, 12, 0.5),
            K("skeleton", 8, 12, 0.45), K("drowned", 6, 10, 0.45)],
        3: [D("gunpowder", 12, 24, 0.2), D("glowstone_dust", 16, 32, 0.12), D("rabbit_foot", 2, 4, 0.6),
            K("witch", 2, 4, 1.5), K("phantom", 4, 8, 0.8), A([{"brew": "minecraft:healing"}], 2, 3, 1.5, 6),
            A([{"cure": ""}], 1, 1, 8.0, 5), O("healers_kit")],
        4: [O("potions"), D("blaze_rod", 4, 8, 1.5), K("blaze", 6, 10, 1.5), K("enderman", 3, 6, 1.2)],
        5: [D("ghast_tear", 4, 8, 2.0), K("wither_skeleton", 6, 10, 2.5), A([{"cure": ""}], 2, 2, 8.0, 5)],
    },
    "armorer": {
        1: [D("coal", 32, 64, 0.08), D("copper_ingot", 32, 64, 0.1), D("leather", 12, 24, 0.2)],
        2: [D("iron_ingot", 16, 32, 0.3), D("lava_bucket", 2, 4, 1.0), K("skeleton", 8, 12, 0.45),
            R("copper_helmet", 2, (8, 11)), R("copper_boots", 2, (8, 11))],
        3: [D("gold_ingot", 12, 24, 0.5), D("iron_block", 4, 8, 2.7), D("obsidian", 8, 16, 0.8), O("guard_gear"),
            K("pillager", 6, 10, 1.0), A([{"summon": "minecraft:iron_golem"}], 1, 1, 6.0, 5),
            R("iron_helmet", 2, (10, 13)), R("iron_chestplate", 2, (10, 13))],
        4: [D("diamond", 4, 8, 3.0), K("vindicator", 3, 6, 2.0), R("diamond_helmet", 2, (15, 18)),
            R("diamond_chestplate", 2, (15, 18))],
        5: [O("smith_order"), K("ravager", 2, 2, 8.0), R("netherite_helmet", 2, (18, 21)),
            R("netherite_chestplate", 2, (18, 21))],
    },
    "weaponsmith": {
        1: [D("coal", 32, 64, 0.08), D("flint", 16, 32, 0.05), D("stick", 32, 64, 0.03), R("stone_sword", 3, (5, 8))],
        2: [D("iron_ingot", 16, 32, 0.3), D("bone", 16, 32, 0.08), K("skeleton", 8, 12, 0.45), K("zombie", 10, 16, 0.35),
            R("copper_sword", 3, (8, 11))],
        3: [D("iron_block", 4, 8, 2.7), D("gold_ingot", 12, 24, 0.5), K("creeper", 6, 10, 0.6), K("witch", 2, 4, 1.5),
            O("armory"), R("iron_sword", 3, (10, 13)), R("iron_axe", 2, (10, 13)),
            A([{"enchant": "", "min_level": 15}], 1, 1, 3.0, 5)],
        4: [D("diamond", 4, 8, 3.0), K("vindicator", 4, 8, 2.0), K("enderman", 4, 8, 1.2), R("diamond_sword", 2, (15, 18))],
        5: [D("netherite_scrap", 2, 4, 8.0), K("evoker", 2, 2, 8.0), K("wither_skeleton", 6, 10, 2.5),
            R("netherite_sword", 2, (18, 21))],
    },
    "toolsmith": {
        1: [D("coal", 32, 64, 0.08), D("flint", 32, 64, 0.04), D("copper_ingot", 32, 64, 0.1), R("stone_pickaxe", 3, (5, 8))],
        2: [D("iron_ingot", 16, 32, 0.3), D("redstone", 16, 32, 0.08), K("spider", 8, 12, 0.35),
            A([{"mine": "#minecraft:iron_ores"}, {"deliver": "minecraft:raw_iron"}], 8, 16, 0.4, 6),
            R("copper_pickaxe", 3, (8, 11)), R("copper_shovel", 3, (8, 11))],
        3: [D("coal_block", 8, 16, 0.7), D("gold_ingot", 12, 24, 0.5), K("cave_spider", 8, 12, 0.45),
            K("silverfish", 8, 12, 0.3), R("iron_pickaxe", 2, (10, 13)), R("iron_shovel", 3, (10, 13))],
        4: [D("diamond", 3, 6, 3.0), D("amethyst_shard", 16, 32, 0.3), O("tool_set"), R("diamond_pickaxe", 2, (15, 18)),
            R("diamond_shovel", 2, (15, 18))],
        5: [D("echo_shard", 4, 8, 3.0), D("diamond", 8, 16, 3.0), R("netherite_pickaxe", 2, (18, 21))],
    },
    "leatherworker": {
        1: [D("leather", 16, 32, 0.2), D("flint", 32, 64, 0.04), D("string", 16, 32, 0.06),
            A([{"breed": "minecraft:cow"}], 2, 4, 0.5)],
        2: [D("rabbit_hide", 12, 24, 0.12), D("leather_chestplate", 2, 4, 0.8), K("spider", 6, 10, 0.35),
            A([{"tame": "minecraft:horse"}], 1, 1, 3.0, 5), A([{"tame": "minecraft:donkey"}], 1, 1, 2.5, 5),
            A([{"tame": "minecraft:llama"}], 1, 1, 2.5, 5)],
        3: [D("rabbit_foot", 3, 6, 0.6), D("turtle_scute", 4, 8, 0.3), D("saddle", 1, 2, 2.0), K("husk", 8, 12, 0.4),
            O("riders_set")],
        4: [D("phantom_membrane", 6, 12, 0.6), D("armadillo_scute", 8, 16, 0.4), K("phantom", 6, 10, 0.8)],
        5: [O("armored_stable"), D("wolf_armor", 1, 2, 3.0), D("phantom_membrane", 16, 24, 0.6, 6)],
    },
    "mason": {
        1: [D("stone", 64, 128, 0.03), D("clay_ball", 16, 32, 0.1), D("deepslate", 64, 128, 0.03)],
        2: [D("brick", 32, 64, 0.08), D("polished_granite", 32, 64, 0.06), D("polished_andesite", 32, 64, 0.06),
            D("polished_diorite", 32, 64, 0.06), K("silverfish", 6, 10, 0.3), O("town_hall"),
            A([{"station": "minecraft:stone_bricks"}, {"deliver": "minecraft:stone_bricks"}], 16, 32, 0.08, 6)],
        3: [D("calcite", 16, 32, 0.1), D("tuff_bricks", 32, 64, 0.08), D("quartz_block", 8, 16, 0.4), K("creeper", 6, 10, 0.6)],
        4: [D("white_glazed_terracotta", 8, 16, 0.3), D("dripstone_block", 16, 32, 0.1), D("prismarine_bricks", 16, 32, 0.3)],
        5: [D("purpur_block", 32, 64, 0.3), D("obsidian", 32, 32, 0.8), D("prismarine_bricks", 32, 64, 0.3, 6)],
    },
    "fletcher": {
        1: [D("stick", 32, 64, 0.03), D("flint", 32, 64, 0.05), D("feather", 16, 32, 0.06),
            A([{"craft": "minecraft:arrow"}, {"deliver": "minecraft:arrow"}], 16, 32, 0.05), R("bow", 3, (8, 12))],
        2: [D("string", 24, 48, 0.06), D("tripwire_hook", 8, 16, 0.15), D("arrow", 32, 64, 0.05), K("skeleton", 8, 12, 0.45)],
        3: [D("bow", 2, 4, 0.6), D("crossbow", 1, 2, 1.2), D("bamboo", 32, 64, 0.04), K("bogged", 4, 8, 0.5),
            K("stray", 4, 8, 0.5), O("hunters_pack"), R("crossbow", 2, (8, 12))],
        4: [D("spectral_arrow", 16, 32, 0.3), K("pillager", 6, 10, 1.0), R("crossbow", 3, (15, 18))],
        5: [D("wind_charge", 16, 32, 0.5), K("breeze", 6, 10, 2.0), R("bow", 3, (18, 21))],
    },
}

# The nitwit does not trade, so it has no packs: the same light errands at every tier.
NITWIT = [D("#minecraft:small_flowers", 1, 3, 0.15), D("sugar_cane", 3, 6, 0.06), D("cookie", 2, 5, 0.1),
          D("apple", 1, 3, 0.15), D("slime_ball", 1, 3, 0.25, 5), D("honey_bottle", 1, 2, 0.4, 5), K("slime", 2, 4, 0.35, 5),
          D("pumpkin_pie", 1, 2, 0.4), D("glow_berries", 2, 5, 0.15), D("sunflower", 1, 2, 0.15), D("egg", 2, 4, 0.08),
          D("melon_slice", 3, 6, 0.06), A([{"tame": "minecraft:cat"}], 1, 1, 1.0), O("picnic")]


# ---------------------------------------------------------------------------------------------------------------
# Action objectives: a delivery becomes "do the action + bring the result" where an action fits.
# Recipes are read from the jar to decide between crafting and smelting.
# ---------------------------------------------------------------------------------------------------------------

CROPS = {"wheat": "wheat", "potato": "potatoes", "carrot": "carrots", "beetroot": "beetroots",
         "nether_wart": "nether_wart", "cocoa_beans": "cocoa"}
FISH = {"cod", "salmon", "tropical_fish", "pufferfish"}
# Recipes exist only from their own storage block; no fitting action can be counted.
DELIVER_ONLY = {"melon", "honey_bottle"}
ALWAYS_SMELT = {"iron_ingot", "gold_ingot", "copper_ingot", "glass", "stone", "smooth_stone", "brick", "netherite_scrap",
                "deepslate"}
# item -> (mob, kills per item brought, value of one kill)
MOB_DROPS = {
    "leather": ("cow", 0.5, 0.2), "string": ("spider", 0.5, 0.35), "feather": ("chicken", 0.5, 0.1),
    "rotten_flesh": ("zombie", 0.5, 0.35), "bone": ("skeleton", 0.5, 0.45), "spider_eye": ("spider", 1.0, 0.35),
    "gunpowder": ("creeper", 0.6, 0.6), "ender_pearl": ("enderman", 1.0, 1.2), "blaze_rod": ("blaze", 1.0, 1.5),
    "phantom_membrane": ("phantom", 1.0, 0.8), "ink_sac": ("squid", 0.4, 0.2), "glow_ink_sac": ("glow_squid", 0.4, 0.3),
    "slime_ball": ("slime", 0.5, 0.35), "prismarine_shard": ("guardian", 0.5, 1.5), "rabbit_hide": ("rabbit", 1.0, 0.25),
    "rabbit_foot": ("rabbit", 2.0, 0.25), "ghast_tear": ("ghast", 1.0, 2.0),
    "beef": ("cow", 0.5, 0.2), "chicken": ("chicken", 1.0, 0.1), "porkchop": ("pig", 0.5, 0.2),
    "mutton": ("sheep", 0.5, 0.2), "rabbit": ("rabbit", 1.0, 0.25), "prismarine_crystals": ("guardian", 0.5, 1.5),
}
# item -> (block or #tag to mine, blocks per item)
MINED = {
    "coal": ("#minecraft:coal_ores", 1.0), "diamond": ("#minecraft:diamond_ores", 1.0),
    "redstone": ("#minecraft:redstone_ores", 0.25), "lapis_lazuli": ("#minecraft:lapis_ores", 0.2),
    "quartz": ("minecraft:nether_quartz_ore", 1.0), "granite": ("minecraft:granite", 1.0),
    "andesite": ("minecraft:andesite", 1.0), "diorite": ("minecraft:diorite", 1.0), "tuff": ("minecraft:tuff", 1.0),
    "calcite": ("minecraft:calcite", 1.0), "dripstone_block": ("minecraft:dripstone_block", 1.0),
    "clay_ball": ("minecraft:clay", 0.25), "amethyst_shard": ("minecraft:amethyst_cluster", 0.25),
    "melon_slice": ("minecraft:melon", 0.2), "obsidian": ("minecraft:obsidian", 1.0),
    "glowstone_dust": ("minecraft:glowstone", 0.33),
}


def load_recipes():
    """result item -> set of 'craft' / 'smelt' / 'station'"""
    kinds = {}
    z = zipfile.ZipFile(JAR)
    for n in z.namelist():
        if not (n.startswith("data/minecraft/recipe/") and n.endswith(".json")):
            continue
        r = json.loads(z.read(n))
        t = r.get("type", "")
        res = r.get("result")
        rid = res.get("id") if isinstance(res, dict) else res
        if not isinstance(rid, str):
            continue
        it = rid.split(":")[-1]
        if t in ("minecraft:crafting_shaped", "minecraft:crafting_shapeless", "minecraft:crafting_transmute"):
            kinds.setdefault(it, set()).add("craft")
        elif t in ("minecraft:smelting", "minecraft:smoking", "minecraft:blasting"):
            kinds.setdefault(it, set()).add("smelt")
        elif t in ("minecraft:stonecutting", "minecraft:smithing_transform"):
            kinds.setdefault(it, set()).add("station")
    return kinds


RECIPES = None


def to_actions(task):
    """A single delivery task becomes an action + delivery task where an action fits; others are returned as is."""
    global RECIPES
    if RECIPES is None:
        RECIPES = load_recipes()
    if "deliver" not in task or task["deliver"].startswith("#"):
        if task.get("deliver") == "#minecraft:wool":
            return with_objectives(task, [{"shear": "minecraft:sheep", "factor": 0.5}, {"deliver": "#minecraft:wool"}], 1.0, 0.05)
        if task.get("deliver") in ("#minecraft:beds", "#minecraft:wool_carpets", "#minecraft:banners"):
            return with_objectives(task, [{"craft": task["deliver"]}, {"deliver": task["deliver"]}], 1.15)
        return task
    target = task["deliver"]
    it = target.split(":")[1]
    if it == "lava_bucket":
        return with_objectives(task, [{"fill_bucket": target}, {"deliver": target}], 1.1)
    if it in CROPS:
        return with_objectives(task, [{"harvest": "minecraft:" + CROPS[it]}, {"deliver": target}], 1.2)
    if it in FISH:
        return with_objectives(task, [{"catch": target}, {"deliver": target}], 1.25)
    # How an item is normally obtained wins over a recipe: leather has a recipe (from rabbit hide) and coal,
    # diamonds or slime balls can be taken back out of their blocks, but they come from mobs or mining.
    if it in DELIVER_ONLY:
        return task
    if it in MOB_DROPS:
        mob, factor, kill_value = MOB_DROPS[it]
        extra = factor * kill_value
        return with_objectives(task, [{"kill": "minecraft:" + mob, "factor": factor}, {"deliver": target}], None, extra)
    if it in MINED:
        block, factor = MINED[it]
        return with_objectives(task, [{"mine": block, "factor": factor}, {"deliver": target}], 1.2)
    kinds = RECIPES.get(it, set())
    if it in ALWAYS_SMELT or ("smelt" in kinds and "craft" not in kinds):
        return with_objectives(task, [{"smelt": target}, {"deliver": target}], 1.2)
    if "craft" in kinds:
        return with_objectives(task, [{"craft": target}, {"deliver": target}], 1.15)
    return task


def with_objectives(task, objectives, value_factor, value_add=0.0):
    out = {k: v for k, v in task.items() if k != "deliver"}
    out["objectives"] = objectives
    value = task["value"] * (value_factor or 1.0) + value_add
    out["value"] = round(value, 3)
    return out


# ---- orders: several things at once, each made and brought. (action, item, count, value per item) ----
ORDERS = {
    "winter_stock": [("craft", "bread", 16, 0.2), ("smelt", "baked_potato", 16, 0.1), ("craft", "pumpkin_pie", 8, 0.4)],
    "beekeeper": [("deliver", "honey_bottle", 8, 0.4), ("deliver", "honeycomb", 8, 0.3), ("craft", "candle", 4, 0.2)],
    "ancient_seeds": [("deliver", "sniffer_egg", 1, 15.0), ("deliver", "torchflower_seeds", 4, 1.5)],
    "feast": [("smelt", "cooked_porkchop", 32, 0.2), ("craft", "pumpkin_pie", 8, 0.4), ("craft", "cake", 2, 2.0)],
    "smiths_lunch": [("smelt", "cooked_beef", 16, 0.2), ("smelt", "baked_potato", 16, 0.1), ("craft", "bread", 16, 0.2)],
    "wedding": [("craft", "cake", 4, 2.0), ("craft", "rabbit_stew", 8, 0.8), ("craft", "golden_carrot", 32, 1.0),
                ("craft", "glistering_melon_slice", 8, 1.0)],
    "fish_market": [("catch", "cod", 16, 0.1), ("catch", "salmon", 8, 0.12), ("catch", "tropical_fish", 2, 0.25)],
    "color_set": [("deliver", "red_wool", 8, 0.1), ("deliver", "yellow_wool", 8, 0.1), ("deliver", "blue_wool", 8, 0.12),
                  ("deliver", "green_wool", 8, 0.12)],
    "new_home": [("craft", "#minecraft:beds", 4, 1.0), ("craft", "#minecraft:wool_carpets", 16, 0.15), ("craft", "painting", 2, 0.5)],
    "reading_room": [("craft", "bookshelf", 8, 1.2), ("craft", "lectern", 2, 1.5), ("craft", "lantern", 4, 0.4)],
    "expedition_kit": [("craft", "map", 4, 0.6), ("craft", "compass", 2, 1.0), ("craft", "spyglass", 1, 2.0)],
    "healers_kit": [("brew", "minecraft:healing", 2, 1.5), ("craft", "glass_bottle", 6, 0.12), ("craft", "golden_apple", 1, 3.0)],
    "potions": [("craft", "blaze_powder", 4, 0.8), ("deliver", "nether_wart", 16, 0.1), ("craft", "glass_bottle", 12, 0.12)],
    "guard_gear": [("craft", "shield", 2, 1.5), ("craft", "iron_helmet", 1, 1.5), ("craft", "iron_boots", 1, 1.2)],
    "smith_order": [("deliver", "diamond", 16, 3.0), ("smelt", "netherite_scrap", 2, 8.0)],
    "armory": [("craft", "iron_sword", 2, 0.8), ("craft", "bow", 1, 0.6), ("craft", "arrow", 32, 0.06)],
    "tool_set": [("craft", "iron_pickaxe", 2, 1.0), ("craft", "iron_shovel", 2, 0.4), ("craft", "iron_axe", 2, 1.0)],
    "town_hall": [("station", "stone_bricks", 32, 0.06), ("craft", "bricks", 16, 0.3), ("station", "polished_andesite", 16, 0.06)],
    "hunters_pack": [("craft", "bow", 1, 0.6), ("craft", "crossbow", 1, 1.2), ("craft", "arrow", 32, 0.06)],
    "riders_set": [("craft", "saddle", 1, 2.0), ("craft", "leather_horse_armor", 1, 1.5), ("craft", "lead", 2, 0.4)],
    "armored_stable": [("deliver", "armadillo_scute", 24, 0.4), ("deliver", "leather", 64, 0.2), ("craft", "saddle", 2, 2.0)],
    "picnic": [("craft", "cookie", 8, 0.1), ("deliver", "apple", 4, 0.15), ("craft", "cake", 1, 2.0)],
}
ORDER_BONUS = 1.3


def order_task(name):
    objectives = []
    total = 0.0
    for action, it, count, value in ORDERS[name]:
        target = mc(it)
        objectives.append({action: target, "count": [count, count]})
        if action not in ("deliver", "brew"):
            objectives.append({"deliver": target, "count": [count, count]})
        total += count * value
    return {"title": "order." + name, "objectives": objectives, "count": [1, 1], "value": round(total * ORDER_BONUS, 2),
            "weight": 6}


# ---- reforge: the value of the materials in one piece ----
MATERIAL = {"stone": 0.05, "copper": 0.12, "iron": 0.25, "diamond": 1.0}
# A netherite piece is a diamond piece plus a netherite ingot (and a smithing template, used up too).
NETHERITE_EXTRA = 10.0
PIECE = {"sword": 2, "axe": 3, "pickaxe": 3, "shovel": 1, "helmet": 5, "chestplate": 8, "leggings": 7, "boots": 4}
PIECE_VALUE = {"bow": 0.6, "crossbow": 1.2}


def reforge_task(name, count, levels):
    action = "craft"
    if name in PIECE_VALUE:
        each = PIECE_VALUE[name]
    else:
        material, piece = name.split("_", 1)
        if material == "netherite":
            # Netherite gear is made on the smithing table, which counts as a station.
            each = MATERIAL["diamond"] * PIECE[piece] + NETHERITE_EXTRA
            action = "station"
        else:
            each = MATERIAL[material] * PIECE[piece]
    target = mc(name)
    return {"objectives": [{action: target, "count": [count, count]}, {"deliver": target, "count": [count, count]}],
            "count": [1, 1], "value": round(count * each, 2), "weight": 8,
            # Every enchantment it gets is at its highest level (user, 2026-10-13); how many it gets still depends on
            # the enchanting levels.
            "reward": {"enchant": target, "levels": list(levels), "max_levels": True}}


def add_top_rewards():
    """Every trading profession has its top reward (TopRewards.java) in the T5 pack."""
    for prof in PACKS:
        REWARDS[prof].append({"top": True, "value": 15.0, "min_tier": 5, "max_tier": 5})


def build(entry, tiers):
    if isinstance(entry, tuple) and entry[0] == "order":
        task = order_task(entry[1])
    elif isinstance(entry, tuple) and entry[0] == "reforge":
        task = reforge_task(*entry[1:])
    else:
        task = to_actions(dict(entry))
    task["tiers"] = list(tiers)
    return task


# ---- check every id against the game ----

def known_ids():
    names = json.load(open(NAMES, encoding="utf-8"))
    items, entities = set(), set()
    for k in names:
        parts = k.split(".")
        if len(parts) == 3 and parts[0] in ("item", "block"):
            items.add("minecraft:" + parts[2])
        elif len(parts) == 3 and parts[0] == "entity":
            entities.add("minecraft:" + parts[2])
    return items, entities


def check(prof, task, items, entities):
    bad = []
    for o in task.get("objectives", [task]):
        for key in ("deliver", "craft", "smelt", "station", "fill_bucket"):
            t = o.get(key)
            if t and not t.startswith("#") and t not in items:
                bad.append(t)
        for key in ("kill", "breed", "tame", "shear", "summon"):
            t = o.get(key)
            if t and t not in entities:
                bad.append(t)
    if task.get("reward") and task["reward"]["enchant"] not in items:
        bad.append(task["reward"]["enchant"])
    if bad:
        raise SystemExit(f"{prof}: unknown ids {bad}")


def main():
    os.makedirs(OUT, exist_ok=True)
    add_top_rewards()
    items, entities = known_ids()
    pools = dict(PACKS)
    for prof in list(pools) + ["nitwit"]:
        if prof == "nitwit":
            tasks = [build(e, (1, 5)) for e in NITWIT]
        else:
            tasks = [build(e, (tier, tier)) for tier, entries in sorted(pools[prof].items()) for e in entries]
        for t in tasks:
            check(prof, t, items, entities)
        for r in REWARDS[prof]:
            rid = r.get("item") or r.get("enchant")
            if r.get("top"):
                continue
            if rid and rid not in items:
                raise SystemExit(f"{prof}: unknown reward {rid}")
        doc = {"profession": "minecraft:" + prof, "tasks": tasks, "rewards": REWARDS[prof]}
        with open(os.path.join(OUT, prof + ".json"), "w", encoding="utf-8") as f:
            json.dump(doc, f, indent=2)
            f.write("\n")
        per_tier = {}
        for t in tasks:
            per_tier[t["tiers"][0]] = per_tier.get(t["tiers"][0], 0) + 1
        print(f"{prof:14} " + " ".join(f"T{k}:{v}" for k, v in sorted(per_tier.items())))


if __name__ == "__main__":
    main()
