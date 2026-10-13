"""Generates the built-in errand pools in src/main/resources/data/villagersremember/errands/.

Each profession has five packs (tiers T1-T5, TIERS.md section 4). A villager offers errands only from the pack of the
player's tier with that villager.

Effort model (user, 2026-10-13): a task's "value" is MINUTES OF WORK per unit for a player who has automatic plant
farms and a trading hall, but no farms for mobs, iron, gold, XP, raids or fish. Farmable things are cheap and live in the low packs; the top packs are actions that can't be taken
out of a chest (rare hunts, fishing, exploring, curing, brewing) and truly rare items. Counts are sized so that one
errand takes the minutes of its tier window (WINDOW); a task that can't fit its window fails the build. Daily errands
pay emeralds, XP and reputation only (the mod turns minutes into pay); items are one-time milestones (trials).
Run: python tools/gen_errands.py
"""
import json
import math
import os
import zipfile

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "data", "villagersremember", "errands")
JAR = os.path.expanduser("~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/26.3/minecraft-common-deobf-26.3.jar")
NAMES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "villagersremember", "vanilla_names", "en_us.json")

# Minutes one daily errand takes, by pack.
WINDOW = {1: (4, 8), 2: (8, 12), 3: (12, 18), 4: (18, 25), 5: (25, 35)}
NITWIT_WINDOW = (1, 4)
# Emeralds' worth per minute by pack (ErrandsConfig.tierEmeraldsPerMinute), only for the report.
RATE = {1: 0.6, 2: 0.8, 3: 1.0, 4: 1.3, 5: 1.6}
# ---- market: what villagers pay for the goods (vanilla buy trades, emeralds per item) ----
# An errand must pay clearly more than selling its goods to a villager: its emeralds are at least
# MARKET_PREMIUM x their market value (the mod enforces it too, ErrandsConfig.marketPremium), and the sizing leaves
# room for experience on top (EMERALD_SHARE).
MARKET_PREMIUM = 1.5
EMERALD_SHARE = 0.7
BUY = {  # villager buy trades: item -> items per emerald
    "wheat": 20, "potato": 26, "carrot": 22, "beetroot": 15, "pumpkin": 6, "melon": 4, "string": 14, "coal": 10,
    "cod": 15, "salmon": 13, "tropical_fish": 6, "pufferfish": 4, "white_wool": 18, "chicken": 14, "porkchop": 7,
    "rabbit": 4, "mutton": 7, "beef": 10, "dried_kelp_block": 10, "sweet_berries": 10, "paper": 24, "book": 4,
    "ink_sac": 5, "writable_book": 2, "glass_pane": 11, "compass": 1, "rotten_flesh": 32, "gold_ingot": 3,
    "rabbit_foot": 2, "turtle_scute": 4, "glass_bottle": 9, "nether_wart": 22, "iron_ingot": 4, "lava_bucket": 1,
    "diamond": 1, "flint": 24, "leather": 6, "rabbit_hide": 9, "clay_ball": 10, "stone": 20, "granite": 16,
    "andesite": 16, "diorite": 16, "quartz": 12, "stick": 32, "feather": 24, "tripwire_hook": 8,
    "white_dye": 12, "red_dye": 12, "yellow_dye": 12, "blue_dye": 12, "green_dye": 12,
}
PRICE = {k: 1.0 / v for k, v in BUY.items()}
# Made from priced goods: the market value of what goes into them.
DERIVED = {
    "bread": 3 * PRICE["wheat"], "hay_block": 9 * PRICE["wheat"], "cake": 3 * PRICE["wheat"] + 0.1,
    "pumpkin_pie": PRICE["pumpkin"] + 0.05, "golden_carrot": 8 / 9 * PRICE["gold_ingot"] + PRICE["carrot"],
    "golden_apple": 8 * PRICE["gold_ingot"], "glistering_melon_slice": 8 / 9 * PRICE["gold_ingot"] + PRICE["melon"] / 9,
    "baked_potato": PRICE["potato"], "cooked_beef": PRICE["beef"], "cooked_porkchop": PRICE["porkchop"],
    "cooked_chicken": PRICE["chicken"], "cooked_mutton": PRICE["mutton"], "cooked_cod": PRICE["cod"],
    "cooked_salmon": PRICE["salmon"], "rabbit_stew": PRICE["rabbit"] + PRICE["carrot"] + PRICE["potato"],
    "beetroot_soup": 6 * PRICE["beetroot"], "melon_slice": PRICE["melon"] / 9, "egg": 0.02,
    "iron_block": 9 * PRICE["iron_ingot"], "coal_block": 9 * PRICE["coal"], "gold_block": 9 * PRICE["gold_ingot"],
    "iron_pickaxe": 3 * PRICE["iron_ingot"], "iron_axe": 3 * PRICE["iron_ingot"], "iron_shovel": PRICE["iron_ingot"],
    "iron_sword": 2 * PRICE["iron_ingot"], "iron_helmet": 5 * PRICE["iron_ingot"], "iron_boots": 4 * PRICE["iron_ingot"],
    "shield": PRICE["iron_ingot"], "bow": 3 * PRICE["string"], "crossbow": 2 * PRICE["string"] + PRICE["iron_ingot"]
    + PRICE["tripwire_hook"], "arrow": (PRICE["flint"] + PRICE["stick"] + PRICE["feather"]) / 4,
    "#minecraft:wool": PRICE["white_wool"], "#minecraft:beds": 3 * PRICE["white_wool"],
    "#minecraft:wool_carpets": 2 * PRICE["white_wool"] / 3, "#minecraft:banners": 6 * PRICE["white_wool"],
    "painting": PRICE["white_wool"] + 0.03, "lead": 4 * PRICE["string"], "bookshelf": 3 * PRICE["book"],
    "lectern": 3 * PRICE["book"], "clock": 4 * PRICE["gold_ingot"], "map": 8 * PRICE["paper"] + PRICE["compass"],
    "leather_chestplate": 8 * PRICE["leather"], "leather_horse_armor": 7 * PRICE["leather"], "saddle": 0,
    "brick": PRICE["clay_ball"], "bricks": 4 * PRICE["clay_ball"], "polished_granite": PRICE["granite"],
    "polished_andesite": PRICE["andesite"], "polished_diorite": PRICE["diorite"], "stone_bricks": PRICE["stone"],
    "quartz_block": 4 * PRICE["quartz"], "candle": PRICE["string"], "red_wool": PRICE["white_wool"] + PRICE["red_dye"],
}
PRICE.update(DERIVED)
for c in ("orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
          "brown", "green", "black", "red"):
    PRICE.setdefault(c + "_wool", PRICE["white_wool"] + 1.0 / 12)


