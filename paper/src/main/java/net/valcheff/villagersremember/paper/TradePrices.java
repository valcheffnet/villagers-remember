package net.valcheff.villagersremember.paper;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.trading.MerchantOffers;
import net.valcheff.villagersremember.Prices;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Prices by standing on Paper ({@code VillagerPricesMixin} on Fabric and NeoForge). They are added when the trading
 * screen opens, before vanilla sends the offers. Vanilla works its special prices out again while a player trades
 * (restocks, gossip) and Paper has no event for it, so at the end of every tick a price that is no longer the one
 * left here means vanilla started over: ours are added again and the offers sent anew.
 */
final class TradePrices {
	private record Session(Villager villager, MerchantMenu menu, int[] expected) {
	}

	private final Map<ServerPlayer, Session> open = new HashMap<>();

	void opened(ServerPlayer player, MerchantMenu menu, Villager villager) {
		Prices.apply(villager, player);
		open.put(player, new Session(villager, menu, diffs(villager.getOffers())));
	}

	void tick() {
		Iterator<Map.Entry<ServerPlayer, Session>> it = open.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<ServerPlayer, Session> e = it.next();
			ServerPlayer player = e.getKey();
			Session s = e.getValue();
			if (player.isRemoved() || player.containerMenu != s.menu) {
				it.remove();
				continue;
			}
			MerchantOffers offers = s.villager.getOffers();
			int[] now = diffs(offers);
			if (Arrays.equals(now, s.expected)) continue;
			// A level-up inside the trade adds the next level's offers and leaves the old prices alone, as on Fabric.
			if (now.length > s.expected.length && Arrays.equals(now, 0, s.expected.length, s.expected, 0, s.expected.length)) {
				e.setValue(new Session(s.villager, s.menu, now));
				continue;
			}
			Prices.apply(s.villager, player);
			e.setValue(new Session(s.villager, s.menu, diffs(offers)));
			player.sendMerchantOffers(s.menu.containerId, offers, s.villager.getVillagerData().level(),
				s.villager.getVillagerXp(), s.villager.showProgressBar(), s.villager.canRestock());
		}
	}

	private static int[] diffs(MerchantOffers offers) {
		int[] d = new int[offers.size()];
		for (int i = 0; i < d.length; i++) d[i] = offers.get(i).getSpecialPriceDiff();
		return d;
	}
}
