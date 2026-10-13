package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.SummonedEntityTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Building an iron golem or snow golem by hand (the Wither too). */
@Mixin(SummonedEntityTrigger.class)
abstract class SummonedEntityTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$summoned(ServerPlayer player, Entity entity, CallbackInfo ci) {
		Actions.record(player, Objective.Type.SUMMON, 1, o -> o.matchesEntity(entity.getType()));
	}
}
