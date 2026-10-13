package net.valcheff.villagersremember;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Operator commands for testing and for server admins. Villages and villagers are named the way the target player
 * sees them (names depend on the player's language), with tab completion.
 * <ul>
 *   <li>{@code /errands rep <player> <village> show}</li>
 *   <li>{@code /errands rep <player> <village> villager <name> set <points> | tier <0-4> | reset}</li>
 *   <li>{@code /errands rep <player> <village> village set <points> | tier <0-4> | reset}</li>
 *   <li>{@code /errands rep <player> <village> all tier <0-4> | reset} (village and every working resident)</li>
 *   <li>{@code /errands standing <player> <tier>}: {@code rep <player> here all tier <tier>}</li>
 *   <li>{@code /errands villages}, {@code /errands treasury <player>}</li>
 *   <li>{@code /errands give <player> <village> <villager> <errand>}: that errand, accepted, from that villager</li>
 *   <li>{@code /errands finish <player> [items]}</li>
 * </ul>
 * {@code <village>} is {@code here}, a name or the id from {@code /errands villages}. A villager whose name is not unique in the village is
 * written {@code Name.trade}, plus {@code .id} when even that repeats.
 */
public final class AdminCommands {
	private static final SimpleCommandExceptionType NOT_IN_VILLAGE = new SimpleCommandExceptionType(
		Component.literal("The player is not in a village"));

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("errands")
			.then(Commands.literal("rep")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("village", StringArgumentType.string()).suggests(VILLAGES)
						.then(Commands.literal("show").executes(ctx -> show(ctx.getSource(), player(ctx), village(ctx))))
						.then(Commands.literal("villager")
							.then(Commands.argument("villager", StringArgumentType.string()).suggests(VILLAGERS)
								.then(Commands.literal("set").then(Commands.argument("points", IntegerArgumentType.integer(-500, 100000))
									.executes(ctx -> setVillager(ctx, IntegerArgumentType.getInteger(ctx, "points"), -1))))
								.then(Commands.literal("tier").then(Commands.argument("tier", IntegerArgumentType.integer(0, Reputation.MAX_TIER))
									.executes(ctx -> setVillager(ctx, Reputation.VILLAGER_TIERS[IntegerArgumentType.getInteger(ctx, "tier")],
										IntegerArgumentType.getInteger(ctx, "tier")))))
								.then(Commands.literal("reset").executes(ctx -> setVillager(ctx, 0, 0)))))
						.then(Commands.literal("village")
							.then(Commands.literal("set").then(Commands.argument("points", IntegerArgumentType.integer(-500, 100000))
								.executes(ctx -> setVillage(ctx, IntegerArgumentType.getInteger(ctx, "points")))))
							.then(Commands.literal("tier").then(Commands.argument("tier", IntegerArgumentType.integer(0, Reputation.MAX_TIER))
								.executes(ctx -> setVillage(ctx, Reputation.VILLAGE_TIERS[IntegerArgumentType.getInteger(ctx, "tier")]))))
							.then(Commands.literal("reset").executes(ctx -> setVillage(ctx, 0))))
						.then(Commands.literal("all")
							.then(Commands.literal("tier").then(Commands.argument("tier", IntegerArgumentType.integer(0, Reputation.MAX_TIER))
								.executes(ctx -> allTier(ctx.getSource(), player(ctx), village(ctx), IntegerArgumentType.getInteger(ctx, "tier")))))
							.then(Commands.literal("reset").executes(ctx -> allReset(ctx.getSource(), player(ctx), village(ctx))))))))
			.then(Commands.literal("standing")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("tier", IntegerArgumentType.integer(0, Reputation.MAX_TIER))
						.executes(ctx -> {
							ServerPlayer target = player(ctx);
							return allTier(ctx.getSource(), target, here(target), IntegerArgumentType.getInteger(ctx, "tier"));
						}))))
			.then(Commands.literal("give")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("village", StringArgumentType.string()).suggests(VILLAGES)
						.then(Commands.argument("villager", StringArgumentType.string()).suggests(VILLAGERS)
							.then(Commands.argument("errand", StringArgumentType.string()).suggests(ERRANDS)
								.executes(AdminCommands::give))))))
			.then(Commands.literal("villager")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("levelup")
					.executes(ctx -> levelUp(ctx.getSource(), nearestVillager(ctx.getSource()), 1))
					.then(Commands.argument("levels", IntegerArgumentType.integer(1, 4))
						.executes(ctx -> levelUp(ctx.getSource(), nearestVillager(ctx.getSource()), IntegerArgumentType.getInteger(ctx, "levels")))))
				// By name: /errands villager <village> <villager> levelup [levels], names as the one typing sees them.
				.then(Commands.argument("village", StringArgumentType.string()).suggests(VILLAGES)
					.then(Commands.argument("villager", StringArgumentType.string()).suggests(VILLAGERS)
						.then(Commands.literal("levelup")
							.executes(ctx -> levelUp(ctx.getSource(), namedVillager(ctx), 1))
							.then(Commands.argument("levels", IntegerArgumentType.integer(1, 4))
								.executes(ctx -> levelUp(ctx.getSource(), namedVillager(ctx), IntegerArgumentType.getInteger(ctx, "levels"))))))))
			.then(Commands.literal("treasury")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> treasury(ctx.getSource(), player(ctx)))))
			.then(Commands.literal("villages")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> villages(ctx.getSource())))
			.then(Commands.literal("finish")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> finish(ctx.getSource(), player(ctx), false))
					.then(Commands.literal("items").executes(ctx -> finish(ctx.getSource(), player(ctx), true)))))
			.then(Commands.literal("fame")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> fame(ctx.getSource(), player(ctx), null, 0))
					.then(Commands.literal("set").then(Commands.argument("points", IntegerArgumentType.integer(0, 1000000))
						.executes(ctx -> fame(ctx.getSource(), player(ctx), "set", IntegerArgumentType.getInteger(ctx, "points")))))
					.then(Commands.literal("add").then(Commands.argument("points", IntegerArgumentType.integer(1, 1000000))
						.executes(ctx -> fame(ctx.getSource(), player(ctx), "add", IntegerArgumentType.getInteger(ctx, "points")))))))
			.then(Commands.literal("order")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("village", StringArgumentType.string()).suggests(VILLAGES)
					.executes(ctx -> order(ctx.getSource(), village(ctx), null, 0))
					.then(Commands.literal("add").then(Commands.argument("player", EntityArgument.player())
						.then(Commands.argument("amount", IntegerArgumentType.integer(1, 100000))
							.executes(ctx -> order(ctx.getSource(), village(ctx), player(ctx), IntegerArgumentType.getInteger(ctx, "amount"))))))))
			.then(Commands.literal("patron")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("village", StringArgumentType.string()).suggests(VILLAGES)
					.executes(ctx -> patron(ctx.getSource(), village(ctx), false))
					.then(Commands.literal("clear").executes(ctx -> patron(ctx.getSource(), village(ctx), true)))))
			.then(Commands.literal("legend")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("player", EntityArgument.player())
					.then(Commands.argument("profession", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(Legends.professions(), builder))
						.executes(ctx -> legend(ctx.getSource(), player(ctx), StringArgumentType.getString(ctx, "profession")))))));
	}

	// ---- end game ----

	private static int fame(CommandSourceStack source, ServerPlayer target, String op, int points) {
		ErrandsData data = ErrandsData.get(source.getServer());
		ErrandsData.PlayerState state = data.player(target.getUUID());
		if ("set".equals(op)) {
			state.fame = points;
			data.setDirty();
			Fame.updateTeam(target);
			Milestones.check(target);
		} else if ("add".equals(op)) {
			Fame.add(target, points);
		}
		int title = Fame.title(state);
		source.sendSuccess(() -> Component.literal(target.getScoreboardName() + ": " + state.fame + " fame, title "
			+ Lang.raw("en_us", "fame.title." + title)), op != null);
		return state.fame;
	}

	private static int order(CommandSourceStack source, VillageRegistry.Village village, ServerPlayer helper, int amount) {
		Optional<GroupOrder.View> view = GroupOrder.current(source.getServer(), village);
		if (view.isEmpty()) {
			source.sendFailure(Component.literal("Village orders are turned off"));
			return 0;
		}
		if (helper != null) GroupOrder.add(source.getServer(), village, view.get(), helper.getUUID(), amount);
		GroupOrder.View v = view.get();
		StringBuilder who = new StringBuilder();
		v.order().contrib.forEach((id, n) -> who.append(' ').append(id.toString(), 0, 8).append('=').append(n));
		source.sendSuccess(() -> Component.literal("Village order of #" + village.id + ": " + v.kind().key() + " "
			+ Math.min(v.order().progress, v.target()) + "/" + v.target() + " for " + v.order().players + " regular players, " + v.daysLeft() + " days left" + (v.order().settled ? ", paid" : "")
			+ (who.isEmpty() ? "" : ", helpers:" + who)), helper != null);
		return v.order().progress;
	}

	private static int patron(CommandSourceStack source, VillageRegistry.Village village, boolean clear) {
		if (clear) {
			village.patron = null;
			village.patronName = null;
			VillageRegistry.get(source.getServer()).setDirty();
		}
		Patron.check(source.getServer(), Optional.of(village.center));
		String who = village.patron != null ? village.patronName + " (" + village.patron + ")" : "nobody";
		source.sendSuccess(() -> Component.literal("Patron of #" + village.id + ": " + who), clear);
		return village.patron != null ? 1 : 0;
	}

	private static int legend(CommandSourceStack source, ServerPlayer target, String profession) {
		if (!Legends.professions().contains(profession)) {
			source.sendFailure(Component.literal("No legendary item for " + profession));
			return 0;
		}
		ErrandsData.PlayerState state = ErrandService.state(target);
		Optional<VillageRegistry.Village> here = VillageRegistry.get(source.getServer()).villageAt(target.level().dimension(), target.blockPosition());
		String villageName = here.map(v -> ErrandText.villageName(target, v.center)).orElse("?");
		ItemStack stack = Legends.create((ServerLevel) target.level(), target, profession, target.getScoreboardName(), villageName);
		state.legends.add(profession);
		ErrandsData.get(source.getServer()).setDirty();
		ErrandService.give(target, stack, 1);
		Milestones.check(target);
		source.sendSuccess(() -> Component.literal("Gave " + target.getScoreboardName() + " the legendary item of " + profession), true);
		return 1;
	}

	// ---- arguments ----

	private static ServerPlayer player(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return EntityArgument.getPlayer(ctx, "player");
	}

	/** The player named in the command while typing it, else the one typing, for suggestions. */
	private static ServerPlayer viewer(CommandContext<CommandSourceStack> ctx) {
		try {
			return EntityArgument.getPlayer(ctx, "player");
		} catch (Exception e) {
			return ctx.getSource().getPlayer();
		}
	}

	private static VillageRegistry.Village here(ServerPlayer target) throws CommandSyntaxException {
		return VillageRegistry.get(target.level().getServer()).villageAt(target.level().dimension(), target.blockPosition())
			.orElseThrow(NOT_IN_VILLAGE::create);
	}

	/** The player named in the command, or the one typing it when the command names none. */
	private static ServerPlayer playerOrSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		try {
			return EntityArgument.getPlayer(ctx, "player");
		} catch (IllegalArgumentException noArgument) {
			return ctx.getSource().getPlayerOrException();
		}
	}

	private static VillageRegistry.Village village(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = playerOrSelf(ctx);
		String arg = StringArgumentType.getString(ctx, "village");
		if (arg.equalsIgnoreCase("here")) return here(target);
		VillageRegistry registry = VillageRegistry.get(ctx.getSource().getServer());
		// An id: "3", or "#3" in quotes.
		String id = arg.startsWith("#") ? arg.substring(1) : arg;
		if (!id.isEmpty() && id.chars().allMatch(Character::isDigit)) {
			Optional<VillageRegistry.Village> v = registry.byId(Integer.parseInt(id));
			if (v.isPresent()) return v.get();
		}
		for (VillageRegistry.Village v : registry.all()) {
			if (ErrandText.villageName(target, v.center).equalsIgnoreCase(arg)) return v;
		}
		throw new SimpleCommandExceptionType(Component.literal("No village called " + arg)).create();
	}

	private static final SuggestionProvider<CommandSourceStack> VILLAGES = (ctx, builder) -> {
		List<String> out = new ArrayList<>();
		out.add("here");
		ServerPlayer viewer = viewer(ctx);
		for (VillageRegistry.Village v : VillageRegistry.get(ctx.getSource().getServer()).all()) {
			out.add(viewer != null ? StringArgumentType.escapeIfRequired(ErrandText.villageName(viewer, v.center)) : String.valueOf(v.id));
		}
		return SharedSuggestionProvider.suggest(out, builder);
	};

	/** Residents of the village with the name the player sees, made unique where needed. */
	public static Map<String, VillageRegistry.Resident> residentNames(MinecraftServer server, ServerPlayer viewer, VillageRegistry.Village village) {
		// The level, to read the custom name of a villager that is loaded.
		ServerLevel level = server.getLevel(village.center.dimension());
		Map<String, Integer> counts = new HashMap<>();
		Map<VillageRegistry.Resident, String> names = new HashMap<>();
		for (VillageRegistry.Resident r : village.residents.values()) {
			String name = ErrandText.villagerName(viewer, r.id, level != null ? Villages.loaded(level, r.id) : null);
			names.put(r, name);
			counts.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
		}
		Map<String, Integer> withTrade = new HashMap<>();
		for (VillageRegistry.Resident r : village.residents.values()) {
			String name = names.get(r);
			if (counts.get(name.toLowerCase(Locale.ROOT)) > 1) {
				name = name + "." + ErrandText.path(r.profession);
				names.put(r, name);
			}
			withTrade.merge(name.toLowerCase(Locale.ROOT), 1, Integer::sum);
		}
		Map<String, VillageRegistry.Resident> out = new java.util.LinkedHashMap<>();
		for (VillageRegistry.Resident r : village.residents.values()) {
			String name = names.get(r);
			if (withTrade.get(name.toLowerCase(Locale.ROOT)) > 1) name = name + "." + r.id.toString().substring(0, 4);
			out.put(name, r);
		}
		return out;
	}

	private static final SuggestionProvider<CommandSourceStack> VILLAGERS = (ctx, builder) -> {
		ServerPlayer viewer = viewer(ctx);
		if (viewer == null) return builder.buildFuture();
		try {
			VillageRegistry.Village village = village(ctx);
			List<String> out = new ArrayList<>();
			for (String name : residentNames(ctx.getSource().getServer(), viewer, village).keySet()) out.add(StringArgumentType.escapeIfRequired(name));
			return SharedSuggestionProvider.suggest(out, builder);
		} catch (CommandSyntaxException e) {
			return builder.buildFuture();
		}
	};

	private static VillageRegistry.Resident villager(CommandContext<CommandSourceStack> ctx, ServerPlayer target,
	                                                 VillageRegistry.Village village) throws CommandSyntaxException {
		String arg = StringArgumentType.getString(ctx, "villager");
		Map<String, VillageRegistry.Resident> names = residentNames(ctx.getSource().getServer(), target, village);
		for (Map.Entry<String, VillageRegistry.Resident> e : names.entrySet()) {
			if (e.getKey().equalsIgnoreCase(arg)) return e.getValue();
		}
		List<String> similar = names.keySet().stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(arg.toLowerCase(Locale.ROOT))).toList();
		throw new SimpleCommandExceptionType(Component.literal("No villager called " + arg
			+ (similar.isEmpty() ? "" : ", did you mean " + String.join(" or ", similar) + "?"))).create();
	}

	private static final SuggestionProvider<CommandSourceStack> ERRANDS = (ctx, builder) -> {
		ServerPlayer viewer = viewer(ctx);
		if (viewer == null) return builder.buildFuture();
		try {
			VillageRegistry.Resident r = villager(ctx, viewer, village(ctx));
			return SharedSuggestionProvider.suggest(ErrandGenerator.taskNames(r.profession).keySet().stream()
				.map(StringArgumentType::escapeIfRequired).toList(), builder);
		} catch (CommandSyntaxException e) {
			return builder.buildFuture();
		}
	};

	// ---- commands ----

	/** Builds the chosen errand from the villager, as in the task's first pack, and accepts it for the player. */
	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer target = player(ctx);
		VillageRegistry.Village village = village(ctx);
		VillageRegistry.Resident r = villager(ctx, target, village);
		String name = StringArgumentType.getString(ctx, "errand");
		var tasks = ErrandGenerator.taskNames(r.profession);
		ErrandPools.Task task = tasks.get(name);
		if (task == null) {
			throw new SimpleCommandExceptionType(Component.literal("No errand " + name + " for a " + ErrandText.path(r.profession)
				+ (tasks.isEmpty() ? "" : "; try one of the suggestions"))).create();
		}
		ErrandGenerator.Giver giver = ErrandGenerator.Giver.of(village, r);
		Errand errand = ErrandGenerator.forTask(giver, ErrandGenerator.day((ServerLevel) target.level()), task);
		if (!ErrandService.accept(target, errand)) {
			ctx.getSource().sendFailure(Component.literal(target.getScoreboardName() + " cannot take another errand ("
				+ ErrandsConfig.INSTANCE.maxActiveErrands + " at most)"));
			return 0;
		}
		String villagerName = StringArgumentType.getString(ctx, "villager");
		ctx.getSource().sendSuccess(() -> Component.literal(target.getScoreboardName() + " took ")
			.append(ErrandText.task(target, errand).withStyle(ChatFormatting.YELLOW))
			.append(" from " + villagerName + " (T" + errand.tier() + ")"), true);
		return 1;
	}

	/** {@code trials}: the trust trials passed with the villager (a tier sets them too), or -1 to keep them. */
	private static int setVillager(CommandContext<CommandSourceStack> ctx, int points, int trials) throws CommandSyntaxException {
		ServerPlayer target = player(ctx);
		VillageRegistry.Village village = village(ctx);
		VillageRegistry.Resident r = villager(ctx, target, village);
		ErrandsData data = ErrandsData.get(ctx.getSource().getServer());
		ErrandsData.PlayerState state = data.player(target.getUUID());
		if (points == 0) {
			state.villagerRep.remove(r.id);
		} else {
			state.villagerRep.put(r.id, points);
		}
		if (trials == 0) state.trialsPassed.remove(r.id);
		else if (trials > 0) state.trialsPassed.put(r.id, trials);
		int tier = Reputation.villagerTier(state, r.id);
		done(data, target);
		String name = StringArgumentType.getString(ctx, "villager");
		ctx.getSource().sendSuccess(() -> Component.literal(target.getScoreboardName() + " with " + name + ": ")
			.append(ErrandText.stars(tier)).append(" " + points + " points, ")
			.append(Lang.tr(target, "rep.villager." + tier).withColor(ErrandText.tierColor(tier))), true);
		return 1;
	}

	private static int setVillage(CommandContext<CommandSourceStack> ctx, int points) throws CommandSyntaxException {
		ServerPlayer target = player(ctx);
		VillageRegistry.Village village = village(ctx);
		ErrandsData data = ErrandsData.get(ctx.getSource().getServer());
		ErrandsData.PlayerState state = data.player(target.getUUID());
		String key = ErrandsData.villageKey(village.center);
		if (points == 0) state.villageRep.remove(key);
		else state.villageRep.put(key, points);
		done(data, target);
		Reputation.Standing s = Reputation.standing(state, Optional.of(village.center));
		ctx.getSource().sendSuccess(() -> Component.literal(target.getScoreboardName() + " in "
				+ ErrandText.villageName(target, village.center) + ": " + points + " points, ")
			.append(Lang.tr(target, "rep.village." + s.tier()).withColor(ErrandText.tierColor(s.tier())))
			.append(s.tier() < Reputation.tier(points, Reputation.VILLAGE_TIERS)
				? Component.literal(" (the residents' standing holds it lower)").withStyle(ChatFormatting.GRAY) : Component.empty()), true);
		return 1;
	}

	/** The tier in the village with what it needs: village points and every working resident at that tier. */
	static int allTier(CommandSourceStack source, ServerPlayer target, VillageRegistry.Village village, int tier) {
		ErrandsData data = ErrandsData.get(source.getServer());
		ErrandsData.PlayerState state = data.player(target.getUUID());
		state.villageRep.put(ErrandsData.villageKey(village.center), Reputation.VILLAGE_TIERS[tier]);
		int count = 0;
		for (VillageRegistry.Resident r : village.workers()) {
			state.villagerRep.put(r.id, Reputation.VILLAGER_TIERS[tier]);
			if (tier > 0) state.trialsPassed.put(r.id, tier);
			else state.trialsPassed.remove(r.id);
			count++;
		}
		done(data, target);
		int residents = count;
		source.sendSuccess(() -> Component.literal(target.getScoreboardName() + " in " + ErrandText.villageName(target, village.center)
				+ ": ").append(Lang.tr(target, "rep.village." + tier).withColor(ErrandText.tierColor(tier)))
			.append(" (" + Reputation.VILLAGE_TIERS[tier] + " points), " + residents + " working residents at ")
			.append(ErrandText.stars(tier)), true);
		if (count < Reputation.VILLAGE_NEEDS[tier]) {
			source.sendFailure(Component.literal("The village needs " + Reputation.VILLAGE_NEEDS[tier]
				+ " working residents for this tier and has " + count));
		}
		return 1;
	}

	private static int allReset(CommandSourceStack source, ServerPlayer target, VillageRegistry.Village village) {
		ErrandsData data = ErrandsData.get(source.getServer());
		ErrandsData.PlayerState state = data.player(target.getUUID());
		state.villageRep.remove(ErrandsData.villageKey(village.center));
		for (UUID id : village.residents.keySet()) {
			state.villagerRep.remove(id);
			state.trialsPassed.remove(id);
		}
		done(data, target);
		source.sendSuccess(() -> Component.literal(target.getScoreboardName() + " is a stranger again in "
			+ ErrandText.villageName(target, village.center)), true);
		return 1;
	}

	private static int show(CommandSourceStack source, ServerPlayer target, VillageRegistry.Village village) {
		ErrandsData.PlayerState state = ErrandsData.get(source.getServer()).player(target.getUUID());
		Reputation.Standing s = Reputation.standing(state, Optional.of(village.center));
		source.sendSuccess(() -> Component.literal(target.getScoreboardName() + " in " + ErrandText.villageName(target, village.center)
				+ " #" + village.id + ": ").append(Lang.tr(target, "rep.village." + s.tier()).withColor(ErrandText.tierColor(s.tier())))
			.append(", " + s.points() + " points"), false);
		Map<String, VillageRegistry.Resident> names = residentNames(source.getServer(), target, village);
		List<Map.Entry<String, VillageRegistry.Resident>> list = new ArrayList<>(names.entrySet());
		list.sort(Comparator.comparingInt((Map.Entry<String, VillageRegistry.Resident> e) -> -Reputation.villager(state, e.getValue().id))
			.thenComparing(Map.Entry::getKey));
		for (Map.Entry<String, VillageRegistry.Resident> e : list) {
			int points = Reputation.villager(state, e.getValue().id);
			int tier = Reputation.villagerTier(state, e.getValue().id);
			MutableComponent line = Component.literal(" ").append(ErrandText.stars(tier)).append(" ")
				.append(Component.literal(e.getKey()).withColor(ErrandText.tierColor(tier)))
				.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
				.append(ErrandText.profession(e.getValue().profession).copy().withStyle(ChatFormatting.GRAY))
				.append(Component.literal(" " + points).withStyle(ChatFormatting.YELLOW));
			source.sendSuccess(() -> line, false);
		}
		return names.size();
	}

	private static int villages(CommandSourceStack source) {
		List<VillageRegistry.Village> all = VillageRegistry.get(source.getServer()).all();
		ServerPlayer viewer = source.getPlayer();
		source.sendSuccess(() -> Component.literal(plural(all.size(), "village")), false);
		for (VillageRegistry.Village v : all) {
			// From the console there is no language to name an unnamed village in; the id is enough.
			String name = viewer != null ? ErrandText.villageName(viewer, v.center) : (v.name != null ? v.name : "");
			var pos = v.center.pos();
			String tp = "/execute in " + v.center.dimension().identifier() + " run tp @s " + pos.getX() + " " + (pos.getY() + 1) + " " + pos.getZ();
			MutableComponent line = Component.literal(" #" + v.id).withStyle(ChatFormatting.GRAY)
				.append(Component.literal(name.isEmpty() ? "" : " " + name).withStyle(ChatFormatting.WHITE))
				.append(Component.literal(" [" + pos.toShortString() + "]").withStyle(s -> s.withColor(ChatFormatting.AQUA)
					.withClickEvent(new ClickEvent.SuggestCommand(tp))))
				.append(Component.literal(" " + plural(v.residents.size(), "resident") + ", " + v.workers().size() + " working, "
					+ plural(v.bells.size(), "bell")).withStyle(ChatFormatting.GRAY));
			source.sendSuccess(() -> line, false);
		}
		return all.size();
	}

	private static net.minecraft.world.entity.npc.villager.Villager namedVillager(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer viewer = playerOrSelf(ctx);
		VillageRegistry.Village village = village(ctx);
		VillageRegistry.Resident r = villager(ctx, viewer, village);
		ServerLevel level = ctx.getSource().getServer().getLevel(village.center.dimension());
		var entity = level != null ? Villages.loaded(level, r.id) : null;
		if (entity == null) {
			throw new SimpleCommandExceptionType(Component.literal(StringArgumentType.getString(ctx, "villager")
				+ " is not loaded; go closer to the village")).create();
		}
		return entity;
	}

	private static net.minecraft.world.entity.npc.villager.Villager nearestVillager(CommandSourceStack source) throws CommandSyntaxException {
		var pos = source.getPosition();
		var list = source.getLevel().getEntitiesOfClass(net.minecraft.world.entity.npc.villager.Villager.class,
			new net.minecraft.world.phys.AABB(pos, pos).inflate(8), v -> v.isAlive());
		return list.stream().min(Comparator.comparingDouble(v -> v.distanceToSqr(pos)))
			.orElseThrow(() -> new SimpleCommandExceptionType(Component.literal("No villager within 8 blocks")).create());
	}

	/**
	 * Raises a villager's trade level the way trading does: the next level with its new trades, and the experience
	 * of that level. {@code /data merge ... {VillagerData:{level:5}}} only changes the number and adds no trades.
	 */
	private static int levelUp(CommandSourceStack source, net.minecraft.world.entity.npc.villager.Villager villager, int levels) {
		var data = villager.getVillagerData();
		String profession = ErrandGenerator.professionId(data);
		if (profession.equals("minecraft:none") || profession.equals("minecraft:nitwit")) {
			source.sendFailure(Component.literal("This villager has no trade to level up"));
			return 0;
		}
		int done = 0;
		while (done < levels && net.minecraft.world.entity.npc.villager.VillagerData.canLevelUp(villager.getVillagerData().level())) {
			((net.valcheff.villagersremember.mixin.VillagerInvoker) villager).villagersremember$increaseMerchantCareer(source.getLevel());
			villager.setVillagerXp(net.minecraft.world.entity.npc.villager.VillagerData.getMinXpPerLevel(villager.getVillagerData().level()));
			done++;
		}
		int raised = done;
		int level = villager.getVillagerData().level();
		source.sendSuccess(() -> Component.literal("Raised " + ErrandText.path(profession) + " by " + plural(raised, "level") + " to ")
			.append(Component.translatable("merchant.level." + level))
			.append(" (" + villager.getOffers().size() + " trades)"), true);
		return raised;
	}

	private static int treasury(CommandSourceStack source, ServerPlayer target) {
		ErrandsData.PlayerState state = ErrandsData.get(source.getServer()).player(target.getUUID());
		Treasury.settle(source.getServer(), state, ErrandGenerator.day((ServerLevel) target.level()));
		List<Treasury.Entry> entries = Treasury.entries(target, state);
		double perDay = entries.stream().mapToDouble(Treasury.Entry::perDay).sum();
		int friendly = Treasury.friendlyVillages(source.getServer(), state);
		source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s: %.1f emeralds a day, %.1f waiting, network bonus +%d%% (%s)",
			target.getScoreboardName(), perDay, Treasury.waiting(state), Math.round(Treasury.networkBonus(friendly) * 100),
			plural(friendly, "friendly village"))), false);
		for (Treasury.Entry e : entries) {
			int tier = Reputation.villageTier(state, Optional.of(e.center()));
			MutableComponent line = Component.literal(" ").append(Component.literal(ErrandText.villageName(target, e.center()))
					.withColor(ErrandText.tierColor(tier)))
				.append(Component.literal(String.format(Locale.ROOT, " [%s] %.2f a day, %.2f waiting, %s counted",
					e.center().pos().toShortString(), e.perDay(), e.waiting(), e.counted())).withStyle(ChatFormatting.GRAY));
			source.sendSuccess(() -> line, false);
		}
		return entries.size();
	}

	/** Every action counter of the active errands full; with items, also what is still missing to bring. */
	private static int finish(CommandSourceStack source, ServerPlayer target, boolean items) {
		ErrandsData data = ErrandsData.get(source.getServer());
		ErrandsData.PlayerState state = data.player(target.getUUID());
		for (ErrandsData.Active a : state.active) {
			for (int i = 0; i < a.errand.objectives().size(); i++) {
				Objective o = a.errand.objectives().get(i);
				if (!o.isDelivery()) {
					a.progress[i] = o.needed();
				} else if (items) {
					int missing = o.needed() - ErrandService.countItems(target, o);
					ItemStack stack = ErrandText.icon(o);
					if (missing > 0 && !stack.isEmpty()) ErrandService.give(target, stack, missing);
				}
			}
		}
		done(data, target);
		int n = state.active.size();
		source.sendSuccess(() -> Component.literal("Villagers Remember: finished the actions of " + plural(n, "errand") + " of "
			+ target.getScoreboardName() + (items ? ", with the items to bring" : "")), true);
		return n;
	}

	private static String plural(int n, String word) {
		return n + " " + word + (n == 1 ? "" : "s");
	}

	private static void done(ErrandsData data, ServerPlayer target) {
		data.setDirty();
		// Standing changed: patrons and advancements now, not at the next bell.
		MinecraftServer server = target.level().getServer();
		for (VillageRegistry.Village v : VillageRegistry.get(server).all()) Patron.check(server, Optional.of(v.center));
		Milestones.check(target);
		Hud.refresh(target);
	}

	private AdminCommands() {
	}
}
