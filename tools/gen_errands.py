"""Generates the built-in errand pools in src/main/resources/data/villageerrands/village_errands/.

Each profession has five packs (tiers T1-T5, TIERS.md section 4). A villager offers errands only from the pack of the
player's tier with that villager.

Effort model (user, 2026-10-13): a task's "value" is MINUTES OF WORK per unit for a player of that stage who has
the usual farms. Farmable things are cheap and live in the low packs; the top packs are actions that can't be taken
out of a chest (rare hunts, fishing, exploring, curing, brewing) and truly rare items. Counts are sized so that one
errand takes the minutes of its tier window (WINDOW); a task that can't fit its window fails the build. Daily errands
pay emeralds, XP and reputation only (the mod turns minutes into pay); items are one-time milestones (trials).
Run: python tools/gen_errands.py
"""
import json
import math
import os
import zipfile

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "villageerrands", "village_errands")
JAR = os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/26.3/minecraft-common-deobf-26.3.jar")
NAMES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "villageerrands", "vanilla_names", "en_us.json")

# Minutes one daily errand takes, by pack.
WINDOW = {1: (4, 8), 2: (8, 12), 3: (12, 18), 4: (18, 25), 5: (25, 35)}
NITWIT_WINDOW = (1, 4)
# Emeralds' worth per minute by pack (ErrandsConfig.tierEmeraldsPerMinute), only for the report.
RATE = {1: 0.6, 2: 0.8, 3: 1.0, 4: 1.3, 5: 1.6}
# What one objective may ask for at most (the mod caps the same way): four stacks of an item, 64 of anything else.
# Unstackable items: at most 16 (they take a slot each).
STACK1 = {"cake", "rabbit_stew", "mushroom_stew", "beetroot_soup", "suspicious_stew", "lava_bucket", "saddle",
          "trident", "shield", "bow", "crossbow", "spyglass", "recovery_compass", "wolf_armor", "iron_helmet",
          "iron_boots", "iron_sword", "iron_pickaxe", "iron_shovel", "iron_axe", "leather_horse_armor",
          "leather_chestplate", "writable_book", "tropical_fish_bucket", "axolotl_bucket", "shears",
          "skull_banner_pattern", "creeper_banner_pattern", "flower_banner_pattern", "piglin_banner_pattern", "#minecraft:beds",
          "heart_of_the_sea"}
STACK16 = {"egg", "ender_pearl", "#minecraft:banners", "painting", "honey_bottle", "wind_charge"}


def mc(i):
    return i if ":" in i else "minecraft:" + i


# ---- task helpers: `minutes` per unit; the count comes from the tier window, or `fixed` (lo, hi) ----

def D(i, minutes, weight=10, fixed=None):
    """Bring an item (turned into "do the action + bring" where an action fits, see to_actions)."""
    return {"deliver": mc(i), "minutes": minutes, "weight": weight, "fixed": fixed}


def K(entity, minutes, weight=10, fixed=None):
    return {"kill": mc(entity), "minutes": minutes, "weight": weight, "fixed": fixed}


def A(objectives, minutes, weight=10, fixed=None):
    return {"objectives": objectives, "minutes": minutes, "weight": weight, "fixed": fixed}


def O(name):
    return ("order", name)


def R(i, count, levels):
    """Reforge (one-time per player): make `count` of an item and bring them, get one of the same, enchanted."""
    return ("reforge", i, count, levels)


# ---- milestone items: given once, with the first trust trial of a tier with a profession (never gear) ----

def item(i, lo, hi, value, tiers=(2, 4)):
    return {"item": mc(i), "count": [lo, hi], "value": value, "min_tier": tiers[0], "max_tier": tiers[1]}


def loot(table, value, tiers=(3, 4)):
    return {"loot_table": table, "value": value, "min_tier": tiers[0], "max_tier": tiers[1]}


def enchant(i, lo, hi, value_per_level, tiers=(3, 4), max_levels=False):
    out = {"enchant": mc(i), "levels": [lo, hi], "value_per_level": value_per_level, "min_tier": tiers[0], "max_tier": tiers[1]}
    if max_levels:
        out["max_levels"] = True
    return out


