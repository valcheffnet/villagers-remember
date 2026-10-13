package net.valcheff.villageerrands;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

/**
 * Fame across all villages (TIERS.md section 7): points from errands (by pack), trust trials, contracts, weekly
 * orders, discovered villages and milestones. Titles go in front of the player's name in chat and TAB through a
 * scoreboard team, which vanilla clients show without any mod.
 */
public final class Fame {
	/** Fame needed for each title: none, Helper, Benefactor, Legend. */
	public static final int[] TITLES = {0, 100, 500, 1500};
	public static final int MAX_TITLE = TITLES.length - 1;
	public static final int[] COLORS = {0xAAAAAA, 0x55FF55, 0x55AAFF, 0xFF8000};
	private static final String TEAM = "ve_fame_";

	/** Points for a completed errand of pack 1..5, a passed trust trial, a contract, a discovered village... */
	public static final int TRIAL = 10;
	public static final int CONTRACT = 15;
	public static final int DISCOVER = 5;
	public static final int HERO = 50;
	public static final int PATRON = 100;

	public static int title(int fame) {
		int t = 0;
		for (int i = 0; i < TITLES.length; i++) if (fame >= TITLES[i]) t = i;
		return t;
	}

	public static int title(ErrandsData.PlayerState state) {
		return title(state.fame);
	}

	public static MutableComponent titleName(ServerPlayer player, int title) {
		return Lang.tr(player, "fame.title." + title).withColor(COLORS[Math.clamp(title, 0, MAX_TITLE)]);
	}

	/** Adds fame; a new title is announced to the player and shown in front of their name. */
	public static void add(ServerPlayer player, int amount) {
		if (amount <= 0) return;
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
		int before = title(state);
		state.fame += amount;
		data.setDirty();
		int after = title(state);
		if (after > before) {
			Hud.title(player, Lang.tr(player, "fame.new_title").withStyle(ChatFormatting.GOLD), titleName(player, after));
			player.sendSystemMessage(Lang.tr(player, "fame.new_title_chat", titleName(player, after)).withStyle(ChatFormatting.GOLD));
			player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
				SoundSource.PLAYERS, 0.8f, 1.0f);
			updateTeam(player);
		}
		Milestones.check(player);
	}

	/**
	 * Puts the player in the team of their title, whose prefix is the title. A player already in a team of someone
	 * else's (another plugin, a server's own teams) is left alone.
	 */
	public static void updateTeam(ServerPlayer player) {
		Scoreboard scoreboard = player.level().getServer().getScoreboard();
		String name = player.getScoreboardName();
		PlayerTeam current = scoreboard.getPlayersTeam(name);
		boolean ours = current == null || current.getName().startsWith(TEAM);
		if (!ours) return;
		int title = ErrandsConfig.INSTANCE.fameTitles ? title(ErrandService.state(player)) : 0;
		if (title == 0) {
			if (current != null) scoreboard.removePlayerFromTeam(name, current);
			return;
		}
		PlayerTeam team = scoreboard.getPlayerTeam(TEAM + title);
		if (team == null) {
			team = scoreboard.addPlayerTeam(TEAM + title);
			team.setDisplayName(Component.literal("Village Errands: " + Lang.raw("en_us", "fame.title." + title)));
		}
		// Vanilla clients have no translation for the key and use the English fallback; a client with the mod
		// installed shows its own language.
		Component prefix = Component.empty()
			.append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
			.append(Component.translatableWithFallback("villageerrands.fame.prefix." + title, Lang.raw("en_us", "fame.title." + title))
				.withColor(COLORS[title]))
			.append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY));
		if (!prefix.equals(team.getPlayerPrefix())) team.setPlayerPrefix(prefix);
		if (current != team) scoreboard.addPlayerToTeam(name, team);
	}

	/** What a wandering trader brings for a Legend: rare things, one of each, for emeralds. */
	private static final Object[][] LEGEND_GOODS = {
		{net.minecraft.world.item.Items.ENCHANTED_GOLDEN_APPLE, 1, 48},
		{net.minecraft.world.item.Items.TOTEM_OF_UNDYING, 1, 40},
		{net.minecraft.world.item.Items.HEART_OF_THE_SEA, 1, 32},
		{net.minecraft.world.item.Items.SNIFFER_EGG, 1, 24},
		{net.minecraft.world.item.Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1, 36},
		{net.minecraft.world.item.Items.TRIDENT, 1, 40},
		{net.minecraft.world.item.Items.ECHO_SHARD, 4, 24},
		{net.minecraft.world.item.Items.DRAGON_BREATH, 4, 20},
	};
	public static final String LEGEND_TAG = "villageerrands_legend_goods";
	private static final int LEGEND_OFFERS = 3;

	/**
	 * A Legend who talks to a wandering trader finds three rare offers added (once per trader; everyone can buy
	 * them, the trader keeps them until it leaves).
	 */
	public static void legendGoods(ServerPlayer player, net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader trader) {
		if (title(ErrandService.state(player)) < MAX_TITLE || trader.entityTags().contains(LEGEND_TAG)) return;
		trader.addTag(LEGEND_TAG);
		java.util.List<Object[]> goods = new java.util.ArrayList<>(java.util.List.of(LEGEND_GOODS));
		java.util.Collections.shuffle(goods, new java.util.Random(trader.getUUID().getLeastSignificantBits()));
		for (Object[] g : goods.subList(0, LEGEND_OFFERS)) {
			net.minecraft.world.item.Item item = (net.minecraft.world.item.Item) g[0];
			trader.getOffers().add(new net.minecraft.world.item.trading.MerchantOffer(
				new net.minecraft.world.item.trading.ItemCost(net.minecraft.world.item.Items.EMERALD, (Integer) g[2]),
				new net.minecraft.world.item.ItemStack(item, (Integer) g[1]), 1, 1, 0.0f));
		}
		player.sendSystemMessage(Lang.tr(player, "fame.legend_goods").withColor(COLORS[MAX_TITLE]));
	}

	private Fame() {
	}
}
