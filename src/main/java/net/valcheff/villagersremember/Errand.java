package net.valcheff.villagersremember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One errand offered by a villager on one day. Immutable; the same errand is offered to every player at the same
 * tier with that villager, and each player can complete it once. Accepted errands are stored as a copy, so later pool changes do not affect them.
 */
public record Errand(
	String id,
	UUID villager,
	String profession,
	String villagerType,
	int villagerLevel,
	/** The pack it came from, 1..5: the player's tier with the villager + 1 when it was offered. */
	int tier,
	Optional<GlobalPos> village,
	/** Lang key of a name for the whole errand (orders), or empty to build the name from the objectives. */
	Optional<String> title,
	List<Objective> objectives,
	double value,
	int emeralds,
	int xp,
	Optional<ItemReward> item,
	int reputation
) {
	/** The same errand under another title (or none). */
	/** Emeralds handed over: none when the server pays rewards without emeralds. */
	public int paidEmeralds() {
		return ErrandsConfig.INSTANCE.emeraldRewards ? emeralds : 0;
	}

	/** Experience handed over, times the server's experience multiplier. */
	public int paidXp() {
		return (int) Math.round(xp * ErrandsConfig.INSTANCE.xpMultiplier);
	}

	public Errand withTitle(Optional<String> newTitle) {
		return new Errand(id, villager, profession, villagerType, villagerLevel, tier, village, newTitle, objectives, value,
			emeralds, xp, item, reputation);
	}

	/** Chance that an enchanted reward draws from the treasure enchantments instead of the enchanting table's. */
	public static final float RARE_POOL_CHANCE = 0.15f;

	/** A reward item, rebuilt from its seed whenever it is shown or given, so it never needs item serialization. */
	public record ItemReward(ErrandPools.RewardKind kind, String id, int amount, long seed) {
		public List<ItemStack> create(ServerLevel level) {
			return create(level, 0);
		}

		/** {@code extraLevels} raise an enchanted reward: the village standing makes rewards better. */
		public List<ItemStack> create(ServerLevel level, int extraLevels) {
			return create(level, extraLevels, null);
		}

		/** {@code origin}: where the player stands when it is given (a treasure map points near it); null for a preview. */
		public List<ItemStack> create(ServerLevel level, int extraLevels, Vec3 origin) {
			return create(level, extraLevels, origin, 0);
		}

		/** {@code choice}: the option of a top reward the player picked. */
		public List<ItemStack> create(ServerLevel level, int extraLevels, Vec3 origin, int choice) {
			List<ItemStack> out = new ArrayList<>();
			switch (kind) {
				case TOP -> out.addAll(TopRewards.create(id, level, seed, origin, choice));
				case ITEM -> BuiltInRegistries.ITEM.getOptional(Identifier.parse(id))
					.ifPresent(item -> out.add(new ItemStack(item, amount)));
				case ENCHANT, ENCHANT_MAX -> BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).ifPresent(item -> {
					// Mostly the enchanting table's own list (#minecraft:in_enchanting_table). Now and then
					// (RARE_POOL_CHANCE) a treasure one (#minecraft:treasure: Mending, Frost Walker... and whatever other
					// mods tag as treasure), never curses. Only tags are used, so any set of mods works; without a treasure
					// enchantment for this item the table's list is used.
					var registry = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
					RandomSource random = RandomSource.create(seed);
					var table = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE)
						.map(set -> (HolderSet<Enchantment>) set);
					ItemStack stack = ItemStack.EMPTY;
					if (random.nextFloat() < RARE_POOL_CHANCE) {
						var rare = HolderSet.direct(registry.listElements()
							.filter(h -> h.is(EnchantmentTags.TREASURE) && !h.is(EnchantmentTags.CURSE))
							.map(h -> (Holder<Enchantment>) h).toList());
						stack = EnchantmentHelper.enchantItem(random, new ItemStack(item), amount + extraLevels, level.registryAccess(), Optional.of(rare));
						if (!stack.isEnchanted()) stack = ItemStack.EMPTY;
					}
					if (stack.isEmpty()) {
						stack = EnchantmentHelper.enchantItem(random, new ItemStack(item), amount + extraLevels, level.registryAccess(), table);
					}
					if (kind == ErrandPools.RewardKind.ENCHANT_MAX) {
						EnchantmentHelper.updateEnchantments(stack, m -> {
							for (var holder : List.copyOf(m.keySet())) m.set(holder, holder.value().getMaxLevel());
						});
					}
					out.add(stack);
				});
				case LOOT -> {
					ResourceKey<LootTable> key = ResourceKey.create(Registries.LOOT_TABLE, Identifier.parse(id));
					LootTable table = level.getServer().reloadableRegistries().getLootTable(key);
					LootParams params = new LootParams.Builder(level)
						.withParameter(LootContextParams.ORIGIN, origin != null ? origin : Vec3.ZERO)
						.create(LootContextParamSets.CHEST);
					// A treasure map is shown by name until it is given, so its structure is searched only then.
					if (origin == null && id.contains(":top/map_")) {
						ItemStack map = new ItemStack(Items.FILLED_MAP);
						map.set(DataComponents.ITEM_NAME, TopRewards.mapName(id.substring(id.indexOf("map_") + 4)));
						out.add(map);
						break;
					}
					table.getRandomItems(params, seed).forEach(out::add);
					out.removeIf(ItemStack::isEmpty);
					if (out.isEmpty()) out.add(new ItemStack(id.contains(":top/map_") ? Items.SPYGLASS : Items.EMERALD));
				}
			}
			return out;
		}
	}

	/** The objective shown first (the main action, or the first item to bring). */
	public Objective main() {
		for (Objective o : objectives) if (!o.isCarried()) return o;
		return objectives.getFirst();
	}

	public boolean hasActions() {
		return objectives.stream().anyMatch(o -> !o.isCarried());
	}

	public long day() {
		String[] parts = id.split("/");
		return parts.length >= 2 ? Long.parseLong(parts[1]) : 0;
	}

	public CompoundTag save() {
		CompoundTag tag = new CompoundTag();
		tag.putString("id", id);
		tag.putString("villager", villager.toString());
		tag.putString("profession", profession);
		tag.putString("type", villagerType);
		tag.putInt("level", villagerLevel);
		tag.putInt("tier", tier);
		village.ifPresent(v -> {
			tag.putString("village_dim", v.dimension().identifier().toString());
			tag.putLong("village_pos", v.pos().asLong());
		});
		title.ifPresent(t -> tag.putString("title", t));
		ListTag list = new ListTag();
		for (Objective o : objectives) list.add(o.save());
		tag.put("objectives", list);
		tag.putDouble("value", value);
		tag.putInt("emeralds", emeralds);
		tag.putInt("xp", xp);
		item.ifPresent(i -> {
			CompoundTag it = new CompoundTag();
			it.putString("kind", i.kind().name());
			it.putString("id", i.id());
			it.putInt("amount", i.amount());
			it.putLong("seed", i.seed());
			tag.put("item", it);
		});
		tag.putInt("reputation", reputation);
		return tag;
	}

	public static Errand load(CompoundTag tag) {
		Optional<GlobalPos> village = Optional.empty();
		String dim = tag.getStringOr("village_dim", "");
		if (!dim.isEmpty()) {
			village = Optional.of(GlobalPos.of(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)),
				BlockPos.of(tag.getLongOr("village_pos", 0L))));
		}
		Optional<ItemReward> item = Optional.empty();
		if (tag.contains("item")) {
			CompoundTag it = tag.getCompoundOrEmpty("item");
			item = Optional.of(new ItemReward(ErrandPools.RewardKind.valueOf(it.getStringOr("kind", "ITEM")),
				it.getStringOr("id", "minecraft:emerald"), it.getIntOr("amount", 1), it.getLongOr("seed", 0L)));
		}
		List<Objective> objectives = new ArrayList<>();
		tag.getListOrEmpty("objectives").compoundStream().forEach(o -> objectives.add(Objective.load(o)));
		if (objectives.isEmpty()) {
			// Saved before errands had objectives: one deliver or kill objective.
			boolean kill = tag.getStringOr("kind", "DELIVER").equals("KILL");
			objectives.add(new Objective(kill ? Objective.Type.KILL : Objective.Type.DELIVER, tag.getStringOr("target", "minecraft:stone"),
				tag.getBooleanOr("tag", false), tag.getIntOr("count", 1), 0));
		}
		String title = tag.getStringOr("title", "");
		return new Errand(
			tag.getStringOr("id", "?"),
			UUID.fromString(tag.getStringOr("villager", new UUID(0, 0).toString())),
			tag.getStringOr("profession", "minecraft:none"),
			tag.getStringOr("type", "minecraft:plains"),
			tag.getIntOr("level", 1),
			tag.getIntOr("tier", 1),
			village,
			title.isEmpty() ? Optional.empty() : Optional.of(title),
			List.copyOf(objectives),
			tag.getDoubleOr("value", 0),
			tag.getIntOr("emeralds", 0),
			tag.getIntOr("xp", 0),
			item,
			tag.getIntOr("reputation", 1));
	}
}
