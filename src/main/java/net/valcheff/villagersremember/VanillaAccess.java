package net.valcheff.villagersremember;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.valcheff.villagersremember.mixin.EntityNameAccessor;
import net.valcheff.villagersremember.mixin.VillagerInvoker;

import java.util.Optional;

/**
 * Vanilla members the mod uses that vanilla keeps private. Fabric and NeoForge reach them through mixin accessors;
 * the Paper build has no mixins and replaces this class with its own.
 */
public final class VanillaAccess {
	/** Vanilla's way into the trading screen, with the player's special prices. */
	public static void startTrading(Villager villager, Player player) {
		((VillagerInvoker) villager).villagersremember$startTrading(player);
	}

	/** Vanilla's own level-up: the next level and that level's trades. */
	public static void increaseMerchantCareer(Villager villager, ServerLevel level) {
		((VillagerInvoker) villager).villagersremember$increaseMerchantCareer(level);
	}

	public static EntityDataAccessor<Optional<Component>> customName() {
		return EntityNameAccessor.villagersremember$customName();
	}

	public static EntityDataAccessor<Boolean> customNameVisible() {
		return EntityNameAccessor.villagersremember$customNameVisible();
	}

	private VanillaAccess() {
	}
}
