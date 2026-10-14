package net.valcheff.villagersremember;

import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.util.Prediction;

import java.util.List;
import java.util.Optional;

/** Accepting, handing in and abandoning errands. All methods run on the server thread. */
public final class ErrandService {
	public enum Status { AVAILABLE, ACCEPTED, READY, DONE }

	public static ErrandsData.PlayerState state(ServerPlayer player) {
		return ErrandsData.get(player.level().getServer()).player(player.getUUID());
	}

	public static Status status(ServerPlayer player, Errand errand) {
		ErrandsData.PlayerState state = state(player);
		if (state.done.containsKey(errand.id())) return Status.DONE;
		ErrandsData.Active active = state.find(errand.id());
		if (active == null) return Status.AVAILABLE;
		return canHandIn(player, active) ? Status.READY : Status.ACCEPTED;
	}

	/** Every action is done and every item to bring is in the inventory. */
	public static boolean canHandIn(ServerPlayer player, ErrandsData.Active active) {
		List<Objective> objectives = active.errand.objectives();
		for (int i = 0; i < objectives.size(); i++) {
			if (progress(player, active, i) < objectives.get(i).needed()) return false;
		}
		return true;
	}

	public static int countItems(ServerPlayer player, Objective objective) {
		int n = 0;
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
			if (deliverable(objective, stack)) n += stack.getCount();
		}
		return n;
	}

	/**
	 * Progress of one objective: the counter for actions; for deliveries what was already given (big orders can be
	 * brought in parts) plus what is in the inventory now.
	 */
	public static int progress(ServerPlayer player, ErrandsData.Active active, int index) {
		Objective o = active.errand.objectives().get(index);
		int done = o.isDelivery() ? active.progress[index] + countItems(player, o)
			: o.isCarried() ? countItems(player, o) : active.progress[index];
		return Math.min(done, o.needed());
	}

	/** Items already given towards a delivery objective. */
	public static int delivered(ErrandsData.Active active, int index) {
		return active.errand.objectives().get(index).isDelivery() ? active.progress[index] : 0;
	}

	/** How many items the player could give now towards the errand's deliveries (what is still needed). */
	public static int givable(ServerPlayer player, ErrandsData.Active active) {
		int n = 0;
		List<Objective> objectives = active.errand.objectives();
		for (int i = 0; i < objectives.size(); i++) {
			Objective o = objectives.get(i);
			if (!o.isDelivery()) continue;
			n += Math.min(countItems(player, o), Math.max(0, o.needed() - active.progress[i]));
		}
		return n;
	}

	/**
	 * Gives the villager what the player has towards the deliveries now; the villager keeps it, and the rest can be
	 * brought later. Returns how many items went.
	 */
	public static int givePart(ServerPlayer player, String errandId) {
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.Active active = data.player(player.getUUID()).find(errandId);
		if (active == null) return 0;
		int given = 0;
		List<Objective> objectives = active.errand.objectives();
		for (int i = 0; i < objectives.size(); i++) {
			Objective o = objectives.get(i);
			if (!o.isDelivery()) continue;
			int want = Math.max(0, o.needed() - active.progress[i]);
			int taken = takeItems(player, o, want);
			active.progress[i] += taken;
			given += taken;
		}
		if (given > 0) {
			data.setDirty();
			player.sendSystemMessage(Lang.tr(player, "msg.gave_part", given).withStyle(ChatFormatting.GREEN));
			sound(player, SoundEvents.VILLAGER_YES);
			Hud.refresh(player);
		}
		return given;
	}

	/** The objective to show when there is room for only one: the first one not done yet (or the last). */
	public static int focus(ServerPlayer player, ErrandsData.Active active) {
		List<Objective> objectives = active.errand.objectives();
		for (int i = 0; i < objectives.size(); i++) {
			if (progress(player, active, i) < objectives.get(i).needed()) return i;
		}
		return objectives.size() - 1;
	}

	public static boolean accept(ServerPlayer player, Errand errand) {
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
		if (state.done.containsKey(errand.id()) || state.find(errand.id()) != null) return false;
		if (state.active.size() >= ErrandsConfig.INSTANCE.maxActiveErrands) {
			player.sendSystemMessage(Lang.tr(player, "msg.too_many", ErrandsConfig.INSTANCE.maxActiveErrands).withStyle(ChatFormatting.RED));
			sound(player, SoundEvents.VILLAGER_NO);
			return false;
		}
		state.active.add(new ErrandsData.Active(errand, new int[errand.objectives().size()]));
		state.tracked.add(errand.id());
		data.setDirty();
		player.sendSystemMessage(Lang.tr(player, "msg.accepted", ErrandText.task(player, errand)).withStyle(ChatFormatting.GREEN));
		// A place to visit: the villager marks the way on a map.
		ServerLevel here = (ServerLevel) player.level();
		String who = ErrandText.villagerName(player, errand.villager(), here.getEntity(errand.villager()) instanceof Villager v ? v : null);
		for (Objective o : errand.objectives()) {
			if (o.type() != Objective.Type.VISIT_STRUCTURE && o.type() != Objective.Type.VISIT_BIOME) continue;
			Maps.chart(player, o, who).ifPresent(chart -> {
				give(player, chart, 1);
				player.sendSystemMessage(Lang.tr(player, "map.given", who, ErrandText.targetName(player, o)).withStyle(ChatFormatting.GOLD));
			});
		}
		Hud.refresh(player);
		sound(player, SoundEvents.VILLAGER_YES);
		Milestones.check(player);
		return true;
	}

	public static void abandon(ServerPlayer player, String errandId) {
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
		ErrandsData.Active active = state.find(errandId);
		if (active == null) return;
		state.active.remove(active);
		state.tracked.remove(errandId);
		data.setDirty();
		player.sendSystemMessage(Lang.tr(player, "msg.abandoned", ErrandText.task(player, active.errand)).withStyle(ChatFormatting.GRAY));
		Hud.refresh(player);
		sound(player, SoundEvents.VILLAGER_NO);
	}

	/** Shows an accepted errand in the on-screen tracker, or hides it if it is already shown. */
	public static void track(ServerPlayer player, String errandId) {
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
		if (state.find(errandId) == null) return;
		if (!state.tracked.remove(errandId)) state.tracked.add(errandId);
		data.setDirty();
		Hud.refresh(player);
	}

	/** Hands in an accepted errand. Returns false (and tells the player why) if it is not finished. */
	public static boolean handIn(ServerPlayer player, String errandId) {
		return handIn(player, errandId, 0);
	}

	/** {@code choice}: the option of a top reward the player picked on the choice screen. */
	public static boolean handIn(ServerPlayer player, String errandId, int choice) {
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData data = ErrandsData.get(level.getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
		ErrandsData.Active active = state.find(errandId);
		if (active == null) return false;
		Errand errand = active.errand;
		if (!canHandIn(player, active)) {
			int i = focus(player, active);
			Objective o = errand.objectives().get(i);
			player.sendSystemMessage(Lang.tr(player, "msg.objective_missing", ErrandText.objective(player, o),
				progress(player, active, i), o.needed()).withStyle(ChatFormatting.RED));
			sound(player, SoundEvents.VILLAGER_NO);
			return false;
		}
		// The reward must fit: nothing is handed in (and nothing dropped) until the player has made room.
		int missing = missingRoom(player, errand, choice);
		if (missing > 0) {
			player.sendSystemMessage(Lang.tr(player, "msg.no_room", missing).withStyle(ChatFormatting.RED));
			sound(player, SoundEvents.VILLAGER_NO);
			return false;
		}
		for (int i = 0; i < errand.objectives().size(); i++) {
			Objective o = errand.objectives().get(i);
			if (o.isDelivery()) takeItems(player, o, o.count() - active.progress[i]);
		}

		double bonus = Reputation.VILLAGE_BONUS[Reputation.villageTier(state, errand.village())];
		int emeralds = ErrandText.withBonus(errand.paidEmeralds(), bonus);
		int xp = ErrandText.withBonus(errand.paidXp(), bonus);
		give(player, new ItemStack(Items.EMERALD), emeralds);
		if (xp > 0) player.giveExperiencePoints(xp);
		int extraLevels = Reputation.enchantBonus(state, errand.village());
		errand.item().ifPresent(item -> item.create(level, extraLevels, player.position(), choice).forEach(stack -> give(player, stack, stack.getCount())));
		// The third contract for a profession brings its legendary item.
		Optional<String> legend = Legends.due(state, errand);
		ItemStack legendary = legend.map(prof -> Legends.award(player, state, prof, errand.villager(),
			errand.village().map(v -> ErrandText.villageName(player, v)).orElse("?"))).orElse(ItemStack.EMPTY);
		if (!legendary.isEmpty()) give(player, legendary.copy(), 1);
		if (errand.item().map(i -> i.kind() == ErrandPools.RewardKind.TOP).orElse(false)) state.flags.add("top_reward");
		boolean contract = ErrandGenerator.isContract(errand);

		Villager villager = level.getEntity(errand.villager()) instanceof Villager v ? v : null;
		int villageTierBefore = Reputation.villageTier(state, errand.village());
		boolean trial = ErrandGenerator.isTrial(errand);
		if (trial) {
			Reputation.passTrial(state, errand.villager(), errand.tier() - 1);
			state.flags.add("trial");
			// The item of a trial is given once per profession and tier; the top reward once per profession.
			String profPath = ErrandText.path(errand.profession());
			if (errand.item().map(i -> i.kind() == ErrandPools.RewardKind.TOP).orElse(false)) state.flags.add(ErrandGenerator.topFlag(profPath));
			else if (errand.item().isPresent()) state.flags.add(ErrandGenerator.trialItemFlag(profPath, errand.tier() - 1));
		}
		int pendingBefore = Reputation.trialPending(state, errand.villager());
		boolean villagerTierUp = Reputation.addVillager(state, player, errand.villager(), villager, errand.reputation());
		boolean trialReady = pendingBefore == 0 && Reputation.trialPending(state, errand.villager()) > 0;
		Reputation.addVillage(state, errand.village(), errand.reputation());
		int villageTierAfter = Reputation.villageTier(state, errand.village());

		state.active.remove(active);
		state.tracked.remove(errand.id());
		state.remember(errand, ErrandGenerator.day(level));
		state.completed++;
		state.done.put(errand.id(), ErrandGenerator.day(level));
		String prof = ErrandText.path(errand.profession());
		ErrandGenerator.taskNameOf(errand).ifPresent(n -> state.collection.computeIfAbsent(prof, k -> new java.util.LinkedHashSet<>()).add(n));
		if (contract) state.contracts.merge(prof, 1, Integer::sum);
		data.prune(ErrandGenerator.day(level));
		data.setDirty();

		player.sendSystemMessage(Lang.tr(player, "msg.completed", ErrandText.task(player, errand)).withStyle(ChatFormatting.GREEN));
		String who = ErrandText.villagerName(player, errand.villager(), villager);
		int tierNow = Reputation.villagerTier(state, errand.villager());
		net.minecraft.network.chat.Component tierName = Lang.tr(player, "rep.villager." + tierNow).withColor(ErrandText.tierColor(tierNow));
		if (villagerTierUp) {
			player.sendSystemMessage(Lang.tr(player, "msg.villager_tier", who, tierName).withStyle(ChatFormatting.AQUA));
		}
		if (trialReady) {
			player.sendSystemMessage(Lang.tr(player, "msg.trial_ready", who).withStyle(ChatFormatting.GOLD));
		}
		if (villageTierAfter > villageTierBefore && errand.village().isPresent()) {
			player.sendSystemMessage(Lang.tr(player, "msg.village_tier", ErrandText.villageName(player, errand.village().get()),
				Lang.tr(player, "rep.village." + villageTierAfter)).withStyle(ChatFormatting.AQUA));
		}
		sound(player, SoundEvents.PLAYER_LEVELUP);
		Hud.refresh(player);
		if (trial) {
			// The trial is an event: a title of its own and the challenge sound.
			sound(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
			Hud.title(player, Lang.tr(player, "title.trust").withStyle(ChatFormatting.GOLD),
				Lang.tr(player, "title.trust_sub", who, tierName));
		} else {
			Hud.title(player, Lang.tr(player, "title.complete").withStyle(ChatFormatting.GREEN),
				ErrandText.task(player, errand).withStyle(ChatFormatting.YELLOW));
		}
		Entity at = villager != null && villager.distanceToSqr(player) < 64 * 64 ? villager : player;
		if (villager != null) villager.playCelebrateSound();
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX(), at.getY() + at.getBbHeight() + 0.3, at.getZ(),
			12, 0.4, 0.3, 0.4, 0.02);
		if (!legendary.isEmpty()) {
			player.sendSystemMessage(Lang.tr(player, "legend.received", legendary.getHoverName().copy().withColor(0xFF8000))
				.withStyle(ChatFormatting.GOLD));
			Hud.title(player, Lang.tr(player, "legend.title").withColor(0xFF8000), legendary.getHoverName().copy().withStyle(ChatFormatting.WHITE));
			sound(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE);
		}
		// Fame: the pack's number, more for trials and contracts, and a big step for becoming Hero of the Village.
		int fame = errand.tier();
		if (trial) fame += Fame.TRIAL * Math.max(1, errand.tier() - 1);
		if (contract) fame += Fame.CONTRACT;
		if (villageTierAfter >= Reputation.MAX_TIER && villageTierBefore < Reputation.MAX_TIER) fame += Fame.HERO;
		Fame.add(player, fame);
		Patron.check(level.getServer(), errand.village());
		errand.village().flatMap(v -> VillageRegistry.get(level.getServer()).byCenter(v))
			.ifPresent(v -> Trust.refresh(level.getServer(), state, v));
		Milestones.check(player);
		return true;
	}

	/** Worn or enchanted pieces are never taken: a reward can't pay for another errand. */
	private static boolean deliverable(Objective objective, ItemStack stack) {
		return objective.matchesItem(stack) && !stack.isDamaged() && !stack.isEnchanted();
	}

	/**
	 * How many more free slots the reward needs, counting the slots the items to bring will leave: the main
	 * inventory is copied, the deliveries are taken from the copy and the reward stacks are fitted into it.
	 */
	public static int missingRoom(ServerPlayer player, Errand errand, int choice) {
		ServerLevel level = (ServerLevel) player.level();
		ErrandsData.PlayerState state = state(player);
		List<ItemStack> slots = new java.util.ArrayList<>();
		for (ItemStack stack : player.getInventory().getNonEquipmentItems()) slots.add(stack.copy());
		ErrandsData.Active accepted = state.find(errand.id());
		for (int i = 0; i < errand.objectives().size(); i++) {
			Objective o = errand.objectives().get(i);
			if (!o.isDelivery()) continue;
			int left = o.count() - (accepted != null ? accepted.progress[i] : 0);
			for (ItemStack stack : slots) {
				if (left <= 0) break;
				if (!deliverable(o, stack)) continue;
				int take = Math.min(left, stack.getCount());
				stack.shrink(take);
				left -= take;
			}
		}
		List<ItemStack> reward = new java.util.ArrayList<>();
		double bonus = Reputation.VILLAGE_BONUS[Reputation.villageTier(state, errand.village())];
		int emeralds = ErrandText.withBonus(errand.paidEmeralds(), bonus);
		while (emeralds > 0) {
			int n = Math.min(64, emeralds);
			reward.add(new ItemStack(Items.EMERALD, n));
			emeralds -= n;
		}
		int extra = Reputation.enchantBonus(state, errand.village());
		errand.item().ifPresent(item -> reward.addAll(item.create(level, extra, null, choice)));
		Legends.due(state, errand).ifPresent(prof -> reward.add(new ItemStack(Legends.icon(prof))));
		int missing = 0;
		for (ItemStack want : reward) {
			int left = want.getCount();
			for (ItemStack slot : slots) {
				if (left <= 0) break;
				if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, want)) {
					int add = Math.min(left, slot.getMaxStackSize() - slot.getCount());
					slot.grow(add);
					left -= add;
				}
			}
			while (left > 0) {
				int at = -1;
				for (int i = 0; i < slots.size(); i++) if (slots.get(i).isEmpty()) { at = i; break; }
				int n = Math.min(left, want.getMaxStackSize());
				if (at < 0) missing++;
				else slots.set(at, want.copyWithCount(n));
				left -= n;
			}
		}
		return missing;
	}

	/** Takes up to {@code amount} items for a delivery objective; returns how many were taken. */
	private static int takeItems(ServerPlayer player, Objective objective, int amount) {
		int left = Math.max(0, amount);
		int want = left;
		List<ItemStack> items = player.getInventory().getNonEquipmentItems();
		for (ItemStack stack : items) {
			if (left <= 0) break;
			if (!deliverable(objective, stack)) continue;
			int take = Math.min(left, stack.getCount());
			stack.shrink(take);
			left -= take;
		}
		player.getInventory().setChanged();
		return want - left;
	}

	static void give(ServerPlayer player, ItemStack template, int amount) {
		while (amount > 0) {
			ItemStack stack = template.copyWithCount(Math.min(amount, template.getMaxStackSize()));
			amount -= stack.getCount();
			if (!player.getInventory().add(stack)) player.drop(stack, false, Prediction.SERVER_ONLY);
		}
	}

	public static void sound(ServerPlayer player, SoundEvent sound) {
		player.level().playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundSource.NEUTRAL, 0.8f, 1.0f);
	}
}
