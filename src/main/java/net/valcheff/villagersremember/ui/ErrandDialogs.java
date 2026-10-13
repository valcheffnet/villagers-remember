package net.valcheff.villagersremember.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.valcheff.villagersremember.Errand;
import net.valcheff.villagersremember.ErrandGenerator;
import net.valcheff.villagersremember.ErrandPools;
import net.valcheff.villagersremember.ErrandService;
import net.valcheff.villagersremember.ErrandText;
import net.valcheff.villagersremember.TopRewards;
import net.valcheff.villagersremember.Treasury;
import net.valcheff.villagersremember.ErrandsConfig;
import net.valcheff.villagersremember.ErrandsData;
import net.valcheff.villagersremember.Lang;
import net.valcheff.villagersremember.Objective;
import net.valcheff.villagersremember.Reputation;
import net.valcheff.villagersremember.VillagerHeads;
import net.valcheff.villagersremember.VillageRegistry;
import net.valcheff.villagersremember.Villages;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The errand screens, built as vanilla dialogs: a list of buttons per villager, per village board and for the
 * journal, and a detail screen per errand. Vanilla clients render them without any mod.
 */
public final class ErrandDialogs {
	private static final int LIST_WIDTH = 320;
	private static final int TEXT_WIDTH = 320;
	/** Buttons of the bell menu, two in a row. */
	private static final int MENU_WIDTH = 155;
	private static final double REACH = 8.0;

	/** Where a detail screen was opened from: what the player may do there and how to get back. */
	private record Place(Predicate<ServerPlayer> near, Predicate<Errand> handsIn, Consumer<ServerPlayer> back) {
	}

	// ---- villager ----

	// ---- greeting: right-click on a villager ----

	/** A trading villager with something to offer (not unemployed, not a nitwit). */
	public static boolean canTrade(Villager villager) {
		String profession = ErrandGenerator.professionId(villager.getVillagerData());
		return !profession.equals("minecraft:none") && !profession.equals("minecraft:nitwit");
	}

	/**
	 * The villager greets the player the way their standing deserves, and asks how they can help: Trade, Errands,
	 * and a trust trial when one waits.
	 */
	public static void greet(ServerPlayer player, Villager villager) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = ErrandService.state(player);
		ErrandGenerator.Giver giver = ErrandGenerator.Giver.of(villager);
		int points = Reputation.villager(state, villager.getUUID());
		int tier = Reputation.villagerTier(state, villager.getUUID());
		boolean errands = ErrandGenerator.givesErrands(villager);

		Component title = Component.empty().append(VillagerHeads.face(ErrandText.path(giver.profession()), ErrandText.path(giver.type())))
			.append(" ").append(ErrandText.villagerName(player, villager.getUUID(), villager));
		// Two lines per tier; which one depends on the villager and the day, so it changes now and then.
		long day = ErrandGenerator.day(level);
		int variant = (int) Math.floorMod(villager.getUUID().getLeastSignificantBits() + day, 2L) + 1;
		String key = points < 0 ? "greet.cold." + variant : "greet." + tier + "." + variant;
		MutableComponent text = Component.empty().append(ErrandText.quotedLine(Lang.raw(Lang.language(player), key)));
		if (errands && Reputation.trialPending(state, villager.getUUID()) > 0) {
			text.append("\n").append(ErrandText.quotedLine(Lang.raw(Lang.language(player), "greet.trial")));
		}
		text.append("\n\n").append(Component.empty().append(ErrandText.profession(giver.profession()).copy())
			.append(" (").append(Component.translatable("merchant.level." + Math.clamp(giver.level(), 1, 5))).append(")")
			.withStyle(ChatFormatting.GRAY));
		if (errands) text.append("\n").append(villagerStanding(player, state, villager.getUUID()));
		if (canTrade(villager)) {
			double factor = Reputation.priceFactor(state, villager.getUUID(), giver.village());
			int percent = (int) Math.round((factor - 1) * 100);
			if (percent != 0) {
				text.append("\n").append(Lang.tr(player, "greet.prices").withStyle(ChatFormatting.GRAY))
					.append(Component.literal((percent > 0 ? "+" : "") + percent + "%")
						.withStyle(percent < 0 ? ChatFormatting.GREEN : ChatFormatting.RED));
			}
		}
		List<DialogBody> body = List.of(new PlainMessage(text, TEXT_WIDTH));

		Place place = new Place(
			p -> villager.isAlive() && villager.distanceToSqr(p) < REACH * REACH,
			e -> e.villager().equals(villager.getUUID()),
			p -> greet(p, villager));
		List<ActionButton> buttons = new ArrayList<>();
		if (errands) {
			ErrandGenerator.trial(level, giver, state).ifPresent(trial -> {
				if (ErrandService.status(player, trial) == ErrandService.Status.AVAILABLE) {
					buttons.add(button(player, Component.empty().append(Component.literal("◆ ").withStyle(ChatFormatting.GOLD))
						.append(ErrandText.task(player, trial).withStyle(ChatFormatting.GOLD)), null, 220, p -> errand(p, trial, place)));
				}
			});
			ErrandGenerator.contract(level, giver, state).ifPresent(contract -> {
				if (ErrandService.status(player, contract) == ErrandService.Status.AVAILABLE) {
					buttons.add(button(player, Component.empty().append(Component.literal("✦ ").withColor(0xFF8000))
						.append(Lang.tr(player, "contract.button").withColor(0xFF8000)).append(" ").append(targetIcon(contract)),
						null, 220, p -> errand(p, contract, place)));
				}
			});
			int open = 0;
			boolean ready = false;
			for (Errand e : ErrandGenerator.shown(level, giver, state)) {
				if (ErrandGenerator.isTrial(e) || ErrandGenerator.isContract(e)) continue;
				if (ErrandService.status(player, e) == ErrandService.Status.AVAILABLE) open++;
			}
			for (ErrandsData.Active a : state.active) {
				if (!a.errand.villager().equals(villager.getUUID())) continue;
				if (ErrandService.canHandIn(player, a)) ready = true;
				else open++;
			}
			MutableComponent label = Component.empty().append(Icons.of(Items.WRITABLE_BOOK))
				.append(Lang.tr(player, "board.errands", open).withStyle(open > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
			if (ready) label.append(Component.literal(" ✔").withStyle(ChatFormatting.GREEN));
			buttons.add(button(player, label, null, 220, p -> villager(p, villager)));
		}
		if (canTrade(villager) && !villager.getOffers().isEmpty()) {
			buttons.add(button(player, Component.empty().append(Icons.of(Items.EMERALD))
				.append(Lang.tr(player, "button.trade").withStyle(ChatFormatting.GREEN)), null, 220, p -> trade(p, villager)));
		}
		show(player, title, body, buttons, closeButton(player), 1);
	}

	/** Opens vanilla's trading screen with this villager, unless someone else is trading with them. */
	private static void trade(ServerPlayer player, Villager villager) {
		player.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
		if (!villager.isAlive() || villager.distanceToSqr(player) > REACH * REACH) return;
		if (villager.isTrading()) {
			player.sendSystemMessage(Lang.tr(player, "trade.busy").withStyle(ChatFormatting.GRAY));
			return;
		}
		((net.valcheff.villagersremember.mixin.VillagerInvoker) villager).villagersremember$startTrading(player);
	}

	public static void villager(ServerPlayer player, Villager villager) {
		Place place = new Place(
			p -> villager.isAlive() && villager.distanceToSqr(p) < REACH * REACH,
			e -> e.villager().equals(villager.getUUID()),
			p -> villager(p, villager));
		villagerView(player, ErrandGenerator.Giver.of(villager), villager, place, false);
	}

	/** One villager's errands. The entity may be null when the villager is opened from the residents list. */
	private static void villagerView(ServerPlayer player, ErrandGenerator.Giver giver, Villager entity, Place place, boolean fromList) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = ErrandService.state(player);
		String prof = ErrandText.path(giver.profession());
		String type = ErrandText.path(giver.type());
		String name = ErrandText.villagerName(player, giver.id(), entity);

		Component title = Component.empty().append(VillagerHeads.face(prof, type)).append(" ")
			.append(Lang.tr(player, "menu.villager", name));
		List<DialogBody> body = new ArrayList<>();
		MutableComponent info = Component.empty()
			.append(ErrandText.profession(giver.profession()).copy().withStyle(ChatFormatting.GRAY))
			.append(Component.literal(" (").withStyle(ChatFormatting.GRAY))
			.append(Component.translatable("merchant.level." + Math.clamp(giver.level(), 1, 5)).withStyle(ChatFormatting.GRAY))
			.append(Component.literal(")").withStyle(ChatFormatting.GRAY))
			.append("\n").append(villagerStanding(player, state, giver.id()));
		giver.village().ifPresent(v -> {
			Reputation.Standing standing = Reputation.standing(state, giver.village());
			info.append("\n").append(Icons.of(Items.BELL)).append(Component.literal(ErrandText.villageName(player, v) + ": ").withStyle(ChatFormatting.GRAY))
				.append(Lang.tr(player, "rep.village." + standing.tier()).withColor(ErrandText.tierColor(standing.tier())));
		});
		body.add(new PlainMessage(info, TEXT_WIDTH));

		// Errand screens opened from here come back to this villager.
		Place self = new Place(place.near(), place.handsIn(),
			p -> villagerView(p, giver, Villages.loaded((ServerLevel) p.level(), giver.id()), place, fromList));
		List<ActionButton> buttons = new ArrayList<>();
		List<Errand> shown = ErrandGenerator.shown(level, giver, state);
		// Accepted errands from this villager on earlier days are listed too.
		for (ErrandsData.Active a : state.active) {
			String id = a.errand.id();
			if (a.errand.villager().equals(giver.id()) && shown.stream().noneMatch(o -> o.id().equals(id))) {
				buttons.add(listButton(player, a.errand, entity, false, self));
			}
		}
		for (Errand e : shown) buttons.add(listButton(player, e, entity, false, self));
		if (buttons.isEmpty()) body.add(new PlainMessage(Lang.tr(player, "menu.no_errands").withStyle(ChatFormatting.GRAY), TEXT_WIDTH));
		buttons.add(journalButton(player, self.back()));
		// Opened from the residents list the bottom button goes back to the list; opened from the villager it closes.
		ActionButton exit = fromList ? button(player, Lang.tr(player, "menu.back"), null, 150, place.back()) : closeButton(player);
		show(player, title, body, buttons, exit, 1);
	}

