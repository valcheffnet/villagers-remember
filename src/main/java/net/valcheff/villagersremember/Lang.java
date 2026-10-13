package net.valcheff.villagersremember;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side translations. A vanilla client has no language file for this mod, so text is resolved here
 * from the language the client reports. Vanilla names (items, mobs, professions) stay translatable components
 * and are translated by the client itself.
 */
public final class Lang {
	private static final String[] LANGUAGES = {"en_us", "bg_bg"};
	private static final Map<String, Map<String, String>> STRINGS = new HashMap<>();
	private static final Map<String, List<String>> VILLAGER_NAMES = new HashMap<>();
	private static final Map<String, List<String>> VILLAGE_NAMES = new HashMap<>();

	public static void load() {
		for (String lang : LANGUAGES) {
			JsonObject json = read("assets/villagersremember/lang/" + lang + ".json");
			if (json == null) continue;
			Map<String, String> map = new HashMap<>();
			for (Map.Entry<String, JsonElement> e : json.entrySet()) {
				if (e.getValue().isJsonPrimitive()) map.put(e.getKey(), e.getValue().getAsString());
			}
			STRINGS.put(lang, map);
			JsonObject names = read("assets/villagersremember/names/" + lang + ".json");
			if (names != null) {
				VILLAGER_NAMES.put(lang, list(names, "villagers"));
				VILLAGE_NAMES.put(lang, list(names, "villages"));
			}
		}
	}

	private static List<String> list(JsonObject json, String key) {
		List<String> out = new ArrayList<>();
		if (json.has(key) && json.get(key).isJsonArray()) json.getAsJsonArray(key).forEach(e -> out.add(e.getAsString()));
		return out;
	}

	private static JsonObject read(String path) {
		try (InputStream in = Lang.class.getClassLoader().getResourceAsStream(path)) {
			if (in == null) return null;
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			VillagersRemember.LOG.warn("Could not read {}: {}", path, e.toString());
			return null;
		}
	}

	public static String language(ServerPlayer player) {
		String forced = ErrandsConfig.INSTANCE.language.toLowerCase(Locale.ROOT);
		String lang = forced.equals("auto") ? player.clientInformation().language().toLowerCase(Locale.ROOT) : forced;
		return STRINGS.containsKey(lang) ? lang : "en_us";
	}

	public static String raw(String lang, String key) {
		Map<String, String> map = STRINGS.get(lang);
		String value = map != null ? map.get(key) : null;
		if (value == null) value = STRINGS.getOrDefault("en_us", Map.of()).get(key);
		return value != null ? value : key;
	}

	/** Translates {@code key} for the player; each {@code %s} is replaced by the next argument (String or Component). */
	public static MutableComponent tr(ServerPlayer player, String key, Object... args) {
		return format(raw(language(player), key), args);
	}

	public static MutableComponent format(String pattern, Object... args) {
		MutableComponent out = Component.empty();
		int argIndex = 0;
		int start = 0;
		int at;
		while ((at = pattern.indexOf("%s", start)) >= 0) {
			if (at > start) out.append(pattern.substring(start, at));
			if (argIndex < args.length) {
				Object arg = args[argIndex++];
				if (arg instanceof Component c) out.append(c);
				else out.append(String.valueOf(arg));
			}
			start = at + 2;
		}
		if (start < pattern.length()) out.append(pattern.substring(start));
		return out;
	}

	public static String villagerName(ServerPlayer player, long hash) {
		return pick(VILLAGER_NAMES, language(player), hash, "Villager");
	}

	public static String villageName(ServerPlayer player, long hash) {
		return pick(VILLAGE_NAMES, language(player), hash, "Village");
	}

	/** How many village names there are (English list); names are handed out by index so that none repeats. */
	public static int villageNameCount() {
		return Math.max(1, VILLAGE_NAMES.getOrDefault("en_us", List.of()).size());
	}

	/** The village name with this index; past the end of the list the names come round again as "Foxhollow 2". */
	public static String villageNameAt(ServerPlayer player, int index) {
		List<String> names = VILLAGE_NAMES.getOrDefault(language(player), List.of());
		if (names.isEmpty()) names = VILLAGE_NAMES.getOrDefault("en_us", List.of());
		if (names.isEmpty()) return "Village " + (index + 1);
		String name = names.get(index % names.size());
		return index < names.size() ? name : name + " " + (index / names.size() + 1);
	}

	private static String pick(Map<String, List<String>> pools, String lang, long hash, String fallback) {
		List<String> names = pools.getOrDefault(lang, List.of());
		if (names.isEmpty()) names = pools.getOrDefault("en_us", List.of());
		if (names.isEmpty()) return fallback;
		return names.get((int) Math.floorMod(hash, (long) names.size()));
	}
}
