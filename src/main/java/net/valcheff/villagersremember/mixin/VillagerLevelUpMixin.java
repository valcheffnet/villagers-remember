package net.valcheff.villagersremember.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.valcheff.villagersremember.Trust;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A villager went up a trade level after trading: the player who traded with it is someone it wants to talk to. */
@Mixin(Villager.class)
abstract class VillagerLevelUpMixin {
	@Shadow
	private Player lastTradedPlayer;

	@Inject(method = "increaseMerchantCareer", at = @At("TAIL"))
	private void villagersremember$levelledUp(ServerLevel level, CallbackInfo ci) {
		if (lastTradedPlayer instanceof ServerPlayer player) Trust.tradedUp(player, (Villager) (Object) this);
	}
}
