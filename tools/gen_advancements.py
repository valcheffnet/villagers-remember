"""Writes the mod's advancement tree (data/villageerrands/advancement/**.json) and its en/bg names.

Every advancement has one "impossible" criterion: Milestones.java grants it when its condition holds, so the ids
here must match the ones there. Titles use translate + fallback: a vanilla client shows the English fallback, a
client with the mod installed shows its own language.

Run: python tools/gen_advancements.py
"""
import json
import os
import shutil

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
OUT = os.path.join(ROOT, "src/main/resources/data/villageerrands/advancement")
LANG = os.path.join(ROOT, "src/main/resources/assets/villageerrands/lang")

# id, parent, icon, frame, (en title, en description), (bg title, bg description)
A = [
    ("root", None, "bell", "task", ("Village Errands", "Lend a villager a hand"),
     ("Селски поръчки", "Подай ръка на селянин")),

    ("errands/first", "root", "writable_book", "task", ("Odd Jobs", "Complete an errand for a villager"),
     ("Дребна работа", "Изпълни поръчка за селянин")),
    ("errands/ten", "errands/first", "emerald", "task", ("Helping Hand", "Complete 10 errands"),
     ("Помощна ръка", "Изпълни 10 поръчки")),
    ("errands/fifty", "errands/ten", "iron_ingot", "goal", ("Village Workhorse", "Complete 50 errands"),
     ("Селският работен кон", "Изпълни 50 поръчки")),
    ("errands/two_hundred", "errands/fifty", "gold_block", "challenge", ("Pillar of the Community", "Complete 200 errands"),
     ("Стълб на общността", "Изпълни 200 поръчки")),
    ("errands/order", "errands/first", "barrel", "task", ("Bulk Order", "Complete an order for several goods"),
     ("Голяма поръчка", "Изпълни поръчка за няколко стоки")),
    ("errands/reforge", "errands/first", "anvil", "task", ("Reforged", "Complete a reforge errand for a smith"),
     ("Преизковано", "Изпълни поръчка за преизковаване")),
    ("errands/top", "errands/ten", "nether_star", "goal", ("Masterwork", "Earn a master's top reward"),
     ("Шедьовър", "Спечели топ награда от майстор")),

    ("trust/acquaintance", "root", "poppy", "task", ("A Familiar Face", "Become an Acquaintance of a villager"),
     ("Познато лице", "Стани познат на селянин")),
    ("trust/trial", "trust/acquaintance", "golden_carrot", "task", ("Trust Earned", "Pass a trial of trust"),
     ("Спечелено доверие", "Мини изпит за доверие")),
    ("trust/best_friend", "trust/trial", "cake", "goal", ("Best Friends", "Become a villager's Best friend"),
     ("Най-добри приятели", "Стани най-добър приятел на селянин")),
    ("trust/close_circle", "trust/best_friend", "totem_of_undying", "challenge", ("Close Circle", "Become Best friends with 5 villagers"),
     ("Близък кръг", "Стани най-добър приятел на 5 селяни")),
    ("contracts/first", "trust/best_friend", "paper", "task", ("Under Contract", "Complete a contract for a Best friend"),
     ("По договор", "Изпълни договор за най-добър приятел")),
    ("contracts/legend", "contracts/first", "netherite_ingot", "goal", ("Legendary", "Earn a legendary item"),
     ("Легендарно", "Спечели легендарен предмет")),
    ("contracts/all_legends", "contracts/legend", "dragon_egg", "challenge", ("Hall of Legends", "Collect every legendary item"),
     ("Залата на легендите", "Събери всички легендарни предмети")),

    ("villages/known", "root", "oak_door", "task", ("Known Around Here", "Become Known in a village"),
     ("Тук те познават", "Стани познат в село")),
    ("villages/respected", "villages/known", "emerald_block", "goal", ("Respected", "Earn respect in a village"),
     ("Уважаван", "Спечели уважението на село")),
    ("villages/hero", "villages/respected", "white_banner", "goal", ("Beloved", "Become the hero of a village through errands"),
     ("Любимец", "Стани герой на село с поръчки")),
    ("villages/patron", "villages/hero", "beacon", "challenge", ("Patron", "Become the patron of a village"),
     ("Покровител", "Стани покровител на село")),
    ("villages/weekly", "villages/known", "hay_block", "task", ("Many Hands", "Help finish a village order"),
     ("Много ръце", "Помогни да се изпълни поръчка на селото")),

    ("explore/first", "root", "compass", "task", ("New Neighbours", "Discover a village"),
     ("Нови съседи", "Открий село")),
    ("explore/five", "explore/first", "map", "task", ("Well Travelled", "Discover 5 villages"),
     ("Пътешественик", "Открий 5 села")),
    ("explore/fifteen", "explore/five", "filled_map", "goal", ("Village Atlas", "Discover 15 villages"),
     ("Атлас на селата", "Открий 15 села")),
    ("explore/thirty", "explore/fifteen", "recovery_compass", "challenge", ("Road Warrior", "Discover 30 villages"),
     ("Пътят е мой дом", "Открий 30 села")),

    ("treasury/first", "root", "ender_chest", "task", ("First Wages", "Send treasury emeralds to your Ender Chest"),
     ("Първа заплата", "Прати изумруди от хазната в Ender Chest-а")),
    ("treasury/income", "treasury/first", "gold_ingot", "goal", ("Steady Income", "Earn 10 emeralds a day from your treasuries"),
     ("Стабилен доход", "Изкарвай по 10 изумруда на ден от хазната")),
    ("treasury/network", "treasury/income", "lodestone", "challenge", ("Village Network", "Be a Friend of 5 villages for the full network bonus"),
     ("Мрежа от села", "Стани приятел на 5 села за пълния бонус")),

    ("fame/helper", "root", "name_tag", "task", ("Helper", "Earn the title Helper"),
     ("Помощник", "Спечели титлата Помощник")),
    ("fame/benefactor", "fame/helper", "golden_apple", "goal", ("Benefactor", "Earn the title Benefactor"),
     ("Благодетел", "Спечели титлата Благодетел")),
    ("fame/legend", "fame/benefactor", "enchanted_golden_apple", "challenge", ("Legend", "Earn the title Legend"),
     ("Легенда", "Спечели титлата Легенда")),
]

