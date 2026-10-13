package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.CuredZombieVillagerTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.npc.villager.Villager;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CuredZombieVillagerTrigger.class)
abstract class CuredZombieVillagerTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$cured(ServerPlayer player, Zombie zombie, Villager villager, CallbackInfo ci) {
		Actions.record(player, Objective.Type.CURE, 1, o -> true);
	}
}
