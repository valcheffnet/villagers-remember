package net.valcheff.villagersremember;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The patron of a village (TIERS.md section 7). A village has one: of the players who qualify (Hero of the Village,
 * and every trust trial passed with at least five of its villagers), the one with the most standing points there.
 * Anyone who qualifies with more points takes the place at any time. The patron's head and name are shown at the
 * bell; the patron trades there at the patron discount and may give the village a name.
 */
public final class Patron {
	/** Villagers with every trust trial passed that a patron needs. */
	public static final int BEST_FRIENDS = 5;

	/** The village's patron, if it has one. */
	public static Optional<UUID> of(VillageRegistry.Village village) {
		return Optional.ofNullable(village.patron);
	}

	/** True when the player is the patron of this village now. */
	public static boolean is(ErrandsData.PlayerState state, Optional<GlobalPos> village) {
		if (village.isEmpty() || state.id == null) return false;
		MinecraftServer server = VillagersRemember.server();
		if (server == null) return false;
		return VillageRegistry.get(server).byCenter(village.get()).map(v -> state.id.equals(v.patron)).orElse(false);
	}

	/** Residents of the village the player has passed every trust trial with. */
	public static int bestFriends(ErrandsData.PlayerState state, VillageRegistry.Village village) {
		int n = 0;
		for (VillageRegistry.Resident r : village.workers()) {
			if (Reputation.trialsPassed(state, r.id) >= Reputation.MAX_TIER && Reputation.villagerTier(state, r.id) >= Reputation.MAX_TIER) n++;
		}
		return n;
	}

	public static boolean qualifies(ErrandsData.PlayerState state, VillageRegistry.Village village) {
		return Reputation.villageTier(state, Optional.of(village.center)) >= Reputation.MAX_TIER && bestFriends(state, village) >= BEST_FRIENDS;
	}

	/** After anything that can change standing in a village: works out who its patron is now. */
	public static void check(MinecraftServer server, Optional<GlobalPos> center) {
		if (center.isEmpty()) return;
		VillageRegistry registry = VillageRegistry.get(server);
		Optional<VillageRegistry.Village> found = registry.byCenter(center.get());
		if (found.isEmpty()) return;
		VillageRegistry.Village village = found.get();
		ErrandsData data = ErrandsData.get(server);
		Optional<GlobalPos> at = Optional.of(village.center);
		UUID best = null;
		int bestPoints = Integer.MIN_VALUE;
		for (Map.Entry<UUID, ErrandsData.PlayerState> e : data.players().entrySet()) {
			if (!qualifies(e.getValue(), village)) continue;
			int points = Reputation.village(e.getValue(), at);
			// The patron keeps the place on a tie: only more points take it.
			boolean better = points > bestPoints || (points == bestPoints && e.getKey().equals(village.patron));
			if (better) {
				best = e.getKey();
				bestPoints = points;
			}
		}
		UUID old = village.patron;
		if (Objects.equals(best, old)) return;
		Map<UUID, String> oldNames = new HashMap<>();
		if (old != null && village.patronName != null) oldNames.put(old, village.patronName);
		village.patron = best;
		ServerPlayer newcomer = best != null ? server.getPlayerList().getPlayer(best) : null;
		if (newcomer != null) village.patronName = newcomer.getGameProfile().name();
		else village.patronName = best != null ? knownName(server, best) : null;
		registry.setDirty();
		String key = ErrandsData.villageKey(village.center);
		if (best != null) {
			ErrandsData.PlayerState state = data.player(best);
			boolean first = state.patronOf.add(key);
			data.setDirty();
			if (newcomer != null) {
				String name = ErrandText.villageName(newcomer, village.center);
				Hud.title(newcomer, Lang.tr(newcomer, "patron.title").withStyle(ChatFormatting.GOLD),
					Component.literal(name).withStyle(ChatFormatting.WHITE));
				newcomer.sendSystemMessage(Lang.tr(newcomer, "patron.chat", name, Math.round(ErrandsConfig.INSTANCE.patronDiscount * 100))
					.withStyle(ChatFormatting.GOLD));
				newcomer.level().playSound(null, newcomer.getX(), newcomer.getY(), newcomer.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
					SoundSource.PLAYERS, 1.0f, 1.0f);
				if (first) Fame.add(newcomer, Fame.PATRON);
				Milestones.check(newcomer);
			} else if (first) {
				state.fame += Fame.PATRON;
			}
		}
		// The whole server hears about it, each player in their own language.
		String oldName = old != null ? oldNames.getOrDefault(old, "?") : null;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			String name = ErrandText.villageName(p, village.center);
			Component newWho = Component.literal(village.patronName != null ? village.patronName : "?").withStyle(ChatFormatting.WHITE);
			Component line;
			if (best == null) line = Lang.tr(p, "patron.broadcast_none", name);
			else if (old == null) line = Lang.tr(p, "patron.broadcast", newWho, name);
			else line = Lang.tr(p, "patron.broadcast_took", newWho, name, Component.literal(oldName).withStyle(ChatFormatting.WHITE));
			p.sendSystemMessage(line.copy().withStyle(ChatFormatting.GOLD));
		}
	}

	/** A player's name from the server's profile cache, for a patron who is offline. */
	private static String knownName(MinecraftServer server, UUID id) {
		try {
			return server.services().nameToIdCache().get(id).map(p -> p.name()).orElse("?");
		} catch (RuntimeException e) {
			return "?";
		}
	}

	/** Keeps the patron's name current (it is shown at the bell while they are offline). */
	public static void onJoin(ServerPlayer player) {
		VillageRegistry registry = VillageRegistry.get(player.level().getServer());
		for (VillageRegistry.Village v : registry.all()) {
			if (player.getUUID().equals(v.patron) && !player.getGameProfile().name().equals(v.patronName)) {
				v.patronName = player.getGameProfile().name();
				registry.setDirty();
			}
		}
	}

	/** The patron gives the village a name; everyone sees it on the board, the sidebar and in the books. */
	public static boolean rename(ServerPlayer player, GlobalPos center, String name) {
		String clean = name.strip().replaceAll("[\\p{Cntrl}§]", "");
		if (clean.isEmpty() || clean.length() > 32) return false;
		MinecraftServer server = player.level().getServer();
		Optional<VillageRegistry.Village> village = VillageRegistry.get(server).byCenter(center);
		if (village.isEmpty() || !player.getUUID().equals(village.get().patron)) return false;
		village.get().name = clean;
		VillageRegistry.get(server).setDirty();
		return true;
	}

	private Patron() {
	}
}
