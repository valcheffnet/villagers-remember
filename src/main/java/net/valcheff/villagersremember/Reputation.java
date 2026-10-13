package net.valcheff.villagersremember;

import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reputation with a single villager and with a whole village.
 *
 * <p>A village tier needs both points and breadth: enough points, and enough working residents the player has
 * reached that tier with ({@link #VILLAGE_NEEDS}). Villages are identified by their center bell; merged villages
 * are followed to the village they became part of.
 */
public final class Reputation {
	public static final int[] VILLAGER_TIERS = {0, 20, 60, 140, 280};
	public static final int[] VILLAGE_TIERS = {0, 60, 180, 420, 840};
	/** Working residents needed at (at least) the same tier, per village tier. */
	public static final int[] VILLAGE_NEEDS = {0, 2, 3, 4, 5};
	public static final double[] VILLAGE_BONUS = {0, 0.05, 0.10, 0.15, 0.20};
	public static final int RESPECTED_TIER = 3;
	public static final int MAX_TIER = 4;

	/**
	 * Where a player stands in a village. {@code tier} is the tier reached; for the next tier {@code nextPoints}
	 * points and {@code needCount} residents at that tier are needed, {@code haveCount} of whom the player has.
	 */
	public record Standing(int points, int tier, int nextPoints, int needCount, int haveCount, int residents) {
		public boolean max() {
			return tier >= MAX_TIER;
		}
	}

	public static int tier(int points, int[] tiers) {
		int t = 0;
		for (int i = 0; i < tiers.length; i++) if (points >= tiers[i]) t = i;
		return t;
	}

	private static String key(GlobalPos village) {
		MinecraftServer server = VillagersRemember.server();
		GlobalPos canonical = server != null ? VillageRegistry.get(server).canonical(village) : village;
		return ErrandsData.villageKey(canonical);
	}

	public static int village(ErrandsData.PlayerState state, Optional<GlobalPos> village) {
		return village.map(v -> state.villageRep.getOrDefault(key(v), 0)).orElse(0);
	}

	public static Standing standing(ErrandsData.PlayerState state, Optional<GlobalPos> village) {
		int points = village(state, village);
		MinecraftServer server = VillagersRemember.server();
		List<VillageRegistry.Resident> workers = village.isPresent() && server != null
			? VillageRegistry.get(server).byCenter(village.get()).map(VillageRegistry.Village::workers).orElse(List.of())
			: List.of();
		int tier = tier(points, VILLAGE_TIERS);
		while (tier > 0 && countAtLeast(state, workers, tier) < VILLAGE_NEEDS[tier]) tier--;
		int next = Math.min(tier + 1, MAX_TIER);
		return new Standing(points, tier, VILLAGE_TIERS[next], VILLAGE_NEEDS[next], countAtLeast(state, workers, next), workers.size());
	}

	private static int countAtLeast(ErrandsData.PlayerState state, List<VillageRegistry.Resident> workers, int tier) {
		int n = 0;
		for (VillageRegistry.Resident r : workers) if (villagerTier(state, r.id) >= tier) n++;
		return n;
	}

	public static int villageTier(ErrandsData.PlayerState state, Optional<GlobalPos> village) {
		return standing(state, village).tier();
	}

	/** Enchanting levels added to an enchanted reward: 3 per village tier. */
	public static int enchantBonus(ErrandsData.PlayerState state, Optional<GlobalPos> village) {
		return 3 * villageTier(state, village);
	}

	public static boolean respected(ErrandsData.PlayerState state, Optional<GlobalPos> village) {
		return villageTier(state, village) >= RESPECTED_TIER;
	}

	public static int villager(ErrandsData.PlayerState state, UUID villager) {
		return state.villagerRep.getOrDefault(villager, 0);
	}

	/**
	 * The player's tier with a villager: what the points reach, but never above the last trust trial passed with
	 * them (TIERS.md section 1). Packs, village standing and the treasury all use this.
	 */
	public static int villagerTier(ErrandsData.PlayerState state, UUID villager) {
		return Math.min(pointsTier(state, villager), trialsPassed(state, villager));
	}

	public static int pointsTier(ErrandsData.PlayerState state, UUID villager) {
		return tier(villager(state, villager), VILLAGER_TIERS);
	}

	public static int trialsPassed(ErrandsData.PlayerState state, UUID villager) {
		return state.trialsPassed.getOrDefault(villager, 0);
	}

	/** The tier the player can prove with a trust trial now (points at its threshold), or 0 when none waits. */
	public static int trialPending(ErrandsData.PlayerState state, UUID villager) {
		int passed = trialsPassed(state, villager);
		return passed < MAX_TIER && villager(state, villager) >= VILLAGER_TIERS[passed + 1] ? passed + 1 : 0;
	}

	public static void passTrial(ErrandsData.PlayerState state, UUID villager, int tier) {
		state.trialsPassed.merge(villager, tier, Math::max);
	}

	public static void addVillage(ErrandsData.PlayerState state, Optional<GlobalPos> village, int delta) {
		village.ifPresent(v -> state.villageRep.merge(key(v), delta, (a, b) -> Math.max(-500, a + b)));
	}

	/**
	 * Adds villager reputation. Points stop at the threshold of the next trust trial until it is passed. Returns
	 * true when a new tier was reached. No vanilla gossip is written: prices come from {@link #priceFactor}.
	 */
	public static boolean addVillager(ErrandsData.PlayerState state, ServerPlayer player, UUID villagerId, Villager villager, int delta) {
		int before = villagerTier(state, villagerId);
		int passed = trialsPassed(state, villagerId);
		int old = villager(state, villagerId);
		int points = Math.max(-500, old + delta);
		if (delta > 0 && passed < MAX_TIER) points = Math.min(points, Math.max(old, VILLAGER_TIERS[passed + 1]));
		state.villagerRep.put(villagerId, points);
		return villagerTier(state, villagerId) > before;
	}

	/** Trade discount with a villager (0..1): their tier and the village tier (TIERS.md section 5). */
	public static double discount(ErrandsData.PlayerState state, UUID villagerId, Optional<GlobalPos> village) {
		ErrandsConfig c = ErrandsConfig.INSTANCE;
		if (!c.tradeDiscounts) return 0;
		double d = Math.min(c.discountPerVillagerTier * villagerTier(state, villagerId) + c.discountPerVillageTier * villageTier(state, village),
			c.maxDiscount);
		if (Patron.is(state, village)) d = Math.max(d, c.patronDiscount);
		return Math.clamp(d, 0.0, 0.95);
	}

	/** Surcharge (0..max) for a player the villager or the village holds a grudge against. */
	public static double surcharge(ErrandsData.PlayerState state, UUID villagerId, Optional<GlobalPos> village) {
		ErrandsConfig c = ErrandsConfig.INSTANCE;
		if (!c.tradeDiscounts) return 0;
		int worst = Math.min(villager(state, villagerId), village(state, village));
		return worst >= 0 ? 0 : Math.min(c.maxSurcharge, -worst * c.surchargePerNegativePoint);
	}

	/** What a trade costs with this villager, as a share of the base price: 1 - discount + surcharge. */
	public static double priceFactor(ErrandsData.PlayerState state, UUID villagerId, Optional<GlobalPos> village) {
		return 1 - discount(state, villagerId, village) + surcharge(state, villagerId, village);
	}
}
