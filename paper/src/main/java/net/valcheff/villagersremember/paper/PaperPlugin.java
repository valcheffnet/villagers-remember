package net.valcheff.villagersremember.paper;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.valcheff.villagersremember.Platform;
import net.valcheff.villagersremember.VillagersRemember;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Paper entry point. Plugins are enabled after the worlds are loaded, so the server is running by then; Paper's events
 * stand in for the loader events and the mixins ({@link PaperEvents}).
 */
public final class PaperPlugin extends JavaPlugin {
	@Override
	public void onEnable() {
		Path configDir = getDataFolder().toPath();
		Platform.set(new Platform.Services() {
			@Override
			public Path configDir() {
				try {
					return Files.createDirectories(configDir);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}

			/** The mods this asks about (Waystones, Dungeons and Taverns) are Fabric and NeoForge mods. */
			@Override
			public boolean isModLoaded(String modId) {
				return false;
			}

			/** Payloads go to a client with the mod; a Paper server has no settings screen to answer. */
			@Override
			public void send(ServerPlayer player, CustomPacketPayload payload) {
			}
		});
		VillagersRemember.init();

		MinecraftServer server = MinecraftServer.getServer();
		VillagersRemember.serverStarting(server);
		VillagersRemember.serverStarted(server);

		getServer().getPluginManager().registerEvents(new PaperEvents(server), this);
		getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> registerCommands(event.registrar()));

		VillagersRemember.LOG.info("Villagers Remember loaded");
	}

	/**
	 * Paper disables plugins and closes their jars before it saves the worlds, so the mod's saved data is written here:
	 * the world save would otherwise run its codecs with classes that can no longer be loaded.
	 */
	@Override
	public void onDisable() {
		MinecraftServer server = MinecraftServer.getServer();
		server.getDataStorage().saveAndJoin();
		VillagersRemember.serverStopped(server);
	}

	/**
	 * The commands are built on vanilla's command source, which is what Paper's command source is underneath, so the
	 * same tree is handed to Paper's registrar.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void registerCommands(Commands registrar) {
		CommandDispatcher<CommandSourceStack> built = new CommandDispatcher<>();
		VillagersRemember.registerCommands(built);
		for (CommandNode<CommandSourceStack> node : built.getRoot().getChildren()) {
			if (node instanceof LiteralCommandNode<CommandSourceStack> literal) {
				registrar.register((LiteralCommandNode) literal, "Villagers Remember");
			}
		}
	}
}
