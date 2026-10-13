package net.valcheff.villageerrands;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
 * The weekly order of a village (TIERS.md section 7): one big order for every player, e.g. "Winter stores: 1024
 * food". Anyone brings what they have, as often as they like (any part of the amount counts); the village keeps
 * the total and each player's share. When it is done every helper with at least the minimum share is paid by
 * share: emeralds (to the ender chest), fame and village standing. An order left unfinished at the end of the
 * week pays half, for what was done.
 */
public final class Weekly {
	public enum Kind {
		FOOD(Items.BREAD, 1024), WOOD(Items.OAK_LOG, 512), STONE(Items.COBBLESTONE, 1536), IRON(Items.IRON_INGOT, 192),
		WOOL(Items.STRING, 256), DEFEND(Items.IRON_SWORD, 100);

		public final Item icon;
		public final int base;

		Kind(Item icon, int base) {
			this.icon = icon;
			this.base = base;
		}

		public String key() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	private static final Set<Item> FOOD = Set.of(Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT, Items.BREAD,
		Items.BAKED_POTATO, Items.APPLE, Items.PUMPKIN, Items.MELON_SLICE, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
		Items.COOKED_CHICKEN, Items.COOKED_MUTTON, Items.COOKED_RABBIT, Items.COOKED_COD, Items.COOKED_SALMON, Items.PUMPKIN_PIE,
		Items.COOKIE, Items.SWEET_BERRIES);
	private static final Set<Item> STONE = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.STONE_BRICKS,
		Items.DEEPSLATE_BRICKS, Items.ANDESITE, Items.DIORITE, Items.GRANITE, Items.TUFF);
	private static final int CHECK_INTERVAL = 200;

	public record View(Kind kind, int target, ErrandsData.Weekly order, long daysLeft) {
		public boolean done() {
			return order.progress >= target;
		}
	}

	public static long week(long day) {
		return Math.floorDiv(day, 7L);
	}

	public static Kind kind(String villageKey, long week) {
		long h = villageKey.hashCode() * 31L + week * 0x9E3779B97F4A7C15L;
		h ^= h >>> 29;
		return Kind.values()[(int) Math.floorMod(h, (long) Kind.values().length)];
	}

	public static int target(Kind kind) {
		return Math.max(1, (int) Math.round(kind.base * ErrandsConfig.INSTANCE.weeklyOrderScale));
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

	/** This week's order of the village; last week's is settled first. */
	public static Optional<View> current(MinecraftServer server, VillageRegistry.Village village) {
		if (!ErrandsConfig.INSTANCE.weeklyOrders) return Optional.empty();
		long day = ErrandGenerator.day(server.overworld());
		long week = week(day);
		ErrandsData data = ErrandsData.get(server);
		String key = ErrandsData.villageKey(village.center);
		ErrandsData.Weekly order = data.weekly.get(key);
		if (order != null && order.week < week) {
			settle(server, key, order);
			order = null;
		}
		if (order == null) {
			order = new ErrandsData.Weekly();
			order.week = week;
			data.weekly.put(key, order);
			data.setDirty();
		}
		Kind kind = kind(key, week);
		return Optional.of(new View(kind, target(kind), order, (week + 1) * 7 - day));
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
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (taken >= want) break;
			if (!accepts(view.kind(), stack)) continue;
			int n = Math.min(stack.getCount(), want - taken);
			stack.shrink(n);
			taken += n;
		}
		if (taken > 0) add(server, village, view, player, taken);
		return taken;
	}

	/** A monster killed inside a village with a "Defend the village" order counts for it. */
	public static void kill(ServerPlayer player, LivingEntity dead) {
		if (!(dead instanceof Enemy)) return;
		MinecraftServer server = player.level().getServer();
		Optional<VillageRegistry.Village> village = VillageRegistry.get(server).villageAt(dead.level().dimension(), dead.blockPosition());
		if (village.isEmpty()) return;
		Optional<View> view = current(server, village.get());
		if (view.isEmpty() || view.get().kind() != Kind.DEFEND || view.get().done()) return;
		add(server, village.get(), view.get(), player, 1);
	}

