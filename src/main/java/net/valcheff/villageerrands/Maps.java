package net.valcheff.villageerrands;

import com.mojang.datafixers.util.Pair;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.component.ItemLore;
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
 * A map to the place an errand sends the player to: given when an errand with a visit is accepted, like the
 * explorer maps of cartographers. The nearest structure (or biome) is searched in the player's dimension first,
 * then in the others (a fortress is found in the Nether, an end city in the End).
 */
public final class Maps {
	/** Chunks searched for a structure, as vanilla explorer maps do. */
	private static final int STRUCTURE_RADIUS = 100;
	private static final int BIOME_RADIUS = 6400;

	public static Optional<ItemStack> forVisit(ServerPlayer player, Objective o, String villagerName) {
		if (o.type() != Objective.Type.VISIT_STRUCTURE && o.type() != Objective.Type.VISIT_BIOME) return Optional.empty();
		MinecraftServer server = player.level().getServer();
		for (ServerLevel level : searchOrder(server, (ServerLevel) player.level())) {
			BlockPos origin = scaled(player, level);
			BlockPos found;
			Holder<MapDecorationType> mark = MapDecorationTypes.RED_X;
			try {
				if (o.type() == Objective.Type.VISIT_STRUCTURE) {
					found = findStructure(level, o, origin);
					String id = o.target();
					if (id.endsWith("mansion")) mark = MapDecorationTypes.WOODLAND_MANSION;
					else if (id.endsWith("monument")) mark = MapDecorationTypes.OCEAN_MONUMENT;
					else if (id.endsWith("trial_chambers")) mark = MapDecorationTypes.TRIAL_CHAMBERS;
				} else {
					found = findBiome(level, o, origin);
				}
			} catch (RuntimeException e) {
				VillageErrands.LOG.debug("Map search for {} failed", o.target(), e);
				found = null;
			}
			if (found == null) continue;
			ItemStack map = MapItem.create(level, found.getX(), found.getZ(), (byte) 2, true, true);
			MapItem.renderBiomePreviewMap(level, map);
			MapItemSavedData.addTargetDecoration(map, found, "+", mark);
			map.set(DataComponents.CUSTOM_NAME, Lang.tr(player, "map.to", ErrandText.targetName(player, o))
				.withStyle(s -> s.withItalic(false).withColor(ChatFormatting.GOLD)));
			List<Component> lore = new ArrayList<>();
			lore.add(Lang.tr(player, "map.from", villagerName).withStyle(s -> s.withItalic(true).withColor(ChatFormatting.GRAY)));
			map.set(DataComponents.LORE, new ItemLore(lore));
			return Optional.of(map);
		}
		return Optional.empty();
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
