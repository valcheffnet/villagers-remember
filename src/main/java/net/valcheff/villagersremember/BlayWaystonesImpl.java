package net.valcheff.villagersremember;

import net.blay09.mods.waystones.api.MutableWaystone;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneOrigin;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.blay09.mods.waystones.api.event.GenerateWaystoneNameEvent;
import net.blay09.mods.waystones.core.WaystoneSyncManager;
import net.blay09.mods.waystones.store.SavedDataWaystonesStore;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.Optional;

/**
 * Waystones gives a generated waystone its name when a player first activates it, through GenerateWaystoneNameEvent.
 * A village's waystone gets the village's English name there (or the name its patron gave it). Waystones that already
 * have a name, and ones a player renamed later, are left alone. Only loaded when the mod is installed.
 */
final class BlayWaystonesImpl {
	static void register() {
		GenerateWaystoneNameEvent.EVENT.register(event -> {
			if (!ErrandsConfig.INSTANCE.waystoneNames) return;
			Waystone waystone = event.getWaystone();
			if (waystone.getOrigin() != WaystoneOrigin.VILLAGE) return;
			MinecraftServer server = VillagersRemember.server();
			if (server == null) return;
			VillageRegistry registry = VillageRegistry.get(server);
			Optional<VillageRegistry.Village> village = registry.villageAt(waystone.getDimension(), waystone.getPos());
			if (village.isEmpty()) return;
			String name = Waystones.name(registry, village.get()).getString();
			event.setName(Component.literal(name));
			village.get().waystone = waystone.getWaystoneUid();
			village.get().waystoneName = name;
			registry.setDirty();
		});
	}

	/** A waystone named here still has that name (no player renamed it) but the village has a new one: renamed too. */
	static void sync(MinecraftServer server) {
		VillageRegistry registry = VillageRegistry.get(server);
		for (VillageRegistry.Village village : registry.all()) {
			if (village.waystone == null) continue;
			String wanted = Waystones.name(registry, village).getString();
			if (wanted.equals(village.waystoneName)) continue;
			Optional<Waystone> found = WaystonesAPI.getWaystone(server, village.waystone);
			if (found.isEmpty() || !(found.get() instanceof MutableWaystone mutable)) continue;
			if (!found.get().getName().getString().equals(village.waystoneName)) {
				// A player gave it their own name: it is theirs now.
				village.waystone = null;
				village.waystoneName = null;
				registry.setDirty();
				continue;
			}
			mutable.setName(Component.literal(wanted));
			village.waystoneName = wanted;
			registry.setDirty();
			SavedDataWaystonesStore.get(server).setDirty();
			WaystoneSyncManager.sendWaystoneUpdateToAll(server, found.get());
		}
	}
}
