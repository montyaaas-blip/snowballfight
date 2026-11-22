package cz.vanoce.snowfight;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jediná aréna minihry Sněhová bitva. Hráči si házejí sněhové koule,
 * každý zásah = 1 bod. Vyhrává, kdo první dosáhne cílového skóre,
 * nebo ten s nejvíce body po uplynutí času.
 */
public final class Game {

    public enum State { WAITING, COUNTDOWN, RUNNING, ENDING }

    /** Stav hráče před vstupem do hry, abychom mu ho po hře vrátili. */
    private record Snapshot(Location location, ItemStack[] contents, GameMode gameMode,
                            double health, int food, float saturation, int level, float exp,
                            List<PotionEffect> effects, Scoreboard scoreboard) {}

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX =
            "<gradient:#ff4d4d:#ffffff>❄ Sněhová bitva</gradient> <dark_gray>» <gray>";
    private static final int ENDING_SECONDS = 8;

    private final SnowFightPlugin plugin;
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private final Map<UUID, Integer> scores = new LinkedHashMap<>();
    private final Map<UUID, Long> frozenUntil = new HashMap<>();
    private final Scoreboard scoreboard;
    private final Objective objective;
    private final BossBar bossBar =
            BossBar.bossBar(Component.empty(), 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    private final BukkitTask ticker;

    private State state = State.WAITING;
    private boolean forced;
    private int secondsLeft;
    private int countdownTotal;
    private int refillCounter;
    private Location center;
    private List<UUID> winners = List.of();

    public Game(SnowFightPlugin plugin) {
        this.plugin = plugin;
        this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.objective = scoreboard.registerNewObjective(
                "snowfight", Criteria.DUMMY, MM.deserialize("<red><bold>❄ Skóre ❄"));
        this.objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        this.ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        updateBossBar();
    }

    // ------------------------------------------------------------------
    // Veřejné API (příkazy a listenery)
    // ------------------------------------------------------------------

    public State getState() {
        return state;
    }

    public int getPlayerCount() {
        return scores.size();
    }

    public boolean isPlaying(Player player) {
        return scores.containsKey(player.getUniqueId());
    }

    public void send(CommandSender to, String text, TagResolver... resolvers) {
        to.sendMessage(msg(text, resolvers));
    }

    public void join(Player player) {
        if (isPlaying(player)) {
            send(player, "<red>Už ve hře jsi.");
            return;
        }
        if (state == State.RUNNING || state == State.ENDING) {
            send(player, "<red>Hra právě probíhá, zkus to za chvíli.");
            return;
        }
        List<Location> spawns = plugin.getSpawns();
        if (spawns.isEmpty()) {
            send(player, "<red>Aréna ještě není nastavená. Admin musí použít <white>/snowfight addspawn</white>.");
            return;
        }
        int max = plugin.getConfig().getInt("max-players", 12);
        if (scores.size() >= max) {
            send(player, "<red>Hra je plná.");
            return;
        }

        snapshots.put(player.getUniqueId(), takeSnapshot(player));
        scores.put(player.getUniqueId(), 0);
        preparePlayer(player);

        Location lobby = plugin.getLobby();
        player.teleport(lobby != null ? lobby : spawns.get(0));
        player.setScoreboard(scoreboard);
        objective.getScore(player.getName()).setScore(0);
        player.showBossBar(bossBar);

        broadcast("<white><player></white> <green>se připojil <gray>(<n>/<max>)",
                ph("player", player.getName()), ph("n", scores.size()), ph("max", max));

        if (state == State.WAITING && scores.size() >= plugin.getConfig().getInt("min-players", 2)) {
            startCountdown(false);
        }
        updateBossBar();
    }

    public boolean leave(Player player, boolean notify) {
        UUID id = player.getUniqueId();
        if (!scores.containsKey(id)) {
            return false;
        }
        scores.remove(id);
        frozenUntil.remove(id);
        scoreboard.resetScores(player.getName());
        player.hideBossBar(bossBar);

        Snapshot snapshot = snapshots.remove(id);
        if (snapshot != null) {
            restore(player, snapshot);
        }
        if (notify) {
            send(player, "<yellow>Opustil jsi hru.");
        }
        broadcast("<white><player></white> <yellow>odešel ze hry <gray>(<n>)",
                ph("player", player.getName()), ph("n", scores.size()));

        if (scores.isEmpty()) {
            resetGame();
        } else if (state == State.RUNNING && scores.size() < 2 && !forced) {
            endGame();
        }
        return true;
    }

    /** Admin: spustí hru i s menším počtem hráčů, odpočet se zkrátí na 5 s. */
    public boolean forceStart() {
        if ((state != State.WAITING && state != State.COUNTDOWN) || scores.isEmpty()) {
            return false;
        }
        forced = true;
        if (state == State.WAITING) {
            startCountdown(true);
        }
        secondsLeft = Math.min(secondsLeft, 5);
        updateBossBar();
        return true;
    }

    /** Admin: okamžitě zruší hru a vrátí hráče zpět. */
    public boolean stop() {
        if (state == State.WAITING && scores.isEmpty()) {
            return false;
        }
        broadcast("<red>Hra byla zrušena adminem.");
        resetGame();
        return true;
    }

    public void shutdown() {
        ticker.cancel();
        resetGame();
    }

    /** Volá se z listeneru, když sněhová koule zasáhne hráče. */
    public void handleHit(Player shooter, Player victim, Vector direction) {
        if (state != State.RUNNING || shooter.equals(victim)) {
            return;
        }
        if (!isPlaying(shooter) || !isPlaying(victim)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (frozenUntil.getOrDefault(victim.getUniqueId(), 0L) > now) {
            return; // zamrzlý hráč se nedá "farmit"
        }

        int freezeSeconds = plugin.getConfig().getInt("freeze-seconds", 2);
        frozenUntil.put(victim.getUniqueId(), now + freezeSeconds * 1000L);

        int points = scores.merge(shooter.getUniqueId(), 1, Integer::sum);
        objective.getScore(shooter.getName()).setScore(points);

        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, freezeSeconds * 20, 2, false, false, true));
        victim.setFreezeTicks(victim.getMaxFreezeTicks());
        if (direction != null && direction.lengthSquared() > 1.0E-6) {
            Vector push = direction.clone().setY(0);
            if (push.lengthSquared() > 1.0E-6) {
                victim.setVelocity(victim.getVelocity().add(push.normalize().multiply(0.4).setY(0.2)));
            }
        }

        shooter.sendActionBar(MM.deserialize("<green>+1 bod! <gray>Trefil jsi <white><v></white>",
                ph("v", victim.getName())));
        shooter.playSound(shooter.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.4f);
        victim.sendActionBar(MM.deserialize("<red>Trefil tě <white><s></white>!",
                ph("s", shooter.getName())));
        victim.playSound(victim.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, 1f, 1f);

        if (points >= plugin.getConfig().getInt("target-score", 15)) {
            endGame();
        }
    }

