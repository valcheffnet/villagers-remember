package net.valcheff.villagersremember;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.valcheff.villagersremember.ui.ErrandDialogs;

import java.util.Optional;

/** Shift + right-click on a villager opens its errands; right-click on a bell opens the village board. */
public final class Interactions {
	public static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		if (level.isClientSide() || !(player instanceof ServerPlayer sp) || player.isSpectator()) return InteractionResult.PASS;
		if (entity instanceof WanderingTrader trader) {
			// A Legend finds rare goods with the wandering trader; the trading itself stays vanilla.
			if (hand == InteractionHand.MAIN_HAND) Fame.legendGoods(sp, trader);
			return InteractionResult.PASS;
		}
		if (!(entity instanceof Villager villager) || villager.isSleeping() || villager.isBaby()) return InteractionResult.PASS;
		if (!ErrandsConfig.INSTANCE.talkMenu) {
			// The old way: right-click trades, sneak + right-click shows the errands.
			if (!player.isShiftKeyDown() || !ErrandGenerator.givesErrands(villager)) return InteractionResult.PASS;
			if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
			ErrandDialogs.villager(sp, villager);
			return InteractionResult.SUCCESS;
		}
		// Sneak + right-click: straight to trading, as in vanilla (trading halls stay one click).
		if (player.isShiftKeyDown()) return InteractionResult.PASS;
		// Things vanilla uses on a villager keep working.
		var held = player.getItemInHand(hand);
		if (held.is(Items.NAME_TAG) || held.getItem() instanceof SpawnEggItem) {
			return InteractionResult.PASS;
		}
		if (!ErrandGenerator.givesErrands(villager) && !ErrandDialogs.canTrade(villager)) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
		ErrandDialogs.greet(sp, villager);
		return InteractionResult.SUCCESS;
	}

	public static InteractionResult onUseBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (level.isClientSide() || !(player instanceof ServerPlayer sp) || player.isSpectator()) return InteractionResult.PASS;
		if (!ErrandsConfig.INSTANCE.bellOpensBoard || player.isShiftKeyDown()) return InteractionResult.PASS;
		if (!(level.getBlockState(hit.getBlockPos()).getBlock() instanceof BellBlock)) return InteractionResult.PASS;
		// During a raid the bell is for ringing.
		if (((ServerLevel) level).getRaidAt(hit.getBlockPos()) != null) return InteractionResult.PASS;
		// Any bell inside a village opens that village's board; a bell far from every village just rings.
		Optional<VillageRegistry.Village> village = Villages.villageOf((ServerLevel) level, GlobalPos.of(level.dimension(), hit.getBlockPos()));
		if (village.isEmpty()) return InteractionResult.PASS;
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;
		ErrandDialogs.board(sp, GlobalPos.of(level.dimension(), hit.getBlockPos()), village.get().center);
		return InteractionResult.SUCCESS;
	}
}
