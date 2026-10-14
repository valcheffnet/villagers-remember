package net.valcheff.villagersremember.mixin;

import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.valcheff.villagersremember.ActionHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A right-click on a block, on the server thread, before anything (vanilla or a mod like RightClickHarvest) handles
 * it: the mature crops around the spot are remembered, to see at the end of the tick whether they were harvested,
 * and the items dropped while it is handled are kept to count what the harvest gave.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class UseItemOnMixin {
	@Inject(method = "handleUseItemOn", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V",
		shift = At.Shift.AFTER))
	private void villagersremember$beforeUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		ServerGamePacketListenerImpl self = (ServerGamePacketListenerImpl) (Object) this;
		ActionHooks.beforeUse(self.player, packet.hitResult().getBlockPos());
	}

	@Inject(method = "handleUseItemOn", at = @At("RETURN"))
	private void villagersremember$afterUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
		ActionHooks.used();
	}
}