	/** "Friend ■■■□□ 34/60" towards the next tier with this villager. */
	private static Component villagerStanding(ServerPlayer player, ErrandsData.PlayerState state, java.util.UUID villager) {
		int points = Reputation.villager(state, villager);
		int tier = Reputation.villagerTier(state, villager);
		MutableComponent line = Component.empty().append(Lang.tr(player, "standing.villager").withStyle(ChatFormatting.GRAY))
			.append(Lang.tr(player, "rep.villager." + tier).withColor(ErrandText.tierColor(tier)));
		if (tier < Reputation.MAX_TIER) {
			line.append(" ").append(ErrandText.bar(Math.max(0, points), Reputation.VILLAGER_TIERS[tier + 1], 8));
		}
		if (Reputation.trialPending(state, villager) > 0) {
			line.append("\n").append(Lang.tr(player, "standing.trial").withStyle(ChatFormatting.GOLD));
		}
		return line;
	}

	// ---- board ----

	/** The bell menu: standing, then the village's lists in two columns (errands, residents, journal...). */
	public static void board(ServerPlayer player, GlobalPos bell, GlobalPos center) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		Optional<VillageRegistry.Village> found = VillageRegistry.get(level.getServer()).byCenter(center);
		if (found.isEmpty()) {
			player.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
			return;
		}
		VillageRegistry.Village village = found.get();
		ErrandsData.PlayerState state = ErrandService.state(player);

		Component title = Component.empty().append(Icons.of(Items.BELL))
			.append(Lang.tr(player, "menu.board", ErrandText.villageName(player, village.center)));
		List<DialogBody> body = new ArrayList<>();
		body.add(new PlainMessage(villageStanding(player, state, village), TEXT_WIDTH));
		net.valcheff.villagersremember.Patron.check(level.getServer(), Optional.of(village.center));
		body.add(patronBody(player, state, village));

