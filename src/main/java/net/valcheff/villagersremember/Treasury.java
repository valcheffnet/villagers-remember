package net.valcheff.villagersremember;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The villages' share for the player (TIERS.md section 6). Every working resident sets aside a share by the
 * player's tier with them; later residents count less, the village standing multiplies it, and befriending several
 * villages adds a network bonus. Income is settled lazily (when the list opens or emeralds are sent), at most
 * {@code treasuryKeepDays} days per village, and sent to the player's ender chest on request.
 */
public final class Treasury {
	/** One village in the player's list: its income per day and what waits to be sent. */
	public record Entry(String key, GlobalPos center, Optional<VillageRegistry.Village> village, double perDay, double waiting,
	                    int counted) {
	}

	/** Income of one village from the shares of its residents (any order) at the given village tier, before the cap. */
	public static double formula(List<Double> shares, int villageTier) {
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		List<Double> sorted = new ArrayList<>(shares);
		sorted.removeIf(s -> s <= 0);
		sorted.sort(Comparator.reverseOrder());
		double sum = 0;
		for (int i = 0; i < sorted.size(); i++) sum += sorted.get(i) / (1.0 + i / 15.0);
		return sum * cfg.treasuryVillageMultiplier[Math.clamp(villageTier, 0, 4)];
	}

	/** Emeralds a day from one village, capped. */
	public static double perDay(ErrandsData.PlayerState state, VillageRegistry.Village village) {
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		if (!cfg.emeraldRewards) return 0;
		List<Double> shares = new ArrayList<>();
		for (VillageRegistry.Resident r : village.workers()) shares.add(cfg.treasuryShare[Reputation.villagerTier(state, r.id)]);
		double income = formula(shares, Reputation.villageTier(state, Optional.of(village.center)));
		return Math.min(income, cfg.treasuryDailyCap);
	}

	/** Working residents who set something aside: the player is past Stranger with them. */
	public static int counted(ErrandsData.PlayerState state, VillageRegistry.Village village) {
		int n = 0;
		for (VillageRegistry.Resident r : village.workers()) if (ErrandsConfig.INSTANCE.treasuryShare[Reputation.villagerTier(state, r.id)] > 0) n++;
		return n;
	}

	/** +5% for every village where the player is at least Friend, up to +25% (config). */
	public static double networkBonus(int friendlyVillages) {
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		return Math.min(cfg.networkBonusMax, friendlyVillages * cfg.networkBonusPerVillage);
	}

	public static int friendlyVillages(MinecraftServer server, ErrandsData.PlayerState state) {
		int n = 0;
		for (String key : keys(state)) {
			GlobalPos center = ErrandsData.parseVillageKey(key);
			if (center != null && Reputation.villageTier(state, Optional.of(center)) >= 2) n++;
		}
		return n;
	}

	/**
	 * Moves what is stored under old keys (a bell position from an older version, or a village merged away) to the
	 * village's center now: the better standing, the earlier discovery, the waiting emeralds added up. Keys of places
	 * that are no village any more are dropped from the list.
	 */
	public static void normalize(MinecraftServer server, ErrandsData.PlayerState state) {
		VillageRegistry registry = VillageRegistry.get(server);
		boolean changed = false;
		for (String key : keys(state)) {
			GlobalPos pos = ErrandsData.parseVillageKey(key);
			Optional<VillageRegistry.Village> village = pos != null ? registry.resolve(pos) : Optional.empty();
			if (village.isEmpty()) {
				changed |= state.discovered.remove(key) != null;
				continue;
			}
			String now = ErrandsData.villageKey(village.get().center);
			if (now.equals(key)) continue;
			Integer rep = state.villageRep.remove(key);
			if (rep != null) state.villageRep.merge(now, rep, Math::max);
			Long found = state.discovered.remove(key);
			if (found != null) state.discovered.merge(now, found, Math::min);
			Double waiting = state.treasury.remove(key);
			if (waiting != null) state.treasury.merge(now, waiting, Double::sum);
			Long paid = state.paidUntil.remove(key);
			if (paid != null) state.paidUntil.merge(now, paid, Math::max);
			if (state.trackedVillages.remove(key)) state.trackedVillages.add(now);
			changed = true;
		}
		if (changed) ErrandsData.get(server).setDirty();
	}

