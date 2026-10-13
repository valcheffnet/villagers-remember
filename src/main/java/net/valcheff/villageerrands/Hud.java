package net.valcheff.villageerrands;

import io.netty.buffer.Unpooled;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundResetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.protocol.game.ClientboundSetScorePacket;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.valcheff.villageerrands.ui.Icons;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player screen elements, all sent as vanilla packets to that player only: the errand tracker in the
 * sidebar, big titles when an errand is ready or done, and "!" / "?" marks above villagers.
 */
public final class Hud {
	private static final String OBJECTIVE = "villageerrands";
	private static final int MAX_LINES = 15;
	private static final Scoreboard DUMMY_BOARD = new Scoreboard();

	private enum Mark { NONE, OFFER, READY }

	private static final class Marker {
		final int displayId;
		Mark mark;

		Marker(int displayId, Mark mark) {
			this.displayId = displayId;
			this.mark = mark;
		}
	}

	/** A sidebar line: text on the left, and an optional part shown in the right-aligned score column. */
	private record Line(Component left, Component right) {
		static Line of(Component left) {
			return new Line(left, null);
		}
	}

	private static final class PlayerHud {
		ServerPlayer entity;
		int since;
		final Set<String> readyNotified = new HashSet<>();
		final List<Line> lines = new ArrayList<>();
		Component title;
		boolean sidebar;
		final Map<Integer, Marker> markers = new HashMap<>();
		/** Villagers (entity ids) whose name this player has been sent, and when. */
		final Map<Integer, Integer> named = new HashMap<>();
		/** Villager entity id -> our text display with its name, riding on its head (mode "always"). */
		final Map<Integer, Integer> nameplates = new HashMap<>();
	}

	private static final Map<UUID, PlayerHud> HUDS = new HashMap<>();

	public static void forget(UUID player) {
		HUDS.remove(player);
	}

	private static PlayerHud hud(ServerPlayer player) {
		PlayerHud hud = HUDS.computeIfAbsent(player.getUUID(), k -> new PlayerHud());
		if (hud.entity != player) {
			// New player object (join, respawn, other dimension): the client dropped our fake entities and may
			// have dropped the sidebar, so start over.
			hud.entity = player;
			hud.since = player.level().getServer().getTickCount();
			hud.markers.clear();
			hud.named.clear();
			hud.nameplates.clear();
			hud.lines.clear();
			hud.title = null;
			hud.sidebar = false;
		}
		return hud;
	}

	public static void tick(MinecraftServer server) {
		int tick = server.getTickCount();
		if (tick % 10 != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PlayerHud hud = hud(player);
			ErrandsData.PlayerState state = ErrandService.state(player);
			checkReady(player, hud, state);
			if (ErrandsConfig.INSTANCE.showTracker) sidebar(player, hud, state);
			// Marks start a moment after joining, once the client has received the villagers around it.
			if (ErrandsConfig.INSTANCE.showMarkers && tick % 20 == 0 && tick - hud.since > 40) markers(player, hud, state);
			if (tick % 20 == 0 && tick - hud.since > 40) names(player, hud, tick);
		}
	}

	/** Updates tracker and marks right away (after accepting, handing in or abandoning). */
	public static void refresh(ServerPlayer player) {
		PlayerHud hud = hud(player);
		ErrandsData.PlayerState state = ErrandService.state(player);
		if (ErrandsConfig.INSTANCE.showTracker) sidebar(player, hud, state);
		if (ErrandsConfig.INSTANCE.showMarkers) markers(player, hud, state);
	}

	// ---- titles ----

	public static void title(ServerPlayer player, Component title, Component subtitle) {
		player.connection.send(new ClientboundSetTitlesAnimationPacket(8, 50, 12));
		player.connection.send(new ClientboundSetSubtitleTextPacket(subtitle));
		player.connection.send(new ClientboundSetTitleTextPacket(title));
	}

	/** An accepted errand just became ready to hand in. */
	public static void ready(ServerPlayer player, ErrandsData.Active active) {
		PlayerHud hud = hud(player);
		if (!hud.readyNotified.add(active.errand.id())) return;
		Errand errand = active.errand;
		Villager villager = player.level() instanceof ServerLevel level && level.getEntity(errand.villager()) instanceof Villager v ? v : null;
		title(player, Lang.tr(player, "title.ready").withStyle(ChatFormatting.GOLD),
			Lang.tr(player, "title.ready_sub", ErrandText.villagerName(player, errand.villager(), villager)).withStyle(ChatFormatting.YELLOW));
		sound(player, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0f, 1.2f);
	}

