package net.valcheff.villageerrands;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.Predicate;

/**
 * The single entry point for actions the player did (caught, crafted, harvested...). Every hook calls
 * {@link #record}; it advances the matching objectives of the player's accepted errands.
 */
public final class Actions {
	/**
	 * Adds {@code amount} to every unfinished objective of this type that {@code matches}, in every accepted errand.
	 * Only actions done after accepting count, because progress is kept on the accepted errand.
	 */
	public static void record(ServerPlayer player, Objective.Type type, int amount, Predicate<Objective> matches) {
		if (amount <= 0 || player.level().getServer() == null) return;
		ErrandsData data = ErrandsData.get(player.level().getServer());
		ErrandsData.PlayerState state = data.player(player.getUUID());
		ErrandsData.Active shownActive = null;
		int shownIndex = -1;
		boolean changed = false;
		for (ErrandsData.Active active : state.active) {
			for (int i = 0; i < active.progress.length; i++) {
				Objective o = active.errand.objectives().get(i);
				if (o.type() != type || active.progress[i] >= o.needed() || !matches.test(o)) continue;
				active.progress[i] = Math.min(o.needed(), active.progress[i] + amount);
				changed = true;
				if (shownActive == null || state.isTracked(active.errand.id())) {
					shownActive = active;
					shownIndex = i;
				}
			}
		}
		if (!changed) return;
		data.setDirty();
		for (ErrandsData.Active active : state.active) {
			if (ErrandService.canHandIn(player, active)) Hud.ready(player, active);
		}
		Objective o = shownActive.errand.objectives().get(shownIndex);
		player.sendOverlayMessage(Component.empty().append(ErrandText.objective(player, o).withStyle(ChatFormatting.YELLOW))
			.append("  ").append(ErrandText.bar(shownActive.progress[shownIndex], o.needed(), 8)));
		Hud.refresh(player);
	}
}
