package net.valcheff.villagersremember;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Builds a villager's errands for a day. The result depends only on the villager (UUID, profession, level), the
 * day, the pack (the player's tier with the villager + 1) and the pools, so it needs no storage and is the same for
 * every player at the same tier.
 */
public final class ErrandGenerator {
	private static final int MAX_SLOTS = 7;
	private static final Map<String, List<Errand>> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, List<Errand>> eldest) {
			return size() > 2048;
		}
	};

	public static void clearCache() {
		CACHE.clear();
	}

	public static long day(ServerLevel level) {
		return Math.floorDiv(level.getOverworldClockTime(), 24000L);
	}

	public static boolean givesErrands(Villager villager) {
		if (villager.isBaby()) return false;
		String profession = professionId(villager.getVillagerData());
		return ErrandPools.get(profession) != null;
	}

	public static String professionId(VillagerData data) {
		return data.profession().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:none");
	}

	public static String typeId(VillagerData data) {
		return data.type().unwrapKey().map(k -> k.identifier().toString()).orElse("minecraft:plains");
	}

	/** Who gives errands: a loaded villager or a remembered resident of a village. */
	public record Giver(UUID id, String profession, String type, int level, Optional<GlobalPos> village) {
		public static Giver of(Villager villager) {
			VillagerData data = villager.getVillagerData();
			return new Giver(villager.getUUID(), professionId(data), typeId(data), data.level(), ErrandGenerator.village(villager));
		}

		public static Giver of(VillageRegistry.Village village, VillageRegistry.Resident r) {
			return new Giver(r.id, r.profession, r.type, r.level, Optional.of(village.center));
		}

		public boolean givesErrands() {
			return ErrandPools.get(profession) != null;
		}
	}

	/** The village (its center bell) this villager lives in, if any. */
	public static Optional<GlobalPos> village(Villager villager) {
		MinecraftServer server = villager.level().getServer();
		if (server == null) return Optional.empty();
		VillageRegistry registry = VillageRegistry.get(server);
		Optional<VillageRegistry.Village> village = registry.villageOf(villager.getUUID());
		if (village.isEmpty() && villager.level() instanceof ServerLevel level) {
			village = registry.observe(server, villager, day(level));
		}
		return village.map(v -> v.center);
	}

	/** The pack a villager offers this player: their tier with the villager + 1, so 1..5. */
	public static int pack(ErrandsData.PlayerState state, UUID villager) {
		return Reputation.villagerTier(state, villager) + 1;
	}

	/** All errand slots this villager could offer the player today; callers show the first n. */
	public static List<Errand> offers(ServerLevel level, Giver giver, ErrandsData.PlayerState state) {
		return offers(level, giver, pack(state, giver.id()));
	}

	public static List<Errand> offers(ServerLevel level, Giver giver, int tier) {
		long day = day(level);
		String key = giver.id() + "/" + day + "/" + tier + "/" + giver.profession() + "/" + giver.level() + "/"
			+ giver.village().map(GlobalPos::toString).orElse("-");
		List<Errand> cached = CACHE.get(key);
		if (cached != null) return cached;
		List<Errand> out = generate(giver.id(), day, giver.profession(), giver.type(), giver.level(), giver.village(), tier);
		CACHE.put(key, out);
		return out;
	}

	public static int slotsFor(Villager villager, boolean respected) {
		return slotsFor(villager.getVillagerData().level(), respected);
	}

	public static int slotsFor(int villagerLevel, boolean respected) {
		int n = ErrandsConfig.INSTANCE.errandsFor(villagerLevel);
		if (respected && ErrandsConfig.INSTANCE.respectedBonusErrand && n > 0) n++;
		return Math.min(n, MAX_SLOTS);
	}

	/**
	 * What a villager shows the player today: the trust trial when one waits, then the day's errands of the
	 * player's pack, as many as the villager's level gives (and one more at Respected).
	 */
	public static List<Errand> shown(ServerLevel level, Giver giver, ErrandsData.PlayerState state) {
		// Gradual trust: villagers outside the player's circle offer nothing yet.
		if (!Trust.trusts(level.getServer(), state, giver)) return List.of();
		List<Errand> out = new ArrayList<>();
		trial(level, giver, state).ifPresent(out::add);
		contract(level, giver, state).ifPresent(out::add);
		List<Errand> offers = offers(level, giver, state).stream().filter(e -> !reforgeDone(state, e)).toList();
		int n = Math.min(offers.size(), slotsFor(giver.level(), Reputation.respected(state, giver.village())));
		out.addAll(offers.subList(0, n));
		return out;
	}

	/**
	 * The trust trial for the tier the player's points have reached with this villager: a task of the pack it
	 * unlocks, asking more and paying more, always with an item. Named "Trial of trust: Friend".
	 */
	public static Optional<Errand> trial(ServerLevel level, Giver giver, ErrandsData.PlayerState state) {
		int earn = Reputation.trialPending(state, giver.id());
		if (earn == 0) return Optional.empty();
		ErrandPools.Pool pool = ErrandPools.get(giver.profession());
		if (pool == null) return Optional.empty();
		int pack = Math.min(5, earn + 1);
		long day = day(level);
		// Not orders (fixed counts) nor reforge errands (their own reward).
		List<ErrandPools.Task> eligible = pool.tasks().stream()
			.filter(t -> pack >= t.minTier() && pack <= t.maxTier() && t.title().isEmpty() && t.reward().isEmpty()).toList();
		if (eligible.isEmpty()) eligible = pool.tasks().stream().filter(t -> pack >= t.minTier() && pack <= t.maxTier()).toList();
		if (eligible.isEmpty()) return Optional.empty();
		RandomSource random = RandomSource.create(seed(giver.id(), day, 10 + earn, 0));
		ErrandPools.Task task = pick(eligible, Set.of(), random);
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		Errand e = build(giver.id(), day, 0, giver.profession(), giver.type(), giver.level(), pack, giver.village(), task, pool,
			random, cfg.trialCountFactor, cfg.trialRewardMultiplier);
		String prof = ErrandText.path(giver.profession());
		Optional<Errand.ItemReward> item = Optional.empty();
		if (earn >= Reputation.MAX_TIER) {
			boolean hasTop = pool.rewards().stream().anyMatch(r -> r.kind() == ErrandPools.RewardKind.TOP);
			if (hasTop && !state.flags.contains(topFlag(prof))) {
				item = Optional.of(new Errand.ItemReward(ErrandPools.RewardKind.TOP, giver.profession(), 1, random.nextLong()));
			}
		} else if (!state.flags.contains(trialItemFlag(prof, earn))) {
			item = milestoneItem(pool, pack, random);
		}
		// The very first trial a player ever gets (user, 2026-10-14): an ender chest to have, bought or found is fine, and
		// kept. The village will leave a little something in it every day: it is where the treasury goes.
		List<Objective> objectives = earn == 1 && !state.flags.contains("trial")
			? List.of(new Objective(Objective.Type.HAVE, "minecraft:ender_chest", false, 1, 0))
			: e.objectives();
		return Optional.of(new Errand(giver.id() + "/" + day + "/trial" + earn, e.villager(), e.profession(), e.villagerType(),
			e.villagerLevel(), e.tier(), e.village(), Optional.of("trial." + earn), objectives, e.value(), e.emeralds(), e.xp(),
			item, e.reputation()));
	}

	/**
	 * A contract (TIERS.md section 7): once the player is a villager's Best friend, one big job a game week. Three
	 * different tasks of the top packs, at least two of them actions (hunt, fish, explore, cure, brew, breed, tame),
	 * together about {@code contractMinutes} of work. It pays emeralds, experience, reputation and fame; the third one
	 * for a profession also brings its legendary item.
	 */
	public static Optional<Errand> contract(ServerLevel level, Giver giver, ErrandsData.PlayerState state) {
		if (!ErrandsConfig.INSTANCE.contracts || Reputation.villagerTier(state, giver.id()) < Reputation.MAX_TIER) return Optional.empty();
		ErrandPools.Pool pool = ErrandPools.get(giver.profession());
		if (pool == null) return Optional.empty();
		long day = day(level);
		long week = week(day);
		List<ErrandPools.Task> plain = pool.tasks().stream().filter(t -> t.title().isEmpty() && t.reward().isEmpty()).toList();
		List<ErrandPools.Task> top = plain.stream().filter(t -> t.maxTier() >= 4).toList();
		if (top.stream().filter(ErrandGenerator::isAction).count() < 2) top = plain.stream().filter(t -> t.maxTier() >= 3).toList();
		if (top.isEmpty()) return Optional.empty();
		RandomSource random = RandomSource.create(seed(giver.id(), week, 20, 0));
		List<ErrandPools.Task> actions = top.stream().filter(ErrandGenerator::isAction).toList();
		Set<String> used = new java.util.HashSet<>();
		List<ErrandPools.Task> parts = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			List<ErrandPools.Task> from = i < 2 && !actions.isEmpty() ? actions : top;
			ErrandPools.Task task = pick(from, used, random);
			if (!used.add(task.key())) task = pick(top, used, random);
			if (used.add(task.key()) || !parts.contains(task)) parts.add(task);
		}
		parts = parts.stream().distinct().toList();
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		double part = cfg.contractMinutes / parts.size();
		List<Objective> objectives = new ArrayList<>();
		double value = 0;
		int emeralds = 0;
		int xp = 0;
		int reputation = 0;
		for (ErrandPools.Task task : parts) {
			double mid = Math.max(1, (task.min() + task.max()) / 2.0) * Math.max(task.value(), 0.001);
			double factor = Math.clamp(part / mid, 0.5, 64.0);
			Errand e = build(giver.id(), day, 0, giver.profession(), giver.type(), giver.level(), 5, giver.village(), task, pool,
				random, factor, cfg.contractRewardMultiplier);
			objectives.addAll(e.objectives());
			value += e.value();
			emeralds += e.emeralds();
			xp += e.xp();
			reputation = Math.max(reputation, e.reputation());
		}
		emeralds = Math.min(emeralds, cfg.contractMaxEmeralds);
		return Optional.of(new Errand(giver.id() + "/w" + week + "/contract", giver.id(), giver.profession(), giver.type(),
			giver.level(), 5, giver.village(), Optional.of("contract"), List.copyOf(objectives), value, emeralds, xp,
			Optional.empty(), reputation * 2));
	}

	/** Game weeks, for contracts. */
	public static long week(long day) {
		return Math.floorDiv(day, 7L);
	}

	/** A task the player has to go and do (not only make or bring): hunt, fish, explore, cure, brew, breed, tame... */
	private static boolean isAction(ErrandPools.Task task) {
		for (ErrandPools.ObjectiveTemplate o : task.objectives()) {
			switch (o.type()) {
				case DELIVER, CRAFT, SMELT, STATION, PLANT, BONEMEAL, TRADE -> {
				}
				default -> {
					return true;
				}
			}
		}
		return false;
	}

	public static boolean isContract(Errand errand) {
		return errand.title().map(t -> t.equals("contract")).orElse(false);
	}

	/**
	 * The name of the task an errand was made from (as {@link #taskNames} names it), for the profession collection;
	 * empty when the pools changed and no task matches.
	 */
	public static Optional<String> taskNameOf(Errand errand) {
		for (java.util.Map.Entry<String, ErrandPools.Task> e : taskNames(errand.profession()).entrySet()) {
			ErrandPools.Task t = e.getValue();
			if (errand.tier() < t.minTier() || errand.tier() > t.maxTier()) continue;
			boolean titled = errand.title().isPresent() && !isTrial(errand) && !isContract(errand);
			if (titled != t.title().isPresent()) continue;
			if (titled && !errand.title().get().equals(t.title().get())) continue;
			if (t.objectives().size() != errand.objectives().size()) continue;
			boolean same = true;
			for (int i = 0; i < t.objectives().size() && same; i++) {
				ErrandPools.ObjectiveTemplate o = t.objectives().get(i);
				Objective have = errand.objectives().get(i);
				same = o.type() == have.type() && o.target().equals(have.target()) && o.tag() == have.tag();
			}
			if (same) return Optional.of(e.getKey());
		}
		return Optional.empty();
	}

	/** Flags of the one-time trial rewards (PlayerState.flags). */
	public static String topFlag(String professionPath) {
		return "top:" + professionPath;
	}

	public static String trialItemFlag(String professionPath, int trialTier) {
		return "trialitem:" + professionPath + ":" + trialTier;
	}

	/** One of the profession's milestone items for this pack (never gear): what the first trial into it gives. */
	private static Optional<Errand.ItemReward> milestoneItem(ErrandPools.Pool pool, int pack, RandomSource random) {
		List<ErrandPools.Reward> options = pool.rewards().stream()
			.filter(r -> r.kind() != ErrandPools.RewardKind.TOP && r.minTier() <= pack && r.maxTier() >= pack).toList();
		if (options.isEmpty()) return Optional.empty();
		ErrandPools.Reward r = options.get(random.nextInt(options.size()));
		int amount = r.min() + (r.max() > r.min() ? random.nextInt(r.max() - r.min() + 1) : 0);
		return Optional.of(new Errand.ItemReward(r.kind(), r.id(), Math.max(1, amount), random.nextLong()));
	}

	/** Reforge errands are one-time: true when the player has already done this one. */
	public static boolean reforgeDone(ErrandsData.PlayerState state, Errand errand) {
		if (errand.item().isEmpty() || errand.item().get().kind() != ErrandPools.RewardKind.ENCHANT_MAX) return false;
		Optional<String> name = taskNameOf(errand);
		return name.isPresent() && name.get().startsWith("reforge")
			&& state.collection.getOrDefault(ErrandText.path(errand.profession()), Set.of()).contains(name.get());
	}

	public static boolean isTrial(Errand errand) {
		return errand.title().map(t -> t.startsWith("trial.")).orElse(false);
	}

	/** The errands of one pack: as many slots as the pack has different tasks, up to MAX_SLOTS. */
	public static List<Errand> generate(UUID villager, long day, String profession, String type, int level,
	                                     Optional<GlobalPos> village, int tier) {
		ErrandPools.Pool pool = ErrandPools.get(profession);
		List<Errand> out = new ArrayList<>();
		if (pool == null) return out;
		int pack = Math.clamp(tier, 1, 5);
		List<ErrandPools.Task> eligible = pool.tasks().stream()
			.filter(t -> pack >= t.minTier() && pack <= t.maxTier()).toList();
		if (eligible.isEmpty()) return out;
		Set<String> used = new HashSet<>();
		for (int slot = 0; slot < Math.min(MAX_SLOTS, eligible.size()); slot++) {
			RandomSource random = RandomSource.create(seed(villager, day, pack, slot));
			ErrandPools.Task task = pick(eligible, used, random);
			used.add(task.key());
			out.add(build(villager, day, slot, profession, type, level, pack, village, task, pool, random));
		}
		return out;
	}

	private static long seed(UUID villager, long day, int tier, int slot) {
		long h = villager.getMostSignificantBits() * 0x9E3779B97F4A7C15L ^ villager.getLeastSignificantBits();
		h ^= (day + 1) * 0xC2B2AE3D27D4EB4FL;
		h ^= (slot + 7L) * 0x165667B19E3779F9L;
		h ^= tier * 0x27D4EB2F165667C5L;
		return h ^ (h >>> 31);
	}

	private static ErrandPools.Task pick(List<ErrandPools.Task> tasks, Set<String> used, RandomSource random) {
		List<ErrandPools.Task> fresh = tasks.stream().filter(t -> !used.contains(t.key())).toList();
		List<ErrandPools.Task> from = fresh.isEmpty() ? tasks : fresh;
		int total = from.stream().mapToInt(ErrandPools.Task::weight).sum();
		int roll = random.nextInt(total);
		for (ErrandPools.Task t : from) {
			roll -= t.weight();
			if (roll < 0) return t;
		}
		return from.getLast();
	}

	/**
	 * One errand of a chosen task from this villager, as it would be offered in the task's first pack; for the
	 * operator command that hands out errands. The id never matches a daily offer.
	 */
	public static Errand forTask(Giver giver, long day, ErrandPools.Task task) {
		ErrandPools.Pool pool = ErrandPools.get(giver.profession());
		int tier = Math.clamp(task.minTier(), 1, 5);
		long stamp = System.nanoTime();
		RandomSource random = RandomSource.create(seed(giver.id(), day, tier, 0) ^ stamp);
		Errand e = build(giver.id(), day, 0, giver.profession(), giver.type(), giver.level(), tier, giver.village(), task, pool, random);
		return new Errand(giver.id() + "/" + day + "/t" + tier + "/given" + Long.toHexString(stamp), e.villager(), e.profession(),
			e.villagerType(), e.villagerLevel(), e.tier(), e.village(), e.title(), e.objectives(), e.value(), e.emeralds(), e.xp(),
			e.item(), e.reputation());
	}

	/**
	 * Short names for the tasks of a profession, for commands: "reforge.iron_sword", "order.winter_stock",
	 * "craft.bread", "kill.zombie"; a name used twice gets the tier added, ".t4".
	 */
	public static java.util.Map<String, ErrandPools.Task> taskNames(String profession) {
		ErrandPools.Pool pool = ErrandPools.get(profession);
		java.util.Map<String, ErrandPools.Task> out = new LinkedHashMap<>();
		if (pool == null) return out;
		java.util.Map<String, Integer> seen = new java.util.HashMap<>();
		List<String> names = new ArrayList<>();
		for (ErrandPools.Task t : pool.tasks()) {
			String name = taskName(t);
			names.add(name);
			seen.merge(name, 1, Integer::sum);
		}
		for (int i = 0; i < pool.tasks().size(); i++) {
			ErrandPools.Task t = pool.tasks().get(i);
			String name = names.get(i);
			if (seen.get(name) > 1) name = name + ".t" + t.minTier();
			while (out.containsKey(name)) name = name + "_";
			out.put(name, t);
		}
		return out;
	}

	private static String taskName(ErrandPools.Task t) {
		if (t.title().isPresent()) return t.title().get();
		ErrandPools.ObjectiveTemplate main = t.objectives().stream().filter(o -> o.type() != Objective.Type.DELIVER)
			.findFirst().orElse(t.objectives().getFirst());
		String target = main.target().isEmpty() ? "" : "." + ErrandText.path(main.target()).replace('/', '_');
		if (t.reward().isPresent() && t.reward().get().id().equals(main.target())) return "reforge" + target;
		if (main.type() == Objective.Type.DELIVER || t.objectives().size() == 1) return main.type().key() + target;
		// "kill.cow" alone does not say that leather is brought; name it by what is brought when that differs.
		ErrandPools.ObjectiveTemplate bring = t.objectives().stream().filter(o -> o.type() == Objective.Type.DELIVER)
			.findFirst().orElse(main);
		return bring.target().equals(main.target()) ? main.type().key() + target
			: main.type().key() + target + "." + ErrandText.path(bring.target()).replace('/', '_');
	}

	/** Reputation for one errand, by pack: 2-3, 3-4, 4-5, 5-6, 6-8 (TIERS.md section 1). */
	private static final int[][] REPUTATION = {{2, 3}, {3, 4}, {4, 5}, {5, 6}, {6, 8}};

	private static Errand build(UUID villager, long day, int slot, String profession, String type, int level, int tier,
	                            Optional<GlobalPos> village, ErrandPools.Task task, ErrandPools.Pool pool, RandomSource random) {
		return build(villager, day, slot, profession, type, level, tier, village, task, pool, random, 1.0, 1.0);
	}

	/** {@code countFactor} asks for more of everything, {@code rewardFactor} pays more and always includes an item. */
	private static Errand build(UUID villager, long day, int slot, String profession, String type, int level, int tier,
	                            Optional<GlobalPos> village, ErrandPools.Task task, ErrandPools.Pool pool, RandomSource random,
	                            double countFactor, double rewardFactor) {
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		int count = task.min() + (task.max() > task.min() ? random.nextInt(task.max() - task.min() + 1) : 0);
		// Never ask for more than four stacks of an item (16 of an item that does not stack).
		for (ErrandPools.ObjectiveTemplate t : task.objectives()) {
			if (t.type().target != Objective.Target.ITEM || t.tag() || t.target().isEmpty() || t.fixedMin() > 0) continue;
			count = Math.min(count, (int) Math.floor(itemCap(t.target()) / Math.max(t.factor(), 0.01)));
		}
		count = Math.max(1, count);
		List<Objective> objectives = new ArrayList<>();
		for (ErrandPools.ObjectiveTemplate t : task.objectives()) {
			int n = t.fixedMin() > 0
				? t.fixedMin() + (t.fixedMax() > t.fixedMin() ? random.nextInt(t.fixedMax() - t.fixedMin() + 1) : 0)
				: Math.max(1, (int) Math.round(count * t.factor()));
			n = Math.max(1, (int) Math.round(n * countFactor));
			n = Math.min(n, maxCount(t));
			// A place is visited once, however big the job.
			if (t.type() == Objective.Type.VISIT_STRUCTURE || t.type() == Objective.Type.VISIT_BIOME) n = 1;
			objectives.add(new Objective(t.type(), t.target(), t.tag(), n, t.minLevel()));
		}

		// value = minutes of work (tools/gen_errands.py); the pay per minute grows with the pack.
		double value = count * task.value() * countFactor;
		double budget = value * cfg.tierEmeraldsPerMinute[tier - 1] * rewardFactor;
		int[] rep = REPUTATION[tier - 1];
		int reputation = rep[0] + random.nextInt(rep[1] - rep[0] + 1);
		String id = villager + "/" + day + "/t" + tier + "/" + slot;

		if (task.reward().isPresent()) {
			// Reforge (once per player): the piece itself is the pay, with a little experience on top.
			ErrandPools.Reward r = task.reward().get();
			int amount = r.min() + (r.max() > r.min() ? random.nextInt(r.max() - r.min() + 1) : 0);
			Optional<Errand.ItemReward> item = Optional.of(new Errand.ItemReward(r.kind(), r.id(), amount, random.nextLong()));
			return new Errand(id, villager, profession, type, level, tier, village, task.title(),
				List.copyOf(objectives), value, 0, (int) Math.max(1, Math.round(budget * 0.25 * cfg.xpPerEmerald)), item, reputation);
		}
		// Repeatable errands pay emeralds, experience and reputation only (user, 2026-10-13).
		Optional<Errand.ItemReward> item = Optional.empty();
		double rest = budget;
		int emeralds;
		int xp;
		if (tier == 1) {
			// The first pack pays one kind: emeralds or experience.
			if (rest >= 1 && random.nextBoolean()) {
				emeralds = (int) Math.max(1, Math.round(rest));
				xp = 0;
			} else {
				emeralds = 0;
				xp = (int) Math.max(1, Math.round(rest * cfg.xpPerEmerald));
			}
		} else {
			double share = 0.4 + random.nextDouble() * 0.3;
			emeralds = (int) Math.round(rest * share);
			xp = (int) Math.max(1, Math.round(rest * (1 - share) * cfg.xpPerEmerald));
		}
		emeralds = Math.min(emeralds, cfg.maxEmeraldsPerErrand);
		// Never less than selling the goods to a villager: the emeralds beat their market value by the premium.
		int market = (int) Math.ceil(count * task.market() * countFactor * cfg.marketPremium * rewardFactor);
		if (emeralds < market) {
			xp = (int) Math.max(tier == 1 ? 0 : 1, Math.round((budget - market) * cfg.xpPerEmerald));
			emeralds = market;
		}
		return new Errand(id, villager, profession, type, level, tier, village, task.title(), List.copyOf(objectives),
			value, emeralds, xp, item, reputation);
	}

	/**
	 * The most an objective may ask for: four stacks of an item, 16 of an item that does not stack, 256 of a tag of
	 * items or of blocks, 128 of anything else (kills, breeding...).
	 */
	private static int maxCount(ErrandPools.ObjectiveTemplate t) {
		if (t.type().target == Objective.Target.BLOCK) return 256;
		if (t.type().target != Objective.Target.ITEM) return 128;
		if (t.tag() || t.target().isEmpty()) return 256;
		return itemCap(t.target());
	}

	private static int itemCap(String id) {
		int stack = BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.parse(id))
			.map(i -> i.getDefaultMaxStackSize()).orElse(64);
		return stack == 1 ? 16 : stack * 4;
	}

	private record ItemChoice(Errand.ItemReward reward, double value) {
	}

	private static Optional<ItemChoice> chooseItem(ErrandPools.Pool pool, int tier, double portion, RandomSource random) {
		List<ItemChoice> options = new ArrayList<>();
		long seed = random.nextLong();
		for (ErrandPools.Reward r : pool.rewards()) {
			if (r.minTier() > tier || r.maxTier() < tier) continue;
			switch (r.kind()) {
				case ITEM -> {
					int amount = (int) Math.clamp((long) Math.floor(portion / r.value()), r.min(), r.max());
					double v = amount * r.value();
					if (v <= portion * 1.3) options.add(new ItemChoice(new Errand.ItemReward(r.kind(), r.id(), amount, seed), v));
				}
				case ENCHANT, ENCHANT_MAX -> {
					int levels = (int) Math.clamp((long) Math.floor(portion / r.value()), r.min(), r.max());
					double v = levels * r.value();
					if (v <= portion * 1.3) options.add(new ItemChoice(new Errand.ItemReward(r.kind(), r.id(), levels, seed), v));
				}
				case LOOT -> {
					if (r.value() <= portion * 1.3) options.add(new ItemChoice(new Errand.ItemReward(r.kind(), r.id(), 1, seed), r.value()));
				}
				case TOP -> {
				}
			}
		}
		if (options.isEmpty()) return Optional.empty();
		return Optional.of(options.get(random.nextInt(options.size())));
	}
}
