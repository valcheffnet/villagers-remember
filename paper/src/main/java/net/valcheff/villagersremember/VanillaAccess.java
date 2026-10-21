package net.valcheff.villagersremember;

import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Vanilla members the mod uses that vanilla keeps private, for the Paper build. Paper makes some of them public; the
 * rest are reached by reflection, which works by their Mojang names since the server ships unobfuscated (26.1).
 */
public final class VanillaAccess {
	private static final Method START_TRADING;
	private static final EntityDataAccessor<Optional<Component>> CUSTOM_NAME;
	private static final EntityDataAccessor<Boolean> CUSTOM_NAME_VISIBLE;

	static {
		try {
			START_TRADING = Villager.class.getDeclaredMethod("startTrading", Player.class);
			START_TRADING.setAccessible(true);
			CUSTOM_NAME = staticField(Entity.class, "DATA_CUSTOM_NAME");
			CUSTOM_NAME_VISIBLE = staticField(Entity.class, "DATA_CUSTOM_NAME_VISIBLE");
		} catch (ReflectiveOperationException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T staticField(Class<?> owner, String name) throws ReflectiveOperationException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return (T) field.get(null);
	}

	/** Vanilla's way into the trading screen, with the player's special prices. */
	public static void startTrading(Villager villager, Player player) {
		Prices.beforeVanilla(villager, player);
		try {
			START_TRADING.invoke(villager, player);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		} catch (InvocationTargetException e) {
			if (e.getCause() instanceof RuntimeException r) throw r;
			throw new IllegalStateException(e.getCause());
		}
	}

	/** Vanilla's own level-up: the next level and that level's trades. */
	public static void increaseMerchantCareer(Villager villager, ServerLevel level) {
		villager.increaseMerchantCareer(level);
	}

	public static EntityDataAccessor<Optional<Component>> customName() {
		return CUSTOM_NAME;
	}

	public static EntityDataAccessor<Boolean> customNameVisible() {
		return CUSTOM_NAME_VISIBLE;
	}

	private VanillaAccess() {
	}
}
