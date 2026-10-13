package net.valcheff.villagersremember.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.valcheff.villagersremember.ConfigSync;
import net.valcheff.villagersremember.ErrandsConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every setting of the mod in one scrolling list. In a world whose server runs the mod (single player too), it shows
 * and saves that server's settings, which needs permission level 3. Elsewhere (title screen, servers without the mod)
 * it edits this game's own file, used by single player worlds.
 */
public class SettingsScreen extends Screen {
	private static final String KEY = "villagersremember.config.";
	private static final int ROW_WIDTH = 340;
	private static final int FIELD_WIDTH = 110;

	/** Sections in screen order; settings not listed here end up under "Other". */
	private static final Map<String, List<String>> SECTIONS = new LinkedHashMap<>();
	private static final Map<String, List<String>> CHOICES = Map.of(
		"language", List.of("auto", "en_us", "bg_bg"),
		"chartLook", List.of("auto", "globe", "dnt"),
		"villagerNames", List.of("always", "look", "off"));

	static {
		SECTIONS.put("general", List.of("language", "talkMenu", "bellOpensBoard", "villageRadius", "maxActiveErrands",
			"errandsPerLevel", "respectedBonusErrand"));
		SECTIONS.put("rewards", List.of("emeraldRewards", "xpMultiplier", "tierEmeraldsPerMinute", "marketPremium",
			"xpPerEmerald", "maxEmeraldsPerErrand", "trialCountFactor", "trialRewardMultiplier"));
		SECTIONS.put("prices", List.of("tradeDiscounts", "discountPerVillagerTier", "discountPerVillageTier", "maxDiscount",
			"surchargePerNegativePoint", "maxSurcharge", "patronDiscount", "hitPenalty", "killPenalty"));
		SECTIONS.put("treasury", List.of("treasuryShare", "treasuryVillageMultiplier", "treasuryDailyCap", "treasuryKeepDays",
			"networkBonusPerVillage", "networkBonusMax"));
		SECTIONS.put("endgame", List.of("fameTitles", "contracts", "contractMinutes", "contractRewardMultiplier",
			"contractMaxEmeralds", "contractsForLegend", "groupOrders", "groupOrderDays", "groupOrderScale",
			"groupRegularDays", "groupRewardEmeralds", "groupMinShare"));
		SECTIONS.put("display", List.of("showMarkers", "markerRange", "villagerNames", "villagerNameRange", "showTracker",
			"chartLook", "waystoneNames"));
	}

	private enum Mode { LOADING, SERVER, LOCAL, DENIED }

	private final Screen parent;
	private HeaderAndFooterLayout layout;
	private Mode mode;
	private boolean otherServer;
	private boolean saving;
	/** Waystones (mod or KawaMood's datapack) where the settings apply; without them their setting is hidden. */
	private boolean waystones;
	private JsonObject values;
	private final Set<String> invalid = new LinkedHashSet<>();
	private SettingsList list;
	private Button saveButton;

