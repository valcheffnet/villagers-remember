package net.valcheff.villagersremember;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The villages of the world and who lives in them.
 *
 * <p>A village is a set of bells. Its area is everything within {@link ErrandsConfig#villageRadius} of one of its
 * bells, plus a small area around each resident's bed and workplace. A bell placed in that area joins the village;
 * a bell that touches two villages merges them into one (the older one keeps its name and center).
 *
 * <p>Residents are remembered even while their chunks are unloaded. A villager joins the village of the bell it
 * gathers at (or, without a bell, the village its bed or workplace is in) and only moves to another village after
 * gathering there for {@link #MOVE_AFTER_DAYS} days in a row.
 */
public final class VillageRegistry extends SavedData {
	public static final SavedDataType<VillageRegistry> TYPE = new SavedDataType<>(
		VillagersRemember.id("villages"),
		VillageRegistry::new,
		CompoundTag.CODEC.xmap(VillageRegistry::load, VillageRegistry::save),
		DataFixTypes.SAVED_DATA_COMMAND_STORAGE);

	public static final int MOVE_AFTER_DAYS = 3;
	private static final int ANCHOR_RADIUS = 16;
	private static final int SCAN_INTERVAL = 100;

	public static final class Resident {
		public final UUID id;
		public String profession = "minecraft:none";
		public String type = "minecraft:plains";
		public int level = 1;
		public boolean baby;
		/** Bed or workplace (or last position when it has neither), for the village area and the residents list. */
		public long anchor;
		public long lastSeenDay;
		/** Another village it has been gathering at, and since which day; -1 when none. */
		int pendingVillage = -1;
		long pendingSince;

		Resident(UUID id) {
			this.id = id;
		}

		/** Counts for the village: has a trade and is grown up. */
		public boolean working() {
			return !baby && !profession.equals("minecraft:none") && !profession.equals("minecraft:nitwit");
		}
	}

	public static final class Village {
		public final int id;
		public final GlobalPos center;
		public String name;
		/** The village's patron (one player, see {@link Patron}) and their name for when they are offline. */
		public UUID patron;
		public String patronName;
		public final Set<Long> bells = new LinkedHashSet<>();
		public final Map<UUID, Resident> residents = new LinkedHashMap<>();

		Village(int id, GlobalPos center) {
			this.id = id;
			this.center = center;
			bells.add(center.pos().asLong());
		}

		/** Inside the village area: near one of its bells, or near a resident's bed or workplace. */
		public boolean contains(ResourceKey<Level> dimension, BlockPos pos, int radius) {
			if (!center.dimension().equals(dimension)) return false;
			long r2 = (long) radius * radius;
			for (long bell : bells) if (BlockPos.of(bell).distSqr(pos) <= r2) return true;
			long a2 = (long) ANCHOR_RADIUS * ANCHOR_RADIUS;
			for (Resident r : residents.values()) if (BlockPos.of(r.anchor).distSqr(pos) <= a2) return true;
			return false;
		}

		boolean nearBell(ResourceKey<Level> dimension, BlockPos pos, int radius) {
			if (!center.dimension().equals(dimension)) return false;
			long r2 = (long) radius * radius;
			for (long bell : bells) if (BlockPos.of(bell).distSqr(pos) <= r2) return true;
			return false;
		}

		public List<Resident> workers() {
			return residents.values().stream().filter(Resident::working).toList();
		}
	}

	private final List<Village> villages = new ArrayList<>();
	/** Centers of villages that were merged away -> id of the village they became part of. */
	private final Map<String, Integer> aliases = new HashMap<>();
	/** Villagers that became new entities (zombified, cured) -> the id their name comes from, so it stays the same. */
	private final Map<UUID, UUID> nameRoots = new HashMap<>();

	/** The id a villager's generated name comes from: its first id, if it was ever zombified or cured. */
	public UUID nameRoot(UUID villager) {
		return nameRoots.getOrDefault(villager, villager);
	}
	private int nextId = 1;

	public static VillageRegistry get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	public List<Village> all() {
		return villages;
	}

	/**
	 * The name of each unnamed village, as an index into the name list: the one its position picks, or the next free
	 * one when an older village already has it. Older villages keep their names when a new one appears.
	 */
	public int nameIndex(Village village, int count) {
		List<Village> byAge = new ArrayList<>(villages);
		byAge.sort(java.util.Comparator.comparingInt(v -> v.id));
		Set<Integer> used = new java.util.HashSet<>();
		for (Village v : byAge) {
			long hash = v.center.pos().asLong() * 31 + v.center.dimension().identifier().hashCode();
			int start = (int) Math.floorMod(hash, (long) count);
			// The first free name from the one the position picks, round the list; numbered only when all are taken.
			int index = -1;
			for (int round = 0; index < 0; round++) {
				for (int step = 0; step < count && index < 0; step++) {
					int candidate = round * count + (start + step) % count;
					if (!used.contains(candidate)) index = candidate;
				}
			}
			used.add(index);
			if (v == village) return index;
		}
		return (int) Math.floorMod(village.center.pos().asLong(), (long) count);
	}

	/** The village for a stored key: its center now, or the village that covers that spot (keys from older versions). */
	public Optional<Village> resolve(GlobalPos pos) {
		Optional<Village> v = byCenter(pos);
		return v.isPresent() ? v : villageAt(pos.dimension(), pos.pos());
	}

	public Optional<Village> byId(int id) {
		for (Village v : villages) if (v.id == id) return Optional.of(v);
		return Optional.empty();
	}

	/** The village with this center, following merges. */
	public Optional<Village> byCenter(GlobalPos center) {
		for (Village v : villages) if (v.center.equals(center)) return Optional.of(v);
		Integer id = aliases.get(ErrandsData.villageKey(center));
		return id != null ? byId(id) : Optional.empty();
	}

	/** The current center of a village given an older center (merged villages point to the survivor). */
	public GlobalPos canonical(GlobalPos center) {
		return byCenter(center).map(v -> v.center).orElse(center);
	}

	public Optional<Village> villageOf(UUID resident) {
		for (Village v : villages) if (v.residents.containsKey(resident)) return Optional.of(v);
		return Optional.empty();
	}

	public Optional<Village> villageAt(ResourceKey<Level> dimension, BlockPos pos) {
		int radius = ErrandsConfig.INSTANCE.villageRadius;
		for (Village v : villages) if (v.contains(dimension, pos, radius)) return Optional.of(v);
		return Optional.empty();
	}

	/**
	 * The village a bell belongs to. With {@code create}, a bell outside every village starts a new one, and a bell
	 * near several villages merges them.
	 */
	public Optional<Village> forBell(MinecraftServer server, GlobalPos bell, boolean create) {
		int radius = ErrandsConfig.INSTANCE.villageRadius;
		List<Village> touching = new ArrayList<>();
		for (Village v : villages) {
			if (v.bells.contains(bell.pos().asLong()) && v.center.dimension().equals(bell.dimension())) return Optional.of(v);
			if (v.nearBell(bell.dimension(), bell.pos(), radius)) touching.add(v);
		}
		if (!create) {
			return touching.stream().min((a, b) -> Double.compare(a.center.pos().distSqr(bell.pos()), b.center.pos().distSqr(bell.pos())));
		}
		Village village;
		if (touching.isEmpty()) {
			village = new Village(nextId++, bell);
			villages.add(village);
		} else {
			touching.sort((a, b) -> Integer.compare(a.id, b.id));
			village = touching.getFirst();
			for (Village other : touching.subList(1, touching.size())) merge(server, village, other);
			village.bells.add(bell.pos().asLong());
		}
		setDirty();
		return Optional.of(village);
	}

	/** Adds a bell that stands inside a village's area (near a resident's bed or workplace) to that village. */
	public void attachBell(MinecraftServer server, Village village, GlobalPos bell) {
		if (!village.bells.add(bell.pos().asLong())) return;
		int radius = ErrandsConfig.INSTANCE.villageRadius;
		for (Village other : new ArrayList<>(villages)) {
			if (other != village && other.nearBell(bell.dimension(), bell.pos(), radius)) {
				if (other.id < village.id) {
					merge(server, other, village);
					village = other;
				} else {
					merge(server, village, other);
				}
			}
		}
		setDirty();
	}

	private void merge(MinecraftServer server, Village keep, Village gone) {
		keep.bells.addAll(gone.bells);
		keep.residents.putAll(gone.residents);
		if (keep.name == null) keep.name = gone.name;
		villages.remove(gone);
		aliases.put(ErrandsData.villageKey(gone.center), keep.id);
		aliases.replaceAll((k, id) -> id == gone.id ? keep.id : id);
		ErrandsData.get(server).mergeVillages(gone.center, keep.center);
		VillagersRemember.LOG.info("Merged village {} into {}", gone.center.pos().toShortString(), keep.center.pos().toShortString());
	}

	/** A resident became a new entity (zombified, or cured back): same place in the village under the new id. */
	public void renameResident(UUID from, UUID to) {
		nameRoots.put(to, nameRoots.getOrDefault(from, from));
		nameRoots.remove(from);
		setDirty();
		for (Village v : villages) {
			Resident old = v.residents.remove(from);
			if (old == null) continue;
			Resident r = new Resident(to);
			r.profession = old.profession;
			r.type = old.type;
			r.level = old.level;
			r.baby = old.baby;
			r.anchor = old.anchor;
			r.lastSeenDay = old.lastSeenDay;
			r.pendingVillage = old.pendingVillage;
			r.pendingSince = old.pendingSince;
			v.residents.put(to, r);
			setDirty();
		}
	}

	public void removeResident(UUID id) {
		for (Village v : villages) {
			if (v.residents.remove(id) != null) setDirty();
		}
	}

	// ---- watching villagers ----

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % SCAN_INTERVAL != 0) return;
		VillageRegistry registry = get(server);
		for (ServerLevel level : server.getAllLevels()) {
			long day = ErrandGenerator.day(level);
			for (Villager villager : level.getEntities(EntityTypes.VILLAGER, v -> v.isAlive())) {
				registry.observe(server, villager, day);
			}
		}
	}

	/** Updates where a loaded villager lives. Returns its village, if any. */
	public Optional<Village> observe(MinecraftServer server, Villager villager, long day) {
		ResourceKey<Level> dimension = villager.level().dimension();
		Optional<Village> current = villageOf(villager.getUUID());
		Optional<Village> candidate = Optional.empty();
		Optional<GlobalPos> bell = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
		if (bell.isPresent() && bell.get().dimension().equals(dimension)) {
			candidate = forBell(server, bell.get(), true);
		} else {
			BlockPos anchor = anchor(villager);
			if (current.isEmpty()) candidate = villageAt(dimension, anchor);
		}
		// villageOf may have changed if forBell merged villages.
		current = villageOf(villager.getUUID());

		Village home;
		if (current.isEmpty()) {
			if (candidate.isEmpty()) return Optional.empty();
			home = candidate.get();
			home.residents.put(villager.getUUID(), new Resident(villager.getUUID()));
		} else if (candidate.isPresent() && candidate.get() != current.get()) {
			Resident r = current.get().residents.get(villager.getUUID());
			if (r.pendingVillage != candidate.get().id) {
				r.pendingVillage = candidate.get().id;
				r.pendingSince = day;
				home = current.get();
			} else if (day - r.pendingSince >= MOVE_AFTER_DAYS) {
				current.get().residents.remove(villager.getUUID());
				home = candidate.get();
				home.residents.put(villager.getUUID(), r);
				r.pendingVillage = -1;
			} else {
				home = current.get();
			}
		} else {
			home = current.get();
			Resident r = home.residents.get(villager.getUUID());
			if (r != null) r.pendingVillage = -1;
		}
		Resident r = home.residents.get(villager.getUUID());
		VillagerData data = villager.getVillagerData();
		r.profession = ErrandGenerator.professionId(data);
		r.type = ErrandGenerator.typeId(data);
		r.level = data.level();
		r.baby = villager.isBaby();
		r.anchor = anchor(villager).asLong();
		r.lastSeenDay = day;
		setDirty();
		return Optional.of(home);
	}

	private static BlockPos anchor(Villager villager) {
		Optional<GlobalPos> home = villager.getBrain().getMemory(MemoryModuleType.HOME);
		if (home.isPresent() && home.get().dimension().equals(villager.level().dimension())) return home.get().pos();
		Optional<GlobalPos> job = villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
		if (job.isPresent() && job.get().dimension().equals(villager.level().dimension())) return job.get().pos();
		return villager.blockPosition();
	}

	// ---- saving ----

	private static VillageRegistry load(CompoundTag tag) {
		VillageRegistry registry = new VillageRegistry();
		registry.nextId = tag.getIntOr("next_id", 1);
		tag.getListOrEmpty("villages").compoundStream().forEach(t -> {
			ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(t.getStringOr("dim", "minecraft:overworld")));
			int id = t.getIntOr("id", registry.nextId++);
			Village v = new Village(id, GlobalPos.of(dim, BlockPos.of(t.getLongOr("center", 0L))));
			String name = t.getStringOr("name", "");
			v.name = name.isEmpty() ? null : name;
			String patron = t.getStringOr("patron", "");
			if (!patron.isEmpty()) {
				try {
					v.patron = UUID.fromString(patron);
					v.patronName = t.getStringOr("patron_name", "");
				} catch (IllegalArgumentException ignored) {
				}
			}
			t.getLongArray("bells").ifPresent(arr -> {
				for (long b : arr) v.bells.add(b);
			});
			t.getListOrEmpty("residents").compoundStream().forEach(rt -> {
				UUID rid;
				try {
					rid = UUID.fromString(rt.getStringOr("id", ""));
				} catch (IllegalArgumentException e) {
					return;
				}
				Resident r = new Resident(rid);
				r.profession = rt.getStringOr("profession", "minecraft:none");
				r.type = rt.getStringOr("type", "minecraft:plains");
				r.level = rt.getIntOr("level", 1);
				r.baby = rt.getBooleanOr("baby", false);
				r.anchor = rt.getLongOr("anchor", 0L);
				r.lastSeenDay = rt.getLongOr("seen", 0L);
				r.pendingVillage = rt.getIntOr("pending", -1);
				r.pendingSince = rt.getLongOr("pending_since", 0L);
				v.residents.put(rid, r);
			});
			registry.villages.add(v);
			registry.nextId = Math.max(registry.nextId, id + 1);
		});
		CompoundTag aliases = tag.getCompoundOrEmpty("aliases");
		for (String k : aliases.keySet()) registry.aliases.put(k, aliases.getIntOr(k, 0));
		CompoundTag roots = tag.getCompoundOrEmpty("name_roots");
		for (String k : roots.keySet()) {
			try {
				registry.nameRoots.put(UUID.fromString(k), UUID.fromString(roots.getStringOr(k, k)));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return registry;
	}

	private CompoundTag save() {
		CompoundTag root = new CompoundTag();
		root.putInt("next_id", nextId);
		ListTag list = new ListTag();
		for (Village v : villages) {
			CompoundTag t = new CompoundTag();
			t.putInt("id", v.id);
			t.putString("dim", v.center.dimension().identifier().toString());
			t.putLong("center", v.center.pos().asLong());
			if (v.name != null) t.putString("name", v.name);
			if (v.patron != null) {
				t.putString("patron", v.patron.toString());
				if (v.patronName != null) t.putString("patron_name", v.patronName);
			}
			t.put("bells", new LongArrayTag(v.bells.stream().mapToLong(Long::longValue).toArray()));
			ListTag residents = new ListTag();
			for (Resident r : v.residents.values()) {
				CompoundTag rt = new CompoundTag();
				rt.putString("id", r.id.toString());
				rt.putString("profession", r.profession);
				rt.putString("type", r.type);
				rt.putInt("level", r.level);
				rt.putBoolean("baby", r.baby);
				rt.putLong("anchor", r.anchor);
				rt.putLong("seen", r.lastSeenDay);
				rt.putInt("pending", r.pendingVillage);
				rt.putLong("pending_since", r.pendingSince);
				residents.add(rt);
			}
			t.put("residents", residents);
			list.add(t);
		}
		root.put("villages", list);
		CompoundTag aliases = new CompoundTag();
		this.aliases.forEach(aliases::putInt);
		root.put("aliases", aliases);
		CompoundTag roots = new CompoundTag();
		nameRoots.forEach((k, v) -> roots.putString(k.toString(), v.toString()));
		root.put("name_roots", roots);
		return root;
	}
}
