package net.valcheff.villagersremember;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
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
 */
public final class ConfigSync {
	/** Client asks for the settings. */
	public record Request() implements CustomPacketPayload {
		public static final Type<Request> TYPE = new Type<>(VillagersRemember.id("config_request"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.unit(new Request());

		@Override
		public Type<Request> type() {
			return TYPE;
		}
	}

	/** Client sends changed settings (the whole file as JSON). */
	public record Update(String json) implements CustomPacketPayload {
		public static final Type<Update> TYPE = new Type<>(VillagersRemember.id("config_update"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Update> CODEC =
			ByteBufCodecs.stringUtf8(1 << 16).map(Update::new, Update::json).cast();

		@Override
		public Type<Update> type() {
			return TYPE;
		}
	}

	/** Server answers: the settings as JSON when the player may change them, or an empty string when not. */
	public record Settings(String json, boolean allowed, boolean saved) implements CustomPacketPayload {
		public static final Type<Settings> TYPE = new Type<>(VillagersRemember.id("config"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Settings> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(1 << 16), Settings::json,
			ByteBufCodecs.BOOL, Settings::allowed,
			ByteBufCodecs.BOOL, Settings::saved,
			Settings::new);

		@Override
		public Type<Settings> type() {
			return TYPE;
		}
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(Request.TYPE, Request.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Update.TYPE, Update.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Settings.TYPE, Settings.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(Request.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			boolean allowed = canEdit(player);
			context.responseSender().sendPacket(new Settings(allowed ? ErrandsConfig.toJson(ErrandsConfig.INSTANCE) : "", allowed, false));
		});
		ServerPlayNetworking.registerGlobalReceiver(Update.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			boolean saved = apply(player.getScoreboardName(), canEdit(player), payload.json());
			boolean allowed = canEdit(player);
			context.responseSender().sendPacket(new Settings(allowed ? ErrandsConfig.toJson(ErrandsConfig.INSTANCE) : "", allowed, saved));
			if (saved) player.sendSystemMessage(Lang.tr(player, "config.saved").withStyle(ChatFormatting.GREEN));
		});
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
