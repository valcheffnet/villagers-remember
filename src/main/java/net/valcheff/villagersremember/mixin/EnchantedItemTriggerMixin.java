package net.valcheff.villagersremember.mixin;

import net.minecraft.advancements.triggers.EnchantedItemTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villagersremember.ActionHooks;
import net.valcheff.villagersremember.Actions;
import net.valcheff.villagersremember.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnchantedItemTrigger.class)
abstract class EnchantedItemTriggerMixin {
	/** {@code levels} is what the enchant cost (1 to 3); min_level is about the level shown on the chosen option. */
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villagersremember$enchanted(ServerPlayer player, ItemStack item, int levels, CallbackInfo ci) {
		int level = ActionHooks.enchantLevel(player, levels);
		Actions.record(player, Objective.Type.ENCHANT, 1, o -> level >= o.minLevel() && (o.target().isEmpty() || o.matchesItem(item)));
	}
}
