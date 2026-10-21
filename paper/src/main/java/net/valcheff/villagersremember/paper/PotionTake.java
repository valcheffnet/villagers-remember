package net.valcheff.villagersremember.paper;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.valcheff.villagersremember.Actions;
import net.valcheff.villagersremember.Objective;

/**
 * Stands in for one of a brewing stand's bottle slots: everything goes to vanilla's slot, and what was brewed is
 * recorded after vanilla's take ({@code BrewedPotionTriggerMixin} on Fabric and NeoForge).
 */
final class PotionTake extends Slot {
	private final Slot slot;

	PotionTake(Slot slot) {
		super(slot.container, slot.getContainerSlot(), slot.x, slot.y);
		this.slot = slot;
		this.index = slot.index;
	}

	@Override
	public boolean mayPlace(ItemStack stack) {
		return slot.mayPlace(stack);
	}

	@Override
	public int getMaxStackSize() {
		return slot.getMaxStackSize();
	}

	@Override
	public int getMaxStackSize(ItemStack stack) {
		return slot.getMaxStackSize(stack);
	}

	@Override
	public Identifier getNoItemIcon() {
		return slot.getNoItemIcon();
	}

	@Override
	public void onTake(Player player, ItemStack carried) {
		PotionContents contents = carried.get(DataComponents.POTION_CONTENTS);
		slot.onTake(player, carried);
		if (contents != null && player instanceof ServerPlayer sp) {
			contents.potion().ifPresent(potion -> Actions.record(sp, Objective.Type.BREW, 1, o -> o.matchesPotion(potion)));
		}
	}
}
