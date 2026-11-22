package cz.vanoce.snowfight;

import org.bukkit.Location;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class SnowFightPlugin extends JavaPlugin {

    private Game game;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        game = new Game(this);
        getServer().getPluginManager().registerEvents(new GameListener(game), this);

        PluginCommand command = getCommand("snowfight");
        if (command != null) {
            SnowCommand handler = new SnowCommand(this, game);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
        }
    }

    // ---- Uložená aréna ----

    public Location getLobby() {
        return getConfig().getLocation("arena.lobby");
    }

    public void setLobby(Location location) {
        getConfig().set("arena.lobby", location);
        saveConfig();
    }

    public List<Location> getSpawns() {
        List<Location> result = new ArrayList<>();
        for (Object o : getConfig().getList("arena.spawns", List.of())) {
            if (o instanceof Location location) {
                result.add(location);
            }
        }
        return result;
    }

    public void addSpawn(Location location) {
        List<Location> spawns = getSpawns();
        spawns.add(location);
        getConfig().set("arena.spawns", spawns);
        saveConfig();
    }

    public void clearSpawns() {
        getConfig().set("arena.spawns", new ArrayList<Location>());
        saveConfig();
    }
}
