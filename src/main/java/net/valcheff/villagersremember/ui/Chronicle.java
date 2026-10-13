package net.valcheff.villagersremember.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.valcheff.villagersremember.Errand;
import net.valcheff.villagersremember.ErrandGenerator;
import net.valcheff.villagersremember.ErrandService;
import net.valcheff.villagersremember.ErrandText;
import net.valcheff.villagersremember.ErrandsConfig;
import net.valcheff.villagersremember.ErrandsData;
import net.valcheff.villagersremember.Lang;
import net.valcheff.villagersremember.Reputation;
import net.valcheff.villagersremember.VillagerHeads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The errand chronicle: a written book shown without giving the player an item. The book is put into the
 * selected hotbar slot on the client only, opened, and the real slot content is sent right after.
 */
public final class Chronicle {
	private static final int ERRANDS_PER_PAGE = 2;
	private static final int VILLAGES_PER_PAGE = 4;
	private static final int HISTORY_PER_PAGE = 3;

	public static void open(ServerPlayer player) {
		ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
		List<Filterable<Component>> pages = new ArrayList<>();
		for (Component page : pages(player)) pages.add(Filterable.passThrough(page));
		book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
			Filterable.passThrough(Lang.raw(Lang.language(player), "book.title")), player.getScoreboardName(), 0, pages, true));

		int slot = 36 + player.getInventory().getSelectedSlot();
		var menu = player.inventoryMenu;
		player.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), slot, book));
		player.connection.send(new ClientboundOpenBookPacket(InteractionHand.MAIN_HAND));
		player.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId, menu.incrementStateId(), slot,
			player.getInventory().getSelectedItem().copy()));
	}

	private static List<Component> pages(ServerPlayer player) {
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = ErrandService.state(player);
		List<Component> pages = new ArrayList<>();

		// Cover
		MutableComponent cover = Component.empty()
			.append(Icons.of(Items.WRITABLE_BOOK)).append(Lang.tr(player, "book.title").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
			.append("\n").append(Component.literal(player.getScoreboardName()).withStyle(ChatFormatting.DARK_GRAY))
			.append("\n\n").append(Lang.tr(player, "book.day", ErrandGenerator.day(level)))
			.append("\n").append(Icons.of(Items.EMERALD)).append(Lang.tr(player, "book.completed", state.completed))
			.append("\n").append(Icons.of(Items.PAPER)).append(Lang.tr(player, "book.accepted", state.active.size(), ErrandsConfig.INSTANCE.maxActiveErrands))
			.append("\n").append(Icons.of(Items.BELL)).append(Lang.tr(player, "book.villages", net.valcheff.villagersremember.Treasury.keys(state).size()))
			.append("\n").append(Icons.of(Items.NETHER_STAR)).append(Lang.tr(player, "book.fame", state.fame))
			.append("\n\n").append(link(Lang.tr(player, "book.open_journal"), "/errands"));
		pages.add(cover);

		// Fame, contracts, legendary items
		int title = net.valcheff.villagersremember.Fame.title(state);
		MutableComponent fame = Component.empty().append(Lang.tr(player, "book.section_fame").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD))
			.append("\n\n").append(Lang.tr(player, "book.fame_title")).append(title > 0
				? net.valcheff.villagersremember.Fame.titleName(player, title).withStyle(ChatFormatting.BOLD)
				: Lang.tr(player, "fame.title.0").withStyle(ChatFormatting.DARK_GRAY))
			.append("\n").append(Lang.tr(player, "book.fame_points", state.fame));
		if (title < net.valcheff.villagersremember.Fame.MAX_TITLE) {
			fame.append("\n").append(Lang.tr(player, "book.fame_next", net.valcheff.villagersremember.Fame.titleName(player, title + 1),
				net.valcheff.villagersremember.Fame.TITLES[title + 1]).withStyle(ChatFormatting.DARK_GRAY));
		}
		int contracts = state.contracts.values().stream().mapToInt(Integer::intValue).sum();
		fame.append("\n\n").append(Icons.of(Items.PAPER)).append(Lang.tr(player, "book.contracts", contracts))
			.append("\n").append(Icons.of(Items.NETHERITE_INGOT)).append(Lang.tr(player, "book.legends", state.legends.size(),
				net.valcheff.villagersremember.Legends.professions().size()));
		for (String prof : state.legends) {
			fame.append("\n ").append(Icons.of(net.valcheff.villagersremember.Legends.icon(prof)))
				.append(ErrandText.profession("minecraft:" + prof).copy().withColor(0xFF8000));
		}
		if (!state.patronOf.isEmpty()) {
			fame.append("\n\n").append(Icons.of(Items.BEACON)).append(Lang.tr(player, "book.patron"));
			var registry = net.valcheff.villagersremember.VillageRegistry.get(level.getServer());
			for (var v : registry.all()) {
				if (player.getUUID().equals(v.patron)) fame.append("\n ").append(Component.literal(ErrandText.villageName(player, v.center)).withStyle(ChatFormatting.GOLD));
			}
		}
		pages.add(fame);

		// Profession collection: every task of a profession done once
		List<String> professions = net.valcheff.villagersremember.Milestones.COLLECTION;
		for (int i = 0; i < professions.size(); i += 7) {
			MutableComponent page = Component.empty().append(Lang.tr(player, "book.section_collection").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			for (int j = i; j < Math.min(i + 7, professions.size()); j++) {
				String prof = professions.get(j);
				int[] c = net.valcheff.villagersremember.Milestones.collection(state, prof);
				boolean full = c[1] > 0 && c[0] >= c[1];
				page.append("\n").append(ErrandText.profession("minecraft:" + prof).copy().withStyle(full ? ChatFormatting.DARK_GREEN : ChatFormatting.BLACK))
					.append(Component.literal(" " + c[0] + "/" + c[1] + (full ? " ✔" : "")).withStyle(full ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_GRAY));
			}
			pages.add(page);
		}

		// Accepted errands
		List<ErrandsData.Active> active = state.active;
		for (int i = 0; i < active.size(); i += ERRANDS_PER_PAGE) {
			MutableComponent page = Component.empty().append(Lang.tr(player, "book.section_active").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			for (int j = i; j < Math.min(i + ERRANDS_PER_PAGE, active.size()); j++) {
				ErrandsData.Active a = active.get(j);
				Errand e = a.errand;
				Villager villager = level.getEntity(e.villager()) instanceof Villager v ? v : null;
				page.append("\n\n").append(Icons.of(ErrandText.icon(e).getItem())).append(ErrandText.task(player, e).withStyle(ChatFormatting.BOLD))
					.append("\n").append(face(e)).append(" ").append(ErrandText.villagerName(player, e.villager(), villager));
				e.village().ifPresent(v -> page.append("\n").append(Component.literal(ErrandText.villageName(player, v)
					+ " " + v.pos().getX() + ", " + v.pos().getZ()).withStyle(ChatFormatting.DARK_GRAY)));
				for (int k = 0; k < e.objectives().size(); k++) {
					page.append("\n").append(Icons.of(ErrandText.icon(e.objectives().get(k)).getItem()))
						.append(ErrandText.bar(ErrandService.progress(player, a, k), e.objectives().get(k).needed(), 6));
				}
				page.append("\n").append(link(Lang.tr(player, state.isTracked(e.id()) ? "book.tracked" : "book.track"),
					"/errands track " + e.id()));
			}
			pages.add(page);
		}

		// Village standings
		List<Map.Entry<String, Integer>> villages = new ArrayList<>();
		for (String key : net.valcheff.villagersremember.Treasury.keys(state)) villages.add(Map.entry(key, state.villageRep.getOrDefault(key, 0)));
		villages.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
		for (int i = 0; i < villages.size(); i += VILLAGES_PER_PAGE) {
			MutableComponent page = Component.empty().append(Lang.tr(player, "book.section_villages").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			for (int j = i; j < Math.min(i + VILLAGES_PER_PAGE, villages.size()); j++) {
				GlobalPos pos = parseVillage(villages.get(j).getKey());
				if (pos == null) continue;
				int points = villages.get(j).getValue();
				int tier = Reputation.tier(points, Reputation.VILLAGE_TIERS);
				page.append("\n\n").append(Icons.of(Items.BELL)).append(Component.literal(ErrandText.villageName(player, pos)).withStyle(ChatFormatting.BOLD))
					.append("\n").append(Lang.tr(player, "rep.village." + tier).withStyle(tierColor(tier)))
					.append(Component.literal(" (" + points + ")").withStyle(ChatFormatting.DARK_GRAY))
					.append("\n").append(Component.literal(pos.pos().getX() + ", " + pos.pos().getZ()).withStyle(ChatFormatting.DARK_GRAY));
				Long found = state.discovered.get(villages.get(j).getKey());
				if (found != null) page.append("\n").append(Lang.tr(player, "book.found", found).withStyle(ChatFormatting.DARK_GRAY));
			}
			pages.add(page);
		}

		// History, newest first
		List<Errand> history = state.history;
		for (int i = history.size() - 1, onPage = 0; i >= 0; ) {
			MutableComponent page = Component.empty().append(Lang.tr(player, "book.section_history").withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
			for (onPage = 0; onPage < HISTORY_PER_PAGE && i >= 0; onPage++, i--) {
				Errand e = history.get(i);
				page.append("\n\n").append(Lang.tr(player, "book.day", state.historyDays.get(i)).withStyle(ChatFormatting.DARK_GRAY))
					.append("\n").append(Icons.of(ErrandText.icon(e).getItem())).append(ErrandText.task(player, e))
					.append("\n").append(face(e)).append(" ").append(Lang.tr(player, "book.for", ErrandText.villagerName(player, e.villager(), null))
						.withStyle(ChatFormatting.DARK_GRAY));
			}
			pages.add(page);
		}
		if (history.isEmpty() && active.isEmpty()) {
			pages.add(Component.empty().append(Lang.tr(player, "journal.empty")).append("\n\n")
				.append(Lang.tr(player, "journal.how").withStyle(ChatFormatting.DARK_GRAY)));
		}
		return pages;
	}

	private static Component link(Component text, String command) {
		return text.copy().withStyle(s -> s.withColor(ChatFormatting.BLUE).withUnderlined(true)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.literal(command))));
	}

	private static Component face(Errand e) {
		return VillagerHeads.face(ErrandText.path(e.profession()), ErrandText.path(e.villagerType()));
	}

	private static ChatFormatting tierColor(int tier) {
		return switch (tier) {
			case 0 -> ChatFormatting.DARK_GRAY;
			case 1 -> ChatFormatting.BLACK;
			case 2 -> ChatFormatting.DARK_GREEN;
			case 3 -> ChatFormatting.DARK_BLUE;
			default -> ChatFormatting.GOLD;
		};
	}

	private static GlobalPos parseVillage(String key) {
		String[] p = key.split("\\|");
		if (p.length != 4) return null;
		try {
			return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, Identifier.parse(p[0])),
				new BlockPos(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
		} catch (RuntimeException e) {
			return null;
		}
	}
}