	/** Every village the player knows: discovered ones and any with standing (from before discovery existed). */
	public static Set<String> keys(ErrandsData.PlayerState state) {
		Set<String> keys = new LinkedHashSet<>(state.discovered.keySet());
		keys.addAll(state.villageRep.keySet());
		return keys;
	}

	/** Adds the days since the last settlement to every village's treasury, keeping at most the configured days. */
	public static void settle(MinecraftServer server, ErrandsData.PlayerState state, long today) {
		normalize(server, state);
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		VillageRegistry registry = VillageRegistry.get(server);
		double bonus = 1 + networkBonus(friendlyVillages(server, state));
		for (String key : keys(state)) {
			long from = state.paidUntil.getOrDefault(key, today);
			state.paidUntil.put(key, today);
			if (from >= today) continue;
			GlobalPos center = ErrandsData.parseVillageKey(key);
			Optional<VillageRegistry.Village> village = center != null ? registry.byCenter(center) : Optional.empty();
			if (village.isEmpty()) continue;
			double daily = perDay(state, village.get()) * bonus;
			if (daily <= 0) continue;
			double waiting = state.treasury.getOrDefault(key, 0.0) + daily * (today - from);
			state.treasury.put(key, Math.min(waiting, daily * cfg.treasuryKeepDays));
		}
		ErrandsData.get(server).setDirty();
	}

	/** The player's list, richest village first, then the nearest. */
	public static List<Entry> entries(ServerPlayer player, ErrandsData.PlayerState state) {
		MinecraftServer server = player.level().getServer();
		normalize(server, state);
		VillageRegistry registry = VillageRegistry.get(server);
		double bonus = 1 + networkBonus(friendlyVillages(server, state));
		List<Entry> out = new ArrayList<>();
		Set<String> seen = new java.util.HashSet<>();
		for (String key : keys(state)) {
			GlobalPos center = ErrandsData.parseVillageKey(key);
			if (center == null) continue;
			Optional<VillageRegistry.Village> village = registry.byCenter(center);
			if (village.isEmpty()) continue;
			GlobalPos canonical = village.get().center;
			if (!seen.add(ErrandsData.villageKey(canonical))) continue;
			double perDay = village.map(v -> perDay(state, v) * bonus).orElse(0.0);
			int counted = village.map(v -> counted(state, v)).orElse(0);
			out.add(new Entry(key, canonical, village, perDay, state.treasury.getOrDefault(key, 0.0), counted));
		}
		out.sort(Comparator.comparingDouble((Entry e) -> -e.perDay())
			.thenComparingDouble(e -> e.center().dimension().equals(player.level().dimension())
				? e.center().pos().distSqr(player.blockPosition()) : Double.MAX_VALUE));
		return out;
	}

	/** Moves the whole emeralds waiting in every village into the ender chest; returns how many went in. */
	public static int sendToEnderChest(ServerPlayer player, ErrandsData.PlayerState state) {
		var chest = player.getEnderChestInventory();
		int sent = 0;
		for (String key : keys(state)) {
			double waiting = state.treasury.getOrDefault(key, 0.0);
			int whole = (int) Math.floor(waiting);
			while (whole > 0) {
				int n = Math.min(whole, 64);
				ItemStack left = chest.addItem(new ItemStack(Items.EMERALD, n));
				int moved = n - left.getCount();
				sent += moved;
				whole -= moved;
				waiting -= moved;
				if (moved < n) break;
			}
			state.treasury.put(key, waiting);
			if (whole > 0) break;
		}
		ErrandsData.get(player.level().getServer()).setDirty();
		return sent;
	}

	public static double waiting(ErrandsData.PlayerState state) {
		double total = 0;
		for (double v : state.treasury.values()) total += v;
		return total;
	}

	private Treasury() {
	}
}