		// Errands: how many are open, with a green tick when one can be handed in here.
		int open = 0;
		boolean ready = false;
		for (Villages.Entry entry : Villages.board(player, village)) {
			ErrandService.Status status = ErrandService.status(player, entry.errand());
			if (status != ErrandService.Status.DONE) open++;
			if (status == ErrandService.Status.READY) ready = true;
		}
		MutableComponent errandsLabel = Component.empty().append(Icons.of(Items.WRITABLE_BOOK))
			.append(Lang.tr(player, "board.errands", open).withStyle(open > 0 ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
		if (ready) errandsLabel.append(Component.literal(" ✔").withStyle(ChatFormatting.GREEN));

		List<ActionButton> buttons = new ArrayList<>();
		buttons.add(button(player, errandsLabel, null, MENU_WIDTH, p -> boardErrands(p, bell, village.center)));
		buttons.add(button(player, Component.empty().append(Icons.of(Items.PLAYER_HEAD))
				.append(Lang.tr(player, "board.residents", village.workers().size()).withStyle(ChatFormatting.AQUA)),
			null, MENU_WIDTH, p -> residents(p, bell, village.center)));
		net.valcheff.villagersremember.GroupOrder.current(level.getServer(), village).ifPresent(view -> {
			MutableComponent label = Component.empty().append(Icons.of(view.kind().icon))
				.append(Lang.tr(player, "group.button", Math.min(view.order().progress, view.target()), view.target())
					.withStyle(view.done() ? ChatFormatting.GREEN : ChatFormatting.GOLD));
			if (view.done()) label.append(Component.literal(" ✔").withStyle(ChatFormatting.GREEN));
			buttons.add(button(player, label, Lang.tr(player, "group." + view.kind().key() + ".name"), MENU_WIDTH,
				p -> villageOrder(p, bell, village.center)));
		});
		buttons.add(treasuryButton(player, p -> board(p, bell, village.center), MENU_WIDTH));
		buttons.add(journalButton(player, p -> board(p, bell, village.center), MENU_WIDTH));
		String key = ErrandsData.villageKey(village.center);
		boolean trackedVillage = state.trackedVillages.contains(key);
		buttons.add(button(player, Lang.tr(player, trackedVillage ? "board.tracked_village" : "board.track_village")
				.withStyle(trackedVillage ? ChatFormatting.GOLD : ChatFormatting.WHITE),
			Lang.tr(player, trackedVillage ? "button.tracked_tip" : "button.track_tip"), MENU_WIDTH, p -> {
				ErrandsData.PlayerState st = ErrandService.state(p);
				if (!st.trackedVillages.remove(key)) st.trackedVillages.add(key);
				ErrandsData.get(p.level().getServer()).setDirty();
				net.valcheff.villagersremember.Hud.refresh(p);
				board(p, bell, village.center);
			}));
		if (player.getUUID().equals(village.patron)) {
			buttons.add(button(player, Component.empty().append(Icons.of(Items.NAME_TAG))
					.append(Lang.tr(player, "patron.rename_button").withStyle(ChatFormatting.GOLD)),
				null, MENU_WIDTH, p -> renameVillage(p, bell, village.center)));
		}
		show(player, title, body, buttons, closeButton(player), 2);
	}

	/** The patron's head and name, or how to become one. */
	private static DialogBody patronBody(ServerPlayer player, ErrandsData.PlayerState state, VillageRegistry.Village village) {
		if (village.patron == null) {
			MutableComponent text = Lang.tr(player, "patron.none").withStyle(ChatFormatting.GRAY);
			if (Reputation.villageTier(state, Optional.of(village.center)) >= Reputation.MAX_TIER) {
				text.append("\n").append(Lang.tr(player, "patron.how", net.valcheff.villagersremember.Patron.bestFriends(state, village),
					net.valcheff.villagersremember.Patron.BEST_FRIENDS).withStyle(ChatFormatting.YELLOW));
			}
			return new PlainMessage(text, TEXT_WIDTH);
		}
		ItemStack head = new ItemStack(Items.PLAYER_HEAD);
		ServerPlayer online = player.level().getServer().getPlayerList().getPlayer(village.patron);
		head.set(net.minecraft.core.component.DataComponents.PROFILE, online != null
			? net.minecraft.world.item.component.ResolvableProfile.createResolved(online.getGameProfile())
			: net.minecraft.world.item.component.ResolvableProfile.createUnresolved(village.patron));
		String name = village.patronName != null ? village.patronName : "?";
		MutableComponent text = Component.empty().append(Lang.tr(player, "patron.label").withStyle(ChatFormatting.GRAY))
			.append(Component.literal(name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		if (player.getUUID().equals(village.patron)) {
			text.append("\n").append(Lang.tr(player, "patron.you", Math.round(ErrandsConfig.INSTANCE.patronDiscount * 100))
				.withStyle(ChatFormatting.GREEN));
		} else if (net.valcheff.villagersremember.Patron.qualifies(state, village)) {
			text.append("\n").append(Lang.tr(player, "patron.challenge").withStyle(ChatFormatting.YELLOW));
		}
		return new ItemBody(ItemStackTemplate.fromNonEmptyStack(head), Optional.of(new PlainMessage(text, 220)), false, false, 16, 16);
	}

	/** The patron names the village: a text field and Save. */
	private static void renameVillage(ServerPlayer player, GlobalPos bell, GlobalPos center) {
		DialogActions.begin(player);
		Component title = Component.empty().append(Icons.of(Items.NAME_TAG)).append(Lang.tr(player, "patron.rename_title"));
		String current = ErrandText.villageName(player, center);
		List<DialogBody> body = List.of(new PlainMessage(Lang.tr(player, "patron.rename_text").withStyle(ChatFormatting.GRAY), TEXT_WIDTH));
		List<net.minecraft.server.dialog.Input> inputs = List.of(new net.minecraft.server.dialog.Input("name",
			new net.minecraft.server.dialog.input.TextInput(200, Lang.tr(player, "patron.rename_label"), true, current, 32, Optional.empty())));
		String template = DialogActions.inputCommand(player, "name", (p, text) -> {
			if (net.valcheff.villagersremember.Patron.rename(p, center, text)) {
				p.sendSystemMessage(Lang.tr(p, "patron.renamed", text.strip()).withStyle(ChatFormatting.GREEN));
				net.valcheff.villagersremember.Hud.refresh(p);
			} else {
				p.sendSystemMessage(Lang.tr(p, "patron.rename_bad").withStyle(ChatFormatting.RED));
			}
			board(p, bell, center);
		});
		net.minecraft.server.dialog.action.ParsedTemplate parsed = net.minecraft.server.dialog.action.ParsedTemplate.CODEC
			.parse(com.mojang.serialization.JavaOps.INSTANCE, template).getOrThrow();
		ActionButton save = new ActionButton(new CommonButtonData(Lang.tr(player, "patron.rename_save").withStyle(ChatFormatting.GREEN),
			Optional.empty(), 150), Optional.of(new net.minecraft.server.dialog.action.CommandTemplate(parsed)));
		ActionButton back = button(player, Lang.tr(player, "menu.back"), null, 150, p -> board(p, bell, center));
		CommonDialogData common = new CommonDialogData(title, Optional.empty(), true, false, DialogAction.NONE, body, inputs);
		player.openDialog(Holder.direct(new MultiActionDialog(common, List.of(save), Optional.of(back), 1)));
	}

	/** The village's monthly order: what is wanted, how far it is, who helped, and a button to bring what you have. */
	private static void villageOrder(ServerPlayer player, GlobalPos bell, GlobalPos center) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		Optional<VillageRegistry.Village> found = VillageRegistry.get(level.getServer()).byCenter(center);
		Optional<net.valcheff.villagersremember.GroupOrder.View> current = found.flatMap(v -> net.valcheff.villagersremember.GroupOrder.current(level.getServer(), v));
		if (current.isEmpty()) {
			player.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
			return;
		}
		VillageRegistry.Village village = found.get();
		net.valcheff.villagersremember.GroupOrder.View view = current.get();
		String kind = view.kind().key();
		ErrandsConfig cfg = ErrandsConfig.INSTANCE;
		Component title = Component.empty().append(Icons.of(view.kind().icon))
			.append(Lang.tr(player, "group.title", Lang.tr(player, "group." + kind + ".name")).withStyle(ChatFormatting.GOLD));
		int progress = Math.min(view.order().progress, view.target());
		int mine = view.order().contrib.getOrDefault(player.getUUID(), 0);
		MutableComponent text = Component.empty()
			.append(ErrandText.quotedLine(Lang.raw(Lang.language(player), "group." + kind + ".desc")))
			.append("\n\n").append(Lang.tr(player, "group." + kind + ".want", view.target()).withStyle(ChatFormatting.WHITE))
			.append("\n").append(Component.literal(net.valcheff.villagersremember.GroupOrder.examples(player, view.kind())).withStyle(ChatFormatting.DARK_GRAY))
			.append("\n\n").append(ErrandText.bar(progress, view.target(), 20))
			.append("\n").append(view.done() ? Lang.tr(player, "group.done").withStyle(ChatFormatting.GREEN)
				: Lang.tr(player, "group.days_left", view.daysLeft()).withStyle(ChatFormatting.GRAY))
			.append("\n").append(Lang.tr(player, "group.mine", mine, Math.round(100.0 * mine / view.target())).withStyle(ChatFormatting.AQUA));
		List<java.util.Map.Entry<java.util.UUID, Integer>> top = net.valcheff.villagersremember.GroupOrder.top(view.order(), 5);
		if (!top.isEmpty()) {
			text.append("\n\n").append(Lang.tr(player, "group.helpers").withStyle(ChatFormatting.GOLD));
			for (var e : top) {
				ServerPlayer online = level.getServer().getPlayerList().getPlayer(e.getKey());
				String who = online != null ? online.getGameProfile().name()
					: level.getServer().services().nameToIdCache().get(e.getKey()).map(n -> n.name()).orElse("?");
				text.append("\n").append(Component.literal(who + ": " + e.getValue()).withStyle(ChatFormatting.WHITE));
			}
		}
		text.append("\n").append(Lang.tr(player, "group.players", Math.max(1, view.order().players)).withStyle(ChatFormatting.DARK_GRAY));
		text.append("\n\n").append(Lang.tr(player, "group.reward", Math.round(net.valcheff.villagersremember.GroupOrder.pool(view.order().players)),
			Math.round(cfg.groupMinShare * 100))
			.withStyle(ChatFormatting.DARK_GREEN));
		List<DialogBody> body = List.of(new PlainMessage(text, TEXT_WIDTH));
		List<ActionButton> buttons = new ArrayList<>();
		String orderKey = ErrandsData.villageKey(village.center);
		boolean trackedOrder = ErrandService.state(player).trackedOrders.contains(orderKey);
		buttons.add(button(player, Lang.tr(player, trackedOrder ? "button.tracked" : "button.track")
				.withStyle(trackedOrder ? ChatFormatting.GOLD : ChatFormatting.WHITE),
			Lang.tr(player, trackedOrder ? "button.tracked_tip" : "button.track_tip"), 300, p -> {
				ErrandsData.PlayerState st = ErrandService.state(p);
				if (!st.trackedOrders.remove(orderKey)) st.trackedOrders.add(orderKey);
				ErrandsData.get(p.level().getServer()).setDirty();
				net.valcheff.villagersremember.Hud.refresh(p);
				villageOrder(p, bell, center);
			}));
		if (!view.done()) {
			if (view.kind() == net.valcheff.villagersremember.GroupOrder.Kind.DEFEND) {
				buttons.add(button(player, Lang.tr(player, "group.defend_hint").withStyle(ChatFormatting.GRAY), null, 300,
					p -> villageOrder(p, bell, center)));
			} else {
				int have = net.valcheff.villagersremember.GroupOrder.available(player, view);
				buttons.add(button(player, have > 0 ? Lang.tr(player, "group.give", have).withStyle(ChatFormatting.GREEN)
						: Lang.tr(player, "group.nothing").withStyle(ChatFormatting.GRAY), null, 300, p -> {
					int given = net.valcheff.villagersremember.GroupOrder.contribute(p, village);
					if (given > 0) {
						p.sendSystemMessage(Lang.tr(p, "group.given", given).withStyle(ChatFormatting.GREEN));
						ErrandService.sound(p, net.minecraft.sounds.SoundEvents.VILLAGER_YES);
					}
					villageOrder(p, bell, center);
				}));
			}
		}
		show(player, title, body, buttons, button(player, Lang.tr(player, "menu.back"), null, 150, p -> board(p, bell, center)), 1);
	}

	/** The village's errands: accepted ones first, then today's offers, then the ones done today. */
	private static void boardErrands(ServerPlayer player, GlobalPos bell, GlobalPos center) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		Optional<VillageRegistry.Village> found = VillageRegistry.get(level.getServer()).byCenter(center);
		if (found.isEmpty()) {
			player.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE);
			return;
		}
		VillageRegistry.Village village = found.get();
		Place place = new Place(
			p -> p.level().dimension().equals(bell.dimension()) && p.distanceToSqr(Vec3.atCenterOf(bell.pos())) < REACH * REACH,
			e -> e.village().map(v -> VillageRegistry.get(level.getServer()).canonical(v).equals(village.center)).orElse(false),
			p -> boardErrands(p, bell, village.center));

		Component title = Component.empty().append(Icons.of(Items.WRITABLE_BOOK))
			.append(Lang.tr(player, "menu.board_errands", ErrandText.villageName(player, village.center)));
		List<DialogBody> body = new ArrayList<>();
		List<ActionButton> buttons = new ArrayList<>();
		for (Villages.Entry entry : Villages.board(player, village)) {
			buttons.add(listButton(player, entry.errand(), entry.villager(), true, place));
		}
		if (buttons.isEmpty()) body.add(new PlainMessage(Lang.tr(player, "menu.board_empty").withStyle(ChatFormatting.GRAY), TEXT_WIDTH));
		else body.add(new PlainMessage(Lang.tr(player, "menu.board_help").withStyle(ChatFormatting.DARK_GRAY), TEXT_WIDTH));
		show(player, title, body, buttons, button(player, Lang.tr(player, "menu.back"), null, 150, p -> board(p, bell, village.center)), 1);
	}

