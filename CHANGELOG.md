# Changelog

## 0.13.2-beta

- The librarian's "Enchant at level 10 (or 30) or higher" errands can be finished now. They compared the level with what the enchant cost (1 to 3 levels) instead of the level shown on the chosen option, so they never moved.

## 0.13.1-beta

- A Paper version: `villagers-remember-paper-0.13.1-beta.jar` runs as a plugin on Paper 26.3, with the same errands and prices as the mod. Paper has no settings screen, so admins edit `plugins/VillagersRemember/villagersremember.json` and run `/errands reload`.
- Taking stonecutter results with shift-click now counts for errands like Make Stone Bricks. Before, only single clicks counted.

## 0.13.0-beta

First public beta for Minecraft 26.3 on Fabric. The mod runs on the server, and players join with a vanilla client.

- Every villager with a job asks for help, and the village bell shows all of the village's errands. There are 21 kinds of tasks, like baking bread or finding a shipwreck.
- Villagers remember you. Your standing grows with each villager and each village, and prices follow it: up to 40% off for friends, up to 50% more if you hurt the village.
- In a new village one villager talks to you first. More open up as you help them, trade with them or raise the village's standing.
- Errands pay experience and standing. Server owners can turn emerald rewards on with `/errands emeralds on`. Items come once, from trials of trust and from each profession's top reward.
- Villages that trust you set emeralds aside in a treasury, and you collect them in your ender chest.
- For the long run there are weekly contracts, legendary items, monthly village orders for the whole server, fame titles and one patron per village.
- The mod has its own advancement tab and a chronicle book. Clients with the mod get a settings screen, where admins can chnage the server's settings.
- Works with Waystones (BlayTheNinth's mod or KawaMood's datapack): village waystones take the name of their village.
- English and Bulgarian.
- On Fabric and on NeoForge 26.3, as two seperate files with the same features.
