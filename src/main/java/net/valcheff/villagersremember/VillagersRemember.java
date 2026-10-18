package net.valcheff.villagersremember;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.valcheff.villagersremember.ui.DialogActions;
import net.valcheff.villagersremember.ui.Icons;
import net.valcheff.villagersremember.ui.TextWidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Villagers hand out errands (bring items, hunt monsters) and remember who helped them.
 * Everything runs on the logical server and uses vanilla menus, items and packets only,
 * so players with an unmodded client can take part.
 */
public final class VillagersRemember {
	public static final String MOD_ID = "villagersremember";
	public static final Logger LOG = LoggerFactory.getLogger("Villagers Remember");

	private static MinecraftServer server;

	/** The running server, or null between worlds. */
	public static MinecraftServer server() {
		return server;
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/** Worlds played while the mod was called Village Errands keep their data in data/villageerrands/. */
	private static void moveOldData(MinecraftServer s) {
		Path data = s.getWorldPath(LevelResource.ROOT).resolve("data");
		Path old = data.resolve("villageerrands");
		Path now = data.resolve(MOD_ID);
		if (!Files.isDirectory(old) || Files.exists(now)) return;
		try {
			Files.move(old, now);
			LOG.info("Moved {} to {}", old, now);
		} catch (Exception e) {
			LOG.warn("Could not move {}: {}", old, e.toString());
		}
	}

	/** Loads everything that does not need a world. The loader's entry point calls it first. */
	public static void init() {
		ErrandsConfig.load();
		Lang.load();
		VillagerHeads.load();
		Icons.load();
		TextWidth.load();
		BlayWaystones.register();
	}

	public static void serverStarting(MinecraftServer s) {
		server = s;
		moveOldData(s);
	}

	public static void serverStarted(MinecraftServer s) {
		ErrandPools.reload(s);
	}

	/** Data packs were reloaded (/reload). */
	public static void dataReloaded(MinecraftServer s) {
		ErrandPools.reload(s);
	}

	public static void serverStopped(MinecraftServer s) {
		ErrandGenerator.clearCache();
		server = null;
	}

	/** The end of every server tick, in this order. */
	public static void endTick(MinecraftServer s) {
		VillageRegistry.tick(s);
		Hud.tick(s);
		ActionHooks.tick(s);
		ActionHooks.afterUse(s);
		GroupOrder.tick(s);
		Waystones.tick(s);
	}

	public static void playerJoined(ServerPlayer player) {
		Fame.updateTeam(player);
		Patron.onJoin(player);
		GroupOrder.deliverMail(player);
		Milestones.check(player);
	}

	public static void playerLeft(ServerPlayer player) {
		Hud.forget(player.getUUID());
		DialogActions.forget(player.getUUID());
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		ErrandsCommand.register(dispatcher);
	}

	private VillagersRemember() {
	}
}
