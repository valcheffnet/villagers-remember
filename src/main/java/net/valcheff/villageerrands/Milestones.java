package net.valcheff.villageerrands;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * The mod's advancement tree (TIERS.md section 7.5). Every advancement has one "impossible" criterion and is
 * granted from here when its condition holds, so the tree also fills in for players who did the work before it
 * existed (checked when they join). The JSON files come from {@code tools/gen_advancements.py}; the ids here must
 * match them.
 */
public final class Milestones {
	/** Professions with a collection advancement (every profession that gives errands). */
	public static final java.util.List<String> COLLECTION = java.util.List.of("armorer", "butcher", "cartographer", "cleric",
		"farmer", "fisherman", "fletcher", "leatherworker", "librarian", "mason", "nitwit", "shepherd", "toolsmith", "weaponsmith");
	private static final Map<String, BiPredicate<ServerPlayer, ErrandsData.PlayerState>> ALL = new LinkedHashMap<>();

	static {
		ALL.put("root", (p, s) -> s.completed > 0 || !s.active.isEmpty() || !s.discovered.isEmpty());
		ALL.put("errands/first", (p, s) -> s.completed >= 1);
		ALL.put("errands/ten", (p, s) -> s.completed >= 10);
		ALL.put("errands/fifty", (p, s) -> s.completed >= 50);
		ALL.put("errands/two_hundred", (p, s) -> s.completed >= 200);
		ALL.put("errands/order", (p, s) -> anyTask(s, "order."));
		ALL.put("errands/reforge", (p, s) -> anyTask(s, "reforge"));
		ALL.put("errands/top", (p, s) -> s.flags.contains("top_reward"));
		ALL.put("trust/acquaintance", (p, s) -> villagersAt(s, 1) >= 1);
		ALL.put("trust/trial", (p, s) -> s.flags.contains("trial"));
		ALL.put("trust/best_friend", (p, s) -> villagersAt(s, Reputation.MAX_TIER) >= 1);
		ALL.put("trust/close_circle", (p, s) -> villagersAt(s, Reputation.MAX_TIER) >= 5);
		ALL.put("contracts/first", (p, s) -> s.contracts.values().stream().mapToInt(Integer::intValue).sum() >= 1);
		ALL.put("contracts/legend", (p, s) -> !s.legends.isEmpty());
		ALL.put("contracts/all_legends", (p, s) -> s.legends.containsAll(Legends.professions()));
		ALL.put("villages/known", (p, s) -> bestVillageTier(s) >= 1);
		ALL.put("villages/respected", (p, s) -> bestVillageTier(s) >= Reputation.RESPECTED_TIER);
		ALL.put("villages/hero", (p, s) -> bestVillageTier(s) >= Reputation.MAX_TIER);
		ALL.put("villages/patron", (p, s) -> !s.patronOf.isEmpty());
		ALL.put("villages/weekly", (p, s) -> s.weeklyDone >= 1);
		ALL.put("explore/first", (p, s) -> s.discovered.size() >= 1);
		ALL.put("explore/five", (p, s) -> s.discovered.size() >= 5);
		ALL.put("explore/fifteen", (p, s) -> s.discovered.size() >= 15);
		ALL.put("explore/thirty", (p, s) -> s.discovered.size() >= 30);
		ALL.put("treasury/first", (p, s) -> s.treasurySent >= 1);
		ALL.put("treasury/income", (p, s) -> Treasury.entries(p, s).stream().mapToDouble(Treasury.Entry::perDay).sum() >= 10);
		ALL.put("treasury/network", (p, s) -> Treasury.friendlyVillages(p.level().getServer(), s) >= 5);
		ALL.put("fame/helper", (p, s) -> Fame.title(s) >= 1);
		ALL.put("fame/benefactor", (p, s) -> Fame.title(s) >= 2);
		ALL.put("fame/legend", (p, s) -> Fame.title(s) >= 3);
		for (String prof : COLLECTION) ALL.put("collection/" + prof, (p, s) -> collected(s, prof));
		ALL.put("collection/all", (p, s) -> {
			for (String prof : COLLECTION) if (!collected(s, prof)) return false;
			return true;
		});
	}

	/** Grants every advancement whose condition now holds. Cheap enough to call after any change. */
	public static void check(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) return;
		ErrandsData.PlayerState state = ErrandService.state(player);
		for (Map.Entry<String, BiPredicate<ServerPlayer, ErrandsData.PlayerState>> e : ALL.entrySet()) {
			AdvancementHolder holder = server.getAdvancements().get(VillageErrands.id(e.getKey()));
			if (holder == null || player.getAdvancements().getOrStartProgress(holder).isDone()) continue;
			boolean met;
			try {
				met = e.getValue().test(player, state);
			} catch (RuntimeException ex) {
				VillageErrands.LOG.debug("Advancement check {} failed", e.getKey(), ex);
				continue;
			}
			if (met) player.getAdvancements().award(holder, "done");
		}
	}

	public static Set<String> ids() {
		return ALL.keySet();
	}

	/** Tasks done for a profession, out of all of its tasks. */
	public static int[] collection(ErrandsData.PlayerState state, String profession) {
		Set<String> names = ErrandGenerator.taskNames("minecraft:" + profession).keySet();
		Set<String> done = state.collection.getOrDefault(profession, Set.of());
		int n = 0;
		for (String name : names) if (done.contains(name)) n++;
		return new int[]{n, names.size()};
	}

	private static boolean collected(ErrandsData.PlayerState state, String profession) {
		int[] c = collection(state, profession);
		return c[1] > 0 && c[0] >= c[1];
	}

	private static boolean anyTask(ErrandsData.PlayerState state, String prefix) {
		for (Set<String> names : state.collection.values()) for (String n : names) if (n.startsWith(prefix)) return true;
		return false;
	}

	private static int villagersAt(ErrandsData.PlayerState state, int tier) {
		int n = 0;
		for (java.util.UUID id : state.villagerRep.keySet()) if (Reputation.villagerTier(state, id) >= tier) n++;
		return n;
	}

	private static int bestVillageTier(ErrandsData.PlayerState state) {
		int best = 0;
		for (String key : state.villageRep.keySet()) {
			GlobalPos center = ErrandsData.parseVillageKey(key);
			if (center != null) best = Math.max(best, Reputation.villageTier(state, Optional.of(center)));
		}
		return best;
	}

	private Milestones() {
	}
}