T2, T3, T4 = (2, 2), (3, 3), (4, 4)
# The unlocked pack decides which item: the trial into pack 2 gives a T2 item, and so on. Pack 5 gives the top reward.
REWARDS = {
    "farmer": [item("pumpkin_pie", 8, 16, 0.25, T2), item("golden_carrot", 16, 32, 0.6, T3), item("golden_apple", 3, 5, 3.0, T4)],
    "fisherman": [item("cooked_salmon", 16, 32, 0.15, T2), loot("minecraft:chests/village/village_fisher", 3, T3),
                  item("nautilus_shell", 2, 4, 3.0, T4)],
    "shepherd": [item("painting", 4, 8, 0.4, T2), loot("minecraft:chests/village/village_shepherd", 3, T3),
                 item("white_banner", 4, 8, 1.0, T4)],
    "librarian": [item("bookshelf", 4, 8, 1.0, T2), enchant("book", 15, 20, 0.4, T3), enchant("book", 25, 30, 0.4, T4)],
    "cartographer": [item("map", 2, 4, 0.6, T2), loot("villageerrands:top/map_buried_treasure", 3, T3),
                     loot("villageerrands:top/map_monument", 5, T4)],
    "cleric": [item("experience_bottle", 8, 16, 0.4, T2), loot("minecraft:chests/village/village_temple", 3, T3),
               item("experience_bottle", 32, 48, 0.4, T4)],
    "armorer": [item("iron_ingot", 16, 32, 0.3, T2), loot("minecraft:chests/village/village_armorer", 3, T3),
                item("diamond", 3, 5, 3.0, T4)],
    "weaponsmith": [item("iron_ingot", 16, 32, 0.3, T2), loot("minecraft:chests/village/village_weaponsmith", 3, T3),
                    item("diamond", 3, 5, 3.0, T4)],
    "toolsmith": [item("iron_ingot", 16, 32, 0.3, T2), loot("minecraft:chests/village/village_toolsmith", 3, T3),
                  item("diamond", 3, 5, 3.0, T4)],
    "butcher": [item("cooked_beef", 16, 32, 0.2, T2), loot("minecraft:chests/village/village_butcher", 3, T3),
                item("golden_carrot", 32, 48, 0.6, T4)],
    "leatherworker": [item("lead", 2, 4, 0.5, T2), item("saddle", 1, 1, 3.0, T3), item("name_tag", 1, 2, 4.0, T4)],
    "mason": [item("bricks", 32, 64, 0.08, T2), item("quartz_block", 16, 32, 0.3, T3), item("sea_lantern", 8, 16, 1.0, T4)],
    "fletcher": [item("arrow", 32, 64, 0.06, T2), loot("minecraft:chests/village/village_fletcher", 3, T3),
                 item("spectral_arrow", 32, 64, 0.3, T4)],
    "nitwit": [item("cookie", 4, 8, 0.1, (1, 5)), item("poppy", 1, 1, 0.05, (1, 5)), item("music_disc_cat", 1, 1, 2.0, (1, 5))],
}


# ---- the packs (TIERS.md section 4): minutes per unit, assuming the usual farms ----
# T1 overworld basics, T2 iron age, T3 diamonds / raids / enchanting, T4 the Nether, monuments, rare mobs,
# T5 the end game: bosses, ancient cities, mansions, bastions, the End.

