package de.elia.cameraplugin.camfly2;

import de.elia.cameraplugin.body.BodySpawner;
import de.elia.cameraplugin.body.BodyWatch;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.ConfigFile;
import de.elia.cameraplugin.config.ConfigIssue;
import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.display.CamActionBar;
import de.elia.cameraplugin.display.CamParticles;
import de.elia.cameraplugin.display.SightGlow;
import de.elia.cameraplugin.feuer.CamFireGuard;
import de.elia.cameraplugin.ghast.CamGhastGuard;
import de.elia.cameraplugin.hunger.CamHungerGuard;
import de.elia.cameraplugin.hunger.CamRegenGuard;
import de.elia.cameraplugin.interaction.CamInteractionGuard;
import de.elia.cameraplugin.inventory.CamInventoryGuard;
import de.elia.cameraplugin.inventory.CamInventoryLock;
import de.elia.cameraplugin.log.ConsoleLog;
import de.elia.cameraplugin.mirrordamage.DamageMirror;
import de.elia.cameraplugin.mob.MobTargeting;
import de.elia.cameraplugin.movement.CamMovementGuard;
import de.elia.cameraplugin.potion.CamPotionGuard;
import de.elia.cameraplugin.scoreboard.CamModeObjective;
import de.elia.cameraplugin.scoreboard.NoCollisionTeam;
import de.elia.cameraplugin.session.CameraHead;
import de.elia.cameraplugin.session.CameraMode;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.SessionListener;
import de.elia.cameraplugin.start.StartChecks;
import de.elia.cameraplugin.timelimit.CamTimeLimit;
import de.elia.cameraplugin.visibility.CamVisibility;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The plugin itself: it starts and stops everything camera mode is made of.
 * What camera mode does lives in the packages next to this one, each topic in
 * its own.
 */
@SuppressWarnings("removal")
public final class CameraPlugin extends JavaPlugin {

    private final ConsoleLog log = new ConsoleLog(this);
    private final Messages messages = new Messages(this);
    private final ConfigFile configFile = new ConfigFile(this, log, messages);
    private final CamSettings settings = new CamSettings(this, log);
    private final CameraPlayers cameraPlayers = new CameraPlayers();

    private final NoCollisionTeam noCollisionTeam = new NoCollisionTeam(settings, cameraPlayers);
    private final CamModeObjective camModeObjective = new CamModeObjective();
    private final CamVisibility visibility = new CamVisibility(this, settings, cameraPlayers);
    private final CamParticles particles = new CamParticles(this, settings, cameraPlayers);
    private final SightGlow sightGlow = new SightGlow(this, settings, cameraPlayers);
    private final CamActionBar actionBar = new CamActionBar(this);
    private final MobTargeting mobTargeting = new MobTargeting(this, settings, cameraPlayers);
    private final BodyWatch bodyWatch = new BodyWatch(this);
    private final CamTimeLimit timeLimit = new CamTimeLimit(this);
    private final StartChecks startChecks = new StartChecks(settings, messages);
    private final CamMovementGuard movementGuard = new CamMovementGuard(this);
    private final CameraMode cameraMode = new CameraMode(this);

    private CamFireGuard camFireGuard;
    private CamHungerGuard camHungerGuard;
    private CamGhastGuard camGhastGuard;
    private CamInventoryGuard camInventoryGuard;
    private BodySpawner bodySpawner;
    private boolean shuttingDown = false;
    /** Whether the start got past the config file and actually set anything up. */
    private boolean startedUp = false;

