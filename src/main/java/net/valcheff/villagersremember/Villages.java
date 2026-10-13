package net.valcheff.villagersremember;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Finding a village from a bell and everything its board lists for a player. */
public final class Villages {
	/** One line of a village board: an errand, its giver, and the giver's entity if it is loaded. */
	public record Entry(Errand errand, ErrandGenerator.Giver giver, Villager villager) {
	}

	/**
	 * The village a bell belongs to: a known bell, a bell inside a village's area (e.g. one the player built in
	 * their base), or a bell villagers gather at. Empty for a lone bell, which then just rings. Bells inside a
	 * village are added to it, which extends the village area.
	 */
	public static Optional<VillageRegistry.Village> villageOf(ServerLevel level, GlobalPos bell) {
		VillageRegistry registry = VillageRegistry.get(level.getServer());
		if (registry.forBell(level.getServer(), bell, false).isPresent()) return registry.forBell(level.getServer(), bell, true);
		Optional<VillageRegistry.Village> around = registry.villageAt(bell.dimension(), bell.pos());
		if (around.isPresent()) {
			registry.attachBell(level.getServer(), around.get(), bell);
			return registry.forBell(level.getServer(), bell, false).or(() -> around);
		}
		int r = ErrandsConfig.INSTANCE.villageRadius;
		boolean gathered = !level.getEntitiesOfClass(Villager.class, new AABB(bell.pos()).inflate(r, Math.min(r, 48), r),
			v -> v.getBrain().getMemory(MemoryModuleType.MEETING_POINT).map(bell::equals).orElse(false)).isEmpty();
		return gathered ? registry.forBell(level.getServer(), bell, true) : Optional.empty();
	}

	/** Board lines: the player's accepted errands from this village first, then open ones, then today's finished ones. */
	public static List<Entry> board(ServerPlayer player, VillageRegistry.Village village) {
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = ErrandService.state(player);
		boolean respected = Reputation.respected(state, Optional.of(village.center));
		List<VillageRegistry.Resident> residents = new ArrayList<>(village.residents.values());
		residents.sort(Comparator.comparing((VillageRegistry.Resident r) -> r.profession).thenComparing(r -> r.id));

		List<Entry> accepted = new ArrayList<>();
		Set<String> shown = new HashSet<>();
		for (ErrandsData.Active a : state.active) {
			if (!a.errand.village().map(v -> VillageRegistry.get(level.getServer()).canonical(v).equals(village.center)).orElse(false)) continue;
			VillageRegistry.Resident r = village.residents.get(a.errand.villager());
			ErrandGenerator.Giver giver = r != null ? ErrandGenerator.Giver.of(village, r) : null;
			accepted.add(new Entry(a.errand, giver, loaded(level, a.errand.villager())));
			shown.add(a.errand.id());
		}
		List<Entry> open = new ArrayList<>();
		List<Entry> done = new ArrayList<>();
		for (VillageRegistry.Resident r : residents) {
			if (r.baby) continue;
			ErrandGenerator.Giver giver = ErrandGenerator.Giver.of(village, r);
			if (!giver.givesErrands()) continue;
			for (Errand e : ErrandGenerator.shown(level, giver, state)) {
				if (!shown.add(e.id())) continue;
				(state.done.containsKey(e.id()) ? done : open).add(new Entry(e, giver, loaded(level, r.id)));
			}
		}
		List<Entry> out = new ArrayList<>(accepted);
		out.addAll(open);
		out.addAll(done);
		return out;
	}

	public static Villager loaded(ServerLevel level, java.util.UUID id) {
		return level.getEntity(id) instanceof Villager v && v.isAlive() ? v : null;
	}
}
