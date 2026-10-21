package net.valcheff.villagersremember.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villagersremember.ActionHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A shift-clicked stonecutter result reaches {@code ItemStack.onCraftedBy} only after it was moved into the inventory,
 * so the stack is empty there and nothing counts. This counts what was moved, from the copy taken before the move;
 * what could not be moved is still counted by {@link ItemStackCraftedMixin}.
 */
@Mixin(StonecutterMenu.class)
abstract class StonecutterQuickMoveMixin {
	@Inject(method = "quickMoveStack", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/inventory/Slot;onTake(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;)V"))
	private void villagersremember$shiftCrafted(Player player, int index, CallbackInfoReturnable<ItemStack> cir,
			@Local(ordinal = 0) ItemStack before, @Local(ordinal = 1) ItemStack left) {
		if (index != StonecutterMenu.RESULT_SLOT || !(player instanceof ServerPlayer sp)) return;
		int moved = before.getCount() - left.getCount();
		if (moved > 0) ActionHooks.crafted(sp, before, moved);
	}
}