PACKS = {
    "farmer": {
        1: [D("wheat", 0.025), D("potato", 0.025), D("carrot", 0.025), D("beetroot", 0.03), D("sugar_cane", 0.025),
            K("rabbit", 0.5, 6), A([{"plant": "minecraft:wheat_seeds"}], 0.02, 6), A([{"bonemeal": ""}], 0.02, 6)],
        2: [D("bread", 0.08), D("pumpkin", 0.12), D("melon", 0.15), D("egg", 0.2), D("hay_block", 0.25),
            K("zombie", 0.25), A([{"breed": "minecraft:chicken"}], 0.3, 6)],
        3: [O("winter_stock"), D("cake", 0.9), D("pumpkin_pie", 0.15), D("golden_carrot", 0.1), D("golden_apple", 0.4),
            K("spider", 0.25)],
        4: [D("glistering_melon_slice", 0.15), O("beekeeper"), K("pillager", 0.6), A([{"cure": ""}], 10.0, 6, (2, 2)),
            A([{"breed": "minecraft:bee"}], 1.0, 6)],
        5: [O("ancient_seeds"), A([{"breed": "minecraft:sniffer"}], 15.0, 10, (2, 2)), K("ravager", 8.0, 10, (4, 4)),
            A([{"cure": ""}], 10.0, 8, (3, 3))],
    },
    "butcher": {
        1: [D("chicken", 0.05), D("porkchop", 0.06), D("beef", 0.06), D("mutton", 0.06),
            A([{"breed": "minecraft:cow"}], 0.3, 6), A([{"breed": "minecraft:pig"}], 0.3, 6)],
        2: [D("cooked_chicken", 0.08), D("cooked_beef", 0.08), D("cooked_porkchop", 0.08), D("cooked_mutton", 0.08),
            D("mushroom_stew", 0.6), D("beetroot_soup", 0.6), K("spider", 0.25), A([{"breed": "minecraft:rabbit"}], 0.5, 6)],
        3: [D("rabbit_stew", 0.8), O("smiths_lunch"), K("zombie", 0.25), K("husk", 0.3)],
        4: [O("feast"), O("wedding"), K("hoglin", 0.6), A([{"breed": "minecraft:goat"}], 1.2, 6)],
        5: [K("piglin_brute", 5.0, 10, (6, 6)), K("ravager", 8.0, 10, (4, 4)),
            A([{"visit_structure": "minecraft:bastion_remnant"}], 30.0, 8, (1, 1))],
    },
    "fisherman": {
        1: [D("cod", 0.3), D("salmon", 0.35), D("kelp", 0.02), D("string", 0.03)],
        2: [D("cooked_cod", 0.35), D("cooked_salmon", 0.4), D("tropical_fish", 0.6), D("lily_pad", 0.1), K("drowned", 0.4),
            O("fish_market"), A([{"fill_bucket": "minecraft:tropical_fish_bucket"}, {"deliver": "minecraft:tropical_fish_bucket"}], 1.0, 6)],
        3: [D("pufferfish", 0.8), D("sea_pickle", 0.2), D("prismarine_shard", 0.3), D("ink_sac", 0.2), K("guardian", 0.4),
            A([{"visit_structure": "#minecraft:shipwreck"}], 15.0, 6, (1, 1))],
        4: [D("nautilus_shell", 3.0), D("prismarine_crystals", 0.3), A([{"fill_bucket": "minecraft:axolotl_bucket"}], 4.0, 6),
            A([{"visit_structure": "minecraft:monument"}], 22.0, 6, (1, 1)), A([{"catch": "minecraft:pufferfish"}], 1.0, 6)],
        5: [D("heart_of_the_sea", 30.0, 10, (1, 1)), D("trident", 30.0, 8, (1, 1)), K("elder_guardian", 30.0, 10, (1, 1)),
            D("wet_sponge", 3.0)],
    },
    "shepherd": {
        1: [D("#minecraft:wool", 0.03), D("white_dye", 0.05), D("wheat", 0.025), A([{"breed": "minecraft:sheep"}], 0.3, 8)],
        2: [D("#minecraft:wool_carpets", 0.05), D("#minecraft:beds", 0.6), O("color_set"), D("lead", 0.4), K("spider", 0.25)],
        3: [D("#minecraft:banners", 0.4), D("painting", 0.2), O("new_home"), A([{"tame": "minecraft:wolf"}], 2.5, 6)],
        4: [K("phantom", 0.8), O("rainbow"), A([{"breed": "minecraft:llama"}], 1.5, 6)],
        5: [D("skull_banner_pattern", 30.0, 10, (1, 1)), D("creeper_banner_pattern", 28.0, 10, (1, 1)),
            D("piglin_banner_pattern", 30.0, 8, (1, 1))],
    },
    "librarian": {
        1: [D("paper", 0.03), D("feather", 0.05), D("ink_sac", 0.2), D("sugar_cane", 0.025)],
        2: [D("book", 0.15), D("candle", 0.2), D("lantern", 0.1), A([{"enchant": "", "min_level": 10}], 2.0, 6)],
        3: [D("bookshelf", 0.6), D("writable_book", 0.9), O("reading_room"), K("witch", 1.5)],
        4: [D("ender_pearl", 0.5), D("glow_ink_sac", 0.4), D("clock", 0.4), A([{"enchant": "", "min_level": 30}], 4.0, 6)],
        5: [D("ender_eye", 1.0), K("evoker", 10.0, 10, (3, 3)), A([{"visit_structure": "minecraft:stronghold"}], 30.0, 8, (1, 1)),
            A([{"visit_structure": "minecraft:ancient_city"}], 30.0, 6, (1, 1))],
    },
    "cartographer": {
        1: [D("paper", 0.03), D("glass", 0.03), D("sugar_cane", 0.025),
            A([{"visit_structure": "#minecraft:ruined_portal"}], 6.0, 6, (1, 1)),
            A([{"visit_structure": "#minecraft:abandoned_camp"}], 6.0, 6, (1, 1)),
            A([{"visit_biome": "minecraft:cherry_grove"}], 6.0, 5, (1, 1))],
        2: [D("compass", 0.3), D("map", 0.3), D("glass_pane", 0.04), K("phantom", 0.8),
            A([{"visit_structure": "minecraft:pillager_outpost"}], 10.0, 6, (1, 1)),
            A([{"visit_structure": "minecraft:igloo"}], 12.0, 5, (1, 1)),
            A([{"visit_structure": "minecraft:desert_pyramid"}], 10.0, 5, (1, 1)),
            A([{"visit_biome": "minecraft:ice_spikes"}], 10.0, 5, (1, 1)),
            A([{"visit_biome": "minecraft:badlands"}], 10.0, 5, (1, 1)),
            A([{"tame": "minecraft:parrot"}], 10.0, 4, (1, 1)), A([{"tame": "minecraft:horse"}], 9.0, 4, (1, 1))],
        3: [D("spyglass", 0.9), D("amethyst_shard", 0.15), O("expedition_kit"), K("pillager", 0.6),
            A([{"visit_structure": "minecraft:trail_ruins"}], 15.0, 6, (1, 1))],
        4: [D("clock", 0.4), K("witch", 1.5), A([{"visit_structure": "minecraft:monument"}], 22.0, 6, (1, 1)),
            A([{"visit_structure": "minecraft:trial_chambers"}], 22.0, 6, (1, 1)),
            A([{"visit_biome": "minecraft:mushroom_fields"}], 22.0, 5, (1, 1))],
        5: [D("recovery_compass", 30.0, 10, (1, 1)), K("evoker", 10.0, 8, (3, 3)),
            A([{"visit_structure": "minecraft:mansion"}], 35.0, 10, (1, 1)),
            A([{"visit_structure": "minecraft:ancient_city"}], 30.0, 8, (1, 1)),
            A([{"visit_structure": "minecraft:end_city"}], 35.0, 6, (1, 1))],
    },
    "cleric": {
        1: [D("rotten_flesh", 0.02), D("bone", 0.03), D("redstone", 0.03), K("zombie", 0.25)],
        2: [D("spider_eye", 0.15), D("lapis_lazuli", 0.05), D("gold_ingot", 0.04), D("glowstone_dust", 0.05),
            K("skeleton", 0.25), K("drowned", 0.4)],
        3: [D("gunpowder", 0.07), D("rabbit_foot", 1.0), K("witch", 1.5), K("phantom", 0.8),
            A([{"brew": "minecraft:healing"}], 0.8, 6), A([{"cure": ""}], 15.0, 5, (1, 1)), O("healers_kit")],
        4: [O("potions"), D("blaze_rod", 0.5), K("blaze", 0.5), K("enderman", 0.5)],
        5: [D("ghast_tear", 1.5), K("wither_skeleton", 1.5), A([{"cure": ""}], 15.0, 8, (2, 2)),
            A([{"brew": "minecraft:strong_strength"}], 1.5, 6)],
    },
    "armorer": {
        1: [D("coal", 0.03), D("copper_ingot", 0.03), D("leather", 0.1)],
        2: [D("iron_ingot", 0.04), D("lava_bucket", 0.5), K("skeleton", 0.25), R("copper_helmet", 2, (8, 11)),
            R("copper_boots", 2, (8, 11))],
        3: [D("iron_block", 0.4), D("obsidian", 0.4), O("guard_gear"), K("pillager", 0.6),
            A([{"summon": "minecraft:iron_golem"}], 2.5, 5), R("iron_helmet", 2, (10, 13)), R("iron_chestplate", 2, (10, 13))],
        4: [D("diamond", 1.0), K("vindicator", 2.0), K("blaze", 0.6), A([{"visit_structure": "minecraft:fortress"}], 20.0, 6, (1, 1)),
            R("diamond_helmet", 2, (15, 18)), R("diamond_chestplate", 2, (15, 18))],
        5: [O("smith_order"), K("ravager", 8.0, 10, (4, 4)), D("netherite_scrap", 6.0), R("netherite_helmet", 2, (18, 21)),
            R("netherite_chestplate", 2, (18, 21))],
    },
    "weaponsmith": {
        1: [D("coal", 0.03), D("flint", 0.05), D("stick", 0.02), D("bone", 0.03), R("stone_sword", 3, (5, 8))],
        2: [D("iron_ingot", 0.04), K("skeleton", 0.25), K("zombie", 0.25), R("copper_sword", 3, (8, 11))],
        3: [D("iron_block", 0.4), K("creeper", 0.35), K("witch", 1.5), O("armory"), R("iron_sword", 3, (10, 13)),
            R("iron_axe", 2, (10, 13)), A([{"enchant": "", "min_level": 15}], 3.0, 5)],
        4: [D("diamond", 1.0), K("vindicator", 2.0), K("enderman", 0.5), R("diamond_sword", 2, (15, 18))],
        5: [D("netherite_scrap", 6.0), K("evoker", 10.0, 10, (3, 3)), K("wither_skeleton", 1.5), K("breeze", 2.5),
            R("netherite_sword", 2, (18, 21))],
    },
    "toolsmith": {
        1: [D("coal", 0.03), D("flint", 0.05), D("copper_ingot", 0.03), D("redstone", 0.03), R("stone_pickaxe", 3, (5, 8))],
        2: [D("iron_ingot", 0.04), K("spider", 0.25),
            A([{"mine": "#minecraft:iron_ores"}, {"deliver": "minecraft:raw_iron"}], 0.15, 6),
            R("copper_pickaxe", 3, (8, 11)), R("copper_shovel", 3, (8, 11))],
        3: [D("coal_block", 0.3), K("cave_spider", 0.3), K("silverfish", 0.3), D("amethyst_shard", 0.15), O("tool_set"),
            R("iron_pickaxe", 2, (10, 13)), R("iron_shovel", 3, (10, 13))],
        4: [D("diamond", 1.0), A([{"mine": "minecraft:ancient_debris"}], 4.0, 6), K("magma_cube", 0.5), D("quartz", 0.08),
            R("diamond_pickaxe", 2, (15, 18)),
            R("diamond_shovel", 2, (15, 18))],
        5: [D("echo_shard", 4.0), D("netherite_scrap", 6.0), A([{"visit_structure": "minecraft:ancient_city"}], 30.0, 8, (1, 1)),
            R("netherite_pickaxe", 2, (18, 21))],
    },
    "leatherworker": {
        1: [D("leather", 0.1), D("flint", 0.05), D("string", 0.03), A([{"breed": "minecraft:cow"}], 0.3)],
        2: [D("rabbit_hide", 0.4), D("leather_chestplate", 0.8), K("spider", 0.25),
            A([{"tame": "minecraft:horse"}], 9.0, 5, (1, 1)), A([{"tame": "minecraft:donkey"}], 9.0, 5, (1, 1)),
            A([{"tame": "minecraft:llama"}], 9.0, 5, (1, 1))],
        3: [D("rabbit_foot", 1.0), D("turtle_scute", 2.0), D("saddle", 6.0), K("husk", 0.3), O("riders_set")],
        4: [D("phantom_membrane", 0.8), D("armadillo_scute", 0.5), A([{"breed": "minecraft:turtle"}], 3.0, 6)],
        5: [O("armored_stable"), D("wolf_armor", 3.0), K("ravager", 8.0, 8, (4, 4))],
    },
    "mason": {
        1: [D("stone", 0.02), D("clay_ball", 0.05), D("deepslate", 0.02)],
        2: [D("brick", 0.04), D("polished_granite", 0.04), D("polished_andesite", 0.04), D("polished_diorite", 0.04),
            K("silverfish", 0.3), O("town_hall"), A([{"station": "minecraft:stone_bricks"}, {"deliver": "minecraft:stone_bricks"}], 0.04, 6)],
        3: [D("calcite", 0.06), D("tuff_bricks", 0.06), D("quartz_block", 0.25), K("creeper", 0.35)],
        4: [D("white_glazed_terracotta", 0.1), D("dripstone_block", 0.08), D("prismarine_bricks", 0.3)],
        5: [D("purpur_block", 0.15), D("end_stone_bricks", 0.12), D("prismarine_bricks", 0.3, 6),
            A([{"visit_structure": "minecraft:end_city"}], 35.0, 6, (1, 1))],
    },
    "fletcher": {
        1: [D("stick", 0.02), D("flint", 0.05), D("feather", 0.05), A([{"craft": "minecraft:arrow"}, {"deliver": "minecraft:arrow"}], 0.03),
            R("bow", 3, (8, 12))],
        2: [D("string", 0.04), D("tripwire_hook", 0.1), D("bow", 0.6), K("skeleton", 0.25)],
        3: [D("crossbow", 0.9), K("bogged", 0.5), K("stray", 0.5), O("hunters_pack"), R("crossbow", 2, (8, 12))],
        4: [D("spectral_arrow", 0.1), K("pillager", 0.6), K("ghast", 1.5), K("phantom", 0.8), R("crossbow", 3, (15, 18))],
        5: [D("wind_charge", 0.5), K("breeze", 2.5), A([{"visit_structure": "minecraft:trial_chambers"}], 30.0, 6, (1, 1)),
            R("bow", 3, (18, 21))],
    },
}

