package net.valcheff.villageerrands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.valcheff.villageerrands.ui.Chronicle;
import net.valcheff.villageerrands.ui.DialogActions;
import net.valcheff.villageerrands.ui.ErrandDialogs;

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
					.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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
			.then(Commands.literal("reload")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ctx -> {
					ErrandsConfig.load();
					Lang.load();
					ErrandPools.reload(ctx.getSource().getServer());
					ctx.getSource().sendSuccess(() -> Component.literal("Village Errands: config and errand pools reloaded"), true);
					return 1;
				}))
			.then(Commands.literal("reset")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
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
						ctx.getSource().sendSuccess(() -> Component.literal("Village Errands: reset " + target.getScoreboardName()), true);
						return 1;
					}))));
		AdminCommands.register(dispatcher);
	}
}