	/** Village tier, points towards the next tier, and how many residents are still needed for it. */
	private static Component villageStanding(ServerPlayer player, ErrandsData.PlayerState state, VillageRegistry.Village village) {
		Reputation.Standing s = Reputation.standing(state, Optional.of(village.center));
		MutableComponent out = Component.empty()
			.append(Lang.tr(player, "standing.village").withStyle(ChatFormatting.GRAY))
			.append(Lang.tr(player, "rep.village." + s.tier()).withColor(ErrandText.tierColor(s.tier())).withStyle(ChatFormatting.BOLD));
		if (s.max()) return out.append("\n").append(Lang.tr(player, "standing.max").withStyle(ChatFormatting.GOLD));
		Component next = Lang.tr(player, "rep.village." + (s.tier() + 1)).withColor(ErrandText.tierColor(s.tier() + 1));
		out.append("\n").append(Lang.tr(player, "standing.next", next)).append(" ")
			.append(ErrandText.bar(Math.max(0, s.points()), s.nextPoints(), 10));
		Component villagerTier = Lang.tr(player, "rep.villager." + (s.tier() + 1)).withColor(ErrandText.tierColor(s.tier() + 1));
		out.append("\n").append(Icons.of(Items.PLAYER_HEAD)).append(Lang.tr(player, "standing.need", villagerTier)).append(" ")
			.append(ErrandText.bar(s.haveCount(), s.needCount(), s.needCount()));
		if (s.points() >= s.nextPoints() && s.haveCount() < s.needCount()) {
			out.append("\n").append(Lang.tr(player, "standing.points_ready", s.needCount() - s.haveCount(), villagerTier)
				.withStyle(ChatFormatting.YELLOW));
		}
		double bonus = Reputation.VILLAGE_BONUS[s.tier()];
		if (bonus > 0) out.append("\n").append(Lang.tr(player, "reward.bonus", Math.round(bonus * 100)).withStyle(ChatFormatting.DARK_GREEN));
		return out;
	}

	// ---- residents ----

	private static void residents(ServerPlayer player, GlobalPos bell, GlobalPos center) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		Optional<VillageRegistry.Village> found = VillageRegistry.get(level.getServer()).byCenter(center);
		if (found.isEmpty()) {
			board(player, bell, center);
			return;
		}
		VillageRegistry.Village village = found.get();
		ErrandsData.PlayerState state = ErrandService.state(player);
		Place place = new Place(
			p -> p.level().dimension().equals(bell.dimension()) && p.distanceToSqr(Vec3.atCenterOf(bell.pos())) < REACH * REACH,
			e -> e.village().map(v -> VillageRegistry.get(level.getServer()).canonical(v).equals(village.center)).orElse(false),
			p -> residents(p, bell, village.center));

		Component title = Component.empty().append(Icons.of(Items.PLAYER_HEAD))
			.append(Lang.tr(player, "residents.title", ErrandText.villageName(player, village.center)));
		List<DialogBody> body = new ArrayList<>();
		body.add(new PlainMessage(Lang.tr(player, "residents.help").withStyle(ChatFormatting.DARK_GRAY), TEXT_WIDTH));

