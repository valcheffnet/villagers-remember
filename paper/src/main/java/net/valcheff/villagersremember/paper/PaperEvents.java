package net.valcheff.villagersremember.paper;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import io.papermc.paper.event.entity.EntityFertilizeEggEvent;
import io.papermc.paper.event.player.PlayerTradeEvent;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.BrewingStandMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.valcheff.villagersremember.ActionHooks;
import net.valcheff.villagersremember.Actions;
import net.valcheff.villagersremember.Interactions;
import net.valcheff.villagersremember.Maps;
import net.valcheff.villagersremember.Objective;
import net.valcheff.villagersremember.Prices;
import net.valcheff.villagersremember.Tracking;
import net.valcheff.villagersremember.Trust;
import net.valcheff.villagersremember.VillagersRemember;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.damage.CraftDamageSource;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftItem;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.craftbukkit.entity.CraftVillager;
import org.bukkit.craftbukkit.inventory.CraftInventoryView;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.craftbukkit.util.CraftMagicNumbers;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.MerchantInventory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Paper's events in place of the loader events and the mixins of the Fabric and NeoForge builds. Most mixins listen
 * to an advancement trigger; each handler here listens to the Paper event fired where that trigger is.
 */
final class PaperEvents implements Listener {
	private static final Map<Class<?>, Boolean> USABLE = new HashMap<>();

	private final MinecraftServer server;
	private final TradePrices prices = new TradePrices();
	/** The entity each player last used an item on this tick: a milk bucket's event does not name the animal. */
	private final Map<UUID, Entity> usedOn = new HashMap<>();
	/** Villagers traded with this tick and their level before, to see at the end of the tick whether they went up. */
	private final List<Traded> traded = new ArrayList<>();

	private record Traded(ServerPlayer player, Villager villager, int levelBefore) {
	}

	PaperEvents(MinecraftServer server) {
		this.server = server;
	}

	private static ServerPlayer player(Player player) {
		return ((CraftPlayer) player).getHandle();
	}

	private static InteractionHand hand(EquipmentSlot slot) {
		return slot == EquipmentSlot.OFF_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
	}

	private static BlockPos pos(Block block) {
		return new BlockPos(block.getX(), block.getY(), block.getZ());
	}

	// ---- server and players ----

	@EventHandler
	public void tickEnd(ServerTickEndEvent event) {
		VillagersRemember.endTick(server);
		for (Traded t : traded) {
			// A villager goes up a level inside the trade that gave it the experience.
			if (t.villager.isAlive() && t.villager.getVillagerData().level() > t.levelBefore) Trust.tradedUp(t.player, t.villager);
		}
		traded.clear();
		usedOn.clear();
		prices.tick();
	}

	@EventHandler
	public void reloaded(ServerResourcesReloadedEvent event) {
		VillagersRemember.dataReloaded(server);
	}

	@EventHandler
	public void joined(PlayerJoinEvent event) {
		VillagersRemember.playerJoined(player(event.getPlayer()));
	}

	@EventHandler
	public void left(PlayerQuitEvent event) {
		VillagersRemember.playerLeft(player(event.getPlayer()));
	}

	// ---- right-clicks ----