# The nitwit does not trade, so it has no packs: the same light errands at every tier (NITWIT_WINDOW).
NITWIT = [D("#minecraft:small_flowers", 0.5), D("sugar_cane", 0.1), D("cookie", 0.2), D("apple", 0.5),
          D("slime_ball", 0.6, 5), D("honey_bottle", 1.0, 5), K("slime", 0.6, 5), D("pumpkin_pie", 0.5),
          D("glow_berries", 0.3), D("sunflower", 0.5), D("egg", 0.3), D("melon_slice", 0.1),
          A([{"tame": "minecraft:cat"}], 2.0, 10, (1, 1)), O("picnic")]


# ---------------------------------------------------------------------------------------------------------------
# Action objectives: a delivery becomes "do the action + bring the result" where an action fits.
# Recipes are read from the jar to decide between crafting and smelting. Minutes stay as given.
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



def with_objectives(task, objectives, value_factor=None, value_add=0.0):
    """The objectives change; the minutes per unit stay as given (they already count the whole job)."""
    out = {k: v for k, v in task.items() if k != "deliver"}
    out["objectives"] = objectives
    return out


# ---- orders: several things at once, each made and brought. (action, item, count, minutes per item) ----
# Counts are scaled as a whole so that the order takes the middle of its pack's window.
ORDERS = {
    "winter_stock": [("craft", "bread", 16, 0.08), ("smelt", "baked_potato", 16, 0.03), ("craft", "pumpkin_pie", 8, 0.15)],
    "beekeeper": [("deliver", "honey_bottle", 8, 1.0), ("deliver", "honeycomb", 8, 0.8), ("craft", "candle", 4, 0.3)],
    "ancient_seeds": [("deliver", "sniffer_egg", 1, 20.0), ("deliver", "torchflower_seeds", 4, 2.5)],
    "feast": [("smelt", "cooked_porkchop", 32, 0.08), ("craft", "pumpkin_pie", 8, 0.15), ("craft", "cake", 2, 0.6)],
    "smiths_lunch": [("smelt", "cooked_beef", 16, 0.08), ("smelt", "baked_potato", 16, 0.03), ("craft", "bread", 16, 0.08)],
    "wedding": [("craft", "cake", 4, 1.2), ("craft", "rabbit_stew", 8, 1.2), ("craft", "golden_carrot", 32, 0.1),
                ("craft", "glistering_melon_slice", 8, 0.15)],
    "fish_market": [("catch", "cod", 16, 0.3), ("catch", "salmon", 8, 0.35), ("catch", "tropical_fish", 2, 0.6)],
    "color_set": [("deliver", "red_wool", 8, 0.06), ("deliver", "yellow_wool", 8, 0.06), ("deliver", "blue_wool", 8, 0.08),
                  ("deliver", "green_wool", 8, 0.08)],
    "rainbow": [("deliver", c + "_wool", 16, 0.08) for c in ("white", "orange", "magenta", "light_blue", "yellow", "lime",
                                                            "pink", "gray", "light_gray", "cyan", "purple", "blue",
                                                            "brown", "green", "red", "black")],
    "new_home": [("craft", "#minecraft:beds", 4, 0.8), ("craft", "#minecraft:wool_carpets", 16, 0.05), ("craft", "painting", 2, 0.2)],
    "reading_room": [("craft", "bookshelf", 8, 0.6), ("craft", "lectern", 2, 1.0), ("craft", "lantern", 4, 0.1)],
    "expedition_kit": [("craft", "map", 4, 0.3), ("craft", "compass", 2, 0.3), ("craft", "spyglass", 1, 0.5)],
    "healers_kit": [("brew", "minecraft:healing", 2, 0.8), ("craft", "glass_bottle", 6, 0.05), ("craft", "golden_apple", 1, 0.4)],
    "potions": [("craft", "blaze_powder", 4, 0.3), ("deliver", "nether_wart", 16, 0.05), ("craft", "glass_bottle", 12, 0.05)],
    "guard_gear": [("craft", "shield", 2, 1.0), ("craft", "iron_helmet", 1, 1.0), ("craft", "iron_boots", 1, 1.0)],
    "smith_order": [("deliver", "diamond", 16, 1.0), ("smelt", "netherite_scrap", 2, 6.0)],
    "armory": [("craft", "iron_sword", 2, 1.0), ("craft", "bow", 1, 1.0), ("craft", "arrow", 32, 0.03)],
    "tool_set": [("craft", "iron_pickaxe", 2, 1.0), ("craft", "iron_shovel", 2, 1.0), ("craft", "iron_axe", 2, 1.0)],
    "town_hall": [("station", "stone_bricks", 32, 0.04), ("craft", "bricks", 16, 0.15), ("station", "polished_andesite", 16, 0.04)],
    "hunters_pack": [("craft", "bow", 1, 1.0), ("craft", "crossbow", 1, 1.5), ("craft", "arrow", 32, 0.03)],
    "riders_set": [("craft", "saddle", 1, 6.0), ("craft", "leather_horse_armor", 1, 1.0), ("craft", "lead", 2, 0.4)],
    "armored_stable": [("deliver", "armadillo_scute", 24, 0.5), ("deliver", "leather", 64, 0.1), ("craft", "saddle", 2, 6.0)],
    "picnic": [("craft", "cookie", 8, 0.05), ("deliver", "apple", 4, 0.3), ("craft", "cake", 1, 0.6)],
}
# Inventory slots one order may need for what is brought: big orders are brought in parts ("Give what I have"),
# but no more than three inventories' worth.
ORDER_SLOTS = 108
# Items the order may not ask more of than one (rare finds).
ORDER_SINGLE = {"sniffer_egg"}


