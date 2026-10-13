package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.BredAnimalsTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BredAnimalsTrigger.class)
abstract class BredAnimalsTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$bred(ServerPlayer player, Animal parent, Animal partner, AgeableMob child, CallbackInfo ci) {
		Actions.record(player, Objective.Type.BREED, 1, o -> o.matchesEntity(parent.getType()));
	}
}
