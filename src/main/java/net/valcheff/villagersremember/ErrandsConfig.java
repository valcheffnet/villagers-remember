package net.valcheff.villagersremember;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** {@code config/villagersremember.json}. Missing keys keep their defaults; the file is rewritten with all keys. */
public final class ErrandsConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("villagersremember.json");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static ErrandsConfig INSTANCE = new ErrandsConfig();

	/** "auto" uses each player's client language; or force one, e.g. "en_us" / "bg_bg". */
	public String language = "auto";
	/** Errands a villager offers per day, by villager level 1..5. */
	public int[] errandsPerLevel = {1, 2, 2, 3, 3};
	/** Extra errand per villager once the player is at least "Respected" in that village. */
	public boolean respectedBonusErrand = true;
	/** How many errands one player may have accepted at the same time. */
	public int maxActiveErrands = 5;
	/** Settings changed meaning in version 2 (effort-based rewards); older files get the new defaults for those. */
	public int configVersion = 4;
	/**
	 * Errands are sized in minutes of work (TIERS.md section 4); the pay is minutes x this, in emeralds' worth (split
	 * into emeralds and experience), by pack T1..T5. T1 pays emeralds or experience. Repeatable errands give no items.
	 */
	public double[] tierEmeraldsPerMinute = {0.6, 0.8, 1.0, 1.3, 1.6};
	/** An errand's emeralds are at least this many times what villagers would pay for the goods it asks for. */
	public double marketPremium = 1.5;
	/** Treasury: emeralds a day one working resident sets aside, by the player's tier with them (Stranger..Best friend). */
	public double[] treasuryShare = {0.0, 0.25, 0.5, 0.8, 1.2};
	/** Treasury: multiplier by the player's village tier (Stranger..Hero). */
	public double[] treasuryVillageMultiplier = {0.5, 0.75, 1.0, 1.25, 1.5};
	/** Treasury: most emeralds one village pays a day, and how many days of income it keeps. */
	public double treasuryDailyCap = 64;
	public int treasuryKeepDays = 3;
	/** Network bonus on the whole income for every village where the player is at least Friend, and its maximum. */
	public double networkBonusPerVillage = 0.05;
	public double networkBonusMax = 0.25;
	/** Trust trial: how much more it asks than an errand of the pack it unlocks, and how much more it pays. */
	public double trialCountFactor = 1.5;
	public double trialRewardMultiplier = 2.0;
	/**
	 * Contracts: one big job a game week from each Best friend, three tasks of the top packs together about this
	 * many minutes, paid at the T5 rate x the multiplier, emeralds up to the cap.
	 */
	public boolean contracts = true;
	public double contractMinutes = 90;
	public double contractRewardMultiplier = 1.2;
	public int contractMaxEmeralds = 160;
	/** Contracts completed for one profession that bring its legendary item. */
	public int contractsForLegend = 3;
	/** Fame titles (Helper, Benefactor, Legend) in front of player names in chat and TAB. */
	public boolean fameTitles = true;
	/** A patron of a village trades there at this discount. */
	public double patronDiscount = 0.45;
	/** Village orders: one huge order per village for all players, every this many game days (30: a game month). */
	public boolean groupOrders = true;
	public int groupOrderDays = 30;
	/**
	 * Size of village orders for each regular player (1.0: 512 food, 256 logs, 768 stone, 96 iron, 128 wool,
	 * 50 monsters). A regular player was online on at least groupRegularDays game days of the last period.
	 */
	public double groupOrderScale = 1.0;
	public int groupRegularDays = 3;
	/** Emeralds a finished village order pays out for each regular player, shared by contribution (plus as much
	 * again in experience, fame and standing); a helper needs at least the minimum share to be paid. */
	public double groupRewardEmeralds = 80;
	public double groupMinShare = 0.02;
	/**
	 * Look of the folded charts given for places to visit: "globe" (the globe banner pattern, any client), "dnt"
	 * (Dungeons and Taverns' rolled chart, for clients with that mod) or "auto" (dnt when that mod is installed).
	 */
	public String chartLook = "auto";
	/**
	 * False: errands, contracts and village orders pay no emeralds, and villages stop putting emeralds into the
	 * treasury; what is already there can still be sent. Experience and standing are paid as usual. /errands emeralds on|off.
	 */
	public boolean emeraldRewards = false;
	/** Experience from errands, contracts and village orders, times their usual amount. /errands xp <multiplier>. */
	public double xpMultiplier = 1.0;
	/** Experience points granted per emerald of reward budget. */
	public double xpPerEmerald = 6.0;
	public int maxEmeraldsPerErrand = 64;
	/**
	 * Right-clicking a villager opens a greeting with Trade and Errands; sneak + right-click trades straight away.
	 * When false, right-click trades as in vanilla and sneak + right-click opens the villager's errands.
	 */
	public boolean talkMenu = true;
	/** Right-clicking a village bell opens the errand board (sneak + right-click still rings it). */
	public boolean bellOpensBoard = true;
	/** How far from the bell villagers of that village are searched for. */
	public int villageRadius = 64;
	/** "!" above villagers with an errand for you, "?" when you can hand one in (only you see them). */
	public boolean showMarkers = true;
	public int markerRange = 24;
	/**
	 * Villager names for each player, in their language, as if named with a name tag (Jade and similar mods show
	 * them too): "always" above the head, "look" only when looking at the villager, "off".
	 */
	public String villagerNames = "always";
	public int villagerNameRange = 32;
	/** Errand tracker in the sidebar (replaces any scoreboard sidebar for players with accepted errands). */
	public boolean showTracker = true;
	/**
	 * Trade prices follow the player's standing: discount = per villager tier x tier with the villager + per village
	 * tier x village tier, up to maxDiscount; a player with negative points pays more (1 % a point, up to
	 * maxSurcharge). Applies to the first thing the player gives; vanilla's own price changes stay on top.
	 */
	public boolean tradeDiscounts = true;
	public double discountPerVillagerTier = 0.04;
	public double discountPerVillageTier = 0.06;
	public double maxDiscount = 0.40;
	public double surchargePerNegativePoint = 0.01;
	public double maxSurcharge = 0.50;
	/** Reputation lost in a village (and with the villager) for hitting / killing a villager. */
	public int hitPenalty = 2;
	public int killPenalty = 25;

	public static void load() {
		// The mod was called Village Errands before 1.0.0 went public; keep a config made under the old name.
		Path old = FILE.resolveSibling("villageerrands.json");
		if (!Files.exists(FILE) && Files.exists(old)) {
			try {
				Files.move(old, FILE);
			} catch (Exception e) {
				VillagersRemember.LOG.warn("Could not rename {}: {}", old, e.toString());
			}
		}
		ErrandsConfig loaded = null;
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, ErrandsConfig.class);
			} catch (Exception e) {
				VillagersRemember.LOG.warn("Could not read {}, using defaults: {}", FILE, e.toString());
			}
		}
		INSTANCE = loaded != null ? loaded.sanitized() : new ErrandsConfig();
		save();
	}

	public static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(INSTANCE, writer);
			}
		} catch (IOException e) {
			VillagersRemember.LOG.warn("Could not write {}: {}", FILE, e.toString());
		}
	}

	private ErrandsConfig sanitized() {
		if (language == null || language.isBlank()) language = "auto";
		if (chartLook == null || !List.of("auto", "globe", "dnt").contains(chartLook.toLowerCase(java.util.Locale.ROOT))) chartLook = "auto";
		if (errandsPerLevel == null || errandsPerLevel.length != 5) errandsPerLevel = new ErrandsConfig().errandsPerLevel;
		for (int i = 0; i < 5; i++) errandsPerLevel[i] = Math.clamp(errandsPerLevel[i], 0, 6);
		maxActiveErrands = Math.clamp(maxActiveErrands, 1, 27);
		ErrandsConfig defaults = new ErrandsConfig();
		if (configVersion < 2) {
			// Version 1 sized rewards by the value of goods; these keys now mean something else.
			contractRewardMultiplier = defaults.contractRewardMultiplier;
		}
		if (configVersion < 3) {
			// Version 3: village orders are sized and paid per regular player.
			groupRewardEmeralds = defaults.groupRewardEmeralds;
			groupOrderScale = defaults.groupOrderScale;
		}
		if (configVersion < 4) {
			// Version 4: emerald rewards are off unless the server turns them on.
			emeraldRewards = defaults.emeraldRewards;
		}
		configVersion = defaults.configVersion;
		if (tierEmeraldsPerMinute == null || tierEmeraldsPerMinute.length != 5) tierEmeraldsPerMinute = defaults.tierEmeraldsPerMinute;
		if (treasuryShare == null || treasuryShare.length != 5) treasuryShare = defaults.treasuryShare;
		if (treasuryVillageMultiplier == null || treasuryVillageMultiplier.length != 5) treasuryVillageMultiplier = defaults.treasuryVillageMultiplier;
		treasuryDailyCap = Math.clamp(treasuryDailyCap, 0.0, 10000.0);
		treasuryKeepDays = Math.clamp(treasuryKeepDays, 1, 365);
		networkBonusPerVillage = Math.clamp(networkBonusPerVillage, 0.0, 10.0);
		networkBonusMax = Math.clamp(networkBonusMax, 0.0, 10.0);
		for (int i = 0; i < 5; i++) {
			tierEmeraldsPerMinute[i] = Math.clamp(tierEmeraldsPerMinute[i], 0.0, 100.0);
		}
		xpPerEmerald = Math.clamp(xpPerEmerald, 0.0, 1000.0);
		maxEmeraldsPerErrand = Math.clamp(maxEmeraldsPerErrand, 0, 64 * 9);
		marketPremium = Math.clamp(marketPremium, 0.0, 100.0);
		discountPerVillagerTier = Math.clamp(discountPerVillagerTier, 0.0, 1.0);
		discountPerVillageTier = Math.clamp(discountPerVillageTier, 0.0, 1.0);
		maxDiscount = Math.clamp(maxDiscount, 0.0, 0.95);
		xpMultiplier = Math.clamp(xpMultiplier, 0.0, 10.0);
		surchargePerNegativePoint = Math.clamp(surchargePerNegativePoint, 0.0, 1.0);
		maxSurcharge = Math.clamp(maxSurcharge, 0.0, 10.0);
		contractMinutes = Math.clamp(contractMinutes, 1.0, 10000.0);
		contractRewardMultiplier = Math.clamp(contractRewardMultiplier, 0.0, 100.0);
		contractMaxEmeralds = Math.clamp(contractMaxEmeralds, 0, 64 * 27);
		contractsForLegend = Math.clamp(contractsForLegend, 1, 100);
		patronDiscount = Math.clamp(patronDiscount, 0.0, 0.95);
		groupOrderDays = Math.clamp(groupOrderDays, 1, 3650);
		groupRegularDays = Math.clamp(groupRegularDays, 1, 3650);
		groupOrderScale = Math.clamp(groupOrderScale, 0.01, 100.0);
		groupRewardEmeralds = Math.clamp(groupRewardEmeralds, 0.0, 100000.0);
		groupMinShare = Math.clamp(groupMinShare, 0.0, 1.0);
		villageRadius = Math.clamp(villageRadius, 16, 256);
		markerRange = Math.clamp(markerRange, 4, 48);
		return this;
	}

	/** The settings as JSON, the same as the config file. */
	public static String toJson(ErrandsConfig config) {
		return GSON.toJson(config);
	}

	/** Settings from JSON (as sent by the config screen), checked like the file is; null if it can't be read. */
	public static ErrandsConfig fromJson(String json) {
		try {
			ErrandsConfig config = GSON.fromJson(json, ErrandsConfig.class);
			return config == null ? null : config.sanitized();
		} catch (Exception e) {
			VillagersRemember.LOG.warn("Could not read settings: {}", e.toString());
			return null;
		}
	}

	/** Uses these settings from now on and writes them to the file. */
	public static void replace(ErrandsConfig config) {
		INSTANCE = config;
		save();
	}

	public int errandsFor(int villagerLevel) {
		return errandsPerLevel[Math.clamp(villagerLevel, 1, 5) - 1];
	}
}
