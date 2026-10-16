package de.invisnames;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versteckt den Namen von unsichtbaren Spielern:
 *  - Nametag ueber dem Kopf
 *  - Name in Kill-/Todesnachrichten
 *  - Name in der Tabliste (Rang + Tag + Ping bleiben, Kopf wird schwarz)
 */
public final class InvisNames extends JavaPlugin implements Listener {

    private static final String TEAM_NAME = "zz_invisnames";
    private static final String NO_TEAM = "";

    /** Merkt sich, was wir fuer einen unsichtbaren Spieler veraendert haben. */
    private static final class State {
        Component oldListName;
        PlayerProfile oldProfile;
        boolean skinApplied;
        Component lastTab;
        /** Scoreboard -> Name des vorherigen Teams (oder NO_TEAM) */
        final Map<Scoreboard, String> oldTeams = new IdentityHashMap<>();
    }

    private final Map<UUID, State> active = new ConcurrentHashMap<>();
    private Method papiSetPlaceholders;

    // ------------------------------------------------------------------ Lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            try {
                Class<?> papi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                papiSetPlaceholders = papi.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI gefunden, aber nicht nutzbar: " + t.getMessage());
            }
        } else {
            getLogger().warning("PlaceholderAPI fehlt - Rang/Tag in der Tabliste koennen nicht aufgeloest werden.");
        }

        if (getConfig().getBoolean("black-skin.enabled", true)
                && getConfig().getString("black-skin.value", "").isBlank()) {
            getLogger().warning("black-skin.value ist leer - der Tab-Kopf wird nicht schwarz (siehe README.md).");
        }

        // Reste eines evtl. abgestuerzten Servers aufraeumen
        for (Scoreboard sb : allScoreboards()) {
            Team t = sb.getTeam(TEAM_NAME);
            if (t != null) t.unregister();
        }

        // Alle 0,5 Sekunden pruefen (faengt auch Effekte ab, die von anderen Plugins kommen)
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 10L);
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) deactivate(p, true);
        }
        active.clear();
        for (Scoreboard sb : allScoreboards()) {
            Team t = sb.getTeam(TEAM_NAME);
            if (t != null) t.unregister();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            for (Player p : Bukkit.getOnlinePlayers()) {
                State s = active.get(p.getUniqueId());
                if (s != null) {
                    s.lastTab = null;
                    refresh(p, s);
                }
            }
            sender.sendMessage("InvisNames: Config neu geladen.");
            return true;
        }
        sender.sendMessage("Benutzung: /invisnames reload");
        return true;
    }

    // ------------------------------------------------------------------ Erkennung

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) check(p);
    }

    private boolean isInvis(Player p) {
        return p.hasPotionEffect(PotionEffectType.INVISIBILITY);
    }

    private void check(Player p) {
        boolean inv = isInvis(p);
        State s = active.get(p.getUniqueId());
        if (inv && s == null) {
            activate(p);
        } else if (!inv && s != null) {
            deactivate(p, true);
        } else if (inv) {
            refresh(p, s);
        }
    }

    @EventHandler
    public void onPotion(EntityPotionEffectEvent e) {
        if (e.getEntity() instanceof Player p) {
            // einen Tick spaeter pruefen, damit der Effekt schon gesetzt/entfernt ist
            Bukkit.getScheduler().runTask(this, () -> {
                if (p.isOnline()) check(p);
            });
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (getConfig().getBoolean("join-quit.hide-messages", true) && isInvis(p)) e.joinMessage(null);
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline()) check(p);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (getConfig().getBoolean("join-quit.hide-messages", true)
                && (active.containsKey(p.getUniqueId()) || isInvis(p))) {
            e.quitMessage(null);
        }
        deactivate(p, false);
    }

    // ------------------------------------------------------------------ An / Aus

    private void activate(Player p) {
        State s = new State();
        s.oldListName = p.playerListName();
        active.put(p.getUniqueId(), s);
        applySkin(p, s);
        s.lastTab = null;
        refresh(p, s);
    }

    private void deactivate(Player p, boolean restore) {
        State s = active.remove(p.getUniqueId());
        if (s == null) return;

        removeFromTeams(p, s);

        if (!restore) return;

        if (s.skinApplied && s.oldProfile != null) {
            try {
                p.setPlayerProfile(s.oldProfile);
            } catch (Throwable t) {
                getLogger().warning("Skin konnte nicht zurueckgesetzt werden: " + t.getMessage());
            }
        }
        if (getConfig().getBoolean("tab.enabled", true)) {
            for (Player viewer : Bukkit.getOnlinePlayers()) viewer.listPlayer(p);
            Component old = s.oldListName;
            boolean isDefault = old == null
                    || PlainTextComponentSerializer.plainText().serialize(old).equals(p.getName());
            p.playerListName(isDefault ? null : old);
        }
    }

    private void refresh(Player p, State s) {
        if (getConfig().getBoolean("nametag.hide", true)) ensureHiddenNametag(p, s);
        if (getConfig().getBoolean("tab.enabled", true)) {
            if (tabHidden()) {
                for (Player viewer : Bukkit.getOnlinePlayers()) viewer.unlistPlayer(p);
            } else {
                updateTab(p, s);
            }
        }
    }

    /** true = Spieler steht gar nicht in der Tabliste, sonst nur ohne Namen */
    private boolean tabHidden() {
        return "HIDDEN".equalsIgnoreCase(getConfig().getString("tab.mode", "HIDDEN"));
    }

    // ------------------------------------------------------------------ Nametag (Scoreboard-Team)

    private Set<Scoreboard> allScoreboards() {
        Set<Scoreboard> set = Collections.newSetFromMap(new IdentityHashMap<>());
        if (Bukkit.getScoreboardManager() != null) set.add(Bukkit.getScoreboardManager().getMainScoreboard());
        for (Player pl : Bukkit.getOnlinePlayers()) set.add(pl.getScoreboard());
        return set;
    }

    private void ensureHiddenNametag(Player p, State s) {
        String entry = p.getName();
        for (Scoreboard sb : allScoreboards()) {
            Team ours = sb.getTeam(TEAM_NAME);
            if (ours == null) {
                ours = sb.registerNewTeam(TEAM_NAME);
                ours.setOption(Team.Option.NAME_TAG_VISIBILITY, Team.OptionStatus.NEVER);
            }
            if (!ours.hasEntry(entry)) {
                Team current = sb.getEntryTeam(entry);
                if (!s.oldTeams.containsKey(sb)) {
                    s.oldTeams.put(sb, current != null ? current.getName() : NO_TEAM);
                }
                ours.addEntry(entry);
            }
        }
    }

    private void removeFromTeams(Player p, State s) {
        String entry = p.getName();
        for (Scoreboard sb : allScoreboards()) {
            Team ours = sb.getTeam(TEAM_NAME);
            if (ours != null && ours.hasEntry(entry)) {
                ours.removeEntry(entry);
                String old = s.oldTeams.get(sb);
                if (old != null && !old.isEmpty()) {
                    Team prev = sb.getTeam(old);
                    if (prev != null) prev.addEntry(entry);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Tabliste

    private void updateTab(Player p, State s) {
        Component c = buildTab(p);
        if (!c.equals(s.lastTab)) {
            p.playerListName(c);
            s.lastTab = c;
        }
    }

    private Component buildTab(Player p) {
        String fmt = getConfig().getString("tab.format", "%luckperms_prefix%&r %deluxetags_tag%");
        fmt = papi(p, fmt).replace('§', '&');
        Component c = LegacyComponentSerializer.builder().character('&').hexColors().build().deserialize(fmt);
        if (PlainTextComponentSerializer.plainText().serialize(c).isBlank()) {
            return Component.text(" ");
        }
        return c;
    }

    private String papi(Player p, String text) {
        if (papiSetPlaceholders == null) return text;
        try {
            return (String) papiSetPlaceholders.invoke(null, p, text);
        } catch (Throwable ignored) {
            return text; // Platzhalter bleiben dann unaufgeloest stehen
        }
    }

    private void applySkin(Player p, State s) {
        if (tabHidden() || !getConfig().getBoolean("tab.enabled", true)) return;
        if (!getConfig().getBoolean("black-skin.enabled", true)) return;
        String value = getConfig().getString("black-skin.value", "");
        if (value.isBlank()) return;
        try {
            s.oldProfile = p.getPlayerProfile();
            PlayerProfile np = Bukkit.createProfile(p.getUniqueId(), p.getName());
            String sig = getConfig().getString("black-skin.signature", "");
            np.setProperty(new ProfileProperty("textures", value, sig.isBlank() ? null : sig));
            p.setPlayerProfile(np);
            s.skinApplied = true;
        } catch (Throwable t) {
            getLogger().warning("Schwarzer Tab-Kopf konnte nicht gesetzt werden: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ Chat

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("chat.enabled", true)) return;
        Player p = e.getPlayer();
        if (!active.containsKey(p.getUniqueId())) return;

        String fmt = getConfig().getString("chat.format", "&7----- &8» &f{message}");
        fmt = papi(p, fmt).replace('§', '&');
        Component base = LegacyComponentSerializer.builder().character('&').hexColors().build().deserialize(fmt);
        e.renderer(ChatRenderer.viewerUnaware((source, sourceDisplayName, message) ->
                base.replaceText(TextReplacementConfig.builder()
                        .matchLiteral("{message}")
                        .replacement(message)
                        .build())));
    }

    // ------------------------------------------------------------------ Advancements

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAdvancement(PlayerAdvancementDoneEvent e) {
        if (getConfig().getBoolean("advancements.hide-for-invisible", true)
                && (active.containsKey(e.getPlayer().getUniqueId()) || isInvis(e.getPlayer()))) {
            e.message(null);
        }
    }

    // ------------------------------------------------------------------ Kill-Nachricht

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        if (!getConfig().getBoolean("kill-message.enabled", true)) return;
        Component msg = e.deathMessage();
        if (msg == null) return;

        Player victim = e.getEntity();
        boolean hideVictim = getConfig().getBoolean("kill-message.hide-victim-name", false);

        List<Player> hidden = new ArrayList<>();
        for (Player pl : Bukkit.getOnlinePlayers()) {
            boolean inv = active.containsKey(pl.getUniqueId()) || isInvis(pl);
            if (!inv) continue;
            if (pl.equals(victim) && !hideVictim) continue;
            hidden.add(pl);
        }
        if (hidden.isEmpty()) return;

        Component replacement = MiniMessage.miniMessage()
                .deserialize(getConfig().getString("kill-message.replacement", "-----"));
        e.deathMessage(scrub(msg, hidden, replacement));
    }

    /** Ersetzt alle Argumente der Nachricht, die einen der Spieler darstellen. */
    private Component scrub(Component c, List<Player> hidden, Component replacement) {
        if (c instanceof TranslatableComponent tc) {
            List<TranslationArgument> newArgs = new ArrayList<>();
            for (TranslationArgument a : tc.arguments()) {
                Component ac = a.asComponent();
                if (matchesAny(ac, hidden)) {
                    newArgs.add(TranslationArgument.component(replacement));
                } else {
                    newArgs.add(TranslationArgument.component(scrub(ac, hidden, replacement)));
                }
            }
            c = tc.arguments(newArgs);
        }
        List<Component> kids = new ArrayList<>();
        for (Component child : c.children()) kids.add(scrub(child, hidden, replacement));
        return c.children(kids);
    }

    private boolean matchesAny(Component c, List<Player> players) {
        for (Player p : players) if (matches(c, p)) return true;
        return false;
    }

    private boolean matches(Component c, Player p) {
        String uuid = p.getUniqueId().toString();
        if (uuid.equals(c.insertion())) return true;

        HoverEvent<?> he = c.hoverEvent();
        if (he != null && he.action() == HoverEvent.Action.SHOW_ENTITY
                && he.value() instanceof HoverEvent.ShowEntity se
                && p.getUniqueId().equals(se.id())) {
            return true;
        }
        // Fallback: Komponente besteht nur aus dem Namen
        return PlainTextComponentSerializer.plainText().serialize(c).equals(p.getName());
    }
}
