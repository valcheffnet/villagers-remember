package net.valcheff.villagersremember.mixin;

import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.valcheff.villagersremember.Prices;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prices by standing, worked out with vanilla's special prices (before they are sent to the trading player). */
@Mixin(Villager.class)
public abstract class VillagerPricesMixin {
	@Inject(method = "updateSpecialPrices", at = @At("HEAD"))
	private void villagersremember$cleanGossip(Player player, CallbackInfo ci) {
		Prices.beforeVanilla((Villager) (Object) this, player);
	}

	@Inject(method = "updateSpecialPrices", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/npc/villager/Villager;getTradingPlayer()Lnet/minecraft/world/entity/player/Player;"))
	private void villagersremember$prices(Player player, CallbackInfo ci) {
		Prices.apply((Villager) (Object) this, player);
	}
}