def stack_of(target):
    it = target.split(":")[-1]
    if it in STACK1 or target in STACK1:
        return 1
    if it in STACK16 or target in STACK16:
        return 16
    return 64


def item_cap(target):
    it = target.split(":")[-1]
    if target.startswith("#"):
        return 16 if target in STACK1 else 64 if target in STACK16 else 256
    if it in STACK1:
        return 16
    if it in STACK16 or target in STACK16:
        return 64
    return 256


def order_task(name, tier):
    lo, hi = WINDOW[tier] if tier else NITWIT_WINDOW
    total = sum(count * m for _, _, count, m in ORDERS[name])
    k = ((lo + hi) / 2) / total
    objectives = []
    minutes = 0.0
    slots = 0
    for action, it, count, m in ORDERS[name]:
        target = mc(it)
        n = 1 if it in ORDER_SINGLE else max(1, min(item_cap(target), round(count * k)))
        objectives.append({action: target, "count": [n, n]})
        if action not in ("deliver", "brew"):
            objectives.append({"deliver": target, "count": [n, n]})
        minutes += n * m
        slots += math.ceil(n / stack_of(target)) if action != "brew" else 0
    if slots > ORDER_SLOTS:
        raise SystemExit(f"order {name} needs {slots} slots (at most {ORDER_SLOTS})")
    return {"title": "order." + name, "objectives": objectives, "count": [1, 1], "value": round(minutes, 2), "weight": 6}


