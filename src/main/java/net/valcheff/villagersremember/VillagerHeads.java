package net.valcheff.villagersremember;

import com.google.common.collect.ImmutableMultimap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.objects.PlayerSprite;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Player heads that look like a villager of a given profession and biome type. The skins are hosted by Mojang
 * (textures.minecraft.net), so vanilla clients render them. Table: {@code assets/villagersremember/heads.json},
 * keys {@code profession/type}, values the base64 "textures" property.
 */
public final class VillagerHeads {
	private static final Map<String, String> TEXTURES = new HashMap<>();

	public static void load() {
		try (InputStream in = VillagerHeads.class.getClassLoader().getResourceAsStream("assets/villagersremember/heads.json")) {
			if (in == null) return;
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : json.entrySet()) TEXTURES.put(e.getKey(), e.getValue().getAsString());
		} catch (Exception e) {
			VillagersRemember.LOG.warn("Could not read villager heads: {}", e.toString());
		}
	}

	public static ResolvableProfile profile(String profession, String type) {
		String texture = TEXTURES.get(profession + "/" + type);
		if (texture == null) texture = TEXTURES.get(profession + "/plains");
		if (texture == null) texture = TEXTURES.get(profession + "/any");
		if (texture == null) return null;
		UUID id = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
		PropertyMap properties = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", texture)));
		return ResolvableProfile.createResolved(new GameProfile(id, "Villager", properties));
	}

	/** A flat, front-facing face for use inside text (vanilla "object" text component). Empty if unknown. */
	public static MutableComponent face(String profession, String type) {
		ResolvableProfile profile = profile(profession, type);
		if (profile == null) return Component.empty();
		return Component.object(new PlayerSprite(profile, true)).withStyle(s -> s.withColor(ChatFormatting.WHITE));
	}

	/** @param profession path of the profession id, e.g. "farmer"; @param type path of the villager type, e.g. "desert" */
	public static ItemStack head(String profession, String type) {
		ResolvableProfile profile = profile(profession, type);
		if (profile == null) return new ItemStack(Items.EMERALD);
		ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
		stack.set(DataComponents.PROFILE, profile);
		return stack;
	}
}