	private static void checkReady(ServerPlayer player, PlayerHud hud, ErrandsData.PlayerState state) {
		Set<String> ready = new HashSet<>();
		for (ErrandsData.Active a : state.active) {
			if (!ErrandService.canHandIn(player, a)) continue;
			ready.add(a.errand.id());
			ready(player, a);
		}
		// Forget errands that are no longer ready (items dropped) or gone, so they can be announced again.
		hud.readyNotified.retainAll(ready);
	}

	public static void sound(ServerPlayer player, SoundEvent sound, float volume, float pitch) {
		player.connection.send(new ClientboundSoundPacket(BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), SoundSource.PLAYERS,
			player.getX(), player.getY(), player.getZ(), volume, pitch, player.getRandom().nextLong()));
	}

	// ---- sidebar tracker ----

	private static void sidebar(ServerPlayer player, PlayerHud hud, ErrandsData.PlayerState state) {
		List<Line> lines = new ArrayList<>();
		ServerLevel level = (ServerLevel) player.level();
		VillageRegistry registry = VillageRegistry.get(level.getServer());
		// Tracked villages: tier, points towards the next tier and residents still needed.
		for (String key : state.trackedVillages) {
			if (lines.size() + 3 > MAX_LINES) break;
			java.util.Optional<VillageRegistry.Village> village = registry.all().stream()
				.filter(v -> ErrandsData.villageKey(v.center).equals(key)).findFirst();
			if (village.isEmpty()) continue;
			Reputation.Standing s = Reputation.standing(state, java.util.Optional.of(village.get().center));
			lines.add(Line.of(Component.empty().append(Icons.of(net.minecraft.world.item.Items.BELL))
				.append(Component.literal(ErrandText.villageName(player, village.get().center)).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
				.append(Lang.tr(player, "rep.village." + s.tier()).withColor(ErrandText.tierColor(s.tier())))));
			if (!s.max()) {
				lines.add(new Line(Component.literal("  ").append(Lang.tr(player, "side.village_points").withStyle(ChatFormatting.GRAY)),
					ErrandText.bar(Math.max(0, s.points()), s.nextPoints(), 5)));
				// "Acquaintances □□ 0/2": villagers needed at the next tier.
				lines.add(new Line(Component.literal("  ").append(Lang.tr(player, "side.need." + (s.tier() + 1)).withStyle(ChatFormatting.GRAY)),
					ErrandText.bar(s.haveCount(), s.needCount(), s.needCount())));
			}
		}
		// Tracked village orders: what is wanted, how far the village is, and the player's own part.
		for (String key : state.trackedOrders) {
			if (lines.size() + 2 > MAX_LINES) break;
			java.util.Optional<VillageRegistry.Village> village = registry.all().stream()
				.filter(v -> ErrandsData.villageKey(v.center).equals(key)).findFirst();
			if (village.isEmpty()) continue;
			java.util.Optional<GroupOrder.View> view = GroupOrder.current(level.getServer(), village.get());
			if (view.isEmpty()) continue;
			GroupOrder.View v = view.get();
			int progress = Math.min(v.order().progress, v.target());
			lines.add(Line.of(Component.empty().append(Icons.of(v.kind().icon))
				.append(Lang.tr(player, "group." + v.kind().key() + ".name").withStyle(v.done() ? ChatFormatting.GREEN : ChatFormatting.GOLD))
				.append(Component.literal(" · " + ErrandText.villageName(player, village.get().center)).withStyle(ChatFormatting.DARK_GRAY))));
			int mine = v.order().contrib.getOrDefault(player.getUUID(), 0);
			lines.add(new Line(Component.literal("  ").append(Lang.tr(player, "side.order_mine", mine).withStyle(ChatFormatting.GRAY)),
				ErrandText.bar(progress, v.target(), 5)));
		}
		// Only the errands the player chose to track: who gives it, then every part of it on its own line.
		for (ErrandsData.Active a : state.active) {
			if (!state.isTracked(a.errand.id())) continue;
			Errand errand = a.errand;
			if (lines.size() + 1 + errand.objectives().size() > MAX_LINES) break;
			Villager villager = level.getEntity(errand.villager()) instanceof Villager v ? v : null;
			MutableComponent header = Component.empty().append(VillagerHeads.face(ErrandText.path(errand.profession()),
				ErrandText.path(errand.villagerType()))).append(" ")
				.append(Component.literal(ErrandText.villagerName(player, errand.villager(), villager)).withStyle(ChatFormatting.WHITE));
			if (ErrandService.canHandIn(player, a)) header.append(Component.literal(" ✔").withStyle(ChatFormatting.GOLD));
			lines.add(Line.of(header));
			for (int k = 0; k < errand.objectives().size(); k++) {
				net.valcheff.villageerrands.Objective o = errand.objectives().get(k);
				int done = ErrandService.progress(player, a, k);
				boolean finished = done >= o.needed();
				MutableComponent text = Component.literal("  ").append(Icons.of(ErrandText.icon(o).getItem()))
					.append(ErrandText.shortName(player, o).withStyle(finished ? ChatFormatting.GRAY : ChatFormatting.WHITE));
				lines.add(new Line(text, ErrandText.bar(done, o.needed(), 5)));
			}
		}

		if (lines.isEmpty()) {
			if (hud.sidebar) {
				player.connection.send(new ClientboundSetObjectivePacket(objective(Component.empty()), ClientboundSetObjectivePacket.METHOD_REMOVE));
				hud.sidebar = false;
				hud.lines.clear();
				hud.title = null;
			}
			return;
		}
		Component title = Component.empty().append(Icons.of(net.minecraft.world.item.Items.WRITABLE_BOOK))
			.append(Lang.tr(player, "tracker.title").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		if (!hud.sidebar) {
			Objective objective = objective(title);
			player.connection.send(new ClientboundSetObjectivePacket(objective, ClientboundSetObjectivePacket.METHOD_ADD));
			player.connection.send(new ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective));
			hud.sidebar = true;
			hud.title = title;
		} else if (!title.equals(hud.title)) {
			player.connection.send(new ClientboundSetObjectivePacket(objective(title), ClientboundSetObjectivePacket.METHOD_CHANGE));
			hud.title = title;
		}
		for (int i = 0; i < lines.size(); i++) {
			if (i < hud.lines.size() && lines.get(i).equals(hud.lines.get(i))) continue;
			Line line = lines.get(i);
			net.minecraft.network.chat.numbers.NumberFormat right = line.right() == null ? BlankFormat.INSTANCE
				: new net.minecraft.network.chat.numbers.FixedFormat(line.right());
			player.connection.send(new ClientboundSetScorePacket("line" + i, OBJECTIVE, MAX_LINES - i,
				java.util.Optional.of(line.left()), java.util.Optional.of(right)));
		}
		for (int i = lines.size(); i < hud.lines.size(); i++) {
			player.connection.send(new ClientboundResetScorePacket("line" + i, OBJECTIVE));
		}
		hud.lines.clear();
		hud.lines.addAll(lines);
	}

	private static Objective objective(Component title) {
		return new Objective(DUMMY_BOARD, OBJECTIVE, ObjectiveCriteria.DUMMY, title, ObjectiveCriteria.RenderType.INTEGER,
			false, BlankFormat.INSTANCE);
	}

	// ---- villager names ----

	/**
	 * Sends each villager's name to this player as if it were named with a name tag: in the player's language,
	 * only for them (the villager itself is not renamed). Vanilla shows it above the head, Jade and similar mods
	 * show it when looking at the villager. Sent again every 10 seconds, and when the villager comes back into
	 * range, since the client forgets it when the villager is sent anew. Villagers with a real name tag keep it.
	 */
	private static void names(ServerPlayer player, PlayerHud hud, int tick) {
		String mode = ErrandsConfig.INSTANCE.villagerNames;
		if (mode == null || mode.equalsIgnoreCase("off")) return;
		boolean always = !mode.equalsIgnoreCase("look");
		ServerLevel level = (ServerLevel) player.level();
		int range = ErrandsConfig.INSTANCE.villagerNameRange;
		Map<Integer, Villager> near = new HashMap<>();
		for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(player.blockPosition()).inflate(range), Villager::isAlive)) {
			if (!v.hasCustomName()) near.put(v.getId(), v);
		}
		// Nameplates of villagers that left: removed.
		Iterator<Map.Entry<Integer, Integer>> it = hud.nameplates.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Integer> e = it.next();
			if (always && near.containsKey(e.getKey())) continue;
			player.connection.send(new ClientboundRemoveEntitiesPacket(e.getValue()));
			Entity v = level.getEntity(e.getKey());
			it.remove();
			if (v != null) player.connection.send(passengers(v, hud));
		}
		for (Villager v : near.values()) {
			Integer sent = hud.named.get(v.getId());
			boolean resend = sent == null || tick - sent >= 200;
			Component name = Component.literal(ErrandText.villagerName(player, v.getUUID(), v));
			if (resend) {
				hud.named.put(v.getId(), tick);
				// For Jade and similar mods: the villager's name, never drawn by vanilla (that is our nameplate's job:
				// vanilla hides the name of a mob that carries a passenger, like our "!").
				player.connection.send(new ClientboundSetEntityDataPacket(v.getId(), List.of(
					SynchedEntityData.DataValue.create(net.valcheff.villageerrands.mixin.EntityNameAccessor.villageerrands$customName(), java.util.Optional.of(name)),
					SynchedEntityData.DataValue.create(net.valcheff.villageerrands.mixin.EntityNameAccessor.villageerrands$customNameVisible(), false))));
			}
			if (!always) continue;
			Integer plate = hud.nameplates.get(v.getId());
			if (plate == null) {
				Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
				if (display == null) return;
				plate = display.getId();
				Vec3 pos = v.position().add(0, v.getBbHeight(), 0);
				player.connection.send(new ClientboundAddEntityPacket(plate, UUID.randomUUID(), pos.x, pos.y, pos.z, 0, 0,
					EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0));
				hud.nameplates.put(v.getId(), plate);
				resend = true;
			}
			if (resend) player.connection.send(new ClientboundSetEntityDataPacket(plate, nameplateData(level, name)));
			// Re-mount every update, as with the marks: a mount sent before the client knew the villager is dropped.
			player.connection.send(passengers(v, hud));
		}
		hud.named.keySet().retainAll(near.keySet());
	}

	/** A text display that looks like a name tag: white name on the usual dark background, just above the head. */
	private static List<SynchedEntityData.DataValue<?>> nameplateData(ServerLevel level, Component name) {
		CompoundTag tag = new CompoundTag();
		tag.put("Pos", list(DoubleTag.valueOf(0), DoubleTag.valueOf(0), DoubleTag.valueOf(0)));
		tag.put("text", ComponentSerialization.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), name).getOrThrow());
		tag.putString("billboard", "center");
		tag.putBoolean("see_through", false);
		CompoundTag transformation = new CompoundTag();
		transformation.put("translation", list(FloatTag.valueOf(0), FloatTag.valueOf(0.3f), FloatTag.valueOf(0)));
		transformation.put("left_rotation", list(FloatTag.valueOf(0), FloatTag.valueOf(0), FloatTag.valueOf(0), FloatTag.valueOf(1)));
		transformation.put("scale", list(FloatTag.valueOf(1f), FloatTag.valueOf(1f), FloatTag.valueOf(1f)));
		transformation.put("right_rotation", list(FloatTag.valueOf(0), FloatTag.valueOf(0), FloatTag.valueOf(0), FloatTag.valueOf(1)));
		tag.put("transformation", transformation);
		Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
		return values != null ? values : List.of();
	}

	// ---- "!" and "?" above villagers ----

	private static void markers(ServerPlayer player, PlayerHud hud, ErrandsData.PlayerState state) {
		ServerLevel level = (ServerLevel) player.level();
		int range = ErrandsConfig.INSTANCE.markerRange;
		Map<Integer, Villager> near = new HashMap<>();
		for (Villager v : level.getEntitiesOfClass(Villager.class, new AABB(player.blockPosition()).inflate(range),
			ErrandGenerator::givesErrands)) {
			near.put(v.getId(), v);
		}
		// Drop marks of villagers that left the range, died or no longer have anything for the player.
		Iterator<Map.Entry<Integer, Marker>> it = hud.markers.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<Integer, Marker> e = it.next();
			Villager v = near.get(e.getKey());
			Mark mark = v == null ? Mark.NONE : mark(player, level, state, v);
			if (mark == Mark.NONE) {
				player.connection.send(new ClientboundRemoveEntitiesPacket(e.getValue().displayId));
				it.remove();
				if (v != null) player.connection.send(passengers(v, hud));
			} else {
				if (mark != e.getValue().mark) {
					e.getValue().mark = mark;
					player.connection.send(new ClientboundSetEntityDataPacket(e.getValue().displayId, markData(level, mark)));
				}
				// Re-mount every update: a mount sent before the client knew the villager is dropped by the client.
				player.connection.send(passengers(v, hud));
			}
		}
		for (Villager v : near.values()) {
			if (hud.markers.containsKey(v.getId())) continue;
			Mark mark = mark(player, level, state, v);
			if (mark == Mark.NONE) continue;
			Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
			if (display == null) return;
			int id = display.getId();
			Vec3 pos = v.position().add(0, v.getBbHeight(), 0);
			player.connection.send(new ClientboundAddEntityPacket(id, UUID.randomUUID(), pos.x, pos.y, pos.z, 0, 0,
				EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0));
			player.connection.send(new ClientboundSetEntityDataPacket(id, markData(level, mark)));
			hud.markers.put(v.getId(), new Marker(id, mark));
			player.connection.send(passengers(v, hud));
		}
	}

	private static Mark mark(ServerPlayer player, ServerLevel level, ErrandsData.PlayerState state, Villager villager) {
		for (ErrandsData.Active a : state.active) {
			if (a.errand.villager().equals(villager.getUUID()) && ErrandService.canHandIn(player, a)) return Mark.READY;
		}
		if (state.active.size() >= ErrandsConfig.INSTANCE.maxActiveErrands) return Mark.NONE;
		for (Errand e : ErrandGenerator.shown(level, ErrandGenerator.Giver.of(villager), state)) {
			String id = e.id();
			if (!state.done.containsKey(id) && state.find(id) == null) return Mark.OFFER;
		}
		return Mark.NONE;
	}

	/** Entity data for a floating "!" or "?": built by loading a throwaway text display from NBT. */
	private static List<SynchedEntityData.DataValue<?>> markData(ServerLevel level, Mark mark) {
		Component text = Component.literal(mark == Mark.READY ? "?" : "!").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
		CompoundTag tag = new CompoundTag();
		tag.put("Pos", list(DoubleTag.valueOf(0), DoubleTag.valueOf(0), DoubleTag.valueOf(0)));
		tag.put("text", ComponentSerialization.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), text).getOrThrow());
		tag.putString("billboard", "center");
		tag.putInt("background", 0);
		tag.putBoolean("shadow", true);
		CompoundTag transformation = new CompoundTag();
		// High enough to sit above the villager's name.
		transformation.put("translation", list(FloatTag.valueOf(0), FloatTag.valueOf(0.6f), FloatTag.valueOf(0)));
		transformation.put("left_rotation", list(FloatTag.valueOf(0), FloatTag.valueOf(0), FloatTag.valueOf(0), FloatTag.valueOf(1)));
		transformation.put("scale", list(FloatTag.valueOf(2.2f), FloatTag.valueOf(2.2f), FloatTag.valueOf(2.2f)));
		transformation.put("right_rotation", list(FloatTag.valueOf(0), FloatTag.valueOf(0), FloatTag.valueOf(0), FloatTag.valueOf(1)));
		tag.put("transformation", transformation);
		Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
		display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
		List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
		return values != null ? values : List.of();
	}

	private static ListTag list(net.minecraft.nbt.Tag... tags) {
		ListTag list = new ListTag();
		for (net.minecraft.nbt.Tag t : tags) list.add(t);
		return list;
	}

	/** The villager's real passengers plus what this player sees riding on its head: our mark and nameplate. */
	private static ClientboundSetPassengersPacket passengers(Entity vehicle, PlayerHud hud) {
		List<Integer> ids = new ArrayList<>();
		for (Entity p : vehicle.getPassengers()) ids.add(p.getId());
		Marker marker = hud.markers.get(vehicle.getId());
		if (marker != null) ids.add(marker.displayId);
		Integer plate = hud.nameplates.get(vehicle.getId());
		if (plate != null) ids.add(plate);
		FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
		buf.writeVarInt(vehicle.getId());
		buf.writeVarIntArray(ids.stream().mapToInt(Integer::intValue).toArray());
		return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
	}
}
