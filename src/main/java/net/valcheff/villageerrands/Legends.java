package net.valcheff.villageerrands;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Legendary items (TIERS.md section 7): one per profession and player, with a name, a story and every fitting
 * enchantment at its best. Earned with contracts: the third contract a player completes for a profession brings
 * its legendary item, named after the villager who gave it ("Lena's Hoe").
 */
public final class Legends {
	private record Legend(Item item, Object[] enchantments) {
	}

	private static final Map<String, Legend> LEGENDS = new LinkedHashMap<>();

	static {
		LEGENDS.put("armorer", new Legend(Items.NETHERITE_CHESTPLATE, new Object[]{Enchantments.PROTECTION, 4, Enchantments.THORNS, 3}));
		LEGENDS.put("weaponsmith", new Legend(Items.NETHERITE_SWORD, new Object[]{Enchantments.SHARPNESS, 5, Enchantments.LOOTING, 3,
			Enchantments.FIRE_ASPECT, 2, Enchantments.SWEEPING_EDGE, 3}));
		LEGENDS.put("toolsmith", new Legend(Items.NETHERITE_PICKAXE, new Object[]{Enchantments.EFFICIENCY, 5, Enchantments.FORTUNE, 3}));
		LEGENDS.put("farmer", new Legend(Items.NETHERITE_HOE, new Object[]{Enchantments.EFFICIENCY, 5, Enchantments.FORTUNE, 3}));
		LEGENDS.put("fisherman", new Legend(Items.FISHING_ROD, new Object[]{Enchantments.LURE, 3, Enchantments.LUCK_OF_THE_SEA, 3}));
		LEGENDS.put("fletcher", new Legend(Items.BOW, new Object[]{Enchantments.POWER, 5, Enchantments.PUNCH, 2, Enchantments.FLAME, 1}));
		LEGENDS.put("librarian", new Legend(Items.NETHERITE_HELMET, new Object[]{Enchantments.PROTECTION, 4, Enchantments.RESPIRATION, 3,
			Enchantments.AQUA_AFFINITY, 1}));
		LEGENDS.put("cartographer", new Legend(Items.NETHERITE_BOOTS, new Object[]{Enchantments.PROTECTION, 4, Enchantments.FEATHER_FALLING, 4,
			Enchantments.DEPTH_STRIDER, 3, Enchantments.SOUL_SPEED, 3}));
		LEGENDS.put("cleric", new Legend(Items.NETHERITE_LEGGINGS, new Object[]{Enchantments.PROTECTION, 4, Enchantments.SWIFT_SNEAK, 3}));
		LEGENDS.put("butcher", new Legend(Items.NETHERITE_AXE, new Object[]{Enchantments.SHARPNESS, 5, Enchantments.EFFICIENCY, 5}));
		LEGENDS.put("leatherworker", new Legend(Items.ELYTRA, new Object[]{}));
		LEGENDS.put("mason", new Legend(Items.NETHERITE_SHOVEL, new Object[]{Enchantments.EFFICIENCY, 5, Enchantments.SILK_TOUCH, 1}));
		LEGENDS.put("shepherd", new Legend(Items.SHIELD, new Object[]{}));
	}

	/** Profession paths that have a legendary item (every profession with contracts). */
	public static List<String> professions() {
		return List.copyOf(LEGENDS.keySet());
	}

	public static Item icon(String profession) {
		Legend l = LEGENDS.get(profession);
		return l != null ? l.item() : Items.NETHER_STAR;
	}

	/** The profession whose legendary item this hand-in brings, if it brings one. */
	public static Optional<String> due(ErrandsData.PlayerState state, Errand errand) {
		if (!ErrandGenerator.isContract(errand)) return Optional.empty();
		String prof = ErrandText.path(errand.profession());
		if (!LEGENDS.containsKey(prof) || state.legends.contains(prof)) return Optional.empty();
		int done = state.contracts.getOrDefault(prof, 0) + 1;
		return done >= ErrandsConfig.INSTANCE.contractsForLegend ? Optional.of(prof) : Optional.empty();
	}

	/** The legendary item of a profession, named after the villager and telling where it came from. */
	public static ItemStack create(ServerLevel level, ServerPlayer player, String profession, String villagerName, String villageName) {
		Legend legend = LEGENDS.get(profession);
		ItemStack stack = new ItemStack(legend.item());
		Object[] pairs = legend.enchantments();
		if (pairs.length > 0) TopRewards.enchant(level, stack, pairs);
		if (stack.isDamageableItem()) {
			// Bows keep Infinity instead of Mending, the rest mend.
			if (stack.is(Items.BOW)) TopRewards.enchant(level, stack, Enchantments.UNBREAKING, 3, Enchantments.INFINITY, 1);
			else TopRewards.enchant(level, stack, Enchantments.UNBREAKING, 3, Enchantments.MENDING, 1);
		}
		stack.set(DataComponents.CUSTOM_NAME, Lang.tr(player, "legend." + profession + ".name", villagerName)
			.withStyle(s -> s.withItalic(false).withColor(0xFF8000)));
		stack.set(DataComponents.RARITY, Rarity.EPIC);
		List<Component> lore = new ArrayList<>();
		for (String line : Lang.raw(Lang.language(player), "legend." + profession + ".lore").replace("%s", villageName).split("\n")) {
			lore.add(Component.literal(line).withStyle(s -> s.withItalic(true).withColor(ChatFormatting.GRAY)));
		}
		lore.add(Lang.tr(player, "legend.tag").withStyle(s -> s.withItalic(false).withColor(0xFF8000)));
		stack.set(DataComponents.LORE, new ItemLore(lore));
		return stack;
	}

	/** Gives a legendary item and records it; returns it so the caller can tell the player. */
	public static ItemStack award(ServerPlayer player, ErrandsData.PlayerState state, String profession, UUID villager, String villageName) {
		ServerLevel level = (ServerLevel) player.level();
		String who = ErrandText.villagerName(player, villager, Villages.loaded(level, villager));
		ItemStack stack = create(level, player, profession, who, villageName);
		state.legends.add(profession);
		ErrandsData.get(level.getServer()).setDirty();
		return stack;
	}

	static ResourceKey<Enchantment> key(Object o) {
		@SuppressWarnings("unchecked")
		ResourceKey<Enchantment> k = (ResourceKey<Enchantment>) o;
		return k;
	}

	private Legends() {
	}
}