    @Override
    public void onEnable() {
        shuttingDown = false;
        saveDefaultConfig();
        // Read before anything is set up, and not left to the first getConfig():
        // Bukkit would answer a file it cannot parse with a stack trace and an
        // empty configuration. A file that cannot be read at all stops the
        // start here - camera mode on settings nobody wrote down is worse than
        // no camera mode, and the file has to be repaired either way. Single
        // values that do not fit are a different matter: they fall back one by
        // one, are listed below, and the plugin starts.
        if (!configFile.readConfigInto(null)) {
            log.log(Level.SEVERE, ChatColor.stripColor(messages.configMessage("config-start-failed",
                    "&cStart fehlgeschlagen. Zum Aktivieren den Fehler in der Konfiguration"
                            + " beheben und den Server neu starten.")));
            unregisterCommands();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        camHungerGuard = new CamHungerGuard(this);
        // Created before the values are read, so its own go through the same
        // load and its notes end up in the same report.
        camFireGuard = new CamFireGuard(this);
        camGhastGuard = new CamGhastGuard(this);
        camInventoryGuard = new CamInventoryGuard(this, this::isInCameraMode);
        configFile.reportConfigWarnings(loadConfigValues(), null);
        bodySpawner = new BodySpawner(this, settings, messages, log);
        camModeObjective.setUp();
        bodySpawner.removeLeftoverEntities();
        warmUpProfileService();
        // Beim Start ist niemand im Cam-Modus -> ein uebrig gebliebenes Team entfernen.
        noCollisionTeam.deleteNoCollisionTeam();
        this.getCommand("cam").setExecutor(new CamCommand(this));
        this.getCommand("cam").setTabCompleter(new CamTabCompleter());
        registerListeners();
        noCollisionTeam.refreshNoCollisionTeam();
        startedUp = true;
        getLogger().info("CameraPlugin wurde aktiviert!");
    }

    /** Hands every event handler of camera mode to the server. */
    private void registerListeners() {
        PluginManager pluginManager = this.getServer().getPluginManager();
        List<Listener> listeners = List.of(
                new DamageMirror(this),
                new CamInteractionGuard(this),
                mobTargeting,
                new CamRegenGuard(cameraPlayers),
                startChecks,
                new SessionListener(this),
                movementGuard,
                new CamInventoryLock(cameraPlayers),
                new CamPotionGuard(this),
                new CamSuggestionFilter(this));
        for (Listener listener : listeners) {
            pluginManager.registerEvents(listener, this);
        }
    }

    /**
     * Touches the profile handling once while the server is still starting.
     *
     * <p>The first time the server works with a skin, Mojang's authlib logs its
     * environment ("Environment[sessionHost=...]"). Without this that line lands
     * in the middle of the game, right after the first /cam, because that is
     * when the body gets the player's head and skin.</p>
     *
     * <p>A bare profile is not enough to set that off - one carrying a texture
     * is, and building the camera head does exactly that. It is therefore built
     * once here and thrown away.</p>
     *
     * <p>The work is framed by two lines of its own, so the authlib line in
     * between reads as part of loading the skins instead of standing there
     * without context.</p>
     *
     * <p>Nothing depends on this, so anything that goes wrong is only reported:
     * at worst the line appears later, as it did before.</p>
     */
    private void warmUpProfileService() {
        logStartupMessage(Level.INFO, "startup-skins-loading", "Skins werden geladen...", null);
        try {
            CameraHead.create(getLogger());
        } catch (RuntimeException ex) {
            // Purely cosmetic, the log line is not worth a failed start.
            logStartupMessage(Level.WARNING, "startup-skins-failed",
                    "Skins konnten beim Start nicht vorgeladen werden: {error}", ex.toString());
            return;
        }
        logStartupMessage(Level.INFO, "startup-skins-loaded", "Skins wurden erfolgreich geladen.", null);
    }

    /**
     * Writes a startup line from the config file into the console. An empty
     * entry switches the line off; {@code {error}} is replaced when a reason is
     * given.
     */
    private void logStartupMessage(Level level, String key, String fallback, String error) {
        String text = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&',
                getConfig().getString("messages." + key, fallback)));
        if (text.isEmpty()) {
            return;
        }
        log.log(level, error == null ? text : text.replace("{error}", error));
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
        if (!startedUp) {
            // The start stopped at the config file, so there is nothing set up
            // that would have to be taken down.
            return;
        }
        // Erstellt eine Kopie der Keys, um ConcurrentModificationException zu vermeiden
        for (UUID playerId : new HashSet<>(cameraPlayers.ids())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                exitCameraMode(player);
            }
        }
        if (camFireGuard != null) {
            camFireGuard.onDisable();
        }
        if (camInventoryGuard != null) {
            camInventoryGuard.onDisable();
        }
        if (camGhastGuard != null) {
            camGhastGuard.onDisable();
        }
        particles.onDisable();
        sightGlow.onDisable();
        actionBar.onDisable();
        timeLimit.onDisable();
        // Kein Spieler mehr im Cam-Modus -> Team entfernen.
        noCollisionTeam.deleteNoCollisionTeam();
        bodySpawner.removeLeftoverEntities();
        getLogger().info("CameraPlugin wurde deaktiviert!");
    }

    /** The config file as it was last read, see {@link ConfigFile#readConfigInto}. */
    @Override
    public FileConfiguration getConfig() {
        return configFile.getConfig();
    }

    /**
     * Reads the config file again, without a player waiting for an answer.
     *
     * <p>Overridden because Bukkit answers a file it cannot parse with an empty
     * configuration and a stack trace in the log: every single setting would
     * quietly fall back to its default, and a {@code /cam reload} would still
     * report success. Here such a file changes nothing, and the reason is said
     * in one line.</p>
     */
    @Override
    public void reloadConfig() {
        configFile.readConfigInto(null);
    }

    /**
     * Reads every value out of the config file.
     *
     * @return a note for each value that did not fit and was replaced
     */
    private List<ConfigIssue> loadConfigValues() {
        ConfigReader config = new ConfigReader(getConfig());
        settings.load(config);
        if (camFireGuard != null) {
            camFireGuard.loadConfig(config);
        }
        return config.getWarnings();
    }

    /**
     * Reads the config file again and puts everything that depends on it back
     * together.
     *
     * <p>The file is read first, before anybody is disturbed: a file with a
     * mistake in it changes nothing at all then, and whoever is in camera mode
     * stays there.</p>
     *
     * @return whether the reload went through
     */
    public boolean reloadPlugin(Player initiator) {
        if (!configFile.readConfigInto(initiator)) {
            return false;
        }
        for (UUID uuid : new HashSet<>(cameraPlayers.ids())) {
            Player camPlayer = Bukkit.getPlayer(uuid);
            if (camPlayer != null) {
                sendConfiguredMessage(camPlayer, "reload-exit");
                exitCameraMode(camPlayer);
            }
        }
        configFile.reportConfigWarnings(loadConfigValues(), initiator);
        noCollisionTeam.refreshNoCollisionTeam();
        timeLimit.clearCooldowns();
        return true;
    }

    /**
     * Takes the plugin's command out of the list the server answers from.
     *
     * <p>A command out of the plugin.yml stays registered even after the plugin
     * has been switched off, and {@code PluginCommand} then answers it with an
     * exception and a stack trace in the log - before a single line of this
     * plugin is reached, so it cannot be caught from inside. Taken out here, a
     * {@code /cam} is simply an unknown command while the plugin is off, which
     * is the truth.</p>
     *
     * <p>Done by reflection because only Paper hands out that list
     * ({@code Server#getCommandMap()}); the Spigot API the plugin is built
     * against does not have it. Where it is missing nothing happens, and the
     * command keeps answering the way the server means it to.</p>
     */
    private void unregisterCommands() {
        PluginCommand command = getCommand("cam");
        if (command == null) {
            return;
        }
        try {
            Object map = Bukkit.getServer().getClass().getMethod("getCommandMap")
                    .invoke(Bukkit.getServer());
            Object known = map.getClass().getMethod("getKnownCommands").invoke(map);
            if (known instanceof Map<?, ?> commands) {
                // Takes the plain name and every alias with it, whatever they
                // are called.
                commands.values().removeIf(entry -> entry == command);
            }
            if (map instanceof CommandMap commandMap) {
                command.unregister(commandMap);
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.log(Level.WARNING, "Der Befehl /cam konnte nicht abgemeldet werden, er antwortet"
                    + " deshalb mit einem Fehler des Servers: " + ex);
        }
    }

    // ------------------------------------------------------------------------
    // What CamCommand asks for
    // ------------------------------------------------------------------------

    public void enterCameraMode(Player player) {
        cameraMode.enterCameraMode(player);
    }

    public void exitCameraMode(Player player) {
        cameraMode.exitCameraMode(player);
    }

    public boolean isInCameraMode(Player player) {
        return cameraPlayers.contains(player.getUniqueId());
    }

    public String getMessage(String path) {
        return messages.getMessage(path);
    }

    public boolean isMessageEnabled(String path) {
        return messages.isMessageEnabled(path);
    }

    public void sendConfiguredMessage(CommandSender sender, String path) {
        messages.sendConfiguredMessage(sender, path);
    }

    public String formatDuration(long seconds) {
        return CamTimeLimit.formatDuration(seconds);
    }

    public boolean isCooldownActive(Player player) {
        return timeLimit.isCooldownActive(player);
    }

    public long getCooldownRemaining(Player player) {
        return timeLimit.getCooldownRemaining(player);
    }

    /** See {@link StartChecks#checkCamSafety(Player)}. */
    public boolean checkCamSafety(Player player) {
        return startChecks.checkCamSafety(player);
    }

    /** See {@link StartChecks#checkCamSpectator(Player)}. */
    public boolean checkCamSpectator(Player player) {
        return startChecks.checkCamSpectator(player);
    }

    /** See {@link StartChecks#checkCamEffects(Player)}. */
    public boolean checkCamEffects(Player player) {
        return startChecks.checkCamEffects(player);
    }

    /** See {@link StartChecks#checkCamArea(Player)}. */
    public boolean checkCamArea(Player player) {
        return startChecks.checkCamArea(player);
    }

    // ------------------------------------------------------------------------
    // The parts, for each other
    // ------------------------------------------------------------------------

    /** Whether the plugin is being switched off right now. */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public CamSettings getSettings() {
        return settings;
    }

    public Messages getMessages() {
        return messages;
    }

    public CameraPlayers getCameraPlayers() {
        return cameraPlayers;
    }

    public BodySpawner getBodySpawner() {
        return bodySpawner;
    }

    public BodyWatch getBodyWatch() {
        return bodyWatch;
    }

    public MobTargeting getMobTargeting() {
        return mobTargeting;
    }

    public CamParticles getParticles() {
        return particles;
    }

    public SightGlow getSightGlow() {
        return sightGlow;
    }

    public CamActionBar getActionBar() {
        return actionBar;
    }

    public CamFireGuard getFireGuard() {
        return camFireGuard;
    }

    public CamHungerGuard getHungerGuard() {
        return camHungerGuard;
    }

    public CamGhastGuard getGhastGuard() {
        return camGhastGuard;
    }

    public CamInventoryGuard getInventoryGuard() {
        return camInventoryGuard;
    }

    public NoCollisionTeam getNoCollisionTeam() {
        return noCollisionTeam;
    }

    public CamVisibility getVisibility() {
        return visibility;
    }

    public CamModeObjective getCamModeObjective() {
        return camModeObjective;
    }

    public CamTimeLimit getTimeLimit() {
        return timeLimit;
    }

    public StartChecks getStartChecks() {
        return startChecks;
    }

    public CamMovementGuard getMovementGuard() {
        return movementGuard;
    }
}