# ---- reforge (one-time per player): the minutes of the pieces; the reward is one of them, enchanted ----
REFORGE_MINUTES = {"stone": 0.05, "copper": 0.2, "iron": 0.3, "diamond": 2.0, "netherite": 10.0, "bow": 0.3, "crossbow": 0.6}


def reforge_task(name, count, levels):
    action = "craft"
    material = name if name in ("bow", "crossbow") else name.split("_", 1)[0]
    if material == "netherite":
        # Netherite gear is made on the smithing table, which counts as a station.
        action = "station"
    target = mc(name)
    return {"objectives": [{action: target, "count": [count, count]}, {"deliver": target, "count": [count, count]}],
            "count": [1, 1], "value": round(count * REFORGE_MINUTES[material], 2), "weight": 8,
            # Every enchantment it gets is at its highest level (user, 2026-10-13); how many it gets still depends on
            # the enchanting levels.
            "reward": {"enchant": target, "levels": list(levels), "max_levels": True}}


def add_top_rewards():
    """Every trading profession has its top reward (TopRewards.java): the first Best-friend trial gives it."""
    for prof in PACKS:
        REWARDS[prof].append({"top": True, "value": 15.0, "min_tier": 5, "max_tier": 5})


def cap_of(task):
    """The most units one errand of this task may ask for (the tightest objective)."""
    cap = 256
    for o in task.get("objectives", []):
        factor = o.get("factor", 1.0)
        for key, target in o.items():
            if key in ("factor", "count", "min_level") or not isinstance(target, str):
                continue
            if key in ("deliver", "craft", "smelt", "station", "catch", "plant", "fill_bucket"):
                c = item_cap(target) if target else 256
            elif key in ("kill", "breed", "tame", "summon", "milk"):
                c = 128
            else:
                c = 256  # blocks: harvest, mine, plant, bonemeal, shear
            cap = min(cap, int(c / max(factor, 0.01)))
    return max(1, cap)


