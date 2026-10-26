package net.valcheff.villagersremember.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.valcheff.villagersremember.ActionHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Notes the level shown on the option a player picks. By the time the advancement trigger runs, a book has already
 * become an enchanted book in the slot and the table has set its costs to 0.
 */
@Mixin(EnchantmentMenu.class)
abstract class EnchantmentMenuMixin {
	@Inject(method = "clickMenuButton", at = @At("HEAD"))
	private void villagersremember$chosen(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
		int[] costs = ((EnchantmentMenu) (Object) this).costs;
		if (player instanceof ServerPlayer sp && id >= 0 && id < costs.length) ActionHooks.enchantChosen(sp, costs[id]);
	}
}
