package net.valcheff.villageerrands.mixin;

import net.minecraft.advancements.triggers.PlayerInteractTrigger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.valcheff.villageerrands.Actions;
import net.valcheff.villageerrands.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A successful use of an item on an animal: shears on a sheep, an empty bucket on a cow or goat. */
@Mixin(PlayerInteractTrigger.class)
abstract class PlayerInteractTriggerMixin {
	@Inject(method = "trigger", at = @At("HEAD"))
	private void villageerrands$interacted(ServerPlayer player, ItemStack item, Entity entity, CallbackInfo ci) {
		if (item.is(Items.SHEARS)) Actions.record(player, Objective.Type.SHEAR, 1, o -> o.matchesEntity(entity.getType()));
		else if (item.is(Items.BUCKET)) Actions.record(player, Objective.Type.MILK, 1, o -> o.matchesEntity(entity.getType()));
	}
}
