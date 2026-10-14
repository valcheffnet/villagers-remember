package net.valcheff.villagersremember;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gradual trust (user, 2026-10-14): in a village only a circle of villagers offers a player errands, and every player
 * has their own circle. The village picks one villager who dares to talk to a stranger. Every tier the player reaches
 * with anyone in the circle brings one more villager; the village's tier brings more (Known +1, Friend +2,
 * Respected +3, Hero: everyone), and so does helping to finish a village order. A member who dies leaves a gap that is
 * not filled; only when nobody in the circle is left does someone new dare, the next game day. Villagers outside the
 * circle still trade.
 */
public final class Trust {
	/** One player's circle in one village. */
	public static final class Circle {
		/** In the order they came to trust the player; the first is the one who dared. */
		public final List<UUID> members = new ArrayList<>();
		/** Members who died -> game day. */
		public final Map<UUID, Long> lost = new HashMap<>();
		/** Members who are zombie villagers now (they come back when cured) -> day it happened. */
		public final Map<UUID, Long> zombies = new HashMap<>();
		/** Village orders the player helped to finish here: one more villager each. */
		public int bonus;
		/** Places given so far; a new villager comes only when the player has earned more than this. */
		public int granted;
		private transient long checkedTick = -1;
	}

	public static boolean enabled() {
		return ErrandsConfig.INSTANCE.gradualTrust;
	}

	/** Whether this villager offers the player errands (always without gradual trust or outside a village). */
	public static boolean trusts(MinecraftServer server, ErrandsData.PlayerState state, ErrandGenerator.Giver giver) {
		if (!enabled() || giver.village().isEmpty()) return true;
		Optional<VillageRegistry.Village> village = VillageRegistry.get(server).byCenter(giver.village().get());
		if (village.isEmpty()) return true;
		return circle(server, state, village.get()).members.contains(giver.id());
	}

	/** The player's circle in a village, made the first time and kept up to date (once a tick). */
	public static Circle circle(MinecraftServer server, ErrandsData.PlayerState state, VillageRegistry.Village village) {
		String key = ErrandsData.villageKey(village.center);
		Circle circle = state.circles.get(key);
		if (circle == null) {
			circle = new Circle();
			state.circles.put(key, circle);
			start(server, state, village, circle);
		}
		if (circle.checkedTick != server.getTickCount()) {
			circle.checkedTick = server.getTickCount();
			fill(server, state, village, circle);
		}
		return circle;
	}

	/** The same, checked again now even if it was checked this tick (after a hand-in, for the operator command). */
	public static Circle refresh(MinecraftServer server, ErrandsData.PlayerState state, VillageRegistry.Village village) {
		Circle circle = state.circles.get(ErrandsData.villageKey(village.center));
		if (circle != null) circle.checkedTick = -1;
		return circle(server, state, village);
	}

	/**
	 * A new circle. A player who already worked with villagers here (saves from before gradual trust) keeps all of
	 * them, most points first; otherwise the village picks one when the circle is filled.
	 */
	private static void start(MinecraftServer server, ErrandsData.PlayerState state, VillageRegistry.Village village, Circle circle) {
		List<UUID> known = new ArrayList<>();
		for (VillageRegistry.Resident r : village.residents.values()) {
			boolean worked = Reputation.villager(state, r.id) != 0
				|| state.active.stream().anyMatch(a -> a.errand.villager().equals(r.id))
				|| state.history.stream().anyMatch(e -> e.villager().equals(r.id));
			if (worked) known.add(r.id);
		}
		known.sort(Comparator.comparingInt((UUID id) -> -Reputation.villager(state, id)));
		circle.members.addAll(known);
		circle.granted = known.size();
		ErrandsData.get(server).setDirty();
	}