	@EventHandler(ignoreCancelled = true)
	public void useEntity(PlayerInteractEntityEvent event) {
		ServerPlayer player = player(event.getPlayer());
		Entity target = ((CraftEntity) event.getRightClicked()).getHandle();
		// Before vanilla works out this player's prices, if this click ends up trading.
		if (target instanceof Villager villager) Prices.beforeVanilla(villager, player);
		InteractionResult result = Interactions.onUseEntity(player, player.level(), hand(event.getHand()), target, null);
		if (result != InteractionResult.PASS) event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void usedEntity(PlayerInteractEntityEvent event) {
		usedOn.put(event.getPlayer().getUniqueId(), ((CraftEntity) event.getRightClicked()).getHandle());
	}

	/**
	 * A right-click on a block or in the air. Paper fires one event per click (the follow-up item use of a click on a
	 * block reuses its result), so a folded chart also unfolds here when aimed at a block that does nothing.
	 */
	@EventHandler
	public void use(PlayerInteractEvent event) {
		if (event.getHand() == null) return;
		ServerPlayer player = player(event.getPlayer());
		InteractionHand hand = hand(event.getHand());
		Block block = event.getClickedBlock();
		if (event.getAction() == Action.RIGHT_CLICK_BLOCK && block != null) {
			BlockPos pos = pos(block);
			ActionHooks.beforeUse(player, pos);
			if (event.useInteractedBlock() == Event.Result.DENY) return;
			BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), CraftBlock.blockFaceToNotch(event.getBlockFace()), pos, false);
			if (Interactions.onUseBlock(player, player.level(), hand, hit) != InteractionResult.PASS) {
				deny(event);
				return;
			}
			if (!player.isShiftKeyDown() && usable(player.level().getBlockState(pos).getBlock().getClass())) return;
		} else if (event.getAction() != Action.RIGHT_CLICK_AIR) {
			return;
		}
		if (event.useItemInHand() != Event.Result.DENY && Maps.use(player, hand) != InteractionResult.PASS) deny(event);
	}

	/** Blocks that do something when right-clicked (chests, doors, levers) have their own {@code useWithoutItem}. */
	private static boolean usable(Class<?> block) {
		return USABLE.computeIfAbsent(block, c -> {
			for (Class<?> k = c; k != null && k != BlockBehaviour.class; k = k.getSuperclass()) {
				for (Method m : k.getDeclaredMethods()) {
					if (m.getName().equals("useWithoutItem")) return true;
				}
			}
			return false;
		});
	}

	private static void deny(PlayerInteractEvent event) {
		event.setUseInteractedBlock(Event.Result.DENY);
		event.setUseItemInHand(Event.Result.DENY);
	}

	// ---- breaking and harvesting ----

	/** A player broke a block and its drops are known: what {@code DestroyBlockMixin} and {@code BlockDropsMixin} see. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void broken(BlockDropItemEvent event) {
		ServerPlayer player = player(event.getPlayer());
		BlockPos pos = pos(event.getBlock());
		ActionHooks.breaking(((CraftBlockState) event.getBlockState()).getHandle());
		for (Item item : event.getItems()) {
			ActionHooks.dropped(player.level(), pos, ((CraftItem) item).getHandle().getItem());
		}
		ActionHooks.broken(player, pos, true);
	}

	/** Items dropped while a right-click is handled (a crop picked by a harvest plugin); ignored at other times. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void dropped(ItemSpawnEvent event) {
		ItemEntity item = ((CraftItem) event.getEntity()).getHandle();
		ActionHooks.dropped(item.level(), item.blockPosition(), item.getItem());
	}

	// ---- making things ----

	/** {@code ItemStack.onCraftedBy} adds to the crafted statistic: crafting, smelting and the work stations. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void crafted(PlayerStatisticIncrementEvent event) {
		if (event.getStatistic() != Statistic.CRAFT_ITEM || event.getMaterial() == null) return;
		int amount = event.getNewValue() - event.getPreviousValue();
		ItemStack stack = new ItemStack(CraftMagicNumbers.getItem(event.getMaterial()));
		ActionHooks.crafted(player(event.getPlayer()), stack, amount);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void enchanted(EnchantItemEvent event) {
		ItemStack item = CraftItemStack.asNMSCopy(event.getItem());
		// The event comes before a book turns into an enchanted book; the trigger sees the result.
		if (item.is(Items.BOOK)) item = item.transmuteCopy(Items.ENCHANTED_BOOK);
		ItemStack enchanted = item;
		int levels = event.whichButton() + 1;
		Actions.record(player(event.getEnchanter()), Objective.Type.ENCHANT, 1,
			o -> levels >= o.minLevel() && (o.target().isEmpty() || o.matchesItem(enchanted)));
	}

	/**
	 * Slots with no Paper event of their own get a wrapper that sees the take: a brewing stand's three bottle slots and a
	 * stonecutter's result slot. A villager's trading screen gets our prices.
	 */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void opened(InventoryOpenEvent event) {
		if (!(event.getPlayer() instanceof CraftPlayer bukkit)) return;
		AbstractContainerMenu menu = ((CraftInventoryView<?, ?>) event.getView()).getHandle();
		if (menu instanceof BrewingStandMenu) {
			for (int i = 0; i < 3; i++) {
				Slot slot = menu.slots.get(i);
				if (!(slot instanceof PotionTake)) menu.slots.set(i, new PotionTake(slot));
			}
		} else if (menu instanceof StonecutterMenu) {
			Slot slot = menu.slots.get(StonecutterMenu.RESULT_SLOT);
			if (!(slot instanceof StonecutterTake)) menu.slots.set(StonecutterMenu.RESULT_SLOT, new StonecutterTake(slot, bukkit.getHandle()));
		} else if (menu instanceof MerchantMenu merchant
			&& event.getInventory() instanceof MerchantInventory inventory
			&& inventory.getMerchant() instanceof CraftVillager villager) {
			prices.opened(bukkit.getHandle(), merchant, villager.getHandle());
		}
	}

	// ---- placing, planting and bone meal ----

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void placed(BlockPlaceEvent event) {
		ItemStack used = CraftItemStack.asNMSCopy(event.getItemInHand());
		Actions.record(player(event.getPlayer()), Objective.Type.PLANT, 1, o -> o.matchesItem(used));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void fertilized(BlockFertilizeEvent event) {
		if (event.getPlayer() == null) return;
		Actions.record(player(event.getPlayer()), Objective.Type.BONEMEAL, 1, o -> true);
	}

	// ---- animals ----

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void bred(EntityBreedEvent event) {
		if (!(event.getBreeder() instanceof CraftPlayer breeder)) return;
		var type = ((CraftEntity) event.getMother()).getHandle().getType();
		Actions.record(breeder.getHandle(), Objective.Type.BREED, 1, o -> o.matchesEntity(type));
	}

	/** Turtles, frogs and sniffers lay eggs instead of having young. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void fertilizedEgg(EntityFertilizeEggEvent event) {
		if (event.getBreeder() == null) return;
		var type = ((CraftEntity) event.getMother()).getHandle().getType();
		Actions.record(player(event.getBreeder()), Objective.Type.BREED, 1, o -> o.matchesEntity(type));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void tamed(EntityTameEvent event) {
		if (!(event.getOwner() instanceof CraftPlayer owner)) return;
		var type = ((CraftEntity) event.getEntity()).getHandle().getType();
		Actions.record(owner.getHandle(), Objective.Type.TAME, 1, o -> o.matchesEntity(type));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void sheared(PlayerShearEntityEvent event) {
		if (!CraftItemStack.asNMSCopy(event.getItem()).is(Items.SHEARS)) return;
		var type = ((CraftEntity) event.getEntity()).getHandle().getType();
		Actions.record(player(event.getPlayer()), Objective.Type.SHEAR, 1, o -> o.matchesEntity(type));
	}

	/** A bucket of water, lava or powder snow; milk counts for the animal milked. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void filled(PlayerBucketFillEvent event) {
		ServerPlayer player = player(event.getPlayer());
		ItemStack bucket = CraftItemStack.asNMSCopy(event.getItemStack());
		if (bucket.is(Items.MILK_BUCKET)) {
			Entity animal = usedOn.get(player.getUUID());
			if (animal != null) Actions.record(player, Objective.Type.MILK, 1, o -> o.matchesEntity(animal.getType()));
			return;
		}
		Actions.record(player, Objective.Type.FILL_BUCKET, 1, o -> o.matchesItem(bucket));
	}

	/** A fish, axolotl or tadpole caught in a bucket. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void bucketed(PlayerBucketEntityEvent event) {
		ItemStack bucket = CraftItemStack.asNMSCopy(event.getEntityBucket());
		Actions.record(player(event.getPlayer()), Objective.Type.FILL_BUCKET, 1, o -> o.matchesItem(bucket));
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void fished(PlayerFishEvent event) {
		if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof CraftItem item)) return;
		ItemStack stack = item.getHandle().getItem();
		Actions.record(player(event.getPlayer()), Objective.Type.CATCH, stack.getCount(), o -> o.matchesItem(stack));
	}

	// ---- golems ----

	/** A golem or the Wither built by hand counts for every player close by, as vanilla's advancement does. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void summoned(CreatureSpawnEvent event) {
		double range = switch (event.getSpawnReason()) {
			case BUILD_IRONGOLEM, BUILD_SNOWMAN, BUILD_COPPERGOLEM -> 5.0;
			case BUILD_WITHER -> 50.0;
			default -> 0;
		};
		if (range == 0) return;
		Entity summoned = ((CraftEntity) event.getEntity()).getHandle();
		for (ServerPlayer player : summoned.level().getEntitiesOfClass(ServerPlayer.class, summoned.getBoundingBox().inflate(range))) {
			Actions.record(player, Objective.Type.SUMMON, 1, o -> o.matchesEntity(summoned.getType()));
		}
	}

	// ---- villagers ----

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void traded(PlayerTradeEvent event) {
		ServerPlayer player = player(event.getPlayer());
		ItemStack bought = CraftItemStack.asNMSCopy(event.getTrade().getResult());
		Actions.record(player, Objective.Type.TRADE, 1, o -> o.matchesItem(bought));
		if (((CraftEntity) event.getVillager()).getHandle() instanceof Villager villager) {
			traded.add(new Traded(player, villager, villager.getVillagerData().level()));
		}
	}

	/** Zombie villagers cured and villagers turned into zombie villagers keep what is known about them. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void transformed(EntityTransformEvent event) {
		Entity previous = ((CraftEntity) event.getEntity()).getHandle();
		Entity converted = ((CraftEntity) event.getTransformedEntity()).getHandle();
		if (previous instanceof Mob from && converted instanceof Mob to) Tracking.onConversion(from, to);
		if (event.getTransformReason() == EntityTransformEvent.TransformReason.CURED
			&& previous instanceof ZombieVillager zombie && zombie.conversionStarter != null) {
			ServerPlayer player = server.getPlayerList().getPlayer(zombie.conversionStarter);
			if (player != null) Actions.record(player, Objective.Type.CURE, 1, o -> true);
		}
	}

	// ---- deaths and damage ----

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void died(EntityDeathEvent event) {
		if (!(((CraftEntity) event.getEntity()).getHandle() instanceof LivingEntity dead)) return;
		Tracking.onDeath(dead, ((CraftDamageSource) event.getDamageSource()).getHandle());
	}

	/** A hit the entity survives; a deadly one is a death instead. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void damaged(EntityDamageEvent event) {
		if (!(((CraftEntity) event.getEntity()).getHandle() instanceof LivingEntity hurt)) return;
		float taken = (float) event.getFinalDamage();
		if (taken >= hurt.getHealth()) return;
		Tracking.onDamage(hurt, ((CraftDamageSource) event.getDamageSource()).getHandle(), (float) event.getDamage(), taken, false);
	}
}
