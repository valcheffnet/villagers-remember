package net.valcheff.villagersremember.client.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.valcheff.villagersremember.ConfigSync;
import net.valcheff.villagersremember.VillagersRemember;
import net.valcheff.villagersremember.client.ClientPlatform;
import net.valcheff.villagersremember.client.SettingsScreen;

/** NeoForge client entry point. The client half only adds the settings screen; everything else runs on the server. */
@Mod(value = VillagersRemember.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeClientEntry {
	public NeoForgeClientEntry(IEventBus modBus, ModContainer container) {
		ClientPlatform.set(new ClientPlatform.Services() {
			@Override
			public boolean canSend(CustomPacketPayload.Type<?> type) {
				var connection = Minecraft.getInstance().getConnection();
				return connection != null && connection.hasChannel(type);
			}

			@Override
			public void send(CustomPacketPayload payload) {
				ClientPacketDistributor.sendToServer(payload);
			}
		});
		// The Config button in NeoForge's mod list opens the settings.
		container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new SettingsScreen(parent));

		modBus.addListener(RegisterClientPayloadHandlersEvent.class, event ->
			event.register(ConfigSync.Settings.TYPE, (payload, context) -> {
				if (Minecraft.getInstance().gui.screen() instanceof SettingsScreen screen) screen.receive(payload);
			}));
		// /villagersremember opens the settings. Queued, so the chat screen closes first.
		NeoForge.EVENT_BUS.addListener(RegisterClientCommandsEvent.class, event -> event.getDispatcher().register(
			Commands.literal("villagersremember").executes(ctx -> {
				Minecraft mc = Minecraft.getInstance();
				mc.schedule(() -> mc.gui.setScreen(new SettingsScreen(null)));
				return 1;
			})));
	}
}
