package net.valcheff.villagersremember.client.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.valcheff.villagersremember.ConfigSync;
import net.valcheff.villagersremember.client.ClientPlatform;
import net.valcheff.villagersremember.client.SettingsScreen;

/** Fabric client entry point. The client half only adds the settings screen; everything else runs on the server. */
public class FabricClientEntry implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlatform.set(new ClientPlatform.Services() {
			@Override
			public boolean canSend(CustomPacketPayload.Type<?> type) {
				return ClientPlayNetworking.canSend(type);
			}

			@Override
			public void send(CustomPacketPayload payload) {
				ClientPlayNetworking.send(payload);
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(ConfigSync.Settings.TYPE, (payload, context) -> {
			if (context.client().gui.screen() instanceof SettingsScreen screen) screen.receive(payload);
		});
		// /villagersremember opens the settings without Mod Menu. Queued, so the chat screen closes first.
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
			ClientCommands.literal("villagersremember").executes(ctx -> {
				var client = ctx.getSource().getClient();
				client.schedule(() -> client.gui.setScreen(new SettingsScreen(null)));
				return 1;
			})));
	}
}
