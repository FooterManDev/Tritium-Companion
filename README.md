### Tritium Companion is a multi-platform mod designed to provide game data to Tritium for ModPack Developers.

Features:
* Can dump Registry Objects, like Recipes. This is triggered either in-game using the command or from Tritium.
* Can dump KubeJS Typings for Tritium's Editor. Typings are dumped as a Flatbuffer.

* Tritium's "Stop Game" button interacts with this mod first,
to close the game as if the player did from the game to prevent corrupting data by forcefully closing the game.
Terminating the game process is still an option, available when holding `shift` and clicking Stop.
* Users can run game commands from Tritium.
* Users can run a server reload from Tritium.