package net.valcheff.villagersremember.paper;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.valcheff.villagersremember.ActionHooks;

/**
 * Stands in for a stonecutter's result slot ({@code StonecutterQuickMoveMixin} on Fabric and NeoForge). A shift-clicked
 * result reaches {@code onCraftedBy} only after it was moved into the inventory, so the stack is empty there and the
 * crafted statistic counts nothing; the stonecutter reports the move here first, with the stack as it was before.
 */
final class StonecutterTake extends Slot {
	private final Slot slot;
	private final ServerPlayer player;

	StonecutterTake(Slot slot, ServerPlayer player) {
		super(slot.container, slot.getContainerSlot(), slot.x, slot.y);
		this.slot = slot;
		this.player = player;
		this.index = slot.index;
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return slot.mayPlace(stack);
	}

	@Override
	public void onTake(Player player, ItemStack carried) {
		slot.onTake(player, carried);
	}

	@Override
	protected void onQuickCraft(ItemStack original, int count) {
		ActionHooks.crafted(player, original, count);
	}
}
