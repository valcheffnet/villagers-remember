package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.BrewedPotionTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.alchemy.PotionContents;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrewedPotionTrigger.class)
abstract class BrewedPotionTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$brewed(ServerPlayer player, PotionContents contents, CallbackInfo ci) {
		contents.potion().ifPresent(potion -> Actions.record(player, Objective.Type.BREW, 1, o -> o.matchesPotion(potion)));
	}
}
