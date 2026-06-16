<img width="128" height="128" alt="icon" src="https://github.com/user-attachments/assets/652c3ef6-3b42-4fbf-a746-ae79c50857a0" />


### Tritium Companion is a multi-platform mod designed to provide game data to Tritium for ModPack Developers.

Features:
* Can dump Registry Objects, like Recipes. This is triggered either in-game using the command or from Tritium.
* Can dump KubeJS Typings for Tritium's Editor. Typings are dumped as a Flatbuffer.

* Tritium's "Stop Game" button interacts with this mod first,
to close the game as if the player did from the game to prevent corrupting data by forcefully closing the game.
Terminating the game process is still an option, available when holding `shift` and clicking Stop.
* Users can run game commands from Tritium.
* Users can run a server reload from Tritium.

---

Included in this repo is [Tritium-Mod-API](https://github.com/FooterManDev/Tritium-Companion/tree/1.21.1-neo-fabric/Tritium-Mod-API) for Mods to implement.
This is nowhere near ready enough for general use.

Current features:
* Provide Recipe Type descriptors for the Recipe Viewer in Tritium.