def build(entry, tiers, report):
    tier = tiers[0] if tiers[0] == tiers[1] else None
    if isinstance(entry, tuple) and entry[0] == "order":
        task = order_task(entry[1], tier)
    elif isinstance(entry, tuple) and entry[0] == "reforge":
        task = reforge_task(*entry[1:])
    else:
        minutes = entry["minutes"]
        task = to_actions({k: v for k, v in entry.items() if k not in ("minutes", "fixed")} | {"value": minutes})
        lo, hi = WINDOW[tier] if tier else NITWIT_WINDOW
        if entry.get("fixed"):
            a, b = entry["fixed"]
        else:
            a = max(1, math.ceil(lo / minutes))
            b = max(a, math.floor(hi / minutes))
            b = min(b, cap_of(task))
        task["count"] = [a, b]
        task["value"] = minutes
    task["tiers"] = list(tiers)
    report.append(task)
    return task


def task_minutes(task):
    c = task["count"]
    return c[0] * task["value"], c[1] * task["value"]


def name_of(task):
    if task.get("title"):
        return task["title"]
    objs = task.get("objectives") or [task]
    parts = []
    for o in objs:
        for k, v in o.items():
            if k in ("count", "factor", "min_level", "value", "weight", "tiers", "minutes", "fixed"):
                continue
            parts.append(f"{k}:{str(v).split(':')[-1]}")
    return " ".join(parts)


