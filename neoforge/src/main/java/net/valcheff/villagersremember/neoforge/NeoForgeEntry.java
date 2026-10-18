package net.valcheff.villagersremember.neoforge;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.valcheff.villagersremember.ConfigSync;
import net.valcheff.villagersremember.Interactions;
import net.valcheff.villagersremember.Maps;
import net.valcheff.villagersremember.Platform;
import net.valcheff.villagersremember.Tracking;
import net.valcheff.villagersremember.VillagersRemember;

import java.nio.file.Path;

/** NeoForge entry point: wires NeoForge's events and networking to the shared code. */
@Mod(VillagersRemember.MOD_ID)
public final class NeoForgeEntry {
	public NeoForgeEntry(IEventBus modBus) {
		Platform.set(new Platform.Services() {
			@Override
			public Path configDir() {
				return FMLPaths.CONFIGDIR.get();
			}

			@Override
			public boolean isModLoaded(String modId) {
				return FMLLoader.getCurrent().getLoadingModList().getModFileById(modId) != null;
			}

			@Override
			public void send(ServerPlayer player, CustomPacketPayload payload) {
				PacketDistributor.sendToPlayer(player, payload);
			}
		});
		VillagersRemember.init();

		// Optional channels: a vanilla client (or one without the mod) joins without them.
		modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
			PayloadRegistrar registrar = event.registrar("1").optional();
			registrar.playToServer(ConfigSync.Request.TYPE, ConfigSync.Request.CODEC, (payload, context) -> {
				if (context.player() instanceof ServerPlayer player) context.reply(ConfigSync.answerRequest(player));
			});
			registrar.playToServer(ConfigSync.Update.TYPE, ConfigSync.Update.CODEC, (payload, context) -> {
				if (context.player() instanceof ServerPlayer player) context.reply(ConfigSync.answerUpdate(player, payload));
			});
			registrar.playToClient(ConfigSync.Settings.TYPE, ConfigSync.Settings.CODEC);
		});

		var bus = NeoForge.EVENT_BUS;
		bus.addListener(ServerStartingEvent.class, e -> VillagersRemember.serverStarting(e.getServer()));
		bus.addListener(ServerStartedEvent.class, e -> VillagersRemember.serverStarted(e.getServer()));
		bus.addListener(ServerStoppedEvent.class, e -> VillagersRemember.serverStopped(e.getServer()));
		// Sent to everyone after /reload (no single player), and to one player when they join.
		bus.addListener(OnDatapackSyncEvent.class, e -> {
			if (e.getPlayer() == null) VillagersRemember.dataReloaded(e.getPlayerList().getServer());
		});

		bus.addListener(PlayerInteractEvent.EntityInteract.class, e -> {
			InteractionResult result = Interactions.onUseEntity(e.getEntity(), e.getLevel(), e.getHand(), e.getTarget(), null);
			if (result != InteractionResult.PASS) {
				e.setCancellationResult(result);
				e.setCanceled(true);
			}
		});
		bus.addListener(PlayerInteractEvent.RightClickBlock.class, e -> {
			InteractionResult result = Interactions.onUseBlock(e.getEntity(), e.getLevel(), e.getHand(), e.getHitVec());
			if (result != InteractionResult.PASS) {
				e.setCancellationResult(result);
				e.setCanceled(true);
			}
		});
		// Folded charts from errands unfold when used.
		bus.addListener(PlayerInteractEvent.RightClickItem.class, e -> {
			if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer player)) return;
			InteractionResult result = Maps.use(player, e.getHand());
			if (result != InteractionResult.PASS) {
				e.setCancellationResult(result);
				e.setCanceled(true);
			}
		});
		// Lowest priority and only when nobody cancelled it: the entity really dies.
		bus.addListener(EventPriority.LOWEST, false, LivingDeathEvent.class, e -> Tracking.onDeath(e.getEntity(), e.getSource()));
		bus.addListener(LivingDamageEvent.Post.class, e ->
			Tracking.onDamage(e.getEntity(), e.getSource(), e.getOriginalDamage(), e.getInflictedDamage(), e.getBlockedDamage() > 0));
		bus.addListener(LivingConversionEvent.Post.class, e -> {
			if (e.getEntity() instanceof Mob previous && e.getOutcome() instanceof Mob converted) Tracking.onConversion(previous, converted);
		});
		bus.addListener(ServerTickEvent.Post.class, e -> VillagersRemember.endTick(e.getServer()));
		bus.addListener(PlayerEvent.PlayerLoggedInEvent.class, e -> {
			if (e.getEntity() instanceof ServerPlayer player) VillagersRemember.playerJoined(player);
		});
		bus.addListener(PlayerEvent.PlayerLoggedOutEvent.class, e -> {
			if (e.getEntity() instanceof ServerPlayer player) VillagersRemember.playerLeft(player);
		});
		bus.addListener(RegisterCommandsEvent.class, e -> VillagersRemember.registerCommands(e.getDispatcher()));

		VillagersRemember.LOG.info("Villagers Remember loaded");
	}
}
