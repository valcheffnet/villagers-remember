package net.valcheff.villagersremember;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

/**
 * Optional support for the Waystones mod (BlayTheNinth): a village's waystone is named after the village. This class
 * has no Waystones types, so it loads without the mod; {@link BlayWaystonesImpl} does the work when the mod is there.
 * If a later version of the mod changes what is used here, the support turns itself off and says so in the log.
 */
public final class BlayWaystones {
	public static final boolean LOADED = FabricLoader.getInstance().isModLoaded("waystones");
	private static boolean broken;

	public static void register() {
		if (!LOADED) return;
		try {
			BlayWaystonesImpl.register();
		} catch (LinkageError | RuntimeException e) {
			off(e);
		}
	}

	/** Follows the village's name (a patron renaming it) on waystones named here. */
	public static void sync(MinecraftServer server) {
		if (!LOADED || broken) return;
		try {
			BlayWaystonesImpl.sync(server);
		} catch (LinkageError | RuntimeException e) {
			off(e);
		}
	}

	static void off(Throwable e) {
		broken = true;
		VillagersRemember.LOG.warn("Waystones support turned off, this version of Waystones is not supported: {}", e.toString());
	}
}
