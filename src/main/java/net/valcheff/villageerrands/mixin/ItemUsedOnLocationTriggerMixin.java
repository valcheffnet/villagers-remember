package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.advancements.triggers.ItemUsedOnLocationTrigger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.Items;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Placing a block from an item (planting saplings and seeds) and using bone meal on a block. */
@Mixin(ItemUsedOnLocationTrigger.class)
abstract class ItemUsedOnLocationTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$used(ServerPlayer player, BlockPos pos, ItemInstance used, CallbackInfo ci) {
		Item item = used.typeHolder().value();
		if ((Object) this == CriteriaTriggers.PLACED_BLOCK) {
			Actions.record(player, Objective.Type.PLANT, 1, o -> o.matchesItem(item));
		} else if ((Object) this == CriteriaTriggers.ITEM_USED_ON_BLOCK && item == Items.BONE_MEAL) {
			Actions.record(player, Objective.Type.BONEMEAL, 1, o -> true);
		}
	}
}
