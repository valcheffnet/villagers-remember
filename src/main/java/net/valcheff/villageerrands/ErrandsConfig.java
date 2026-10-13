package net.valcheff.villageerrands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code config/villageerrands.json}. Missing keys keep their defaults; the file is rewritten with all keys. */
public final class ErrandsConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("villageerrands.json");
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
	/** Reward budget = value of what is asked x this, by pack T1..T5. */
	public double[] tierRewardMultiplier = {1.3, 1.4, 1.5, 1.65, 1.8};
	/** Chance that part of the reward is an item, by pack T1..T5. T1 pays emeralds or experience only. */
	public double[] tierItemChance = {0.0, 0.3, 0.5, 0.6, 0.7};
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
	/** Chance that a T5 errand's item is the profession's top reward. */
	public double topRewardChance = 0.5;
	/** Contracts: one hard errand a day from each Best friend; how much more it asks and pays. */
	public boolean contracts = true;
	public double contractCountFactor = 2.0;
	/** Emeralds' worth of goods a contract asks for in all (split between its two tasks). */
	public double contractValue = 96;
	public double contractRewardMultiplier = 2.5;
	/** Contracts completed for one profession that bring its legendary item. */
	public int contractsForLegend = 3;
	/** Fame titles (Helper, Benefactor, Legend) in front of player names in chat and TAB. */
	public boolean fameTitles = true;
	/** A patron of a village trades there at this discount. */
	public double patronDiscount = 0.45;
	/** Weekly orders: one big order per village for all players. */
	public boolean weeklyOrders = true;
	/** Size of weekly orders (1.0: 1024 food, 512 logs, 1536 stone, 192 iron, 256 wool, 100 monsters). */
	public double weeklyOrderScale = 1.0;
	/** Emeralds a finished weekly order pays out in all, by share; a helper needs at least this share to be paid. */
	public double weeklyRewardEmeralds = 192;
	public double weeklyMinShare = 0.02;
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
		ErrandsConfig loaded = null;
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				loaded = GSON.fromJson(reader, ErrandsConfig.class);
			} catch (Exception e) {
				VillageErrands.LOG.warn("Could not read {}, using defaults: {}", FILE, e.toString());
			}
		}
		INSTANCE = loaded != null ? loaded.sanitized() : new ErrandsConfig();
		save();
	}

	private static void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(INSTANCE, writer);
			}
		} catch (IOException e) {
			VillageErrands.LOG.warn("Could not write {}: {}", FILE, e.toString());
		}
	}

	private ErrandsConfig sanitized() {
		if (language == null || language.isBlank()) language = "auto";
		if (errandsPerLevel == null || errandsPerLevel.length != 5) errandsPerLevel = new ErrandsConfig().errandsPerLevel;
		for (int i = 0; i < 5; i++) errandsPerLevel[i] = Math.clamp(errandsPerLevel[i], 0, 6);
		maxActiveErrands = Math.clamp(maxActiveErrands, 1, 27);
		ErrandsConfig defaults = new ErrandsConfig();
		if (tierRewardMultiplier == null || tierRewardMultiplier.length != 5) tierRewardMultiplier = defaults.tierRewardMultiplier;
		if (tierItemChance == null || tierItemChance.length != 5) tierItemChance = defaults.tierItemChance;
		if (treasuryShare == null || treasuryShare.length != 5) treasuryShare = defaults.treasuryShare;
		if (treasuryVillageMultiplier == null || treasuryVillageMultiplier.length != 5) treasuryVillageMultiplier = defaults.treasuryVillageMultiplier;
		treasuryDailyCap = Math.clamp(treasuryDailyCap, 0.0, 10000.0);
		treasuryKeepDays = Math.clamp(treasuryKeepDays, 1, 365);
		networkBonusPerVillage = Math.clamp(networkBonusPerVillage, 0.0, 10.0);
		networkBonusMax = Math.clamp(networkBonusMax, 0.0, 10.0);
		for (int i = 0; i < 5; i++) {
			tierRewardMultiplier[i] = Math.clamp(tierRewardMultiplier[i], 0.0, 100.0);
			tierItemChance[i] = Math.clamp(tierItemChance[i], 0.0, 1.0);
		}
		xpPerEmerald = Math.clamp(xpPerEmerald, 0.0, 1000.0);
		maxEmeraldsPerErrand = Math.clamp(maxEmeraldsPerErrand, 0, 64 * 9);
		discountPerVillagerTier = Math.clamp(discountPerVillagerTier, 0.0, 1.0);
		discountPerVillageTier = Math.clamp(discountPerVillageTier, 0.0, 1.0);
		maxDiscount = Math.clamp(maxDiscount, 0.0, 0.95);
		surchargePerNegativePoint = Math.clamp(surchargePerNegativePoint, 0.0, 1.0);
		maxSurcharge = Math.clamp(maxSurcharge, 0.0, 10.0);
		contractCountFactor = Math.clamp(contractCountFactor, 0.1, 10.0);
		contractValue = Math.clamp(contractValue, 1.0, 100000.0);
		contractRewardMultiplier = Math.clamp(contractRewardMultiplier, 0.0, 100.0);
		contractsForLegend = Math.clamp(contractsForLegend, 1, 100);
		patronDiscount = Math.clamp(patronDiscount, 0.0, 0.95);
		weeklyOrderScale = Math.clamp(weeklyOrderScale, 0.01, 100.0);
		weeklyRewardEmeralds = Math.clamp(weeklyRewardEmeralds, 0.0, 100000.0);
		weeklyMinShare = Math.clamp(weeklyMinShare, 0.0, 1.0);
		villageRadius = Math.clamp(villageRadius, 16, 256);
		markerRange = Math.clamp(markerRange, 4, 48);
		return this;
	}

	public int errandsFor(int villagerLevel) {
		return errandsPerLevel[Math.clamp(villagerLevel, 1, 5) - 1];
	}
}
