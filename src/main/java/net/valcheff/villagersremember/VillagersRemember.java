package net.valcheff.villagersremember;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.valcheff.villagersremember.ui.DialogActions;
import net.valcheff.villagersremember.ui.Icons;
import net.valcheff.villagersremember.ui.TextWidth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Villagers hand out errands (bring items, hunt monsters) and remember who helped them.
 * Everything runs on the logical server and uses vanilla menus, items and packets only,
 * so players with an unmodded client can take part.
 */
public final class VillagersRemember implements ModInitializer {
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
		java.nio.file.Path data = s.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data");
		java.nio.file.Path old = data.resolve("villageerrands");
		java.nio.file.Path now = data.resolve(MOD_ID);
		if (!java.nio.file.Files.isDirectory(old) || java.nio.file.Files.exists(now)) return;
		try {
			java.nio.file.Files.move(old, now);
			LOG.info("Moved {} to {}", old, now);
		} catch (Exception e) {
			LOG.warn("Could not move {}: {}", old, e.toString());
		}
	}

	@Override
	public void onInitialize() {
		ErrandsConfig.load();
		Lang.load();
		VillagerHeads.load();
		Icons.load();
		TextWidth.load();
		ConfigSync.register();
		BlayWaystones.register();

		ServerLifecycleEvents.SERVER_STARTING.register(s -> {
			server = s;
			moveOldData(s);
		});
		ServerLifecycleEvents.SERVER_STARTED.register(ErrandPools::reload);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) ErrandPools.reload(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
			ErrandGenerator.clearCache();
			server = null;
		});

		UseEntityCallback.EVENT.register(Interactions::onUseEntity);
		// Folded charts from errands unfold when used.
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) ->
			!level.isClientSide() && player instanceof net.minecraft.server.level.ServerPlayer sp ? Maps.use(sp, hand)
				: net.minecraft.world.InteractionResult.PASS);
		UseBlockCallback.EVENT.register(Interactions::onUseBlock);
		ServerLivingEntityEvents.AFTER_DEATH.register(Tracking::onDeath);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(Tracking::onDamage);
		ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, params) -> Tracking.onConversion(previous, converted));
		ServerTickEvents.END_SERVER_TICK.register(VillageRegistry::tick);
		ServerTickEvents.END_SERVER_TICK.register(Hud::tick);
		ServerTickEvents.END_SERVER_TICK.register(ActionHooks::tick);
		ServerTickEvents.END_SERVER_TICK.register(GroupOrder::tick);
		ServerTickEvents.END_SERVER_TICK.register(Waystones::tick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> {
			var player = handler.getPlayer();
			Fame.updateTeam(player);
			Patron.onJoin(player);
			GroupOrder.deliverMail(player);
			Milestones.check(player);
		});
		PlayerBlockBreakEvents.AFTER.register(ActionHooks::blockBroken);
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			Hud.forget(handler.getPlayer().getUUID());
			DialogActions.forget(handler.getPlayer().getUUID());
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			ErrandsCommand.register(dispatcher));

		LOG.info("Villagers Remember loaded");
	}
}
