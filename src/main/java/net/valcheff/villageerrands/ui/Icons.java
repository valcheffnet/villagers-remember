package net.valcheff.villageerrands.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.objects.AtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.valcheff.villageerrands.VillageErrands;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Inline item icons for text, drawn by the client from its own texture atlases. The sprite of each item is
 * looked up in {@code assets/villageerrands/icons.json} (generated from the client jar by tools/gen_icons.py).
 */
public final class Icons {
	private static final Map<String, Identifier[]> SPRITES = new HashMap<>();

	public static void load() {
		try (InputStream in = Icons.class.getClassLoader().getResourceAsStream("assets/villageerrands/icons.json")) {
			if (in == null) return;
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, JsonElement> e : json.entrySet()) {
				String[] parts = e.getValue().getAsString().split("\\|");
				SPRITES.put(e.getKey(), new Identifier[]{Identifier.parse(parts[0]), Identifier.parse(parts[1])});
			}
		} catch (Exception e) {
			VillageErrands.LOG.warn("Could not read item icons: {}", e.toString());
		}
	}

	/** The item's icon followed by a space, or an empty component when no sprite is known. */
	public static MutableComponent of(Item item) {
		Identifier[] sprite = SPRITES.get(BuiltInRegistries.ITEM.getKey(item).toString());
		if (sprite == null) return Component.empty();
		return Component.empty().append(Component.object(new AtlasSprite(sprite[0], sprite[1]))
			.withStyle(s -> s.withColor(ChatFormatting.WHITE))).append(" ");
	}
}
