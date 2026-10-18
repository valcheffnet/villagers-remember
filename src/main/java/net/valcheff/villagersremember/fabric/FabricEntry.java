package net.valcheff.villagersremember.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.valcheff.villagersremember.ConfigSync;
import net.valcheff.villagersremember.Interactions;
import net.valcheff.villagersremember.Maps;
import net.valcheff.villagersremember.Platform;
import net.valcheff.villagersremember.Tracking;
import net.valcheff.villagersremember.VillagersRemember;

import java.nio.file.Path;

/** Fabric entry point: wires Fabric's events and networking to the shared code. */
public final class FabricEntry implements ModInitializer {
	@Override
	public void onInitialize() {
		Platform.set(new Platform.Services() {
			@Override
			public Path configDir() {
				return FabricLoader.getInstance().getConfigDir();
			}

			@Override
			public boolean isModLoaded(String modId) {
				return FabricLoader.getInstance().isModLoaded(modId);
			}

			@Override
			public void send(ServerPlayer player, CustomPacketPayload payload) {
				ServerPlayNetworking.send(player, payload);
			}
		});
		VillagersRemember.init();

		PayloadTypeRegistry.serverboundPlay().register(ConfigSync.Request.TYPE, ConfigSync.Request.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ConfigSync.Update.TYPE, ConfigSync.Update.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(ConfigSync.Settings.TYPE, ConfigSync.Settings.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(ConfigSync.Request.TYPE, (payload, context) ->
			context.responseSender().sendPacket(ConfigSync.answerRequest(context.player())));
		ServerPlayNetworking.registerGlobalReceiver(ConfigSync.Update.TYPE, (payload, context) ->
			context.responseSender().sendPacket(ConfigSync.answerUpdate(context.player(), payload)));

		ServerLifecycleEvents.SERVER_STARTING.register(VillagersRemember::serverStarting);
		ServerLifecycleEvents.SERVER_STARTED.register(VillagersRemember::serverStarted);
		ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resources, success) -> {
			if (success) VillagersRemember.dataReloaded(server);
		});
		ServerLifecycleEvents.SERVER_STOPPED.register(VillagersRemember::serverStopped);

		UseEntityCallback.EVENT.register(Interactions::onUseEntity);
		// Folded charts from errands unfold when used.
		UseItemCallback.EVENT.register((player, level, hand) ->
			!level.isClientSide() && player instanceof ServerPlayer sp ? Maps.use(sp, hand) : InteractionResult.PASS);
		UseBlockCallback.EVENT.register(Interactions::onUseBlock);
		ServerLivingEntityEvents.AFTER_DEATH.register(Tracking::onDeath);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(Tracking::onDamage);
		ServerLivingEntityEvents.MOB_CONVERSION.register((previous, converted, params) -> Tracking.onConversion(previous, converted));
		ServerTickEvents.END_SERVER_TICK.register(VillagersRemember::endTick);
		ServerPlayConnectionEvents.JOIN.register((handler, sender, s) -> VillagersRemember.playerJoined(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> VillagersRemember.playerLeft(handler.getPlayer()));

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
			VillagersRemember.registerCommands(dispatcher));

		VillagersRemember.LOG.info("Villagers Remember loaded");
	}
}
