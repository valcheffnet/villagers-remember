package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.TradeTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TradeTrigger.class)
abstract class TradeTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$traded(ServerPlayer player, AbstractVillager merchant, ItemStack bought, CallbackInfo ci) {
		Actions.record(player, Objective.Type.TRADE, 1, o -> o.matchesItem(bought));
	}
}
