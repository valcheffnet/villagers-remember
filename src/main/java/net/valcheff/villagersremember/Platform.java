package net.valcheff.villagersremember;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/** What the mod needs from the loader (Fabric or NeoForge). The loader's entry point sets it before anything else. */
public final class Platform {
	public interface Services {
		Path configDir();

		boolean isModLoaded(String modId);

		/** Sends a payload to a player whose client has the mod (the caller checks that the client asked). */
		void send(ServerPlayer player, CustomPacketPayload payload);
	}

	private static Services services;

	public static void set(Services s) {
		services = s;
	}

	public static Services get() {
		if (services == null) throw new IllegalStateException("Villagers Remember: the loader entry point has not run");
		return services;
	}

	private Platform() {
	}
}