def check_window(prof, task, problems):
    if task.get("reward"):
        return  # reforge: a one-time milestone, paid with its piece
    t = task["tiers"]
    lo, hi = WINDOW[t[0]] if t[0] == t[1] else NITWIT_WINDOW
    a, b = task_minutes(task)
    # A little slack for rounding of counts and fixed counts.
    if b < lo * 0.95 or a > hi * 1.15:
        problems.append(f"{prof} T{t[0]} {name_of(task)}: {a:.1f}-{b:.1f} min, window {lo}-{hi} (count {task['count']})")


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
    problems = []
    lines = []
    for prof in list(PACKS) + ["nitwit"]:
        report = []
        if prof == "nitwit":
            tasks = [build(e, (1, 5), report) for e in NITWIT]
        else:
            tasks = [build(e, (tier, tier), report) for tier, entries in sorted(PACKS[prof].items()) for e in entries]
        for t in tasks:
            check(prof, t, items, entities)
            check_window(prof, t, problems)
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
        # Balance report: minutes and pay (emeralds' worth) per pack.
        by_tier = {}
        for t in tasks:
            if t.get("reward"):
                continue
            by_tier.setdefault(t["tiers"][0], []).append(task_minutes(t))
        parts = []
        for tier, mins in sorted(by_tier.items()):
            lo = min(m[0] for m in mins)
            hi = max(m[1] for m in mins)
            rate = RATE.get(tier, 0.6) if prof != "nitwit" else 0.6
            parts.append(f"T{tier} {len(mins)}x {lo:.0f}-{hi:.0f}m ~{lo * rate:.0f}-{hi * rate:.0f}e")
        lines.append(f"{prof:14} " + " | ".join(parts))
    print("\n".join(lines))
    if problems:
        print("\nTasks outside their window:")
        print("\n".join(problems))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
