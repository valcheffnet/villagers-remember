package net.valcheff.villagersremember;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The config screen of a client with the mod reads and changes the server's settings through these packets.
 * Only players with permission level 3 (the same as the operator commands) or the owner of a single player world get
 * the settings or may change them.
 * Vanilla clients never send the request, so the server never sends them anything.
 * <p>
 * Every packet carries one JSON string, so a field added later doesn't break an older client or server; missing fields
 * read as their defaults. A change that older versions can't read gets new channel names (as the "config*" channels of
 * 1.0.0-test.4 were replaced here): a client and server that don't share a channel just don't talk, the screen then
 * shows the local settings, and nobody is disconnected.
 */
public final class ConfigSync {
	/** Client asks for the settings. */
	public record Request() implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(VillagersRemember.id("settings_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());

		@Override
		public Type<Request> type() {
			return TYPE;
		}
	}

	/** Client sends changed settings (the whole file as JSON). */
	public record Update(String json) implements CustomPacketPayload {
		public static final Type<Update> TYPE = new Type<>(VillagersRemember.id("settings_update"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC =
			ByteBufCodecs.stringUtf8(1 << 16).map(Update::new, Update::json).cast();

		@Override
		public Type<Update> type() {
			return TYPE;
		}
	}

	/**
	 * Server answers: the settings as JSON when the player may change them (empty when not), whether the last change was
	 * saved, and whether waystones are there. Sent as {"settings": ..., "allowed": ..., "saved": ..., "waystones": ...}.
	 */
	public record Settings(String json, boolean allowed, boolean saved, boolean waystones) implements CustomPacketPayload {
		public static final Type<Settings> TYPE = new Type<>(VillagersRemember.id("settings"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Settings> CODEC =
			ByteBufCodecs.stringUtf8(1 << 16).map(Settings::read, Settings::write).cast();

		private String write() {
			JsonObject out = new JsonObject();
			out.addProperty("settings", json);
			out.addProperty("allowed", allowed);
			out.addProperty("saved", saved);
			out.addProperty("waystones", waystones);
			return out.toString();
		}

		private static Settings read(String text) {
			try {
				JsonObject in = JsonParser.parseString(text).getAsJsonObject();
				return new Settings(string(in, "settings"), bool(in, "allowed"), bool(in, "saved"), bool(in, "waystones"));
			} catch (RuntimeException e) {
				return new Settings("", false, false, false);
			}
		}

		private static String string(JsonObject in, String key) {
			return in.has(key) && in.get(key).isJsonPrimitive() ? in.get(key).getAsString() : "";
		}

		private static boolean bool(JsonObject in, String key) {
			return in.has(key) && in.get(key).isJsonPrimitive() && in.get(key).getAsBoolean();
		}

		@Override
		public Type<Settings> type() {
			return TYPE;
		}
	}

	/** The answer to a client asking for the settings. */
	public static Settings answerRequest(ServerPlayer player) {
		boolean allowed = canEdit(player);
		return new Settings(allowed ? ErrandsConfig.toJson(ErrandsConfig.INSTANCE) : "", allowed, false, Waystones.available(player.level().getServer()));
	}

	/** Uses a client's changed settings if the player may change them, and answers with what the server has now. */
	public static Settings answerUpdate(ServerPlayer player, Update payload) {
		boolean saved = apply(player.getScoreboardName(), canEdit(player), payload.json());
		boolean allowed = canEdit(player);
		if (saved) player.sendSystemMessage(Lang.tr(player, "config.saved").withStyle(ChatFormatting.GREEN));
		return new Settings(allowed ? ErrandsConfig.toJson(ErrandsConfig.INSTANCE) : "", allowed, saved, Waystones.available(player.level().getServer()));
	}

	/** Level 3, like the operator commands, or the owner of a single player world (cheats on or not). */
	public static boolean canEdit(ServerPlayer player) {
		return canEdit(player.permissions()) || player.level().getServer().isSingleplayerOwner(player.nameAndId());
	}

	public static boolean canEdit(PermissionSet permissions) {
		return Commands.LEVEL_ADMINS.check(permissions);
	}

	/** Uses and saves the settings if allowed. Every change is written to the server log. */
	public static boolean apply(String who, boolean allowed, String json) {
		if (!allowed) {
			VillagersRemember.LOG.warn("{} tried to change the settings without permission", who);
			return false;
		}
		ErrandsConfig next = ErrandsConfig.fromJson(json);
		if (next == null) return false;
		List<String> changes = changes(ErrandsConfig.INSTANCE, next);
		ErrandsConfig.replace(next);
		ErrandPools.reload(VillagersRemember.server());
		VillagersRemember.LOG.info("{} changed the settings: {}", who, changes.isEmpty() ? "nothing" : String.join(", ", changes));
		return true;
	}

	private static List<String> changes(ErrandsConfig before, ErrandsConfig after) {
		JsonObject a = JsonParser.parseString(ErrandsConfig.toJson(before)).getAsJsonObject();
		JsonObject b = JsonParser.parseString(ErrandsConfig.toJson(after)).getAsJsonObject();
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, JsonElement> e : b.entrySet()) {
			JsonElement old = a.get(e.getKey());
			if (!e.getValue().equals(old)) out.add(e.getKey() + " " + old + " -> " + e.getValue());
		}
		return out;
	}
}
