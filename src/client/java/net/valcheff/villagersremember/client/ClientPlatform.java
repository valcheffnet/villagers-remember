package net.valcheff.villagersremember.client;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client networking from the loader (Fabric or NeoForge), set by its client entry point. */
public final class ClientPlatform {
	public interface Services {
		/** Whether the server listens on this channel (it has the mod). */
		boolean canSend(CustomPacketPayload.Type<?> type);

		void send(CustomPacketPayload payload);
	}

	private static Services services;

	public static void set(Services s) {
		services = s;
	}

	public static Services get() {
		if (services == null) throw new IllegalStateException("Villagers Remember: the client entry point has not run");
		return services;
	}

	private ClientPlatform() {
	}
}
