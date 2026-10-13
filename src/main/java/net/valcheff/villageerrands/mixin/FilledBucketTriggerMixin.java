package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.FilledBucketTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FilledBucketTrigger.class)
abstract class FilledBucketTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$filled(ServerPlayer player, ItemStack bucket, CallbackInfo ci) {
		Actions.record(player, Objective.Type.FILL_BUCKET, 1, o -> o.matchesItem(bucket));
	}
}
