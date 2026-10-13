package net.valcheff.villageerrands.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's own level-up (next level and that level's trades), for the operator command. */
@Mixin(Villager.class)
public interface VillagerInvoker {
	@Invoker("increaseMerchantCareer")
	void villageerrands$increaseMerchantCareer(ServerLevel level);

	/** Vanilla's way into the trading screen, with the player's special prices. */
	@Invoker("startTrading")
	void villageerrands$startTrading(net.minecraft.world.entity.player.Player player);
}
