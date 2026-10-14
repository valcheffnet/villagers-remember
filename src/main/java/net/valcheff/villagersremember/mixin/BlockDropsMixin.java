package net.valcheff.villagersremember.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.valcheff.villagersremember.ActionHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every item a block drops, vanilla or a mod like RightClickHarvest: what a harvest really gave. */
@Mixin(Block.class)
abstract class BlockDropsMixin {
	@Inject(method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"))
	private static void villagersremember$dropped(Level level, BlockPos pos, ItemStack stack, CallbackInfo ci) {
		ActionHooks.dropped(level, pos, stack);
	}
}