	private static void add(MinecraftServer server, VillageRegistry.Village village, View view, ServerPlayer player, int amount) {
		ErrandsData.Weekly order = view.order();
		order.progress += amount;
		order.contrib.merge(player.getUUID(), amount, Integer::sum);
		ErrandsData.get(server).setDirty();
		if (order.progress >= view.target() && !order.settled) {
			settle(server, ErrandsData.villageKey(village.center), order);
		}
	}

	/**
	 * Pays every helper with at least the minimum share: the whole reward when the order is done, half of what was
	 * earned when the week ended first.
	 */
	public static void settle(MinecraftServer server, String key, ErrandsData.Weekly order) {
		if (order.settled) return;
		order.settled = true;
		ErrandsData data = ErrandsData.get(server);
		data.setDirty();
		Kind kind = kind(key, order.week);
		int target = target(kind);
		if (order.progress <= 0) return;
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		boolean done = order.progress >= target;
		double pay = done ? 1.0 : 0.5 * order.progress / target;
		Optional<VillageRegistry.Village> village = Optional.ofNullable(ErrandsData.parseVillageKey(key))
			.flatMap(c -> VillageRegistry.get(server).byCenter(c));
		for (Map.Entry<UUID, Integer> e : order.contrib.entrySet()) {
			double share = (double) e.getValue() / Math.max(target, order.progress);
			if (share < cfg.weeklyMinShare) continue;
			ErrandsData.PlayerState state = data.player(e.getKey());
			int emeralds = Math.max(1, (int) Math.round(cfg.weeklyRewardEmeralds * share * pay));
			int fame = Math.max(1, (int) Math.round(Fame.WEEKLY_POOL * share * pay));
			state.mail += emeralds;
			if (done) state.weeklyDone++;
			village.ifPresent(v -> Reputation.addVillage(state, Optional.of(v.center), Math.max(1, (int) Math.round(40 * share * pay))));
			ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
			if (player != null) {
				String name = village.map(v -> ErrandText.villageName(player, v.center)).orElse("?");
				player.sendSystemMessage(Lang.tr(player, done ? "weekly.paid" : "weekly.paid_partial",
					Lang.tr(player, "weekly." + kind.key() + ".name"), name, emeralds).withStyle(ChatFormatting.GOLD));
				if (done) {
					Hud.title(player, Lang.tr(player, "weekly.done_title").withStyle(ChatFormatting.GOLD),
						Lang.tr(player, "weekly." + kind.key() + ".name").withStyle(ChatFormatting.YELLOW));
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

	/** Emeralds owed to the player go to their ender chest; what does not fit waits for the next time. */
	public static void deliverMail(ServerPlayer player) {
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
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
		if (sent > 0) player.sendSystemMessage(Lang.tr(player, "weekly.mail", sent).withStyle(ChatFormatting.GREEN));
		if (state.mail > 0) player.sendSystemMessage(Lang.tr(player, "weekly.mail_full", state.mail).withStyle(ChatFormatting.RED));
	}

	/** Settles orders of weeks that have ended, and brings online players what they are owed. */
	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % CHECK_INTERVAL != 0) return;
		ErrandsData data = ErrandsData.get(server);
		long week = week(ErrandGenerator.day(server.overworld()));
		for (Map.Entry<String, ErrandsData.Weekly> e : new ArrayList<>(data.weekly.entrySet())) {
			if (e.getValue().week < week) {
				settle(server, e.getKey(), e.getValue());
				data.weekly.remove(e.getKey());
				data.setDirty();
			}
		}
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (data.player(player.getUUID()).mail > 0 && server.getTickCount() % 6000 == 0) deliverMail(player);
		}
	}

	/** The biggest helpers, most first. */
	public static List<Map.Entry<UUID, Integer>> top(ErrandsData.Weekly order, int n) {
		return order.contrib.entrySet().stream().sorted(Comparator.comparingInt((Map.Entry<UUID, Integer> e) -> -e.getValue()))
			.limit(n).toList();
	}

	/** Item names accepted by an order, for its description. */
	public static String examples(ServerPlayer player, Kind kind) {
		return Lang.raw(Lang.language(player), "weekly." + kind.key() + ".items");
	}

	static Item item(String path) {
		return BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.withDefaultNamespace(path));
	}

	private Weekly() {
	}
}
