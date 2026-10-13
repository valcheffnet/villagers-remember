package net.valcheff.villagersremember.mixin;

import net.minecraft.advancements.triggers.EnchantedItemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villagersremember.Actions;
import net.valcheff.villagersremember.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnchantedItemTrigger.class)
abstract class EnchantedItemTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villagersremember$enchanted(ServerPlayer player, ItemStack item, int levels, CallbackInfo ci) {
		Actions.record(player, Objective.Type.ENCHANT, 1, o -> levels >= o.minLevel() && (o.target().isEmpty() || o.matchesItem(item)));
	}
}
