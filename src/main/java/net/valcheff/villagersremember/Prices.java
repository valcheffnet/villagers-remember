package net.valcheff.villagersremember;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * Trade prices by standing (TIERS.md section 5), applied where vanilla works out a player's special prices, so
 * vanilla clients see them as ordinary discounts. Vanilla's own changes (demand, Hero of the Village, gossip from a
 * cure) stay on top.
 */
public final class Prices {
	private Prices() {
	}

	/** Before vanilla reads the gossip: takes back what older versions wrote for this player's errands. */
	public static void beforeVanilla(Villager villager, Player player) {
		if (!(player instanceof ServerPlayer sp)) return;
		ErrandsData.PlayerState state = ErrandService.state(sp);
		Integer major = state.gossipToClean.remove(villager.getUUID());
		if (major == null) return;
		if (major > 0) villager.getGossips().remove(sp.getUUID(), GossipType.MAJOR_POSITIVE, major);
		villager.getGossips().remove(sp.getUUID(), GossipType.MINOR_POSITIVE);
		ErrandsData.get(sp.level().getServer()).setDirty();
	}

	/** After vanilla's discounts: ours, as a share of the base price of the first thing the player gives. */
	public static void apply(Villager villager, Player player) {
		if (!(player instanceof ServerPlayer sp) || !ErrandsConfig.INSTANCE.tradeDiscounts) return;
		ErrandsData.PlayerState state = ErrandService.state(sp);
		double factor = Reputation.priceFactor(state, villager.getUUID(), ErrandGenerator.village(villager));
		if (Math.abs(factor - 1) < 1e-6) return;
		for (MerchantOffer offer : villager.getOffers()) offer.addToSpecialPriceDiff(change(offer.getBaseCostA().getCount(), factor));
	}

	/** How many items fewer (negative) or more a price of {@code base} costs at {@code factor}. */
	public static int change(int base, double factor) {
		return Math.max(1, (int) Math.round(base * factor)) - base;
	}
}
