package net.valcheff.villageerrands;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The top reward of each profession (TIERS.md section 4), given by T5 errands. The player chooses one of its
 * options on the errand screen once the errand is accepted; nothing about it is left to chance at hand-in.
 * Options are rebuilt from the errand's seed whenever they are shown or given. Names are translatable with an
 * English fallback, so a vanilla client shows English and a client with the mod shows its own language.
 */
public final class TopRewards {
	private static final List<String> TRIMS = List.of("bolt", "coast", "dune", "eye", "flow", "host", "raiser", "rib",
		"sentry", "shaper", "silence", "snout", "spire", "tide", "vex", "ward", "wayfinder", "wild");
	private static final List<String> BANNERS = List.of("globe", "piglin", "flow", "guster");
	private static final List<String> SHERDS = List.of("angler", "archer", "arms_up", "blade", "brewer", "burn", "danger",
		"explorer", "flow", "friend", "guster", "heart", "heartbreak", "howl", "miner", "mourner", "plenty", "prize", "scrape",
		"sheaf", "shelter", "skull", "snort");
	/** Treasure map kinds: loot table data/villageerrands/loot_table/top/map_<kind>.json and the English name. */
	private static final Map<String, String> MAPS = new LinkedHashMap<>();
	/** The librarian's book table: weight per book, groups common 7.9 %, rare 5.3 %, very rare 3.9 % each. */
	private static final Map<ResourceKey<Enchantment>, Integer> BOOKS = new LinkedHashMap<>();

	static {
		MAPS.put("mansion", "Woodland Mansion Map");
		MAPS.put("monument", "Ocean Monument Map");
		MAPS.put("trial_chambers", "Trial Chambers Map");
		MAPS.put("buried_treasure", "Buried Treasure Map");
		for (var e : List.of(Enchantments.SHARPNESS, Enchantments.PROTECTION, Enchantments.EFFICIENCY, Enchantments.POWER,
			Enchantments.UNBREAKING, Enchantments.FEATHER_FALLING)) BOOKS.put(e, 79);
		for (var e : List.of(Enchantments.FORTUNE, Enchantments.LOOTING, Enchantments.SILK_TOUCH, Enchantments.INFINITY,
			Enchantments.SWEEPING_EDGE, Enchantments.RESPIRATION, Enchantments.DEPTH_STRIDER)) BOOKS.put(e, 53);
		for (var e : List.of(Enchantments.MENDING, Enchantments.SWIFT_SNEAK, Enchantments.WIND_BURST, Enchantments.SOUL_SPEED)) BOOKS.put(e, 39);
	}

	/** The chosen option; {@code origin} is where the player stands (for treasure maps), null for a preview. */
	public static List<ItemStack> create(String profession, ServerLevel level, long seed, Vec3 origin, int choice) {
		List<List<ItemStack>> options = options(profession, level, seed, origin);
		return options.get(Math.clamp(choice, 0, options.size() - 1));
	}

	public static boolean hasChoice(net.minecraft.server.level.ServerPlayer player, Errand errand) {
		return errand.item().map(i -> i.kind() == ErrandPools.RewardKind.TOP
			&& options(i.id(), (ServerLevel) player.level(), i.seed(), null).size() > 1).orElse(false);
	}

