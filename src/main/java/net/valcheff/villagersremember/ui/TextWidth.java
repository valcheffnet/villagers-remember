package net.valcheff.villagersremember.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.valcheff.villagersremember.VillagersRemember;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How wide a line of text is on a vanilla client, in GUI pixels. Dialogs centre every line, so lines that should
 * start at the same place are padded to the same width.
 *
 * <p>Glyph widths come from {@code font_widths.bin}, written by the client game test from the real default font:
 * one byte per character of the Basic Multilingual Plane, 1 + the advance in half pixels (0 when unknown), then two
 * bytes the same way for an inline sprite and for the extra width of a bold glyph. Vanilla names are looked up in {@code vanilla_names/<lang>.json}
 * (tools/gen_vanilla_names.py), as a dedicated server knows only English. A resource pack that changes the font or
 * "Force Unicode Font" breaks the alignment, nothing worse.
 */
public final class TextWidth {
	private static final int CHARS = 0x10000;
	private static byte[] widths = new byte[0];
	private static float sprite = 9;
	private static float bold = 1;
	private static final Map<String, Map<String, String>> NAMES = new HashMap<>();

	public static void load() {
		try (InputStream in = TextWidth.class.getClassLoader().getResourceAsStream("assets/villagersremember/font_widths.bin")) {
			if (in != null) {
				byte[] all = in.readAllBytes();
				if (all.length == CHARS + 2) {
					widths = all;
					sprite = half(all[CHARS]);
					bold = half(all[CHARS + 1]);
				}
			}
		} catch (Exception e) {
			VillagersRemember.LOG.warn("Could not read font widths: {}", e.toString());
		}
		for (String lang : List.of("en_us", "bg_bg")) {
			try (InputStream in = TextWidth.class.getClassLoader().getResourceAsStream("assets/villagersremember/vanilla_names/" + lang + ".json")) {
				if (in == null) continue;
				JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
				Map<String, String> map = new HashMap<>();
				for (Map.Entry<String, JsonElement> e : json.entrySet()) map.put(e.getKey(), e.getValue().getAsString());
				NAMES.put(lang, map);
			} catch (Exception e) {
				VillagersRemember.LOG.warn("Could not read vanilla names for {}: {}", lang, e.toString());
			}
		}
	}

	private static float half(byte b) {
		return ((b & 0xFF) - 1) / 2f;
	}

	public static float of(Component component, String lang) {
		return walk(component, Style.EMPTY, lang);
	}

	private static float walk(Component component, Style parent, String lang) {
		Style style = component.getStyle().applyTo(parent);
		float w = switch (component.getContents()) {
			case PlainTextContents text -> text(text.text(), style);
			case TranslatableContents tr -> translated(tr, style, lang);
			case ObjectContents ignored -> sprite;
			default -> text(component.getContents().visit(s -> java.util.Optional.of(s)).orElse(""), style);
		};
		for (Component sibling : component.getSiblings()) w += walk(sibling, style, lang);
		return w;
	}

	private static float text(String s, Style style) {
		float w = 0;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			w += cp < CHARS && widths.length > cp && widths[cp] != 0 ? half(widths[cp]) : 6;
			if (style.isBold()) w += bold;
		}
		return w;
	}

	/** "%s" and "%1$s" take the arguments in turn or by number, "%%" is a percent sign, like vanilla. */
	private static float translated(TranslatableContents tr, Style style, String lang) {
		String pattern = name(lang, tr.getKey());
		if (pattern == null) pattern = tr.getFallback() != null ? tr.getFallback() : tr.getKey();
		Object[] args = tr.getArgs();
		float w = 0;
		int next = 0;
		StringBuilder plain = new StringBuilder();
		for (int i = 0; i < pattern.length(); i++) {
			char c = pattern.charAt(i);
			if (c != '%' || i + 1 >= pattern.length()) {
				plain.append(c);
				continue;
			}
			int j = i + 1;
			while (j < pattern.length() && Character.isDigit(pattern.charAt(j))) j++;
			int index = next;
			boolean numbered = j > i + 1 && j < pattern.length() && pattern.charAt(j) == '$';
			if (numbered) {
				index = Integer.parseInt(pattern.substring(i + 1, j)) - 1;
				j++;
			} else {
				j = i + 1;
			}
			char kind = j < pattern.length() ? pattern.charAt(j) : ' ';
			if (kind == '%') {
				plain.append('%');
			} else if (kind == 's' || kind == 'd') {
				w += text(plain.toString(), style);
				plain.setLength(0);
				if (index >= 0 && index < args.length) {
					w += args[index] instanceof Component arg ? walk(arg, style, lang) : text(String.valueOf(args[index]), style);
				}
				if (!numbered) next++;
			} else {
				plain.append(c);
				continue;
			}
			i = j;
		}
		return w + text(plain.toString(), style);
	}

	private static String name(String lang, String key) {
		String value = NAMES.getOrDefault(lang, Map.of()).get(key);
		if (value == null) value = NAMES.getOrDefault("en_us", Map.of()).get(key);
		if (value == null && Language.getInstance().has(key)) value = Language.getInstance().getOrDefault(key);
		return value;
	}

	/** Spaces of 4 and bold spaces of 5 pixels that add up to {@code pixels}; exact from 12 up. */
	public static MutableComponent gap(int pixels) {
		int bolds = 0;
		while (bolds < 4 && Math.floorMod(pixels - bolds * 5, 4) != 0) bolds++;
		int plain = Math.max(0, (pixels - bolds * 5) / 4);
		MutableComponent out = Component.empty();
		if (plain > 0) out.append(" ".repeat(plain));
		if (bolds > 0) out.append(Component.literal(" ".repeat(bolds)).withStyle(ChatFormatting.BOLD));
		return out;
	}

	/** Smallest gap between the two columns of {@link #columns}; every width from here up can be padded exactly. */
	public static final int MIN_GAP = 12;

	/**
	 * Lines with text on the left and an optional part on the right, padded to one width so that, centred by the
	 * client, the left parts start and the right parts end in the same column.
	 */
	public static List<MutableComponent> columns(List<Component> left, List<Component> right, String lang) {
		int n = left.size();
		float[] lw = new float[n];
		float[] rw = new float[n];
		float widest = 0;
		for (int i = 0; i < n; i++) {
			lw[i] = of(left.get(i), lang);
			rw[i] = right.get(i) == null ? 0 : of(right.get(i), lang);
			widest = Math.max(widest, lw[i] + rw[i]);
		}
		int total = (int) Math.ceil(widest) + MIN_GAP;
		List<MutableComponent> out = new java.util.ArrayList<>();
		for (int i = 0; i < n; i++) {
			MutableComponent line = Component.empty().append(left.get(i))
				.append(gap(Math.round(total - lw[i] - rw[i])));
			if (right.get(i) != null) line.append(right.get(i));
			out.add(line);
		}
		return out;
	}
}
