package net.valcheff.villageerrands;

import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Per-player errand state and reputation, saved in {@code <world>/data/villageerrands/errands.dat}. */
public final class ErrandsData extends SavedData {
	public static final SavedDataType<ErrandsData> TYPE = new SavedDataType<>(
		VillageErrands.id("errands"),
		ErrandsData::new,
		CompoundTag.CODEC.xmap(ErrandsData::load, ErrandsData::save),
		DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	/** Completed errands are remembered this many days, which is longer than any errand is offered. */
	private static final long KEEP_DONE_DAYS = 3;
	private static final int HISTORY_SIZE = 40;

	public static final class Active {
		public final Errand errand;
		/** Done count per objective; deliveries are counted from the inventory instead and stay 0 here. */
		public final int[] progress;
		public Active(Errand errand, int[] progress) {
			this.errand = errand;
			this.progress = progress.length == errand.objectives().size() ? progress : new int[errand.objectives().size()];
		}

		/** All objectives except deliveries are done. */
		public boolean actionsDone() {
			for (int i = 0; i < progress.length; i++) {
				Objective o = errand.objectives().get(i);
				if (!o.isDelivery() && progress[i] < o.needed()) return false;
			}
			return true;
		}
	}

	public static final class PlayerState {
		/** The player this state belongs to. */
		public UUID id;
		public final List<Active> active = new ArrayList<>();
		/** errand id -> day it was completed */
		public final Map<String, Long> done = new HashMap<>();
		public final Map<UUID, Integer> villagerRep = new HashMap<>();
		/** Highest trust trial passed with each villager; the villager tier never goes above it. */
		public final Map<UUID, Integer> trialsPassed = new HashMap<>();
		/**
		 * Villagers that still carry vanilla gossip written by versions before 0.2 (which turned errands into
		 * gossip), with the major positive gossip to take back. Cleaned the next time the villager's prices are
		 * worked out for this player.
		 */
		public final Map<UUID, Integer> gossipToClean = new HashMap<>();
		public final Map<String, Integer> villageRep = new HashMap<>();
		/** Villages (keys of their centers) shown in the on-screen tracker. */
		public final Set<String> trackedVillages = new LinkedHashSet<>();
		/** Errands shown in the on-screen tracker (ids of accepted errands). */
		public final Set<String> tracked = new LinkedHashSet<>();
		/** Errands finished in total. */
		public int completed;
		/** Finished errands, newest last, for the chronicle book. */
		public final List<Errand> history = new ArrayList<>();
		public final List<Long> historyDays = new ArrayList<>();
		/** Villages the player has found (keys of their centers) -> day found. */
		public final Map<String, Long> discovered = new HashMap<>();
		/** Emeralds waiting in each village's treasury, and the day it was last settled. */
		public final Map<String, Double> treasury = new HashMap<>();
		public final Map<String, Long> paidUntil = new HashMap<>();
		/** Fame across all villages (titles in chat and TAB). */
		public int fame;
		/** One-off things done, for the advancements: "top_reward", "trial". */
		public final Set<String> flags = new LinkedHashSet<>();
		/** Profession -> names of the tasks done for it (the profession collection). */
		public final Map<String, Set<String>> collection = new HashMap<>();
		/** Profession -> contracts completed. */
		public final Map<String, Integer> contracts = new HashMap<>();
		/** Professions whose legendary item the player has received. */
		public final Set<String> legends = new LinkedHashSet<>();
		/** Villages (keys) where the player became patron. */
		public final Set<String> patronOf = new LinkedHashSet<>();
		/** Emeralds sent from the treasury so far, weekly orders helped to finish. */
		public int treasurySent;
		public int weeklyDone;
		/** Emeralds owed to the player (weekly order shares while offline, or a full ender chest). */
		public int mail;

		public Active find(String errandId) {
			for (Active a : active) if (a.errand.id().equals(errandId)) return a;
			return null;
		}

		public boolean isTracked(String errandId) {
			return tracked.contains(errandId);
		}

		public void remember(Errand errand, long day) {
			history.add(errand);
			historyDays.add(day);
			while (history.size() > HISTORY_SIZE) {
				history.removeFirst();
				historyDays.removeFirst();
			}
		}
	}

	private final Map<UUID, PlayerState> players = new HashMap<>();

	/** A village's weekly order, shared by every player: what is done and who did how much. */
	public static final class Weekly {
		public long week;
		public int progress;
		public final Map<UUID, Integer> contrib = new HashMap<>();
		public boolean settled;
	}

	/** Village key -> its weekly order (the current week, or the last one until it is settled). */
	public final Map<String, Weekly> weekly = new HashMap<>();

	public Map<UUID, PlayerState> players() {
		return players;
	}

	public static ErrandsData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public static String villageKey(GlobalPos pos) {
		return pos.dimension().identifier() + "|" + pos.pos().getX() + "|" + pos.pos().getY() + "|" + pos.pos().getZ();
	}

	/** The center written by {@link #villageKey}, or null when the key is not one. */
	public static GlobalPos parseVillageKey(String key) {
		String[] p = key.split("\\|");
		if (p.length != 4) return null;
		try {
			return GlobalPos.of(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				net.minecraft.resources.Identifier.parse(p[0])),
				new net.minecraft.core.BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
		} catch (RuntimeException e) {
			return null;
		}
	}

	public PlayerState player(UUID id) {
		return players.computeIfAbsent(id, k -> {
			PlayerState state = new PlayerState();
			state.id = k;
			return state;
		});
	}

	/**
	 * A villager became a new entity (zombified, or cured back): it keeps every player's standing, trials and
	 * accepted errands under its new id.
	 */
	public void renameVillager(UUID from, UUID to) {
		for (PlayerState p : players.values()) {
			Integer rep = p.villagerRep.remove(from);
			if (rep != null) p.villagerRep.merge(to, rep, Math::max);
			Integer gossip = p.gossipToClean.remove(from);
			if (gossip != null) p.gossipToClean.merge(to, gossip, Math::max);
			Integer trials = p.trialsPassed.remove(from);
			if (trials != null) p.trialsPassed.merge(to, trials, Math::max);
			for (int i = 0; i < p.active.size(); i++) {
				Active a = p.active.get(i);
				if (!a.errand.villager().equals(from)) continue;
				Errand e = a.errand;
				p.active.set(i, new Active(new Errand(e.id(), to, e.profession(), e.villagerType(), e.villagerLevel(), e.tier(),
					e.village(), e.title(), e.objectives(), e.value(), e.emeralds(), e.xp(), e.item(), e.reputation()), a.progress));
			}
		}
		setDirty();
	}

	/** Two villages became one: each player keeps the better of the two reputations. */
	public void mergeVillages(GlobalPos gone, GlobalPos keep) {
		String goneKey = villageKey(gone);
		String keepKey = villageKey(keep);
		for (PlayerState p : players.values()) {
			Integer old = p.villageRep.remove(goneKey);
			if (old != null) p.villageRep.merge(keepKey, old, Math::max);
			if (p.trackedVillages.remove(goneKey)) p.trackedVillages.add(keepKey);
			Long found = p.discovered.remove(goneKey);
			if (found != null) p.discovered.merge(keepKey, found, Math::min);
			Double waiting = p.treasury.remove(goneKey);
			if (waiting != null) p.treasury.merge(keepKey, waiting, Double::sum);
			Long paid = p.paidUntil.remove(goneKey);
			if (paid != null) p.paidUntil.merge(keepKey, paid, Math::max);
			if (p.patronOf.remove(goneKey)) p.patronOf.add(keepKey);
		}
		weekly.remove(goneKey);
		setDirty();
	}

	public void prune(long today) {
		for (PlayerState p : players.values()) p.done.values().removeIf(day -> day < today - KEEP_DONE_DAYS);
	}

	private static Active loadActive(CompoundTag a) {
		Errand errand = Errand.load(a.getCompoundOrEmpty("errand"));
		int[] progress = a.getIntArray("progress").orElse(null);
		if (progress == null) {
			// Saved with a single counter (kill errands only).
			progress = new int[errand.objectives().size()];
			int old = a.getIntOr("progress", 0);
			for (int i = 0; i < progress.length; i++) if (!errand.objectives().get(i).isDelivery()) progress[i] = old;
		}
		return new Active(errand, progress);
	}

	private static ErrandsData load(CompoundTag tag) {
		ErrandsData data = new ErrandsData();
		CompoundTag players = tag.getCompoundOrEmpty("players");
		for (String key : players.keySet()) {
			UUID id;
			try {
				id = UUID.fromString(key);
			} catch (IllegalArgumentException e) {
				continue;
			}
			CompoundTag p = players.getCompoundOrEmpty(key);
			PlayerState state = data.player(id);
			p.getListOrEmpty("active").compoundStream().forEach(a ->
				state.active.add(loadActive(a)));
			CompoundTag done = p.getCompoundOrEmpty("done");
			for (String k : done.keySet()) state.done.put(k, done.getLongOr(k, 0L));
			CompoundTag vr = p.getCompoundOrEmpty("villager_rep");
			for (String k : vr.keySet()) state.villagerRep.put(UUID.fromString(k), vr.getIntOr(k, 0));
			if (!p.contains("gossip_v")) {
				// Saved before 0.2: every villager the player helped carries our gossip (5 major per tier rewarded).
				CompoundTag vt = p.getCompoundOrEmpty("villager_tier");
				state.villagerRep.forEach((k, v) -> {
					if (v > 0) state.gossipToClean.put(k, 5 * vt.getIntOr(k.toString(), 0));
				});
			} else {
				CompoundTag gc = p.getCompoundOrEmpty("gossip_clean");
				for (String k : gc.keySet()) state.gossipToClean.put(UUID.fromString(k), gc.getIntOr(k, 0));
			}
			CompoundTag tp = p.getCompoundOrEmpty("trials");
			for (String k : tp.keySet()) state.trialsPassed.put(UUID.fromString(k), tp.getIntOr(k, 0));
			if (!p.contains("trials_v")) {
				// Saved before trust trials: the tiers the player had are kept as passed.
				state.villagerRep.forEach((k, v) -> state.trialsPassed.put(k, Reputation.tier(v, Reputation.VILLAGER_TIERS)));
			}
			CompoundTag vg = p.getCompoundOrEmpty("village_rep");
			for (String k : vg.keySet()) state.villageRep.put(k, vg.getIntOr(k, 0));
			state.completed = p.getIntOr("completed", 0);
			p.getListOrEmpty("tracked").forEach(t -> t.asString().ifPresent(state.tracked::add));
			p.getListOrEmpty("tracked_villages").forEach(t -> t.asString().ifPresent(state.trackedVillages::add));
			state.tracked.retainAll(state.active.stream().map(a -> a.errand.id()).toList());
			CompoundTag found = p.getCompoundOrEmpty("discovered");
			for (String k : found.keySet()) state.discovered.put(k, found.getLongOr(k, 0L));
			CompoundTag treasury = p.getCompoundOrEmpty("treasury");
			for (String k : treasury.keySet()) state.treasury.put(k, treasury.getDoubleOr(k, 0));
			CompoundTag paid = p.getCompoundOrEmpty("paid_until");
			for (String k : paid.keySet()) state.paidUntil.put(k, paid.getLongOr(k, 0L));
			state.fame = p.getIntOr("fame", 0);
			p.getListOrEmpty("flags").forEach(t -> t.asString().ifPresent(state.flags::add));
			CompoundTag col = p.getCompoundOrEmpty("collection");
			for (String k : col.keySet()) {
				Set<String> names = new LinkedHashSet<>();
				col.getListOrEmpty(k).forEach(t -> t.asString().ifPresent(names::add));
				state.collection.put(k, names);
			}
			CompoundTag con = p.getCompoundOrEmpty("contracts");
			for (String k : con.keySet()) state.contracts.put(k, con.getIntOr(k, 0));
			p.getListOrEmpty("legends").forEach(t -> t.asString().ifPresent(state.legends::add));
			p.getListOrEmpty("patron_of").forEach(t -> t.asString().ifPresent(state.patronOf::add));
			state.treasurySent = p.getIntOr("treasury_sent", 0);
			state.weeklyDone = p.getIntOr("weekly_done", 0);
			state.mail = p.getIntOr("mail", 0);
			p.getListOrEmpty("history").compoundStream().forEach(h -> {
				state.history.add(Errand.load(h.getCompoundOrEmpty("errand")));
				state.historyDays.add(h.getLongOr("day", 0L));
			});
		}
		CompoundTag weekly = tag.getCompoundOrEmpty("weekly");
		for (String key : weekly.keySet()) {
			CompoundTag w = weekly.getCompoundOrEmpty(key);
			Weekly order = new Weekly();
			order.week = w.getLongOr("week", 0L);
			order.progress = w.getIntOr("progress", 0);
			order.settled = w.getBooleanOr("settled", false);
			CompoundTag c = w.getCompoundOrEmpty("contrib");
			for (String k : c.keySet()) {
				try {
					order.contrib.put(UUID.fromString(k), c.getIntOr(k, 0));
				} catch (IllegalArgumentException ignored) {
				}
			}
			data.weekly.put(key, order);
		}
		return data;
	}

	private CompoundTag save() {
		CompoundTag root = new CompoundTag();
		CompoundTag all = new CompoundTag();
		for (Map.Entry<UUID, PlayerState> e : players.entrySet()) {
			PlayerState state = e.getValue();
			CompoundTag p = new CompoundTag();
			ListTag active = new ListTag();
			for (Active a : state.active) {
				CompoundTag t = new CompoundTag();
				t.put("errand", a.errand.save());
				t.putIntArray("progress", a.progress);
				active.add(t);
			}
			p.put("active", active);
			CompoundTag done = new CompoundTag();
			state.done.forEach(done::putLong);
			p.put("done", done);
			CompoundTag vr = new CompoundTag();
			state.villagerRep.forEach((k, v) -> vr.putInt(k.toString(), v));
			p.put("villager_rep", vr);
			CompoundTag gc = new CompoundTag();
			state.gossipToClean.forEach((k, v) -> gc.putInt(k.toString(), v));
			p.put("gossip_clean", gc);
			p.putInt("gossip_v", 1);
			CompoundTag tp = new CompoundTag();
			state.trialsPassed.forEach((k, v) -> tp.putInt(k.toString(), v));
			p.put("trials", tp);
			p.putInt("trials_v", 1);
			CompoundTag vg = new CompoundTag();
			state.villageRep.forEach(vg::putInt);
			p.put("village_rep", vg);
			ListTag tracked = new ListTag();
			state.tracked.forEach(id -> tracked.add(StringTag.valueOf(id)));
			p.put("tracked", tracked);
			ListTag trackedVillages = new ListTag();
			state.trackedVillages.forEach(k -> trackedVillages.add(StringTag.valueOf(k)));
			p.put("tracked_villages", trackedVillages);
			p.putInt("completed", state.completed);
			CompoundTag found = new CompoundTag();
			state.discovered.forEach(found::putLong);
			p.put("discovered", found);
			CompoundTag treasury = new CompoundTag();
			state.treasury.forEach(treasury::putDouble);
			p.put("treasury", treasury);
			CompoundTag paid = new CompoundTag();
			state.paidUntil.forEach(paid::putLong);
			p.put("paid_until", paid);
			ListTag history = new ListTag();
			for (int i = 0; i < state.history.size(); i++) {
				CompoundTag h = new CompoundTag();
				h.put("errand", state.history.get(i).save());
				h.putLong("day", state.historyDays.get(i));
				history.add(h);
			}
			p.put("history", history);
			p.putInt("fame", state.fame);
			ListTag flags = new ListTag();
			state.flags.forEach(k -> flags.add(StringTag.valueOf(k)));
			p.put("flags", flags);
			CompoundTag col = new CompoundTag();
			state.collection.forEach((k, names) -> {
				ListTag list = new ListTag();
				names.forEach(n -> list.add(StringTag.valueOf(n)));
				col.put(k, list);
			});
			p.put("collection", col);
			CompoundTag con = new CompoundTag();
			state.contracts.forEach(con::putInt);
			p.put("contracts", con);
			ListTag legends = new ListTag();
			state.legends.forEach(k -> legends.add(StringTag.valueOf(k)));
			p.put("legends", legends);
			ListTag patron = new ListTag();
			state.patronOf.forEach(k -> patron.add(StringTag.valueOf(k)));
			p.put("patron_of", patron);
			p.putInt("treasury_sent", state.treasurySent);
			p.putInt("weekly_done", state.weeklyDone);
			p.putInt("mail", state.mail);
			all.put(e.getKey().toString(), p);
		}
		root.put("players", all);
		CompoundTag weeklyTag = new CompoundTag();
		weekly.forEach((key, order) -> {
			CompoundTag w = new CompoundTag();
			w.putLong("week", order.week);
			w.putInt("progress", order.progress);
			w.putBoolean("settled", order.settled);
			CompoundTag c = new CompoundTag();
			order.contrib.forEach((k, v) -> c.putInt(k.toString(), v));
			w.put("contrib", c);
			weeklyTag.put(key, w);
		});
		root.put("weekly", weeklyTag);
		return root;
	}
}
