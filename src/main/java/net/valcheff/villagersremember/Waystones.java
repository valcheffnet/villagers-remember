package net.valcheff.villagersremember;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

import java.util.Optional;

/**
 * Names waystones of KawaMood's Waystones datapack after the village they stand in, so the waystone list shows
 * villages instead of a row of "Waystone". Only waystones still called "Waystone" are renamed, and the ones renamed
 * here follow the village (a patron renaming it). Without the datapack nothing happens.
 * <p>
 * The datapack keeps its waystones in the command storage pk:waystones, database.waystones[{id, name, location}], with
 * one name for everyone, so the waystone gets the village's English name (or the name its patron gave it).
 */
public final class Waystones {
	private static final Identifier STORAGE = Identifier.fromNamespaceAndPath("pk", "waystones");
	/** Set on waystones this mod named, with the village key, so they can follow the village's name. */
	private static final String OURS = "villagersremember_village";
	private static final int EVERY_TICKS = 200;

	public static void tick(MinecraftServer server) {
		if (server.getTickCount() % EVERY_TICKS != 0 || !ErrandsConfig.INSTANCE.waystoneNames) return;
		sync(server);
		if (BlayWaystones.LOADED) BlayWaystones.sync(server);
	}

	/** Whether this server has either: the Waystones mod, or KawaMood's datapack (its functions are loaded). */
	public static boolean available(MinecraftServer server) {
		if (BlayWaystones.LOADED) return true;
		for (Identifier id : server.getFunctions().getFunctionNames()) if (id.getNamespace().equals("pk_waystones")) return true;
		return false;
	}

	/** Renames what needs it. Returns how many waystones got a new name. */
	public static int sync(MinecraftServer server) {
		CompoundTag root = server.getCommandStorage().get(STORAGE);
		ListTag waystones = root.getCompoundOrEmpty("database").getListOrEmpty("waystones");
		if (waystones.isEmpty()) return 0;
		VillageRegistry registry = VillageRegistry.get(server);
		int renamed = 0;
		for (int i = 0; i < waystones.size(); i++) {
			if (!(waystones.get(i) instanceof CompoundTag waystone)) continue;
			boolean ours = waystone.contains(OURS);
			if (!ours && !isDefaultName(waystone.get("name"))) continue;
			CompoundTag location = waystone.getCompoundOrEmpty("location");
			ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION,
				Identifier.parse(location.getStringOr("dimension", "minecraft:overworld")));
			BlockPos pos = new BlockPos(location.getIntOr("x", 0), location.getIntOr("y", 0), location.getIntOr("z", 0));
			Optional<VillageRegistry.Village> village = registry.villageAt(dimension, pos);
			if (village.isEmpty()) continue;
			Component name = name(registry, village.get());
			// The same shape as the datapack's own default, {text:"Waystone"}: its list reads the name with interpret.
			CompoundTag tag = new CompoundTag();
			tag.putString("text", name.getString());
			if (tag.equals(waystone.get("name"))) continue;
			waystone.put("name", tag);
			waystone.putString(OURS, ErrandsData.villageKey(village.get().center));
			renameContainer(server.getLevel(dimension), pos, name, waystone.getIntOr("id", 0));
			renamed++;
		}
		if (renamed > 0) {
			server.getCommandStorage().set(STORAGE, root);
			VillagersRemember.LOG.info("Named {} waystone(s) after their village", renamed);
		}
		return renamed;
	}

	private static boolean isDefaultName(Tag name) {
		if (name == null) return true;
		if (name.asString().map("Waystone"::equals).orElse(false)) return true;
		return name instanceof CompoundTag c && c.getStringOr("text", "").equals("Waystone") && c.getListOrEmpty("extra").isEmpty();
	}

	/** The stored name, the same for everyone: English, or the name a patron gave the village. */
	public static Component name(VillageRegistry registry, VillageRegistry.Village village) {
		if (village.name != null) return Component.literal(village.name);
		return Component.literal(Lang.villageNameAt("en_us", registry.nameIndex(village, Lang.villageNameCount())));
	}

	/**
	 * The waystone's barrel (one block above its location) shows the name as its title, set by the datapack when it is
	 * placed: "Waystone (12)". Renamed the same way when the chunk is loaded; the datapack also rebuilds it from the
	 * stored name whenever it sets the barrel up again.
	 */
	private static void renameContainer(ServerLevel level, BlockPos pos, Component name, int id) {
		if (level == null) return;
		for (BlockPos at : new BlockPos[] {pos.above(), pos}) {
			if (!level.isLoaded(at) || !(level.getBlockEntity(at) instanceof BarrelBlockEntity barrel)) continue;
			CustomData data = barrel.getItem(0).get(DataComponents.CUSTOM_DATA);
			if (data == null || !data.copyTag().getCompoundOrEmpty("pk_data").getBooleanOr("custom_block", false)) continue;
			Component title = name.copy().append(Component.literal(" (" + id + ")").withStyle(ChatFormatting.GRAY));
			Tag tag = ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, title).getOrThrow();
			MinecraftServer server = level.getServer();
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(),
				"data modify block " + at.getX() + " " + at.getY() + " " + at.getZ() + " CustomName set value " + tag);
			return;
		}
	}
}
