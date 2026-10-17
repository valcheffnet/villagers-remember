package net.valcheff.villagersremember.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's own level-up (next level and that level's trades), for the operator command. */
@Mixin(Villager.class)
public interface VillagerInvoker {
	@Invoker("increaseMerchantCareer")
	void villagersremember$increaseMerchantCareer(ServerLevel level);

	/** Vanilla's way into the trading screen, with the player's special prices. */
	@Invoker("startTrading")
	void villagersremember$startTrading(Player player);
}
