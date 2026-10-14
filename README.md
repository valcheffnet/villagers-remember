# Villagers Remember: Quests, Trust & Fair Trades

[![Villager heads from Minecraft Heads](art/minecraft-heads-banner.png)](https://minecraft-heads.com)

**Server-side. Vanilla clients welcome.**

Villagers in vanilla Minecraft trade and that's it. With this mod they ask you for help. The farmer needs 20 bread baked, the cleric wants zombies cleared off the road, the cartographer sends you to find a shipwreck. Do the job, bring what was asked, and get paid. Keep helping and every villager remembers it: they greet you by how well they know you, give you better work, sell to you cheaper and one day make you the patron of their village.

The whole mod runs on the server. Players join with a normal vanilla client and install nothing. The screens are vanilla dialogs, the markers are vanilla text displays, and the names come in each player's own language.

## What makes it different

Most quest mods give a village a board or a quest master, and every player has to install the mod. Here each villager is a person with their own memory of you. Stoyan the farmer may be your best friend while the butcher next door has never heard of you. That trust shows up where you already look: the prices in the trading screen go down for friends and up for people who hurt the village.

Rewards are tuned to the work. Every errand pays more than the same goods would fetch from a villager, so an errand never earns less than plain trading. Big jobs are for the whole server: a monthly village order sized by how many people play, paid by what each of them brought.

## Talking to villagers

Right-click a villager and they greet you the way your standing deserves. "Yes? Do I know you?" from a stranger, "My friend! Come, sit." from someone you have helped for weeks. From there you pick Errands or Trade. Sneak + right-click still trades straight away, so trading halls stay one click.

Every villager with a job offers a few errands a day, more as their trade level grows. Each errand is one or more of 21 kinds of tasks: craft, smelt, mine, harvest, fish, breed, tame, kill, brew, enchant, visit a structure or a biome, and so on. Harvesting counts whether you break the crop or pick it with a right-click mod like RightClickHarvest. Taking an item to a villager is counted apart from making it, so you can't just buy what was asked.

Right-click the village bell for the board: all of the village's errands, its residents, your standing, the treasury and the village order. Sneak + right-click rings the bell as usual.

## Standing and trust

A village doesn't open up to a stranger all at once, and every player earns it on their own. When you first come, one villager dares to talk to you (the one with the "!"); the others trade with you and tell you to ask them. Every tier you reach with someone who trusts you makes one more villager willing to give you work. Helping to finish a village order does too, and so does trading with a villager until they reach a new trade level (they liked dealing with you). Villagers also go by their own memory: one you cured, or one you have traded with a lot lately (about ten trades), knows you from the start, so a trading hall you have used for ages, Master villagers included, opens up at once. The village's own tier adds more: one at Known, two at Friend, three at Respected, and everyone once you are its Hero. If someone who trusts you dies, their place stays empty; only when nobody who trusted you is left does someone new dare, the next day. A friend turned zombie villager is back, with everything, once cured. Server owners can turn this off (`gradualTrust`).

You earn standing with every villager and with the village as a whole. Villager tiers go Stranger, Acquaintance, Friend, Good friend, Best friend. A villager won't move you up a tier on points alone: when you have the points, they give you a trial of trust, a harder errand that pays double. Pass it and they hand you the next pack of errands.

Village tiers (Known, Friend, Respected, Hero of the Village) need points and enough residents who trust you that much. Each tier makes rewards 5% bigger and enchanted rewards stronger.

Each pack asks for more work. A first errand takes about five minutes for a player who has no farms yet: twenty wheat from a field, a few rabbits, a handful of coal. A top-pack errand takes half an hour and can't be pulled out of a chest: an elder guardian, a woodland mansion, a pair of zombie villagers to cure, a trident. Each pack after that expects a bit more of a farm, and the top pack assumes the usual farms, so farmable goods stay in the low packs.

## Rewards

Daily errands pay experience and standing, sized by the work. Emerald rewards are off by default so that trading keeps its worth; a server that wants them runs `/errands emeralds on`. Then errands pay emeralds as well: a few for a five-minute job, 40 to 56 emeralds' worth for a half-hour one.

Items come once. The first trial of trust of each tier with a profession gives one of its items: golden carrots, a treasure map, enchanted books. The first time a villager of a profession makes you their Best friend, you choose its top reward: Fortune or Silk Touch on a netherite pickaxe, Sharpness or Smite on the sword, a map to a mansion. Gear comes with Unbreaking III and Mending, and the enchantments stay a surprise until you hand the trial in. The smiths reforge each kind of piece once: forge a few iron swords, get one back enchanted, stone at the start up to netherite at the top.

## Prices

Your standing sets your prices. A Best friend in a Hero village trades 40% cheaper, while a player who hurt the village pays up to 50% more. Vanilla's own discounts (curing a zombie villager, Hero of the Village after a raid) still apply on top.

## Treasury

Villagers who trust you put a share of their work aside for you every day. The treasury lists every village you have found with what it pays a game day; one button sends the emeralds to your ender chest. It is a small, slow income: a Hero village where ten villagers are your best friends puts aside about 0.6 emeralds a game day, some 40 in a real day of play. Each village keeps up to 504 game days of income (a real week of play), so nothing is lost if you collect once a day, and helping five villages at Friend or better raises all of it by 25%. The treasury pays whether errands pay emeralds or not. Your very first trial of trust is the way in: the villager asks you to get an ender chest (made, bought or found, it stays yours), because that is where the village will leave its share.

## End game

- Fame from everything you do, with titles in chat and TAB: Helper, Benefactor, Legend. A Legend finds rare goods with the wandering trader.
- Contracts: once a week every Best friend has a big job in three parts, about an hour and a half of work, paid in emeralds, experience, standing and fame. The third contract for a profession brings its legendary item, named after the villager who gave it ("Stoyan's Hoe") and enchanted to the limit.
- Village orders: once a game month every village posts one big order for everyone on the server, sized by how many people play regularly: 512 food before winter for each of them, or 50 monsters cleared out of the village. Two friends get an order for two, a busy server one for its crowd. Bring any amount, any time. When it's done everyone is paid by their share, and never less than the goods would fetch from a villager.
- Patron: the Hero of a village who has passed every trial with five of its villagers can become its patron. A village has one patron, the one with the most standing; anyone who does more takes the place, and the whole server hears about it. The patron's head is shown at the bell, they trade there at 45% off and they can give the village a name.
- A collection for every profession: do each of its errands once.
- Its own advancement tab, 45 of them.

## Everything else

- 230 village names (Bulgarian villages, many of them real) and 314 villager names, mostly old Bulgarian ones, the same name in both languages (Мария is Maria in English). More languages can be added as lang files.
- Villager names above their heads and in Jade, for each player in their language.
- A sidebar tracker for the errands you follow. Over villagers: "!" when they have work for you and your journal has room, a yellow "?" when you can hand something in, a grey "?" when you carry one of their errands.
- A chronicle book with your errands, villages, fame and collection (`/errands book`).
- A villager who turns into a zombie villager and is cured keeps your standing, errands and name.
- Works with the Waystones mod (BlayTheNinth) and the KawaMood's Waystones datapack: the waystone in a village is named after it (English name, or the name its patron gave it), so the waystone list shows villages. Waystones a player named keep their name. Setting `waystoneNames`, shown only when one of them is installed.
- Errands are datapack JSON (`data/<namespace>/errands/*.json`), so packs can add their own.

## Install

Fabric Loader 0.19 or newer and Fabric API, Minecraft 26.3. Drop the jar into the server's `mods` folder. In single player it works the same when installed on the client.

## Configuration

Players who install the mod on their client get a settings screen, from Mod Menu or with `/villagersremember`. On a server that runs the mod, admins (permission level 3) see and change the server's settings there; in single player the world's owner always can. Every change is written to the server log.

The same settings live in `config/villagersremember.json`, created on first start, with a comment in the code for each one ([ErrandsConfig.java](src/main/java/net/valcheff/villagersremember/ErrandsConfig.java)). Some of them:

| Setting | Default | |
|---|---|---|
| `language` | `auto` | Each player's client language, or force one (`en_us`, `bg_bg`). |
| `talkMenu` | `true` | Right-click greets; `false` keeps vanilla right-click trading and puts errands on sneak + right-click. |
| `maxActiveErrands` | `5` | Errands one player can carry at once. |
| `tradeDiscounts` | `true` | Prices by standing. `maxDiscount` 0.40, `maxSurcharge` 0.50, `patronDiscount` 0.45. |
| `treasuryDailyCap` | `64` | Most emeralds one village pays a day. |
| `fameTitles` | `true` | Titles in chat and TAB. Turn off if another plugin or mod manages teams. |
| `tierEmeraldsPerMinute` | `0.6 ... 1.6` | Pay per minute of work, by pack. |
| `groupOrders` | `true` | `groupOrderDays` 30; per regular player (online on `groupRegularDays` 3 days of the month): `groupOrderScale` 1.0 of the size, `groupRewardEmeralds` 80. |
| `emeraldRewards` | `false` | `false` stops emeralds from errands, contracts and village orders; the treasury still pays. Same as `/errands emeralds off`. |
| `xpMultiplier` | `1.0` | Experience from errands, contracts and village orders. Same as `/errands xp`. |
| `contracts` | `true` | `contractMinutes` 90, `contractMaxEmeralds` 160, `contractsForLegend` 3. |
| `villagerNames` | `always` | `always`, `look` (only when looking at them) or `off`. |

Run `/errands reload` after editing.

## Commands

Players have `/errands` (journal), `/errands book` and `/errands village`, which only show their own progress. Everything else needs permission level 3: standing, testing and end game commands, and two switches for the economy (`/errands emeralds on|off`, `/errands xp <multiplier>`). All of them are listed in [COMMANDS.md](COMMANDS.md).

## Credits

Villager head textures come from [minecraft-heads.com](https://minecraft-heads.com) and are used with their permission. Item and block names shown in the screens are Minecraft's own translations and belong to Mojang.

## License

All rights reserved, with permission to use the mod on any server and in modpacks. See [LICENSE.md](LICENSE.md).
