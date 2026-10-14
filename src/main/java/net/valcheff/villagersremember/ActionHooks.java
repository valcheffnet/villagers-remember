package net.valcheff.villagersremember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Translates game events (from Fabric events and the mixins) into {@link Actions#record} calls. */
public final class ActionHooks {
	private static final int VISIT_INTERVAL = 40;

	/** {@code ItemStack.onCraftedBy}: which kind of making it was depends on the open screen. */
	public static void crafted(ServerPlayer player, ItemStack stack, int amount) {
		if (stack.isEmpty()) return;
		AbstractContainerMenu menu = player.containerMenu;
		Objective.Type type;
		if (menu instanceof InventoryMenu || menu instanceof CraftingMenu) type = Objective.Type.CRAFT;
		else if (menu instanceof AbstractFurnaceMenu) type = Objective.Type.SMELT;
		else if (menu instanceof StonecutterMenu || menu instanceof SmithingMenu || menu instanceof CartographyTableMenu) type = Objective.Type.STATION;
		else return;
		int count = Math.max(amount, 1);
		Actions.record(player, type, count, o -> o.matchesItem(stack));
	}

	/**
	 * Mods like RightClickHarvest pick a crop with a right-click and replant it without breaking the block, so
	 * {@link #blockBroken} never sees it. A right-click remembers the mature crops around the spot (radius 2, for
	 * harvesting with a hoe's reach); at the end of the tick each one that is the same crop but young again counts
	 * as a harvest. A broken crop is gone by then and was counted by the break.
	 */
	private static final java.util.Map<ServerPlayer, java.util.Map<BlockPos, BlockState>> CLICKED = new java.util.HashMap<>();
	private static final int CLICK_RADIUS = 2;

	public static void beforeUse(ServerPlayer player, BlockPos pos) {
		Level level = player.level();
		java.util.Map<BlockPos, BlockState> crops = null;
		for (BlockPos at : BlockPos.betweenClosed(pos.offset(-CLICK_RADIUS, -1, -CLICK_RADIUS), pos.offset(CLICK_RADIUS, 1, CLICK_RADIUS))) {
			BlockState state = level.getBlockState(at);
			if (!mature(state)) continue;
			if (crops == null) crops = CLICKED.computeIfAbsent(player, p -> new java.util.HashMap<>());
			crops.putIfAbsent(at.immutable(), state);
		}
	}

	/** End of the server tick: crops right-clicked this tick that are young again were harvested. */
	public static void afterUse(MinecraftServer server) {
		if (CLICKED.isEmpty()) return;
		for (java.util.Map.Entry<ServerPlayer, java.util.Map<BlockPos, BlockState>> e : CLICKED.entrySet()) {
			ServerPlayer player = e.getKey();
			if (player.isRemoved()) continue;
			for (java.util.Map.Entry<BlockPos, BlockState> crop : e.getValue().entrySet()) {
				BlockState now = player.level().getBlockState(crop.getKey());
				if (now.getBlock() != crop.getValue().getBlock() || mature(now)) continue;
				BlockState before = crop.getValue();
				Actions.record(player, Objective.Type.HARVEST, 1, o -> o.matchesBlock(before));
			}
		}
		CLICKED.clear();
	}

	/** Breaking a block: a fully grown crop is a harvest; any block mined without Silk Touch counts as mined. */
	public static void blockBroken(Level level, Player player, BlockPos pos, BlockState state, BlockEntity blockEntity) {
		if (!(player instanceof ServerPlayer sp)) return;
		if (mature(state)) Actions.record(sp, Objective.Type.HARVEST, 1, o -> o.matchesBlock(state));
		if (!silkTouch(level, sp.getMainHandItem())) Actions.record(sp, Objective.Type.MINE, 1, o -> o.matchesBlock(state));
	}

	private static boolean mature(BlockState state) {
		if (state.getBlock() instanceof CropBlock crop) return crop.isMaxAge(state);
		if (state.getBlock() instanceof NetherWartBlock) return state.getValue(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
		if (state.getBlock() instanceof CocoaBlock) return state.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
		return false;
	}

	private static boolean silkTouch(Level level, ItemStack tool) {
		Holder<Enchantment> silk = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH);
		return EnchantmentHelper.getItemEnchantmentLevel(silk, tool) > 0;
	}

	/** Every two seconds: is the player inside a structure or biome an accepted errand asks to visit? */
	/** The first time a player stands in a village: a title, and the village joins the treasury list and the book. */
	private static void discover(MinecraftServer server, ServerPlayer player, ErrandsData.PlayerState state) {
		var village = VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition());
		if (village.isEmpty()) return;
		String key = ErrandsData.villageKey(village.get().center);
		if (state.discovered.containsKey(key)) return;
		long day = ErrandGenerator.day((ServerLevel) player.level());
		state.discovered.put(key, day);
		state.paidUntil.putIfAbsent(key, day);
		ErrandsData.get(server).setDirty();
		Hud.title(player, Lang.tr(player, "discover.title").withStyle(net.minecraft.ChatFormatting.GOLD),
			Component.literal(ErrandText.villageName(player, village.get().center)).withStyle(net.minecraft.ChatFormatting.WHITE));
		player.sendSystemMessage(Lang.tr(player, "discover.chat", ErrandText.villageName(player, village.get().center))
			.withStyle(net.minecraft.ChatFormatting.GRAY));
		Fame.add(player, Fame.DISCOVER);
		Milestones.check(player);
	}

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % VISIT_INTERVAL != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ErrandsData.PlayerState state = ErrandService.state(player);
			discover(server, player, state);
			if (Trust.enabled()) {
				VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition())
					.ifPresent(v -> Trust.circle(server, state, v));
			}
			boolean wants = state.active.stream().anyMatch(a -> a.errand.objectives().stream()
				.anyMatch(o -> o.type() == Objective.Type.VISIT_STRUCTURE || o.type() == Objective.Type.VISIT_BIOME));
			if (!wants) continue;
			ServerLevel level = (ServerLevel) player.level();
			BlockPos pos = player.blockPosition();
			Actions.record(player, Objective.Type.VISIT_STRUCTURE, 1, o -> inStructure(level, pos, o));
			Holder<Biome> biome = level.getBiome(pos);
			Actions.record(player, Objective.Type.VISIT_BIOME, 1, o -> o.tag()
				? biome.is(TagKey.create(Registries.BIOME, Identifier.parse(o.target())))
				: biome.is(Identifier.parse(o.target())));
		}
	}

	private static boolean inStructure(ServerLevel level, BlockPos pos, Objective o) {
		if (o.target().isEmpty()) return false;
		Identifier id = Identifier.parse(o.target());
		if (o.tag()) {
			return level.structureManager().getStructureWithPieceAt(pos, TagKey.create(Registries.STRUCTURE, id)).isValid();
		}
		var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).get(ResourceKey.create(Registries.STRUCTURE, id));
		if (structure.isEmpty()) return false;
		HolderSet<Structure> set = HolderSet.direct(structure.get());
		return level.structureManager().getStructureWithPieceAt(pos, set).isValid();
	}
}
