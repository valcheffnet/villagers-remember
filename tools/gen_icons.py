"""Builds src/main/resources/assets/villagersremember/icons.json: item id -> "atlas|sprite" for inline text icons.

A dedicated server has no textures, so the sprite each item uses is looked up here from the client jar.
Items drawn from a block model use one of the block's textures from the blocks atlas.
Run: python tools/gen_icons.py [path/to/minecraft-client.jar]
"""
import json
import os
import sys
import zipfile

JAR = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser(
    "~/.gradle/caches/fabric-loom/26.3/minecraft-client.jar")
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "villagersremember", "icons.json")
BLOCK_KEYS = ["front", "side", "all", "texture", "end", "top", "cross", "particle"]


def ref_of(value):
    if isinstance(value, dict):
        value = value.get("sprite")
    return value if isinstance(value, str) else None


def strip(ref):
    return ref.split(":", 1)[1] if ":" in ref else ref


def main():
    z = zipfile.ZipFile(JAR)
    names = set(z.namelist())

    def model(path):
        f = "assets/minecraft/models/" + strip(path) + ".json"
        return json.loads(z.read(f)) if f in names else None

    def textures(path, depth=0):
        m = model(path)
        if m is None or depth > 8:
            return {}
        out = textures(m["parent"], depth + 1) if "parent" in m else {}
        out.update(m.get("textures", {}))
        return out

    icons = {}
    for f in sorted(names):
        if not (f.startswith("assets/minecraft/items/") and f.endswith(".json")):
            continue
        item = f.split("/")[-1][:-5]
        d = json.loads(z.read(f))["model"]
        # Follow simple wrappers (conditions/selects) to their fallback/first plain model.
        for _ in range(6):
            if d.get("type") == "minecraft:model":
                break
            nxt = d.get("fallback") or d.get("on_false") or (d.get("cases") or [{}])[0].get("model") or d.get("model")
            if not isinstance(nxt, dict):
                break
            d = nxt
        if d.get("type") != "minecraft:model":
            continue
        tex = textures(d["model"])
        sprite = ref_of(tex.get("layer0"))
        if sprite and strip(sprite).startswith("item/"):
            icons["minecraft:" + item] = "minecraft:items|minecraft:" + strip(sprite)
            continue
        for key in BLOCK_KEYS:
            ref = ref_of(tex.get(key))
            if ref and not ref.startswith("#") and strip(ref).startswith("block/"):
                icons["minecraft:" + item] = "minecraft:blocks|minecraft:" + strip(ref)
                break
    with open(OUT, "w", encoding="utf-8") as out:
        json.dump(icons, out, indent=0, sort_keys=True)
    print(len(icons), "icons ->", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
