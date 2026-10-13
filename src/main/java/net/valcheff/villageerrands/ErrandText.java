package net.valcheff.villageerrands;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Builds names, icons and description lines for errands, in the player's language. */
public final class ErrandText {
	/** "■■■■□□□ 4/7": filled squares in the progress colour, empty ones dark grey. */
	public static MutableComponent bar(int done, int total, int segments) {
		done = Math.clamp(done, 0, Math.max(total, 1));
		boolean full = done >= total;
		int filled = total <= 0 ? segments : (int) Math.floor((double) done * segments / total);
		if (done > 0 && filled == 0) filled = 1;
		ChatFormatting color = full ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
		return Component.empty()
			.append(Component.literal("■".repeat(filled)).withStyle(color))
			.append(Component.literal("□".repeat(segments - filled)).withStyle(ChatFormatting.GRAY))
			.append(Component.literal(" " + done + "/" + total).withStyle(color));
	}

	public static String path(String id) {
		int colon = id.indexOf(':');
		return colon >= 0 ? id.substring(colon + 1) : id;
	}

	/** Actions that make an item; with a delivery of the same item and count the errand reads "Catch and bring". */
	private static final java.util.Set<Objective.Type> MAKES_ITEM = java.util.EnumSet.of(Objective.Type.CATCH,
		Objective.Type.CRAFT, Objective.Type.SMELT, Objective.Type.STATION, Objective.Type.FILL_BUCKET, Objective.Type.TRADE);

	public static Component targetName(ServerPlayer player, Objective o) {
		if (o.target().isEmpty()) return Component.empty();
		Identifier id = Identifier.parse(o.target());
		if (o.tag()) {
			String key = "tag." + id.getNamespace() + "." + id.getPath().replace('/', '.');
			String translated = Lang.raw(Lang.language(player), key);
			if (!translated.equals(key)) return Component.literal(translated);
			if (o.type().target == Objective.Target.ITEM) return Lang.tr(player, "tag.any", firstItem(o).getHoverName());
			return Component.literal(id.getPath().replace('_', ' '));
		}
		return switch (o.type().target) {
			case ITEM -> BuiltInRegistries.ITEM.getOptional(id).map(i -> (Component) Component.translatable(i.getDescriptionId()))
				.orElse(Component.literal(o.target()));
			case ENTITY -> BuiltInRegistries.ENTITY_TYPE.getOptional(id).map(EntityType::getDescription)
				.orElse(Component.literal(o.target()));
			case BLOCK -> BuiltInRegistries.BLOCK.getOptional(id).map(b -> (Component) Component.translatable(b.getDescriptionId()))
				.orElse(Component.literal(o.target()));
			case POTION -> Component.translatable("item.minecraft.potion.effect." + id.getPath());
			case BIOME -> Component.translatable("biome." + id.getNamespace() + "." + id.getPath());
			case STRUCTURE -> {
				String key = "structure." + id.getNamespace() + "." + id.getPath();
				String translated = Lang.raw(Lang.language(player), key);
				yield Component.literal(translated.equals(key) ? id.getPath().replace('_', ' ') : translated);
			}
			case NONE -> Component.empty();
		};
	}

	/** The sidebar line for an objective, without the count: "Defeat Squid", "Cook Steak", "Visit Igloo". */
	public static MutableComponent shortName(ServerPlayer player, Objective o) {
		return switch (o.type()) {
			case SMELT -> Lang.tr(player, "side.smelt" + (smeltVariant(o).equals(".food") ? ".food" : ""), targetName(player, o));
			case ENCHANT -> Lang.tr(player, "side.enchant", o.minLevel());
			case CURE, BONEMEAL -> Lang.tr(player, "side." + o.type().key());
			default -> Lang.tr(player, "side." + o.type().key(), targetName(player, o));
		};
	}

