package net.valcheff.villagersremember.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villagersremember.ActionHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Crafting, furnace/smoker/blast furnace output and stonecutter/smithing/cartography results taken by a player. */
@Mixin(ItemStack.class)
abstract class ItemStackCraftedMixin {
	@Inject(method = "onCraftedBy", at = @At("HEAD"))
	private void villagersremember$crafted(Player player, int amount, CallbackInfo ci) {
		if (player instanceof ServerPlayer sp) ActionHooks.crafted(sp, (ItemStack) (Object) this, amount);
	}
}
