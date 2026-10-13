# Commands

Everything lives under `/errands`. Players need no permission for the first group. The rest needs operator level 2 (`/op`, or cheats on in single player).

## For players

| Command | What it does |
|---|---|
| `/errands` | Opens the errand journal: the errands you have taken, with their progress. |
| `/errands book` | Opens the chronicle, a book with your finished errands and your standing in each village. |
| `/errands village` | Tells you about the village you stand in: its name, bells, residents and your standing there. |

The journal and the villager screens open from the game too: right-click a villager to talk (sneak + right-click trades straight away), or right-click a village bell. The commands are there for when no villager is around.

## For operators

### Server

| Command | What it does |
|---|---|
| `/errands reload` | Reloads the config and the errand pools (datapacks under `data/<namespace>/village_errands/`). |
| `/errands reset <player>` | Wipes a player's errands and all of their standing, everywhere. |
| `/errands village name <name>` | Renames the village you stand in. Everyone sees the new name, in any language. |
| `/errands villages` | Lists every village the mod knows: id, name, centre, residents, working residents, bells. Click the coordinates to get a `/tp` command in the chat box. |
| `/errands treasury <player>` | The player's treasury: income a day, what waits, the network bonus, and one line per village they know. |

### Standing

The standing commands work for one player in one village.

```
/errands rep <player> <village> show
/errands rep <player> <village> villager <name> set <points>
/errands rep <player> <village> villager <name> tier <0-4>
/errands rep <player> <village> villager <name> reset
/errands rep <player> <village> village set <points>
/errands rep <player> <village> village tier <0-4>
/errands rep <player> <village> village reset
/errands rep <player> <village> all tier <0-4>
/errands rep <player> <village> all reset
/errands standing <player> <tier>
```

`show` prints the village standing and every resident with stars, points and tier.

`villager` changes the standing with one villager. `village` changes only the village points. A village tier also needs enough working residents at that tier, so `village tier 3` alone can leave the player lower; the command says so when that happens.

`all tier` sets the village points and every working resident to that tier at once, which meets every requirement of the tier. `all reset` makes the player a stranger to the whole village again. `standing <player> <tier>` is the short form of `rep <player> here all tier <tier>`.

Tiers and the points they start at:

| Tier | Villager | Points | Village | Points | Working residents at this villager tier |
|---|---|---|---|---|---|
| 0 | Stranger | 0 | Stranger | 0 | |
| 1 | Acquaintance | 20 | Known | 60 | 2 |
| 2 | Friend | 60 | Friend | 180 | 3 |
| 3 | Good friend | 140 | Respected | 420 | 4 |
| 4 | Best friend | 280 | Hero of the Village | 840 | 5 |

### End game

| Command | What it does |
|---|---|
| `/errands fame <player>` | Shows the player's fame and title. |
| `/errands fame <player> set <points>` / `add <points>` | Sets or adds fame. Titles: Helper at 100, Benefactor at 500, Legend at 1500. |
| `/errands order <village>` | The village's monthly order: kind, progress, days left, helpers. |
| `/errands order <village> add <player> <amount>` | Counts `amount` for the order as if the player had brought it. Reaching the target pays everyone out. |
| `/errands patron <village>` | Works out the patron again and shows who it is. |
| `/errands patron <village> clear` | Removes the patron; the best qualifying player (if any) takes the place. |
| `/errands legend <player> <profession>` | Gives the profession's legendary item and records it, as the third contract would. |

### Naming villages and villagers

`<village>` can be written three ways:

- `here` is the village the player stands in.
- A name, such as `Applecross`.
- The number from `/errands villages`, such as `3` for `#3`.

Names are the ones the target player sees, and they depend on that player's language. Tab completion lists them. A name with spaces or non-Latin letters needs quotes (`"Ябълково"`), and the suggestions add them for you.

Two villagers in one village can share a name. They get their trade added (`Lotte.fisherman`, `Lotte.weaponsmith`), and a short id on top if even that repeats.

### Testing

| Command | What it does |
|---|---|
| `/errands villager levelup [levels]` | Raises the nearest villager (8 blocks) by one trade level, or by `levels`, the way trading does: the new level gets its trades. Unlike `/data merge ... {VillagerData:{level:5}}`, which changes only the number. |
| `/errands villager <village> <villager> levelup [levels]` | The same for a villager named as you see them (the villager must be loaded). Example: `/errands villager here Lotte.weaponsmith levelup 4`. |
| `/errands finish <player>` | Fills every action counter (craft, kill, catch...) of the player's active errands. |
| `/errands give <player> <village> <villager> <errand>` | Gives the player a chosen errand from that villager, already accepted, as it would come in its pack. Tab completion lists the villager's errands: `reforge.iron_sword`, `order.winter_stock`, `craft.bread`, `kill.zombie`... A name used in two packs gets the pack added (`reforge.crossbow.t4`). |
| `/errands finish <player> items` | The same, and gives the items still missing to bring, so every active errand can be handed in right away. |

## Examples

```
/errands rep @p here show
/errands rep Steve Applecross villager Odo tier 3
/errands rep Steve 2 all tier 4
/errands rep Steve here village reset
/errands standing @p 3
/errands give @p here Lotte.weaponsmith reforge.iron_sword
/errands finish @p items
/errands fame @p set 1500
/errands order here add @p 500
/errands legend @p farmer
```