COLLECTION = [
    ("armorer", "blast_furnace", "Armorer", "бронаря"),
    ("butcher", "smoker", "Butcher", "месаря"),
    ("cartographer", "cartography_table", "Cartographer", "картографа"),
    ("cleric", "brewing_stand", "Cleric", "свещеника"),
    ("farmer", "composter", "Farmer", "фермера"),
    ("fisherman", "barrel", "Fisherman", "рибаря"),
    ("fletcher", "fletching_table", "Fletcher", "стрелара"),
    ("leatherworker", "cauldron", "Leatherworker", "кожаря"),
    ("librarian", "lectern", "Librarian", "библиотекаря"),
    ("mason", "stonecutter", "Mason", "зидаря"),
    ("nitwit", "green_wool", "Nitwit", "глупчото"),
    ("shepherd", "loom", "Shepherd", "овчаря"),
    ("toolsmith", "smithing_table", "Toolsmith", "инструментаря"),
    ("weaponsmith", "grindstone", "Weaponsmith", "оръжейника"),
]
for prof, icon, en, bg in COLLECTION:
    A.append(("collection/" + prof, "errands/ten", icon, "goal",
              (en + "'s Book", "Complete every errand a " + en.lower() + " gives"),
              ("Книгата на " + bg, "Изпълни всяка поръчка на " + bg)))
A.append(("collection/all", "errands/two_hundred", "knowledge_book", "challenge",
          ("Master of All Trades", "Complete every errand of every profession"),
          ("Майстор на всички занаяти", "Изпълни всяка поръчка на всяка професия")))


def key(aid, part):
    return "advancement.villageerrands." + aid.replace("/", ".") + "." + part


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    en, bg = {}, {}
    for aid, parent, icon, frame, (et, ed), (bt, bd) in A:
        display = {
            "icon": {"id": "minecraft:" + icon},
            "title": {"translate": key(aid, "title"), "fallback": et},
            "description": {"translate": key(aid, "description"), "fallback": ed},
            "frame": frame,
            "show_toast": aid != "root",
            "announce_to_chat": aid != "root",
            "hidden": False,
        }
        if aid == "root":
            display["background"] = "minecraft:gui/advancements/backgrounds/adventure"
        data = {}
        if parent:
            data["parent"] = "villageerrands:" + parent
        data["criteria"] = {"done": {"trigger": "minecraft:impossible"}}
        data["display"] = display
        data["requirements"] = [["done"]]
        path = os.path.join(OUT, aid + ".json")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
            f.write("\n")
        en[key(aid, "title")], en[key(aid, "description")] = et, ed
        bg[key(aid, "title")], bg[key(aid, "description")] = bt, bd
    for lang, values in (("en_us", en), ("bg_bg", bg)):
        p = os.path.join(LANG, lang + ".json")
        d = json.load(open(p, encoding="utf-8"))
        d = {k: v for k, v in d.items() if not k.startswith("advancement.villageerrands.")}
        d.update(values)
        with open(p, "w", encoding="utf-8") as f:
            f.write(json.dumps(d, ensure_ascii=False, indent="\t") + "\n")
    print(len(A), "advancements")


if __name__ == "__main__":
    main()
