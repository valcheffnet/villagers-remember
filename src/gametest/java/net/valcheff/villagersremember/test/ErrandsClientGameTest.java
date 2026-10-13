package net.valcheff.villagersremember.test;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.dialog.WaitingForResponseScreen;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.valcheff.villagersremember.Errand;
import net.valcheff.villagersremember.ErrandGenerator;
import net.valcheff.villagersremember.ErrandText;
import net.valcheff.villagersremember.Objective;
import net.valcheff.villagersremember.ErrandService;
import net.valcheff.villagersremember.ErrandsConfig;
import net.valcheff.villagersremember.ErrandsData;
import net.valcheff.villagersremember.Reputation;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * End-to-end test with a real client, driving the dialogs the way a player does (pressing their buttons):
 * villager dialog, accept, tracker/marks/titles, hand in, board, journal and the chronicle book.
 * Screenshots land in build/run/clientGameTest/screenshots for review.
 */
@SuppressWarnings("UnstableApiUsage")
public class ErrandsClientGameTest implements FabricClientGameTest {
	private static final List<ResourceKey<VillagerProfession>> PROFESSIONS = List.of(
		VillagerProfession.FARMER, VillagerProfession.CLERIC, VillagerProfession.LIBRARIAN, VillagerProfession.ARMORER,
		VillagerProfession.FISHERMAN, VillagerProfession.SHEPHERD, VillagerProfession.FLETCHER, VillagerProfession.WEAPONSMITH,
		VillagerProfession.TOOLSMITH, VillagerProfession.BUTCHER, VillagerProfession.MASON, VillagerProfession.CARTOGRAPHER);
	private static final String[] TYPES = {"plains", "desert", "taiga", "snow", "savanna", "jungle", "swamp"};

	@Override
	public void runTest(ClientGameTestContext context) {
		// Silent: the test window opens on the user's desktop.
		context.runOnClient(mc -> {
			mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
			mc.options.save();
		});
		// ERRANDS_TEST_QUICK=1: only open the window for a few seconds (to see where it goes), no test.
		// The window itself is moved by TestWindow (ERRANDS_TEST_WINDOW) while the game loads.
		if (System.getenv("ERRANDS_TEST_QUICK") != null) {
			context.waitTicks(200);
			return;
		}
		try (TestSingleplayerContext sp = context.worldBuilder().create()) {
			dumpBalance(sp);
			writeFontWidths(context);
			sp.getServer().runCommand("time set day");
			sp.getServer().runCommand("weather clear");
			sp.getServer().runCommand("difficulty easy");

			BlockPos base = sp.getServer().computeOnServer(ErrandsClientGameTest::setUpVillage);
			context.waitTicks(40);
			BlockPos bell = base.offset(0, 0, 8);
			List<UUID> villagers = sp.getServer().computeOnServer(server -> villagerIds(server, base));
			check(villagers.size() == PROFESSIONS.size(), "spawned villagers " + villagers.size());

			// A "craft and bring" errand and a hunt, from the T2 packs (the player is an acquaintance of everyone).
			sp.getServer().runOnServer(server -> villagers.forEach(id -> {
				state(server).villagerRep.put(id, Reputation.VILLAGER_TIERS[1]);
				state(server).trialsPassed.put(id, 1);
			}));
			Errand deliver = sp.getServer().computeOnServer(server -> findErrand(server, villagers,
				e -> e.objectives().size() == 2 && e.main().type() == Objective.Type.CRAFT && !e.main().tag()));
			Errand kill = sp.getServer().computeOnServer(server -> findErrand(server, villagers,
				e -> e.objectives().size() == 1 && e.main().type() == Objective.Type.KILL && !e.main().tag()));
			check(deliver != null && kill != null, "need a craft-and-bring errand and a hunt");
			String deliverLabel = sp.getServer().computeOnServer(server -> ErrandText.task(player(server), deliver).getString());
			String killLabel = sp.getServer().computeOnServer(server -> ErrandText.task(player(server), kill).getString());

			// Villager dialog: shift + right-click.
			openVillager(context, sp, deliver.villager());
			context.takeScreenshot("dlg-1-villager");

			// Open the delivery errand, accept it.
			press(context, label -> label.contains(deliverLabel));
			context.takeScreenshot("dlg-2-detail");
			press(context, label -> label.equals("Accept"));
			sp.getServer().waitFor(server -> active(server).size() == 1, 40);
			context.takeScreenshot("dlg-3-accepted");
			closeScreen(context);

			// Tracker, "!" marks; then the items arrive: "Errand ready!" title and a "?" above the giver.
			context.waitTicks(30);
			context.takeScreenshot("hud-1-tracker-and-marks");
			// Bringing the items alone is not enough: the craft counter is separate.
			sp.getServer().runOnServer(server -> giveTarget(player(server), deliver.objectives().get(1)));
			context.waitTicks(15);
			check(sp.getServer().computeOnServer(server -> !ErrandService.canHandIn(player(server), active(server).getFirst())),
				"ready without crafting");
			// Crafting half shows half on the craft line only.
			int half = Math.max(1, deliver.main().count() / 2);
			sp.getServer().runOnServer(server -> craft(server, deliver.main(), half));
			check(sp.getServer().computeOnServer(server -> active(server).getFirst().progress[0]) == half, "craft progress");
			sp.getServer().runOnServer(server -> craft(server, deliver.main(), deliver.main().count() - half));
			context.waitTicks(25);
			context.takeScreenshot("hud-2-ready-title");

			// Hand in from the villager dialog: afterwards a dialog must be shown again (not "waiting for server").
			int emeraldsBefore = sp.getServer().computeOnServer(server -> countEmeralds(player(server)));
			openVillager(context, sp, deliver.villager());
			press(context, label -> label.contains(deliverLabel));
			context.takeScreenshot("dlg-3b-detail-ready");
			press(context, label -> label.equals("Complete"));
			sp.getServer().waitFor(server -> active(server).isEmpty(), 40);
			context.waitTicks(10);
			context.takeScreenshot("dlg-4-after-hand-in");
			Screen after = context.computeOnClient(mc -> mc.gui.screen());
			check(after instanceof DialogScreen<?>, "after hand-in the screen is " + after);
			int emeraldsAfter = sp.getServer().computeOnServer(server -> countEmeralds(player(server)));
			check(emeraldsAfter - emeraldsBefore == deliver.emeralds(), "emeralds " + (emeraldsAfter - emeraldsBefore) + " vs " + deliver.emeralds());
			closeScreen(context);
			context.waitTicks(5);
			context.takeScreenshot("hud-3-complete-title");

			// A failed hand-in (hunt not done) also comes back to a dialog.
			sp.getServer().runOnServer(server -> ErrandService.accept(player(server), kill));
			openVillager(context, sp, kill.villager());
			press(context, label -> label.contains(killLabel));
			press(context, label -> label.equals("Complete"));
			context.waitTicks(10);
			after = context.computeOnClient(mc -> mc.gui.screen());
			check(after instanceof DialogScreen<?>, "after a failed hand-in the screen is " + after);
			closeScreen(context);

			// The hunt: kill the monsters, "Errand ready!", hand in at the board.
			sp.getServer().runOnServer(server -> killTargets(server, kill));
			context.waitTicks(10);
			context.takeScreenshot("hud-4-hunt-ready");
			walkTo(context, sp, Vec3.atCenterOf(bell).add(0, -0.5, -2));
			context.getInput().lookAt(bell);
			context.waitTicks(2);
			context.getInput().pressKey(options -> options.keyUse);
			waitForDialog(context, null);
			context.takeScreenshot("dlg-5-board");
			press(context, label -> label.startsWith("Residents"));
			context.takeScreenshot("dlg-5b-residents");
			checkButtonColumns(context);
			press(context, label -> label.equals("Back"));
			press(context, label -> label.contains("Track village"));
			closeScreen(context);
			context.waitTicks(15);
			context.takeScreenshot("hud-5-tracked-village");
			context.getInput().pressKey(options -> options.keyUse);
			waitForDialog(context, null);
			press(context, label -> label.contains("Errands ("));
			context.takeScreenshot("dlg-5c-board-errands");
			press(context, label -> label.contains(killLabel));
			press(context, label -> label.equals("Complete"));
			sp.getServer().waitFor(server -> active(server).isEmpty(), 40);
			context.waitTicks(10);
			check(context.computeOnClient(mc -> mc.gui.screen()) instanceof DialogScreen<?>, "no dialog after board hand-in");
			check(sp.getServer().computeOnServer(server -> Reputation.village(state(server),
				ErrandGenerator.village(villager(server, kill.villager())))) == deliver.reputation() + kill.reputation(), "village rep");

			// Smelting and harvesting through the real game paths.
			Errand smelt = sp.getServer().computeOnServer(server -> findErrand(server, villagers,
				e -> e.main().type() == Objective.Type.SMELT && !e.main().tag() && ErrandService.status(player(server), e) == ErrandService.Status.AVAILABLE));
			Errand harvest = sp.getServer().computeOnServer(server -> findErrand(server, villagers,
				e -> e.main().type() == Objective.Type.HARVEST && !e.main().tag() && ErrandService.status(player(server), e) == ErrandService.Status.AVAILABLE));
			System.out.println("ERRANDS-COVERAGE smelt=" + (smelt != null ? smelt.id() + " " + smelt.main() : "none")
				+ " harvest=" + (harvest != null ? harvest.main() : "none"));
			if (smelt != null) {
				sp.getServer().runOnServer(server -> ErrandService.accept(player(server), smelt));
				sp.getServer().runOnServer(server -> smeltInFurnace(server, base, smelt.main()));
				check(sp.getServer().computeOnServer(server -> state(server).find(smelt.id()).progress[0]) == smelt.main().count(), "smelt progress");
				sp.getServer().runOnServer(server -> ErrandService.abandon(player(server), smelt.id()));
			}
			if (harvest != null) {
				sp.getServer().runOnServer(server -> ErrandService.accept(player(server), harvest));
				sp.getServer().runOnServer(server -> harvestCrops(server, base, harvest.main()));
				check(sp.getServer().computeOnServer(server -> state(server).find(harvest.id()).progress[0]) == harvest.main().count(), "harvest progress");
				sp.getServer().runOnServer(server -> ErrandService.abandon(player(server), harvest.id()));
			}

			// Every action hook through the real trigger or game path, on one synthetic errand.
			List<Objective> actions = List.of(
				new Objective(Objective.Type.HARVEST, "minecraft:wheat", false, 2, 0),
				new Objective(Objective.Type.MINE, "minecraft:coal_ores", true, 1, 0),
				new Objective(Objective.Type.BREED, "minecraft:cow", false, 1, 0),
				new Objective(Objective.Type.TAME, "minecraft:cat", false, 1, 0),
				new Objective(Objective.Type.SHEAR, "minecraft:sheep", false, 1, 0),
				new Objective(Objective.Type.CATCH, "minecraft:cod", false, 2, 0),
				new Objective(Objective.Type.BONEMEAL, "", false, 1, 0));
			Errand synthetic = new Errand("test/0/0", UUID.randomUUID(), "minecraft:farmer", "minecraft:plains", 1, 1, Optional.empty(),
				Optional.empty(), actions, 1, 0, 1, Optional.empty(), 1);
			sp.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				ServerLevel level = server.overworld();
				check(ErrandService.accept(player, synthetic), "synthetic accept");
				harvestCrops(server, base, actions.get(0));
				BlockPos ore = base.offset(-4, 0, 4);
				level.setBlockAndUpdate(ore, Blocks.COAL_ORE.defaultBlockState());
				player.gameMode.destroyBlock(ore);
				var cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
				var calf = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
				net.minecraft.advancements.triggers.CriteriaTriggers.BRED_ANIMALS.trigger(player, cow, cow, calf);
				var cat = EntityTypes.CAT.create(level, EntitySpawnReason.COMMAND);
				net.minecraft.advancements.triggers.CriteriaTriggers.TAME_ANIMAL.trigger(player, cat);
				var sheep = EntityTypes.SHEEP.create(level, EntitySpawnReason.COMMAND);
				net.minecraft.advancements.triggers.CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY.trigger(player, new ItemStack(Items.SHEARS), sheep);
				var hook = EntityTypes.FISHING_BOBBER.create(level, EntitySpawnReason.COMMAND);
				net.minecraft.advancements.triggers.CriteriaTriggers.FISHING_ROD_HOOKED.trigger(player, new ItemStack(Items.FISHING_ROD), hook,
					List.of(new ItemStack(Items.COD, 2)));
				net.minecraft.advancements.triggers.CriteriaTriggers.ITEM_USED_ON_BLOCK.trigger(player, base, new ItemStack(Items.BONE_MEAL));
				int[] progress = state(server).find(synthetic.id()).progress;
				for (int i = 0; i < actions.size(); i++) {
					check(progress[i] == actions.get(i).needed(), "action " + actions.get(i).type() + " progress " + progress[i]);
				}
				ErrandService.abandon(player, synthetic.id());
			});

