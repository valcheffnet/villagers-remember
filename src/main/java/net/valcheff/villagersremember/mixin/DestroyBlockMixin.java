package net.valcheff.villagersremember.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.valcheff.villagersremember.ActionHooks;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player breaks a block: the drops come after the block-break event, so the harvest is counted on return. */
@Mixin(ServerPlayerGameMode.class)
abstract class DestroyBlockMixin {
	@Shadow
	protected ServerLevel level;
	@Shadow
	@Final
	protected ServerPlayer player;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void villagersremember$breaking(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		ActionHooks.breaking(level.getBlockState(pos));
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void villagersremember$broken(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		ActionHooks.broken(player, pos, cir.getReturnValueZ());
	}
}