    // ------------------------------------------------------------------
    // Průběh hry
    // ------------------------------------------------------------------

    private void tick() {
        switch (state) {
            case WAITING -> { }
            case COUNTDOWN -> tickCountdown();
            case RUNNING -> tickRunning();
            case ENDING -> tickEnding();
        }
    }

    private void startCountdown(boolean force) {
        state = State.COUNTDOWN;
        forced = force;
        countdownTotal = plugin.getConfig().getInt("countdown-seconds", 20);
        secondsLeft = countdownTotal;
        broadcast("<green>Hra brzy začne! Připrav si sněhové koule.");
    }

    private void tickCountdown() {
        int min = plugin.getConfig().getInt("min-players", 2);
        if (!forced && scores.size() < min) {
            state = State.WAITING;
            broadcast("<yellow>Málo hráčů, čekám na další...");
            updateBossBar();
            return;
        }
        if (secondsLeft <= 0) {
            startGame();
            return;
        }
        if (secondsLeft <= 5 || secondsLeft % 10 == 0) {
            for (Player p : players()) {
                p.sendActionBar(MM.deserialize("<green>Start za <white><n></white> s", ph("n", secondsLeft)));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
            }
        }
        secondsLeft--;
        updateBossBar();
    }

    private void startGame() {
        state = State.RUNNING;
        secondsLeft = plugin.getConfig().getInt("game-seconds", 180);
        refillCounter = 0;
        frozenUntil.clear();

        List<Location> spawns = new ArrayList<>(plugin.getSpawns());
        Collections.shuffle(spawns);
        center = computeCenter(spawns);

        int index = 0;
        for (Player p : players()) {
            scores.put(p.getUniqueId(), 0);
            objective.getScore(p.getName()).setScore(0);
            p.teleport(spawns.get(index++ % spawns.size()));
            equip(p);
            p.showTitle(Title.title(
                    MM.deserialize("<red><bold>Do boje!"),
                    MM.deserialize("<gray>Házej sněhové koule a trefuj ostatní"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1500), Duration.ofMillis(400))));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        }
        broadcast("<red>Hra začala! <gray>Cíl: <white><t></white> zásahů, nebo nejvíc bodů za <white><m></white>.",
                ph("t", plugin.getConfig().getInt("target-score", 15)),
                ph("m", formatTime(secondsLeft)));
        updateBossBar();
    }

    private void tickRunning() {
        secondsLeft--;
        refillCounter++;

        if (refillCounter >= plugin.getConfig().getInt("snowball-refill-seconds", 3)) {
            refillCounter = 0;
            for (Player p : players()) {
                refill(p);
            }
        }
        for (Player p : players()) {
            keepInArena(p);
            if (secondsLeft > 0 && secondsLeft <= 5) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.7f);
            }
        }
        updateBossBar();
        if (secondsLeft <= 0) {
            endGame();
        }
    }

    private void endGame() {
        state = State.ENDING;
        secondsLeft = ENDING_SECONDS;

        List<Map.Entry<UUID, Integer>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());

        int top = sorted.isEmpty() ? 0 : sorted.get(0).getValue();
        List<UUID> won = new ArrayList<>();
        if (top > 0) {
            for (Map.Entry<UUID, Integer> e : sorted) {
                if (e.getValue() == top) {
                    won.add(e.getKey());
                }
            }
        }
        winners = won;

        StringBuilder names = new StringBuilder();
        for (UUID id : won) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(nameOf(id));
        }

        for (Player p : players()) {
            p.getInventory().clear();
            for (PotionEffect effect : p.getActivePotionEffects()) {
                p.removePotionEffect(effect.getType());
            }
            p.setFreezeTicks(0);

            boolean isWinner = won.contains(p.getUniqueId());
            Component title = isWinner
                    ? MM.deserialize("<gold><bold>Vyhrál jsi!")
                    : MM.deserialize("<yellow><bold>Konec hry");
            Component subtitle = won.isEmpty()
                    ? MM.deserialize("<gray>Nikdo nezískal žádný bod")
                    : MM.deserialize("<gray>Vítěz: <white><w></white>", ph("w", names.toString()));
            p.showTitle(Title.title(title, subtitle,
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofMillis(500))));
            p.playSound(p.getLocation(),
                    isWinner ? Sound.ENTITY_PLAYER_LEVELUP : Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1f);
        }

        broadcast("<gold>Konec hry! <gray>Výsledky:");
        int place = 1;
        for (Map.Entry<UUID, Integer> e : sorted) {
            if (place > 3) {
                break;
            }
            broadcast("<yellow><place>. <white><player> <gray>- <green><points> b.",
                    ph("place", place), ph("player", nameOf(e.getKey())), ph("points", e.getValue()));
            place++;
        }
        updateBossBar();
    }

    private void tickEnding() {
        secondsLeft--;
        for (UUID id : winners) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && isPlaying(p)) {
                spawnFirework(p.getLocation().add(0, 1, 0));
            }
        }
        if (secondsLeft <= 0) {
            resetGame();
        }
    }

    /** Vrátí všechny hráče, vyčistí stav a připraví hru na další kolo. */
    private void resetGame() {
        for (UUID id : new ArrayList<>(scores.keySet())) {
            Player p = Bukkit.getPlayer(id);
            Snapshot snapshot = snapshots.get(id);
            if (p != null) {
                p.hideBossBar(bossBar);
                if (snapshot != null) {
                    restore(p, snapshot);
                }
            }
        }
        scores.clear();
        snapshots.clear();
        frozenUntil.clear();
        for (String entry : new ArrayList<>(scoreboard.getEntries())) {
            scoreboard.resetScores(entry);
        }
        winners = List.of();
        forced = false;
        center = null;
        state = State.WAITING;
        updateBossBar();
    }

    // ------------------------------------------------------------------
    // Pomocné metody
    // ------------------------------------------------------------------

    private void equip(Player p) {
        PlayerInventory inv = p.getInventory();
        inv.clear();
        inv.setHelmet(armor(Material.LEATHER_HELMET, Color.RED, "<red>Čepice Santy"));
        inv.setChestplate(armor(Material.LEATHER_CHESTPLATE, Color.RED, "<red>Vánoční kabátek"));
        inv.setLeggings(armor(Material.LEATHER_LEGGINGS, Color.fromRGB(0x1B8A3A), "<green>Skřítčí kalhoty"));
        inv.setBoots(armor(Material.LEATHER_BOOTS, Color.WHITE, "<white>Sněhule"));
        int max = plugin.getConfig().getInt("max-snowballs", 16);
        inv.setItem(0, new ItemStack(Material.SNOWBALL, Math.max(1, Math.min(max, 16))));
        p.getInventory().setHeldItemSlot(0);
    }

    private ItemStack armor(Material material, Color color, String name) {
        ItemStack item = new ItemStack(material);
        item.editMeta(LeatherArmorMeta.class, meta -> {
            meta.setColor(color);
            meta.displayName(MM.deserialize(name).decoration(TextDecoration.ITALIC, false));
        });
        return item;
    }

    private void refill(Player p) {
        int max = plugin.getConfig().getInt("max-snowballs", 16);
        int have = 0;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.getType() == Material.SNOWBALL) {
                have += item.getAmount();
            }
        }
        if (have < max) {
            p.getInventory().addItem(new ItemStack(Material.SNOWBALL, Math.min(4, max - have)));
        }
    }

    private void keepInArena(Player p) {
        if (center == null) {
            return;
        }
        double radius = plugin.getConfig().getDouble("arena-radius", 40.0);
        Location loc = p.getLocation();
        boolean outside = !loc.getWorld().equals(center.getWorld())
                || loc.distanceSquared(center) > radius * radius;
        if (outside) {
            List<Location> spawns = plugin.getSpawns();
            if (!spawns.isEmpty()) {
                p.teleport(spawns.get(ThreadLocalRandom.current().nextInt(spawns.size())));
                p.sendActionBar(MM.deserialize("<yellow>Vrátil ses zpět do arény."));
            }
        }
    }

    private Location computeCenter(List<Location> spawns) {
        World world = spawns.get(0).getWorld();
        double x = 0, y = 0, z = 0;
        for (Location s : spawns) {
            x += s.getX();
            y += s.getY();
            z += s.getZ();
        }
        int n = spawns.size();
        return new Location(world, x / n, y / n, z / n);
    }

    private void spawnFirework(Location location) {
        location.getWorld().spawn(location, Firework.class, firework -> {
            FireworkMeta meta = firework.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .with(FireworkEffect.Type.STAR)
                    .withColor(Color.RED, Color.WHITE)
                    .withFade(Color.GREEN)
                    .build());
            meta.setPower(1);
            firework.setFireworkMeta(meta);
        });
    }

    private Snapshot takeSnapshot(Player p) {
        ItemStack[] raw = p.getInventory().getContents();
        ItemStack[] copy = new ItemStack[raw.length];
        for (int i = 0; i < raw.length; i++) {
            copy[i] = raw[i] == null ? null : raw[i].clone();
        }
        return new Snapshot(p.getLocation(), copy, p.getGameMode(), p.getHealth(), p.getFoodLevel(),
                p.getSaturation(), p.getLevel(), p.getExp(),
                new ArrayList<>(p.getActivePotionEffects()), p.getScoreboard());
    }

    private void preparePlayer(Player p) {
        p.getInventory().clear();
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(maxHealth(p));
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        p.setFreezeTicks(0);
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
    }

    private void restore(Player p, Snapshot s) {
        p.teleport(s.location());
        p.getInventory().clear();
        p.getInventory().setContents(s.contents());
        for (PotionEffect effect : p.getActivePotionEffects()) {
            p.removePotionEffect(effect.getType());
        }
        for (PotionEffect effect : s.effects()) {
            p.addPotionEffect(effect);
        }
        p.setFreezeTicks(0);
        p.setGameMode(s.gameMode());
        p.setHealth(Math.min(s.health(), maxHealth(p)));
        p.setFoodLevel(s.food());
        p.setSaturation(s.saturation());
        p.setLevel(s.level());
        p.setExp(s.exp());
        p.setScoreboard(s.scoreboard());
    }

    private double maxHealth(Player p) {
        AttributeInstance attribute = p.getAttribute(Attribute.MAX_HEALTH);
        return attribute != null ? attribute.getValue() : 20.0;
    }

    private void updateBossBar() {
        switch (state) {
            case WAITING -> {
                bossBar.name(MM.deserialize("<white>Čekání na hráče <gray>(<n>/<min>)",
                        ph("n", scores.size()), ph("min", plugin.getConfig().getInt("min-players", 2))));
                bossBar.color(BossBar.Color.WHITE);
                bossBar.progress(1f);
            }
            case COUNTDOWN -> {
                bossBar.name(MM.deserialize("<green>Start za <white><n></white> s", ph("n", secondsLeft)));
                bossBar.color(BossBar.Color.GREEN);
                bossBar.progress(clamp(countdownTotal == 0 ? 0f : (float) secondsLeft / countdownTotal));
            }
            case RUNNING -> {
                int total = Math.max(1, plugin.getConfig().getInt("game-seconds", 180));
                bossBar.name(MM.deserialize("<red>Zbývá <white><t></white> <gray>| cíl: <white><g> b.",
                        ph("t", formatTime(secondsLeft)),
                        ph("g", plugin.getConfig().getInt("target-score", 15))));
                bossBar.color(BossBar.Color.RED);
                bossBar.progress(clamp((float) secondsLeft / total));
            }
            case ENDING -> {
                bossBar.name(MM.deserialize("<gold>Konec hry"));
                bossBar.color(BossBar.Color.YELLOW);
                bossBar.progress(1f);
            }
        }
    }

    private List<Player> players() {
        List<Player> list = new ArrayList<>();
        for (UUID id : scores.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        return p != null ? p.getName() : "?";
    }

    private void broadcast(String text, TagResolver... resolvers) {
        Component component = msg(text, resolvers);
        for (Player p : players()) {
            p.sendMessage(component);
        }
    }

    private Component msg(String text, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + text, resolvers);
    }

    private static TagResolver ph(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    private static String formatTime(int seconds) {
        int s = Math.max(0, seconds);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
