package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.FishingRodHookedTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collection;

@Mixin(FishingRodHookedTrigger.class)
abstract class FishingRodHookedTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$caught(ServerPlayer player, ItemStack rod, FishingHook hook, Collection<ItemStack> items, CallbackInfo ci) {
		for (ItemStack stack : items) Actions.record(player, Objective.Type.CATCH, stack.getCount(), o -> o.matchesItem(stack));
	}
}
