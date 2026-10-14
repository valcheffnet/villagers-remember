package net.valcheff.villagersremember;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.valcheff.villagersremember.ui.Chronicle;
import net.valcheff.villagersremember.ui.DialogActions;
import net.valcheff.villagersremember.ui.ErrandDialogs;

/**
 * {@code /errands} opens the journal, {@code /errands village} tells about the village the player is in.
 * Operators: {@code /errands reload}, {@code /errands reset <player>}, {@code /errands village name <name>};
 * the reputation and test commands are in {@link AdminCommands}.
 */
public final class ErrandsCommand {
	private static int villageInfo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		VillageRegistry registry = VillageRegistry.get(source.getServer());
		var found = registry.villageAt(player.level().dimension(), player.blockPosition());
		if (found.isEmpty()) {
			source.sendFailure(Lang.tr(player, "village.none"));
			return 0;
		}
		VillageRegistry.Village v = found.get();
		ErrandsData.PlayerState state = ErrandService.state(player);
		Reputation.Standing s = Reputation.standing(state, java.util.Optional.of(v.center));
		java.util.Map<String, Integer> byProfession = new java.util.TreeMap<>();
		for (VillageRegistry.Resident r : v.residents.values()) byProfession.merge(ErrandText.path(r.profession), 1, Integer::sum);
		source.sendSuccess(() -> Lang.tr(player, "village.info", ErrandText.villageName(player, v.center),
			v.center.pos().toShortString(), v.bells.size(), v.residents.size(), v.workers().size(),
			Lang.tr(player, "rep.village." + s.tier()), s.points()), false);
		source.sendSuccess(() -> Component.literal(byProfession.toString()), false);
		return 1;
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("errands")
			.executes(ctx -> {
				ErrandDialogs.journal(ctx.getSource().getPlayerOrException(), null);
				return 1;
			})
			// Dialog buttons; tokens are only valid for the dialog that is open.
			.then(Commands.literal("click")
				.then(Commands.argument("token", StringArgumentType.word())
					.executes(ctx -> DialogActions.run(ctx.getSource().getPlayerOrException(),
						StringArgumentType.getString(ctx, "token")) ? 1 : 0)))
			.then(Commands.literal("input")
				.then(Commands.argument("token", StringArgumentType.word())
					.executes(ctx -> DialogActions.runInput(ctx.getSource().getPlayerOrException(),
						StringArgumentType.getString(ctx, "token"), "") ? 1 : 0)
					.then(Commands.argument("text", StringArgumentType.greedyString())
						.executes(ctx -> DialogActions.runInput(ctx.getSource().getPlayerOrException(),
							StringArgumentType.getString(ctx, "token"), StringArgumentType.getString(ctx, "text")) ? 1 : 0))))
			.then(Commands.literal("book")
				.executes(ctx -> {
					Chronicle.open(ctx.getSource().getPlayerOrException());
					return 1;
				}))
			.then(Commands.literal("track")
				.then(Commands.argument("errand", StringArgumentType.greedyString())
					.executes(ctx -> {
						ServerPlayer player = ctx.getSource().getPlayerOrException();
						ErrandService.track(player, StringArgumentType.getString(ctx, "errand"));
						Chronicle.open(player);
						return 1;
					})))
			.then(Commands.literal("village")
				.executes(ctx -> villageInfo(ctx.getSource()))
				.then(Commands.literal("name")
					.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
					.then(Commands.argument("name", StringArgumentType.greedyString())
						.executes(ctx -> {
							ServerPlayer player = ctx.getSource().getPlayerOrException();
							var village = VillageRegistry.get(ctx.getSource().getServer()).villageAt(player.level().dimension(), player.blockPosition());
							if (village.isEmpty()) {
								ctx.getSource().sendFailure(Component.literal("You are not in a village"));
								return 0;
							}
							village.get().name = StringArgumentType.getString(ctx, "name");
							VillageRegistry.get(ctx.getSource().getServer()).setDirty();
							ctx.getSource().sendSuccess(() -> Component.literal("Village renamed to " + village.get().name), true);
							return 1;
						}))))
			.then(Commands.literal("emeralds")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.executes(ctx -> {
					boolean on = ErrandsConfig.INSTANCE.emeraldRewards;
					ctx.getSource().sendSuccess(() -> Component.literal(on
						? "Emerald rewards are on: errands, contracts and village orders pay emeralds"
						: "Emerald rewards are off: no emeralds from errands, contracts or village orders (the treasury still pays)"), false);
					return on ? 1 : 0;
				})
				.then(Commands.literal("on").executes(ctx -> emeralds(ctx.getSource(), true)))
				.then(Commands.literal("off").executes(ctx -> emeralds(ctx.getSource(), false))))
			.then(Commands.literal("xp")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.executes(ctx -> {
					double m = ErrandsConfig.INSTANCE.xpMultiplier;
					ctx.getSource().sendSuccess(() -> Component.literal("Experience multiplier: x" + m), false);
					return (int) Math.round(m * 100);
				})
				.then(Commands.argument("multiplier", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 10))
					.executes(ctx -> {
						double m = com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(ctx, "multiplier");
						ErrandsConfig.INSTANCE.xpMultiplier = m;
						ErrandsConfig.save();
						ctx.getSource().sendSuccess(() -> Component.literal("Experience from errands, contracts and village orders: x" + m), true);
						return 1;
					})))
			.then(Commands.literal("reload")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.executes(ctx -> {
					ErrandsConfig.load();
					Lang.load();
					ErrandPools.reload(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal("Villagers Remember: config and errand pools reloaded"), true);
					return 1;
				}))
			.then(Commands.literal("reset")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.argument("player", EntityArgument.player())
					.executes(ctx -> {
						ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
						ErrandsData data = ErrandsData.get(ctx.getSource().getServer());
						ErrandsData.PlayerState state = data.player(target.getUUID());
						state.active.clear();
						state.done.clear();
						state.villagerRep.clear();
						state.trialsPassed.clear();
						state.villageRep.clear();
						data.setDirty();
						ctx.getSource().sendSuccess(() -> Component.literal("Villagers Remember: reset " + target.getScoreboardName()), true);
						return 1;
					}))));
		AdminCommands.register(dispatcher);
	}

	/** Turns emerald rewards on or off for the whole server and saves the choice in the config file. */
	private static int emeralds(net.minecraft.commands.CommandSourceStack source, boolean on) {
		ErrandsConfig.INSTANCE.emeraldRewards = on;
		ErrandsConfig.save();
		source.sendSuccess(() -> Component.literal(on
			? "Emerald rewards on. Errands offered before keep their listed pay."
			: "Emerald rewards off. Errands, contracts and village orders pay no emeralds; the treasury still pays."), true);
		return 1;
	}
}
