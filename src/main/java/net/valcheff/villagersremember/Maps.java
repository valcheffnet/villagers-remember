package net.valcheff.villagersremember;

import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.maps.MapDecorationType;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Charts to the places errands send the player to. Accepting an errand with a visit gives a folded chart (a blank
 * filled map with the target in its custom data, tinted by kind). Using it unfolds it: the nearest structure or
 * biome is searched then, in the dimension the player is in, and the chart becomes a map with the place marked, like
 * an explorer map. A chart for another dimension says where it unfolds. Vanilla clients see an ordinary map item.
 */
public final class Maps {
	private static final String CHART = "villagersremember_chart";
	/** Charts handed out before the mod was renamed (Village Errands). */
	private static final String OLD_CHART = "villageerrands_chart";

	/** A folded chart for a visit objective, drawn by the villager. */
	public static Optional<ItemStack> chart(ServerPlayer player, Objective o, String villagerName) {
		if (o.type() != Objective.Type.VISIT_STRUCTURE && o.type() != Objective.Type.VISIT_BIOME) return Optional.empty();
		ItemStack chart = new ItemStack(Items.FILLED_MAP);
		CompoundTag tag = new CompoundTag();
		tag.putString("type", o.type().key());
		tag.putString("target", o.target());
		tag.putBoolean("tag", o.tag());
		tag.putString("by", villagerName);
		CompoundTag root = new CompoundTag();
		root.put(CHART, tag);
		chart.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
		chart.set(DataComponents.CUSTOM_NAME, Lang.tr(player, "chart.name", ErrandText.targetName(player, o))
			.withStyle(s -> s.withItalic(false).withColor(ChatFormatting.AQUA)));
		List<Component> lore = new ArrayList<>();
		lore.add(Lang.tr(player, "chart.use").withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GRAY)));
		lore.add(Lang.tr(player, "map.from", villagerName).withStyle(s -> s.withItalic(true).withColor(ChatFormatting.DARK_GRAY)));
		chart.set(DataComponents.LORE, new ItemLore(lore));
		if (dntLook()) {
			// Dungeons and Taverns' rolled chart (its resource pack draws "dnt:chart"), ribbon tinted by the place.
			// Its own unfolding only reacts to an item being used over time, which ours never is.
			chart.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(
				List.of(), List.of(), List.of("dnt:chart"), List.of()));
			chart.set(DataComponents.DYED_COLOR, new DyedItemColor(tint(o)));
			chart.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.DYED_COLOR, true));
		} else {
			// Looks like a rolled-up chart (the globe banner pattern) until it is unfolded into the map.
			chart.set(DataComponents.ITEM_MODEL, BuiltInRegistries.ITEM.getKey(Items.GLOBE_BANNER_PATTERN));
		}
		chart.set(DataComponents.MAX_STACK_SIZE, 1);
		return Optional.of(chart);
	}

	/** Dungeons and Taverns' chart look: chosen in the config, or "auto" when that mod is installed. */
	private static boolean dntLook() {
		String look = ErrandsConfig.INSTANCE.chartLook;
		if ("dnt".equalsIgnoreCase(look)) return true;
		if ("globe".equalsIgnoreCase(look)) return false;
		return Platform.get().isModLoaded("mr_dungeons_andtaverns");
	}

	private static CompoundTag chartTag(CustomData data) {
		CompoundTag all = data.copyTag();
		CompoundTag tag = all.getCompoundOrEmpty(CHART);
		return tag.isEmpty() ? all.getCompoundOrEmpty(OLD_CHART) : tag;
	}

	/** The objective a chart leads to, if the stack is one of our charts. */
	public static Optional<Objective> chartTarget(ItemStack stack) {
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null) return Optional.empty();
		CompoundTag tag = chartTag(data);
		if (tag.isEmpty()) return Optional.empty();
		Objective.Type type = tag.getStringOr("type", "").equals(Objective.Type.VISIT_BIOME.key())
			? Objective.Type.VISIT_BIOME : Objective.Type.VISIT_STRUCTURE;
		return Optional.of(new Objective(type, tag.getStringOr("target", ""), tag.getBooleanOr("tag", false), 1, 0));
	}

	/**
	 * Right-click with a chart: searched now, in this dimension. Found: the chart becomes the map. Elsewhere: it says
	 * which dimension it unfolds in. Nowhere near: it stays folded.
	 */
	public static InteractionResult use(ServerPlayer player, InteractionHand hand) {
		ItemStack stack = player.getItemInHand(hand);
		Optional<Objective> target = chartTarget(stack);
		if (target.isEmpty()) return InteractionResult.PASS;
		Objective o = target.get();
		String by = chartTag(stack.get(DataComponents.CUSTOM_DATA)).getStringOr("by", "?");
		ServerLevel here = (ServerLevel) player.level();
		Optional<ItemStack> map = mapIn(player, here, o, by);
		if (map.isPresent()) {
			player.setItemInHand(hand, map.get());
			Hud.title(player, Component.empty(), Lang.tr(player, "chart.unfolding").withStyle(ChatFormatting.GOLD));
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BOOK_PAGE_TURN,
				SoundSource.PLAYERS, 1.0f, 1.0f);
			return InteractionResult.SUCCESS;
		}
		// Not in this dimension: where then?
		for (ServerLevel level : searchOrder(player.level().getServer(), here)) {
			if (level == here) continue;
			if (find(player, level, o) != null) {
				player.sendSystemMessage(Lang.tr(player, "chart.wrong_dimension", Lang.tr(player, "dim." + level.dimension().identifier().getPath()))
					.withStyle(ChatFormatting.YELLOW));
				return InteractionResult.SUCCESS;
			}
		}
		player.sendSystemMessage(Lang.tr(player, "chart.nothing", ErrandText.targetName(player, o)).withStyle(ChatFormatting.GRAY));
		return InteractionResult.SUCCESS;
	}

	/** Tint of a folded chart by what it leads to. */
	private static int tint(Objective o) {
		String id = o.target();
		if (o.type() == Objective.Type.VISIT_BIOME) return 0x3C8C3C;
		if (id.contains("ancient_city")) return 0x0D3B4F;
		if (id.contains("mansion")) return 0x5A3A1E;
		if (id.contains("monument") || id.contains("shipwreck")) return 0x2E6FA8;
		if (id.contains("trial")) return 0xB06A2A;
		if (id.contains("fortress") || id.contains("bastion") || id.contains("ruined_portal")) return 0x8A1E1E;
		if (id.contains("end_city") || id.contains("stronghold")) return 0x6A4A8A;
		return 0x8C7A55;
	}
	/** Chunks searched for a structure, as vanilla explorer maps do. */
	private static final int STRUCTURE_RADIUS = 100;
	private static final int BIOME_RADIUS = 6400;

	/** A finished map to the nearest such place in any dimension (the player's first). */
	public static Optional<ItemStack> forVisit(ServerPlayer player, Objective o, String villagerName) {
		if (o.type() != Objective.Type.VISIT_STRUCTURE && o.type() != Objective.Type.VISIT_BIOME) return Optional.empty();
		MinecraftServer server = player.level().getServer();
		for (ServerLevel level : searchOrder(server, (ServerLevel) player.level())) {
			Optional<ItemStack> map = mapIn(player, level, o, villagerName);
			if (map.isPresent()) return map;
		}
		return Optional.empty();
	}

	private static BlockPos find(ServerPlayer player, ServerLevel level, Objective o) {
		try {
			BlockPos origin = scaled(player, level);
			return o.type() == Objective.Type.VISIT_STRUCTURE ? findStructure(level, o, origin) : findBiome(level, o, origin);
		} catch (RuntimeException e) {
			VillagersRemember.LOG.debug("Map search for {} failed", o.target(), e);
			return null;
		}
	}

	/** A map in this dimension to the nearest such place, or empty when there is none in reach. */
	private static Optional<ItemStack> mapIn(ServerPlayer player, ServerLevel level, Objective o, String villagerName) {
		BlockPos found = find(player, level, o);
		if (found == null) return Optional.empty();
		Kind kind = kindOf(o);
		ItemStack map = MapItem.create(level, found.getX(), found.getZ(), (byte) 2, true, true);
		MapItem.renderBiomePreviewMap(level, map);
		MapItemSavedData.addTargetDecoration(map, found, "+", kind.mark);
		if (kind.item != null) {
			// The game's own explorer map for this place: its icon in the inventory and its name in every language.
			map = map.transmuteCopy(kind.item);
		} else {
			map.set(DataComponents.ITEM_NAME, Lang.tr(player, "map.to", ErrandText.targetName(player, o)));
			map.set(DataComponents.DYED_COLOR, new DyedItemColor(tint(o)));
			map.set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT.withHidden(DataComponents.DYED_COLOR, true));
		}
		List<Component> lore = new ArrayList<>();
		lore.add(Lang.tr(player, "map.from", villagerName).withStyle(s -> s.withItalic(true).withColor(ChatFormatting.GRAY)));
		map.set(DataComponents.LORE, new ItemLore(lore));
		return Optional.of(map);
	}

	/** The game's explorer map item and map icon for a place, where it has them (26.3). */
	private record Kind(Item item, Holder<MapDecorationType> mark) {
	}

	private static Kind kindOf(Objective o) {
		if (o.type() != Objective.Type.VISIT_STRUCTURE) return new Kind(null, MapDecorationTypes.RED_X);
		String id = o.target().substring(o.target().indexOf(':') + 1);
		return switch (id) {
			case "ancient_city" -> new Kind(Items.BURIED_ANCIENT_CITY_MAP, MapDecorationTypes.ANCIENT_CITY);
			case "mansion" -> new Kind(Items.WOODLAND_MANSION_MAP, MapDecorationTypes.WOODLAND_MANSION);
			case "monument" -> new Kind(Items.OCEAN_MONUMENT_MAP, MapDecorationTypes.OCEAN_MONUMENT);
			case "trial_chambers" -> new Kind(Items.BURIED_TRIAL_CHAMBERS_MAP, MapDecorationTypes.TRIAL_CHAMBERS);
			case "desert_pyramid" -> new Kind(Items.DESERT_PYRAMID_MAP, MapDecorationTypes.DESERT_PYRAMID);
			case "jungle_pyramid" -> new Kind(Items.JUNGLE_PYRAMID_MAP, MapDecorationTypes.JUNGLE_TEMPLE);
			case "swamp_hut" -> new Kind(Items.SWAMP_HUT_MAP, MapDecorationTypes.SWAMP_HUT);
			case "abandoned_camp" -> new Kind(Items.ABANDONED_CAMP_MAP, MapDecorationTypes.ABANDONED_CAMP);
			case "mineshaft", "mineshaft_mesa" -> new Kind(Items.BURIED_MINESHAFT_MAP, MapDecorationTypes.MINESHAFT);
			case "ocean_ruin_warm" -> new Kind(Items.WARM_OCEAN_RUINS_MAP, MapDecorationTypes.OCEAN_RUIN_WARM);
			case "buried_treasure" -> new Kind(Items.BURIED_TREASURE_MAP, MapDecorationTypes.RED_X);
			case "village_desert" -> new Kind(Items.DESERT_VILLAGE_MAP, MapDecorationTypes.DESERT_VILLAGE);
			case "village_plains" -> new Kind(Items.PLAINS_VILLAGE_MAP, MapDecorationTypes.PLAINS_VILLAGE);
			case "village_savanna" -> new Kind(Items.SAVANNA_VILLAGE_MAP, MapDecorationTypes.SAVANNA_VILLAGE);
			case "village_snowy" -> new Kind(Items.SNOWY_VILLAGE_MAP, MapDecorationTypes.SNOWY_VILLAGE);
			case "village_taiga" -> new Kind(Items.TAIGA_VILLAGE_MAP, MapDecorationTypes.TAIGA_VILLAGE);
			default -> new Kind(null, MapDecorationTypes.RED_X);
		};
	}
	private static List<ServerLevel> searchOrder(MinecraftServer server, ServerLevel here) {
		List<ServerLevel> out = new ArrayList<>();
		out.add(here);
		for (ResourceKey<Level> key : List.of(Level.OVERWORLD, Level.NETHER, Level.END)) {
			ServerLevel level = server.getLevel(key);
			if (level != null && !out.contains(level)) out.add(level);
		}
		return out;
	}

	/** The player's position in that dimension (a Nether block is 8 overworld blocks; the End starts at 0, 0). */
	private static BlockPos scaled(ServerPlayer player, ServerLevel level) {
		ResourceKey<Level> from = player.level().dimension();
		ResourceKey<Level> to = level.dimension();
		BlockPos pos = player.blockPosition();
		if (from.equals(to)) return pos;
		if (to.equals(Level.END)) return BlockPos.ZERO;
		if (to.equals(Level.NETHER)) return new BlockPos(pos.getX() / 8, 64, pos.getZ() / 8);
		if (from.equals(Level.NETHER)) return new BlockPos(pos.getX() * 8, 64, pos.getZ() * 8);
		return pos;
	}

	private static BlockPos findStructure(ServerLevel level, Objective o, BlockPos origin) {
		Identifier id = Identifier.parse(o.target());
		if (o.tag()) {
			return level.findNearestMapStructure(TagKey.create(Registries.STRUCTURE, id), origin, STRUCTURE_RADIUS, false);
		}
		Optional<Holder.Reference<Structure>> holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
			.get(ResourceKey.create(Registries.STRUCTURE, id));
		if (holder.isEmpty()) return null;
		return level.findNearestMapStructure(HolderSet.direct(holder.get()), origin, STRUCTURE_RADIUS, false);
	}

	private static BlockPos findBiome(ServerLevel level, Objective o, BlockPos origin) {
		Identifier id = Identifier.parse(o.target());
		TagKey<Biome> tag = TagKey.create(Registries.BIOME, id);
		Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(
			b -> o.tag() ? b.is(tag) : b.is(id), origin, BIOME_RADIUS, 32, 64);
		return found != null ? found.getFirst() : null;
	}

	private Maps() {
	}
}
