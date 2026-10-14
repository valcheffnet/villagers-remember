# Changelog

## 1.0.0

First release, for Minecraft 26.3 on Fabric. Server-side: players join with a vanilla client.

- Errands from every villager with a job, and the whole village's on the bell board. 21 kinds of tasks, orders for several goods, reforging for the smiths.
- Right-click greets you by your standing, with Errands and Trade; sneak + right-click trades.
- Standing with each villager and each village, trials of trust between villager tiers, five packs of errands sized by minutes of work (about 5 minutes at the start, half an hour at the top).
- Daily errands pay emeralds, experience and standing. Items come once: from the first trial of each tier with a profession, the top reward you choose at the first Best-friend trial, one reforge of each piece.
- Prices by standing: up to 40% off, up to 50% more after harming the village.
- Treasury: daily income from villagers who trust you, sent to the ender chest.
- Fame and titles in chat and TAB, weekly contracts and legendary items, monthly village orders for the whole server, one patron per village.
- An advancement tab, a chronicle book and a profession collection.
- Two economy switches for server owners: `/errands emeralds on|off` (off by default) and `/errands xp <multiplier>`.
- A settings screen for clients with the mod (Mod Menu or `/villagersremember`). Admins edit the server's settings from it.
- Players can only use the commands about their own progress; everything else needs permission level 3.
- Waystones (mod by BlayTheNinth, or KawaMood's datapack): village waystones are named after their village.
- Grey "?" over villagers whose errands you carry, also when the journal is full.
- Harvests picked with a right-click mod (RightClickHarvest and similar) count.
- A harvest counts what the crops gave, not the plants: "harvest 18 carrots" is done with 18 carrots (seeds left out, the one a right-click mod replants too).
- Gradual trust: in a new village one villager talks to you first; every tier with someone who trusts you, a finished village order, trading a villager up a level and the village's standing open up more; villagers you cured or traded with a lot lately know you from the start. Per player.
- The first trial of trust a player ever gets asks for an ender chest (kept): that is where the treasury leaves its daily share. The treasury pays whether errand emeralds are on or off.
- Treasury income is a 24th of before (about 0.6 a game day for a Hero village of best friends) and is kept for 504 game days (a real week).
- The first pack is sized for players without farms (20-odd wheat, 30-50 sugar cane instead of hundreds); each pack after it expects a bit more of a farm, the top one the usual farms.
- English and Bulgarian.