	public SettingsScreen(Screen parent) {
		super(Component.translatable(KEY + "title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		if (mode == null) start();
		// A new layout each time: rebuildWidgets() calls init() again after the server answers.
		layout = new HeaderAndFooterLayout(this, 44, 33);
		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(4));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(title, font));
		header.addChild(new StringWidget(note(), font).setMaxWidth(width - 20));

		list = null;
		if (mode == Mode.SERVER || mode == Mode.LOCAL) {
			list = addRenderableWidget(new SettingsList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight()));
		} else {
			Component text = Component.translatable(KEY + (mode == Mode.LOADING ? "loading" : "denied")).withStyle(ChatFormatting.GRAY);
			layout.addToContents(new MultiLineTextWidget(text, font).setMaxWidth(ROW_WIDTH).setCentered(true));
		}

		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		boolean editable = mode == Mode.SERVER || mode == Mode.LOCAL;
		if (editable) {
			footer.addChild(Button.builder(Component.translatable(KEY + "reset"), b -> resetToDefaults()).width(100).build());
			footer.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(100).build());
			saveButton = footer.addChild(Button.builder(Component.translatable(KEY + "save"), b -> save()).width(100).build());
			updateSaveButton();
		} else {
			footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(200).build());
		}
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	private void start() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.getConnection() != null && ClientPlayNetworking.canSend(ConfigSync.Request.TYPE)) {
			mode = Mode.LOADING;
			ClientPlayNetworking.send(new ConfigSync.Request());
		} else {
			mode = Mode.LOCAL;
			otherServer = mc.getConnection() != null;
			waystones = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("waystones");
			values = JsonParser.parseString(ErrandsConfig.toJson(ErrandsConfig.INSTANCE)).getAsJsonObject();
		}
	}

	private Component note() {
		return switch (mode) {
			case SERVER -> Component.translatable(KEY + "note.server").withStyle(ChatFormatting.YELLOW);
			case LOCAL -> Component.translatable(KEY + (otherServer ? "note.other_server" : "note.local")).withStyle(ChatFormatting.GRAY);
			default -> Component.empty();
		};
	}

	/** The server's answer to a request or a save. */
	public void receive(ConfigSync.Settings settings) {
		saving = false;
		if (!settings.allowed()) {
			mode = Mode.DENIED;
		} else if (settings.saved()) {
			minecraft.gui.setScreen(parent);
			return;
		} else {
			mode = Mode.SERVER;
			waystones = settings.waystones();
			values = JsonParser.parseString(settings.json()).getAsJsonObject();
			invalid.clear();
		}
		rebuildWidgets();
	}

	private void save() {
		if (!invalid.isEmpty() || saving) return;
		if (mode == Mode.SERVER) {
			saving = true;
			updateSaveButton();
			ClientPlayNetworking.send(new ConfigSync.Update(values.toString()));
		} else if (mode == Mode.LOCAL) {
			ErrandsConfig next = ErrandsConfig.fromJson(values.toString());
			if (next != null) ErrandsConfig.replace(next);
			onClose();
		}
	}

	private void resetToDefaults() {
		values = JsonParser.parseString(ErrandsConfig.toJson(new ErrandsConfig())).getAsJsonObject();
		invalid.clear();
		rebuildWidgets();
	}

	private void updateSaveButton() {
		if (saveButton != null) saveButton.active = invalid.isEmpty() && !saving;
	}

	@Override
	protected void repositionElements() {
		if (layout == null) return;
		layout.arrangeElements();
		if (list != null) list.updateSize(width, layout);
	}

	/** A paused single player server would never answer, so the game keeps running behind this screen. */
	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}

	/** For the game test: sets one value as if typed or clicked. */
	public void setForTest(String key, JsonElement value) {
		values.add(key, value);
	}

	public boolean showsWaystones() {
		return waystones;
	}

	public boolean loaded() {
		return mode == Mode.SERVER || mode == Mode.LOCAL;
	}

	public boolean denied() {
		return mode == Mode.DENIED;
	}

	public void saveForTest() {
		save();
	}

	// ---- the list ----

	private class SettingsList extends ContainerObjectSelectionList<Row> {
		SettingsList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, 24);
			Set<String> shown = new LinkedHashSet<>();
			for (Map.Entry<String, List<String>> section : SECTIONS.entrySet()) {
				addEntry(new HeaderRow(Component.translatable(KEY + "section." + section.getKey())));
				for (String key : section.getValue()) {
					if (values.has(key) && (waystones || !key.equals("waystoneNames"))) addEntry(setting(key));
					shown.add(key);
				}
			}
			List<String> rest = new ArrayList<>();
			for (String key : values.keySet()) if (!shown.contains(key) && !key.equals("configVersion")) rest.add(key);
			if (!rest.isEmpty()) {
				addEntry(new HeaderRow(Component.translatable(KEY + "section.other")));
				for (String key : rest) addEntry(setting(key));
			}
		}

		@Override
		public int getRowWidth() {
			return ROW_WIDTH;
		}
	}

	private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
	}

	private class HeaderRow extends Row {
		private final Component text;

		HeaderRow(Component text) {
			this.text = text.copy().withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			graphics.centeredText(font, text, getContentXMiddle(), getContentY() + (getContentHeight() - 9) / 2, 0xFFFFFFFF);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of();
		}
	}

	private class SettingRow extends Row {
		private final Component label;
		private final AbstractWidget widget;

		SettingRow(Component label, AbstractWidget widget) {
			this.label = label;
			this.widget = widget;
		}

		@Override
		public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			int y = getContentY() + (getContentHeight() - 9) / 2;
			graphics.text(font, font.substrByWidth(label, ROW_WIDTH - FIELD_WIDTH - 8).getString(), getContentX(), y, 0xFFFFFFFF);
			widget.setPosition(getContentRight() - FIELD_WIDTH, getContentY());
			widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
		}

		@Override
		public List<? extends GuiEventListener> children() {
			return List.of(widget);
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return List.of(widget);
		}
	}

	// ---- one setting ----

	private Row setting(String key) {
		Component label = Component.translatableWithFallback(KEY + key, words(key));
		Class<?> type = typeOf(key);
		AbstractWidget widget;
		JsonElement value = values.get(key);
		if (type == boolean.class) {
			widget = CycleButton.onOffBuilder(value.getAsBoolean()).displayOnlyValue()
				.create(0, 0, FIELD_WIDTH, 20, label, (b, v) -> values.addProperty(key, v));
		} else if (CHOICES.containsKey(key)) {
			List<String> options = new ArrayList<>(CHOICES.get(key));
			String current = value.getAsString().toLowerCase(Locale.ROOT);
			if (!options.contains(current)) options.add(current);
			widget = CycleButton.builder((String v) -> Component.translatableWithFallback(KEY + "choice." + v, v), current)
				.withValues(options).displayOnlyValue()
				.create(0, 0, FIELD_WIDTH, 20, label, (b, v) -> values.addProperty(key, v));
		} else {
			EditBox box = new EditBox(font, FIELD_WIDTH, 20, label);
			box.setMaxLength(200);
			box.setValue(text(value));
			box.setResponder(s -> {
				JsonElement parsed = parse(type, s);
				if (parsed == null) {
					invalid.add(key);
					box.setTextColor(0xFFFF5555);
				} else {
					invalid.remove(key);
					box.setTextColor(0xFFE0E0E0);
					values.add(key, parsed);
				}
				updateSaveButton();
			});
			widget = box;
		}
		Component tip = Component.translatableWithFallback(KEY + key + ".tooltip", "");
		if (!tip.getString().isEmpty()) widget.setTooltip(Tooltip.create(Component.empty().append(label.copy().withStyle(ChatFormatting.YELLOW))
			.append("\n").append(tip)));
		return new SettingRow(label, widget);
	}

	private static Class<?> typeOf(String key) {
		try {
			Field field = ErrandsConfig.class.getField(key);
			if (!Modifier.isStatic(field.getModifiers())) return field.getType();
		} catch (NoSuchFieldException ignored) {
			// A setting this client doesn't know (newer server): edit it as text.
		}
		return String.class;
	}

	private static String text(JsonElement value) {
		if (value.isJsonArray()) {
			List<String> parts = new ArrayList<>();
			for (JsonElement e : value.getAsJsonArray()) parts.add(number(e));
			return String.join(", ", parts);
		}
		return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? number(value) : value.getAsString();
	}

	private static String number(JsonElement e) {
		double d = e.getAsDouble();
		return d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString((long) d) : Double.toString(d);
	}

	/** The typed text as JSON of the setting's type, or null if it isn't one. */
	private static JsonElement parse(Class<?> type, String text) {
		try {
			String s = text.trim();
			if (type == int.class) return new JsonPrimitive(Integer.parseInt(s));
			if (type == double.class) return new JsonPrimitive(Double.parseDouble(s));
			if (type == int[].class || type == double[].class) {
				JsonArray array = new JsonArray();
				for (String part : s.split(",")) {
					String p = part.trim();
					if (type == int[].class) array.add(Integer.parseInt(p));
					else array.add(Double.parseDouble(p));
				}
				return array;
			}
			return new JsonPrimitive(text);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** "maxActiveErrands" -> "Max active errands", for settings without a translation. */
	private static String words(String key) {
		String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
		return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}
}