def price_of(target):
    """Market value of one item (0 when no villager buys it or what it is made of)."""
    t = target if target.startswith("#") else target.split(":")[-1]
    return PRICE.get(t, 0.0)


def market_of(task):
    """Market value of what one rolled unit of a task asks to bring."""
    total = 0.0
    for o in task.get("objectives", []):
        if "deliver" in o:
            total += price_of(o["deliver"]) * o.get("factor", 1.0)
    return total


def priced_minutes(minutes, market, tier):
    """Minutes per unit, raised so that the pay leaves the goods' market value well behind."""
    rate = RATE.get(tier, RATE[1]) if tier else RATE[1]
    return max(minutes, market * MARKET_PREMIUM / EMERALD_SHARE / rate)


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
    "cartographer": [item("map", 2, 4, 0.6, T2), loot("villagersremember:top/map_buried_treasure", 3, T3),
                     loot("villagersremember:top/map_monument", 5, T4)],
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


# ---- the packs (TIERS.md section 4): minutes per unit ----
# Assumed player (user, 2026-10-13 09:38): automatic farms for plants (crops, sugar cane, pumpkins, melons, trees,
# bamboo, kelp) and a trading hall. NO farms for mobs, iron, gold, XP, raids or fish. So mob drops are killed by
# hand at night, iron and gold are mined, raids need a Bad Omen each, bees and sniffers breed slowly.
# T1 overworld basics, T2 iron age, T3 diamonds / enchanting / first raids, T4 the Nether, monuments, rare mobs,
# T5 the end game: raids for ravagers, bastions, ancient cities, mansions, the End.
# Counts that a market price would make larger are raised by the market floor (MARKET_PREMIUM), so crops and other
# cheap goods stay small enough that the errand pays clearly more than selling them.

