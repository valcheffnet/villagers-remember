package net.valcheff.villagersremember.mixin;

import net.minecraft.advancements.criterion.BrewedPotionTrigger;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.alchemy.Potion;
import net.valcheff.villagersremember.Actions;
import net.valcheff.villagersremember.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrewedPotionTrigger.class)
abstract class BrewedPotionTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villagersremember$brewed(ServerPlayer player, Holder<Potion> potion, CallbackInfo ci) {
		Actions.record(player, Objective.Type.BREW, 1, o -> o.matchesPotion(potion));
	}
}
