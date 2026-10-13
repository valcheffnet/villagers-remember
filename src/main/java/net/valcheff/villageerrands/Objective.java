package net.valcheff.villageerrands;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * One part of an errand: an action to do a number of times ({@code CATCH} 8 cod) or items to bring ({@code DELIVER}
 * 8 cod). {@code target} is a registry id, or a tag id when {@code tag} is set; what it names depends on the type.
 * {@code minLevel} is only used by {@code ENCHANT} (minimum enchanting levels).
 */
public record Objective(Type type, String target, boolean tag, int count, int minLevel) {
	/** What the target of a type refers to. */
	public enum Target { ITEM, ENTITY, BLOCK, POTION, STRUCTURE, BIOME, NONE }

	public enum Type {
		DELIVER(Target.ITEM),
		KILL(Target.ENTITY),
		CATCH(Target.ITEM),
		CRAFT(Target.ITEM),
		/** Taken out of a furnace, smoker or blast furnace. Campfires are not counted (no player is known). */
		SMELT(Target.ITEM),
		/** Taken out of a stonecutter, smithing table or cartography table. */
		STATION(Target.ITEM),
		/** Breaking a fully grown crop. */
		HARVEST(Target.BLOCK),
		/** Breaking a block without Silk Touch, so a placed block cannot be broken again and again. */
		MINE(Target.BLOCK),
		PLANT(Target.ITEM),
		BONEMEAL(Target.NONE),
		BREED(Target.ENTITY),
		TAME(Target.ENTITY),
		SHEAR(Target.ENTITY),
		MILK(Target.ENTITY),
		FILL_BUCKET(Target.ITEM),
		TRADE(Target.ITEM),
		ENCHANT(Target.NONE),
		BREW(Target.POTION),
		CURE(Target.NONE),
		SUMMON(Target.ENTITY),
		VISIT_STRUCTURE(Target.STRUCTURE),
		VISIT_BIOME(Target.BIOME);

		public final Target target;

		Type(Target target) {
			this.target = target;
		}

		public String key() {
			return name().toLowerCase(Locale.ROOT);
		}

		public static Type parse(String name) {
			return valueOf(name.toUpperCase(Locale.ROOT));
		}
	}

	public boolean isDelivery() {
		return type == Type.DELIVER;
	}

	/** Visits count once: being there is enough. */
	public int needed() {
		return type == Type.VISIT_STRUCTURE || type == Type.VISIT_BIOME ? 1 : count;
	}

	private Identifier id() {
		return Identifier.parse(target);
	}

	public boolean matchesItem(ItemStack stack) {
		return !stack.isEmpty() && matchesItem(stack.getItem());
	}

	public boolean matchesItem(Item item) {
		if (target.isEmpty()) return true;
		Holder<Item> holder = BuiltInRegistries.ITEM.wrapAsHolder(item);
		return tag ? holder.is(TagKey.create(Registries.ITEM, id())) : holder.is(id());
	}

	public boolean matchesEntity(EntityType<?> type) {
		if (target.isEmpty()) return true;
		Holder<EntityType<?>> holder = BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type);
		return tag ? holder.is(TagKey.create(Registries.ENTITY_TYPE, id())) : holder.is(id());
	}

	public boolean matchesBlock(BlockState state) {
		if (target.isEmpty()) return true;
		Holder<Block> holder = state.getBlock().builtInRegistryHolder();
		return tag ? holder.is(TagKey.create(Registries.BLOCK, id())) : holder.is(id());
	}

	public boolean matchesPotion(Holder<Potion> potion) {
		if (target.isEmpty()) return true;
		return tag ? potion.is(TagKey.create(Registries.POTION, id())) : potion.is(id());
	}

	public CompoundTag save() {
		CompoundTag t = new CompoundTag();
		t.putString("type", type.key());
		t.putString("target", target);
		t.putBoolean("tag", tag);
		t.putInt("count", count);
		if (minLevel > 0) t.putInt("min_level", minLevel);
		return t;
	}

	public static Objective load(CompoundTag t) {
		return new Objective(Type.parse(t.getStringOr("type", "deliver")), t.getStringOr("target", ""),
			t.getBooleanOr("tag", false), t.getIntOr("count", 1), t.getIntOr("min_level", 0));
	}
}