	/**
	 * What sets option {@code k} apart from the others of the same item: its enchantments the others do not have,
	 * with a short word on what they do ("Fortune III: more drops"). Null when nothing does.
	 */
	public static Component distinction(net.minecraft.server.level.ServerPlayer player, List<List<ItemStack>> options, int k) {
		ItemStack mine = options.get(k).getFirst();
		if (!mine.isEnchanted() && !mine.has(DataComponents.STORED_ENCHANTMENTS)) return null;
		var own = enchantmentsOf(mine);
		java.util.Set<Holder<Enchantment>> common = null;
		for (int i = 0; i < options.size(); i++) {
			ItemStack other = options.get(i).getFirst();
			if (i == k || !other.is(mine.getItem())) continue;
			java.util.Set<Holder<Enchantment>> keys = new java.util.HashSet<>(enchantmentsOf(other).keySet());
			if (common == null) common = keys;
			else common.retainAll(keys);
		}
		net.minecraft.network.chat.MutableComponent out = Component.empty();
		boolean any = false;
		for (var e : own.entrySet()) {
			if (common != null && common.contains(e.getKey())) continue;
			if (any) out.append(Component.literal(", ").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
			any = true;
			out.append(Enchantment.getFullname(e.getKey(), e.getValue()).copy().withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
			String key = "ench.desc." + e.getKey().unwrapKey().map(r -> r.identifier().getPath()).orElse("");
			String desc = Lang.raw(Lang.language(player), key);
			if (!desc.equals(key)) out.append(Component.literal(": " + desc).withStyle(net.minecraft.ChatFormatting.GRAY));
		}
		return any ? out : null;
	}

	private static Map<Holder<Enchantment>, Integer> enchantmentsOf(ItemStack stack) {
		var list = stack.getOrDefault(DataComponents.ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
		if (list.isEmpty()) list = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
		Map<Holder<Enchantment>, Integer> out = new LinkedHashMap<>();
		for (var e : list.entrySet()) out.put(e.getKey(), e.getIntValue());
		return out;
	}

	/**
	 * Every option the player can choose from, in order; at least one. Gear always has Unbreaking III and Mending
	 * (where vanilla allows it) and every other enchantment at its highest level; the options are the enchantments
	 * that cannot go together (Fortune or Silk Touch, Infinity or Mending...), so the choice is never a loss.
	 */
	public static List<List<ItemStack>> options(String profession, ServerLevel level, long seed, Vec3 origin) {
		RandomSource r = RandomSource.create(seed);
		List<List<ItemStack>> out = new ArrayList<>();
		switch (ErrandText.path(profession)) {
			case "farmer" -> {
				for (var drop : List.of(new Object[]{Enchantments.FORTUNE, 3}, new Object[]{Enchantments.SILK_TOUCH, 1})) {
					out.add(List.of(named(gear(level, Items.DIAMOND_HOE, Enchantments.EFFICIENCY, 5, drop[0], drop[1]), "elder_hoe", "Elder's Hoe")));
				}
				out.add(List.of(new ItemStack(Items.GOLDEN_CARROT, 64), new ItemStack(Items.GOLDEN_APPLE, 8)));
			}
			case "butcher" -> {
				for (var damage : List.of(new Object[]{Enchantments.SHARPNESS, 5}, new Object[]{Enchantments.SMITE, 5})) {
					out.add(List.of(named(gear(level, Items.NETHERITE_AXE, damage[0], damage[1], Enchantments.EFFICIENCY, 5,
						Enchantments.LOOTING, 3, Enchantments.FORTUNE, 3), "butchers_cleaver", "Butcher's Cleaver")));
				}
				out.add(List.of(new ItemStack(Items.GOLDEN_CARROT, 64), new ItemStack(Items.GOLDEN_APPLE, 4)));
			}
			case "fisherman" -> out.add(List.of(named(gear(level, Items.FISHING_ROD, Enchantments.LUCK_OF_THE_SEA, 3, Enchantments.LURE, 3),
				"anglers_pride", "Angler's Pride")));
			case "shepherd" -> {
				List<String> trims = new ArrayList<>(TRIMS);
				for (int i = 0; i < 2; i++) out.add(List.of(new ItemStack(item(trims.remove(r.nextInt(trims.size())) + "_armor_trim_smithing_template"))));
				out.add(List.of(new ItemStack(item(BANNERS.get(r.nextInt(BANNERS.size())) + "_banner_pattern"))));
				out.add(List.of(gear(level, Items.SHEARS, Enchantments.EFFICIENCY, 5, Enchantments.SILK_TOUCH, 1)));
			}
			case "librarian" -> {
				// Three books to choose from: each a book of the table plus two more that go with it, all at the highest level.
				Map<ResourceKey<Enchantment>, Integer> left = new LinkedHashMap<>(BOOKS);
				for (int i = 0; i < 3; i++) {
					ResourceKey<Enchantment> pick = weighted(left, r);
					left.remove(pick);
					out.add(List.of(book(level, pick, r)));
				}
			}
			case "cartographer" -> {
				for (var map : MAPS.entrySet()) out.add(List.of(treasureMap(level, map.getKey(), map.getValue(), seed, origin)));
			}
			case "cleric" -> {
				out.add(List.of(new ItemStack(Items.EXPERIENCE_BOTTLE, 64), new ItemStack(Items.GOLDEN_APPLE, 4)));
				out.add(List.of(new ItemStack(Items.TOTEM_OF_UNDYING)));
			}
			case "armorer" -> {
				out.add(List.of(gear(level, Items.NETHERITE_HELMET, Enchantments.PROTECTION, 4, Enchantments.RESPIRATION, 3, Enchantments.AQUA_AFFINITY, 1)));
				out.add(List.of(gear(level, Items.NETHERITE_CHESTPLATE, Enchantments.PROTECTION, 4, Enchantments.THORNS, 3)));
				out.add(List.of(gear(level, Items.NETHERITE_LEGGINGS, Enchantments.PROTECTION, 4, Enchantments.SWIFT_SNEAK, 3)));
				for (var water : List.of(new Object[]{Enchantments.DEPTH_STRIDER, 3}, new Object[]{Enchantments.FROST_WALKER, 2})) {
					out.add(List.of(gear(level, Items.NETHERITE_BOOTS, Enchantments.PROTECTION, 4, Enchantments.FEATHER_FALLING, 4,
						Enchantments.SOUL_SPEED, 3, water[0], water[1])));
				}
				out.add(List.of(new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2)));
			}
			case "weaponsmith" -> {
				for (var damage : List.of(new Object[]{Enchantments.SHARPNESS, 5}, new Object[]{Enchantments.SMITE, 5})) {
					out.add(List.of(gear(level, Items.NETHERITE_SWORD, damage[0], damage[1], Enchantments.LOOTING, 3, Enchantments.SWEEPING_EDGE, 3,
						Enchantments.FIRE_ASPECT, 2, Enchantments.KNOCKBACK, 2)));
				}
				for (var hit : List.of(new Object[]{Enchantments.DENSITY, 5}, new Object[]{Enchantments.BREACH, 4})) {
					out.add(List.of(gear(level, Items.MACE, hit[0], hit[1], Enchantments.WIND_BURST, 3, Enchantments.FIRE_ASPECT, 2)));
				}
			}
			case "toolsmith" -> {
				for (var drop : List.of(new Object[]{Enchantments.FORTUNE, 3}, new Object[]{Enchantments.SILK_TOUCH, 1})) {
					out.add(List.of(gear(level, Items.NETHERITE_PICKAXE, Enchantments.EFFICIENCY, 5, drop[0], drop[1])));
				}
				out.add(List.of(gear(level, Items.NETHERITE_SHOVEL, Enchantments.EFFICIENCY, 5, Enchantments.SILK_TOUCH, 1)));
			}
			case "leatherworker" -> {
				out.add(List.of(new ItemStack(Items.NETHERITE_HORSE_ARMOR)));
				out.add(List.of(gear(level, Items.WOLF_ARMOR)));
				out.add(List.of(new ItemStack(Items.NETHERITE_NAUTILUS_ARMOR)));
			}
			case "mason" -> {
				List<String> sherds = new ArrayList<>(SHERDS);
				List<ItemStack> set = new ArrayList<>();
				for (int i = 0; i < 4; i++) set.add(new ItemStack(item(sherds.remove(r.nextInt(sherds.size())) + "_pottery_sherd")));
				set.add(new ItemStack(Items.DECORATED_POT));
				out.add(set);
			}
			case "fletcher" -> {
				// Infinity and Mending cannot go together on a bow: the player picks.
				out.add(List.of(enchant(level, new ItemStack(Items.BOW), Enchantments.POWER, 5, Enchantments.PUNCH, 2, Enchantments.FLAME, 1,
					Enchantments.UNBREAKING, 3, Enchantments.INFINITY, 1)));
				out.add(List.of(gear(level, Items.BOW, Enchantments.POWER, 5, Enchantments.PUNCH, 2, Enchantments.FLAME, 1)));
				for (var shot : List.of(new Object[]{Enchantments.MULTISHOT, 1}, new Object[]{Enchantments.PIERCING, 4})) {
					out.add(List.of(gear(level, Items.CROSSBOW, Enchantments.QUICK_CHARGE, 3, shot[0], shot[1])));
				}
			}
			default -> out.add(List.of(new ItemStack(Items.EMERALD, 16)));
		}
		return out;
	}

	/** Gear of the top rewards: the given enchantments plus Unbreaking III and Mending. */
	private static ItemStack gear(ServerLevel level, Item item, Object... pairs) {
		ItemStack stack = enchant(level, new ItemStack(item), pairs);
		return enchant(level, stack, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
	}

	/** A book with {@code main} and two more enchantments of the common and rare groups that go with it, all at max. */
	private static ItemStack book(ServerLevel level, ResourceKey<Enchantment> main, RandomSource r) {
		List<Holder<Enchantment>> chosen = new ArrayList<>();
		chosen.add(holder(level, main));
		List<ResourceKey<Enchantment>> extras = new ArrayList<>();
		for (var e : BOOKS.entrySet()) if (e.getValue() > 39 && !e.getKey().equals(main)) extras.add(e.getKey());
		while (chosen.size() < 3 && !extras.isEmpty()) {
			Holder<Enchantment> h = holder(level, extras.remove(r.nextInt(extras.size())));
			if (chosen.stream().allMatch(c -> Enchantment.areCompatible(c, h))) chosen.add(h);
		}
		ItemStack stack = new ItemStack(Items.ENCHANTED_BOOK);
		EnchantmentHelper.updateEnchantments(stack, m -> chosen.forEach(h -> m.set(h, h.value().getMaxLevel())));
		return stack;
	}

	/** The name of a treasure map kind ("mansion", "monument"...), translatable with an English fallback. */
	public static Component mapName(String kind) {
		return Component.translatableWithFallback("item.villageerrands.map." + kind, MAPS.getOrDefault(kind, "Treasure Map"));
	}

	private static ResourceKey<Enchantment> weighted(Map<ResourceKey<Enchantment>, Integer> table, RandomSource r) {
		int roll = r.nextInt(table.values().stream().mapToInt(Integer::intValue).sum());
		for (var e : table.entrySet()) {
			roll -= e.getValue();
			if (roll < 0) return e.getKey();
		}
		return table.keySet().iterator().next();
	}

	/**
	 * A map to that kind of structure near the player, made by vanilla's exploration map loot function. A preview
	 * is a named map; a spyglass when no such structure can be found.
	 */
	private static ItemStack treasureMap(ServerLevel level, String kind, String english, long seed, Vec3 origin) {
		Component name = Component.translatableWithFallback("item.villageerrands.map." + kind, english);
		if (origin == null) {
			ItemStack preview = new ItemStack(Items.FILLED_MAP);
			preview.set(DataComponents.ITEM_NAME, name);
			return preview;
		}
		ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, VillageErrands.id("top/map_" + kind));
		LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
		LootParams params = new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, origin).create(LootContextParamSets.CHEST);
		List<ItemStack> items = new ArrayList<>();
		table.getRandomItems(params, seed).forEach(items::add);
		items.removeIf(ItemStack::isEmpty);
		if (items.isEmpty()) return new ItemStack(Items.SPYGLASS);
		ItemStack map = items.getFirst();
		map.set(DataComponents.ITEM_NAME, name);
		return map;
	}

	private static Item item(String path) {
		return BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
	}

	static Holder<Enchantment> holder(ServerLevel level, ResourceKey<Enchantment> key) {
		return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
	}

	/** Pairs of (enchantment, level) set on the stack, on top of what it has. */
	static ItemStack enchant(ServerLevel level, ItemStack stack, Object... pairs) {
		EnchantmentHelper.updateEnchantments(stack, m -> {
			for (int i = 0; i + 1 < pairs.length; i += 2) {
				@SuppressWarnings("unchecked")
				ResourceKey<Enchantment> key = (ResourceKey<Enchantment>) pairs[i];
				m.set(holder(level, key), (Integer) pairs[i + 1]);
			}
		});
		return stack;
	}

	private static ItemStack named(ItemStack stack, String key, String english) {
		stack.set(DataComponents.ITEM_NAME, Component.translatableWithFallback("item.villageerrands." + key, english));
		return stack;
	}

	private TopRewards() {
	}
}
