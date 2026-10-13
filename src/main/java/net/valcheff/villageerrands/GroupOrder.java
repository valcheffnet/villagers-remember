package net.valcheff.villageerrands;

import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The village order (TIERS.md section 7): once a game month (30 game days), one big order for every player, sized
 * by the regular players of the last month, e.g. "Winter stores: 512 food" for each of them. Anyone brings what they have, as often as they like (any part counts); the village
 * keeps the total and each player's share. When it is done every helper with at least the minimum share is paid by
 * share in emeralds (to the ender chest), experience, fame and village standing. No items. An order left unfinished
 * when the month ends pays half, for what was done.
 */
public final class GroupOrder {
	public enum Kind {
		FOOD(Items.BREAD, 512), WOOD(Items.OAK_LOG, 256), STONE(Items.COBBLESTONE, 768), IRON(Items.IRON_INGOT, 96),
		WOOL(Items.STRING, 128), DEFEND(Items.IRON_SWORD, 50);

		public final Item icon;
		/** Size for each regular player. */
		public final int base;

		Kind(Item icon, int base) {
			this.icon = icon;
			this.base = base;
		}

		public String key() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}

		static Kind of(String key) {
			for (Kind k : values()) if (k.key().equals(key)) return k;
			return null;
		}
	}

	private static final Set<Item> FOOD = Set.of(Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BREAD,
		Items.BAKED_POTATO, Items.APPLE, Items.PUMPKIN, Items.MELON_SLICE, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
		Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.PUMPKIN_PIE,
		Items.COOKIE, Items.SWEET_BERRIES);
	private static final Set<Item> STONE = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.STONE_BRICKS,
		Items.DEEPSLATE_BRICKS, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF);
	private static final int CHECK_INTERVAL = 200;
	/** What villagers pay for the goods (vanilla buy trades, emeralds per item), so a helper never loses on a payout. */
	private static final Map<Item, Double> PRICE = Map.ofEntries(
		Map.entry(Items.WHEAT, 1 / 20.0), Map.entry(Items.CARROT, 1 / 22.0), Map.entry(Items.POTATO, 1 / 26.0),
		Map.entry(Items.BEETROOT, 1 / 15.0), Map.entry(Items.BREAD, 3 / 20.0), Map.entry(Items.BAKED_POTATO, 1 / 26.0),
		Map.entry(Items.PUMPKIN, 1 / 6.0), Map.entry(Items.PUMPKIN_PIE, 1 / 6.0 + 0.05), Map.entry(Items.MELON_SLICE, 1 / 36.0),
		Map.entry(Items.SWEET_BERRIES, 0.1), Map.entry(Items.COOKED_BEEF, 0.1), Map.entry(Items.COOKED_PORKCHOP, 1 / 7.0),
		Map.entry(Items.COOKED_CHICKEN, 1 / 14.0), Map.entry(Items.COOKED_MUTTON, 1 / 7.0), Map.entry(Items.COOKED_RABBIT, 0.25),
		Map.entry(Items.COOKED_COD, 1 / 15.0), Map.entry(Items.COOKED_SALMON, 1 / 13.0), Map.entry(Items.STONE, 1 / 20.0),
		Map.entry(Items.GRANITE, 1 / 16.0), Map.entry(Items.ANDESITE, 1 / 16.0), Map.entry(Items.DIORITE, 1 / 16.0),
		Map.entry(Items.STONE_BRICKS, 1 / 20.0), Map.entry(Items.IRON_INGOT, 0.25));
	/** Wool sells at 18 a emerald, whatever its colour. */
	private static final double WOOL_PRICE = 1 / 18.0;
	/** Fame, experience (in emeralds' worth) and village standing a finished order shares out. */
	public static final int FAME_POOL = 80;
	private static final int STANDING_POOL = 80;

	public record View(Kind kind, int target, ErrandsData.VillageOrder order, long daysLeft) {
		public boolean done() {
			return order.progress >= target;
		}
	}

	public static long period(long day) {
		return Math.floorDiv(day, (long) ErrandsConfig.INSTANCE.groupOrderDays);
	}

	public static Kind kind(String villageKey, long period) {
		long h = villageKey.hashCode() * 31L + period * 0x9E3779B97F4A7C15L;
		h ^= h >>> 29;
		return Kind.values()[(int) Math.floorMod(h, (long) Kind.values().length)];
	}

	public static Kind kindOf(String villageKey, ErrandsData.VillageOrder order) {
		Kind k = order.kind != null ? Kind.of(order.kind) : null;
		return k != null ? k : kind(villageKey, order.period);
	}

	/** Size of an order for this many regular players. */
	public static int target(Kind kind, int players) {
		return Math.max(1, (int) Math.round(kind.base * Math.max(1, players) * ErrandsConfig.INSTANCE.groupOrderScale));
	}

	/** Emeralds an order shares out, for this many regular players. */
	public static double pool(int players) {
		return ErrandsConfig.INSTANCE.groupRewardEmeralds * Math.max(1, players);
	}

	/**
	 * Players who play regularly: online on at least {@code groupRegularDays} different game days of the last period.
	 * Two friends get an order for two; a server with a hundred regulars gets one for a hundred. At least one.
	 */
	public static int regularPlayers(MinecraftServer server) {
		long today = ErrandGenerator.day(server.overworld());
		long from = today - ErrandsConfig.INSTANCE.groupOrderDays;
		int n = 0;
		for (ErrandsData.PlayerState state : ErrandsData.get(server).players().values()) {
			if (state.daysSeen.subSet(from, true, today, true).size() >= ErrandsConfig.INSTANCE.groupRegularDays) n++;
		}
		return Math.max(1, Math.max(n, server.getPlayerList().getPlayerCount() > 0 ? 1 : 0));
	}

	/** Marks today for every player online (called now and then). */
	private static void markSeen(MinecraftServer server) {
		long today = ErrandGenerator.day(server.overworld());
		ErrandsData data = ErrandsData.get(server);
		long keep = today - 2L * ErrandsConfig.INSTANCE.groupOrderDays;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ErrandsData.PlayerState state = data.player(player.getUUID());
			if (state.daysSeen.add(today)) {
				state.daysSeen.headSet(keep).clear();
				data.setDirty();
			}
		}
	}

	public static boolean accepts(Kind kind, ItemStack stack) {
		if (stack.isEmpty() || stack.isDamaged() || stack.isEnchanted()) return false;
		return switch (kind) {
			case FOOD -> FOOD.contains(stack.getItem());
			case WOOD -> stack.is(ItemTags.LOGS);
			case STONE -> STONE.contains(stack.getItem());
			case IRON -> stack.is(Items.IRON_INGOT);
			case WOOL -> stack.is(ItemTags.WOOL);
			case DEFEND -> false;
		};
	}

	/** This month's order of the village; last month's is settled first. */
	public static Optional<View> current(MinecraftServer server, VillageRegistry.Village village) {
		if (!ErrandsConfig.INSTANCE.groupOrders) return Optional.empty();
		long day = ErrandGenerator.day(server.overworld());
		long period = period(day);
		ErrandsData data = ErrandsData.get(server);
		String key = ErrandsData.villageKey(village.center);
		ErrandsData.VillageOrder order = data.orders.get(key);
		if (order != null && order.period < period && !order.carried) {
			settle(server, key, order);
			order = null;
		}
		if (order == null) {
			order = new ErrandsData.VillageOrder();
			order.period = period;
			order.kind = kind(key, period).key();
			order.players = regularPlayers(server);
			data.orders.put(key, order);
			data.setDirty();
		} else if (order.carried) {
			// A weekly order from before 1.1 becomes this month's order, with what was brought so far.
			order.carried = false;
			order.period = period;
			data.setDirty();
		}
		if (order.players <= 0) {
			order.players = regularPlayers(server);
			data.setDirty();
		}
		Kind kind = kindOf(key, order);
		long days = ErrandsConfig.INSTANCE.groupOrderDays;
		return Optional.of(new View(kind, target(kind, order.players), order, (period + 1) * days - day));
	}

	/** How many of the order's items the player has with them (as much as is still needed). */
	public static int available(ServerPlayer player, View view) {
		int have = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) if (accepts(view.kind(), stack)) have += stack.getCount();
		return Math.min(have, Math.max(0, view.target() - view.order().progress));
	}

	/** Takes what the player has towards the order; returns how many items went in. */
	public static int contribute(ServerPlayer player, VillageRegistry.Village village) {
		MinecraftServer server = player.level().getServer();
		Optional<View> found = current(server, village);
		if (found.isEmpty() || found.get().done() || found.get().kind() == Kind.DEFEND) return 0;
		View view = found.get();
		int want = available(player, view);
		int taken = 0;
		double value = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (taken >= want) break;
			if (!accepts(view.kind(), stack)) continue;
			int n = Math.min(stack.getCount(), want - taken);
			value += n * price(stack);
			stack.shrink(n);
			taken += n;
		}
		if (taken > 0) {
			add(server, village, view, player.getUUID(), taken);
			view.order().contribValue.merge(player.getUUID(), value, Double::sum);
		}
		return taken;
	}

	private static double price(ItemStack stack) {
		if (stack.is(ItemTags.WOOL)) return WOOL_PRICE;
		return PRICE.getOrDefault(stack.getItem(), 0.0);
	}

	/** A monster killed inside a village with a "Defend the village" order counts for it. */
	public static void kill(ServerPlayer player, LivingEntity dead) {
		if (!(dead instanceof Enemy)) return;
		MinecraftServer server = player.level().getServer();
		Optional<VillageRegistry.Village> village = VillageRegistry.get(server).villageAt(dead.level().dimension(), dead.blockPosition());
		if (village.isEmpty()) return;
		Optional<View> view = current(server, village.get());
		if (view.isEmpty() || view.get().kind() != Kind.DEFEND || view.get().done()) return;
		add(server, village.get(), view.get(), player.getUUID(), 1);
	}

	/** Counts {@code amount} for a player (also used by the operator command). */
	public static void add(MinecraftServer server, VillageRegistry.Village village, View view, UUID player, int amount) {
		ErrandsData.VillageOrder order = view.order();
		int add = Math.min(amount, Math.max(0, view.target() - order.progress));
		if (add <= 0) return;
		order.progress += add;
		order.contrib.merge(player, add, Integer::sum);
		ErrandsData.get(server).setDirty();
		if (order.progress >= view.target() && !order.settled) {
			settle(server, ErrandsData.villageKey(village.center), order);
		}
	}

	/**
	 * Pays every helper with at least the minimum share: the whole reward when the order is done, half of what was
	 * earned when the month ended first.
	 */
	public static void settle(MinecraftServer server, String key, ErrandsData.VillageOrder order) {
		if (order.settled) return;
		order.settled = true;
		ErrandsData data = ErrandsData.get(server);
		data.setDirty();
		Kind kind = kindOf(key, order);
		int target = target(kind, order.players);
		double pool = pool(order.players);
		if (order.progress <= 0) return;
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		boolean done = order.progress >= target;
		double pay = done ? 1.0 : 0.5 * order.progress / target;
		Optional<VillageRegistry.Village> village = Optional.ofNullable(ErrandsData.parseVillageKey(key))
			.flatMap(c -> VillageRegistry.get(server).byCenter(c));
		for (Map.Entry<UUID, Integer> e : order.contrib.entrySet()) {
			double share = (double) e.getValue() / Math.max(target, order.progress);
			if (share < cfg.groupMinShare) continue;
			ErrandsData.PlayerState state = data.player(e.getKey());
			// By share, but never less than the premium over what villagers would have paid for the goods.
			double goods = order.contribValue.getOrDefault(e.getKey(), 0.0);
			int emeralds = Math.max(1, (int) Math.round(Math.max(pool * share * pay, goods * cfg.marketPremium)));
			int xp = Math.max(1, (int) Math.round(pool * share * pay * cfg.xpPerEmerald));
			int fame = Math.max(1, (int) Math.round(FAME_POOL * share * pay));
			state.mail += emeralds;
			state.mailXp += xp;
			if (done) state.weeklyDone++;
			village.ifPresent(v -> Reputation.addVillage(state, Optional.of(v.center), Math.max(1, (int) Math.round(STANDING_POOL * share * pay))));
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player != null) {
				String name = village.map(v -> ErrandText.villageName(player, v.center)).orElse("?");
				player.sendSystemMessage(Lang.tr(player, done ? "group.paid" : "group.paid_partial",
					Lang.tr(player, "group." + kind.key() + ".name"), name, emeralds).withStyle(ChatFormatting.GOLD));
				if (done) {
					Hud.title(player, Lang.tr(player, "group.done_title").withStyle(ChatFormatting.GOLD),
						Lang.tr(player, "group." + kind.key() + ".name").withStyle(ChatFormatting.YELLOW));
					player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
						SoundSource.PLAYERS, 0.8f, 1.0f);
				}
				Fame.add(player, fame);
				deliverMail(player);
				Milestones.check(player);
			} else {
				state.fame += fame;
			}
		}
	}

	/** Emeralds owed to the player go to their ender chest (what does not fit waits), owed experience to them. */
	public static void deliverMail(ServerPlayer player) {
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
		if (state.mailXp > 0) {
			player.giveExperiencePoints(state.mailXp);
			state.mailXp = 0;
			data.setDirty();
		}
		if (state.mail <= 0) return;
		int sent = 0;
		var chest = player.getEnderChestInventory();
		while (state.mail > 0) {
			int n = Math.min(64, state.mail);
			ItemStack left = chest.addItem(new ItemStack(Items.EMERALD, n));
			int moved = n - left.getCount();
			state.mail -= moved;
			sent += moved;
			if (moved < n) break;
		}
		data.setDirty();
		if (sent > 0) player.sendSystemMessage(Lang.tr(player, "group.mail", sent).withStyle(ChatFormatting.GREEN));
		if (state.mail > 0) player.sendSystemMessage(Lang.tr(player, "group.mail_full", state.mail).withStyle(ChatFormatting.RED));
	}

	/** Settles orders of months that have ended, and brings online players what they are owed. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_INTERVAL != 0) return;
		markSeen(server);
		ErrandsData data = ErrandsData.get(server);
		long period = period(ErrandGenerator.day(server.overworld()));
		for (Map.Entry<String, ErrandsData.VillageOrder> e : new ArrayList<>(data.orders.entrySet())) {
			if (e.getValue().period < period && !e.getValue().carried) {
				settle(server, e.getKey(), e.getValue());
				data.orders.remove(e.getKey());
				data.setDirty();
			}
		}
		if (server.getTickCount() % 6000 != 0) return;
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			ErrandsData.PlayerState state = data.player(player.getUUID());
			if (state.mail > 0 || state.mailXp > 0) deliverMail(player);
		}
	}

	/** The biggest helpers, most first. */
	public static List<Map.Entry<UUID, Integer>> top(ErrandsData.VillageOrder order, int n) {
		return order.contrib.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<UUID, Integer> e) -> -e.getValue()))
			.limit(n).toList();
	}

	/** Item names accepted by an order, for its description. */
	public static String examples(ServerPlayer player, Kind kind) {
		return Lang.raw(Lang.language(player), "group." + kind.key() + ".items");
	}

	private GroupOrder() {
	}
}
