package net.valcheff.villageerrands.mixin;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/** The name fields of an entity's synced data, to send a villager's name to one player only. */
@Mixin(Entity.class)
public interface EntityNameAccessor {
	@Accessor("DATA_CUSTOM_NAME")
	static EntityDataAccessor<Optional<Component>> villageerrands$customName() {
		throw new AssertionError();
	}

	@Accessor("DATA_CUSTOM_NAME_VISIBLE")
	static EntityDataAccessor<Boolean> villageerrands$customNameVisible() {
		throw new AssertionError();
	}
}