	/** "Catch 8× Cod", "Cook 16× Steak in a furnace or smoker", "Visit a Pillager Outpost"... */
	public static MutableComponent objective(ServerPlayer player, Objective o) {
		return switch (o.type()) {
			case SMELT -> Lang.tr(player, "obj.smelt" + smeltVariant(o), o.count(), targetName(player, o));
			case ENCHANT -> Lang.tr(player, "obj.enchant", o.count(), o.minLevel());
			case CURE, BONEMEAL -> Lang.tr(player, "obj." + o.type().key(), o.count());
			case VISIT_STRUCTURE, VISIT_BIOME -> Lang.tr(player, "obj." + o.type().key(), targetName(player, o));
			default -> Lang.tr(player, "obj." + o.type().key(), o.count(), targetName(player, o));
		};
	}

	/** Like {@link #objective} but without the station for smelting, for lists that name the station once. */
	public static MutableComponent objectiveShort(ServerPlayer player, Objective o) {
		if (o.type() == Objective.Type.SMELT) {
			return Lang.tr(player, "objs.smelt" + (smeltVariant(o).equals(".food") ? ".food" : ""), o.count(), targetName(player, o));
		}
		return objective(player, o);
	}

	/** Furnace or smoker for food, furnace or blast furnace for ores, otherwise just furnace. Campfires never count. */
	public static String smeltVariant(Objective o) {
		if (o.tag() || o.target().isEmpty()) return "";
		Optional<Item> item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(o.target()));
		if (item.isEmpty()) return "";
		ItemStack stack = new ItemStack(item.get());
		if (stack.has(net.minecraft.core.component.DataComponents.FOOD)) return ".food";
		String path = path(o.target());
		if (path.endsWith("_ingot") || path.equals("netherite_scrap")) return ".ore";
		return "";
	}

	/**
	 * A short first-person line from the villager about why they need it: the line for this exact target if there
	 * is one, otherwise one of the variants for this profession and action, picked from the errand id.
	 */
	public static Optional<Component> lore(ServerPlayer player, Errand errand) {
		String lang = Lang.language(player);
		if (errand.title().isPresent()) {
			String key = "lore." + errand.title().get();
			String text = Lang.raw(lang, key);
			if (!text.equals(key)) return Optional.of(quoted(text));
		}
		Objective main = errand.main();
		// Reforge: the reward is the same thing that is made.
		if (errand.item().map(i -> i.kind() != ErrandPools.RewardKind.ITEM && i.kind() != ErrandPools.RewardKind.LOOT
			&& i.id().equals(main.target())).orElse(false)) {
			String key = "lore." + path(errand.profession()) + ".reforge";
			String text = Lang.raw(lang, key);
			if (!text.equals(key)) return Optional.of(quoted(text));
		}
		String base = "lore." + path(errand.profession()) + "." + main.type().key();
		if (!main.target().isEmpty()) {
			String specific = base + "." + path(main.target());
			String text = Lang.raw(lang, specific);
			if (!text.equals(specific)) return Optional.of(quoted(text));
		}
		int variants = 0;
		while (!Lang.raw(lang, base + "." + (variants + 1)).equals(base + "." + (variants + 1))) variants++;
		if (variants == 0) return Optional.empty();
		int pick = Math.floorMod(errand.id().hashCode(), variants) + 1;
		return Optional.of(quoted(Lang.raw(lang, base + "." + pick)));
	}

	/** A line the villager says: white italic, in quotes. */
	public static Component quotedLine(String text) {
		return quoted(text);
	}

	private static Component quoted(String text) {
		return Component.literal("\"" + text + "\"").withStyle(ChatFormatting.WHITE, ChatFormatting.ITALIC);
	}

	/** The errand's name: an order name, "Catch and bring 8× Cod", or the main objective (+ what to bring). */
	public static MutableComponent task(ServerPlayer player, Errand errand) {
		if (ErrandGenerator.isTrial(errand)) {
			int tier = errand.tier() - 1;
			return Lang.tr(player, "trial.name", Lang.tr(player, "rep.villager." + tier));
		}
		if (ErrandGenerator.isContract(errand)) return Lang.tr(player, "contract.name", villagerName(player, errand.villager(), null));
		if (errand.title().isPresent()) return Lang.tr(player, errand.title().get());
		List<Objective> objectives = errand.objectives();
		if (objectives.size() == 1) return objective(player, objectives.getFirst());
		if (objectives.size() == 2) {
			Objective action = errand.main();
			Objective delivery = objectives.get(0) == action ? objectives.get(1) : objectives.get(0);
			if (delivery.isDelivery() && MAKES_ITEM.contains(action.type()) && action.count() == delivery.count()
				&& action.target().equals(delivery.target()) && action.tag() == delivery.tag()) {
				String key = "pair." + action.type().key() + (action.type() == Objective.Type.SMELT ? smeltVariant(action) : "");
				return Lang.tr(player, key, action.count(), targetName(player, delivery));
			}
		}
		MutableComponent out = objective(player, errand.main());
		for (Objective o : objectives) {
			if (o == errand.main()) continue;
			out.append(Component.literal(" + ")).append(objective(player, o));
		}
		return out;
	}

	/** The item shown for an errand: what to bring, or what the main action is about. */
	public static ItemStack icon(Errand errand) {
		for (Objective o : errand.objectives()) if (o.isDelivery()) return icon(o);
		return icon(errand.main());
	}

	public static ItemStack icon(Objective o) {
		ItemStack stack = switch (o.type().target) {
			case ITEM -> firstItem(o).copy();
			case ENTITY -> {
				Optional<EntityType<?>> type = o.tag() ? Optional.empty() : BuiltInRegistries.ENTITY_TYPE.getOptional(Identifier.parse(o.target()));
				Optional<Holder<Item>> egg = type.flatMap(SpawnEggItem::byId);
				yield egg.map(ItemStack::new).orElse(new ItemStack(o.type() == Objective.Type.KILL ? Items.IRON_SWORD : Items.LEAD));
			}
			case BLOCK -> o.tag() ? new ItemStack(Items.IRON_PICKAXE)
				: BuiltInRegistries.BLOCK.getOptional(Identifier.parse(o.target())).map(b -> new ItemStack(b.asItem()))
					.filter(st -> !st.isEmpty()).orElse(new ItemStack(Items.IRON_HOE));
			case POTION -> new ItemStack(Items.POTION);
			case STRUCTURE -> new ItemStack(Items.FILLED_MAP);
			case BIOME -> new ItemStack(Items.COMPASS);
			case NONE -> new ItemStack(switch (o.type()) {
				case ENCHANT -> Items.ENCHANTING_TABLE;
				case CURE -> Items.GOLDEN_APPLE;
				case BONEMEAL -> Items.BONE_MEAL;
				default -> Items.PAPER;
			});
		};
		if (stack.isEmpty()) stack = new ItemStack(Items.PAPER);
		stack.setCount(Math.clamp(o.count(), 1, stack.getMaxStackSize()));
		return stack;
	}

	private static ItemStack firstItem(Objective o) {
		if (!o.tag()) {
			return BuiltInRegistries.ITEM.getOptional(Identifier.parse(o.target())).map(ItemStack::new)
				.orElse(new ItemStack(Items.BARRIER));
		}
		TagKey<Item> tag = TagKey.create(Registries.ITEM, Identifier.parse(o.target()));
		for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(tag)) return new ItemStack(holder);
		return new ItemStack(Items.BARRIER);
	}

	public static String villagerName(ServerPlayer player, UUID villager, Villager entity) {
		if (entity != null && entity.hasCustomName()) return entity.getCustomName().getString();
		MinecraftServer server = player.level().getServer();
		UUID root = server != null ? VillageRegistry.get(server).nameRoot(villager) : villager;
		return Lang.villagerName(player, root.getMostSignificantBits() ^ root.getLeastSignificantBits());
	}

	public static String villageName(ServerPlayer player, GlobalPos village) {
		MinecraftServer server = player.level().getServer();
		if (server != null) {
			VillageRegistry registry = VillageRegistry.get(server);
			var v = registry.byCenter(village);
			if (v.isPresent()) {
				if (v.get().name != null) return v.get().name;
				return Lang.villageNameAt(player, registry.nameIndex(v.get(), Lang.villageNameCount()));
			}
		}
		return Lang.villageName(player, village.pos().asLong() * 31 + village.dimension().identifier().hashCode());
	}

	public static Component profession(String professionId) {
		Identifier id = Identifier.parse(professionId);
		String key = "entity." + (id.getNamespace().equals("minecraft") ? "minecraft" : id.getNamespace()) + ".villager." + id.getPath();
		return Component.translatable(key);
	}

	/** "Name, Farmer (Apprentice)" */
	public static MutableComponent villagerLine(ServerPlayer player, Errand errand, Villager entity) {
		return Lang.tr(player, "errand.villager", villagerName(player, errand.villager(), entity),
			profession(errand.profession()), Component.translatable("merchant.level." + Math.clamp(errand.villagerLevel(), 1, 5)));
	}

	/** "Sharpness II, Unbreaking I" for an enchanted item or book, in light purple; empty when none. */
	public static Component enchantments(ItemStack stack) {
		net.minecraft.world.item.enchantment.ItemEnchantments list = stack.getOrDefault(
			net.minecraft.core.component.DataComponents.ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
		if (list.isEmpty()) list = stack.getOrDefault(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS,
			net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);
		MutableComponent out = Component.empty();
		boolean first = true;
		for (var entry : list.entrySet()) {
			if (!first) out.append(", ");
			first = false;
			out.append(net.minecraft.world.item.enchantment.Enchantment.getFullname(entry.getKey(), entry.getIntValue()).copy()
				.withStyle(ChatFormatting.LIGHT_PURPLE));
		}
		return out;
	}

	/**
	 * Colour of a reputation tier, from grey to purple like item rarity in WoW: poor, common, uncommon, rare, epic.
	 * Blue and purple are lighter than WoW's, to read on a grey button.
	 */
	public static int tierColor(int tier) {
		return switch (Math.clamp(tier, 0, 4)) {
			case 0 -> 0x9D9D9D;
			case 1 -> 0xFFFFFF;
			case 2 -> 0x1EFF00;
			case 3 -> 0x4DA6FF;
			default -> 0xC060FF;
		};
	}

	/** "★★☆☆": one filled star per villager tier, in that tier's colour. */
	public static Component stars(int tier) {
		return Component.empty()
			.append(Component.literal("★".repeat(tier)).withColor(tierColor(tier)))
			.append(Component.literal("☆".repeat(Reputation.MAX_TIER - tier)).withStyle(ChatFormatting.DARK_GRAY));
	}

	public static int withBonus(int amount, double bonus) {
		return amount <= 0 ? 0 : (int) Math.round(amount * (1 + bonus));
	}

	public static Component villageRepLine(ServerPlayer player, int points) {
		int tier = Reputation.tier(points, Reputation.VILLAGE_TIERS);
		Component name = Lang.tr(player, "rep.village." + tier);
		if (tier + 1 < Reputation.VILLAGE_TIERS.length) {
			return Lang.tr(player, "rep.village.line", name, points, Reputation.VILLAGE_TIERS[tier + 1]);
		}
		return Lang.tr(player, "rep.village.line.max", name, points);
	}

	public static Component villagerRepLine(ServerPlayer player, int points) {
		int tier = Reputation.tier(points, Reputation.VILLAGER_TIERS);
		Component name = Lang.tr(player, "rep.villager." + tier);
		if (tier + 1 < Reputation.VILLAGER_TIERS.length) {
			return Lang.tr(player, "rep.villager.line", name, points, Reputation.VILLAGER_TIERS[tier + 1]);
		}
		return Lang.tr(player, "rep.villager.line.max", name, points);
	}
}
