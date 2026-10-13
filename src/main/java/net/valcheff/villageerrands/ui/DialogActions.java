package net.valcheff.villageerrands.ui;

import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.server.level.ServerPlayer;
import net.valcheff.villageerrands.VillageErrands;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Dialog buttons run {@code /errands click <token>}. Each token maps to an action registered when the dialog
 * was built, so a player can only do what the dialog on their screen offered. Opening a new dialog replaces
 * the previous dialog's tokens.
 */
public final class DialogActions {
	private static final Map<UUID, Map<String, Consumer<ServerPlayer>>> ACTIONS = new HashMap<>();
	private static final Map<UUID, Map<String, java.util.function.BiConsumer<ServerPlayer, String>>> INPUTS = new HashMap<>();

	/** Starts a new dialog for the player: drops the tokens of the previous one. */
	static void begin(ServerPlayer player) {
		ACTIONS.put(player.getUUID(), new HashMap<>());
		INPUTS.put(player.getUUID(), new HashMap<>());
	}

	/** A button that sends what the player typed in the dialog's text field {@code key}. */
	static String inputCommand(ServerPlayer player, String key, java.util.function.BiConsumer<ServerPlayer, String> action) {
		String token = Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
		INPUTS.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(token, action);
		return "errands input " + token + " $(" + key + ")";
	}

	public static boolean runInput(ServerPlayer player, String token, String text) {
		Map<String, java.util.function.BiConsumer<ServerPlayer, String>> inputs = INPUTS.get(player.getUUID());
		java.util.function.BiConsumer<ServerPlayer, String> action = inputs != null ? inputs.get(token) : null;
		if (action == null) {
			player.connection.send(ClientboundClearDialogPacket.INSTANCE);
			return false;
		}
		try {
			action.accept(player, text);
			return true;
		} catch (RuntimeException e) {
			VillageErrands.LOG.error("Dialog input failed for {}", player.getScoreboardName(), e);
			player.connection.send(ClientboundClearDialogPacket.INSTANCE);
			return false;
		}
	}

	static String command(ServerPlayer player, Consumer<ServerPlayer> action) {
		String token = Long.toString(ThreadLocalRandom.current().nextLong() & Long.MAX_VALUE, 36);
		ACTIONS.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(token, action);
		return "errands click " + token;
	}

	/**
	 * Runs a button. The client waits for an answer after a click, so if nothing else gets shown (unknown token,
	 * error) the dialog is closed rather than leaving the player on "Waiting for server".
	 */
	public static boolean run(ServerPlayer player, String token) {
		Map<String, Consumer<ServerPlayer>> actions = ACTIONS.get(player.getUUID());
		Consumer<ServerPlayer> action = actions != null ? actions.get(token) : null;
		if (action == null) {
			VillageErrands.LOG.debug("Unknown dialog token {} from {}", token, player.getScoreboardName());
			player.connection.send(ClientboundClearDialogPacket.INSTANCE);
			return false;
		}
		try {
			action.accept(player);
			return true;
		} catch (RuntimeException e) {
			VillageErrands.LOG.error("Dialog action failed for {}", player.getScoreboardName(), e);
			player.connection.send(ClientboundClearDialogPacket.INSTANCE);
			return false;
		}
	}

	public static void forget(UUID player) {
		ACTIONS.remove(player);
		INPUTS.remove(player);
	}
}
