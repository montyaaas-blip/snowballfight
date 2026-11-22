package cz.vanoce.snowfight;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SnowCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "snowfight.admin";

    private final SnowFightPlugin plugin;
    private final Game game;

    public SnowCommand(SnowFightPlugin plugin, Game game) {
        this.plugin = plugin;
        this.game = game;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "join" -> {
                if (sender instanceof Player player) {
                    game.join(player);
                } else {
                    game.send(sender, "<red>Tento příkaz může použít jen hráč.");
                }
            }
            case "leave" -> {
                if (sender instanceof Player player) {
                    if (!game.leave(player, true)) {
                        game.send(player, "<red>Nejsi ve hře.");
                    }
                } else {
                    game.send(sender, "<red>Tento příkaz může použít jen hráč.");
                }
            }
            case "status" -> status(sender);
            case "start" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (game.forceStart()) {
                    game.send(sender, "<green>Hra se spouští.");
                } else {
                    game.send(sender, "<red>Nelze spustit (hra už běží, nebo v ní nikdo není).");
                }
            }
            case "stop" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                if (game.stop()) {
                    game.send(sender, "<green>Hra zastavena.");
                } else {
                    game.send(sender, "<red>Žádná hra neběží.");
                }
            }
            case "setlobby" -> {
                if (!requireAdmin(sender) || !requirePlayer(sender)) {
                    return true;
                }
                plugin.setLobby(((Player) sender).getLocation());
                game.send(sender, "<green>Lobby nastaveno na tvoji pozici.");
            }
            case "addspawn" -> {
                if (!requireAdmin(sender) || !requirePlayer(sender)) {
                    return true;
                }
                plugin.addSpawn(((Player) sender).getLocation());
                game.send(sender, "<green>Spawn č. <white><n></white> přidán.",
                        Placeholder.unparsed("n", String.valueOf(plugin.getSpawns().size())));
            }
            case "clearspawns" -> {
                if (!requireAdmin(sender)) {
                    return true;
                }
                plugin.clearSpawns();
                game.send(sender, "<green>Všechny spawny smazány.");
            }
            default -> help(sender);
        }
        return true;
    }

    private void status(CommandSender sender) {
        String state = switch (game.getState()) {
            case WAITING -> "čeká se na hráče";
            case COUNTDOWN -> "odpočet";
            case RUNNING -> "probíhá";
            case ENDING -> "končí";
        };
        game.send(sender, "Stav: <white><s></white>, hráčů: <white><p></white>, spawnů: <white><c></white>, lobby: <white><l></white>",
                Placeholder.unparsed("s", state),
                Placeholder.unparsed("p", String.valueOf(game.getPlayerCount())),
                Placeholder.unparsed("c", String.valueOf(plugin.getSpawns().size())),
                Placeholder.unparsed("l", plugin.getLobby() != null ? "nastaveno" : "nenastaveno"));
    }

    private void help(CommandSender sender) {
        game.send(sender, "<white>/snowfight join</white> - připojit se do hry");
        game.send(sender, "<white>/snowfight leave</white> - odejít ze hry");
        game.send(sender, "<white>/snowfight status</white> - stav hry");
        if (sender.hasPermission(ADMIN)) {
            game.send(sender, "<white>/snowfight setlobby</white> - nastaví čekárnu na tvoji pozici");
            game.send(sender, "<white>/snowfight addspawn</white> - přidá spawn v aréně na tvoji pozici");
            game.send(sender, "<white>/snowfight clearspawns</white> - smaže všechny spawny");
            game.send(sender, "<white>/snowfight start</white> - vynutí start (i s menším počtem hráčů)");
            game.send(sender, "<white>/snowfight stop</white> - zruší hru");
        }
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission(ADMIN)) {
            return true;
        }
        game.send(sender, "<red>Na to nemáš oprávnění.");
        return false;
    }

    private boolean requirePlayer(CommandSender sender) {
        if (sender instanceof Player) {
            return true;
        }
        game.send(sender, "<red>Tento příkaz může použít jen hráč.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>(List.of("join", "leave", "status"));
        if (sender.hasPermission(ADMIN)) {
            options.addAll(List.of("start", "stop", "setlobby", "addspawn", "clearspawns"));
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(prefix)).toList();
    }
}