	/**
	 * Places earned: 1 + every tier reached with anyone in the circle + the village's tier bonus + village orders
	 * helped to finish. 10 000 when the village's tier opens it to everyone.
	 */
	public static int earned(ErrandsData.PlayerState state, VillageRegistry.Village village, Circle circle) {
		int tiers = 0;
		for (UUID id : circle.members) tiers += Reputation.villagerTier(state, id);
		int villageTier = Reputation.villageTier(state, Optional.of(village.center));
		int[] bonus = ErrandsConfig.INSTANCE.trustVillageBonus;
		int fromVillage = bonus[Math.clamp(villageTier, 0, bonus.length - 1)];
		if (fromVillage < 0) return 10_000;
		return 1 + tiers + fromVillage + circle.bonus;
	}

	/** Members who can still offer errands, or will again (zombie villagers can be cured). */
	private static int living(Circle circle) {
		int n = 0;
		for (UUID id : circle.members) if (!circle.lost.containsKey(id)) n++;
		return n;
	}

	private static void fill(MinecraftServer server, ErrandsData.PlayerState state, VillageRegistry.Village village, Circle circle) {
		boolean changed = false;
		if (living(circle) == 0 && !circle.members.isEmpty()) {
			// Everyone who trusted the player is gone: someone new dares, the day after the last one died.
			long today = ErrandGenerator.day(server.overworld());
			long lastLost = circle.lost.values().stream().mapToLong(Long::longValue).max().orElse(today);
			if (today > lastLost) {
				Optional<VillageRegistry.Resident> next = next(state, village, circle);
				if (next.isPresent()) {
					circle.members.add(next.get().id);
					tell(server, state, next.get().id, "trust.first");
					changed = true;
				}
			}
		} else {
			int earned = earned(state, village, circle);
			while (circle.granted < earned) {
				Optional<VillageRegistry.Resident> next = next(state, village, circle);
				if (next.isEmpty()) break;
				circle.members.add(next.get().id);
				tell(server, state, next.get().id, circle.granted == 0 ? "trust.first" : "trust.new");
				circle.granted++;
				changed = true;
			}
		}
		if (changed) ErrandsData.get(server).setDirty();
	}

	/**
	 * Who comes next: a resident with errands to give, not in the circle yet; a trade the circle doesn't have yet first,
	 * then the nearest to the first living member. With no one in the circle the order is stable per player, so two
	 * players can meet different villagers first.
	 */
	private static Optional<VillageRegistry.Resident> next(ErrandsData.PlayerState state, VillageRegistry.Village village, Circle circle) {
		Set<String> trades = new HashSet<>();
		BlockPos near = null;
		for (UUID id : circle.members) {
			VillageRegistry.Resident r = village.residents.get(id);
			if (r == null || circle.lost.containsKey(id)) continue;
			trades.add(r.profession);
			if (near == null) near = BlockPos.of(r.anchor);
		}
		BlockPos from = near;
		return village.residents.values().stream()
			.filter(r -> !r.baby && !circle.members.contains(r.id) && ErrandPools.get(r.profession) != null)
			.min(Comparator.<VillageRegistry.Resident>comparingInt(r -> trades.contains(r.profession) ? 1 : 0)
				.thenComparingDouble(r -> from == null ? 0 : from.distSqr(BlockPos.of(r.anchor)))
				.thenComparingInt(r -> order(state.id, r.id)));
	}

	private static int order(UUID player, UUID villager) {
		return UUID.nameUUIDFromBytes((player + "/" + villager).getBytes(StandardCharsets.UTF_8)).hashCode();
	}

