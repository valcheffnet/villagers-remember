package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.TameAnimalTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Animal;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TameAnimalTrigger.class)
abstract class TameAnimalTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$tamed(ServerPlayer player, Animal animal, CallbackInfo ci) {
		Actions.record(player, Objective.Type.TAME, 1, o -> o.matchesEntity(animal.getType()));
	}
}