PACKS = {
    "farmer": {
        1: [D("wheat", 0.01), D("potato", 0.01), D("carrot", 0.01), D("beetroot", 0.01), D("sugar_cane", 0.02),
            K("rabbit", 0.7, 6), A([{"plant": "minecraft:wheat_seeds"}], 0.02, 6), A([{"bonemeal": ""}], 0.03, 6)],
        2: [D("bread", 0.03), D("pumpkin", 0.02), D("melon", 0.03), D("egg", 0.3), D("hay_block", 0.05), D("cake", 0.3),
            K("zombie", 0.5), A([{"breed": "minecraft:chicken"}], 0.3, 6)],
        3: [O("winter_stock"), D("pumpkin_pie", 0.15), D("golden_carrot", 1.0), K("spider", 0.5), K("husk", 0.6)],
        4: [D("glistering_melon_slice", 1.2), D("golden_apple", 8.0, 8), O("beekeeper"), K("pillager", 1.0),
            A([{"cure": ""}], 20.0, 6, (1, 1)), A([{"breed": "minecraft:bee"}], 2.0, 6)],
        5: [K("ravager", 13.0, 10, (2, 2)), A([{"cure": ""}], 15.0, 10, (2, 2)), D("sniffer_egg", 28.0, 8, (1, 1)),
            D("golden_apple", 8.0, 8)],
    },
    "butcher": {
        1: [D("chicken", 0.1), D("porkchop", 0.1), D("beef", 0.1), D("mutton", 0.1),
            A([{"breed": "minecraft:cow"}], 0.3, 6), A([{"breed": "minecraft:pig"}], 0.3, 6)],
        2: [D("cooked_chicken", 0.15), D("cooked_beef", 0.15), D("cooked_porkchop", 0.15), D("cooked_mutton", 0.15),
            D("mushroom_stew", 0.6), D("beetroot_soup", 0.6), K("spider", 0.5), A([{"breed": "minecraft:rabbit"}], 1.0, 6)],
        3: [D("rabbit_stew", 1.7), O("smiths_lunch"), K("zombie", 0.5), K("husk", 0.6)],
        4: [O("feast"), O("wedding"), K("hoglin", 1.0), A([{"breed": "minecraft:goat"}], 3.0, 6)],
        5: [K("piglin_brute", 8.0, 10, (4, 4)), K("ravager", 13.0, 10, (2, 2)),
            A([{"visit_structure": "minecraft:bastion_remnant"}], 30.0, 8, (1, 1))],
    },
    "fisherman": {
        1: [D("cod", 0.6), D("salmon", 0.7), D("kelp", 0.02), D("string", 0.3)],
        2: [D("cooked_cod", 0.7), D("cooked_salmon", 0.8), D("tropical_fish", 1.0), D("lily_pad", 0.3), K("drowned", 0.6),
            O("fish_market"), A([{"fill_bucket": "minecraft:tropical_fish_bucket"}, {"deliver": "minecraft:tropical_fish_bucket"}], 1.0, 6)],
        3: [D("pufferfish", 2.0), D("sea_pickle", 0.2), D("prismarine_shard", 0.5), D("ink_sac", 0.5), K("guardian", 1.0),
            A([{"visit_structure": "#minecraft:shipwreck"}], 15.0, 6, (1, 1))],
        4: [D("nautilus_shell", 3.0), D("prismarine_crystals", 0.8), A([{"fill_bucket": "minecraft:axolotl_bucket"}], 4.0, 6),
            A([{"visit_structure": "minecraft:monument"}], 22.0, 6, (1, 1)), A([{"catch": "minecraft:pufferfish"}], 2.0, 6)],
        5: [D("heart_of_the_sea", 30.0, 10, (1, 1)), D("trident", 38.0, 6, (1, 1)), K("elder_guardian", 38.0, 8, (1, 1)),
            D("wet_sponge", 8.0)],
    },
    "shepherd": {
        1: [D("#minecraft:wool", 0.03), D("white_dye", 0.1), D("wheat", 0.01), A([{"breed": "minecraft:sheep"}], 0.3, 8)],
        2: [D("#minecraft:wool_carpets", 0.05), D("#minecraft:beds", 0.6), O("color_set"), D("lead", 1.5), K("spider", 0.5)],
        3: [D("#minecraft:banners", 0.9), D("painting", 0.25), O("new_home"), A([{"tame": "minecraft:wolf"}], 3.0, 6)],
        4: [K("phantom", 1.5), A([{"breed": "minecraft:llama"}], 2.5, 6), A([{"shear": "minecraft:mooshroom"}], 9.0, 6, (2, 2))],
        5: [O("rainbow"), D("piglin_banner_pattern", 30.0, 8, (1, 1)), A([{"breed": "minecraft:llama"}], 2.5, 6)],
    },
    "librarian": {
        1: [D("paper", 0.02), D("feather", 0.15), D("ink_sac", 0.5), D("sugar_cane", 0.02)],
        2: [D("book", 0.4), D("candle", 1.5), D("lantern", 0.1), A([{"enchant": "", "min_level": 10}], 3.0, 6)],
        3: [D("bookshelf", 1.3), D("writable_book", 1.0), O("reading_room"), K("witch", 3.0)],
        4: [D("ender_pearl", 1.0), D("glow_ink_sac", 1.0), D("clock", 4.0), A([{"enchant": "", "min_level": 30}], 8.0, 6)],
        5: [D("ender_eye", 2.0), K("evoker", 15.0, 10, (2, 2)), A([{"visit_structure": "minecraft:stronghold"}], 30.0, 8, (1, 1)),
            A([{"visit_structure": "minecraft:ancient_city"}], 30.0, 6, (1, 1))],
    },
    "cartographer": {
        1: [D("paper", 0.02), D("glass", 0.03), D("sugar_cane", 0.02),
            A([{"visit_structure": "#minecraft:ruined_portal"}], 6.0, 6, (1, 1)),
            A([{"visit_structure": "#minecraft:abandoned_camp"}], 6.0, 6, (1, 1)),
            A([{"visit_biome": "minecraft:cherry_grove"}], 6.0, 5, (1, 1))],
        2: [D("compass", 1.3), D("map", 1.5), D("glass_pane", 0.02), K("phantom", 1.5),
            A([{"visit_structure": "minecraft:pillager_outpost"}], 10.0, 6, (1, 1)),
            A([{"visit_structure": "minecraft:igloo"}], 12.0, 5, (1, 1)),
            A([{"visit_structure": "minecraft:desert_pyramid"}], 10.0, 5, (1, 1)),
            A([{"visit_biome": "minecraft:ice_spikes"}], 10.0, 5, (1, 1)),
            A([{"visit_biome": "minecraft:badlands"}], 10.0, 5, (1, 1)),
            A([{"tame": "minecraft:parrot"}], 10.0, 4, (1, 1)), A([{"tame": "minecraft:horse"}], 4.0, 4, (2, 3))],
        3: [D("spyglass", 0.9), D("amethyst_shard", 0.3), O("expedition_kit"), K("pillager", 1.0),
            A([{"visit_structure": "minecraft:trail_ruins"}], 15.0, 6, (1, 1))],
        4: [D("clock", 4.0), K("witch", 3.0), A([{"visit_structure": "minecraft:monument"}], 22.0, 6, (1, 1)),
            A([{"visit_structure": "minecraft:trial_chambers"}], 22.0, 6, (1, 1)),
            A([{"visit_biome": "minecraft:mushroom_fields"}], 22.0, 5, (1, 1))],
        5: [D("recovery_compass", 30.0, 10, (1, 1)), K("evoker", 15.0, 8, (2, 2)),
            A([{"visit_structure": "minecraft:mansion"}], 35.0, 10, (1, 1)),
            A([{"visit_structure": "minecraft:ancient_city"}], 30.0, 8, (1, 1)),
            A([{"visit_structure": "minecraft:end_city"}], 35.0, 6, (1, 1))],
    },
    "cleric": {
        1: [D("rotten_flesh", 0.2), D("bone", 0.3), D("redstone", 0.03), K("zombie", 0.5)],
        2: [D("spider_eye", 0.8), D("lapis_lazuli", 0.05), D("gold_ingot", 1.0), D("glowstone_dust", 0.1),
            K("skeleton", 0.5), K("drowned", 0.6)],
        3: [D("gunpowder", 0.7), D("rabbit_foot", 3.0), K("witch", 3.0), K("phantom", 1.5),
            A([{"brew": "minecraft:healing"}], 2.0, 6), A([{"cure": ""}], 15.0, 5, (1, 1)), O("healers_kit")],
        4: [O("potions"), D("blaze_rod", 1.0), K("blaze", 1.0), K("enderman", 1.0)],
        5: [D("ghast_tear", 3.0), K("wither_skeleton", 2.5), A([{"cure": ""}], 15.0, 8, (2, 2)),
            A([{"brew": "minecraft:strong_strength"}], 3.0, 6)],
    },
    "armorer": {
        1: [D("coal", 0.05), D("copper_ingot", 0.05), D("leather", 0.3)],
        2: [D("iron_ingot", 0.3), D("lava_bucket", 0.5), K("skeleton", 0.5), R("copper_helmet", 2, (8, 11)),
            R("copper_boots", 2, (8, 11))],
        3: [D("iron_block", 2.7), D("obsidian", 0.5), O("guard_gear"), K("pillager", 1.0),
            A([{"summon": "minecraft:iron_golem"}], 13.0, 5, (1, 1)), R("iron_helmet", 2, (10, 13)), R("iron_chestplate", 2, (10, 13))],
        4: [D("diamond", 3.0), K("vindicator", 3.0), K("blaze", 1.0), A([{"visit_structure": "minecraft:fortress"}], 20.0, 6, (1, 1)),
            R("diamond_helmet", 2, (15, 18)), R("diamond_chestplate", 2, (15, 18))],
        5: [O("smith_order"), K("ravager", 13.0, 10, (2, 2)), D("netherite_scrap", 8.0), R("netherite_helmet", 2, (18, 21)),
            R("netherite_chestplate", 2, (18, 21))],
    },
    "weaponsmith": {
        1: [D("coal", 0.05), D("flint", 0.1), D("stick", 0.02), D("bone", 0.3), R("stone_sword", 3, (5, 8))],
        2: [D("iron_ingot", 0.3), K("skeleton", 0.5), K("zombie", 0.5), R("copper_sword", 3, (8, 11))],
        3: [D("iron_block", 2.7), K("creeper", 0.6), K("witch", 3.0), O("armory"), R("iron_sword", 3, (10, 13)),
            R("iron_axe", 2, (10, 13)), A([{"enchant": "", "min_level": 15}], 4.0, 5)],
        4: [D("diamond", 3.0), K("vindicator", 3.0), K("enderman", 1.0), R("diamond_sword", 2, (15, 18))],
        5: [D("netherite_scrap", 8.0), K("evoker", 15.0, 10, (2, 2)), K("wither_skeleton", 2.5), K("breeze", 3.0),
            R("netherite_sword", 2, (18, 21))],
    },
    "toolsmith": {
        1: [D("coal", 0.05), D("flint", 0.1), D("copper_ingot", 0.05), D("redstone", 0.03), R("stone_pickaxe", 3, (5, 8))],
        2: [D("iron_ingot", 0.3), K("spider", 0.5),
            A([{"mine": "#minecraft:iron_ores"}, {"deliver": "minecraft:raw_iron"}], 0.3, 6),
            R("copper_pickaxe", 3, (8, 11)), R("copper_shovel", 3, (8, 11))],
        3: [D("coal_block", 0.45), K("cave_spider", 0.6), K("silverfish", 0.5), D("amethyst_shard", 0.3), O("tool_set"),
            R("iron_pickaxe", 2, (10, 13)), R("iron_shovel", 3, (10, 13))],
        4: [D("diamond", 3.0), A([{"mine": "minecraft:ancient_debris"}], 8.0, 6), K("magma_cube", 0.6), D("quartz", 0.1),
            R("diamond_pickaxe", 2, (15, 18)), R("diamond_shovel", 2, (15, 18))],
        5: [D("echo_shard", 3.0), D("netherite_scrap", 8.0), A([{"visit_structure": "minecraft:ancient_city"}], 30.0, 8, (1, 1)),
            R("netherite_pickaxe", 2, (18, 21))],
    },
    "leatherworker": {
        1: [D("leather", 0.3), D("flint", 0.1), D("string", 0.3), A([{"breed": "minecraft:cow"}], 0.3)],
        2: [D("rabbit_hide", 0.8), D("leather_chestplate", 2.4), K("spider", 0.5),
            A([{"tame": "minecraft:horse"}], 4.0, 5, (2, 3)), A([{"tame": "minecraft:donkey"}], 4.0, 5, (2, 3)),
            A([{"tame": "minecraft:llama"}], 4.0, 5, (2, 3))],
        3: [D("rabbit_foot", 3.0), D("turtle_scute", 6.0), D("saddle", 6.0), K("husk", 0.6), O("riders_set")],
        4: [D("phantom_membrane", 1.5), D("armadillo_scute", 1.5), A([{"breed": "minecraft:turtle"}], 6.0, 6)],
        5: [O("armored_stable"), D("wolf_armor", 12.0), K("ravager", 13.0, 8, (2, 2))],
    },
    "mason": {
        1: [D("stone", 0.02), D("clay_ball", 0.1), D("deepslate", 0.02)],
        2: [D("brick", 0.05), D("polished_granite", 0.02), D("polished_andesite", 0.02), D("polished_diorite", 0.02),
            K("silverfish", 0.5), O("town_hall"), A([{"station": "minecraft:stone_bricks"}, {"deliver": "minecraft:stone_bricks"}], 0.03, 6)],
        3: [D("calcite", 0.1), D("tuff_bricks", 0.07), D("quartz_block", 0.4), K("creeper", 0.6)],
        4: [D("white_glazed_terracotta", 0.15), D("dripstone_block", 0.1), D("prismarine_bricks", 4.5)],
        5: [D("purpur_block", 0.3), D("end_stone_bricks", 0.12), D("prismarine_bricks", 4.5, 6),
            A([{"visit_structure": "minecraft:end_city"}], 35.0, 6, (1, 1))],
    },
    "fletcher": {
        1: [D("stick", 0.02), D("flint", 0.1), D("feather", 0.15), A([{"craft": "minecraft:arrow"}, {"deliver": "minecraft:arrow"}], 0.03),
            R("bow", 3, (8, 12))],
        2: [D("string", 0.3), D("tripwire_hook", 0.3), D("bow", 1.0), K("skeleton", 0.5)],
        3: [D("crossbow", 1.2), K("bogged", 1.0), K("stray", 1.0), O("hunters_pack"), R("crossbow", 2, (8, 12))],
        4: [D("spectral_arrow", 0.2), K("pillager", 1.0), K("ghast", 2.5), K("phantom", 1.5), R("crossbow", 3, (15, 18))],
        5: [D("wind_charge", 1.0), K("breeze", 3.0), A([{"visit_structure": "minecraft:trial_chambers"}], 30.0, 6, (1, 1)),
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
    "winter_stock": [("craft", "bread", 16, 0.03), ("smelt", "baked_potato", 16, 0.02), ("craft", "pumpkin_pie", 8, 0.15)],
    "beekeeper": [("deliver", "honey_bottle", 8, 2.0), ("deliver", "honeycomb", 8, 1.5), ("craft", "candle", 4, 1.5)],
    "ancient_seeds": [("deliver", "sniffer_egg", 1, 28.0), ("deliver", "torchflower_seeds", 4, 3.0)],
    "feast": [("smelt", "cooked_porkchop", 32, 0.3), ("craft", "pumpkin_pie", 8, 0.15), ("craft", "cake", 2, 0.3)],
    "smiths_lunch": [("smelt", "cooked_beef", 16, 0.3), ("smelt", "baked_potato", 16, 0.02), ("craft", "bread", 16, 0.03)],
    "wedding": [("craft", "cake", 4, 0.3), ("craft", "rabbit_stew", 8, 1.7), ("craft", "golden_carrot", 32, 1.0),
                ("craft", "glistering_melon_slice", 8, 1.2)],
    "fish_market": [("catch", "cod", 16, 0.6), ("catch", "salmon", 8, 0.7), ("catch", "tropical_fish", 2, 1.0)],
    "color_set": [("deliver", "red_wool", 8, 0.05), ("deliver", "yellow_wool", 8, 0.05), ("deliver", "blue_wool", 8, 0.05),
                  ("deliver", "green_wool", 8, 0.06)],
    "rainbow": [("deliver", c + "_wool", 16, 0.08) for c in ("white", "orange", "magenta", "light_blue", "yellow", "lime",
                                                            "pink", "gray", "light_gray", "cyan", "purple", "blue",
                                                            "brown", "green", "red", "black")],
    "new_home": [("craft", "#minecraft:beds", 4, 0.6), ("craft", "#minecraft:wool_carpets", 16, 0.1), ("craft", "painting", 2, 0.25)],
    "reading_room": [("craft", "bookshelf", 8, 1.6), ("craft", "lectern", 2, 1.8), ("craft", "lantern", 4, 0.1)],
    "expedition_kit": [("craft", "map", 4, 1.5), ("craft", "compass", 2, 1.3), ("craft", "spyglass", 1, 0.9)],
    "healers_kit": [("brew", "minecraft:healing", 2, 2.0), ("craft", "glass_bottle", 6, 0.05), ("craft", "golden_apple", 1, 8.0)],
    "potions": [("craft", "blaze_powder", 4, 0.5), ("deliver", "nether_wart", 16, 0.05), ("craft", "glass_bottle", 12, 0.05)],
    "guard_gear": [("craft", "shield", 2, 0.5), ("craft", "iron_helmet", 1, 1.5), ("craft", "iron_boots", 1, 1.2)],
    "smith_order": [("deliver", "diamond", 16, 3.0), ("smelt", "netherite_scrap", 2, 8.0)],
    "armory": [("craft", "iron_sword", 2, 0.6), ("craft", "bow", 1, 1.0), ("craft", "arrow", 32, 0.03)],
    "tool_set": [("craft", "iron_pickaxe", 2, 0.9), ("craft", "iron_shovel", 2, 0.3), ("craft", "iron_axe", 2, 0.9)],
    "town_hall": [("station", "stone_bricks", 32, 0.03), ("craft", "bricks", 16, 0.2), ("station", "polished_andesite", 16, 0.02)],
    "hunters_pack": [("craft", "bow", 1, 1.0), ("craft", "crossbow", 1, 1.2), ("craft", "arrow", 32, 0.03)],
    "riders_set": [("craft", "saddle", 1, 6.0), ("craft", "leather_horse_armor", 1, 2.1), ("craft", "lead", 2, 1.5)],
    "armored_stable": [("deliver", "armadillo_scute", 24, 1.5), ("deliver", "leather", 64, 0.3), ("craft", "saddle", 2, 6.0)],
    "picnic": [("craft", "cookie", 8, 0.05), ("deliver", "apple", 4, 0.3), ("craft", "cake", 1, 0.3)],
}

# ---- what a task takes, for the audit table (docs/TASK_AUDIT.md, Bulgarian, for the user) ----
NEED = {
    "wheat": "от фермата", "potato": "от фермата", "carrot": "от фермата", "beetroot": "от фермата",
    "sugar_cane": "ферма за тръстика", "pumpkin": "от фермата", "melon": "от фермата", "kelp": "ферма за водорасли",
    "bread": "жито от фермата", "hay_block": "жито от фермата", "cake": "мляко, захар, яйца, жито",
    "pumpkin_pie": "тиква, захар, яйце", "egg": "кокошки: яйце на 5-10 мин от кокошка",
    "golden_carrot": "злато от копаене (няма ферма): 8 късчета на морков",
    "golden_apple": "8 златни слитъка, копани на ръка: ~8 мин ябълка",
    "glistering_melon_slice": "злато от копаене + пъпеш", "sniffer_egg": "крайморски руини, четка по подозрителен пясък",
    "chicken": "развъдник с кокошки", "porkchop": "развъдник", "beef": "развъдник", "mutton": "развъдник",
    "cooked_chicken": "развъдник + пещ", "cooked_beef": "развъдник + пещ", "cooked_porkchop": "развъдник + пещ",
    "cooked_mutton": "развъдник + пещ", "mushroom_stew": "гъби + купа", "beetroot_soup": "6 цвекла + купа",
    "rabbit_stew": "лов на заек + морков, картоф, гъба", "cod": "въдица", "salmon": "въдица в река или студено море",
    "cooked_cod": "въдица + пещ", "cooked_salmon": "въдица + пещ", "tropical_fish": "кофа в топло море или въдица",
    "pufferfish": "въдица или кофа в топло море", "lily_pad": "блато", "sea_pickle": "топло море, корали",
    "prismarine_shard": "пазители в подводен храм", "prismarine_crystals": "пазители в подводен храм",
    "ink_sac": "калмари", "glow_ink_sac": "светещи калмари в пещери", "nautilus_shell": "удавници (8%) или въдица",
    "heart_of_the_sea": "карта за заровено съкровище от корабокрушение", "trident": "удавник с тризъбец (рядък дроп)",
    "wet_sponge": "стаите на подводния храм / древен пазител", "string": "паяци нощем (няма ферма)",
    "#minecraft:wool": "стрижене на овце", "white_dye": "кости или момина сълза", "#minecraft:wool_carpets": "вълна",
    "#minecraft:beds": "вълна + дъски", "lead": "конец + топка слуз (слузове в блато)", "#minecraft:banners": "6 вълна + пръчка",
    "painting": "вълна + пръчки", "piglin_banner_pattern": "сандък в бастион",
    "paper": "тръстика от фермата", "feather": "кокошки", "book": "хартия + кожа", "candle": "конец + пчелна пита (ножица на кошер)",
    "lantern": "железни късчета + факла", "bookshelf": "3 книги (кожа!) + дъски", "writable_book": "книга + мастило + перо",
    "ender_pearl": "ендърмени нощем (няма ферма)", "clock": "4 златни слитъка + червен прах", "ender_eye": "перла + блейз прах",
    "glass": "пясък + пещ", "glass_pane": "стъкло", "compass": "4 желязо + червен прах", "map": "8 хартия + компас",
    "spyglass": "мед + аметист", "amethyst_shard": "аметистова геода", "recovery_compass": "8 ехо парчета от древен град + компас",
    "rotten_flesh": "зомбита нощем", "bone": "скелети нощем", "redstone": "копаене или клерик", "spider_eye": "паяци нощем",
    "lapis_lazuli": "копаене или клерик", "gold_ingot": "копаене (няма ферма)", "glowstone_dust": "Недър или клерик",
    "gunpowder": "криипъри нощем", "rabbit_foot": "зайци, 10% шанс", "blaze_rod": "блейзове в крепост в Недъра",
    "ghast_tear": "гасти в Недъра", "coal": "копаене", "copper_ingot": "копаене + пещ", "leather": "крави",
    "iron_ingot": "копаене + пещ (няма ферма)", "lava_bucket": "езеро с лава", "iron_block": "9 желязо",
    "obsidian": "лава + вода, диамантена кирка", "diamond": "копаене дълбоко (~20 на час)",
    "netherite_scrap": "древни останки в Недъра (~7 на час)", "flint": "чакъл", "stick": "дърво",
    "coal_block": "9 въглища", "quartz": "Недър", "echo_shard": "сандъци в древен град", "rabbit_hide": "зайци",
    "leather_chestplate": "8 кожа", "turtle_scute": "малки костенурки порастват", "saddle": "сандъци, риболов или кожар",
    "phantom_membrane": "фантоми (3 нощи без сън)", "armadillo_scute": "четка по армадилос в савана",
    "wolf_armor": "8 люспи от армадилос", "stone": "кърваван камък + пещ", "clay_ball": "глина в реки",
    "deepslate": "копаене дълбоко", "brick": "глина + пещ", "polished_granite": "гранит", "polished_andesite": "андезит",
    "polished_diorite": "диорит", "calcite": "геоди / планини", "tuff_bricks": "туф", "quartz_block": "4 кварц от Недъра",
    "white_glazed_terracotta": "теракота + пещ", "dripstone_block": "пещери с капкокамък",
    "prismarine_bricks": "9 парчета призмарин (пазители)", "purpur_block": "хорус от Края",
    "end_stone_bricks": "камък от Края", "tripwire_hook": "желязо + дърво", "bow": "3 конеца", "crossbow": "конец, желязо, кука",
    "spectral_arrow": "светещ прах + стрела", "wind_charge": "бризове в изпитни зали", "arrow": "кремък, пръчка, перо",
    # actions
    "kill:rabbit": "лов на зайци", "kill:zombie": "нощем, ~2 в минута", "kill:spider": "нощем", "kill:husk": "пустиня нощем",
    "kill:skeleton": "нощем", "kill:drowned": "реки и морета нощем", "kill:creeper": "нощем", "kill:witch": "блата / нападения, редки",
    "kill:phantom": "3 нощи без сън", "kill:pillager": "аванпост или нападение", "kill:vindicator": "имение или нападение",
    "kill:evoker": "имение (~2) или нападение (1-2)", "kill:ravager": "само в нападение: ~2 на нападение (normal), Bad Omen от капитан",
    "kill:guardian": "подводен храм", "kill:elder_guardian": "подводен храм: 3 древни пазителя, умора",
    "kill:blaze": "крепост в Недъра", "kill:enderman": "нощем / изкривена гора", "kill:hoglin": "пурпурна гора в Недъра",
    "kill:piglin_brute": "бастион: 4-6 на бастион, не се появяват отново", "kill:wither_skeleton": "крепост в Недъра",
    "kill:ghast": "Недър", "kill:breeze": "изпитни зали", "kill:magma_cube": "Недър, базалтови делти",
    "kill:cave_spider": "мини с паешки спаунер", "kill:silverfish": "крепост или планини", "kill:bogged": "блата / изпитни зали",
    "kill:stray": "ледени равнини нощем", "kill:slime": "блата нощем",
    "breed:chicken": "семена", "breed:cow": "жито", "breed:pig": "моркови", "breed:sheep": "жито", "breed:rabbit": "хванати зайци + моркови",
    "breed:bee": "пчели от гнезда + цветя, 5 мин почивка", "breed:goat": "кози от планини, жито", "breed:llama": "лами + бали сено",
    "breed:turtle": "костенурки на плаж + морска трева", "tame:wolf": "кокали, гора", "tame:horse": "равнини, ябълки/захар",
    "tame:donkey": "равнини", "tame:llama": "планини / савана", "tame:parrot": "джунгла, семена", "tame:cat": "село / блато, риба",
    "cure": "зомби селянин + отвара Слабост + златна ябълка", "brew:healing": "Недър брадавица + блестящ пъпеш",
    "brew:strong_strength": "блейз прах + светещ прах", "enchant": "маса за омагьосване + XP (няма ферма)",
    "summon:iron_golem": "4 железни блока (36 желязо) + тиква", "mine:ancient_debris": "Недър, легла / TNT",
    "fill_bucket:axolotl_bucket": "буйни пещери", "fill_bucket:tropical_fish_bucket": "топло море",
    "catch:pufferfish": "въдица, рядко", "plant:wheat_seeds": "фермата", "bonemeal": "костно брашно от компостер",
    "shear:mooshroom": "гъбени полета (рядък биом)",
}


def need_of(task):
    """A short Bulgarian description of what a task takes."""
    out = []
    for o in task.get("objectives", [task]):
        for key, target in o.items():
            if key in ("count", "factor", "min_level", "value", "weight", "tiers", "market", "title", "reward"):
                continue
            if not isinstance(target, str):
                continue
            t = target.split(":")[-1] if not target.startswith("#") else target
            if key == "deliver":
                text = NEED.get(t)
            elif key in ("visit_structure", "visit_biome"):
                text = "пътуване"
            else:
                text = NEED.get(key + ":" + t) or NEED.get(key)
            if text and text not in out:
                out.append(text)
    return "; ".join(out)

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
    def eff(it, m, action):
        p = price_of(mc(it)) if action != "brew" else 0.0
        return priced_minutes(m, p, tier)
    total = sum(count * eff(it, m, action) for action, it, count, m in ORDERS[name])
    k = ((lo + hi) / 2) / total
    objectives = []
    minutes = 0.0
    market = 0.0
    slots = 0
    for action, it, count, m in ORDERS[name]:
        target = mc(it)
        n = 1 if it in ORDER_SINGLE else max(1, min(item_cap(target), round(count * k)))
        market += n * (price_of(target) if action != "brew" else 0.0)
        m = eff(it, m, action)
        objectives.append({action: target, "count": [n, n]})
        if action not in ("deliver", "brew"):
            objectives.append({"deliver": target, "count": [n, n]})
        minutes += n * m
        slots += math.ceil(n / stack_of(target)) if action != "brew" else 0
    if slots > ORDER_SLOTS:
        raise SystemExit(f"order {name} needs {slots} slots (at most {ORDER_SLOTS})")
    return {"title": "order." + name, "objectives": objectives, "count": [1, 1], "value": round(minutes, 2),
            "market": round(market, 3), "weight": 6}


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
        task = to_actions({k: v for k, v in entry.items() if k not in ("minutes", "fixed")} | {"value": entry["minutes"]})
        if "deliver" in task:
            task_objs = [{"deliver": task["deliver"]}]
        else:
            task_objs = task.get("objectives", [])
        market = market_of({"objectives": task_objs})
        minutes = priced_minutes(entry["minutes"], market, tier)
        if market > 0:
            task["market"] = round(market, 4)
        lo, hi = WINDOW[tier] if tier else NITWIT_WINDOW
        if entry.get("fixed"):
            a, b = entry["fixed"]
        else:
            a = max(1, math.ceil(lo / minutes))
            b = max(a, math.floor(hi / minutes))
            b = min(b, cap_of(task))
        task["count"] = [a, b]
        task["value"] = round(minutes, 4)
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


# ---- the audit table for the user: every task, what it takes, minutes, pay ----
AUDIT = os.path.join(os.path.dirname(__file__), "..", "docs", "TASK_AUDIT.md")
BG_NAMES = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "villagersremember", "vanilla_names", "bg_bg.json")
VERB = {"deliver": "донеси", "craft": "направи", "smelt": "изпечи", "station": "изсечи", "kill": "убий", "catch": "хвани",
        "breed": "развъди", "tame": "опитоми", "shear": "остражи", "harvest": "ожъни", "mine": "изкопай", "plant": "посади",
        "bonemeal": "натори", "fill_bucket": "напълни кофа", "visit_structure": "посети", "visit_biome": "посети",
        "cure": "излекувай зомби селянин", "brew": "свари", "enchant": "омагьоса предмет", "summon": "построй"}
LANG_BG = {}
PROF_BG = {"farmer": "Фермер", "butcher": "Касапин", "fisherman": "Рибар", "shepherd": "Овчар", "librarian": "Библиотекар",
           "cartographer": "Картограф", "cleric": "Клерик", "armorer": "Бронар", "weaponsmith": "Оръжейник",
           "toolsmith": "Инструментар", "leatherworker": "Кожар", "mason": "Зидар", "fletcher": "Стрелар", "nitwit": "Глупчо"}


def bg_name(names, target, entity=False):
    if target.startswith("#"):
        return target.split(":")[-1].replace("_", " ")
    path = target.split(":")[-1]
    for kind in (("entity", "item", "block") if entity else ("item", "block", "entity")):
        v = names.get(f"{kind}.minecraft.{path}")
        if v:
            return v
    return path.replace("_", " ")


def describe(names, task, lo, hi):
    if task.get("title"):
        parts = []
        for o in task["objectives"]:
            if "deliver" in o:
                parts.append(f"{o['count'][0]} {bg_name(names, o['deliver'])}")
        title = LANG_BG.get(task["title"], task["title"].split(".")[-1])
        return f"Поръчка „{title}“: " + ", ".join(parts)
    count = f"{lo}" if lo == hi else f"{lo}-{hi}"
    words = []
    objectives = task.get("objectives") or [{k: v for k, v in task.items() if k in VERB}]
    for o in objectives:
        for k, v in o.items():
            if k in ("count", "factor", "min_level") or not isinstance(v, str):
                continue
            verb = VERB.get(k, k)
            if k == "deliver" and len(objectives) > 1:
                continue
            target = bg_name(names, v, k in ("kill", "breed", "tame", "shear", "summon")) if v else ""
            f = o.get("factor", 1.0)
            a_n, b_n = max(1, round(lo * f)), max(1, round(hi * f))
            n = (f"{a_n}" if a_n == b_n else f"{a_n}-{b_n}") if k not in ("visit_structure", "visit_biome") else ""
            if k == "enchant":
                words.append(f"{verb} {count}× на {o.get('min_level', 0)}+ нива")
            elif k in ("cure",):
                words.append(f"{verb} ×{count}")
            else:
                words.append(f"{verb} {n}{'× ' if n else ''}{target}".strip())
    return ", ".join(words)


def write_audit(all_tasks):
    global LANG_BG
    names = json.load(open(BG_NAMES, encoding="utf-8"))
    LANG_BG = json.load(open(os.path.join(os.path.dirname(BG_NAMES), "..", "lang", "bg_bg.json"), encoding="utf-8"))
    out = ["# Одит на задачите: какво трябва да се направи",
           "",
           "Генерира се от `tools/gen_errands.py`. Играчът има автоматични ферми за растения и търговска зала, но няма",
           "ферми за мобове, желязо, злато, XP, нападения или риба. Минутите са реалистично време за една задача;",
           "наградата е в изумруди + опит (опитът е сметнат по 6 точки за изумруд). „Продажба“ е колко биха дали",
           "селяните за стоката; изумрудите от задачата са поне 1,5× това.",
           "",
           "Прозорци: T1 4-8 мин, T2 8-12, T3 12-18, T4 18-25, T5 25-35. Плащане на минута: 0,6 / 0,8 / 1,0 / 1,3 / 1,6.",
           ""]
    for prof, tasks in all_tasks:
        out.append(f"## {PROF_BG.get(prof, prof)}")
        out.append("")
        out.append("| Ниво | Задача | Какво трябва | Минути | Награда | Продажба |")
        out.append("|---|---|---|---|---|---|")
        for t in tasks:
            tier = t["tiers"][0] if t["tiers"][0] == t["tiers"][1] else 1
            lo, hi = t["count"]
            if t.get("reward"):
                out.append(f"| T{tier} | Преизковаване: {describe(names, t, t['objectives'][0]['count'][0], t['objectives'][0]['count'][0])} | "
                           f"еднократно, наградата е омагьосан предмет | - | омагьосан предмет | - |")
                continue
            rate = RATE.get(tier, 0.6) if prof != "nitwit" else 0.6
            m_lo, m_hi = lo * t["value"], hi * t["value"]
            pay = f"{m_lo * rate:.0f}" if lo == hi else f"{m_lo * rate:.0f}-{m_hi * rate:.0f}"
            mins = f"{m_lo:.0f}" if lo == hi else f"{m_lo:.0f}-{m_hi:.0f}"
            sell = t.get("market", 0) * (1 if t.get("title") else hi)
            sell_s = f"{sell:.1f}" if sell else "-"
            label = "T1-5" if t["tiers"][0] != t["tiers"][1] else f"T{tier}"
            out.append(f"| {label} | {describe(names, t, lo, hi)} | {need_of(t)} | {mins} | {pay} | {sell_s} |")
        out.append("")
    os.makedirs(os.path.dirname(AUDIT), exist_ok=True)
    with open(AUDIT, "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")



def main():
    os.makedirs(OUT, exist_ok=True)
    add_top_rewards()
    items, entities = known_ids()
    problems = []
    lines = []
    biggest = []
    audit = []
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
        audit.append((prof, tasks))
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
        for t in tasks:
            if t.get("reward") or not t.get("market"):
                continue
            tier = t["tiers"][0] if t["tiers"][0] == t["tiers"][1] else 1
            n = t["count"][1]
            sell = n * t["market"]
            pay = n * t["value"] * (RATE.get(tier, 0.6) if prof != "nitwit" else 0.6)
            biggest.append((sell, prof, tier, name_of(t), n, pay))
            if pay < sell * MARKET_PREMIUM / EMERALD_SHARE * 0.99:
                problems.append(f"{prof} T{tier} {name_of(t)}: pays {pay:.1f}, goods sell for {sell:.1f}")
    print("\n".join(lines))
    write_audit(audit)
    biggest.sort(reverse=True)
    print("\nBiggest goods (most a villager would pay for them / what the errand pays in emeralds' worth):")
    for sell, prof, tier, name, n, pay in biggest[:8]:
        print(f"  {prof} T{tier} {name} x{n}: sell {sell:.1f} / errand {pay:.1f}")
    if problems:
        print("\nTasks outside their window:")
        print("\n".join(problems))
        raise SystemExit(1)


if __name__ == "__main__":
    main()
