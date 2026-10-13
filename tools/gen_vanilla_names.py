"""Copies the vanilla names the mod shows (items, blocks, mobs, biomes, potions, levels) for the languages it supports.

A dedicated server only knows English names, and the names decide how wide a line of text is, which the dialogs
need to line text up (see TextWidth.java). Run after a Minecraft update:
    python tools/gen_vanilla_names.py
"""
import json
import os
import zipfile

MC = "26.3"
HOME = os.path.expanduser("~")
CLIENT = os.path.join(HOME, ".gradle/caches/fabric-loom", MC, "minecraft-client.jar")
ASSETS = os.path.join(HOME, ".gradle/caches/fabric-loom/assets")
OUT = os.path.join(os.path.dirname(__file__), "..", "src/main/resources/assets/villageerrands/vanilla_names")
LANGUAGES = ["en_us", "bg_bg"]
PREFIXES = ("item.minecraft.", "block.minecraft.", "entity.minecraft.", "biome.minecraft.", "merchant.level.")


def index():
    names = sorted(n for n in os.listdir(os.path.join(ASSETS, "indexes")) if n.startswith(MC + "-") or n == MC + ".json")
    return json.load(open(os.path.join(ASSETS, "indexes", names[-1]), encoding="utf-8"))["objects"]


def read_lang(lang, objects):
    if lang == "en_us":
        with zipfile.ZipFile(CLIENT) as z:
            return json.loads(z.read("assets/minecraft/lang/en_us.json").decode("utf-8"))
    h = objects["minecraft/lang/" + lang + ".json"]["hash"]
    return json.load(open(os.path.join(ASSETS, "objects", h[:2], h), encoding="utf-8"))


def main():
    objects = index()
    os.makedirs(OUT, exist_ok=True)
    for lang in LANGUAGES:
        d = read_lang(lang, objects)
        # Long descriptions (smithing templates, banner patterns...) are never shown as a target name.
        kept = {k: v for k, v in sorted(d.items()) if k.startswith(PREFIXES) and len(v) <= 40}
        with open(os.path.join(OUT, lang + ".json"), "w", encoding="utf-8") as f:
            json.dump(kept, f, ensure_ascii=False, separators=(",", ":"))
        print(lang, len(kept))


if __name__ == "__main__":
    main()
