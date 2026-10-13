package net.valcheff.villageerrands;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Errand pools per profession, loaded from data packs: {@code data/<namespace>/village_errands/*.json}.
 * Files for the same profession are merged unless one sets {@code "replace": true}.
 */
public final class ErrandPools {
	/** ENCHANT_MAX: enchanted like ENCHANT, then every enchantment it got is raised to its highest level. */
	public enum RewardKind { ITEM, LOOT, ENCHANT, ENCHANT_MAX, TOP }

	/**
	 * One objective of a task. Its count is the task's rolled count × {@code factor}, or its own range when
	 * {@code fixedMin} > 0 (for orders that ask for different amounts of different things).
	 */
	public record ObjectiveTemplate(Objective.Type type, String target, boolean tag, double factor,
	                                int fixedMin, int fixedMax, int minLevel) {
	}

	/**
	 * A task: objectives done together. Value is per rolled unit, in emeralds. A fixed reward replaces the roll
	 * from the profession's reward list (reforge errands: the same item back, enchanted).
	 */
	public record Task(String key, List<ObjectiveTemplate> objectives, java.util.Optional<String> title,
	                   int min, int max, double value, int minTier, int maxTier, int weight,
	                   java.util.Optional<Reward> reward) {
	}

	/** For ITEM: value per item. For LOOT: value of one roll. For ENCHANT: value per enchanting level. */
	public record Reward(RewardKind kind, String id, int min, int max, double value, int minTier, int maxTier) {
	}

	public record Pool(List<Task> tasks, List<Reward> rewards) {
	}

	private static Map<String, Pool> pools = Map.of();

	public static Pool get(String professionId) {
		return pools.get(professionId);
	}

	public static void reload(MinecraftServer server) {
		Map<String, List<Task>> tasks = new HashMap<>();
		Map<String, List<Reward>> rewards = new HashMap<>();
		Map<Identifier, Resource> files = new TreeMap<>(server.getResourceManager()
			.listResources("village_errands", path -> path.getPath().endsWith(".json")));
		for (Map.Entry<Identifier, Resource> file : files.entrySet()) {
			try (Reader reader = file.getValue().openAsReader()) {
				JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
				String profession = json.get("profession").getAsString();
				if (!profession.contains(":")) profession = "minecraft:" + profession;
				if (json.has("replace") && json.get("replace").getAsBoolean()) {
					tasks.remove(profession);
					rewards.remove(profession);
				}
				List<Task> t = tasks.computeIfAbsent(profession, k -> new ArrayList<>());
				for (JsonElement e : array(json, "tasks")) {
					Task task = parseTask(e.getAsJsonObject(), file.getKey());
					if (task != null) t.add(task);
				}
				List<Reward> r = rewards.computeIfAbsent(profession, k -> new ArrayList<>());
				for (JsonElement e : array(json, "rewards")) {
					Reward reward = parseReward(e.getAsJsonObject(), file.getKey());
					if (reward != null) r.add(reward);
				}
			} catch (Exception e) {
				VillageErrands.LOG.warn("Skipping errand file {}: {}", file.getKey(), e.toString());
			}
		}
		Map<String, Pool> out = new HashMap<>();
		for (String profession : tasks.keySet()) {
			out.put(profession, new Pool(List.copyOf(tasks.get(profession)), List.copyOf(rewards.getOrDefault(profession, List.of()))));
		}
		pools = out;
		ErrandGenerator.clearCache();
		VillageErrands.LOG.info("Loaded errand pools for {} professions", out.size());
	}

	private static JsonArray array(JsonObject json, String key) {
		return json.has(key) ? json.getAsJsonArray(key) : new JsonArray();
	}

	/**
	 * A task is either the short form {@code {"deliver": "minecraft:wheat"}} / {@code {"kill": ...}} or
	 * {@code {"objectives": [{"harvest": "minecraft:wheat"}, {"deliver": "minecraft:wheat"}], ...}}.
	 */
	private static Task parseTask(JsonObject o, Identifier file) {
		List<ObjectiveTemplate> objectives = new ArrayList<>();
		if (o.has("objectives")) {
			for (JsonElement e : o.getAsJsonArray("objectives")) {
				ObjectiveTemplate t = parseObjective(e.getAsJsonObject(), file);
				if (t == null) return null;
				objectives.add(t);
			}
		} else {
			ObjectiveTemplate t = parseObjective(o, file);
			if (t == null) return null;
			objectives.add(t);
		}
		if (objectives.isEmpty()) {
			VillageErrands.LOG.warn("{}: task without objectives", file);
			return null;
		}
		int[] count = range(o, "count", 1, 1);
		// The packs a task belongs to; "levels" is the older name, from before the packs.
		int[] levels = range(o, o.has("tiers") ? "tiers" : "levels", 1, 5);
		double value = o.has("value") ? o.get("value").getAsDouble() : 0.1;
		int weight = o.has("weight") ? o.get("weight").getAsInt() : 10;
		if (weight <= 0) return null;
		java.util.Optional<String> title = o.has("title") ? java.util.Optional.of(o.get("title").getAsString()) : java.util.Optional.empty();
		java.util.Optional<Reward> reward = java.util.Optional.empty();
		if (o.has("reward")) {
			JsonObject r = o.getAsJsonObject("reward").deepCopy();
			if (!r.has("value_per_level")) r.addProperty("value_per_level", 0);
			if (!r.has("value")) r.addProperty("value", 0);
			reward = java.util.Optional.ofNullable(parseReward(r, file));
			if (reward.isEmpty()) return null;
		}
		StringBuilder key = new StringBuilder();
		for (ObjectiveTemplate t : objectives) key.append(t.type()).append(t.target()).append(';');
		return new Task(key.toString(), List.copyOf(objectives), title, count[0], count[1], value, levels[0], levels[1], weight, reward);
	}

	private static ObjectiveTemplate parseObjective(JsonObject o, Identifier file) {
		Objective.Type type = null;
		for (Objective.Type t : Objective.Type.values()) {
			if (o.has(t.key())) {
				type = t;
				break;
			}
		}
		if (type == null) {
			VillageErrands.LOG.warn("{}: objective without a known type: {}", file, o);
			return null;
		}
		String raw = o.get(type.key()).getAsString();
		boolean tag = raw.startsWith("#");
		String target = tag ? raw.substring(1) : raw;
		if (!target.isEmpty()) {
			Identifier id = Identifier.tryParse(target);
			if (id == null || (!tag && !exists(type.target, id))) {
				VillageErrands.LOG.warn("{}: unknown {} {}", file, type.target.name().toLowerCase(java.util.Locale.ROOT), raw);
				return null;
			}
			target = id.toString();
		}
		double factor = o.has("factor") ? o.get("factor").getAsDouble() : 1.0;
		int[] fixed = o.has("count") ? range(o, "count", 0, 0) : new int[]{0, 0};
		int minLevel = o.has("min_level") ? o.get("min_level").getAsInt() : 0;
		return new ObjectiveTemplate(type, target, tag, factor, fixed[0], fixed[1], minLevel);
	}

	/** Structures and biomes are data-driven and only checked when an errand is generated. */
	private static boolean exists(Objective.Target kind, Identifier id) {
		return switch (kind) {
			case ITEM -> BuiltInRegistries.ITEM.containsKey(id);
			case ENTITY -> BuiltInRegistries.ENTITY_TYPE.containsKey(id);
			case BLOCK -> BuiltInRegistries.BLOCK.containsKey(id);
			case POTION -> BuiltInRegistries.POTION.containsKey(id);
			case STRUCTURE, BIOME, NONE -> true;
		};
	}

	private static Reward parseReward(JsonObject o, Identifier file) {
		int minLevel = o.has("min_tier") ? o.get("min_tier").getAsInt() : o.has("min_level") ? o.get("min_level").getAsInt() : 1;
		// The last pack it can come from, so cheaper rewards stop where better ones start.
		int maxTier = o.has("max_tier") ? o.get("max_tier").getAsInt() : 5;
		if (o.has("item")) {
			Identifier id = Identifier.tryParse(o.get("item").getAsString());
			if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
				VillageErrands.LOG.warn("{}: unknown reward item {}", file, o.get("item"));
				return null;
			}
			int[] count = range(o, "count", 1, 1);
			return new Reward(RewardKind.ITEM, id.toString(), count[0], count[1], o.get("value").getAsDouble(), minLevel, maxTier);
		}
		if (o.has("top")) {
			// The profession's top reward (TopRewards); the errand fills in the profession.
			return new Reward(RewardKind.TOP, "", 1, 1, o.get("value").getAsDouble(), minLevel, maxTier);
		}
		if (o.has("loot_table")) {
			return new Reward(RewardKind.LOOT, o.get("loot_table").getAsString(), 1, 1, o.get("value").getAsDouble(), minLevel, maxTier);
		}
		if (o.has("enchant")) {
			Identifier id = Identifier.tryParse(o.get("enchant").getAsString());
			if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
				VillageErrands.LOG.warn("{}: unknown item to enchant {}", file, o.get("enchant"));
				return null;
			}
			int[] levels = range(o, "levels", 5, 30);
			boolean max = o.has("max_levels") && o.get("max_levels").getAsBoolean();
			return new Reward(max ? RewardKind.ENCHANT_MAX : RewardKind.ENCHANT, id.toString(), levels[0], levels[1],
				o.get("value_per_level").getAsDouble(), minLevel, maxTier);
		}
		VillageErrands.LOG.warn("{}: reward needs 'item', 'loot_table' or 'enchant'", file);
		return null;
	}

	private static int[] range(JsonObject o, String key, int defMin, int defMax) {
		if (!o.has(key)) return new int[]{defMin, defMax};
		JsonElement e = o.get(key);
		if (e.isJsonArray()) {
			int a = e.getAsJsonArray().get(0).getAsInt();
			int b = e.getAsJsonArray().get(1).getAsInt();
			return new int[]{Math.min(a, b), Math.max(a, b)};
		}
		int v = e.getAsInt();
		return new int[]{v, v};
	}
}