			// An order: several things made and brought at once (toolsmith "A full tool set").
			sp.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				// Earlier rewards may include a tool of the same kind.
				player.getInventory().clearContent();
				Errand order = null;
				for (int i = 0; i < 400 && order == null; i++) {
					for (Errand e : ErrandGenerator.generate(new UUID(42, i), i, "minecraft:toolsmith", "minecraft:plains", 5, Optional.empty(), 3)) {
						if (e.title().isPresent()) {
							order = e;
							break;
						}
					}
				}
				check(order != null && order.objectives().size() == 6, "no toolsmith order generated");
				check(ErrandText.task(player, order).getString().equals("A full tool set"), "order title " + ErrandText.task(player, order).getString());
				check(ErrandText.lore(player, order).map(c -> c.getString()).orElse("").contains("bare hands"), "order lore");
				check(ErrandService.accept(player, order), "order accept");
				Errand accepted = order;
				for (Objective o : accepted.objectives()) {
					if (o.type() == Objective.Type.CRAFT) craft(server, o, o.count());
				}
				check(!ErrandService.canHandIn(player, state(server).find(accepted.id())), "order ready without the items");
				for (Objective o : accepted.objectives()) {
					if (o.isDelivery()) giveTarget(player, o);
				}
				check(ErrandService.canHandIn(player, state(server).find(accepted.id())), "order not ready");
				check(ErrandService.handIn(player, accepted.id()), "order hand in");
				for (Objective o : accepted.objectives()) {
					if (!o.isDelivery()) continue;
					int left = 0;
					for (ItemStack st : player.getInventory().getNonEquipmentItems()) if (o.matchesItem(st)) left += st.getCount();
					// The reward may be one of the same tools.
					int rewarded = accepted.item().map(r -> r.create(server.overworld()).stream().filter(o::matchesItem)
						.mapToInt(ItemStack::getCount).sum()).orElse(0);
					check(left == rewarded, "items left after order: " + o.target() + " " + left + ", rewarded " + rewarded);
				}
			});

			// Reforge: forge iron swords, bring them, get one enchanted. An enchanted sword never counts as one to bring.
			sp.getServer().runOnServer(server -> {
				ServerPlayer player = player(server);
				ServerLevel level = server.overworld();
				player.getInventory().clearContent();
				Errand reforge = null;
				for (int i = 0; i < 400 && reforge == null; i++) {
					for (Errand e : ErrandGenerator.generate(new UUID(44, i), i, "minecraft:weaponsmith", "minecraft:plains", 3, Optional.empty(), 3)) {
						if (e.item().isPresent() && e.item().get().id().equals("minecraft:iron_sword") && e.main().target().equals("minecraft:iron_sword")) {
							reforge = e;
							break;
						}
					}
				}
				check(reforge != null, "no iron sword reforge generated");
				check(reforge.emeralds() == 0 && reforge.xp() > 0, "reforge pays the sword and some XP");
				check(ErrandText.lore(player, reforge).map(c -> c.getString()).orElse("").contains("enchant"), "reforge lore");
				check(ErrandService.accept(player, reforge), "reforge accept");
				Errand accepted = reforge;
				Objective make = accepted.main();
				craft(server, make, make.count());
				ItemStack enchanted = net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(
					net.minecraft.util.RandomSource.create(1), new ItemStack(Items.IRON_SWORD), 20, level.registryAccess(), Optional.empty());
				check(enchanted.isEnchanted(), "test sword enchanted");
				player.getInventory().add(enchanted.copy());
				player.getInventory().add(new ItemStack(Items.IRON_SWORD, make.count() - 1));
				check(!ErrandService.canHandIn(player, state(server).find(accepted.id())), "an enchanted sword counted for the reforge");
				player.getInventory().add(new ItemStack(Items.IRON_SWORD));
				check(ErrandService.canHandIn(player, state(server).find(accepted.id())), "reforge not ready");
				check(ErrandService.handIn(player, accepted.id()), "reforge hand in");
				int plain = 0;
				int magic = 0;
				for (ItemStack st : player.getInventory().getNonEquipmentItems()) {
					if (!st.is(Items.IRON_SWORD)) continue;
					if (st.isEnchanted()) magic += st.getCount();
					else plain += st.getCount();
				}
				check(plain == 0 && magic == 2, "after reforge: plain " + plain + ", enchanted " + magic);
				check(!ErrandText.enchantments(accepted.item().get().create(level).getFirst()).getString().isEmpty(), "reward enchantment list");
				// Every enchantment of a reforge reward is at its highest level.
				ItemStack reward = accepted.item().get().create(level, 9).getFirst();
				for (var entry : reward.getEnchantments().entrySet()) {
					check(entry.getIntValue() == entry.getKey().value().getMaxLevel(),
						"reforge enchantment " + entry.getKey().getRegisteredName() + " " + entry.getIntValue());
				}
			});

			// Errands saved by the previous version (single deliver/kill fields) still load.
			net.minecraft.nbt.CompoundTag old = new net.minecraft.nbt.CompoundTag();
			old.putString("id", "old/1/0");
			old.putString("villager", UUID.randomUUID().toString());
			old.putString("kind", "KILL");
			old.putString("target", "minecraft:zombie");
			old.putInt("count", 6);
			Errand legacy = Errand.load(old);
			check(legacy.objectives().size() == 1 && legacy.main().type() == Objective.Type.KILL && legacy.main().count() == 6,
				"legacy errand " + legacy.objectives());

			// Journal and chronicle.
			Errand another = sp.getServer().computeOnServer(server -> findAvailable(server, villagers));
			sp.getServer().runOnServer(server -> ErrandService.accept(player(server), another));
			// A second order stays accepted, to look at its screen from the journal.
			sp.getServer().runOnServer(server -> {
				for (int i = 0; i < 400; i++) {
					for (Errand e : ErrandGenerator.generate(new UUID(43, i), i, "minecraft:butcher", "minecraft:plains", 5, Optional.empty(), 3)) {
						if (e.title().isPresent()) {
							ErrandService.accept(player(server), e);
							return;
						}
					}
				}
			});
			// A diamond reforge stays accepted too, to look at its reward.
			sp.getServer().runOnServer(server -> {
				for (int i = 0; i < 400; i++) {
					for (Errand e : ErrandGenerator.generate(new UUID(45, i), i, "minecraft:armorer", "minecraft:plains", 5, Optional.empty(), 4)) {
						if (e.item().isPresent() && e.main().target().equals("minecraft:diamond_chestplate")) {
							ErrandService.accept(player(server), e);
							return;
						}
					}
				}
			});
			// From the board's errand list back to the bell menu, where the journal is.
			press(context, label -> label.equals("Back"));
			press(context, label -> label.contains("Errand journal"));
			context.takeScreenshot("dlg-6-journal");
			press(context, label -> label.contains("Village feast") || label.contains("Lunch for the smiths"));
			context.takeScreenshot("dlg-7-order");
			checkColumns(context);
			press(context, label -> label.equals("Back"));
			press(context, label -> label.contains("Diamond Chestplate"));
			context.takeScreenshot("dlg-8-reforge");
			String reforgeText = context.computeOnClient(mc -> {
				StringBuilder text = new StringBuilder();
				if (mc.gui.screen() instanceof DialogScreen<?> screen) {
					for (var body : dialog(screen).common().body()) {
						if (body instanceof net.minecraft.server.dialog.body.PlainMessage m) text.append(m.contents().getString()).append('\n');
					}
				}
				return text.toString();
			});
			check(reforgeText.contains("1× Enchanted Diamond Chestplate"), "reforge reward name: " + reforgeText);
			press(context, label -> label.equals("Back"));
			press(context, label -> label.contains("Errand chronicle"));
			context.waitFor(mc -> mc.gui.screen() instanceof BookViewScreen, 60);
			context.takeScreenshot("book-1");
			context.runOnClient(mc -> mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(262, 0, 0)));
			context.waitTicks(3);
			context.takeScreenshot("book-2");
			closeScreen(context);
			check(sp.getServer().computeOnServer(server -> state(server).completed) == 4, "completed counter");

			// Test command: tier 3 in the village with what it needs.
			walkTo(context, sp, Vec3.atCenterOf(base.above()));
			sp.getServer().runCommand("errands standing @p 3");
			int tier = sp.getServer().computeOnServer(server -> {
				ServerPlayer player = player(server);
				var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition());
				return village.map(v -> Reputation.villageTier(state(server), Optional.of(v.center))).orElse(-1);
			});
			check(tier == 3, "standing command gave village tier " + tier);
			int extra = sp.getServer().computeOnServer(server -> {
				ServerPlayer player = player(server);
				var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition());
				return Reputation.enchantBonus(state(server), village.map(v -> v.center));
			});
			check(extra == 9, "enchant bonus at village tier 3: " + extra);

			// Residents at every villager tier, to look at the colours.
			sp.getServer().runOnServer(server -> {
				int[] points = {0, 25, 70, 150, 300};
				for (int i = 0; i < points.length && i < villagers.size(); i++) {
					state(server).villagerRep.put(villagers.get(i), points[i]);
					state(server).trialsPassed.put(villagers.get(i), Reputation.tier(points[i], Reputation.VILLAGER_TIERS));
				}
			});
			walkTo(context, sp, Vec3.atCenterOf(bell).add(0, -0.5, -2));
			context.getInput().lookAt(bell);
			context.waitTicks(2);
			context.getInput().pressKey(options -> options.keyUse);
			waitForDialog(context, null);
			context.takeScreenshot("dlg-9-board-tier3");
			press(context, label -> label.startsWith("Residents"));
			context.takeScreenshot("dlg-9b-residents-tiers");
			checkButtonColumns(context);
			closeScreen(context);
			adminCommands(context, sp, villagers);
			packs(sp, villagers);
			partialDelivery(sp);
			rewardSwitches(sp);
			commandPermissions(sp);
			trials(context, sp, villagers);
			treasury(context, sp, bell);
			uniqueNames(sp);
			zombieAndCure(sp, base);
			topRewards(context, sp);
			names(context, sp, villagers);
			tradeFromGreeting(context, sp, villagers);
			prices(context, sp, villagers);
			endGame(context, sp, villagers, bell);
		}
	}

	// ---- dialogs ----

	private static void openVillager(ClientGameTestContext context, TestSingleplayerContext sp, UUID id) {
		Vec3 eyes = sp.getServer().computeOnServer(server -> villager(server, id).getEyePosition());
		Vec3 feet = sp.getServer().computeOnServer(server -> villager(server, id).position());
		Vec3 stand = sp.getServer().computeOnServer(server -> {
			Vec3 center = Vec3.atBottomCenterOf(player(server).blockPosition());
			Vec3 dir = center.subtract(feet).multiply(1, 0, 1).normalize();
			return feet.add(dir.scale(2.0));
		});
		walkTo(context, sp, stand);
		context.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, eyes));
		context.waitTicks(3);
		// Right-click: the villager greets; Errands opens their list.
		context.getInput().pressKey(options -> options.keyUse);
		waitForDialog(context, null);
		greeted++;
		if (greeted == 1) context.takeScreenshot("dlg-0-greeting");
		press(context, label -> label.contains("Errands ("));
	}

	private static int greeted;

	private static void walkTo(ClientGameTestContext context, TestSingleplayerContext sp, Vec3 pos) {
		sp.getServer().runOnServer(server -> player(server).teleportTo(pos.x, pos.y, pos.z));
		context.waitTicks(5);
	}

	/** Presses the dialog button whose label matches, exactly as a click would, and waits for the next dialog. */
	private static void press(ClientGameTestContext context, java.util.function.Predicate<String> label) {
		press(context, label, true);
	}

	private static void press(ClientGameTestContext context, java.util.function.Predicate<String> label, boolean waitDialog) {
		Screen before = context.computeOnClient(mc -> {
			if (!(mc.gui.screen() instanceof DialogScreen<?> screen)) throw new AssertionError("no dialog open: " + mc.gui.screen());
			ActionButton found = null;
			List<String> labels = new ArrayList<>();
			for (ActionButton b : buttons(dialog(screen))) {
				String text = b.button().label().getString();
				labels.add(text);
				if (found == null && label.test(text)) found = b;
			}
			if (found == null) throw new AssertionError("no matching button in " + labels);
			Optional<ClickEvent> click = found.action().map(a -> ((StaticAction) a).value());
			screen.runAction(click);
			return screen;
		});
		if (waitDialog) waitForDialog(context, before);
	}

	private static void waitForDialog(ClientGameTestContext context, Screen previous) {
		context.waitFor(mc -> {
			Screen s = mc.gui.screen();
			return s != previous && (s instanceof DialogScreen<?> || s instanceof BookViewScreen);
		}, 60);
		context.waitTicks(3);
	}

	/** Closes a container screen the way the player does, so the server lets go of the villager too. */
	private static void closeTrade(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.player.closeContainer());
		context.waitTicks(5);
	}

	private static void closeScreen(ClientGameTestContext context) {
		context.runOnClient(mc -> mc.gui.setScreen(null));
		context.waitTicks(3);
	}

	/**
	 * Measures every character with the real default font and writes the table TextWidth reads. When it changed, the
	 * server already loaded the old one, so the test stops and asks for another run.
	 */
	private static void writeFontWidths(ClientGameTestContext context) {
		byte[] table = context.computeOnClient(mc -> {
			net.minecraft.client.StringSplitter splitter = mc.font.getSplitter();
			byte[] out = new byte[0x10000 + 2];
			for (int cp = 0x20; cp < 0x10000; cp++) {
				if (Character.isSurrogate((char) cp)) continue;
				out[cp] = encode(splitter.stringWidth(String.valueOf((char) cp)));
			}
			out[0x10000] = encode(splitter.stringWidth(net.valcheff.villagersremember.ui.Icons.of(Items.COD)) - splitter.stringWidth(" "));
			out[0x10001] = encode(splitter.stringWidth(net.minecraft.network.chat.Component.literal("a")
				.withStyle(net.minecraft.ChatFormatting.BOLD)) - splitter.stringWidth("a"));
			return out;
		});
		Path file = projectDir().resolve("src/main/resources/assets/villagersremember/font_widths.bin");
		try {
			if (Files.exists(file) && java.util.Arrays.equals(Files.readAllBytes(file), table)) return;
			Files.write(file, table);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
		throw new AssertionError("font widths written to " + file + ", build and run the test again");
	}

	private static byte encode(float width) {
		return (byte) Math.clamp(Math.round(width * 2) + 1, 1, 255);
	}

	private static Path projectDir() {
		for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
			if (Files.exists(p.resolve("build.gradle"))) return p;
		}
		throw new AssertionError("project directory not found");
	}

	/** The objective lines of the open errand screen: every one exactly as wide as the others, so they line up. */
	private static void checkColumns(ClientGameTestContext context) {
		String result = context.computeOnClient(mc -> {
			if (!(mc.gui.screen() instanceof DialogScreen<?> screen)) return "no dialog";
			List<net.minecraft.server.dialog.body.DialogBody> body = dialog(screen).common().body();
			if (body.size() < 3 || !(body.get(2) instanceof net.minecraft.server.dialog.body.PlainMessage message)) {
				return "no objectives body";
			}
			List<Integer> widths = new ArrayList<>();
			for (net.minecraft.network.chat.FormattedText line : mc.font.getSplitter().splitLines(message.contents(), 10000,
				net.minecraft.network.chat.Style.EMPTY)) {
				if (!line.getString().isBlank()) widths.add(mc.font.width(line));
			}
			if (widths.size() < 2) return "too few lines " + widths;
			if (widths.stream().distinct().count() != 1) return "widths differ " + widths;
			return widths.getFirst() <= message.width() ? "ok" : "wider than the body " + widths.getFirst() + " > " + message.width();
		});
		check(result.equals("ok"), "objective columns: " + result);
	}

	/** Right-click, then Trade: vanilla's trading screen opens. */
	private static void tradeFromGreeting(ClientGameTestContext context, TestSingleplayerContext sp, List<UUID> villagers) {
		UUID id = villagers.get(6);
		Vec3 eyes = sp.getServer().computeOnServer(server -> villager(server, id).getEyePosition());
		Vec3 feet = sp.getServer().computeOnServer(server -> villager(server, id).position());
		walkTo(context, sp, feet.add(0, 0, 2));
		context.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, eyes));
		context.waitTicks(3);
		context.getInput().pressKey(options -> options.keyUse);
		waitForDialog(context, null);
		Screen greeting = context.computeOnClient(mc -> mc.gui.screen());
		press(context, label -> label.contains("Trade"), false);
		context.waitFor(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen, 60);
		context.takeScreenshot("dlg-13-trade");
		closeTrade(context);
	}

	/**
	 * Prices by standing: the trading screen shows the discount (and a surcharge for a grudge), and gossip written
	 * by older versions is taken back the first time.
	 */
	private static void prices(ClientGameTestContext context, TestSingleplayerContext sp, List<UUID> villagers) {
		UUID id = villagers.get(6);
		check(Math.abs(net.valcheff.villagersremember.Prices.change(20, 0.66) + 7) == 0, "20 at 34 % off costs 13");
		check(net.valcheff.villagersremember.Prices.change(1, 0.6) == 0, "a price never drops below 1");
		sp.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			Villager v = villager(server, id);
			state(server).villagerRep.put(id, Reputation.VILLAGER_TIERS[4]);
			state(server).trialsPassed.put(id, 4);
			// What 0.1 left behind: gossip for the errands.
			v.getGossips().add(player.getUUID(), net.minecraft.world.entity.ai.gossip.GossipType.MAJOR_POSITIVE, 15);
			v.getGossips().add(player.getUUID(), net.minecraft.world.entity.ai.gossip.GossipType.MINOR_POSITIVE, 8);
			state(server).gossipToClean.put(id, 15);
		});
		double factor = sp.getServer().computeOnServer(server ->
			Reputation.priceFactor(state(server), id, net.valcheff.villagersremember.ErrandGenerator.village(villager(server, id))));
		check(factor <= 0.84 + 1e-9 && factor >= 0.55 - 1e-9, "best friend price factor " + factor);
		checkTradePrices(context, sp, id, factor, "discount");
		int gossip = sp.getServer().computeOnServer(server -> villager(server, id).getPlayerReputation(player(server)));
		check(gossip == 0, "old gossip cleaned, reputation left " + gossip);
		check(sp.getServer().computeOnServer(server -> state(server).gossipToClean.isEmpty()), "cleanup done once");

		// A grudge: 30 points below zero, 30 % more.
		sp.getServer().runOnServer(server -> {
			state(server).villagerRep.put(id, -30);
			state(server).trialsPassed.remove(id);
		});
		double grudge = sp.getServer().computeOnServer(server ->
			Reputation.priceFactor(state(server), id, net.valcheff.villagersremember.ErrandGenerator.village(villager(server, id))));
		double surcharge = sp.getServer().computeOnServer(server ->
			Reputation.surcharge(state(server), id, net.valcheff.villagersremember.ErrandGenerator.village(villager(server, id))));
		check(Math.abs(surcharge - 0.30) < 1e-9 && grudge > factor, "surcharge " + surcharge + ", factor " + grudge);
		checkTradePrices(context, sp, id, grudge, "surcharge");
		sp.getServer().runOnServer(server -> state(server).villagerRep.put(id, 0));
	}

	/**
	 * End game: patron (one per village, taken by more points), weekly order brought in parts and paid by share,
	 * a contract bringing a legendary item, fame titles in the TAB/chat team, the Legend's trader goods and the
	 * advancement tree.
	 */
	private static void endGame(ClientGameTestContext context, TestSingleplayerContext sp, List<UUID> villagers, BlockPos bell) {
		walkTo(context, sp, Vec3.atCenterOf(bell).add(0, -0.5, -2));
		context.getInput().lookAt(bell);
		context.waitTicks(2);
		sp.getServer().runCommand("errands standing @p 4");
		String patron = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			net.valcheff.villagersremember.Patron.check(server, Optional.of(village.center));
			if (!player.getUUID().equals(village.patron)) return "not patron: qualifies " + net.valcheff.villagersremember.Patron.qualifies(state, village)
				+ ", best friends " + net.valcheff.villagersremember.Patron.bestFriends(state, village);
			double d = Reputation.discount(state, villagers.get(0), Optional.of(village.center));
			if (Math.abs(d - 0.45) > 1e-9) return "patron discount " + d;
			// A rival with more points takes the place; when the rival is gone the place comes back.
			UUID rivalId = new UUID(0xBADL, 1);
			ErrandsData.PlayerState rival = ErrandsData.get(server).player(rivalId);
			rival.villagerRep.putAll(state.villagerRep);
			rival.trialsPassed.putAll(state.trialsPassed);
			String key = ErrandsData.villageKey(village.center);
			rival.villageRep.put(key, state.villageRep.getOrDefault(key, 0) + 100);
			net.valcheff.villagersremember.Patron.check(server, Optional.of(village.center));
			if (!rivalId.equals(village.patron)) return "rival with more points did not take the place";
			if (Reputation.discount(state, villagers.get(0), Optional.of(village.center)) > 0.4 + 1e-9) return "former patron kept the discount";
			ErrandsData.get(server).players().remove(rivalId);
			net.valcheff.villagersremember.Patron.check(server, Optional.of(village.center));
			if (!player.getUUID().equals(village.patron)) return "place did not come back";
			if (net.valcheff.villagersremember.Patron.rename(player, village.center, "")) return "empty name accepted";
			if (!net.valcheff.villagersremember.Patron.rename(player, village.center, "Testville") || !"Testville".equals(village.name)) return "rename";
			return "ok";
		});
		check(patron.equals("ok"), "patron: " + patron);

		// The village order (monthly), brought in parts through the button.
		String kind = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			var view = net.valcheff.villagersremember.GroupOrder.current(server, village).orElseThrow();
			player.getInventory().clearContent();
			Item item = switch (view.kind()) {
				case FOOD -> Items.BREAD;
				case WOOD -> Items.OAK_LOG;
				case STONE -> Items.COBBLESTONE;
				case IRON -> Items.IRON_INGOT;
				case WOOL -> net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("white_wool"));
				case DEFEND -> Items.AIR;
			};
			if (item != Items.AIR) player.getInventory().add(new ItemStack(item, 32));
			return view.kind().key();
		});
		context.getInput().pressKey(options -> options.keyUse);
		waitForDialog(context, null);
		context.takeScreenshot("dlg-14-board-patron");
		press(context, label -> label.contains("Village order"));
		context.takeScreenshot("dlg-15-village-order");
		press(context, label -> label.contains("Track"));
		check(sp.getServer().computeOnServer(server -> state(server).trackedOrders.size() == 1), "village order tracked");
		if (!kind.equals("defend")) {
			press(context, label -> label.contains("Give what I have"));
			int progress = sp.getServer().computeOnServer(server -> {
				ServerPlayer player = player(server);
				var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
				return net.valcheff.villagersremember.GroupOrder.current(server, village).orElseThrow().order().progress;
			});
			check(progress == 32, "village order got " + progress + " of 32 " + kind);
		} else {
			// A monster killed inside the village counts.
			int progress = sp.getServer().computeOnServer(server -> {
				ServerPlayer player = player(server);
				var zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(server.overworld(), EntitySpawnReason.COMMAND);
				zombie.snapTo(player.getX() + 2, player.getY(), player.getZ(), 0, 0);
				server.overworld().addFreshEntity(zombie);
				zombie.hurtServer(server.overworld(), player.damageSources().playerAttack(player), 1000);
				var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
				return net.valcheff.villagersremember.GroupOrder.current(server, village).orElseThrow().order().progress;
			});
			check(progress == 1, "defend order counted " + progress);
		}
		// The patron names the village through the dialog's text field (a command template runs /errands input).
		press(context, label -> label.equals("Back"));
		press(context, label -> label.contains("Name the village"));
		context.takeScreenshot("dlg-16-rename");
		// What the Save button does with the text typed in the field: its command template filled in.
		Screen renameScreen = context.computeOnClient(mc -> {
			DialogScreen<?> screen = (DialogScreen<?>) mc.gui.screen();
			ActionButton save = buttons(dialog(screen)).stream().filter(x -> x.button().label().getString().equals("Save")).findFirst().orElseThrow();
			Optional<ClickEvent> click = save.action().orElseThrow().createAction(java.util.Map.of("name",
				net.minecraft.server.dialog.action.Action.ValueGetter.of("Bodilovo Upper")));
			screen.runAction(click);
			return screen;
		});
		waitForDialog(context, renameScreen);
		String renamed = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			return village.name;
		});
		check("Bodilovo Upper".equals(renamed), "renamed through the dialog to " + renamed);
		closeScreen(context);
		String villageOrder = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			player.getEnderChestInventory().clearContent();
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "errands order here add " + player.getScoreboardName() + " 100000");
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			var view = net.valcheff.villagersremember.GroupOrder.current(server, village).orElseThrow();
			if (!view.order().settled || !view.done()) return "not settled " + view.order().progress + "/" + view.target();
			int emeralds = player.getEnderChestInventory().countItem(Items.EMERALD);
			if (view.order().players != 1 || view.target() != view.kind().base) return "sized for " + view.order().players + ": " + view.target();
			if (emeralds != Math.round(net.valcheff.villagersremember.GroupOrder.pool(1))) return "paid " + emeralds;
			if (state(server).weeklyDone != 1) return "orders done " + state(server).weeklyDone;
			return "ok";
		});
		check(villageOrder.equals("ok"), "village order: " + villageOrder);
		String mapped = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			// The flat test world has no structures: a map to the biome the player stands in.
			String biome = player.level().getBiome(player.blockPosition()).unwrapKey().orElseThrow().identifier().toString();
			Objective visit = new Objective(Objective.Type.VISIT_BIOME, biome, false, 1, 0);
			var map = net.valcheff.villagersremember.Maps.forVisit(player, visit, "Odo");
			if (map.isEmpty()) return "no map to " + biome;
			// The chart given with an errand unfolds into the map when used.
			ItemStack chart = net.valcheff.villagersremember.Maps.chart(player, visit, "Odo").orElseThrow();
			ItemStack held = player.getMainHandItem().copy();
			player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, chart);
			net.valcheff.villagersremember.Maps.use(player, net.minecraft.world.InteractionHand.MAIN_HAND);
			ItemStack unfolded = player.getMainHandItem();
			player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
			if (unfolded.get(net.minecraft.core.component.DataComponents.MAP_ID) == null) return "chart did not unfold: " + unfolded;
			if (!map.get().is(Items.FILLED_MAP) || !map.get().getHoverName().getString().endsWith(" Map")) return "map " + map.get();
			// Enchanted rewards: from any set of enchantments, never a curse, always at the highest level.
			for (int i = 0; i < 60; i++) {
				ItemStack sword = new Errand.ItemReward(net.valcheff.villagersremember.ErrandPools.RewardKind.ENCHANT_MAX, "minecraft:iron_sword", 12, i)
					.create(server.overworld()).getFirst();
				for (var e : sword.getEnchantments().entrySet()) {
					if (e.getKey().is(net.minecraft.tags.EnchantmentTags.CURSE)) return "curse on reward " + i;
					if (e.getIntValue() != e.getKey().value().getMaxLevel()) return "level " + e.getIntValue() + " of " + e.getKey().getRegisteredName();
				}
			}
			return "ok";
		});
		check(mapped.equals("ok"), "visit map and enchantments: " + mapped);
		// Sized by regular players: two friends who played on three days of the month count, a one-day visitor does not.
		int regulars = sp.getServer().computeOnServer(server -> {
			long today = ErrandGenerator.day(server.overworld());
			for (int i = 0; i < 3; i++) {
				ErrandsData.PlayerState friend = ErrandsData.get(server).player(new UUID(0xF1EL, i));
				int days = i < 2 ? 3 : 1;
				for (int d = 0; d < days; d++) friend.daysSeen.add(today - d);
			}
			int n = net.valcheff.villagersremember.GroupOrder.regularPlayers(server);
			for (int i = 0; i < 3; i++) ErrandsData.get(server).players().remove(new UUID(0xF1EL, i));
			return n;
		});
		check(regulars == 2, "regular players " + regulars);

		// A contract with a Best friend: the third one for the profession brings its legendary item.
		String contract = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			state.active.clear();
			player.getInventory().clearContent();
			Villager v = null;
			Errand c = null;
			for (UUID id : villagers) {
				Villager candidate = villager(server, id);
				var found = ErrandGenerator.contract(server.overworld(), ErrandGenerator.Giver.of(candidate), state);
				String path = ErrandText.path(found.map(Errand::profession).orElse("minecraft:none"));
				if (found.isPresent() && net.valcheff.villagersremember.Legends.professions().contains(path)) {
					v = candidate;
					c = found.get();
					break;
				}
			}
			if (c == null) return "no contract offered";
			if (!ErrandGenerator.shown(server.overworld(), ErrandGenerator.Giver.of(v), state).stream().anyMatch(ErrandGenerator::isContract)) return "contract not shown";
			String prof = ErrandText.path(c.profession());
			// Three parts, at least two of them things to go and do, about 90 minutes; currency only, once a week.
			long actions = c.objectives().stream().filter(o -> switch (o.type()) {
				case DELIVER, CRAFT, SMELT, STATION, PLANT, BONEMEAL, TRADE -> false;
				default -> true;
			}).count();
			if (c.objectives().size() < 3 || actions < 2 || c.value() < 60) return "contract too light: " + c.objectives() + ", minutes " + c.value();
			if (c.item().isPresent() || c.emeralds() > ErrandsConfig.INSTANCE.contractMaxEmeralds || !c.id().contains("/w")) return "contract pay " + c;
			System.out.println("CONTRACT " + ErrandText.task(player, c).getString() + ": " + c.objectives().stream()
				.map(o -> o.type() + " " + o.count() + " " + o.target()).toList() + " value " + Math.round(c.value()) + ", " + c.emeralds() + " emeralds");
			state.contracts.put(prof, 2);
			int fameBefore = state.fame;
			if (!ErrandService.accept(player, c)) return "accept";
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "errands finish " + player.getScoreboardName() + " items");
			if (!ErrandService.handIn(player, c.id(), 0)) return "hand in: " + c.id();
			if (!state.legends.contains(prof) || state.contracts.get(prof) != 3) return "legend " + state.legends + " contracts " + state.contracts;
			boolean has = false;
			for (ItemStack st : player.getInventory().getNonEquipmentItems()) {
				if (st.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME) != null && st.getRarity() == net.minecraft.world.item.Rarity.EPIC) has = true;
			}
			if (!has) return "no legendary item in the inventory";
			if (state.fame < fameBefore + 5 + 15) return "fame " + fameBefore + " -> " + state.fame;
			if (state.collection.isEmpty()) return "collection empty";
			return "ok " + prof;
		});
		check(contract.startsWith("ok"), "contract: " + contract);

		// Fame titles: a scoreboard team whose prefix is the title; a Legend finds rare goods with the trader.
		String fame = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "errands fame " + player.getScoreboardName() + " set 120");
			var team = server.getScoreboard().getPlayersTeam(player.getScoreboardName());
			if (team == null || !team.getPlayerPrefix().getString().contains("Helper")) return "team " + (team == null ? null : team.getPlayerPrefix().getString());
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "errands fame " + player.getScoreboardName() + " set 1600");
			team = server.getScoreboard().getPlayersTeam(player.getScoreboardName());
			if (!team.getPlayerPrefix().getString().contains("Legend")) return "legend prefix " + team.getPlayerPrefix().getString();
			var trader = net.minecraft.world.entity.EntityTypes.WANDERING_TRADER.create(server.overworld(), EntitySpawnReason.COMMAND);
			trader.snapTo(player.getX() + 1, player.getY(), player.getZ(), 0, 0);
			trader.setNoAi(true);
			server.overworld().addFreshEntity(trader);
			int before = trader.getOffers().size();
			net.valcheff.villagersremember.Interactions.onUseEntity(player, server.overworld(), net.minecraft.world.InteractionHand.MAIN_HAND, trader, null);
			net.valcheff.villagersremember.Interactions.onUseEntity(player, server.overworld(), net.minecraft.world.InteractionHand.MAIN_HAND, trader, null);
			int after = trader.getOffers().size();
			trader.discard();
			return after == before + 3 ? "ok" : "trader offers " + before + " -> " + after;
		});
		check(fame.equals("ok"), "fame: " + fame);

		String advancements = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			net.valcheff.villagersremember.Milestones.check(player);
			StringBuilder missing = new StringBuilder();
			for (String id : List.of("root", "errands/first", "trust/trial", "trust/best_friend", "trust/close_circle", "contracts/first",
				"contracts/legend", "villages/hero", "villages/patron", "villages/weekly", "explore/first", "treasury/first",
				"fame/helper", "fame/legend")) {
				var holder = server.getAdvancements().get(net.valcheff.villagersremember.VillagersRemember.id(id));
				if (holder == null) missing.append(" ").append(id).append("(no file)");
				else if (!player.getAdvancements().getOrStartProgress(holder).isDone()) missing.append(" ").append(id);
			}
			int total = 0;
			for (var h : server.getAdvancements().getAllAdvancements()) if (h.id().getNamespace().equals("villagersremember")) total++;
			if (total != 45) missing.append(" total=").append(total);
			return missing.toString();
		});
		check(advancements.isEmpty(), "advancements not granted:" + advancements);
		context.runOnClient(mc -> mc.gui.setScreen(new net.minecraft.client.gui.screens.advancements.AdvancementsScreen(mc.player.connection.getAdvancements())));
		context.waitTicks(10);
		context.takeScreenshot("adv-1-tree");
		closeScreen(context);
		context.runOnClient(mc -> mc.player.connection.sendCommand("errands book"));
		context.waitFor(mc -> mc.gui.screen() instanceof BookViewScreen, 60);
		context.runOnClient(mc -> mc.gui.screen().keyPressed(new net.minecraft.client.input.KeyEvent(262, 0, 0)));
		context.waitTicks(3);
		context.takeScreenshot("book-3-fame");
		closeScreen(context);
	}

	private static void checkTradePrices(ClientGameTestContext context, TestSingleplayerContext sp, UUID id, double factor, String what) {
		Vec3 eyes = sp.getServer().computeOnServer(server -> villager(server, id).getEyePosition());
		Vec3 feet = sp.getServer().computeOnServer(server -> villager(server, id).position());
		walkTo(context, sp, feet.add(0, 0, 2));
		context.runOnClient(mc -> mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, eyes));
		context.waitTicks(3);
		context.getInput().pressKey(options -> options.keyUse);
		waitForDialog(context, null);
		press(context, label -> label.contains("Trade"), false);
		context.waitFor(mc -> mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.MerchantScreen, 60);
		context.waitTicks(5);
		context.takeScreenshot("trade-" + what);
		String result = context.computeOnClient(mc -> {
			if (!(mc.player.containerMenu instanceof net.minecraft.world.inventory.MerchantMenu menu)) return "no menu";
			StringBuilder bad = new StringBuilder();
			int n = 0;
			for (var offer : menu.getOffers()) {
				int base = offer.getBaseCostA().getCount();
				int expected = Math.clamp(Math.max(1, Math.round(base * factor)), 1, offer.getBaseCostA().getMaxStackSize());
				int shown = offer.getCostA().getCount();
				n++;
				if (shown != expected) bad.append(base).append("->").append(shown).append(" (want ").append(expected).append(") ");
			}
			return n == 0 ? "no offers" : bad.toString();
		});
		check(result.isEmpty(), what + " prices: " + result);
		closeTrade(context);
	}

	/** Each player sees villager names (in their language) as name tags; the villagers themselves stay unnamed. */
	private static void names(ClientGameTestContext context, TestSingleplayerContext sp, List<UUID> villagers) {
		UUID id = villagers.get(5);
		Vec3 at = sp.getServer().computeOnServer(server -> villager(server, id).position());
		walkTo(context, sp, at.add(0, 0, 4));
		context.getInput().lookAt(BlockPos.containing(at.add(0, 1.5, 0)));
		context.waitTicks(260);
		context.takeScreenshot("hud-6-names");
		String expected = sp.getServer().computeOnServer(server -> ErrandText.villagerName(player(server), id, villager(server, id)));
		boolean serverNamed = sp.getServer().computeOnServer(server -> villager(server, id).hasCustomName());
		// For Jade: the villager's own name on the client. Above the head: our nameplate riding on it.
		String seen = context.computeOnClient(mc -> {
			for (var e : mc.level.entitiesForRendering()) {
				if (!e.getUUID().equals(id)) continue;
				String jade = e.getCustomName() == null ? "<none>" : e.getCustomName().getString();
				String plate = e.getPassengers().stream().filter(p -> p instanceof net.minecraft.world.entity.Display.TextDisplay)
					.map(p -> ((net.minecraft.world.entity.Display.TextDisplay) p).getText().getString())
					.filter(t -> !t.equals("!") && !t.equals("?")).findFirst().orElse("<no plate>");
				return jade + "|" + plate;
			}
			return "<not loaded>";
		});
		check(!serverNamed && seen.equals(expected + "|" + expected), "villager name: client sees " + seen + ", expected " + expected + ", server named " + serverNamed);
	}

	/** T5 errands give each profession's top reward; every one of them builds real items. */
	private static void topRewards(ClientGameTestContext context, TestSingleplayerContext sp) {
		String result = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = player(server);
			StringBuilder seen = new StringBuilder();
			for (String p : List.of("farmer", "fisherman", "shepherd", "librarian", "cartographer", "cleric", "armorer", "weaponsmith",
				"toolsmith", "butcher", "leatherworker", "mason", "fletcher")) {
				List<ItemStack> given = new Errand.ItemReward(net.valcheff.villagersremember.ErrandPools.RewardKind.TOP, "minecraft:" + p, 1, 77)
					.create(level, 0, player.position());
				if (given.isEmpty() || given.stream().anyMatch(ItemStack::isEmpty)) return p + " top reward is empty";
				seen.append(p).append('=').append(given.getFirst().getHoverName().getString()).append(' ');
			}
			System.out.println("TOP-REWARDS " + seen);
			// Gear among the top rewards always has Unbreaking III and Mending, except the bow with Infinity.
			var unbreaking = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.UNBREAKING);
			var mending = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.MENDING);
			var infinity = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
				.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.INFINITY);
			for (String p : List.of("farmer", "butcher", "fisherman", "armorer", "weaponsmith", "toolsmith", "fletcher")) {
				for (List<ItemStack> option : net.valcheff.villagersremember.TopRewards.options("minecraft:" + p, level, 5, null)) {
					ItemStack st = option.getFirst();
					if (!st.isEnchanted()) continue;
					var ench = st.getEnchantments();
					if (ench.getLevel(unbreaking) != 3) return p + " " + st.getHoverName().getString() + " without Unbreaking III";
					if (ench.getLevel(mending) != 1 && ench.getLevel(infinity) != 1) return p + " " + st.getHoverName().getString() + " without Mending";
					for (var e : ench.entrySet()) if (e.getIntValue() != e.getKey().value().getMaxLevel()) return p + " " + e.getKey().getRegisteredName() + " " + e.getIntValue();
				}
			}
			// The librarian's books carry several enchantments, each at its highest level.
			for (int i = 0; i < 40; i++) {
				var book = new Errand.ItemReward(net.valcheff.villagersremember.ErrandPools.RewardKind.TOP, "minecraft:librarian", 1, i)
					.create(level, 0, player.position()).getFirst();
				var stored = book.get(net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS);
				if (stored == null || stored.size() < 2) return "book " + i + " has " + stored;
				for (var e : stored.entrySet()) if (e.getIntValue() != e.getKey().value().getMaxLevel()) return "book level " + e.getIntValue();
			}
			return "ok";
		});
		check(result.equals("ok"), "top rewards: " + result);

		// The top reward comes once per profession, with the first Best-friend trial; the player picks the option
		// (the farmer's carrots instead of the hoe) with the real button.
		String label = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			state.active.clear();
			state.flags.remove("top:farmer");
			UUID farmer = new UUID(0xFA4L, 1);
			state.villagerRep.put(farmer, Reputation.VILLAGER_TIERS[4]);
			state.trialsPassed.put(farmer, 3);
			var giver = new ErrandGenerator.Giver(farmer, "minecraft:farmer", "minecraft:plains", 5, Optional.empty());
			Errand trial = ErrandGenerator.trial(server.overworld(), giver, state).orElse(null);
			if (trial == null || !trial.item().map(r -> r.kind() == net.valcheff.villagersremember.ErrandPools.RewardKind.TOP).orElse(false)) return "";
			ErrandService.accept(player, trial);
			return ErrandText.task(player, trial).getString();
		});
		check(!label.isEmpty(), "no farmer top trial");
		// A full inventory: the errand is not handed in, nothing is dropped.
		sp.getServer().runCommand("errands finish @p");
		String full = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			player.getInventory().clearContent();
			ErrandsData.Active a = state(server).active.getFirst();
			for (Objective o : a.errand.objectives()) if (o.isDelivery()) giveTarget(player, o);
			for (int i = 0; i < 36; i++) if (player.getInventory().getItem(i).isEmpty()) player.getInventory().setItem(i, new ItemStack(Items.DIRT, 64));
			if (!ErrandService.canHandIn(player, a)) return "not ready for the room check";
			int missing = ErrandService.missingRoom(player, a.errand, 2);
			if (missing <= 0) return "no room needed with a full inventory";
			boolean done = ErrandService.handIn(player, a.errand.id(), 2);
			player.getInventory().clearContent();
			return done ? "handed in with a full inventory" : "ok";
		});
		check(full.equals("ok"), "full inventory: " + full);

		// Ready to hand in: the choice screen comes after Hand in, with the magic shown only there.
		sp.getServer().runCommand("errands finish @p items");
		sp.getServer().runOnServer(server -> {
			ServerPlayer player = player(server);
			player.getInventory().clearContent();
			ErrandsData.Active a = state(server).active.getFirst();
			for (Objective o : a.errand.objectives()) if (o.isDelivery()) giveTarget(player, o);
			net.valcheff.villagersremember.ui.ErrandDialogs.chooseTop(player, a.errand.id());
		});
		waitForDialog(context, null);
		context.takeScreenshot("dlg-12-top-choice");
		press(context, l -> l.contains("Golden Carrot"));
		closeScreen(context);
		String handed = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			if (!state(server).active.isEmpty()) return "not handed in";
			int carrots = 0, hoes = 0;
			for (ItemStack st : player.getInventory().getNonEquipmentItems()) {
				if (st.is(Items.GOLDEN_CARROT)) carrots += st.getCount();
				if (st.is(Items.DIAMOND_HOE)) hoes++;
			}
			if (carrots < 64 || hoes != 0) return "carrots " + carrots + ", hoes " + hoes;
			// A second farmer's Best-friend trial pays currency only: the top reward was given already.
			ErrandsData.PlayerState state = state(server);
			if (!state.flags.contains("top:farmer")) return "top reward not recorded";
			UUID second = new UUID(0xFA4L, 2);
			state.villagerRep.put(second, Reputation.VILLAGER_TIERS[4]);
			state.trialsPassed.put(second, 3);
			Errand again = ErrandGenerator.trial(server.overworld(),
				new ErrandGenerator.Giver(second, "minecraft:farmer", "minecraft:plains", 5, Optional.empty()), state).orElseThrow();
			state.villagerRep.remove(second);
			state.trialsPassed.remove(second);
			return again.item().isEmpty() && again.emeralds() + again.xp() > 0 ? "ok" : "second trial item " + again.item();
		});
		check(handed.equals("ok"), "chosen top reward: " + handed);
	}

	/** A villager zombified and cured again keeps the player's standing, trials and accepted errands. */
	private static void zombieAndCure(TestSingleplayerContext sp, BlockPos base) {
		String result = sp.getServer().computeOnServer(server -> {
			ServerLevel level = server.overworld();
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			v.setVillagerData(v.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.FARMER).withLevel(3));
			v.setPos(Vec3.atCenterOf(base.offset(4, 0, -4)));
			v.setNoAi(true);
			level.addFreshEntity(v);
			UUID first = v.getUUID();
			String name = ErrandText.villagerName(player, first, v);
			state.active.clear();
			state.villagerRep.put(first, 70);
			state.trialsPassed.put(first, 2);
			Errand errand = ErrandGenerator.offers(level, ErrandGenerator.Giver.of(v), state).getFirst();
			if (!ErrandService.accept(player, errand)) return "accept";
			// The trade level command, the vanilla way: new trades come with every level.
			int tradesBefore = v.getOffers().size();
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withPosition(v.position()), "errands villager levelup 2");
			if (v.getVillagerData().level() != 5 || v.getOffers().size() <= tradesBefore) {
				return "levelup: level " + v.getVillagerData().level() + ", trades " + tradesBefore + " -> " + v.getOffers().size();
			}
			var zombie = v.convertTo(net.minecraft.world.entity.EntityTypes.ZOMBIE_VILLAGER,
				net.minecraft.world.entity.ConversionParams.single(v, true, true), z -> { });
			if (zombie == null) return "no zombie";
			var cured = zombie.convertTo(net.minecraft.world.entity.EntityTypes.VILLAGER,
				net.minecraft.world.entity.ConversionParams.single(zombie, false, false), c -> { });
			if (cured == null) return "no cure";
			UUID now = cured.getUUID();
			if (now.equals(first)) return "same id, nothing to check";
			if (state.villagerRep.getOrDefault(now, -1) != 70 || state.villagerRep.containsKey(first)) return "standing " + state.villagerRep.get(now);
			if (Reputation.trialsPassed(state, now) != 2) return "trials " + Reputation.trialsPassed(state, now);
			if (!state.active.getFirst().errand.villager().equals(now)) return "errand still with the old villager";
			String after = ErrandText.villagerName(player, now, (Villager) cured);
			if (!after.equals(name)) return "name changed from " + name + " to " + after;
			return "ok";
		});
		check(result.equals("ok"), "zombie and cure: " + result);
	}

	/** More villages than names: every name of the list once, then they come round again as "Name 2". */
	private static void uniqueNames(TestSingleplayerContext sp) {
		String result = sp.getServer().computeOnServer(server -> {
			var registry = net.valcheff.villagersremember.VillageRegistry.get(server);
			int count = net.valcheff.villagersremember.Lang.villageNameCount();
			for (int i = 1; i <= count + 10; i++) {
				registry.forBell(server, GlobalPos.of(server.overworld().dimension(), new BlockPos(5000 * i, 64, -7000)), true);
			}
			ServerPlayer player = player(server);
			java.util.Set<String> names = new java.util.HashSet<>();
			int numbered = 0;
			for (var v : registry.all()) {
				String name = ErrandText.villageName(player, v.center);
				if (!names.add(name)) return "repeated name " + name;
				if (name.endsWith(" 2")) numbered++;
			}
			return numbered == registry.all().size() - count ? "ok" : "numbered " + numbered + " of " + registry.all().size();
		});
		check(result.equals("ok"), "village names: " + result);
	}

	/** Treasury: the formula, the day cap, the ender chest, and that the village was discovered on the way in. */
	private static void treasury(ClientGameTestContext context, TestSingleplayerContext sp, BlockPos bell) {
		walkTo(context, sp, Vec3.atCenterOf(bell).add(0, -0.5, -2));
		context.getInput().lookAt(bell);
		context.waitTicks(2);
		String result = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			String key = ErrandsData.villageKey(village.center);
			if (!state.discovered.containsKey(key)) return "village not discovered";
			// Standing stored under a bell position by an older version moves to the village's center.
			String old = ErrandsData.villageKey(net.minecraft.core.GlobalPos.of(village.center.dimension(), village.center.pos().offset(3, 0, 3)));
			state.villageRep.put(old, 999);
			net.valcheff.villagersremember.Treasury.normalize(server, state);
			if (state.villageRep.containsKey(old) || state.villageRep.getOrDefault(key, 0) != 999) return "old key not moved: " + state.villageRep;
			if (net.valcheff.villagersremember.Treasury.entries(player, state).size() != 1) return "list has " + net.valcheff.villagersremember.Treasury.entries(player, state).size();
			state.villageRep.remove(key);
			// 40 best friends in a Hero village: about 36 a day (TIERS.md section 6).
			double forty = net.valcheff.villagersremember.Treasury.formula(java.util.Collections.nCopies(40, 1.2), 4);
			if (Math.abs(forty - 35.7) > 0.3) return "40 villagers give " + forty;
			if (Math.abs(net.valcheff.villagersremember.Treasury.networkBonus(2) - 0.10) > 1e-9) return "network bonus for 2";
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "errands rep " + player.getScoreboardName() + " here all tier 4");
			long today = ErrandGenerator.day(server.overworld());
			double daily = net.valcheff.villagersremember.Treasury.perDay(state, village) * 1.05;
			state.treasury.put(key, 0.0);
			state.paidUntil.put(key, today - 10);
			net.valcheff.villagersremember.Treasury.settle(server, state, today);
			double waiting = state.treasury.getOrDefault(key, 0.0);
			if (Math.abs(waiting - daily * 3) > 0.01) return "after 10 days " + waiting + ", expected 3 days of " + daily;
			player.getEnderChestInventory().clearContent();
			int sent = net.valcheff.villagersremember.Treasury.sendToEnderChest(player, state);
			int inChest = player.getEnderChestInventory().countItem(Items.EMERALD);
			if (sent != (int) Math.floor(waiting) || inChest != sent) return "sent " + sent + ", in chest " + inChest + ", waiting was " + waiting;
			for (int i = 0; i < player.getEnderChestInventory().getContainerSize(); i++) {
				player.getEnderChestInventory().setItem(i, new ItemStack(Items.DIRT, 64));
			}
			state.treasury.put(key, 10.0);
			int none = net.valcheff.villagersremember.Treasury.sendToEnderChest(player, state);
			if (none != 0 || Math.abs(state.treasury.get(key) - 10.0) > 1e-9) return "full chest took " + none;
			player.getEnderChestInventory().clearContent();
			return "ok";
		});
		check(result.equals("ok"), "treasury: " + result);
		context.getInput().pressKey(options -> options.keyUse);
		waitForDialog(context, null);
		press(context, label -> label.contains("Treasury ("));
		context.takeScreenshot("dlg-10-treasury");
		press(context, label -> label.contains("Send to Ender Chest"));
		int chest = sp.getServer().computeOnServer(server -> player(server).getEnderChestInventory().countItem(Items.EMERALD));
		check(chest >= 10, "send button put " + chest + " emeralds in the ender chest");
		closeScreen(context);
	}

	/** /errands emeralds off: hand-in pays no emeralds and the treasury stops; /errands xp 2 doubles the experience. */
	private static void rewardSwitches(TestSingleplayerContext sp) {
		String result = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			state.active.clear();
			player.getInventory().clearContent();
			Errand errand = null;
			for (int i = 0; i < 400 && errand == null; i++) {
				for (Errand e : ErrandGenerator.generate(new UUID(47, i), i, "minecraft:farmer", "minecraft:plains", 3, Optional.empty(), 2)) {
					if (e.title().isEmpty() && e.emeralds() > 0 && e.xp() > 0 && e.item().isEmpty()) errand = e;
				}
			}
			if (errand == null) return "no errand with emeralds and experience";
			var source = server.createCommandSourceStack();
			server.getCommands().performPrefixedCommand(source, "errands emeralds off");
			server.getCommands().performPrefixedCommand(source, "errands xp 2");
			try {
				ErrandsConfig cfg = ErrandsConfig.INSTANCE;
				if (cfg.emeraldRewards || cfg.xpMultiplier != 2.0) return "commands did not set " + cfg.emeraldRewards + " x" + cfg.xpMultiplier;
				if (errand.paidEmeralds() != 0 || errand.paidXp() != Math.round(errand.xp() * 2.0)) return "paid " + errand.paidEmeralds() + "/" + errand.paidXp();
				var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition());
				if (village.isPresent() && net.valcheff.villagersremember.Treasury.perDay(state, village.get()) != 0) return "treasury still pays";
				if (!ErrandService.accept(player, errand)) return "accept";
				ErrandsData.Active a = state.find(errand.id());
				for (Objective o : errand.objectives()) {
					if (o.isDelivery()) giveTarget(player, o);
					else a.progress[errand.objectives().indexOf(o)] = o.needed();
				}
				int xpBefore = player.totalExperience;
				int emeraldsBefore = countEmeralds(player);
				if (!ErrandService.handIn(player, errand.id())) return "hand in";
				int gotXp = player.totalExperience - xpBefore;
				int gotEmeralds = countEmeralds(player) - emeraldsBefore;
				if (gotEmeralds != 0) return "got " + gotEmeralds + " emeralds";
				if (gotXp != errand.paidXp()) return "got " + gotXp + " xp, expected " + errand.paidXp();
				return "ok";
			} finally {
				server.getCommands().performPrefixedCommand(source, "errands emeralds on");
				server.getCommands().performPrefixedCommand(source, "errands xp 1");
				player.getInventory().clearContent();
			}
		});
		check(result.equals("ok"), "reward switches: " + result);
		check(ErrandsConfig.INSTANCE.emeraldRewards && ErrandsConfig.INSTANCE.xpMultiplier == 1.0, "reward switches restored");
	}

	/** A player without permissions sees only the commands about their own progress, plus the dialog plumbing. */
	private static void commandPermissions(TestSingleplayerContext sp) {
		String result = sp.getServer().computeOnServer(server -> {
			var plain = player(server).createCommandSourceStack().withPermission(net.minecraft.server.permissions.PermissionSet.NO_PERMISSIONS);
			var gamemaster = player(server).createCommandSourceStack().withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER);
			var root = server.getCommands().getDispatcher().getRoot().getChild("errands");
			if (root == null || !root.canUse(plain)) return "/errands not open to players";
			java.util.Set<String> open = new java.util.TreeSet<>();
			for (var child : root.getChildren()) {
				if (child.canUse(plain)) open.add(child.getName());
				else if (child.canUse(gamemaster)) return child.getName() + " open to level 2";
			}
			if (!open.equals(new java.util.TreeSet<>(List.of("book", "click", "input", "track", "village")))) return "open to players: " + open;
			if (root.getChild("village").getChild("name").canUse(plain)) return "village name open to players";
			return "ok";
		});
		check(result.equals("ok"), "command permissions: " + result);
	}

	/** A villager offers only the pack of the player's tier with it: T1 for a stranger, T2 once Acquaintance. */
	/** A big order is brought in parts: what is given stays with the villager, hand-in takes only the rest. */
	private static void partialDelivery(TestSingleplayerContext sp) {
		String result = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			state.active.clear();
			player.getInventory().clearContent();
			Errand order = null;
			for (int i = 0; i < 400 && order == null; i++) {
				for (Errand e : ErrandGenerator.generate(new UUID(46, i), i, "minecraft:farmer", "minecraft:plains", 5, Optional.empty(), 3)) {
					if (e.title().isPresent()) order = e;
				}
			}
			if (order == null || !ErrandService.accept(player, order)) return "no order";
			ErrandsData.Active a = state.find(order.id());
			for (Objective o : order.objectives()) if (!o.isDelivery()) a.progress[order.objectives().indexOf(o)] = o.needed();
			Objective first = order.objectives().stream().filter(Objective::isDelivery).findFirst().orElseThrow();
			int half = Math.max(1, first.count() / 2);
			ItemStack sample = ErrandText.icon(first).copyWithCount(half);
			player.getInventory().add(sample);
			int given = ErrandService.givePart(player, order.id());
			if (given != half) return "gave " + given + " of " + half;
			if (ErrandService.countItems(player, first) != 0) return "items left after giving a part";
			if (ErrandService.canHandIn(player, a)) return "ready after half";
			int index = order.objectives().indexOf(first);
			if (ErrandService.progress(player, a, index) != half) return "progress " + ErrandService.progress(player, a, index);
			// The rest of everything, plus extra: hand-in takes only what is still missing.
			for (Objective o : order.objectives()) if (o.isDelivery()) giveTarget(player, o);
			if (!ErrandService.canHandIn(player, a)) return "not ready with the rest";
			if (!ErrandService.handIn(player, order.id())) return "hand in";
			int left = ErrandService.countItems(player, first);
			player.getInventory().clearContent();
			return left == half ? "ok" : "left " + left + ", expected " + half;
		});
		check(result.equals("ok"), "partial delivery: " + result);
	}

	private static void packs(TestSingleplayerContext sp, List<UUID> villagers) {
		String result = sp.getServer().computeOnServer(server -> {
			ErrandsData.PlayerState state = state(server);
			UUID id = villagers.get(3);
			state.villagerRep.remove(id);
			ErrandGenerator.Giver giver = ErrandGenerator.Giver.of(villager(server, id));
			List<Errand> t1 = ErrandGenerator.offers(server.overworld(), giver, state);
			if (t1.isEmpty() || t1.stream().anyMatch(e -> e.tier() != 1)) return "stranger offers " + t1.stream().map(Errand::tier).toList();
			state.villagerRep.put(id, Reputation.VILLAGER_TIERS[1]);
			state.trialsPassed.put(id, 1);
			List<Errand> t2 = ErrandGenerator.offers(server.overworld(), giver, state);
			if (t2.isEmpty() || t2.stream().anyMatch(e -> e.tier() != 2)) return "acquaintance offers " + t2.stream().map(Errand::tier).toList();
			if (t1.stream().anyMatch(a -> t2.stream().anyMatch(b -> b.id().equals(a.id())))) return "T1 and T2 share ids";
			// Every pack of every profession has errands to give.
			for (String p : List.of("farmer", "fisherman", "shepherd", "librarian", "cartographer", "cleric", "armorer", "weaponsmith",
				"toolsmith", "butcher", "leatherworker", "mason", "fletcher")) {
				for (int tier = 1; tier <= 5; tier++) {
					List<Errand> pack = ErrandGenerator.generate(new UUID(7, tier), 0, "minecraft:" + p, "minecraft:plains", 5, Optional.empty(), tier);
					if (pack.size() < 3) return p + " T" + tier + " has " + pack.size() + " errands";
					double[] window = {0, 4, 8, 12, 18, 25, 35};
					for (Errand e : pack) {
						boolean reforge = e.item().map(r -> r.kind() == net.valcheff.villagersremember.ErrandPools.RewardKind.ENCHANT_MAX).orElse(false);
						if (e.item().isPresent() && !reforge) return p + " T" + tier + " daily with an item: " + e.item().get();
						if (reforge) continue;
						if (e.value() < window[tier] * 0.8 || e.value() > window[tier + 1] * 1.25) return p + " T" + tier + " " + e.id() + " takes " + e.value() + " min";
						// Never less than selling the goods to a villager (x1.5).
						java.util.Map<String, Double> perEmerald = java.util.Map.of("minecraft:carrot", 22.0, "minecraft:wheat", 20.0,
							"minecraft:gold_ingot", 3.0, "minecraft:iron_ingot", 4.0, "minecraft:diamond", 1.0, "minecraft:rotten_flesh", 32.0);
						for (Objective o : e.objectives()) {
							Double per = o.isDelivery() ? perEmerald.get(o.target()) : null;
							if (per != null && e.emeralds() < Math.ceil(o.count() / per * 1.5) - 1e-9) {
								return p + " T" + tier + " " + o.count() + " " + o.target() + " pays " + e.emeralds() + " emeralds";
							}
						}
					}
				}
			}
			state.villagerRep.remove(id);
			state.trialsPassed.remove(id);
			// A reforge done once is not offered again.
			UUID smith = new UUID(0x5717L, 1);
			var smithGiver = new ErrandGenerator.Giver(smith, "minecraft:weaponsmith", "minecraft:plains", 5, Optional.empty());
			state.villagerRep.put(smith, Reputation.VILLAGER_TIERS[2]);
			state.trialsPassed.put(smith, 2);
			Errand sword = null;
			for (int d = 0; d < 200 && sword == null; d++) {
				for (Errand e : ErrandGenerator.generate(smith, d, "minecraft:weaponsmith", "minecraft:plains", 5, Optional.empty(), 3)) {
					if (e.main().target().equals("minecraft:iron_sword") && e.item().isPresent()) sword = e;
				}
			}
			if (sword == null) return "no iron sword reforge";
			state.collection.computeIfAbsent("weaponsmith", k -> new java.util.LinkedHashSet<>()).add("reforge.iron_sword");
			boolean again = ErrandGenerator.reforgeDone(state, sword);
			state.villagerRep.remove(smith);
			state.trialsPassed.remove(smith);
			if (!again) return "a done reforge is still offered";
			return "ok";
		});
		check(result.equals("ok"), "packs: " + result);
	}

	/** Trust trial: points stop at the threshold, the villager offers the trial, handing it in unlocks the next pack. */
	private static void trials(ClientGameTestContext context, TestSingleplayerContext sp, List<UUID> villagers) {
		UUID id = villagers.get(4);
		String result = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			state.active.clear();
			state.villagerRep.remove(id);
			state.trialsPassed.remove(id);
			Villager v = villager(server, id);
			Reputation.addVillager(state, player, id, v, 30);
			if (Reputation.villager(state, id) != Reputation.VILLAGER_TIERS[1]) return "points not held at the threshold: " + Reputation.villager(state, id);
			if (Reputation.villagerTier(state, id) != 0 || Reputation.trialPending(state, id) != 1) return "tier before the trial";
			ErrandGenerator.Giver giver = ErrandGenerator.Giver.of(v);
			List<Errand> shown = ErrandGenerator.shown(server.overworld(), giver, state);
			Errand trial = shown.getFirst();
			if (!ErrandGenerator.isTrial(trial) || trial.tier() != 2 || trial.item().isEmpty()) return "trial offered: " + trial.id() + " tier " + trial.tier();
			if (!ErrandText.task(player, trial).getString().equals("Trial of trust: Acquaintance")) return "trial name " + ErrandText.task(player, trial).getString();
			return "ok";
		});
		check(result.equals("ok"), "trials: " + result);
		openVillager(context, sp, id);
		context.takeScreenshot("dlg-11-trial-offered");
		press(context, label -> label.contains("Trial of trust"));
		context.takeScreenshot("dlg-11b-trial-detail");
		press(context, label -> label.equals("Accept"));
		closeScreen(context);
		check(sp.getServer().computeOnServer(server -> state(server).active.stream().anyMatch(a -> ErrandGenerator.isTrial(a.errand))), "trial accepted");
		sp.getServer().runCommand("errands finish @p items");
		result = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			ErrandsData.PlayerState state = state(server);
			Errand trial = state.active.getFirst().errand;
			if (!ErrandService.handIn(player, trial.id())) return "trial hand in";
			if (Reputation.trialsPassed(state, id) != 1 || Reputation.villagerTier(state, id) != 1) return "after the trial tier " + Reputation.villagerTier(state, id);
			if (Reputation.villager(state, id) <= Reputation.VILLAGER_TIERS[1]) return "points did not carry on: " + Reputation.villager(state, id);
			ErrandGenerator.Giver giver = ErrandGenerator.Giver.of(villager(server, id));
			List<Errand> shown = ErrandGenerator.shown(server.overworld(), giver, state);
			if (shown.stream().anyMatch(ErrandGenerator::isTrial) || shown.stream().anyMatch(e -> e.tier() != 2)) return "after the trial offers " + shown.stream().map(Errand::id).toList();
			return "ok";
		});
		check(result.equals("ok"), "trials: " + result);
	}

	/** The operator commands, run as the server would, checked against the stored standing. */
	private static void adminCommands(ClientGameTestContext context, TestSingleplayerContext sp, List<UUID> villagers) {
		// Two villagers with the same name: they are told apart by their trade.
		sp.getServer().runOnServer(server -> {
			villager(server, villagers.get(0)).setCustomName(net.minecraft.network.chat.Component.literal("Twin"));
			villager(server, villagers.get(1)).setCustomName(net.minecraft.network.chat.Component.literal("Twin"));
		});
		String villageArg = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			return com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(ErrandText.villageName(player, village.center));
		});
		String twin = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			var names = net.valcheff.villagersremember.AdminCommands.residentNames(server, player, village);
			for (var e : names.entrySet()) if (e.getValue().id.equals(villagers.get(1))) return e.getKey();
			return "";
		});
		check(twin.startsWith("Twin."), "twin name " + twin);

		sp.getServer().runCommand("errands rep @p " + villageArg + " villager " + twin + " set 77");
		check(sp.getServer().computeOnServer(server -> state(server).villagerRep.getOrDefault(villagers.get(1), -1)) == 77, "rep villager set");
		sp.getServer().runCommand("errands rep @p " + villageArg + " villager " + twin + " tier 2");
		check(sp.getServer().computeOnServer(server -> state(server).villagerRep.getOrDefault(villagers.get(1), -1)) == 60, "rep villager tier");
		sp.getServer().runCommand("errands rep @p " + villageArg + " villager " + twin + " reset");
		check(sp.getServer().computeOnServer(server -> !state(server).villagerRep.containsKey(villagers.get(1))), "rep villager reset");

		sp.getServer().runCommand("errands rep @p here village set 100");
		check(sp.getServer().computeOnServer(server -> state(server).villageRep.containsValue(100)), "rep village set");
		sp.getServer().runCommand("errands rep @p 1 all tier 4");
		check(sp.getServer().computeOnServer(server -> state(server).villagerRep.getOrDefault(villagers.get(2), -1)) == 280, "rep all tier");
		sp.getServer().runCommand("errands rep @p here show");
		sp.getServer().runCommand("errands villages");
		sp.getServer().runCommand("errands rep @p here all reset");
		check(sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			return Reputation.village(state(server), Optional.of(village.center)) == 0
				&& villagers.stream().noneMatch(id -> state(server).villagerRep.containsKey(id));
		}), "rep all reset");

		// give: a chosen errand from a chosen villager, accepted right away.
		String smith = sp.getServer().computeOnServer(server -> {
			ServerPlayer player = player(server);
			var village = net.valcheff.villagersremember.VillageRegistry.get(server).villageAt(player.level().dimension(), player.blockPosition()).orElseThrow();
			for (var e : net.valcheff.villagersremember.AdminCommands.residentNames(server, player, village).entrySet()) {
				if (e.getValue().profession.equals("minecraft:weaponsmith")) return e.getKey();
			}
			return "";
		});
		sp.getServer().runOnServer(server -> state(server).active.clear());
		sp.getServer().runCommand("errands give @p " + villageArg + " " + com.mojang.brigadier.arguments.StringArgumentType.escapeIfRequired(smith)
			+ " reforge.iron_sword");
		check(sp.getServer().computeOnServer(server -> state(server).active.stream().anyMatch(a ->
			a.errand.item().map(i -> i.id().equals("minecraft:iron_sword")).orElse(false) && a.errand.tier() == 3)), "give reforge.iron_sword");
		check(sp.getServer().computeOnServer(server -> ErrandGenerator.taskNames("minecraft:weaponsmith").containsKey("reforge.iron_sword")
			&& ErrandGenerator.taskNames("minecraft:farmer").containsKey("order.winter_stock")), "errand names");

		// levelup by name, as the player typing it sees the name.
		sp.getServer().runOnServer(server -> {
			var v = villager(server, villagers.get(1));
			v.setVillagerData(v.getVillagerData().withLevel(1));
		});
		sp.getServer().runCommand("execute as @p run errands villager " + villageArg + " " + twin + " levelup 2");
		check(sp.getServer().computeOnServer(server -> villager(server, villagers.get(1)).getVillagerData().level()) == 3, "levelup by name");

		// finish: counters full, and with items the errand can be handed in.
		Errand any = sp.getServer().computeOnServer(server -> findAvailable(server, villagers));
		sp.getServer().runOnServer(server -> ErrandService.accept(player(server), any));
		sp.getServer().runCommand("errands finish @p items");
		check(sp.getServer().computeOnServer(server -> ErrandService.canHandIn(player(server), state(server).find(any.id()))), "finish items");
	}

	/** The list buttons of the open dialog: labels of one width, so names and bars line up. */
	private static void checkButtonColumns(ClientGameTestContext context) {
		String result = context.computeOnClient(mc -> {
			if (!(mc.gui.screen() instanceof DialogScreen<?> screen)) return "no dialog";
			List<Integer> widths = new ArrayList<>();
			int limit = 0;
			for (ActionButton b : buttons(dialog(screen))) {
				if (b.button().label().getString().equals("Back")) continue;
				widths.add(mc.font.width(b.button().label()));
				limit = b.button().width();
			}
			if (widths.size() < 2) return "too few buttons " + widths;
			if (widths.stream().distinct().count() != 1) return "widths differ " + widths;
			return widths.getFirst() <= limit - 8 ? "ok" : "label wider than the button " + widths.getFirst() + " > " + limit;
		});
		check(result.equals("ok"), "resident columns: " + result);
	}

	private static Dialog dialog(DialogScreen<?> screen) {
		try {
			for (Class<?> c = screen.getClass(); c != null; c = c.getSuperclass()) {
				for (Field f : c.getDeclaredFields()) {
					if (Dialog.class.isAssignableFrom(f.getType())) {
						f.setAccessible(true);
						return (Dialog) f.get(screen);
					}
				}
			}
		} catch (IllegalAccessException e) {
			throw new AssertionError(e);
		}
		throw new AssertionError("dialog field not found");
	}

	private static List<ActionButton> buttons(Dialog dialog) {
		List<ActionButton> out = new ArrayList<>();
		if (dialog instanceof MultiActionDialog m) {
			out.addAll(m.actions());
			m.exitAction().ifPresent(out::add);
		}
		return out;
	}

	// ---- setup ----

	private static BlockPos setUpVillage(MinecraftServer server) {
		ServerLevel level = server.overworld();
		ServerPlayer player = player(server);
		BlockPos base = player.blockPosition();
		for (int x = -10; x <= 10; x++) {
			for (int z = -10; z <= 10; z++) {
				level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.STONE_BRICKS.defaultBlockState());
				for (int y = 0; y < 5; y++) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState());
			}
		}
		BlockPos bell = base.offset(0, 0, 8);
		level.setBlockAndUpdate(bell.below(), Blocks.OAK_PLANKS.defaultBlockState());
		level.setBlockAndUpdate(bell, Blocks.BELL.defaultBlockState());
		player.teleportTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
		GlobalPos meeting = GlobalPos.of(level.dimension(), bell);
		for (int i = 0; i < PROFESSIONS.size(); i++) {
			Villager v = EntityTypes.VILLAGER.create(level, EntitySpawnReason.COMMAND);
			v.setVillagerData(v.getVillagerData()
				.withProfession(level.registryAccess(), PROFESSIONS.get(i))
				.withType(level.registryAccess(), ResourceKey.create(Registries.VILLAGER_TYPE, Identifier.withDefaultNamespace(TYPES[i % TYPES.length])))
				.withLevel(1 + i % 5));
			double angle = Math.PI * 2 * i / PROFESSIONS.size();
			v.snapTo(base.getX() + 0.5 + Math.cos(angle) * 5, base.getY(), base.getZ() + 0.5 + Math.sin(angle) * 5, 0, 0);
			v.setNoAi(true);
			v.getBrain().setMemory(MemoryModuleType.MEETING_POINT, meeting);
			// Fixed ids, so the same errands come up in every run.
			v.setUUID(new UUID(0x7E57L, i));
			level.addFreshEntity(v);
		}
		return base;
	}

	private static List<UUID> villagerIds(MinecraftServer server, BlockPos base) {
		return server.overworld().getEntitiesOfClass(Villager.class, new AABB(base).inflate(16)).stream().map(Villager::getUUID).toList();
	}

	private static Errand findErrand(MinecraftServer server, List<UUID> villagers, java.util.function.Predicate<Errand> wanted) {
		for (UUID id : villagers) {
			Villager v = villager(server, id);
			List<Errand> offers = ErrandGenerator.offers(server.overworld(), ErrandGenerator.Giver.of(v), state(server));
			int n = Math.min(offers.size(), ErrandGenerator.slotsFor(v, false));
			for (int i = 0; i < n; i++) if (wanted.test(offers.get(i))) return offers.get(i);
		}
		return null;
	}

	/** Crafting as the game reports it: the result taken from the player's own 2x2 grid. */
	private static void craft(MinecraftServer server, Objective o, int count) {
		ServerPlayer player = player(server);
		ItemStack result = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(o.target())), count);
		result.onCraftedBy(player, count);
	}

	/** Smelting as the game reports it: the result taken out of a furnace the player has open. */
	private static void smeltInFurnace(MinecraftServer server, BlockPos base, Objective o) {
		ServerLevel level = server.overworld();
		ServerPlayer player = player(server);
		BlockPos pos = base.offset(-3, 0, -3);
		level.setBlockAndUpdate(pos, Blocks.FURNACE.defaultBlockState());
		if (level.getBlockEntity(pos) instanceof net.minecraft.world.MenuProvider furnace) player.openMenu(furnace);
		new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(o.target())), o.count()).onCraftedBy(player, o.count());
		player.closeContainer();
		level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
	}

	/** Breaking fully grown crops with the player's game mode, which fires the block-break event. */
	private static void harvestCrops(MinecraftServer server, BlockPos base, Objective o) {
		ServerLevel level = server.overworld();
		ServerPlayer player = player(server);
		var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(o.target()));
		BlockPos pos = base.offset(3, 0, -3);
		level.setBlockAndUpdate(pos.below(), Blocks.FARMLAND.defaultBlockState());
		for (int i = 0; i < o.count(); i++) {
			var state = block instanceof net.minecraft.world.level.block.CropBlock crop ? crop.getStateForAge(crop.getMaxAge()) : block.defaultBlockState();
			level.setBlockAndUpdate(pos, state);
			player.gameMode.destroyBlock(pos);
		}
	}

	private static Errand findAvailable(MinecraftServer server, List<UUID> villagers) {
		for (UUID id : villagers) {
			Villager v = villager(server, id);
			List<Errand> offers = ErrandGenerator.offers(server.overworld(), ErrandGenerator.Giver.of(v), state(server));
			int n = Math.min(offers.size(), ErrandGenerator.slotsFor(v, false));
			for (int i = 0; i < n; i++) {
				if (ErrandService.status(player(server), offers.get(i)) == ErrandService.Status.AVAILABLE) return offers.get(i);
			}
		}
		throw new AssertionError("no available errand left");
	}

	private static void giveTarget(ServerPlayer player, Objective o) {
		var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(o.target()));
		int left = o.count();
		while (left > 0) {
			int n = Math.min(left, item.getDefaultMaxStackSize());
			player.getInventory().add(new ItemStack(item, n));
			left -= n;
		}
	}

	private static void killTargets(MinecraftServer server, Errand errand) {
		ServerLevel level = server.overworld();
		ServerPlayer player = player(server);
		var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(errand.main().target()));
		for (int i = 0; i < errand.main().count(); i++) {
			var entity = type.create(level, EntitySpawnReason.COMMAND);
			if (!(entity instanceof LivingEntity living)) throw new AssertionError("not living: " + errand.main().target());
			living.snapTo(player.getX() + 3, player.getY(), player.getZ() - 3, 0, 0);
			if (living instanceof Mob mob) mob.setNoAi(true);
			level.addFreshEntity(living);
			living.hurtServer(level, player.damageSources().playerAttack(player), 10000f);
		}
	}

	private static int countEmeralds(ServerPlayer player) {
		int n = 0;
		for (ItemStack s : player.getInventory().getNonEquipmentItems()) if (s.is(Items.EMERALD)) n += s.getCount();
		return n;
	}

	private static ServerPlayer player(MinecraftServer server) {
		return server.getPlayerList().getPlayers().getFirst();
	}

	private static Villager villager(MinecraftServer server, UUID id) {
		return (Villager) server.overworld().getEntity(id);
	}

	private static ErrandsData.PlayerState state(MinecraftServer server) {
		return ErrandsData.get(server).player(player(server).getUUID());
	}

	private static List<ErrandsData.Active> active(MinecraftServer server) {
		return state(server).active;
	}

	/** Writes every generated errand for many villagers to errand-balance.csv for review. */
	private static void dumpBalance(TestSingleplayerContext sp) {
		String csv = sp.getServer().computeOnServer(server -> {
			StringBuilder out = new StringBuilder("profession,tier,objectives,target,count,value,emeralds,xp,item_kind,item,item_amount,rep\n");
			String[] profs = {"farmer", "fisherman", "shepherd", "librarian", "cartographer", "cleric", "armorer", "weaponsmith",
				"toolsmith", "butcher", "leatherworker", "mason", "fletcher", "nitwit"};
			for (String p : profs) {
				for (int tier = 1; tier <= 5; tier++) {
					for (int i = 0; i < 60; i++) {
						UUID id = new UUID(p.hashCode() * 7919L + tier, i * 104729L + 17);
						List<Errand> offers = ErrandGenerator.generate(id, i % 7, "minecraft:" + p, "minecraft:plains", 5, Optional.empty(), tier);
						for (Errand e : offers.subList(0, Math.min(3, offers.size()))) {
							out.append(p).append(',').append(tier).append(',').append(e.objectives().stream().map(o -> o.type().key()).collect(java.util.stream.Collectors.joining("+"))).append(',')
								.append(e.main().target()).append(',').append(e.main().count()).append(',').append(String.format(java.util.Locale.ROOT, "%.2f", e.value())).append(',')
								.append(e.emeralds()).append(',').append(e.xp()).append(',')
								.append(e.item().map(x -> x.kind().name()).orElse("")).append(',')
								.append(e.item().map(Errand.ItemReward::id).orElse("")).append(',')
								.append(e.item().map(x -> String.valueOf(x.amount())).orElse("")).append(',')
								.append(e.reputation()).append('\n');
						}
					}
				}
			}
			return out.toString();
		});
		try {
			Files.writeString(Path.of("errand-balance.csv"), csv);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new AssertionError(message);
	}
}