		List<VillageRegistry.Resident> list = new ArrayList<>(village.residents.values());
		list.removeIf(r -> r.baby);
		list.sort(java.util.Comparator.comparingInt((VillageRegistry.Resident r) -> -Reputation.villager(state, r.id))
			.thenComparing(r -> r.profession));
		// Name and trade on the left; stars (one per tier, in the tier's colour) and the bar on the right, lined up.
		List<Component> left = new ArrayList<>();
		List<Component> right = new ArrayList<>();
		List<Component> tips = new ArrayList<>();
		List<Consumer<ServerPlayer>> actions = new ArrayList<>();
		for (VillageRegistry.Resident r : list) {
			Villager entity = Villages.loaded(level, r.id);
			int points = Reputation.villager(state, r.id);
			int tier = Reputation.villagerTier(state, r.id);
			left.add(Component.empty()
				.append(VillagerHeads.face(ErrandText.path(r.profession), ErrandText.path(r.type))).append(" ")
				.append(Component.literal(ErrandText.villagerName(player, r.id, entity)).withColor(ErrandText.tierColor(tier)))
				.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
				.append(ErrandText.profession(r.profession).copy().withStyle(ChatFormatting.GRAY)));
			MutableComponent standing = Component.empty().append(ErrandText.stars(tier));
			if (tier < Reputation.MAX_TIER) standing.append(" ").append(ErrandText.bar(Math.max(0, points), Reputation.VILLAGER_TIERS[tier + 1], 5));
			right.add(standing);
			MutableComponent tip = Component.empty().append(Lang.tr(player, "rep.villager." + tier).withColor(ErrandText.tierColor(tier)))
				.append("\n").append(ErrandText.profession(r.profession).copy())
				.append(" (").append(Component.translatable("merchant.level." + Math.clamp(r.level, 1, 5))).append(")");
			if (Reputation.trialPending(state, r.id) > 0) tip.append("\n").append(Lang.tr(player, "residents.trial").withStyle(ChatFormatting.GOLD));
			if (!r.working()) tip.append("\n").append(Lang.tr(player, "residents.not_counted").withStyle(ChatFormatting.GRAY));
			if (entity == null) tip.append("\n").append(Lang.tr(player, "residents.away").withStyle(ChatFormatting.DARK_GRAY));
			tips.add(tip);
			ErrandGenerator.Giver giver = ErrandGenerator.Giver.of(village, r);
			actions.add(giver.givesErrands()
				? p -> villagerView(p, giver, Villages.loaded((ServerLevel) p.level(), r.id), place, true)
				: p -> residents(p, bell, village.center));
		}
		List<MutableComponent> labels = TextWidth.columns(left, right, Lang.language(player));
		List<ActionButton> buttons = new ArrayList<>();
		for (int i = 0; i < labels.size(); i++) buttons.add(button(player, labels.get(i), tips.get(i), LIST_WIDTH, actions.get(i)));
		if (buttons.isEmpty()) body.add(new PlainMessage(Lang.tr(player, "residents.none").withStyle(ChatFormatting.GRAY), TEXT_WIDTH));
		show(player, title, body, buttons, button(player, Lang.tr(player, "menu.back"), null, 150, p -> board(p, bell, village.center)), 1);
	}

	// ---- journal ----

	public static void journal(ServerPlayer player, Consumer<ServerPlayer> back) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = ErrandService.state(player);
		Place place = new Place(p -> false, e -> false, p -> journal(p, back));
		Component title = Component.empty().append(Icons.of(Items.WRITABLE_BOOK)).append(Lang.tr(player, "menu.journal"));
		List<DialogBody> body = new ArrayList<>();
		body.add(new PlainMessage(Lang.tr(player, "menu.journal_count", state.active.size(), ErrandsConfig.INSTANCE.maxActiveErrands)
			.withStyle(ChatFormatting.GRAY), TEXT_WIDTH));
		List<ActionButton> buttons = new ArrayList<>();
		for (ErrandsData.Active a : state.active) {
			Villager villager = level.getEntity(a.errand.villager()) instanceof Villager v ? v : null;
			buttons.add(listButton(player, a.errand, villager, true, place));
		}
		if (buttons.isEmpty()) {
			body.add(new PlainMessage(Lang.tr(player, "journal.empty").withStyle(ChatFormatting.GRAY)
				.append("\n").append(Lang.tr(player, "journal.how").withStyle(ChatFormatting.DARK_GRAY)), TEXT_WIDTH));
		}
		buttons.add(treasuryButton(player, p -> journal(p, back), LIST_WIDTH));
		buttons.add(button(player, Component.empty().append(Icons.of(Items.WRITTEN_BOOK))
			.append(Lang.tr(player, "menu.chronicle").withStyle(ChatFormatting.GOLD)), null, LIST_WIDTH, Chronicle::open));
		ActionButton exit = back != null ? button(player, Lang.tr(player, "menu.back"), null, 150, back) : closeButton(player);
		show(player, title, body, buttons, exit, 1);
	}

	// ---- one errand ----

	private static void errand(ServerPlayer player, Errand errand, Place place) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = ErrandService.state(player);
		Villager villager = level.getEntity(errand.villager()) instanceof Villager v ? v : null;
		ErrandService.Status status = ErrandService.status(player, errand);
		ErrandsData.Active active = state.find(errand.id());

		// The dialog title sits in the top edge and is hard to read over the world, so the task itself is the
		// first body line, next to its icon; the title only names the giver.
		Component title = Component.empty().append(face(errand)).append(" ")
			.append(ErrandText.villagerName(player, errand.villager(), villager));
		List<DialogBody> body = new ArrayList<>();
		// Right under the name in the title: profession and level, then the village and where it is.
		MutableComponent who = Component.empty()
			.append(ErrandText.profession(errand.profession()).copy())
			.append(" (").append(Component.translatable("merchant.level." + Math.clamp(errand.villagerLevel(), 1, 5))).append(")")
			// The pack it came from, as the stars of the standing that unlocks it.
			.append("  ").append(ErrandText.stars(errand.tier() - 1));
		errand.village().ifPresent(v -> who.append("\n").append(Lang.tr(player, "journal.village", ErrandText.villageName(player, v),
			v.pos().getX(), v.pos().getY(), v.pos().getZ())));
		body.add(new PlainMessage(who.withStyle(ChatFormatting.GRAY), TEXT_WIDTH));
		ItemStack icon = ErrandText.icon(errand);
		MutableComponent heading = Component.empty()
			.append(ErrandText.task(player, errand).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
		ErrandText.lore(player, errand).ifPresent(l -> heading.append("\n\n").append(l));
		if (ErrandGenerator.isContract(errand)) {
			long today = ErrandGenerator.day((ServerLevel) player.level());
			long left = (ErrandGenerator.week(today) + 1) * 7 - today;
			heading.append("\n").append(Lang.tr(player, "contract.days_left", left).withStyle(ChatFormatting.GRAY));
		}
		body.add(new ItemBody(ItemStackTemplate.fromNonEmptyStack(icon), Optional.of(new PlainMessage(heading, 220)),
			true, true, 16, 16));

		// One line per objective, with its own bar once accepted. The client centres every line, so TextWidth pads
		// them to one width: the texts start in one column and the bars end in another.
		// Where to cook or smelt is said once, below them.
		java.util.Set<String> stations = new java.util.LinkedHashSet<>();
		List<Component> left = new ArrayList<>();
		List<Component> right = new ArrayList<>();
		for (int k = 0; k < errand.objectives().size(); k++) {
			Objective o = errand.objectives().get(k);
			left.add(Component.empty().append(Icons.of(ErrandText.icon(o).getItem()))
				.append(ErrandText.objectiveShort(player, o).withStyle(ChatFormatting.WHITE)));
			right.add(active != null ? ErrandText.bar(ErrandService.progress(player, active, k), o.needed(), 5) : null);
			if (o.type() == Objective.Type.SMELT) stations.add(ErrandText.smeltVariant(o));
		}
		String lang = Lang.language(player);
		MutableComponent objectives = Component.empty();
		int widest = 0;
		for (MutableComponent line : TextWidth.columns(left, right, lang)) {
			objectives.append("\n").append(line);
			widest = Math.max(widest, (int) Math.ceil(TextWidth.of(line, lang)));
		}
		body.add(new PlainMessage(objectives, Math.clamp(widest + 4, TEXT_WIDTH, 1024)));

		MutableComponent text = Component.empty();
		for (String variant : stations) {
			text.append(Lang.tr(player, "hint.smelt" + variant).withStyle(ChatFormatting.DARK_GRAY)).append("\n");
		}
		text.append("\n").append(rewards(player, level, errand, state));
		text.append("\n\n").append(statusLine(player, errand, status, place));
		body.add(new PlainMessage(text, TEXT_WIDTH));

		List<ActionButton> buttons = new ArrayList<>();
		boolean here = place.near().test(player) && place.handsIn().test(errand);
		switch (status) {
			case AVAILABLE -> {
				if (here) buttons.add(button(player, Lang.tr(player, "button.accept").withStyle(ChatFormatting.GREEN), null, 150, p -> {
					if (place.near().test(p) && ErrandService.accept(p, errand)) place.back().accept(p);
					else errand(p, errand, place);
				}));
			}
			case ACCEPTED, READY -> {
				boolean isTracked = state.isTracked(errand.id());
				buttons.add(button(player, (isTracked ? Lang.tr(player, "button.tracked") : Lang.tr(player, "button.track"))
					.withStyle(isTracked ? ChatFormatting.GOLD : ChatFormatting.WHITE),
					Lang.tr(player, isTracked ? "button.tracked_tip" : "button.track_tip"), 150, p -> {
					ErrandService.track(p, errand.id());
					errand(p, errand, place);
				}));
				if (here && status != ErrandService.Status.READY) {
					// Big orders don't fit in one inventory: what the player has now can be given, the rest later.
					ErrandsData.Active accepted = state.find(errand.id());
					int givable = accepted != null ? ErrandService.givable(player, accepted) : 0;
					if (givable > 0) {
						buttons.add(button(player, Lang.tr(player, "button.give_part", givable).withStyle(ChatFormatting.YELLOW),
							Lang.tr(player, "button.give_part_tip"), 150, p -> {
								if (place.near().test(p)) ErrandService.givePart(p, errand.id());
								errand(p, errand, place);
							}));
					}
				}
				if (here) {
					Component label = Lang.tr(player, "button.hand_in")
						.withStyle(status == ErrandService.Status.READY ? ChatFormatting.GREEN : ChatFormatting.GRAY);
					buttons.add(button(player, label, null, 150, p -> {
						if (!place.near().test(p)) {
							errand(p, errand, place);
						} else if (TopRewards.hasChoice(p, errand) && ErrandService.canHandIn(p, ErrandService.state(p).find(errand.id()))) {
							chooseTop(p, errand, place);
						} else if (ErrandService.handIn(p, errand.id())) {
							place.back().accept(p);
						} else {
							errand(p, errand, place);
						}
					}));
				}
				buttons.add(button(player, Lang.tr(player, "button.abandon").withStyle(ChatFormatting.RED), null, 150,
					p -> confirmAbandon(p, errand, place)));
			}
			case DONE -> {
			}
		}
		show(player, title, body, buttons, button(player, Lang.tr(player, "menu.back"), null, 150, place.back()), 2);
	}

	/**
	 * After Hand in, for a top reward with options: the magic is shown now, one option per button, each with what
	 * sets it apart; the errand is handed in when the player picks one. Leaving keeps the errand ready.
	 */
	public static void chooseTop(ServerPlayer player, Errand errand, Place place) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		List<List<ItemStack>> options = TopRewards.options(errand.profession(), level, errand.item().get().seed(), null);
		Component title = Component.empty().append(Icons.of(Items.NETHER_STAR)).append(Lang.tr(player, "choose.title"));
		List<DialogBody> body = new ArrayList<>();
		body.add(new PlainMessage(Lang.tr(player, "choose.help", ErrandText.profession(errand.profession())).withStyle(ChatFormatting.GRAY), TEXT_WIDTH));
		List<ActionButton> buttons = new ArrayList<>();
		for (int k = 0; k < options.size(); k++) {
			int option = k;
			List<ItemStack> stacks = options.get(k);
			ItemStack first = stacks.getFirst();
			MutableComponent label = Component.empty().append(Icons.of(first.getItem()))
				.append(Component.empty().append(first.getCount() > 1 ? first.getCount() + "× " : "").append(first.getHoverName()).withStyle(ChatFormatting.AQUA));
			Component apart = TopRewards.distinction(player, options, k);
			if (apart != null) label.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY)).append(apart);
			for (int i = 1; i < stacks.size(); i++) {
				label.append(Component.literal(" + " + stacks.get(i).getCount() + "× ").withStyle(ChatFormatting.GRAY))
					.append(Component.empty().append(stacks.get(i).getHoverName()).withStyle(ChatFormatting.GRAY));
			}
			// Hover: every enchantment of the option.
			MutableComponent tip = Component.empty();
			for (ItemStack st : stacks) {
				if (!tip.getString().isEmpty()) tip.append("\n");
				tip.append(Component.empty().append(st.getCount() + "× ").append(st.getHoverName()).withStyle(ChatFormatting.AQUA));
				Component ench = ErrandText.enchantments(st);
				if (!ench.getString().isEmpty()) tip.append("\n").append(ench);
			}
			buttons.add(button(player, label, tip, LIST_WIDTH, p -> {
				if (place.near().test(p) && ErrandService.handIn(p, errand.id(), option)) place.back().accept(p);
				else errand(p, errand, place);
			}));
		}
		show(player, title, body, buttons, button(player, Lang.tr(player, "menu.back"), null, 150, p -> errand(p, errand, place)), 1);
	}

	/** For a test or a command: the choice screen of an accepted errand, wherever the player is. */
	public static void chooseTop(ServerPlayer player, String errandId) {
		ErrandsData.Active a = ErrandService.state(player).find(errandId);
		if (a == null) return;
		Place anywhere = new Place(p -> true, e -> true, p -> journal(p, null));
		chooseTop(player, a.errand, anywhere);
	}

	private static void confirmAbandon(ServerPlayer player, Errand errand, Place place) {
		DialogActions.begin(player);
		Component title = Lang.tr(player, "button.abandon");
		MutableComponent text = Lang.tr(player, "confirm.abandon", ErrandText.task(player, errand));
		ErrandsData.Active accepted = ErrandService.state(player).find(errand.id());
		if (accepted != null) {
			int given = 0;
			for (int i = 0; i < errand.objectives().size(); i++) given += ErrandService.delivered(accepted, i);
			if (given > 0) text.append("\n").append(Lang.tr(player, "confirm.abandon_kept", given).withStyle(ChatFormatting.GOLD));
		}
		List<DialogBody> body = List.of(new PlainMessage(text, TEXT_WIDTH));
		List<ActionButton> buttons = List.of(button(player, Lang.tr(player, "button.abandon_yes").withStyle(ChatFormatting.RED), null, 150, p -> {
			ErrandService.abandon(p, errand.id());
			place.back().accept(p);
		}));
		show(player, title, body, buttons, button(player, Lang.tr(player, "menu.back"), null, 150, p -> errand(p, errand, place)), 2);
	}

	// ---- pieces ----

	private static ActionButton listButton(ServerPlayer player, Errand errand, Villager villager, boolean withFace, Place place) {
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = ErrandService.state(player);
		ErrandService.Status status = ErrandService.status(player, errand);
		ChatFormatting color = switch (status) {
			case AVAILABLE -> ChatFormatting.WHITE;
			case ACCEPTED -> ChatFormatting.YELLOW;
			case READY -> ChatFormatting.GREEN;
			case DONE -> ChatFormatting.DARK_GRAY;
		};
		MutableComponent label = Component.empty();
		if (state.isTracked(errand.id())) label.append(Component.literal("★ ").withStyle(ChatFormatting.GOLD));
		if (withFace) label.append(face(errand)).append(" ");
		if (ErrandGenerator.isTrial(errand)) label.append(Component.literal("◆ ").withStyle(ChatFormatting.GOLD));
		label.append(targetIcon(errand)).append(ErrandText.task(player, errand).withStyle(color));
		switch (status) {
			case ACCEPTED -> {
				ErrandsData.Active a = state.find(errand.id());
				int focus = ErrandService.focus(player, a);
				label.append("  ").append(ErrandText.bar(ErrandService.progress(player, a, focus), errand.objectives().get(focus).needed(), 5));
			}
			case READY -> label.append(Component.literal("  ✔").withStyle(ChatFormatting.GREEN));
			case DONE -> label.append(Component.literal("  ✔").withStyle(ChatFormatting.DARK_GRAY));
			default -> {
			}
		}
		MutableComponent tooltip = Component.empty();
		ErrandText.lore(player, errand).ifPresent(l -> tooltip.append(l).append("\n"));
		tooltip.append(ErrandText.villagerLine(player, errand, villager).withStyle(ChatFormatting.GRAY))
			.append("\n\n").append(rewards(player, level, errand, state));
		return button(player, label, tooltip, LIST_WIDTH, p -> errand(p, errand, place));
	}

	// ---- treasury ----

	private static ActionButton treasuryButton(ServerPlayer player, Consumer<ServerPlayer> back, int width) {
		ErrandsData.PlayerState state = ErrandService.state(player);
		Treasury.settle(player.level().getServer(), state, ErrandGenerator.day((ServerLevel) player.level()));
		int waiting = (int) Math.floor(Treasury.waiting(state));
		Component label = Component.empty().append(Icons.of(Items.EMERALD))
			.append(Lang.tr(player, "board.treasury", waiting).withStyle(waiting > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY));
		return button(player, label, null, width, p -> treasury(p, back));
	}

	/** Every village the player has found: income a day and what waits, lined up; sends the emeralds to the ender chest. */
	public static void treasury(ServerPlayer player, Consumer<ServerPlayer> back) {
		DialogActions.begin(player);
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = ErrandService.state(player);
		Treasury.settle(level.getServer(), state, ErrandGenerator.day(level));
		List<Treasury.Entry> entries = Treasury.entries(player, state);
		double perDay = entries.stream().mapToDouble(Treasury.Entry::perDay).sum();
		double waiting = Treasury.waiting(state);
		int friendly = Treasury.friendlyVillages(level.getServer(), state);

		Component title = Component.empty().append(Icons.of(Items.EMERALD)).append(Lang.tr(player, "treasury.title"));
		List<DialogBody> body = new ArrayList<>();
		MutableComponent head = Component.empty()
			.append(Lang.tr(player, "treasury.income", money(perDay)).withStyle(ChatFormatting.GREEN))
			.append("\n").append(Lang.tr(player, "treasury.waiting", (int) Math.floor(waiting)).withStyle(ChatFormatting.YELLOW));
		if (friendly > 0) {
			head.append("\n").append(Lang.tr(player, friendly == 1 ? "treasury.network.one" : "treasury.network",
					Math.round(Treasury.networkBonus(friendly) * 100), friendly)
				.withStyle(ChatFormatting.DARK_GREEN));
		}
		head.append("\n").append(Lang.tr(player, "treasury.help", ErrandsConfig.INSTANCE.treasuryKeepDays).withStyle(ChatFormatting.GRAY));
		body.add(new PlainMessage(head, TEXT_WIDTH));

		if (entries.isEmpty()) {
			body.add(new PlainMessage(Lang.tr(player, "treasury.none").withStyle(ChatFormatting.GRAY), TEXT_WIDTH));
		} else {
			// Name and where it is on the left; income a day and what waits on the right, lined up.
			List<Component> left = new ArrayList<>();
			List<Component> right = new ArrayList<>();
			for (Treasury.Entry e : entries) {
				int tier = Reputation.villageTier(state, Optional.of(e.center()));
				left.add(Component.empty().append(Icons.of(Items.BELL))
					.append(Component.literal(ErrandText.villageName(player, e.center())).withColor(ErrandText.tierColor(tier)))
					.append(Component.literal("  ").append(where(player, e.center())).withStyle(ChatFormatting.GRAY)));
				right.add(Component.empty()
					.append(Lang.tr(player, "treasury.per_day", money(e.perDay())).withStyle(e.perDay() > 0 ? ChatFormatting.GREEN : ChatFormatting.GRAY))
					.append("  ").append(Icons.of(Items.EMERALD))
					.append(Component.literal(String.valueOf((int) Math.floor(e.waiting()))).withStyle(e.waiting() >= 1 ? ChatFormatting.YELLOW : ChatFormatting.GRAY)));
			}
			String lang = Lang.language(player);
			MutableComponent list = Component.empty();
			int widest = 0;
			boolean first = true;
			for (MutableComponent line : TextWidth.columns(left, right, lang)) {
				if (!first) list.append("\n");
				first = false;
				list.append(line);
				widest = Math.max(widest, (int) Math.ceil(TextWidth.of(line, lang)));
			}
			body.add(new PlainMessage(list, Math.clamp(widest + 4, TEXT_WIDTH, 1024)));
		}

		List<ActionButton> buttons = new ArrayList<>();
		boolean any = waiting >= 1;
		buttons.add(button(player, Component.empty().append(Icons.of(Items.ENDER_CHEST))
				.append(Lang.tr(player, "treasury.send").withStyle(any ? ChatFormatting.GREEN : ChatFormatting.GRAY)),
			null, 200, p -> {
				ErrandsData.PlayerState st = ErrandService.state(p);
				Treasury.settle(p.level().getServer(), st, ErrandGenerator.day((ServerLevel) p.level()));
				int before = (int) Math.floor(Treasury.waiting(st));
				int sent = Treasury.sendToEnderChest(p, st);
				st.treasurySent += sent;
				net.valcheff.villagersremember.Milestones.check(p);
				if (sent > 0) {
					p.sendSystemMessage(Lang.tr(p, "treasury.sent", sent).withStyle(ChatFormatting.GREEN));
					ErrandService.sound(p, net.minecraft.sounds.SoundEvents.ENDER_CHEST_OPEN);
				}
				if (sent < before) p.sendSystemMessage(Lang.tr(p, "treasury.full", before - sent).withStyle(ChatFormatting.YELLOW));
				treasury(p, back);
			}));
		ActionButton exit = back != null ? button(player, Lang.tr(player, "menu.back"), null, 150, back) : closeButton(player);
		show(player, title, body, buttons, exit, 1);
	}

	/** "1.5" with one decimal, "12" from ten up. */
	private static String money(double amount) {
		return amount >= 10 ? String.valueOf(Math.round(amount)) : String.format(java.util.Locale.ROOT, "%.1f", amount);
	}

	/** "340 m NE" from the player, or the dimension when the village is in another one. */
	private static Component where(ServerPlayer player, GlobalPos center) {
		if (!center.dimension().equals(player.level().dimension())) return Lang.tr(player, "treasury.elsewhere");
		double dx = center.pos().getX() + 0.5 - player.getX();
		double dz = center.pos().getZ() + 0.5 - player.getZ();
		int distance = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
		if (distance < 16) return Lang.tr(player, "treasury.here");
		// 0 = north, clockwise in eighths; Minecraft's north is -z.
		int eighth = (int) Math.floorMod(Math.round(Math.toDegrees(Math.atan2(dx, -dz)) / 45.0), 8L);
		String[] dirs = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
		return Lang.tr(player, "treasury.distance", distance, Lang.tr(player, "dir." + dirs[eighth]));
	}

	private static ActionButton journalButton(ServerPlayer player, Consumer<ServerPlayer> back) {
		return journalButton(player, back, LIST_WIDTH);
	}

	private static ActionButton journalButton(ServerPlayer player, Consumer<ServerPlayer> back, int width) {
		int active = ErrandService.state(player).active.size();
		Component label = Component.empty().append(Icons.of(Items.BOOK))
			.append(Lang.tr(player, "menu.journal").withStyle(ChatFormatting.GOLD))
			.append(Component.literal(" (" + active + "/" + ErrandsConfig.INSTANCE.maxActiveErrands + ")").withStyle(ChatFormatting.GRAY));
		return button(player, label, null, width, p -> journal(p, back));
	}

	/** With after-action NONE a button without an action does nothing, so the server closes the dialog itself. */
	private static ActionButton closeButton(ServerPlayer player) {
		return button(player, Lang.tr(player, "menu.close"), null, 150,
			p -> p.connection.send(net.minecraft.network.protocol.common.ClientboundClearDialogPacket.INSTANCE));
	}

	private static ActionButton button(ServerPlayer player, Component label, Component tooltip, int width, Consumer<ServerPlayer> action) {
		String command = DialogActions.command(player, action);
		return new ActionButton(new CommonButtonData(label, Optional.ofNullable(tooltip), width),
			Optional.of(new StaticAction(new ClickEvent.RunCommand(command))));
	}

	private static void show(ServerPlayer player, Component title, List<DialogBody> body, List<ActionButton> buttons,
	                         ActionButton exit, int columns) {
		// NONE keeps the current dialog on screen after a click until the server sends the next one. With
		// WAIT_FOR_RESPONSE the client swaps the dialog for its "Waiting for server" screen, and vanilla's
		// clearDialog() only closes a DialogScreen: every answer that closes the dialog (Close, Trade, an unknown
		// token) was ignored and the player stayed on "Waiting for server" until pressing Back.
		CommonDialogData common = new CommonDialogData(title, Optional.empty(), true, false, DialogAction.NONE,
			body, List.of());
		Dialog dialog = buttons.isEmpty()
			? new MultiActionDialog(common, List.of(exit), Optional.empty(), 1)
			: new MultiActionDialog(common, buttons, Optional.of(exit), columns);
		player.openDialog(Holder.direct(dialog));
	}

	private static Component face(Errand errand) {
		return VillagerHeads.face(ErrandText.path(errand.profession()), ErrandText.path(errand.villagerType()));
	}

	private static Component targetIcon(Errand errand) {
		return Icons.of(ErrandText.icon(errand).getItem());
	}

	private static Component rewards(ServerPlayer player, ServerLevel level, Errand errand, ErrandsData.PlayerState state) {
		double bonus = Reputation.VILLAGE_BONUS[Reputation.villageTier(state, errand.village())];
		MutableComponent out = Lang.tr(player, "errand.rewards").withStyle(ChatFormatting.GOLD);
		int emeralds = ErrandText.withBonus(errand.emeralds(), bonus);
		int xp = ErrandText.withBonus(errand.xp(), bonus);
		if (emeralds > 0) out.append("\n ").append(Icons.of(Items.EMERALD)).append(Lang.tr(player, "reward.emeralds", emeralds).withStyle(ChatFormatting.GREEN));
		if (xp > 0) out.append("\n ").append(Icons.of(Items.EXPERIENCE_BOTTLE)).append(Lang.tr(player, "reward.xp", xp).withStyle(ChatFormatting.YELLOW));
		errand.item().ifPresent(item -> {
			if (item.kind() == ErrandPools.RewardKind.LOOT && !item.id().contains(":top/map_")) {
				out.append("\n ").append(Icons.of(Items.CHEST)).append(Lang.tr(player, "reward.surprise", ErrandText.profession(errand.profession()))
					.withStyle(ChatFormatting.LIGHT_PURPLE));
			} else {
				if (item.kind() == ErrandPools.RewardKind.TOP) {
					// The magic stays a surprise until the errand is handed in: only what can be chosen is named.
					out.append("\n ").append(Lang.tr(player, "reward.top", ErrandText.profession(errand.profession())).withStyle(ChatFormatting.GOLD));
					List<List<ItemStack>> options = TopRewards.options(errand.profession(), level, item.seed(), null);
					java.util.Set<String> named = new java.util.LinkedHashSet<>();
					MutableComponent names = Component.empty();
					for (List<ItemStack> option : options) {
						ItemStack first = option.getFirst();
						if (!named.add(first.getHoverName().getString())) continue;
						if (named.size() > 1) names.append(Component.literal(", ").withStyle(ChatFormatting.GRAY));
						names.append(Icons.of(first.getItem())).append(Component.empty().append(first.getHoverName()).withStyle(ChatFormatting.AQUA));
					}
					out.append("\n ").append(names);
					return;
				}
				for (ItemStack stack : item.create(level, Reputation.enchantBonus(state, errand.village()))) {
					Component name = stack.isEnchanted() ? Lang.tr(player, "reward.enchanted", stack.getHoverName()) : stack.getHoverName();
					MutableComponent line = Component.empty().append(stack.getCount() + "× ").append(name);
					out.append("\n ").append(Icons.of(stack.getItem())).append(line.withStyle(ChatFormatting.AQUA));
					Component enchantments = ErrandText.enchantments(stack);
					if (!enchantments.getString().isEmpty()) out.append("\n ").append(enchantments);
				}
			}
		});
		out.append("\n ").append(Icons.of(Items.EMERALD_BLOCK)).append(Lang.tr(player, "reward.reputation", errand.reputation())
			.withStyle(ChatFormatting.DARK_AQUA));
		if (bonus > 0) out.append("\n ").append(Lang.tr(player, "reward.bonus", Math.round(bonus * 100)).withStyle(ChatFormatting.DARK_GREEN));
		return out;
	}

	private static Component statusLine(ServerPlayer player, Errand errand, ErrandService.Status status, Place place) {
		boolean here = place.near().test(player) && place.handsIn().test(errand);
		return switch (status) {
			case AVAILABLE -> here ? Lang.tr(player, "status.available").withStyle(ChatFormatting.WHITE)
				: Lang.tr(player, "status.go_to_giver").withStyle(ChatFormatting.GRAY);
			case ACCEPTED -> Lang.tr(player, errand.hasActions() ? "status.working" : "status.collecting")
				.withStyle(ChatFormatting.YELLOW);
			case READY -> here ? Lang.tr(player, "status.ready").withStyle(ChatFormatting.GOLD)
				: Lang.tr(player, "journal.ready").withStyle(ChatFormatting.GOLD);
			case DONE -> Lang.tr(player, "status.done").withStyle(ChatFormatting.DARK_GREEN);
		};
	}
}
