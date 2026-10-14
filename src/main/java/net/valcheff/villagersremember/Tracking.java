package net.valcheff.villagersremember;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

import java.util.List;

/** Kill counting and reputation loss for harming villagers. */
public final class Tracking {
	public static void onDeath(LivingEntity entity, DamageSource source) {
		if (entity instanceof Villager dead && entity.level().getServer() != null) {
			Trust.lost(entity.level().getServer(), dead.getUUID());
			VillageRegistry.get(entity.level().getServer()).removeResident(dead.getUUID());
		}
		if (entity instanceof net.minecraft.world.entity.monster.zombie.ZombieVillager zombie && entity.level().getServer() != null) {
			Trust.lost(entity.level().getServer(), zombie.getUUID());
		}
		if (!(source.getEntity() instanceof ServerPlayer player)) return;
		if (entity instanceof Villager villager) {
			penalize(player, villager, ErrandsConfig.INSTANCE.killPenalty);
			return;
		}
		// A kill counts for every accepted hunt that wants this mob.
		Actions.record(player, Objective.Type.KILL, 1, o -> o.matchesEntity(entity.getType()));
		// And for a village's "Defend the village" weekly order when it died inside that village.
		GroupOrder.kill(player, entity);
	}

	/**
	 * A villager killed by a zombie becomes a zombie villager, and a cured one becomes a villager again; each time
	 * the game makes a new entity with a new id. Everything known about the villager follows it, so a cured
	 * villager still knows the player and their accepted errands can be handed in.
	 */
	public static void onConversion(net.minecraft.world.entity.Mob previous, net.minecraft.world.entity.Mob converted) {
		boolean zombified = previous instanceof Villager && converted instanceof net.minecraft.world.entity.monster.zombie.ZombieVillager;
		boolean cured = previous instanceof net.minecraft.world.entity.monster.zombie.ZombieVillager && converted instanceof Villager;
		MinecraftServer server = previous.level().getServer();
		if ((!zombified && !cured) || server == null || previous.getUUID().equals(converted.getUUID())) return;
		ErrandsData.get(server).renameVillager(previous.getUUID(), converted.getUUID());
		VillageRegistry.get(server).renameResident(previous.getUUID(), converted.getUUID());
		Trust.converted(server, converted.getUUID(), zombified);
		VillagersRemember.LOG.debug("Villager {} is now {} ({})", previous.getUUID(), converted.getUUID(), zombified ? "zombified" : "cured");
	}

	public static void onDamage(LivingEntity entity, DamageSource source, float baseDamage, float damageTaken, boolean blocked) {
		if (blocked || damageTaken <= 0 || !entity.isAlive()) return;
		if (entity instanceof Villager villager && source.getEntity() instanceof ServerPlayer player && !player.isCreative()) {
			penalize(player, villager, ErrandsConfig.INSTANCE.hitPenalty);
		}
	}

	private static void penalize(ServerPlayer player, Villager villager, int amount) {
		if (amount <= 0) return;
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
		Reputation.addVillager(state, player, villager.getUUID(), villager, -amount);
		Reputation.addVillage(state, ErrandGenerator.village(villager), -amount);
		data.setDirty();
	}
}