	private static void tell(MinecraftServer server, ErrandsData.PlayerState state, UUID villager, String key) {
		ServerPlayer player = server.getPlayerList().getPlayer(state.id);
		if (player == null) return;
		String name = ErrandText.villagerName(player, villager, Villages.loaded(server.overworld(), villager));
		player.sendSystemMessage(Lang.tr(player, key, name).withStyle(ChatFormatting.YELLOW));
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.VILLAGER_YES, SoundSource.NEUTRAL, 0.6f, 1.0f);
	}

	/** The first member who can talk now (the one other villagers point to), if any. */
	public static Optional<UUID> trusted(MinecraftServer server, ErrandsData.PlayerState state, VillageRegistry.Village village) {
		Circle circle = circle(server, state, village);
		return circle.members.stream().filter(id -> !circle.lost.containsKey(id) && !circle.zombies.containsKey(id)).findFirst();
	}

	/** A village order the player helped to finish: the village talks about them, one more villager opens up. */
	public static void orderHelped(MinecraftServer server, ErrandsData.PlayerState state, VillageRegistry.Village village) {
		Circle circle = circle(server, state, village);
		circle.bonus++;
		refresh(server, state, village);
		ErrandsData.get(server).setDirty();
	}

	// ---- deaths and zombies ----

	/** A villager or zombie villager died: if they were in a circle, their place stays empty. */
	public static void lost(MinecraftServer server, UUID id) {
		ErrandsData data = ErrandsData.get(server);
		long today = ErrandGenerator.day(server.overworld());
		for (ErrandsData.PlayerState state : data.players().values()) {
			for (Circle circle : state.circles.values()) {
				if (!circle.members.contains(id)) continue;
				circle.lost.putIfAbsent(id, today);
				circle.zombies.remove(id);
			}
		}
		data.setDirty();
	}

	/** Called after the ids were moved to the new entity: a member turned into a zombie villager, or was cured. */
	public static void converted(MinecraftServer server, UUID now, boolean zombified) {
		ErrandsData data = ErrandsData.get(server);
		long today = ErrandGenerator.day(server.overworld());
		for (ErrandsData.PlayerState state : data.players().values()) {
			for (Circle circle : state.circles.values()) {
				if (!circle.members.contains(now)) continue;
				circle.lost.remove(now);
				if (zombified) circle.zombies.put(now, today);
				else circle.zombies.remove(now);
			}
		}
		data.setDirty();
	}

	/** Moves a villager's id inside every circle (the entity changes id when it turns into a zombie and back). */
	public static void rename(ErrandsData.PlayerState state, UUID from, UUID to) {
		for (Circle circle : state.circles.values()) {
			int i = circle.members.indexOf(from);
			if (i >= 0) circle.members.set(i, to);
			Long lostDay = circle.lost.remove(from);
			if (lostDay != null) circle.lost.put(to, lostDay);
			Long zombieDay = circle.zombies.remove(from);
			if (zombieDay != null) circle.zombies.put(to, zombieDay);
		}
	}

	// ---- saving ----

	public static net.minecraft.nbt.CompoundTag save(Circle circle) {
		net.minecraft.nbt.CompoundTag t = new net.minecraft.nbt.CompoundTag();
		net.minecraft.nbt.ListTag members = new net.minecraft.nbt.ListTag();
		circle.members.forEach(id -> members.add(net.minecraft.nbt.StringTag.valueOf(id.toString())));
		t.put("members", members);
		net.minecraft.nbt.CompoundTag lost = new net.minecraft.nbt.CompoundTag();
		circle.lost.forEach((id, day) -> lost.putLong(id.toString(), day));
		t.put("lost", lost);
		net.minecraft.nbt.CompoundTag zombies = new net.minecraft.nbt.CompoundTag();
		circle.zombies.forEach((id, day) -> zombies.putLong(id.toString(), day));
		t.put("zombies", zombies);
		t.putInt("bonus", circle.bonus);
		t.putInt("granted", circle.granted);
		return t;
	}

	public static Circle load(net.minecraft.nbt.CompoundTag t) {
		Circle circle = new Circle();
		t.getListOrEmpty("members").forEach(tag -> tag.asString().ifPresent(s -> parse(s).ifPresent(circle.members::add)));
		net.minecraft.nbt.CompoundTag lost = t.getCompoundOrEmpty("lost");
		for (String k : lost.keySet()) parse(k).ifPresent(id -> circle.lost.put(id, lost.getLongOr(k, 0L)));
		net.minecraft.nbt.CompoundTag zombies = t.getCompoundOrEmpty("zombies");
		for (String k : zombies.keySet()) parse(k).ifPresent(id -> circle.zombies.put(id, zombies.getLongOr(k, 0L)));
		circle.bonus = t.getIntOr("bonus", 0);
		circle.granted = t.getIntOr("granted", circle.members.size());
		return circle;
	}

	private static Optional<UUID> parse(String s) {
		try {
			return Optional.of(UUID.fromString(s));
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
