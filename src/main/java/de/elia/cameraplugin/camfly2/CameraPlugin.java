package de.elia.cameraplugin.camfly2;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.block.BlockReceiveGameEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityRegainHealthEvent.RegainReason;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.block.Block;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import de.elia.cameraplugin.mirrordamage.ArmorDamageMode;
import de.elia.cameraplugin.mirrordamage.ArmorWear;
import de.elia.cameraplugin.mirrordamage.DamageMode;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import de.elia.cameraplugin.feuer.CamFireGuard;
import de.elia.cameraplugin.ghast.CamGhastGuard;
import de.elia.cameraplugin.hunger.CamHungerGuard;
import de.elia.cameraplugin.body.BodyType;
import de.elia.cameraplugin.body.EquipmentVisibility;
import de.elia.cameraplugin.body.MannequinLabel;
import de.elia.cameraplugin.body.MannequinSkin;
import de.elia.cameraplugin.body.MobHeads;
import de.elia.cameraplugin.body.MobTargetMode;
import de.elia.cameraplugin.body.MovementSensitivity;
import de.elia.cameraplugin.area.CamAreaRules;
import de.elia.cameraplugin.portal.PortalKind;
import de.elia.cameraplugin.portal.PortalReturn;
import de.elia.cameraplugin.portal.PortalRules;
import de.elia.cameraplugin.config.ConfigIssue;
import de.elia.cameraplugin.config.ConfigReader;

import static org.bukkit.Sound.ENTITY_ITEM_BREAK;

@SuppressWarnings("removal")
public final class CameraPlugin extends JavaPlugin implements Listener {

    /** The config file as it was last read, see {@link #readConfigInto}. */
    private FileConfiguration config;
    private final Map<UUID, CameraData> cameraPlayers = new HashMap<>();
    private final Map<UUID, Long> distanceMessageCooldown = new HashMap<>();
    /** Players who were just told that camera mode is not allowed where they are heading. */
    private final Map<UUID, Long> areaMessageCooldown = new HashMap<>();
    /** Players who were just told that a portal does not let them through. */
    private final Map<UUID, Long> portalMessageCooldown = new HashMap<>();
    /** The player who is taking the hit his body took right now. */
    private final Set<UUID> damageImmunityBypass = new HashSet<>();
    /** Players whose body was hit and whose hit has not reached them yet. */
    private final Set<UUID> pendingMirrorHit = new HashSet<>();
    private final Map<UUID, UUID> bodyOwners = new HashMap<>();
    private final Map<UUID, UUID> hitboxEntities = new HashMap<>();
    private final Set<UUID> pendingDamage = new HashSet<>();
    private CamFireGuard camFireGuard;
    private CamHungerGuard camHungerGuard;
    private CamGhastGuard camGhastGuard;
    private double particleHeight;
    private int particlesPerTick;
    private boolean showOwnParticles;
    private final Map<UUID, BukkitRunnable> particleTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> sightGlowTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> mobTargetTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> actionBarTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> offMessageTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> timeLimitTasks = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Long> camCooldowns = new HashMap<>();
    private final Map<UUID, BukkitRunnable> cooldownTasks = new HashMap<>();
    private final Map<UUID, Long> lastDamageTimes = new HashMap<>();
    private boolean shuttingDown = false;
    /** Whether the start got past the config file and actually set anything up. */
    private boolean startedUp = false;
    /** Whether the missing way to hide the "NPC" line has already been reported. */
    private boolean mannequinLabelReported = false;
    private NamespacedKey bodyKey;
    private NamespacedKey hitboxKey;
    private NamespacedKey hiddenArmorAsset;
    /** Armour slots in the order of {@link org.bukkit.inventory.PlayerInventory#getArmorContents()}. */
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };
    private static final String CAM_OBJECTIVE = "cam_mode";
    private org.bukkit.scoreboard.Objective camModeObjective;

    private boolean actionBarEnabled;
    private String actionBarOnMessage;
    private String actionBarOffMessage;
    private int actionBarOffDuration;

    private static final String NO_COLLISION_TEAM = "cam_no_push";

    /**
     * Smallest threshold that is accepted for {@code body.move-threshold}, in
     * blocks. Anything below is raised to this value.
     */
    private static final double MIN_MOVE_THRESHOLD = 0.01;

    /**
     * How long a mirrored hit waits at most for the player to live through a
     * tick, so that it never hangs should he stop ticking altogether.
     */
    private static final int MAX_ARMOR_WAIT_TICKS = 10;

    /**
     * How much of the reason a broken config file gives is passed on. The rest
     * is cut off: a YAML error carries the offending line and a caret under it
     * and would otherwise fill the chat.
     */
    private static final int MAX_CONFIG_ERROR_LENGTH = 200;

    /**
     * How many config notes are sent into the chat of the player who reloaded.
     * The rest is only in the console, so that a thoroughly broken file does not
     * bury the chat.
     */
    private static final int MAX_CHAT_WARNINGS = 8;

    /**
     * How often {@code glowing-outline: sight} checks whether somebody has the
     * camera player in sight, in ticks.
     */
    private static final long SIGHT_GLOW_INTERVAL = 5L;

    /**
     * How often {@code body.mob-target} looks around the body for hostile mobs
     * to send after it, in ticks.
     */
    private static final long MOB_TARGET_INTERVAL = 20L;

    /**
     * The range of a mob that carries no follow range attribute, in blocks.
     * That is what most mobs have in vanilla.
     */
    private static final double DEFAULT_FOLLOW_RANGE = 16.0;

    /**
     * How far the search around the body reaches at most, in blocks. No mob in
     * vanilla notices anything from further away, so looking further would only
     * cost time.
     */
    private static final double MAX_MOB_TARGET_RANGE = 64.0;

    /** What a mob head on the body leaves of the range of that kind of mob. */
    private static final double MOB_HEAD_SIGHT_FACTOR = 0.5;

    /** What the server console writes with, see {@link #consoleCharset()}. */
    private static final Charset CONSOLE_CHARSET = consoleCharset();

    /**
     * How long a portal is left alone after it turned a camera player away, in
     * ticks. Without it the server would try the very same trip again straight
     * away - an end portal is stepped on and not waited in, so it would ask
     * every single tick - and the player would stand in a portal that tells him
     * off without end.
     *
     * <p>The same waiting time is put on a player who is brought back, in case
     * he is set down in a portal himself: the one he set out through.</p>
     */
    private static final int PORTAL_COOLDOWN_TICKS = 100;

    // Configurable values
    private boolean maxDistanceEnabled;
    private double maxDistance;
    private int distanceWarningCooldown;
    private boolean bodyNameVisible;
    private boolean bodyVisible;
    /** Whether the armour the body wears is drawn, {@code body.armor-visible}. */
    private boolean bodyArmorVisible;
    private BodyType bodyType;
    private MovementSensitivity movementSensitivity;
    /** {@code body.move-threshold} squared, so the square root can be skipped. */
    private double moveThresholdSquared;
    /** Which range decides whether a mob notices the body. */
    private MobTargetMode mobTargetMode;
    /** The range of the mode {@code custom}, in blocks. */
    private double mobTargetRadius;
    /** Whether a mob head on the body halves the range of that kind of mob. */
    private boolean mobTargetHeads;
    /** Where camera mode may be started and flown, the section {@code cam-area}. */
    private final CamAreaRules camAreaRules = new CamAreaRules();
    /** Whether a portal lets a camera player through, the section {@code portals}. */
    private final PortalRules portalRules = new PortalRules();
    private VisibilityMode playerVisibilityMode;
    private boolean allowInvisibilityPotion;
    private GlowMode glowMode;
    private boolean allowLavaFlight;
    private Object Sound;

    // Damage transfer settings
    private DamageMode damageMode;
    /** How hard the transferred hit wears down the player's armour. */
    private ArmorDamageMode armorDamageMode;
    /** Durability points per hit in the armour mode "custom". */
    private int customArmorDamage;
    /** Whether the Unbreaking enchantment counts against that wear. */
    private boolean respectUnbreaking;
    /** Whether the player's armour still takes its share off the damage. */
    private boolean damageCountsArmor;
    /** Whether the transferred hit pushes the player back. */
    private boolean mirrorKnockback;
    /** Whether every transferred hit reports its numbers, for measuring. */
    private boolean mirrorDebug;
    private double customDamageHearts;

    private boolean cameraHeadEnabled;
    // Time limit and cooldown settings
    private boolean timeLimitEnabled;
    private boolean cooldownsEnabled;
    private int durationSeconds;
    private int cooldownSeconds;
    private boolean showBossbar;
    private BarColor bossbarColor;
    private String bossbarText;
    private String cooldownText;
    private String cooldownAvailableText;

    // Camera safety settings
    private boolean camSafetyEnabled;
    private int camSafetyDelay;
    private String camSafetyMessage;

    private enum VisibilityMode { CAM, ALL, NONE }

    /** What {@code camera-mode.start-with-effects} allows him to start with. */
    private EffectStart startWithEffects;

    /** The values of {@code camera-mode.glowing-outline}: true, false and sight. */
    private enum GlowMode { ALWAYS, OFF, SIGHT }

    /**
     * The values of {@code camera-mode.start-with-effects}: true, false and
     * positive.
     */
    private enum EffectStart { ANY, NONE, POSITIVE }

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
        if (!readConfigInto(null)) {
            log(Level.SEVERE, ChatColor.stripColor(configMessage("config-start-failed",
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
        reportConfigWarnings(loadConfigValues(), null);
        bodyKey = new NamespacedKey(this, "cam_body");
        hitboxKey = new NamespacedKey(this, "cam_hitbox");
        // Deliberately not a real equipment asset: the client finds nothing for
        // it and therefore draws nothing.
        hiddenArmorAsset = new NamespacedKey(this, "hidden_armor");
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        camModeObjective = scoreboard.getObjective(CAM_OBJECTIVE);
        if (camModeObjective == null) {
            camModeObjective = scoreboard.registerNewObjective(CAM_OBJECTIVE, Criteria.DUMMY, "Cam Mode");
        }
        for (String entry : scoreboard.getEntries()) {
            camModeObjective.getScore(entry).setScore(0);
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            camModeObjective.getScore(p.getName()).setScore(0);
        }
        removeLeftoverEntities();
        warmUpProfileService();
        // Beim Start ist niemand im Cam-Modus -> ein uebrig gebliebenes Team entfernen.
        deleteNoCollisionTeam();
        this.getCommand("cam").setExecutor(new CamCommand(this));
        this.getCommand("cam").setTabCompleter(new CamTabCompleter());
        this.getServer().getPluginManager().registerEvents(this, this);
        refreshNoCollisionTeam();
        startedUp = true;
        getLogger().info("CameraPlugin wurde aktiviert!");
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
            createCameraHead();
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
        log(level, error == null ? text : text.replace("{error}", error));
    }

    /**
     * Puts one line into the server console, written so that console can
     * actually print it, see {@link #forConsole(String)}.
     */
    private void log(Level level, String text) {
        getLogger().log(level, forConsole(text));
    }

    /**
     * The charset the server console writes its lines with, which decides
     * whether an umlaut survives the way out.
     */
    private static Charset consoleCharset() {
        // Deliberately not System.out: the server puts a stream of its own in
        // its place early on, and that one answers with the charset of the log
        // framework rather than the one the line is written out with. These two
        // properties carry the charset of the console itself.
        for (String property : new String[] {"stdout.encoding", "native.encoding"}) {
            String name = System.getProperty(property);
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                return Charset.forName(name);
            } catch (RuntimeException ignored) {
                // A name this Java does not know - the next one counts.
            }
        }
        // Nothing to go by: rewrite the umlauts rather than risk question marks.
        return StandardCharsets.US_ASCII;
    }

    /**
     * The same sentence, written so that the server console can print it.
     *
     * <p>A console that cannot write umlauts turns every one of them into a
     * question mark - "Falscher Wert f?r ..." instead of "für". Which
     * characters it can write is decided by how the server was started and not
     * by this plugin, so the sentence is rewritten only when it really would
     * not survive: ä becomes ae, ß becomes ss, and whatever is left over loses
     * its accent. A console that can write them gets the sentence untouched,
     * and the chat keeps the umlauts either way.</p>
     */
    private static String forConsole(String text) {
        if (CONSOLE_CHARSET.newEncoder().canEncode(text)) {
            return text;
        }
        String plain = text
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
                .replace("Ä", "Ae").replace("Ö", "Oe").replace("Ü", "Ue")
                .replace("ß", "ss");
        if (CONSOLE_CHARSET.newEncoder().canEncode(plain)) {
            return plain;
        }
        // Alles Übrige verliert seine Zeichen: é wird e, ein Zeichen ohne
        // Entsprechung bleibt und wird zum Fragezeichen wie bisher.
        return Normalizer.normalize(plain, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
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
        for (UUID playerId : new HashSet<>(cameraPlayers.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                exitCameraMode(player);
            }
        }
        if (camFireGuard != null) {
            camFireGuard.onDisable();
        }
        if (camGhastGuard != null) {
            camGhastGuard.onDisable();
        }
        for (BukkitRunnable task : particleTasks.values()) {
            task.cancel();
        }
        particleTasks.clear();
        for (BukkitRunnable task : sightGlowTasks.values()) {
            task.cancel();
        }
        sightGlowTasks.clear();
        for (BukkitRunnable task : actionBarTasks.values()) {
            task.cancel();
        }
        actionBarTasks.clear();
        for (BukkitRunnable task : offMessageTasks.values()) {
            task.cancel();
        }
        offMessageTasks.clear();
        for (BukkitRunnable task : timeLimitTasks.values()) {
            task.cancel();
        }
        timeLimitTasks.clear();
        for (BukkitRunnable task : cooldownTasks.values()) {
            task.cancel();
        }
        cooldownTasks.clear();
        for (BossBar bar : bossBars.values()) {
            bar.removeAll();
        }
        // Kein Spieler mehr im Cam-Modus -> Team entfernen.
        deleteNoCollisionTeam();
        removeLeftoverEntities();
        getLogger().info("CameraPlugin wurde deaktiviert!");
    }


    public void enterCameraMode(Player player) {
        // *** Inventar und Rüstung speichern ***
        PlayerInventory playerInventory = player.getInventory();
        ItemStack[] originalInventory = playerInventory.getContents();
        ItemStack[] originalArmor = playerInventory.getArmorContents();
        Collection<PotionEffect> pausedEffects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            pausedEffects.add(effect);
            player.removePotionEffect(effect.getType());
        }
        boolean originalGlowing = player.isGlowing();
        int originalRemainingAir = player.getRemainingAir();

        // *** Inventar und Rüstung leeren ***
        playerInventory.clear();
        playerInventory.setArmorContents(new ItemStack[4]);// Leeres Array für Rüstungsslots
        player.updateInventory();

        Location playerLocation = player.getLocation();

        LivingEntity body = spawnCameraBody(player, playerLocation, originalRemainingAir, originalArmor);

        // A mannequin takes the hits for both body types and is the entity the
        // movement check watches. It carries the player's armour so the body
        // looks like him; the damage itself is calculated on the player, see
        // onBodyDamage.
        Mannequin hitbox = null;
        if (usesMannequinBody()) {
            EntityEquipment bodyEquipment = body.getEquipment();
            if (bodyEquipment != null) {
                // Here body and hitbox are one and the same entity, so it wears
                // the armour either way: shown the way the player wears it, or
                // with its rendering taken away.
                bodyEquipment.setArmorContents(showsBodyArmor()
                        ? createMirrorArmor(originalArmor)
                        : createHiddenArmor(originalArmor));
            }
        } else {
            // The mannequin next to the armour stand is invisible, so it wears
            // copies whose armour is not rendered either. What is seen of the
            // armour hangs on the armour stand, see spawnArmorStandBody.
            hitbox = spawnHitbox(player, playerLocation, createHiddenArmor(originalArmor));
            hitbox.teleport(body.getLocation());
        }
        // The mannequin is the entity that takes the hits.
        LivingEntity damageTarget = hitbox != null ? hitbox : body;

        GameMode originalGameMode = player.getGameMode();
        boolean originalAllowFlight = player.getAllowFlight();
        boolean originalFlying = player.isFlying();

        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        if (glowMode == GlowMode.ALWAYS && playerVisibilityMode != VisibilityMode.NONE) {
            // The invisibility takes the body away, the outline puts a visible
            // shape back - that is what everyone else sees of the camera player.
            // In mode NONE nobody is meant to see him, so an outline would give
            // away exactly what that mode hides. For "sight" the outline is
            // switched by startSightGlow instead.
            player.setGlowing(true);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                player.setGameMode(GameMode.ADVENTURE);
                player.setAllowFlight(true); // ensure flight remains enabled
                player.setFlying(true);       // keep player flying
                if (needsInvisibility() && !player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
                }
            }
        }.runTaskLater(this, 1L);

        double reaggroRadius = 64.0;
        for (Entity entity : player.getNearbyEntities(reaggroRadius, reaggroRadius, reaggroRadius)) {
            if (entity instanceof Mob mob && player.equals(mob.getTarget())) {
                // Aggro away from the player: onto his body, or nowhere at all -
                // when the body is out of the reach of that mob, and in the mode
                // off, where nobody is handed the body at all.
                boolean toBody = mobTargetMode.attractsMobs() && noticesBody(mob, damageTarget);
                mob.setTarget(toBody ? damageTarget : null);
            }
        }

        // *** Gespeichertes Inventar an CameraData übergeben ***
        cameraPlayers.put(player.getUniqueId(), new CameraData(body, hitbox, originalGameMode, originalAllowFlight, originalFlying, originalGlowing, originalInventory, originalArmor, pausedEffects, originalRemainingAir));
        bodyOwners.put(body.getUniqueId(), player.getUniqueId());
        if (hitbox != null) {
            hitboxEntities.put(hitbox.getUniqueId(), player.getUniqueId());
        }

        startCameraParticles(player);
        startSightGlow(player);
        startActionBar(player);
        camFireGuard.startFor(player);
        camHungerGuard.startFor(player);
        camGhastGuard.startFor(player);
        // The entity taking the hits is the mannequin for both body types, so the
        // movement check always runs on it. Both calls look at the sensitivity
        // level and only one of them does anything.
        startBodyMovementCheck(player, damageTarget);
        startBodyPin(player, body, hitbox);
        startMobTargeting(player, damageTarget);
        addPlayerToNoCollisionTeam(player);
        // Team wurde evtl. gerade neu erstellt -> alle Mitglieder neu setzen.
        refreshNoCollisionTeam();
        updateVisibilityForAll();
        if (camModeObjective != null) {
            camModeObjective.getScore(player.getName()).setScore(1);
        }
        startTimeLimit(player);

        if (cameraHeadEnabled) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    ItemStack camHead = createCameraHead();
                    player.getInventory().setHelmet(camHead);
                }
            }.runTaskLater(this, 2L); // delay to ensure armour is restored
        }
    }

    /**
     * Copies the player's armour for the visible body. Copies are all it takes:
     * the pieces are only worn there, the damage is calculated on the player
     * himself and his own armour is what wears out.
     */
    private ItemStack[] createMirrorArmor(ItemStack[] originalArmor) {
        ItemStack[] mirrorArmor = new ItemStack[originalArmor.length];
        for (int i = 0; i < originalArmor.length; i++) {
            if (originalArmor[i] != null) {
                mirrorArmor[i] = originalArmor[i].clone();
            }
        }
        return mirrorArmor;
    }

    /**
     * Copies the armour the armour stand of body type 1 shows. Everything but
     * the helmet: the player's head sits in that slot and is what the body is
     * recognised by. The helmet itself is worn by the mannequin standing in the
     * body, hidden, so a mob head still shortens the range of its kind of mob.
     */
    private ItemStack[] createArmorStandArmor(ItemStack[] originalArmor) {
        ItemStack[] worn = createMirrorArmor(originalArmor);
        for (int i = 0; i < worn.length && i < ARMOR_SLOTS.length; i++) {
            if (ARMOR_SLOTS[i] == EquipmentSlot.HEAD) {
                worn[i] = null;
            }
        }
        return worn;
    }

    /**
     * Copies the player's armour for a mannequin that is not to show it and
     * takes away its rendering: the invisible mannequin, whose pieces would
     * otherwise float in front of the armour stand, and the visible one when
     * {@code body.armor-visible} is off.
     *
     * <p>Copies are enough here: the body never really takes the damage, its
     * damage event is cancelled. The reduction and the durability are both
     * taken from the player's own armour when the hit is passed on to him, and
     * the pieces on the mannequin change nothing about either.</p>
     */
    private ItemStack[] createHiddenArmor(ItemStack[] originalArmor) {
        ItemStack[] hiddenArmor = new ItemStack[originalArmor.length];
        boolean stillVisible = false;
        for (int i = 0; i < originalArmor.length && i < ARMOR_SLOTS.length; i++) {
            if (originalArmor[i] == null) {
                continue;
            }
            ItemStack copy = originalArmor[i].clone();
            if (!EquipmentVisibility.hide(copy, ARMOR_SLOTS[i], hiddenArmorAsset)) {
                stillVisible = true;
            }
            hiddenArmor[i] = copy;
        }
        if (stillVisible) {
            log(Level.WARNING, "Die Rüstung des Mannequins konnte nicht ausgeblendet werden, "
                    + "sie bleibt am Körper sichtbar. Setter: " + EquipmentVisibility.describeAssetSetter());
        }
        return hiddenArmor;
    }

    /**
     * Creates the invisible mannequin that takes the hits for a body that is
     * not a mannequin itself. A mannequin has the same hitbox as a player, so
     * hits land on the body the way they would land on the player himself.
     *
     * <p>It is invisible and its armour is not rendered either, but it is a
     * normal entity otherwise: players, mobs and the world hit it directly, just
     * like the visible mannequin of body type 2.</p>
     */
    private Mannequin spawnHitbox(Player player, Location location, ItemStack[] mirrorArmor) {
        Mannequin hitbox = (Mannequin) location.getWorld().spawnEntity(location, EntityType.MANNEQUIN);
        EntityEquipment equipment = hitbox.getEquipment();
        if (equipment != null) {
            equipment.setArmorContents(mirrorArmor);
        }
        hitbox.getPersistentDataContainer().set(hitboxKey, PersistentDataType.INTEGER, 1);
        hideMannequin(hitbox);
        hitbox.setSilent(true);
        // It stands inside the armour stand and therefore has to behave the same
        // way: whatever moves the one moves the other. Only then does the
        // movement check on the mannequin notice that the body has fallen.
        hitbox.setGravity(useBodyGravity());
        applyMovementSensitivity(hitbox);
        hitbox.setInvulnerable(false);
        hitbox.setCustomName(getMessage("hitbox.name-format").replace("{player}", player.getName()));
        hitbox.setCustomNameVisible(false);
        hideMannequinDescription(hitbox);
        hitbox.setCanPickupItems(false);
        return hitbox;
    }

    /**
     * Spawns the body that stays behind while the player is in camera mode:
     * either a mannequin that uses the player's own skin or an armour stand
     * wearing his head, see {@link #usesMannequinBody()}. An invisible body
     * gets neither the skin nor the head, only its name stays above it.
     *
     * <p>The armour stand puts the armour on right here, the mannequin gets it
     * from the caller: it is the entity taking the hits as well and therefore
     * wears the pieces even when they are not meant to be seen.</p>
     */
    private LivingEntity spawnCameraBody(Player player, Location location, int remainingAir,
                                         ItemStack[] originalArmor) {
        LivingEntity body = usesMannequinBody()
                ? spawnMannequinBody(player, location)
                : spawnArmorStandBody(player, location, originalArmor);

        body.setRemainingAir(remainingAir);
        body.getPersistentDataContainer().set(bodyKey, PersistentDataType.INTEGER, 1);
        body.setGravity(useBodyGravity());
        body.setCanPickupItems(false);
        applyBodyName(body, player);
        body.setInvulnerable(false);
        AttributeInstance maxHealth = body.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(20.0);
            body.setHealth(20.0);
        }
        return body;
    }

    /** Creates a mannequin that shows the player's own skin. */
    private Mannequin spawnMannequinBody(Player player, Location location) {
        Mannequin mannequin = (Mannequin) location.getWorld().spawnEntity(location, EntityType.MANNEQUIN);
        if (!MannequinSkin.apply(mannequin, player)) {
            log(Level.WARNING, "Der Skin von " + player.getName()
                    + " konnte nicht auf das Mannequin übertragen werden, es benutzt den Standard-Skin.");
        }
        applyMovementSensitivity(mannequin);
        hideMannequinDescription(mannequin);
        return mannequin;
    }

    /**
     * Takes the grey "NPC" line off a mannequin, the line the client draws
     * under its name.
     *
     * <p>Reported once when this server's API offers no way to do it: the line
     * would otherwise sit under every body without a word about why.</p>
     */
    private void hideMannequinDescription(Mannequin mannequin) {
        if (MannequinLabel.hideDescription(mannequin) || mannequinLabelReported) {
            return;
        }
        mannequinLabelReported = true;
        log(Level.WARNING, "Die Zeile \"NPC\" unter dem Namen des Mannequins konnte nicht abgeschaltet werden."
                + " Setter: " + MannequinLabel.describeSetter());
    }

    /**
     * Whether the body itself is the mannequin. Only a visible body of type 2
     * is.
     *
     * <p>A mannequin that is not drawn does not show a name either, so an
     * invisible body is built the way type 1 always is: an invisible armour
     * stand that carries the name, with the mannequin standing in it taking the
     * hits. Both types therefore end up the same as soon as the body is
     * switched invisible - and either way a mannequin is the entity that is
     * hit, so it is hit where the player himself would be hit.</p>
     */
    private boolean usesMannequinBody() {
        return bodyType == BodyType.MANNEQUIN && bodyVisible;
    }

    /**
     * Whether the armour of the player is drawn on his body,
     * {@code body.armor-visible}.
     *
     * <p>An invisible body never shows it, whatever the setting says: armour
     * does not turn invisible along with what wears it, the pieces would hang
     * in the air on their own - the same reason an invisible armour stand is
     * left without the player's head.</p>
     *
     * <p>Purely a matter of looks: the hit is calculated on the player himself
     * with his own armour, and his own armour is what wears out, see
     * {@link #onBodyDamage(EntityDamageEvent)}.</p>
     */
    private boolean showsBodyArmor() {
        return bodyArmorVisible && bodyVisible;
    }

    /**
     * Puts the configured name above the body, or leaves it without one. The
     * grey line a mannequin draws under that name is taken away separately, by
     * {@link #hideMannequinDescription(Mannequin)}.
     */
    private void applyBodyName(LivingEntity body, Player player) {
        body.setCustomName(bodyNameVisible
                ? getMessage("armorstand.name-format").replace("{player}", player.getName())
                : null);
        body.setCustomNameVisible(bodyNameVisible);
    }

    /**
     * Takes a mannequin out of sight. The armour it wears is hidden separately
     * through {@link #createHiddenArmor(ItemStack[])}, otherwise the pieces
     * would stay where the body is.
     */
    private void hideMannequin(Mannequin mannequin) {
        mannequin.setInvisible(true);
        mannequin.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
    }

    /**
     * Puts the configured {@code body.movement-sensitivity} onto a mannequin.
     *
     * <p>On level 0 it is nailed to its spot, on the levels above it is moved by
     * gravity, water and pistons, because that movement is what ends camera
     * mode. Only level 2 also lets players and mobs push it.</p>
     *
     * <p>The same call fits the hitbox and the body, because the mannequin is
     * the entity that gets pushed in either case. Behind the armour stand of
     * body type 1 level 2 has already fallen back to level 1, so the hitbox
     * standing in that body never becomes collidable.</p>
     */
    private void applyMovementSensitivity(Mannequin mannequin) {
        mannequin.setImmovable(movementSensitivity.isFixed());
        mannequin.setCollidable(movementSensitivity.allowsEntityPush());
    }

    /**
     * Gravity for the body entities, decided by
     * {@code body.movement-sensitivity} alone: level 0 nails the body to its
     * spot, every level above it lets the body fall.
     */
    private boolean useBodyGravity() {
        return !movementSensitivity.isFixed();
    }

    /**
     * Creates the classic body: an armour stand wearing the player's head, and
     * his armour with it when {@link #showsBodyArmor()} says so.
     */
    private ArmorStand spawnArmorStandBody(Player player, Location location, ItemStack[] originalArmor) {
        ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        armorStand.setVisible(bodyVisible);
        // Never a marker: that would take away its hitbox and drop the name tag
        // from above the head down to its feet. Level 0 is held in place by
        // startBodyPin instead.
        armorStand.setMarker(false);
        armorStand.addEquipmentLock(EquipmentSlot.HEAD, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.CHEST, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.LEGS, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.FEET, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.HAND, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.OFF_HAND, ArmorStand.LockType.REMOVING_OR_CHANGING);

        if (bodyVisible) {
            EntityEquipment equipment = armorStand.getEquipment();
            if (showsBodyArmor()) {
                // Before the head goes on, not after: this call writes the whole
                // set of four slots and would take the head off again.
                equipment.setArmorContents(createArmorStandArmor(originalArmor));
            }
            // Only a body that is meant to be seen gets the head: on an
            // invisible armour stand it would go on floating by itself.
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
            if (skullMeta != null) {
                skullMeta.setOwningPlayer(player);
                playerHead.setItemMeta(skullMeta);
            }
            equipment.setHelmet(playerHead);
        }
        return armorStand;
    }

    public void exitCameraMode(Player player) {
        CameraData cameraData = cameraPlayers.get(player.getUniqueId());
        if (cameraData == null) {
            // Ensure players are removed from the no-collision team and get
            // their hunger back even if the CameraData has already been
            // cleaned up by another call.
            removePlayerFromNoCollisionTeam(player);
            camHungerGuard.stopFor(player);
            camGhastGuard.stopFor(player);
            updateViewerTeam(player);
            if (camModeObjective != null) {
                camModeObjective.getScore(player.getName()).setScore(0);
            }
            return;
        }
        cancelTimeLimit(player);
        stopMobTargeting(player);
        LivingEntity body = cameraData.getBody();
        Mannequin hitbox = cameraData.getHitbox();

        // Zuerst zum Körper teleportieren
        player.teleport(body.getLocation());
        stopCameraParticles(player);
        stopSightGlow(player);
        stopActionBar(player);
        if (!shuttingDown) {
            showActionBarOffMessage(player);
        }
        boolean standingInFire = camFireGuard.stopFor(player);
        camGhastGuard.stopFor(player);

        // *** Inventar und Rüstung wiederherstellen ***
        PlayerInventory playerInventory = player.getInventory();
        playerInventory.clear(); // Sicherheitshalber leeren, falls Items hinzugefügt wurden
        playerInventory.setContents(cameraData.getOriginalInventoryContents());
        playerInventory.setArmorContents(cameraData.getOriginalArmorContents());
        player.updateInventory();

        player.removePotionEffect(PotionEffectType.INVISIBILITY);

        if (standingInFire) {
            player.setFireTicks(160);
        } else {
            player.setFireTicks(0);
        }
        for (PotionEffect effect : cameraData.getPausedEffects()) {
            player.addPotionEffect(effect);
        }
        player.setGameMode(cameraData.getOriginalGameMode());
        player.setAllowFlight(cameraData.getOriginalAllowFlight());
        player.setFlying(cameraData.getOriginalFlying());
        player.setGlowing(cameraData.getOriginalGlowing());
        player.setRemainingAir(cameraData.getOriginalRemainingAir());
        camHungerGuard.stopFor(player);

        removePlayerFromNoCollisionTeam(player);

        cameraPlayers.remove(player.getUniqueId());
        updateViewerTeam(player);
        if (camModeObjective != null) {
            camModeObjective.getScore(player.getName()).setScore(0);
        }

        // Safety check to ensure the player really left the no-collision team
        removePlayerFromNoCollisionTeam(player);

        // The aggro goes back to the player, who is standing where his body
        // stood. Deliberately only here, after he has stopped being a camera
        // player: onMobTarget keeps mobs off a camera player and would send
        // them straight back to the body that is removed a moment later.
        double reaggroRadius = 64.0;
        for (Entity entity : body.getNearbyEntities(reaggroRadius, reaggroRadius, reaggroRadius)) {
            if (entity instanceof Mob mob
                    && (body.equals(mob.getTarget()) || (hitbox != null && hitbox.equals(mob.getTarget())))) {
                mob.setTarget(player);
            }
        }

        // Aufräumen
        bodyOwners.remove(body.getUniqueId());
        if (hitbox != null) {
            hitboxEntities.remove(hitbox.getUniqueId());
        }

        // Clear equipment before removing to avoid item drops or duplication
        EntityEquipment bodyEquipment = body.getEquipment();
        if (bodyEquipment != null) {
            bodyEquipment.setArmorContents(new ItemStack[4]);
            bodyEquipment.setHelmet(new ItemStack(Material.AIR));
        }
        body.remove();
        // Remove armour from the hitbox before deleting it to avoid item drops
        if (hitbox != null) {
            EntityEquipment hitboxEquipment = hitbox.getEquipment();
            if (hitboxEquipment != null) {
                hitboxEquipment.setArmorContents(new ItemStack[4]);
            }
            hitbox.remove();
        }

        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(this, player);
        }
        updateVisibilityForAll();
        if (!shuttingDown) {
            startCooldown(player);
        }
    }

    public boolean isInCameraMode(Player player) {
        return cameraPlayers.containsKey(player.getUniqueId());
    }

    /**
     * Watches the mannequin while camera mode is running and ends the mode as
     * soon as it leaves its spot. Both body types run through this one check,
     * because both have a mannequin: with body type 1 it is the invisible one
     * standing in the armour stand, with type 2 the visible body itself. It is
     * the entity that takes the hits in either case, so gravity, water and
     * pistons are noticed on the same entity for both types.
     *
     * <p>Deliberately checked by the scheduler and not through an event: the
     * mannequin has no AI, so {@code EntityMoveEvent} does not fire for it, and
     * falling or drifting away would go unnoticed.</p>
     *
     * <p>Drowning, suffocation, fire and lava are not checked here: the
     * mannequin takes that damage itself and
     * {@link #onBodyDamage(EntityDamageEvent)} ends camera mode.</p>
     */
    private void startBodyMovementCheck(Player player, LivingEntity mannequin) {
        if (movementSensitivity.isFixed()) {
            // Nothing can move the body on this level, so the check would only
            // compare a location with itself every tick.
            return;
        }
        new BukkitRunnable() {
            /**
             * The spot the body is compared against. Taken at the first run and
             * not when it is spawned, so that settling onto the ground right
             * after the spawn does not already count as movement.
             */
            private Location reference;

            @Override
            public void run() {
                if (!cameraPlayers.containsKey(player.getUniqueId()) || !player.isOnline() || mannequin.isDead()) {
                    this.cancel();
                    return;
                }
                Location current = mannequin.getLocation();
                if (reference == null) {
                    reference = current.clone();
                    return;
                }
                if (hasMoved(reference, current)) {
                    sendConfiguredMessage(player, "body-moved");
                    exitCameraMode(player);
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 20L, 1L);
    }

    /**
     * Puts the body back whenever something moved it, as long as sensitivity
     * level 0 is set.
     *
     * <p>{@code setImmovable} keeps gravity and knockback off the mannequin but
     * does not stop a piston, and an armour stand is pushed by one as well. The
     * level promises that nothing moves the body, so what a piston does is
     * undone here.</p>
     */
    private void startBodyPin(Player player, LivingEntity body, Mannequin hitbox) {
        if (!movementSensitivity.isFixed()) {
            return;
        }
        final Location anchor = body.getLocation().clone();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.containsKey(player.getUniqueId()) || !player.isOnline() || body.isDead()) {
                    this.cancel();
                    return;
                }
                pinToSpot(body, anchor);
                if (hitbox != null && !hitbox.isDead()) {
                    pinToSpot(hitbox, anchor);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /**
     * Sends the hostile mobs around the body after it, as long as
     * {@code body.mob-target} names a mode that does so.
     *
     * <p>Without it the body stands there untouched: no mob picks a mannequin
     * as its target by itself. Only the ones that were already after the player
     * follow his body, and they leave it again as soon as something else
     * catches their eye. With a mode set the body stands in for the player here
     * as well - what would have come for him comes for it, as far as it would
     * have noticed him, see {@link #sightRangeFor(Mob, LivingEntity)}.</p>
     *
     * <p>The target is set again and again, not once: a mob works out its
     * target anew every so often and would drop a target it did not pick
     * itself.</p>
     *
     * @param damageTarget the mannequin that takes the hits, see
     *                     {@link CameraData#getDamageTarget()}
     */
    private void startMobTargeting(Player player, LivingEntity damageTarget) {
        if (!mobTargetMode.attractsMobs() || searchRadius() <= 0.0) {
            return;
        }
        stopMobTargeting(player);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.containsKey(player.getUniqueId()) || !player.isOnline()
                        || damageTarget.isDead()) {
                    this.cancel();
                    mobTargetTasks.remove(player.getUniqueId(), this);
                    return;
                }
                sendMobsAfterBody(player, damageTarget);
            }
        };
        task.runTaskTimer(this, 0L, MOB_TARGET_INTERVAL);
        mobTargetTasks.put(player.getUniqueId(), task);
    }

    private void stopMobTargeting(Player player) {
        BukkitRunnable task = mobTargetTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * One pass of {@link #startMobTargeting(Player, LivingEntity)}: the hostile
     * mobs that notice the body and can see it get it as their target.
     *
     * <p>Noticing it is the point of the range: without it every mob around
     * would set off, including the ones standing behind a wall, in a cave below
     * or on the other side of a hill. On top of the range comes the same look a
     * mob takes at a player - eye to eye, without a block in between.</p>
     *
     * <p>A mob that is busy with somebody else keeps the target it has - that
     * fight is not ours to take away. One that is already after the body is
     * left alone too: it is on its way, and losing sight of it on that way is
     * its own business, exactly as it would be while chasing a player. Left out
     * on purpose: the warden, which
     * {@link #onWardenTarget(EntityTargetLivingEntityEvent)} keeps off the body
     * and off the camera player alike, and a mob whose AI is switched off.</p>
     */
    private void sendMobsAfterBody(Player player, LivingEntity damageTarget) {
        double radius = searchRadius();
        for (Entity entity : damageTarget.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Mob mob) || !(entity instanceof Enemy) || mob instanceof Warden) {
                continue;
            }
            if (!mob.isAware()) {
                continue;
            }
            LivingEntity current = mob.getTarget();
            if (damageTarget.equals(current)) {
                continue;
            }
            if (player.equals(current)) {
                // Beating on the camera player gains nothing, he takes no
                // damage. Either the body takes his place right below, or the
                // mob is left without a target.
                mob.setTarget(null);
                current = null;
            }
            if (current != null && !current.isDead()) {
                continue;
            }
            if (!noticesBody(mob, damageTarget) || !mob.hasLineOfSight(damageTarget)) {
                continue;
            }
            mob.setTarget(damageTarget);
        }
    }

    /**
     * How far the search around the body looks, in blocks. The mode
     * {@code custom} has one range for everybody, {@code vanilla} has to look as
     * far as the mob with the longest reach and sorts the rest out mob by mob.
     */
    private double searchRadius() {
        return mobTargetMode == MobTargetMode.CUSTOM ? mobTargetRadius : MAX_MOB_TARGET_RANGE;
    }

    /**
     * How far this mob may notice the body, in blocks.
     *
     * <p>In the mode {@code custom} that is the configured radius, the same one
     * for every mob. In {@code vanilla} it is the range the mob brings with it:
     * its follow range, the 16 blocks of a zombie, the 48 of a blaze. Read off
     * the mob itself and not from a list here, so it fits every mob - the ones
     * a later version adds and the ones another plugin hands a range of its own
     * included.</p>
     *
     * <p>A mob head on the body halves it, the way it halves the distance at
     * which that kind of mob notices a player wearing it. That part is switched
     * by {@code body.mob-target-heads}.</p>
     *
     * <p>The mode {@code false} never asks: nothing is sent to the body there
     * and nothing is handed over to it either.</p>
     */
    private double sightRangeFor(Mob mob, LivingEntity body) {
        double range = mobTargetMode == MobTargetMode.CUSTOM ? mobTargetRadius : vanillaFollowRange(mob);
        if (mobTargetHeads && MobHeads.matches(body.getEquipment(), mob.getType())) {
            range *= MOB_HEAD_SIGHT_FACTOR;
        }
        return range;
    }

    /** The distance at which this mob notices a player, out of its own attributes. */
    private double vanillaFollowRange(Mob mob) {
        AttributeInstance followRange = mob.getAttribute(Attribute.FOLLOW_RANGE);
        return followRange != null ? followRange.getValue() : DEFAULT_FOLLOW_RANGE;
    }

    /** Whether the body stands close enough for this mob to go for it. */
    private boolean noticesBody(Mob mob, LivingEntity body) {
        if (body.isDead() || !mob.getWorld().equals(body.getWorld())) {
            return false;
        }
        double range = sightRangeFor(mob, body);
        return range > 0.0 && mob.getLocation().distanceSquared(body.getLocation()) <= range * range;
    }

    /** Teleports the entity back to its spot when something pushed it away. */
    private void pinToSpot(Entity entity, Location anchor) {
        Location current = entity.getLocation();
        if (!current.getWorld().equals(anchor.getWorld())
                || current.distanceSquared(anchor) > MIN_MOVE_THRESHOLD * MIN_MOVE_THRESHOLD) {
            entity.teleport(anchor);
            entity.setVelocity(new Vector());
        }
    }

    /** {@code true} when the body left its spot or its world. */
    private boolean hasMoved(Location reference, Location current) {
        if (!current.getWorld().equals(reference.getWorld())) {
            return true;
        }
        return current.distanceSquared(reference) > moveThresholdSquared;
    }

    private void startCameraParticles(Player player) {
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.containsKey(player.getUniqueId()) || !player.isOnline()) {
                    this.cancel();
                    return;
                }
                Location particleLoc = player.getLocation().add(0, particleHeight, 0);
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (!showOwnParticles && viewer.equals(player)) continue;
                    if (shouldShowParticlesTo(viewer, particleLoc)) {
                        viewer.spawnParticle(Particle.SOUL_FIRE_FLAME, particleLoc,
                                particlesPerTick, 0.1, 0.1, 0.1, 0);
                    }
                }
            }
        };
        task.runTaskTimer(this, 0L, 1L);
        particleTasks.put(player.getUniqueId(), task);
    }

    /**
     * Whether this player is shown the particles around a camera player.
     *
     * <p>The world is asked first: a camera player who went through a portal is
     * a world away, and the particles carry only coordinates, so everybody over
     * here would see them floating at the same spot in his own world.</p>
     */
    private boolean shouldShowParticlesTo(Player viewer, Location particleLoc) {
        if (!viewer.getWorld().equals(particleLoc.getWorld())) {
            return false;
        }
        return switch (playerVisibilityMode) {
            case ALL -> true;
            case CAM -> cameraPlayers.containsKey(viewer.getUniqueId());
            case NONE -> false;
        };
    }

    private void stopCameraParticles(Player player) {
        BukkitRunnable task = particleTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    /**
     * Outline for {@code glowing-outline: sight}: the camera player only glows
     * while at least one other player looks at him without a block in between.
     *
     * <p>Glowing is a single flag on the entity and every client draws it
     * through walls, so no viewer can be left out of it. Once one player has
     * him in sight, the ones behind a wall see the outline as well. What this
     * does prevent is the outline giving him away while nobody sees him.</p>
     */
    private void startSightGlow(Player player) {
        if (glowMode != GlowMode.SIGHT || playerVisibilityMode == VisibilityMode.NONE) {
            return;
        }
        stopSightGlow(player);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                CameraData data = cameraPlayers.get(player.getUniqueId());
                if (data == null || !player.isOnline()) {
                    this.cancel();
                    sightGlowTasks.remove(player.getUniqueId(), this);
                    return;
                }
                // A glow he already had before camera mode is left alone.
                boolean glowing = data.getOriginalGlowing() || isInSightOfAnyone(player);
                if (player.isGlowing() != glowing) {
                    player.setGlowing(glowing);
                }
            }
        };
        task.runTaskTimer(this, 0L, SIGHT_GLOW_INTERVAL);
        sightGlowTasks.put(player.getUniqueId(), task);
    }

    /**
     * Whether another player has the camera player in sight right now. Only
     * players he is shown to count, which leaves out everybody outside camera
     * mode in mode CAM. Spectators do not count either: they are not part of
     * the game and would switch the outline on for everybody else.
     */
    private boolean isInSightOfAnyone(Player camPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(camPlayer)
                    || viewer.getGameMode() == GameMode.SPECTATOR
                    || !viewer.getWorld().equals(camPlayer.getWorld())
                    || !viewer.canSee(camPlayer)) {
                continue;
            }
            // Eye to eye, the same check hostile mobs use to spot a player.
            if (viewer.hasLineOfSight(camPlayer)) {
                return true;
            }
        }
        return false;
    }

    private void stopSightGlow(Player player) {
        BukkitRunnable task = sightGlowTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    private void startActionBar(Player player) {
        if (!actionBarEnabled) return;
        stopActionBar(player);
        BukkitRunnable off = offMessageTasks.remove(player.getUniqueId());
        if (off != null) off.cancel();
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.containsKey(player.getUniqueId()) || !player.isOnline()) {
                    this.cancel();
                    return;
                }
                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(actionBarOnMessage));
            }
        };
        task.runTaskTimer(this, 0L, 40L);
        actionBarTasks.put(player.getUniqueId(), task);
    }

    private void stopActionBar(Player player) {
        BukkitRunnable task = actionBarTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    private void showActionBarOffMessage(Player player) {
        if (!actionBarEnabled || shuttingDown) return;
        stopActionBar(player);
        BukkitRunnable existing = offMessageTasks.remove(player.getUniqueId());
        if (existing != null) existing.cancel();

        BukkitRunnable task = new BukkitRunnable() {
            private int ticks = 0;


            @Override
            public void run() {
                if (!player.isOnline()) {
                    this.cancel();
                    offMessageTasks.remove(player.getUniqueId());
                    return;
                }

                if (ticks >= actionBarOffDuration) {
                    this.cancel();
                    offMessageTasks.remove(player.getUniqueId());
                    return;
                }

                player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(actionBarOffMessage));
                ticks++;
            }
        };

        task.runTaskTimer(this, 0L, 1L);
        offMessageTasks.put(player.getUniqueId(), task);
    }



    /**
     * Every hit on the body ends camera mode and is passed on to the player.
     * There is no separate check for lava, water or blocks: the body takes that
     * damage itself and the damage event is all that is needed to notice it.
     *
     * <p>Only the raw damage of the hit travels to the player, together with the
     * damage source it came with. The server then reduces it exactly once, on
     * the player: his armour, his enchantments, his resistance and his
     * absorption, in the order and with the rules of the real damage type. What
     * the body wears never counts - it would be a second, wrong reduction, and
     * a damage type that ignores armour (falling, drowning, magic) would lose
     * against armour it never touches in the first place.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBodyDamage(EntityDamageEvent event) {
        Entity damagedEntity = event.getEntity();

        // Prüfe, ob es sich um unseren Körper oder die zugehörige Hitbox handelt
        boolean damagedBody = isCameraBody(damagedEntity);
        UUID ownerUUID = getBodyOrHitboxOwner(damagedEntity);

        if (ownerUUID == null) return; // Nicht von uns verwaltet

        Player owner = Bukkit.getPlayer(ownerUUID);
        if (owner == null || !owner.isOnline()) {
            // Spieler offline -> Aufräumen
            if (damagedBody) {
                bodyOwners.remove(damagedEntity.getUniqueId());
            } else {
                hitboxEntities.remove(damagedEntity.getUniqueId());
            }
            cameraPlayers.remove(ownerUUID);
            deleteNoCollisionTeamIfUnused();
            damagedEntity.remove();
            return;
        }

        // Which of the two entities was hit makes no difference any more: only
        // the raw damage is passed on, and the reduction happens on the player.
        // A hit on the armour stand therefore no longer has to be forwarded to
        // the mannequin standing in it.
        if (!pendingDamage.add(ownerUUID)) {
            // already scheduled damage for this hit
            return;
        }

        if (owner.isDead()) {
            event.setCancelled(true);
            exitCameraMode(owner);
            pendingDamage.remove(ownerUUID);
            return;
        }

        // Der Körper selbst soll keinen Schaden nehmen, jeder Treffer beendet den Cam-Modus.
        event.setCancelled(true);

        if (event instanceof EntityDamageByEntityEvent selfHit &&
                selfHit.getDamager().getUniqueId().equals(owner.getUniqueId())) {
            sendConfiguredMessage(owner, "camera-off");
            exitCameraMode(owner);
            pendingDamage.remove(ownerUUID);
            return;
        }

        DamageCause cause = event.getCause();

        String damagerName = "Umgebung";
        Entity damagerEntity = null;
        if (event instanceof EntityDamageByEntityEvent entityEvent) {
            damagerEntity = entityEvent.getDamager();
            damagerName = damagerEntity instanceof Player ? damagerEntity.getName() : damagerEntity.getType().toString();

            // apply tipped arrow effects to the player
            if (damagerEntity instanceof Arrow arrow) {
                applyArrowEffects(arrow, owner);
            }
        }


        double applyDamage;
        switch (damageMode) {
            // The damage the hit started with, before anything reduced it.
            // Armour, armour toughness, protection enchantments, resistance and
            // absorption all belong to the player: the server applies them once,
            // when the hit is passed on to him below. Whatever the body wears
            // does not count, so nothing is subtracted twice and explosions and
            // falling anvils need no special case any more.
            case MIRROR -> applyDamage = event.getDamage();
            case CUSTOM -> {
                applyDamage = customDamageHearts * 2.0;
                // At zero hearts the mode is meant to cost nothing at all, so
                // no enchantment puts anything on top of it either.
                if (applyDamage > 0 && event instanceof EntityDamageByEntityEvent ede) {
                    Entity dmg = ede.getDamager();
                    if (dmg instanceof LivingEntity attacker) {
                        ItemStack wpn = attacker.getEquipment().getItemInMainHand();
                        int sharp = wpn.getEnchantmentLevel(Enchantment.SHARPNESS);
                        if (sharp > 0) {
                            applyDamage += 1 + sharp; // rough Sharpness formula
                        }
                    } else if (dmg instanceof AbstractArrow arrow) {
                        ProjectileSource src = arrow.getShooter();
                        if (src instanceof LivingEntity attacker) {
                            ItemStack bow = attacker.getEquipment().getItemInMainHand();
                            int power = bow.getEnchantmentLevel(Enchantment.POWER);
                            if (power > 0) {
                                applyDamage += 1 + power; // approximate Power formula
                            }
                        }
                    }
                }
            }
            default -> applyDamage = 0.0;
        }

        exitCameraMode(owner);

        String messageKey = resolveDamageMessageKey(event, cause);
        if (isMessageEnabled(messageKey)) {
            owner.sendMessage(
                    getMessage(messageKey)
                            .replace("{damager}", damagerName)
                            .replace("{cause}", event.getCause().toString())
            );
        }

        if (mirrorDebug) {
            sendMirrorDebug(owner, String.format(Locale.ROOT,
                    "Koerper getroffen: roh %.3f | nach Koerper-Ruestung %.3f | %s",
                    event.getDamage(), event.getFinalDamage(), event.getCause()));
        }

        // The hit always reaches the player, whatever the mode passes on of it:
        // the damage, the push, or only the pause it leaves behind. It also
        // keeps the damage source it had - only with it does the server treat
        // it as the fall, the drowning or the arrow it really was, and the
        // source decides whether armour counts at all, which protection
        // enchantment counts, and who gets the kill.
        mirrorHitToPlayer(owner, applyDamage, event.getDamage(), event.getDamageSource(), damagerEntity);

        pendingDamage.remove(ownerUUID);
    }

    /**
     * Hands the hit the body took to the player - as soon as his armour
     * actually protects him again.
     *
     * <p>Camera mode has just put the armour back into his inventory, but that
     * alone does not protect him: the server turns the pieces into attribute
     * modifiers in the player's own tick, and scheduled tasks run before the
     * entities of that tick. A hit passed on one tick later can therefore still
     * find him standing there as if he wore nothing, while waiting longer than
     * needed only gives something else the chance to hit him first.</p>
     *
     * <p>So the hit does not wait for a number of ticks, it waits for the
     * player: as soon as he has lived through one tick since the armour came
     * back, that tick has applied it, and the hit lands with the protection he
     * really has.</p>
     */
    private void mirrorHitToPlayer(Player owner, double amount, double rawDamage,
                                   org.bukkit.damage.DamageSource source, Entity attacker) {
        int restoredAt = owner.getTicksLived();
        // Nothing else may reach him until this hit has landed, see onPlayerDamage.
        pendingMirrorHit.add(owner.getUniqueId());
        new BukkitRunnable() {
            private int waited = 0;

            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead()) {
                    pendingMirrorHit.remove(owner.getUniqueId());
                    cancel();
                    return;
                }
                if (owner.getTicksLived() <= restoredAt && ++waited < MAX_ARMOR_WAIT_TICKS) {
                    return; // his tick is still to come, his armour is not on yet
                }
                cancel();
                applyMirroredHit(owner, amount, rawDamage, source, attacker, waited);
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    /**
     * Puts the hit onto the player: once, with his own armour, his own
     * enchantments and his own effects, and with the damage source it came
     * with.
     *
     * <p>His invulnerability is left alone on purpose. Should something else
     * have hit him while this one was on its way, the server counts the two
     * together the way it counts any two hits that land within the same
     * invulnerability - the bigger one wins instead of both being taken. Forcing
     * this hit through would take it on top of the other one, and the player
     * would lose more hearts than the same hit costs outside camera mode.</p>
     *
     * <p>When the mode passes on no damage at all, the hit still arrives: as
     * the push, if the push is switched on, and as the pause it leaves behind
     * either way - and as the wear on the armour, which follows a mode of its
     * own.</p>
     */
    private void applyMirroredHit(Player owner, double amount, double rawDamage,
                                  org.bukkit.damage.DamageSource source, Entity attacker, int waitedTicks) {
        double speed = owner.getVelocity().length();
        double armor = attributeValue(owner, Attribute.ARMOR);
        double toughness = attributeValue(owner, Attribute.ARMOR_TOUGHNESS);
        double knockbackResistance = attributeValue(owner, Attribute.KNOCKBACK_RESISTANCE);
        double healthBefore = owner.getHealth();
        double absorptionBefore = owner.getAbsorptionAmount();
        int framesBefore = owner.getNoDamageTicks();
        double lastBefore = owner.getLastDamage();
        int fireBefore = owner.getFireTicks();
        UUID ownerId = owner.getUniqueId();
        pendingMirrorHit.remove(ownerId);

        if (amount <= 0) {
            // Nothing to pass on - but the hit did happen, it only landed on
            // the body standing in for the player. The pause it leaves behind
            // is his as well: without it the next swing of the attacker, or the
            // fire his body stood in, would reach him in the same moment and
            // take exactly the hearts this mode is meant to save him. The
            // push is his too, as long as it is switched on - the server hands
            // it out only together with damage, so here it has to be handed
            // out by hand.
            if (mirrorKnockback) {
                owner.setVelocity(new Vector(0, 0, 0));
                pushAwayFrom(owner, attacker, knockbackResistance);
            }
            owner.setNoDamageTicks(20);
            owner.setLastDamage(rawDamage);
            // The armour follows its own mode: that no hearts are passed on
            // does not mean the hit left the armour alone.
            int wornPoints = wearWornArmor(owner, rawDamage, source);
            reportMirroredHit(owner, amount, source, armor, toughness, knockbackResistance,
                    healthBefore, speed, waitedTicks, framesBefore, lastBefore, fireBefore,
                    armorNote(false, wornPoints));
            return;
        }

        // The server wears the armour down out of the very damage it deals, and
        // that number is the only one it can use. It is therefore left to do the
        // wear in the one case where its number is the right one anyway: the
        // real hit, mirrored, with the Unbreaking enchantment counting. In every
        // other case the armour is swapped for copies, the server wears those
        // out, and the real pieces get the wear their own mode asks for.
        boolean serverWearsArmor = armorDamageMode == ArmorDamageMode.MIRROR
                && respectUnbreaking && amount == rawDamage;
        ItemStack[] saved = null;
        if (!serverWearsArmor) {
            // Copies protect exactly like the originals, so the player takes
            // the same damage - only the copies wear out, and they are thrown
            // away right afterwards. Taking the armour off instead would not
            // work: the protection sits in attribute modifiers the server only
            // refreshes in the entity's own tick, so it would still count here
            // while the durability was already gone.
            saved = owner.getInventory().getArmorContents();
            ItemStack[] copies = new ItemStack[saved.length];
            for (int i = 0; i < saved.length; i++) {
                if (saved[i] != null) {
                    copies[i] = saved[i].clone();
                }
            }
            owner.getInventory().setArmorContents(copies);
            owner.updateInventory();
        }
        // A moment ago the player was flying. That speed must not ride along
        // into the knockback of this hit: outside camera mode he would have
        // been standing where his body stood, and the hit would push him from
        // a standstill.
        owner.setVelocity(new Vector(0, 0, 0));
        damageImmunityBypass.add(ownerId);
        try {
            if (source != null) {
                owner.damage(amount, source);
            } else {
                owner.damage(amount, attacker);
            }
        } finally {
            damageImmunityBypass.remove(ownerId);
        }
        if (!damageCountsArmor) {
            takeWhatTheArmorKeptAway(owner, amount, healthBefore + absorptionBefore, framesBefore);
        }
        if (!mirrorKnockback) {
            // The hit is passed on, the push behind it is not: the server has
            // just turned it into movement, and that movement is taken back
            // before anyone sees it.
            owner.setVelocity(new Vector(0, 0, 0));
        }
        // A dead player keeps nothing of this: what he dropped are the copies,
        // so wearing the originals down would only be heard and seen for a set
        // of pieces that is about to be thrown away.
        int wornPoints = saved == null || owner.isDead() ? 0 : wearArmor(owner, saved, rawDamage, source);
        reportMirroredHit(owner, amount, source, armor, toughness, knockbackResistance,
                healthBefore, speed, waitedTicks, framesBefore, lastBefore, fireBefore,
                armorNote(serverWearsArmor, wornPoints));
        if (attacker instanceof LivingEntity living) {
            ItemStack weapon = living.getEquipment().getItemInMainHand();
            int fireLevel = weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
            if (fireLevel > 0) {
                int ticks = Math.max(owner.getFireTicks(), fireLevel * 80);
                owner.setFireTicks(ticks);
            }
        } else if (attacker instanceof AbstractArrow arr) {
            if (arr.getFireTicks() > 0) {
                int ticks = Math.max(owner.getFireTicks(), 100);
                owner.setFireTicks(ticks);
            }
        }
        if (saved != null && !owner.isDead()) {
            owner.getInventory().setArmorContents(saved);
            owner.updateInventory();
        }
        // Died from the hit: the copies are what he dropped, and they carry the
        // same pieces. Handing the originals back into an inventory the server
        // has just emptied would only put them into the world twice.
    }

    /**
     * Wears down the pieces the player has on, for a hit that passes on no
     * damage of its own. Only that one case needs this - everywhere else the
     * armour is off his body already, swapped for the copies the server wears
     * out in its stead.
     */
    private int wearWornArmor(Player owner, double rawDamage, org.bukkit.damage.DamageSource source) {
        if (armorDamageMode == ArmorDamageMode.OFF) {
            return 0;
        }
        ItemStack[] worn = owner.getInventory().getArmorContents();
        int points = wearArmor(owner, worn, rawDamage, source);
        if (points > 0) {
            owner.getInventory().setArmorContents(worn);
            owner.updateInventory();
        }
        return points;
    }

    /**
     * Wears the pieces down after {@code mirror-damage.damage-armor-mode}:
     * after the real hit, after a fixed number of durability points, or not at
     * all.
     *
     * @return the durability points every piece was asked to give up
     */
    private int wearArmor(Player owner, ItemStack[] armor, double rawDamage,
                          org.bukkit.damage.DamageSource source) {
        return switch (armorDamageMode) {
            case MIRROR -> ArmorWear.wearMirrored(owner, armor, rawDamage, source, respectUnbreaking);
            case CUSTOM -> ArmorWear.wearFixed(owner, armor, customArmorDamage, source, respectUnbreaking);
            case OFF -> 0;
        };
    }

    /**
     * Takes off afterwards what the armour kept away, so that the hit costs
     * exactly what it is worth: the hearts "custom" is set to, or the whole of
     * the real hit under "mirror".
     *
     * <p>Only reached with {@code damage-counts-armor} switched off. The hit is
     * still dealt the normal way first, with its damage source, its push and
     * the death message it brings; what the armour, the protection enchantments
     * or an effect took off it is then taken from the player by hand.</p>
     *
     * <p>What this does not touch is the armour itself: how hard the hit wears
     * it down is {@code damage-armor-mode}'s to say alone, so the pieces still
     * take their durability - and on the body they are worn and shown the whole
     * time either way.</p>
     *
     * <p>A hit that does not land at all stays at nothing, though: something
     * cancelled it outright - fire resistance against fire, a protected region -
     * and that is not the armour taking its share. Neither is the
     * invulnerability of a hit the player had just taken, which this one is
     * meant to run into the same way it would outside camera mode.</p>
     */
    private void takeWhatTheArmorKeptAway(Player owner, double target, double poolBefore,
                                          int framesBefore) {
        if (owner.isDead() || framesBefore > 0) {
            return;
        }
        double dealt = poolBefore - (owner.getHealth() + owner.getAbsorptionAmount());
        if (dealt <= 0 || dealt >= target - 1.0E-4) {
            return;
        }
        double missing = target - dealt;
        double absorption = owner.getAbsorptionAmount();
        if (absorption > 0) {
            // Absorption hearts stand in front of the real ones here as well.
            double taken = Math.min(absorption, missing);
            owner.setAbsorptionAmount(absorption - taken);
            missing -= taken;
        }
        if (missing > 0) {
            owner.setHealth(Math.max(0.0, owner.getHealth() - missing));
        }
    }

    /** How the wear on the armour reads in the measuring line. */
    private String armorNote(boolean serverWears, int points) {
        if (serverWears) {
            return "mirror, vom Server";
        }
        return switch (armorDamageMode) {
            case MIRROR -> "mirror, " + points + " Punkte";
            case CUSTOM -> "custom, " + points + " Punkte";
            case OFF -> "false";
        };
    }

    /**
     * Pushes the player away from whoever hit his body, the way the server
     * pushes anyone it hits: with the same strength, against his knockback
     * resistance, and from a standstill.
     *
     * <p>Needed because the server only ever hands out that push together with
     * damage. Where no damage is passed on, the push would be lost with it -
     * and whether the player is pushed is not meant to depend on how much of
     * the hit the mode passes on.</p>
     */
    private void pushAwayFrom(Player owner, Entity attacker, double knockbackResistance) {
        if (attacker == null) {
            return;
        }
        double strength = 0.4 * (1.0 - knockbackResistance);
        if (strength <= 0.0) {
            return;
        }
        Location from = attacker.getLocation();
        Location to = owner.getLocation();
        Vector direction = new Vector(from.getX() - to.getX(), 0.0, from.getZ() - to.getZ());
        if (direction.lengthSquared() < 1.0E-7) {
            return; // standing in the same spot, there is no direction to push in
        }
        Vector push = direction.normalize().multiply(strength);
        Vector velocity = owner.getVelocity();
        double lift = owner.isOnGround() ? Math.min(0.4, velocity.getY() / 2.0 + strength) : velocity.getY();
        owner.setVelocity(new Vector(
                velocity.getX() / 2.0 - push.getX(),
                lift,
                velocity.getZ() / 2.0 - push.getZ()));
    }

    /** One line of the measurement, whenever {@code mirror-damage.debug} is on. */
    private void reportMirroredHit(Player owner, double amount, org.bukkit.damage.DamageSource source,
                                   double armor, double toughness, double knockbackResistance,
                                   double healthBefore, double speed, int waitedTicks,
                                   int framesBefore, double lastBefore, int fireBefore,
                                   String armorNote) {
        if (!mirrorDebug) {
            return;
        }
        sendMirrorDebug(owner, String.format(Locale.ROOT,
                "uebertragen: roh %.3f (%s) | Ruestung %.1f (zaehlt %s), Haerte %.1f, KB-Schutz %.2f"
                        + " | Leben %.2f -> %.2f (-%.3f) | Tempo %.3f | Wartezeit %d Ticks"
                        + " | Unverwundbar %d, letzter Treffer %.2f, Feuer %d | Rueckstoss %s"
                        + " | Abnutzung %s",
                amount, damageTypeName(source), armor, damageCountsArmor ? "an" : "aus",
                toughness, knockbackResistance,
                healthBefore, owner.getHealth(), healthBefore - owner.getHealth(), speed, waitedTicks,
                framesBefore, lastBefore, fireBefore, mirrorKnockback ? "an" : "aus", armorNote));
    }

    /**
     * Picks the message for the hit that ended camera mode. Drowning and
     * suffocation keep their own text, everything else is reported as an attack
     * or as generic environmental damage.
     */
    private String resolveDamageMessageKey(EntityDamageEvent event, DamageCause cause) {
        if (event instanceof EntityDamageByEntityEvent) {
            return "body-attacked";
        }
        return switch (cause) {
            case DROWNING -> "body-drowning";
            case SUFFOCATION -> "body-suffocating";
            default -> "body-env-damage";
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBodyInteract(PlayerInteractEntityEvent event) {
        handleBodyInteract(event);
    }

    /**
     * Armour stands and mannequins are clicked with the "interact at" variant of
     * the event, which has its own handler list. Without this the body could be
     * equipped by right clicking it.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onBodyInteractAt(PlayerInteractAtEntityEvent event) {
        handleBodyInteract(event);
    }

    /**
     * Taking a piece off an armour stand, or hanging one on it, is a third event
     * with a handler list of its own. It is only ever reached through the
     * interact-at above, which turns it away already - but a click that somehow
     * gets past that one must not end up moving armour around either.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        handleBodyInteract(event);
    }

    private void handleBodyInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        UUID ownerUUID = getBodyOrHitboxOwner(entity);
        if (ownerUUID == null) {
            // Anything that is not a camera body. The camera player has no hands
            // out here: blocks are shut in onPlayerInteract already, and this is
            // the same rule for the entities standing among them - the item
            // frame he would turn, the armour stand he would undress, the
            // villager he would trade with, the chest minecart, the boat with
            // the chest, the horse. His own body is the one thing left to him,
            // and that is the case below.
            if (cameraPlayers.containsKey(player.getUniqueId())) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (!cameraPlayers.containsKey(ownerUUID)) {
            // Beide Event-Varianten können für denselben Klick ausgelöst werden.
            return;
        }
        if (player.getUniqueId().equals(ownerUUID)) {
            sendConfiguredMessage(player, "camera-off");
            Player owner = Bukkit.getPlayer(ownerUUID);
            if (owner != null) {
                exitCameraMode(owner);
            }
        } else {
            sendConfiguredMessage(player, "cant-interact-other");
        }
    }

    /**
     * Keeps mobs off the camera player. He takes no damage while his body
     * stands in for him, so a mob after him beats on nothing at all - and one
     * that shoots, a blaze or a ghast, keeps firing at him from far away
     * without ever being able to hit him.
     *
     * <p>In the modes {@code vanilla} and {@code custom} his body takes his
     * place, as long as it stands within the range this mob has, see
     * {@link #sightRangeFor(Mob, LivingEntity)}. Is it further away - the
     * player flies on, his body stays behind - then the mob gets no target
     * rather than one it can never reach.</p>
     *
     * <p>In the mode {@code false} nothing is handed over: whoever takes aim at
     * the camera player loses his target and stays where he is. Otherwise
     * flying past a zombie would be enough to send it off to the body, which is
     * exactly what that mode is meant to prevent. The body itself is refused
     * there as well, so that mode holds even where a mob would pick a mannequin
     * on its own.</p>
     */
    @EventHandler
    public void onMobTarget(EntityTargetEvent event) {
        if (!(event.getTarget() instanceof Player player)) {
            // In the mode false the body is nobody's target either, not even of
            // a mob that would go for a mannequin by itself.
            if (!mobTargetMode.attractsMobs()) {
                UUID owner = getBodyOrHitboxOwner(event.getTarget());
                if (owner != null && cameraPlayers.containsKey(owner)) {
                    event.setCancelled(true);
                    event.setTarget(null);
                }
            }
            return;
        }
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        if (mobTargetMode.attractsMobs() && event.getEntity() instanceof Mob mob
                && noticesBody(mob, data.getDamageTarget())) {
            event.setTarget(data.getDamageTarget());
            return;
        }
        // Cancelled rather than handed an empty target: a mob that is already
        // after the body keeps it that way, only the camera player is refused.
        event.setCancelled(true);
        event.setTarget(null);
    }

    @EventHandler
    public void onWardenTarget(EntityTargetLivingEntityEvent event) {
        if (!(event.getEntity() instanceof Warden)) return;
        LivingEntity target = event.getTarget();
        if (target instanceof Player player) {
            if (cameraPlayers.containsKey(player.getUniqueId())) {
                event.setCancelled(true);
                event.setTarget(null);
            }
        } else {
            UUID owner = getBodyOrHitboxOwner(target);
            if (owner != null && cameraPlayers.containsKey(owner)) {
                event.setCancelled(true);
                event.setTarget(null);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockReceiveGame(BlockReceiveGameEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof Player player && cameraPlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player)) {
            return;
        }

        Player shooter = (Player) event.getEntity().getShooter();

        if (cameraPlayers.containsKey(shooter.getUniqueId())) {
            event.setCancelled(true); // Generell Projektile verhindern
            sendConfiguredMessage(shooter, "no-projectiles");
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.ADVENTURE) {
            player.stopSound(SoundCategory.PLAYERS);
        }
        if (!cameraPlayers.containsKey(player.getUniqueId())) {
            return;
        }

        Action action = event.getAction();

        // Verhindert jegliche Interaktionen im Kamera-Modus
        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK ||
                action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK ||
                action == Action.PHYSICAL) {
            event.setCancelled(true);
            player.stopSound(SoundCategory.PLAYERS);
        }
    }

    /**
     * The camera player takes no damage himself: while camera mode runs his
     * body stands in for him, and in the tick or two between the hit on the
     * body and that hit reaching him nothing else may hit him either.
     *
     * <p>That second part matters more than it looks. The hit on the body came
     * first - without camera mode the player would have taken it right there,
     * and everything reaching him in the next few ticks would have run into the
     * invulnerability of that hit. Letting something hit him while his own hit
     * is still on its way would take it on top instead: the fire his body stood
     * in, or the next swing of the attacker, would cost him hearts that the
     * same situation never costs outside camera mode.</p>
     */
    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        UUID playerId = player.getUniqueId();
        if (damageImmunityBypass.contains(playerId)) {
            return; // his own hit, on its way through
        }
        if (cameraPlayers.containsKey(playerId) || pendingMirrorHit.contains(playerId)) {
            event.setCancelled(true);
        }
    }

    /**
     * The camera player does not heal by himself either.
     *
     * <p>Natural regeneration is paid for out of the hunger: outside camera
     * mode every half heart of it costs saturation first and then a haunch off
     * the bar. That bar stands still while he watches - see
     * {@link de.elia.cameraplugin.hunger.CamHungerGuard} - so left running it
     * would be free here, and a player could sit his wounds out in the air
     * instead of eating them off on the ground. Both of its reasons are
     * therefore turned away while he is in camera mode: the fast one out of
     * the saturation, and the slow one off the full bar, which is also the one
     * a peaceful world heals with.</p>
     *
     * <p>Only those two. Healing that somebody hands him on purpose - an
     * effect, another plugin - is none of this plugin's business. The one
     * thing still moving his health is the hit his body takes, and that ends
     * camera mode in the same breath.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCameraRegainHealth(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        RegainReason reason = event.getRegainReason();
        if (reason != RegainReason.REGEN && reason != RegainReason.SATIATED) {
            return;
        }
        if (cameraPlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordLastDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            lastDamageTimes.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (cameraPlayers.containsKey(event.getPlayer().getUniqueId())) {
            exitCameraMode(event.getPlayer());
        }
        distanceMessageCooldown.remove(event.getPlayer().getUniqueId());
        areaMessageCooldown.remove(event.getPlayer().getUniqueId());
        portalMessageCooldown.remove(event.getPlayer().getUniqueId());
        removePlayerFromNoCollisionTeam(event.getPlayer());
        lastDamageTimes.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        new BukkitRunnable() {
            @Override
            public void run() {
                updateViewerTeam(event.getPlayer());
                updateVisibilityForAll();
                if (camModeObjective != null) {
                    camModeObjective.getScore(event.getPlayer().getName()).setScore(0);
                }
            }
        }.runTaskLater(this, 1L);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!cameraPlayers.containsKey(player.getUniqueId())) return;
        Location to = event.getTo();
        if (to == null) return;

        Block blockAt = to.getBlock();
        if (!allowLavaFlight && blockAt.getType() == Material.LAVA) {
            sendConfiguredMessage(player, "cant-fly-in-lava");
            exitCameraMode(player);
            return;
        }

        if (entersForbiddenArea(player, event.getFrom(), to)) {
            event.setCancelled(true);
            return;
        }

        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (!maxDistanceEnabled || !beyondMaxDistance(data, to)) {
            return;
        }
        if (beyondMaxDistance(data, event.getFrom())) {
            // He is out there already, so stopping the step alone would nail him
            // to the spot instead of keeping him near his body - which is
            // exactly what a portal used to do to him. He is brought back.
            //
            // The step is deliberately not cancelled here: a cancelled step
            // puts the player back where he came from once this returns, and
            // that is out there - it would undo the teleport that just brought
            // him in. The step is sent to the spot he belongs at instead.
            warnDistanceLimit(player, "portal-return-distance", data, event.getFrom());
            event.setTo(bringBack(player, data));
            return;
        }
        event.setCancelled(true);
        warnDistanceLimit(player, "distance-limit", data, to);
    }

    /**
     * Tells the player that he has reached the end of his leash, at most once
     * every {@code camera-mode.distance-warning-cooldown} seconds - the step is
     * stopped over and over as long as he keeps walking against the border.
     *
     * @param key   the message: the one for a step that does not go through, or
     *              the one for a player who is brought back
     * @param where the spot the distance was measured at, which decides what
     *              {@code {from}} names
     */
    private void warnDistanceLimit(Player player, String key, CameraData data, Location where) {
        long now = System.currentTimeMillis();
        if (distanceMessageCooldown.getOrDefault(player.getUniqueId(), 0L) >= now) {
            return;
        }
        distanceMessageCooldown.put(player.getUniqueId(),
                now + TimeUnit.SECONDS.toMillis(distanceWarningCooldown));
        sendMessage(player, key, "{distance}", String.valueOf(maxDistance),
                "{from}", distanceAnchorName(data, where));
    }

    /**
     * What the distance is measured from at this spot, written out for a
     * message: his body, or - in a world his body is not in - the portal he
     * came out of there.
     *
     * <p>Follows {@link #distanceAnchor(CameraData, Location)}, so the message
     * never sends a player looking for his body while he is a world away from
     * it.</p>
     */
    private String distanceAnchorName(CameraData data, Location where) {
        boolean fromPortal = !data.getBody().getWorld().equals(where.getWorld())
                && data.getPortalAnchor() != null;
        String text = getMessage(fromPortal ? "distance-from-portal" : "distance-from-body");
        // Ein Satzteil, kein ganzer Satz: Fehlt er in einer Konfiguration aus
        // einer aelteren Version, stuende sonst "... blocks from !" im Chat.
        if (!text.isEmpty()) {
            return text;
        }
        return fromPortal ? "the portal you came out of" : "your body";
    }

    /**
     * Whether a portal lets the camera player through, and what he finds on the
     * other side.
     *
     * <p>Two things can shut a portal: the switch of its own kind under
     * {@code portals}, and {@code cam-area} forbidding the dimension behind it -
     * but only on level 2, the level that keeps him out of such a place while he
     * flies. A portal that is shut simply does not carry him anywhere; he keeps
     * standing where he is.</p>
     *
     * <p>Where a portal comes out cannot be asked beforehand, so a forbidden
     * biome or structure over there is found only by walking through once, see
     * {@link #afterPortal(Player, Location)}. From then on the portal is known
     * and shuts like the other two, as long as {@code portals.remember-blocked}
     * is on - and, under {@code portals.forget-changed}, only for as long as
     * what was found over there still holds, which is looked over at every
     * attempt.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCameraPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        PortalKind kind = PortalKind.of(event.getCause());
        if (kind == null) {
            // An end gateway or a chorus fruit: no setting here rules over
            // those, only the distance to the body does.
            return;
        }
        boolean open = portalRules.letsThrough(kind);
        String area = null;
        if (open) {
            // Die Dimension hinter dem Portal steht schon vor der Reise fest.
            // Ein verbotenes Biom oder eine verbotene Struktur erst danach -
            // deshalb zaehlt hier, was eine fruehere Reise ergeben hat.
            area = forbiddenPortalDimension(event.getTo());
            if (area == null) {
                area = portalRules.forbiddenAreaBehind(event.getFrom(),
                        this::forbiddenCamArea);
            }
        }
        if (!open || area != null) {
            event.setCancelled(true);
            player.setPortalCooldown(PORTAL_COOLDOWN_TICKS);
            warnPortalShut(player, kind, area);
            return;
        }
        // The trip itself happens after this event, so the far side can only be
        // looked at from the next tick on.
        Location entry = event.getFrom().clone();
        Bukkit.getScheduler().runTask(this, () -> afterPortal(player, entry));
    }

    /**
     * A portal newly built lets go of every remembered portal it stands close
     * enough to, see {@link PortalRules#forgetPortalsNear(World, Collection)}.
     *
     * <p>Watched in every world and not only where camera players are: the one
     * building the portal is usually not the one who will be turned away by
     * it.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortalCreated(PortalCreateEvent event) {
        // Die Endplattform zaehlt nicht dazu: Sie wird jedes Mal neu gesetzt,
        // wenn jemand im End ankommt, und wuerde das Gemerkte dort bei jedem
        // fremden Besuch wegwerfen.
        if (event.getReason() == PortalCreateEvent.CreateReason.END_PLATFORM) {
            return;
        }
        portalRules.forgetPortalsNear(event.getWorld(), event.getBlocks());
    }

    /**
     * The dimension a portal leads into, when {@code cam-area} does not allow
     * camera mode in it at all.
     *
     * <p>Only level 2 keeps the player out of it: level 1 has a say over the
     * start alone and lets him fly wherever he likes afterwards, portals
     * included.</p>
     *
     * @return the name of the dimension, or {@code null} when the portal may be
     *         used
     */
    private String forbiddenPortalDimension(Location to) {
        return camAreaRules.getLevel().blocksFlight() ? camAreaRules.forbiddenDimension(to) : null;
    }

    /**
     * The dimension, biome or structure that keeps camera mode out of this
     * spot, gated by the same level as {@link #forbiddenPortalDimension}.
     *
     * <p>Asked at the far side of a portal twice: once when a trip comes out
     * there, and from then on every time that portal is stepped into again -
     * an area that has been taken apart in the meantime is not to hold the
     * portal shut, see
     * {@link PortalRules#forbiddenAreaBehind(Location, PortalRules.AreaCheck)}.</p>
     *
     * @return the name of the area, or {@code null} when camera mode is allowed
     *         there
     */
    private String forbiddenCamArea(Location where) {
        return camAreaRules.getLevel().blocksFlight() ? camAreaRules.forbiddenArea(where) : null;
    }

    /**
     * Tells the player why the portal did not take him along: the portal itself
     * is shut, or camera mode is not allowed in what lies behind it.
     *
     * <p>He keeps standing in the portal, which asks again and again, so the
     * message waits {@code portals.warning-cooldown} seconds before it is sent
     * a second time.</p>
     *
     * @param area the forbidden dimension behind the portal, or the biome or
     *             structure an earlier trip through it came out in, or
     *             {@code null} when the portal is shut on its own
     */
    private void warnPortalShut(Player player, PortalKind kind, String area) {
        long now = System.currentTimeMillis();
        if (portalMessageCooldown.getOrDefault(player.getUniqueId(), 0L) >= now) {
            return;
        }
        portalMessageCooldown.put(player.getUniqueId(),
                now + TimeUnit.SECONDS.toMillis(portalRules.getWarningCooldown()));
        if (area != null) {
            sendMessage(player, "cam-area-limit", "{area}", area);
        } else {
            sendMessage(player, "portal-blocked", "{portal}", kind.getConfigName());
        }
    }

    /**
     * What the camera player finds on the far side of a portal, one tick after
     * the trip.
     *
     * <p>His body stays where it was, so the far side decides what
     * {@code camera-mode.max-distance} is measured from: in another world it is
     * the portal he came out of, and once he is back in the world of his body
     * that body counts again. Whoever comes out too far away from it is brought
     * back, and so is whoever comes out in a biome or a structure camera mode is
     * not allowed in - a portal is not asked beforehand where it comes out. The
     * second kind is written down, together with the spot the trip came out at,
     * so that the same portal turns the next camera player away instead of
     * sending him over first - and so that the note can be checked against the
     * world later on instead of being believed.</p>
     *
     * @param entry where he stepped into the portal, the spot he is brought back
     *              to under {@code portals.return-to: portal}
     */
    private void afterPortal(Player player, Location entry) {
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null || !player.isOnline()) {
            return;
        }
        Location arrival = player.getLocation();
        boolean withBody = data.getBody().getWorld().equals(arrival.getWorld());
        data.setPortalAnchor(withBody ? null : arrival.clone());
        if (!withBody && data.getPortalEntry() == null) {
            // The first portal of the trip is the one he is brought back to,
            // not whichever he went through last: that one is in the world he
            // is being taken out of.
            data.setPortalEntry(entry);
        }
        String area = forbiddenCamArea(arrival);
        if (area != null) {
            // Jetzt ist bekannt, wo dieses Portal herauskommt. Ohne das wuerde
            // es ihn bei jedem Durchgang aufs Neue hinueber und gleich wieder
            // zurueck schicken.
            portalRules.rememberForbiddenArea(entry, area, arrival);
            sendMessage(player, "portal-return-area", "{area}", area);
            bringBack(player, data);
            return;
        }
        if (maxDistanceEnabled && beyondMaxDistance(data, arrival)) {
            sendMessage(player, "portal-return-distance", "{distance}",
                    String.valueOf(maxDistance), "{from}", distanceAnchorName(data, arrival));
            bringBack(player, data);
            return;
        }
        if (withBody) {
            // He is home, the trip is over: the next one gets a starting portal
            // of its own.
            data.setPortalEntry(null);
        }
    }

    /**
     * Puts a player back where camera mode holds him: at his body, or at the
     * portal he set out through, see {@code portals.return-to}.
     *
     * <p>The portal is only taken when it is a spot he would be allowed to stand
     * in anyway - in the world of his body and inside the distance to it -
     * otherwise he would be brought back to a place he would be brought back
     * from again. Without a portal to go back to it is the body, which is always
     * there.</p>
     *
     * @return the spot he was put down at
     */
    private Location bringBack(Player player, CameraData data) {
        // Whatever he was measured against over there is done with.
        data.setPortalAnchor(null);
        Location entry = data.getPortalEntry();
        data.setPortalEntry(null);
        Location target = returnTarget(data, entry);
        player.teleport(target);
        // He may well have been set down in the portal he set out through.
        player.setPortalCooldown(PORTAL_COOLDOWN_TICKS);
        keepFlying(player);
        return target;
    }

    /** Where {@link #bringBack(Player, CameraData)} puts the player down. */
    private Location returnTarget(CameraData data, Location entry) {
        if (portalRules.getReturnTo() == PortalReturn.PORTAL && entry != null
                && entry.getWorld().equals(data.getBody().getWorld())
                && (!maxDistanceEnabled || !beyondMaxDistance(data, entry))) {
            return entry;
        }
        return data.getBody().getLocation();
    }

    /**
     * Keeps the player in the air after he was teleported, which takes flight
     * away from him. Done once more a tick later: the client is sent its own
     * flight state along with the teleport and would otherwise let him drop.
     */
    private void keepFlying(Player player) {
        player.setAllowFlight(true);
        player.setFlying(true);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && cameraPlayers.containsKey(player.getUniqueId())) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }
            }
        }.runTaskLater(this, 1L);
    }

    /**
     * Whether this spot lies further from the body than
     * {@code camera-mode.max-distance} allows.
     *
     * <p>Measured from the body, or, while the player is in a world his body is
     * not in, from the portal he came out of there: his body cannot be reached
     * from that world, so the portal takes its place. A player in a world
     * neither of the two is in - one that something else carried him into - has
     * nothing left to measure against and counts as too far away, which brings
     * him back to his body.</p>
     */
    private boolean beyondMaxDistance(CameraData data, Location location) {
        Location anchor = distanceAnchor(data, location);
        return anchor == null || location.distanceSquared(anchor) > maxDistance * maxDistance;
    }

    /**
     * What the distance to the body is measured from in the world of this spot,
     * or {@code null} when nothing there can be measured against.
     */
    private Location distanceAnchor(CameraData data, Location location) {
        Location body = data.getBody().getLocation();
        if (body.getWorld().equals(location.getWorld())) {
            return body;
        }
        Location portal = data.getPortalAnchor();
        return portal != null && portal.getWorld().equals(location.getWorld()) ? portal : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void suppressAdventureMoveSound(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.ADVENTURE) {
            player.stopSound(SoundCategory.PLAYERS);
            player.stopSound(SoundCategory.BLOCKS);
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Der Spieler soll sterben, aber vorher den Kamera-Modus korrekt beenden.
        // Die Drops und XP werden vom Tod selbst gehandhabt.
        if (cameraPlayers.containsKey(event.getEntity().getUniqueId())) {
            // Wichtig: Die Items sind im CameraData gespeichert.
            // Wir müssen die Drops des Todes-Events leeren und unsere eigenen Items fallen lassen.
            Player player = event.getEntity();
            CameraData data = cameraPlayers.get(player.getUniqueId());

            event.getDrops().clear(); // Leert die Standard-Drops (leeres Inventar)

            // Füge die gespeicherten Items zu den Drops hinzu
            for (ItemStack item : data.getOriginalInventoryContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    event.getDrops().add(item);
                }
            }
            for (ItemStack item : data.getOriginalArmorContents()) {
                if (item != null && item.getType() != Material.AIR) {
                    event.getDrops().add(item);
                }
            }

            exitCameraMode(player);
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player &&
                cameraPlayers.containsKey(event.getEntered().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Nothing carries a camera player.
     *
     * <p>The event above already turns away what the API counts as a vehicle -
     * boats, minecarts, horses, and the happy ghast among them. This is the same
     * rule for everything else that can be sat on, and it covers the mount that
     * no right click of his goes through: being put onto something by a command
     * or by another plugin.</p>
     */
    @EventHandler
    public void onCameraMount(EntityMountEvent event) {
        if (event.getEntity() instanceof Player player
                && cameraPlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player &&
                cameraPlayers.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (cameraPlayers.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * An effect on the body ends camera mode, and the effect itself goes on to
     * the player - his body caught it for him, but it is still meant for him.
     *
     * <p>Which effect it was is what the message says. He notices the effect
     * anyway, it is on him a moment later; what he would otherwise be missing
     * is the reason he was put back into his body, the way
     * {@code body-attacked} and {@code body-moved} give him theirs.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBodyPotionEffect(EntityPotionEffectEvent event) {
        Entity entity = event.getEntity();
        UUID ownerUUID = getBodyOrHitboxOwner(entity);

        if (ownerUUID == null) return;

        Player owner = Bukkit.getPlayer(ownerUUID);
        if (owner != null) {
            PotionEffect newEffect = event.getNewEffect();
            if (newEffect != null) {
                owner.addPotionEffect(newEffect);
            }
            exitCameraMode(owner);
            // After the exit, like the message about a hit on the body: first
            // he is back in his body, then he reads why.
            sendMessage(owner, "body-got-effect", "{effect}",
                    event.getModifiedType().getKey().getKey());
        }

        event.setCancelled(true);
    }

    /** Apply potion effects from a tipped arrow to the player. */
    private void applyArrowEffects(Arrow arrow, Player player) {
        var base = arrow.getBasePotionType();
        if (base != null) {
            base.getPotionEffects().forEach(effect -> player.addPotionEffect(effect, true));
        }
        var custom = arrow.getCustomEffects();
        if (custom != null) {
            custom.forEach(effect -> player.addPotionEffect(effect, true));
        }
    }

    /**
     * Transfer potion effects from splash potions that hit the camera body.
     *
     * <p>The camera player himself is taken out of the cloud beforehand. A
     * thrown potion reaches him as little as a swing does while his body
     * stands in for him - and the body is the one thing a potion can still
     * find of him: it wets the mannequin, and from there the effect is passed
     * on to him like everything else his body is met with. An armour stand
     * does not take potions at all, the mannequin standing in it does.</p>
     */
    @EventHandler
    public void onPotionSplash(PotionSplashEvent event) {
        // Over a copy: taking somebody out of the cloud removes him from the
        // very collection this loop walks.
        for (LivingEntity entity : new ArrayList<>(event.getAffectedEntities())) {
            if (entity instanceof Player camPlayer
                    && cameraPlayers.containsKey(camPlayer.getUniqueId())) {
                // Intensity zero is how the API says "not affected": he is
                // dropped from the list the potion works through.
                event.setIntensity(entity, 0.0);
                continue;
            }
            UUID owner = getBodyOrHitboxOwner(entity);
            if (owner == null) continue;
            Player player = Bukkit.getPlayer(owner);
            if (player == null) continue;
            double intensity = event.getIntensity(entity);
            for (PotionEffect effect : event.getPotion().getEffects()) {
                int duration = (int) Math.round(effect.getDuration() * intensity);
                PotionEffect applied = new PotionEffect(
                        effect.getType(), duration, effect.getAmplifier(),
                        effect.isAmbient(), effect.hasParticles(), effect.hasIcon());
                player.addPotionEffect(applied, true);
            }
        }
    }

    /** Transfer potion effects from arrows that hit the camera body. */
    /**
     * The same for the cloud a lingering potion leaves lying: it does not
     * touch the camera player either.
     *
     * <p>A cloud is not one throw but a question asked over and over, about
     * once a second for as long as it lies there, so he is taken out of every
     * single one of those rounds. What it does find of him is his body: the
     * mannequin takes the effect, and from there it reaches him and ends
     * camera mode - the same way it does when a potion is thrown at the body.
     * Dragon's breath works through the same cloud and is covered with it.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event) {
        // The list of this event is meant to be changed; taking somebody out
        // of it is how the API says he is spared.
        event.getAffectedEntities().removeIf(entity -> entity instanceof Player player
                && cameraPlayers.containsKey(player.getUniqueId()));
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Entity hit = event.getHitEntity();
        if (hit == null) return;
        UUID owner = getBodyOrHitboxOwner(hit);
        if (owner == null) return;
        Player player = Bukkit.getPlayer(owner);
        if (player == null) return;
        Projectile proj = event.getEntity();
        if (proj instanceof Arrow arrow) {
            applyArrowEffects(arrow, player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void filterCommandSuggestions(PlayerCommandSendEvent event) {
        event.getCommands().removeIf(cmd -> cmd.equalsIgnoreCase("camplugin:cam"));
        // Remove the namespaced variant of our command from the suggestion list
        // so only "/cam" is shown when tab completing. Using the plugin name
        // ensures this works even if the plugin is renamed.
        String namespaced = getName().toLowerCase() + ":cam";
        event.getCommands().removeIf(cmd -> cmd.equalsIgnoreCase(namespaced));
    }

    @EventHandler
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) {
            return;
        }

        if (!cameraPlayers.containsKey(attacker.getUniqueId())) {
            return;
        }

        Entity target = event.getEntity();
        UUID ownerUUID = getBodyOrHitboxOwner(target);

        // Cancel attacks on anything except the player's own body or hitbox
        if (ownerUUID == null || !ownerUUID.equals(attacker.getUniqueId())) {
            event.setCancelled(true);
            if (ownerUUID != null && !ownerUUID.equals(attacker.getUniqueId())) {
                sendConfiguredMessage(attacker, "cant-attack-other-body");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void suppressAdventureHitSound(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player attacker &&
                attacker.getGameMode() == GameMode.ADVENTURE) {
            attacker.stopSound(SoundCategory.PLAYERS);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player &&
                cameraPlayers.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * No window opens in front of a camera player.
     *
     * <p>The click into one is turned away by the handler above, and the way
     * into a chest or a barrel by the interaction lock. This shuts the windows
     * that hang on an entity instead of on a block - the trade of a villager,
     * the inventory of a chest minecart or of a horse - and it shuts them before
     * they are seen, rather than only keeping his hands out of them.</p>
     */
    @EventHandler
    public void onCameraInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && cameraPlayers.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }



    /** The value of one of the player's attributes, or zero when he has none. */
    private double attributeValue(Player player, Attribute attribute) {
        AttributeInstance instance = player.getAttribute(attribute);
        return instance == null ? 0.0 : instance.getValue();
    }

    /** The name of the damage type a hit carries, for the measuring output. */
    private String damageTypeName(org.bukkit.damage.DamageSource source) {
        return source == null ? "ohne Quelle" : source.getDamageType().getKey().toString();
    }

    /**
     * Puts one line of the measurement in front of the player and into the log.
     * Only ever reached while {@code mirror-damage.debug} is switched on.
     */
    private void sendMirrorDebug(Player owner, String line) {
        getLogger().info("[Schadensuebertragung] " + owner.getName() + ": " + line);
        owner.sendMessage("§e[CamFly] §7" + line);
    }

    /** {@code true} when the entity is the camera body of a player. */
    private boolean isCameraBody(Entity entity) {
        return entity != null && bodyOwners.containsKey(entity.getUniqueId());
    }

    /**
     * Returns the camera player that owns the given body or hitbox entity,
     * or {@code null} when the entity is not managed by us.
     */
    private UUID getBodyOrHitboxOwner(Entity entity) {
        if (entity == null) {
            return null;
        }
        UUID owner = bodyOwners.get(entity.getUniqueId());
        return owner != null ? owner : hitboxEntities.get(entity.getUniqueId());
    }

    public String getMessage(String path) {
        return ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages." + path, ""));
    }

    public boolean isMessageEnabled(String path) {
        if (!getConfig().getBoolean("message-settings.enabled", true)) {
            return false;
        }
        return getConfig().getBoolean("message-settings." + path, true);
    }

    public void sendConfiguredMessage(CommandSender sender, String path) {
        if (!(sender instanceof Player) || isMessageEnabled(path)) {
            sender.sendMessage(getMessage(path));
        }
    }

    /**
     * Sends a message with its placeholders filled in, unless it is switched
     * off or carries no text at all - a config file from an older version does
     * not have the newer keys in it, and a blank line in the chat says nothing.
     *
     * @param fills placeholder and value, one pair after the other
     */
    private void sendMessage(Player player, String key, String... fills) {
        if (!isMessageEnabled(key)) {
            return;
        }
        String text = getMessage(key);
        if (text.isEmpty()) {
            return;
        }
        for (int i = 0; i + 1 < fills.length; i += 2) {
            text = text.replace(fills[i], fills[i + 1]);
        }
        player.sendMessage(text);
    }

    /**
     * Reads every value out of the config file.
     *
     * @return a note for each value that did not fit and was replaced
     */
    private List<ConfigIssue> loadConfigValues() {
        ConfigReader config = new ConfigReader(getConfig());
        maxDistanceEnabled = config.getBoolean("camera-mode.max-distance-enabled", true);
        maxDistance = config.getDouble("camera-mode.max-distance", 100.0, 0.0);
        distanceWarningCooldown = config.getInt("camera-mode.distance-warning-cooldown", 3, 0);
        String visibility = config.getChoice("camera-mode.player_visibility_mode", "cam", "cam", "true", "false")
                .toLowerCase();
        playerVisibilityMode = switch (visibility) {
            case "true" -> VisibilityMode.ALL;
            case "false" -> VisibilityMode.NONE;
            default -> VisibilityMode.CAM;
        };
        allowInvisibilityPotion = config.getBoolean("camera-mode.allow_invisibility_potion", true);
        String glow = config.getChoice("camera-mode.glowing-outline", "true", "true", "false", "sight")
                .toLowerCase();
        glowMode = switch (glow) {
            case "false" -> GlowMode.OFF;
            case "sight" -> GlowMode.SIGHT;
            default -> GlowMode.ALWAYS;
        };
        allowLavaFlight = config.getBoolean("camera-mode.allow_lava_flight", false);
        String effects = config.getChoice("camera-mode.start-with-effects",
                "positive", "true", "false", "positive").toLowerCase();
        startWithEffects = switch (effects) {
            case "true" -> EffectStart.ANY;
            case "false" -> EffectStart.NONE;
            default -> EffectStart.POSITIVE;
        };
        cameraHeadEnabled = config.getBoolean("camera-head.enabled", false);
        bodyType = resolveBodyType(config, config.getInt("body.type", BodyType.ARMOR_STAND.getId()));
        bodyNameVisible = config.getBoolean("body.name-visible", true);
        bodyVisible = config.getBoolean("body.visible", true);
        bodyArmorVisible = config.getBoolean("body.armor-visible", true);
        movementSensitivity = resolveMovementSensitivity(config,
                config.getInt("body.movement-sensitivity", MovementSensitivity.NORMAL.getId()));
        double moveThreshold = config.getDouble("body.move-threshold", 0.05, MIN_MOVE_THRESHOLD);
        moveThresholdSquared = moveThreshold * moveThreshold;
        mobTargetMode = resolveMobTargetMode(config);
        mobTargetRadius = config.getDouble("body.mob-target-radius", 16.0, 0.0);
        mobTargetHeads = config.getBoolean("body.mob-target-heads", true);
        particleHeight = config.getDouble("camera-particles.height", 1.0);
        particlesPerTick = config.getInt("camera-particles.particles-per-tick", 5, 0);
        showOwnParticles = config.getBoolean("camera-particles.show-own-particles", false);
        actionBarEnabled = config.getBoolean("action-bar.enabled", true);
        actionBarOffDuration = config.getInt("action-bar.off-duration", 10, 0);
        actionBarOnMessage = ChatColor.translateAlternateColorCodes('&', config.getString("messages.actionbar-on", "&aCam-Modus aktiviert"));
        actionBarOffMessage = ChatColor.translateAlternateColorCodes('&', config.getString("messages.actionbar-off", "&cCam-Modus beendet"));
        timeLimitEnabled = config.getBoolean("time-limit.enabled", false);
        cooldownsEnabled = config.getBoolean("time-limit.cooldowns-enabled", false);
        durationSeconds = config.getInt("time-limit.duration-seconds", 300, 1);
        cooldownSeconds = config.getInt("time-limit.cooldown-seconds", 120, 0);
        showBossbar = config.getBoolean("time-limit.show-bossbar", true);
        bossbarColor = config.getEnum("time-limit.bossbar-color", BarColor.class, BarColor.BLUE);
        bossbarText = ChatColor.translateAlternateColorCodes('&', config.getString("messages.bossbar-text", "Cam-Modus endet in: %time%"));
        cooldownText = ChatColor.translateAlternateColorCodes('&', config.getString("messages.cooldown-text", "Du kannst den Cam-Modus erst in %time% erneut starten."));
        cooldownAvailableText = ChatColor.translateAlternateColorCodes('&', config.getString("messages.cooldown-available", "&aCam-Modus wieder verf\u00fcgbar"));
        camSafetyEnabled = config.getBoolean("cam-safety.enabled", true);
        camSafetyDelay = config.getInt("cam-safety.delay", 5, 0);
        camSafetyMessage = config.getString("messages.cam-safety",
                "§cDu kannst den Cam-Modus nicht starten! Du musst noch %seconds% Sekunden in Sicherheit bleiben.");
        camAreaRules.load(config);
        portalRules.load(config);

        mirrorKnockback = config.getBoolean("mirror-damage.knockback", true);
        mirrorDebug = config.getBoolean("mirror-damage.debug", false);
        // "off" war frueher die Schreibweise fuer aus und wird weiter gelesen.
        String modeRaw = readMode(config, "mirror-damage.damage-mode", "mirror",
                List.of("mirror", "custom", "false"), List.of("off"));
        if ("custom".equalsIgnoreCase(modeRaw)) {
            damageMode = DamageMode.CUSTOM;
        } else if ("false".equalsIgnoreCase(modeRaw) || "off".equalsIgnoreCase(modeRaw)) {
            damageMode = DamageMode.OFF;
        } else {
            damageMode = DamageMode.MIRROR;
        }
        customDamageHearts = config.getDouble("mirror-damage.custom-damage-hearts", 0.5, 0.0);
        // Used to be called custom-damage-counts-armor, back when it only had a
        // say over the custom damage. A file that still carries the old name
        // keeps its setting, it is simply read as the default of the new one.
        damageCountsArmor = config.getBoolean("mirror-damage.damage-counts-armor",
                config.getBoolean("mirror-damage.custom-damage-counts-armor", true));
        // The mode grew out of the old truth value damage-armor, so a config
        // file that still carries only that one keeps saying what it said:
        // true wears the armour down, false leaves it alone.
        String armorDefault = config.getBoolean("mirror-damage.damage-armor", true) ? "mirror" : "false";
        String armorRaw = readMode(config, "mirror-damage.damage-armor-mode", armorDefault,
                List.of("mirror", "custom", "false"), List.of("off", "true"));
        if ("custom".equalsIgnoreCase(armorRaw)) {
            armorDamageMode = ArmorDamageMode.CUSTOM;
        } else if ("false".equalsIgnoreCase(armorRaw) || "off".equalsIgnoreCase(armorRaw)) {
            armorDamageMode = ArmorDamageMode.OFF;
        } else {
            armorDamageMode = ArmorDamageMode.MIRROR;
        }
        customArmorDamage = config.getInt("mirror-damage.custom-armor-damage", 1, 0);
        respectUnbreaking = config.getBoolean("mirror-damage.respect-unbreaking", true);
        // Read one by one while the server runs, so a wrong value would show up
        // again and again instead of once. Checked here in one go instead.
        config.checkBooleanSection("message-settings");
        warnAboutOldArmorStandSection();
        warnAboutOldDamageArmorKey();
        warnAboutOldStructureKey();
        if (camFireGuard != null) {
            camFireGuard.loadConfig(config);
        }
        return config.getWarnings();
    }

    /**
     * Says once that a leftover {@code armorstand} section is not read any
     * more. Its two remaining settings now sit in {@code body}, and gravity is
     * decided by {@code body.movement-sensitivity} - without this note a config
     * file from an older version would quietly run on the default values.
     */
    private void warnAboutOldArmorStandSection() {
        if (!getConfig().isConfigurationSection("armorstand")) {
            return;
        }
        getLogger().warning("Der Abschnitt \"armorstand\" wird nicht mehr gelesen: name-visible und visible"
                + " stehen jetzt unter \"body\", gravity ist durch body.movement-sensitivity ersetzt.");
    }

    /**
     * Says once that {@code mirror-damage.damage-armor} has become a mode of
     * its own. The truth value is still read, as the default of the new key,
     * so that a config file from an older version keeps behaving the way it
     * reads - but only the new key knows the third mode.
     */
    private void warnAboutOldDamageArmorKey() {
        if (!getConfig().isSet("mirror-damage.damage-armor")) {
            return;
        }
        log(Level.WARNING, "mirror-damage.damage-armor heisst jetzt damage-armor-mode und kennt drei Werte:"
                + " mirror, custom und false. true wird als mirror gelesen, false bleibt false.");
    }

    /**
     * Says once that {@code cam-area.forbidden-structures} has become two
     * lists. The old one is still read, as the default of the box list, so that
     * a config file from an older version keeps behaving the way it reads - but
     * measuring by pieces only happens once the new list is filled.
     */
    private void warnAboutOldStructureKey() {
        if (!getConfig().isSet("cam-area.forbidden-structures")) {
            return;
        }
        getLogger().warning("cam-area.forbidden-structures ist in zwei Listen aufgeteilt:"
                + " forbidden-structures-box misst den ganzen Kasten einer Struktur,"
                + " forbidden-structures-components nur ihre einzelnen Bauteile."
                + " Die alten Eintraege werden als Kasten-Liste gelesen.");
    }

    /**
     * Reads a setting that knows more than two values and hands back the
     * spelling it was written with.
     *
     * <p>Kept apart from {@link ConfigReader#getChoice} because of the
     * spellings an older config file may carry: those are still read, they are
     * simply not offered any more. Only {@code shown} turns up in the note
     * about a value nobody knows, so nobody is sent back to a spelling this
     * version has left behind.</p>
     *
     * @param shown  the spellings this version writes
     * @param legacy the spellings an older version wrote, read but not offered
     */
    private String readMode(ConfigReader config, String path, String def,
                            List<String> shown, List<String> legacy) {
        String value = config.getString(path, def);
        if (isOneOf(value, shown) || isOneOf(value, legacy)) {
            return value;
        }
        config.warnUnknownValue(path, value, String.join(", ", shown), def);
        return def;
    }

    /** Whether the value is one of these spellings, capitals not counting. */
    private static boolean isOneOf(String value, List<String> spellings) {
        for (String spelling : spellings) {
            if (spelling.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads {@code body.mob-target}. The setting used to be a truth value, back
     * when there was only the one range to switch on and off, so those two
     * values keep saying what they said: {@code true} is the range out of the
     * config file, {@code false} is nobody sent to the body.
     */
    private MobTargetMode resolveMobTargetMode(ConfigReader config) {
        String raw = readMode(config, "body.mob-target", "false",
                List.of("vanilla", "custom", "false"), List.of("off", "true"));
        if ("vanilla".equalsIgnoreCase(raw)) {
            return MobTargetMode.VANILLA;
        }
        if ("custom".equalsIgnoreCase(raw) || "true".equalsIgnoreCase(raw)) {
            return MobTargetMode.CUSTOM;
        }
        return MobTargetMode.OFF;
    }

    /**
     * Turns the number configured in {@code body.type} into a body type and
     * falls back to the armour stand when the value is unknown.
     */
    private BodyType resolveBodyType(ConfigReader config, int configuredId) {
        BodyType requested = BodyType.fromId(configuredId);
        if (requested == null) {
            config.warnUnknownValue("body.type", configuredId, "1, 2",
                    String.valueOf(BodyType.ARMOR_STAND.getId()));
            return BodyType.ARMOR_STAND;
        }
        return requested;
    }

    /**
     * Turns the number configured in {@code body.movement-sensitivity} into a
     * level and falls back to 1 when the value is unknown.
     *
     * <p>Level 2 is cut back to level 1 for an armour stand body. That is a
     * valid setting in the wrong combination and not a mistake, so it is
     * described in the config file instead of being logged.</p>
     */
    private MovementSensitivity resolveMovementSensitivity(ConfigReader config, int configuredId) {
        MovementSensitivity requested = MovementSensitivity.fromId(configuredId);
        if (requested == null) {
            config.warnUnknownValue("body.movement-sensitivity", configuredId, "0, 1, 2",
                    String.valueOf(MovementSensitivity.NORMAL.getId()));
            requested = MovementSensitivity.NORMAL;
        }
        return requested.forBodyType(bodyType);
    }

    /**
     * Writes the notes from {@link #loadConfigValues()} into the console and,
     * after a reload, into the chat of the player who started it. Without the
     * second part a broken config file stays invisible in game: the reload
     * reports success while the server quietly runs on default values.
     */
    private void reportConfigWarnings(List<ConfigIssue> warnings, Player initiator) {
        List<String> texts = new ArrayList<>(warnings.size());
        for (ConfigIssue warning : warnings) {
            texts.add(warning.format(configMessage(warning.getMessageKey(), warning.getFallback())));
        }
        for (String text : texts) {
            // The console has no use for colour codes, only for the sentence.
            log(Level.WARNING, ChatColor.stripColor(text));
        }
        if (initiator == null || texts.isEmpty() || !isMessageEnabled("config-errors")) {
            return;
        }
        // The command reports success right after this, so the block needs a
        // headline of its own to not be mistaken for a clean reload.
        String header = texts.size() == 1
                ? configMessage("config-error-header-single", "&cDie Konfiguration hat eine ungültige Stelle:")
                : configMessage("config-error-header", "&cDie Konfiguration hat {count} ungültige Stellen:");
        initiator.sendMessage(header.replace("{count}", String.valueOf(texts.size())));
        int shown = Math.min(texts.size(), MAX_CHAT_WARNINGS);
        for (int i = 0; i < shown; i++) {
            initiator.sendMessage(texts.get(i));
        }
        if (texts.size() > shown) {
            initiator.sendMessage(configMessage("config-error-more",
                    "&c... und {count} weitere. Alle stehen in der Server-Konsole.")
                    .replace("{count}", String.valueOf(texts.size() - shown)));
        }
    }

    /**
     * Looks up the wording of a config note. Unlike {@link #getMessage(String)}
     * an empty entry falls back to the built-in text: a note that lost its
     * wording would be an empty line in the log and would hide the very problem
     * it is about. Use {@code message-settings.config-errors} to switch the
     * notes in the chat off instead.
     */
    @Override
    public FileConfiguration getConfig() {
        if (config == null) {
            reloadConfig();
        }
        return config;
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
        readConfigInto(null);
    }

    /**
     * Reads the config file and puts it in place.
     *
     * <p>A file that cannot be read leaves the settings exactly as they are - a
     * forgotten dash must not put the whole server back to the default values.
     * Only while the plugin is starting is there nothing to keep, and the
     * values built into the jar have to carry it; that is what the two
     * different messages say.</p>
     *
     * @param initiator the player who asked for the reload, told about a broken
     *                  file as well, or {@code null} for the console alone
     * @return whether the file could be read
     */
    private boolean readConfigInto(CommandSender initiator) {
        YamlConfiguration loaded = new YamlConfiguration();
        File file = new File(getDataFolder(), "config.yml");
        Exception problem = null;
        if (file.exists()) {
            try {
                loaded.load(file);
            } catch (IOException | InvalidConfigurationException ex) {
                problem = ex;
            }
        }
        // Reported only once something is in place, because the report looks
        // its own wording up in the config file.
        if (problem != null && config != null) {
            reportBrokenConfig("config-broken", problem, initiator);
            return false;
        }
        InputStream defaults = getResource("config.yml");
        if (defaults != null) {
            loaded.setDefaults(YamlConfiguration.loadConfiguration(
                    new InputStreamReader(defaults, StandardCharsets.UTF_8)));
        }
        config = loaded;
        if (problem != null) {
            reportBrokenConfig("config-broken-startup", problem, initiator);
            return false;
        }
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
            log(Level.WARNING, "Der Befehl /cam konnte nicht abgemeldet werden, er antwortet"
                    + " deshalb mit einem Fehler des Servers: " + ex);
        }
    }

    /** Says in one line why the config file could not be read. */
    private void reportBrokenConfig(String key, Exception problem, CommandSender initiator) {
        String fallback = "config-broken".equals(key)
                ? "&cDie Konfiguration hat einen Fehler und wurde nicht übernommen,"
                        + " es gelten weiter die bisherigen Einstellungen: {error}"
                : "&cDie Konfiguration hat einen Fehler: {error}";
        String text = configMessage(key, fallback).replace("{error}", describeProblem(problem.getMessage()));
        log(Level.SEVERE, ChatColor.stripColor(text));
        if (initiator != null && isMessageEnabled("config-errors")) {
            initiator.sendMessage(text);
        }
    }

    /**
     * What the parser says about a spot in the file, and what it means in
     * German. Matched by a piece of the sentence, because the exact wording
     * differs between versions of the parser. These are the mistakes that
     * really happen while editing the file by hand; anything else keeps the
     * parser's own words.
     */
    private static final String[][] CONFIG_PROBLEMS = {
            {"could not find expected ':'",
                    "Hier fehlt ein \"-\" am Zeilenanfang oder ein \":\" hinter dem Namen"},
            {"mapping values are not allowed",
                    "Hier steht ein \":\" zu viel, oder der Wert gehört in Anführungszeichen"},
            {"cannot start any token",
                    "Hier steht ein Tabulator; eingerückt wird nur mit Leerzeichen"},
            {"expected <block end>",
                    "Hier stimmt die Einrückung nicht mit den Zeilen darüber überein", "zweite"},
            {"found unexpected end of stream",
                    "Hier fehlt das schließende Anführungszeichen"},
    };

    /**
     * Turns what the parser reports into one short sentence: where it is, and
     * what is missing there.
     *
     * <p>The parser hands out several lines for one mistake - its own wording,
     * the offending line of the file, a caret underneath, and the spot where it
     * finally gave up. Only two of those are worth anything: the spot to
     * repair and the reason. Its own sentences are the ones that start at the
     * very left, the last of them names the problem; the spots are indented.
     * </p>
     *
     * <p>Read out of the text and not out of the parser's own error class,
     * which is none of the server API and need not be the same everywhere. A
     * text this cannot make sense of is passed on as it is.</p>
     */
    private static String describeProblem(String message) {
        if (message == null) {
            return "";
        }
        String reason = "";
        for (String line : message.split("\\R")) {
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                reason = line.trim();
            }
        }
        // Which of the two spots to name: usually the first, where the mistake
        // begins. Only a broken indentation is the other way round - there the
        // first spot is the block that was still fine.
        boolean secondSpot = false;
        for (String[] known : CONFIG_PROBLEMS) {
            if (message.contains(known[0])) {
                reason = known[1];
                secondSpot = known.length > 2;
                break;
            }
        }
        List<String> spots = new ArrayList<>();
        java.util.regex.Matcher marks = java.util.regex.Pattern
                .compile("line (\\d+), column (\\d+)").matcher(message);
        while (marks.find()) {
            spots.add("Zeile " + marks.group(1) + ", Spalte " + marks.group(2));
        }
        if (spots.isEmpty()) {
            return shorten(message.replaceAll("\\s+", " ").trim());
        }
        String spot = secondSpot && spots.size() > 1 ? spots.get(1) : spots.get(0);
        return shorten(spot + ": " + (reason.isEmpty() ? "unlesbar" : reason));
    }

    /** Cuts a reason that is longer than a line of chat. */
    private static String shorten(String text) {
        return text.length() <= MAX_CONFIG_ERROR_LENGTH
                ? text
                : text.substring(0, MAX_CONFIG_ERROR_LENGTH) + "...";
    }

    private String configMessage(String key, String fallback) {
        String raw = getConfig().getString("messages." + key, fallback);
        if (raw == null || raw.isEmpty()) {
            raw = fallback;
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /**
     * Erstellt das Team, sobald mindestens ein Spieler es braucht, und gibt es zurueck.
     */
    private Team ensureNoCollisionTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(NO_COLLISION_TEAM);
        if (team == null) {
            team = scoreboard.registerNewTeam(NO_COLLISION_TEAM);
        }
        team.setOption(Team.Option.COLLISION_RULE, Team.OptionStatus.NEVER);
        // Members see each other through the invisibility, which is what mode
        // CAM lives on. Mode NONE hides the player from everybody, so there it
        // would be a hole.
        team.setCanSeeFriendlyInvisibles(playerVisibilityMode != VisibilityMode.NONE);
        return team;
    }

    /** Loescht das Team samt aller Eintraege, falls es existiert. */
    private void deleteNoCollisionTeam() {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(NO_COLLISION_TEAM);
        if (team == null) return;
        for (String entry : new HashSet<>(team.getEntries())) {
            team.removeEntry(entry);
        }
        team.unregister();
    }

    /** Loescht das Team, sobald kein Spieler mehr im Cam-Modus ist. */
    private void deleteNoCollisionTeamIfUnused() {
        if (cameraPlayers.isEmpty()) {
            deleteNoCollisionTeam();
        }
    }

    /**
     * Haelt das Team genau so lange am Leben, wie mindestens ein Spieler im
     * Cam-Modus ist, und synchronisiert die Mitgliedschaft aller Online-Spieler.
     */
    private void refreshNoCollisionTeam() {
        if (cameraPlayers.isEmpty()) {
            deleteNoCollisionTeam();
            return;
        }
        ensureNoCollisionTeam();
        for (Player online : Bukkit.getOnlinePlayers()) {
            updateViewerTeam(online);
        }
    }

    private void addPlayerToNoCollisionTeam(Player player) {
        ensureNoCollisionTeam().addEntry(player.getName());
    }

    private void removePlayerFromNoCollisionTeam(Player player) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
        Team team = scoreboard.getTeam(NO_COLLISION_TEAM);
        if (team != null) {
            team.removeEntry(player.getName());
        }
        deleteNoCollisionTeamIfUnused();
    }

    /**
     * Keeps the team down to the players who are in camera mode right now.
     *
     * <p>The team switches collisions off for its members, so everybody in it
     * walks through everybody else. Players who were only watching used to be
     * added as well - that let them see through the camera player's invisibility,
     * but it also took collisions away from the whole server as soon as a single
     * player started camera mode. The glowing outline shows the camera player to
     * everybody instead, so nobody else has to join the team.</p>
     */
    private void updateViewerTeam(Player player) {
        if (cameraPlayers.isEmpty()) {
            // Niemand im Cam-Modus -> das Team wird nicht gebraucht.
            deleteNoCollisionTeam();
            return;
        }
        Team team = ensureNoCollisionTeam();

        if (cameraPlayers.containsKey(player.getUniqueId())) {
            if (!team.hasEntry(player.getName())) {
                team.addEntry(player.getName());
            }
        } else {
            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
    }

    /**
     * Whether the camera player is turned invisible. Mode NONE takes him away
     * from everybody and the effect is the only thing left that still does so,
     * so there it is applied even when the option is switched off.
     */
    private boolean needsInvisibility() {
        return allowInvisibilityPotion || playerVisibilityMode == VisibilityMode.NONE;
    }

    /**
     * Puts one viewer on the right side of the camera player.
     *
     * <p>Mode NONE leans on the invisibility instead of hiding the player from
     * the client: {@code hidePlayer} would take him out of the tab list as well,
     * and he is supposed to stay in there. What that costs is worn equipment -
     * the camera head keeps being drawn on an invisible player.</p>
     */
    private void applyVisibility(Player camPlayer, Player viewer) {
        if (camPlayer.equals(viewer)) return;
        switch (playerVisibilityMode) {
            case CAM -> {
                if (cameraPlayers.containsKey(viewer.getUniqueId())) {
                    viewer.showPlayer(this, camPlayer);
                } else {
                    viewer.hidePlayer(this, camPlayer);
                }
            }
            // ALL shows him with the outline, NONE leans on the invisibility -
            // either way the entity stays where it is.
            case ALL, NONE -> viewer.showPlayer(this, camPlayer);
        }
    }

    private void updateVisibilityForAll() {
        for (UUID camId : cameraPlayers.keySet()) {
            Player cam = Bukkit.getPlayer(camId);
            if (cam == null) continue;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                applyVisibility(cam, viewer);
            }
        }
    }

    private void removeLeftoverEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(bodyKey, PersistentDataType.INTEGER) ||
                        entity.getPersistentDataContainer().has(hitboxKey, PersistentDataType.INTEGER)) {
                    entity.remove();
                }
            }
        }
    }

    public String formatDuration(long seconds) {
        if (seconds >= 3600) {
            long h = seconds / 3600;
            long m = (seconds % 3600) / 60;
            long s = seconds % 60;
            return h + "h " + m + "m " + s + "s";
        } else if (seconds >= 60) {
            long m = seconds / 60;
            long s = seconds % 60;
            return m + "m " + s + "s";
        } else {
            return seconds + " seconds";
        }
    }

    private void startTimeLimit(Player player) {
        if (!timeLimitEnabled) return;
        cancelTimeLimit(player);
        BossBar bar = null;
        if (showBossbar) {
            bar = Bukkit.createBossBar("", bossbarColor, BarStyle.SOLID);
            bar.addPlayer(player);
            bossBars.put(player.getUniqueId(), bar);
        }
        long total = durationSeconds;
        BossBar finalBar = bar;
        BukkitRunnable task = new BukkitRunnable() {
            long remaining = total;
            @Override
            public void run() {
                if (!cameraPlayers.containsKey(player.getUniqueId()) || !player.isOnline()) {
                    cancel();
                    if (finalBar != null) finalBar.removeAll();
                    return;
                }
                remaining--;
                if (finalBar != null) {
                    finalBar.setProgress(Math.max(0.0, remaining / (double) total));
                    if (isMessageEnabled("bossbar-text")) {
                        finalBar.setTitle(bossbarText.replace("%time%", formatDuration(remaining)));
                    }
                }
                if (remaining <= 0) {
                    cancel();
                    if (finalBar != null) finalBar.removeAll();
                    exitCameraMode(player);
                    sendConfiguredMessage(player, "time-limit-expired");
                }
            }
        };
        task.runTaskTimer(this, 20L, 20L);
        timeLimitTasks.put(player.getUniqueId(), task);
    }

    private void cancelTimeLimit(Player player) {
        BukkitRunnable t = timeLimitTasks.remove(player.getUniqueId());
        if (t != null) t.cancel();
        BossBar bar = bossBars.remove(player.getUniqueId());
        if (bar != null) bar.removeAll();
    }

    private void startCooldown(Player player) {
        if (!cooldownsEnabled) return;
        long end = System.currentTimeMillis() + cooldownSeconds * 1000L;
        camCooldowns.put(player.getUniqueId(), end);
        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                long remaining = end - System.currentTimeMillis();
                if (remaining <= 0) {
                    Player p = Bukkit.getPlayer(player.getUniqueId());
                    if (p != null && isMessageEnabled("cooldown-available")) {
                        p.sendMessage(cooldownAvailableText);
                    }
                    camCooldowns.remove(player.getUniqueId());
                    cooldownTasks.remove(player.getUniqueId());
                    cancel();
                }
            }
        };
        task.runTaskTimer(this, 20L, 20L);
        cooldownTasks.put(player.getUniqueId(), task);
    }

    public boolean isCooldownActive(Player player) {
        if (!cooldownsEnabled) return false;
        Long end = camCooldowns.get(player.getUniqueId());
        if (end == null) return false;
        if (System.currentTimeMillis() >= end) {
            camCooldowns.remove(player.getUniqueId());
            BukkitRunnable task = cooldownTasks.remove(player.getUniqueId());
            if (task != null) task.cancel();
            return false;
        }
        return true;
    }

    public long getCooldownRemaining(Player player) {
        Long end = camCooldowns.get(player.getUniqueId());
        if (end == null) return 0;
        long remaining = end - System.currentTimeMillis();
        if (remaining < 0) return 0;
        return (remaining + 999) / 1000;
    }

    public boolean checkCamSafety(Player player) {
        if (!camSafetyEnabled) return true;
        Long last = lastDamageTimes.get(player.getUniqueId());
        if (last == null) return true;
        long elapsed = System.currentTimeMillis() - last;
        long delayMillis = camSafetyDelay * 1000L;
        if (elapsed >= delayMillis) return true;
        long remaining = (delayMillis - elapsed + 999) / 1000;
        String msg = camSafetyMessage.replace("%seconds%", String.valueOf(remaining));
        if (isMessageEnabled("cam-safety")) {
            player.sendMessage(ChatColor.RED + ChatColor.translateAlternateColorCodes('&', msg));
        }
        return false;
    }

    /**
     * Checks whether the player may start camera mode with the effects he is
     * carrying, as {@code camera-mode.start-with-effects} has it.
     *
     * <p>There is something to keep him from here: camera mode takes his
     * effects off him and gives them back when he leaves, so a poisoned player
     * could sit his poison out up there for as long as he likes. On
     * {@code positive} only that kind stands in his way - what does him no
     * harm, a beneficial effect or a neutral one like glowing, lets him
     * through.</p>
     *
     * <p>The message names every effect he is turned away over, not just the
     * first: being sent back three times in a row, once per effect, tells him
     * no more than being told all three at once.</p>
     *
     * @return whether he may start; if not, he has been told why
     */
    public boolean checkCamEffects(Player player) {
        if (startWithEffects == EffectStart.ANY) {
            return true;
        }
        List<String> blocking = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            PotionEffectType type = effect.getType();
            if (startWithEffects == EffectStart.POSITIVE
                    && type.getCategory() != PotionEffectTypeCategory.HARMFUL) {
                continue;
            }
            blocking.add(type.getKey().getKey());
        }
        if (blocking.isEmpty()) {
            return true;
        }
        sendMessage(player, "cam-effect-start", "{effect}", String.join(", ", blocking));
        return false;
    }

    /**
     * Checks whether camera mode may be started where the player is standing,
     * as long as {@code cam-area.level} is not 0.
     *
     * <p>Which areas those are is decided by {@link CamAreaRules}: the
     * dimension the world belongs to and the biome the player stands in.</p>
     *
     * @return {@code true} when he may start, otherwise {@code false} and he
     *         has been told why
     */
    public boolean checkCamArea(Player player) {
        if (!camAreaRules.getLevel().blocksStart()) {
            return true;
        }
        String area = camAreaRules.forbiddenArea(player.getLocation());
        if (area == null) {
            return true;
        }
        if (isMessageEnabled("cam-area-start")) {
            player.sendMessage(getMessage("cam-area-start").replace("{area}", area));
        }
        return false;
    }

    /**
     * Whether this step would carry the player into an area camera mode is not
     * allowed in, which only level 2 keeps him out of.
     *
     * <p>He is stopped at the border and not sent back to his body: the step is
     * simply cancelled, the same way {@code camera-mode.max-distance} does it.
     * Whoever already stands in such an area - because the rule only came later,
     * say - may keep moving until he is out of it, otherwise he would sit there
     * stuck.</p>
     *
     * <p>Only a step that leaves the block is looked at. Looking around fires
     * this event as well, and the biome of a spot does not change from that.</p>
     */
    private boolean entersForbiddenArea(Player player, Location from, Location to) {
        if (!camAreaRules.getLevel().blocksFlight()
                || (from.getBlockX() == to.getBlockX()
                    && from.getBlockY() == to.getBlockY()
                    && from.getBlockZ() == to.getBlockZ()
                    && from.getWorld().equals(to.getWorld()))) {
            return false;
        }
        String area = camAreaRules.forbiddenArea(to);
        if (area == null || camAreaRules.forbiddenArea(from) != null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (areaMessageCooldown.getOrDefault(player.getUniqueId(), 0L) < now) {
            if (isMessageEnabled("cam-area-limit")) {
                player.sendMessage(getMessage("cam-area-limit").replace("{area}", area));
            }
            areaMessageCooldown.put(player.getUniqueId(),
                    now + TimeUnit.SECONDS.toMillis(camAreaRules.getWarningCooldown()));
        }
        return true;
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
        if (!readConfigInto(initiator)) {
            return false;
        }
        for (UUID uuid : new HashSet<>(cameraPlayers.keySet())) {
            Player camPlayer = Bukkit.getPlayer(uuid);
            if (camPlayer != null) {
                sendConfiguredMessage(camPlayer, "reload-exit");
                exitCameraMode(camPlayer);
            }
        }
        reportConfigWarnings(loadConfigValues(), initiator);
        refreshNoCollisionTeam();
        for (BukkitRunnable task : cooldownTasks.values()) {
            task.cancel();
        }
        cooldownTasks.clear();
        camCooldowns.clear();
        return true;
    }
    private ItemStack createCameraHead() {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            try {
                PlayerProfile profile = Bukkit.createPlayerProfile(UUID.randomUUID());
                profile.getTextures().setSkin(new java.net.URL("http://textures.minecraft.net/texture/bfef5141d0d29154efa496144e117d18c257b4705ad10d29ba07ec7f45fcabc3"));
                meta.setOwnerProfile(profile);
            } catch (java.net.MalformedURLException e) {
                getLogger().warning("Failed to set camera head texture: " + e.getMessage());
            }
            meta.setLore(java.util.Collections.singletonList("https://namemc.com/skin/437c1be7c3e403c1"));
            head.setItemMeta(meta);
        }
        return head;
    }



    // *** CameraData Klasse erweitert ***
    private static class CameraData {
        private final LivingEntity body;
        private final Mannequin hitbox;
        private final GameMode originalGameMode;
        private final boolean originalAllowFlight;
        private final boolean originalFlying;
        private final boolean originalGlowing;
        private final int originalRemainingAir;
        private final ItemStack[] originalInventoryContents; // Für Inventar
        private final ItemStack[] originalArmorContents;     // Für Rüstung
        private final Collection<PotionEffect> pausedEffects;
        /**
         * Where the distance to the body is measured from while the player is
         * in a world his body is not in: the portal he came out of there.
         * {@code null} while he is in the world of his body, where the body
         * itself is measured from.
         */
        private Location portalAnchor;
        /**
         * Where he stepped into the first portal of his trip, the spot
         * {@code portals.return-to: portal} brings him back to. {@code null}
         * while he has not left the world of his body.
         */
        private Location portalEntry;

        public CameraData(LivingEntity body, Mannequin hitbox, GameMode originalGameMode, boolean originalAllowFlight, boolean originalFlying, boolean originalGlowing, ItemStack[] originalInventoryContents, ItemStack[] originalArmorContents, Collection<PotionEffect> pausedEffects, int originalRemainingAir) {
            this.body = body;
            this.hitbox = hitbox;
            this.originalGameMode = originalGameMode;
            this.originalAllowFlight = originalAllowFlight;
            this.originalFlying = originalFlying;
            this.originalGlowing = originalGlowing;
            this.originalInventoryContents = originalInventoryContents;
            this.originalArmorContents = originalArmorContents;
            this.pausedEffects = pausedEffects;
            this.originalRemainingAir = originalRemainingAir;
        }

        public LivingEntity getBody() { return body; }
        /** The separate hitbox, or {@code null} when the body is hit directly. */
        public Mannequin getHitbox() { return hitbox; }
        /** The entity that takes the hits: the separate hitbox, or the body itself. */
        public LivingEntity getDamageTarget() { return hitbox != null ? hitbox : body; }
        public GameMode getOriginalGameMode() { return originalGameMode; }
        public boolean getOriginalAllowFlight() { return originalAllowFlight; }
        public boolean getOriginalFlying() { return originalFlying; }
        /** Whether the player was already glowing before camera mode. */
        public boolean getOriginalGlowing() { return originalGlowing; }
        public ItemStack[] getOriginalInventoryContents() { return originalInventoryContents; }
        public ItemStack[] getOriginalArmorContents() { return originalArmorContents; }
        public Collection<PotionEffect> getPausedEffects() { return pausedEffects; }
        public int getOriginalRemainingAir() { return originalRemainingAir; }
        /** The portal the distance is measured from, or {@code null} for the body. */
        public Location getPortalAnchor() { return portalAnchor; }
        public void setPortalAnchor(Location portalAnchor) { this.portalAnchor = portalAnchor; }
        /** The portal he set out through, or {@code null} when he is still home. */
        public Location getPortalEntry() { return portalEntry; }
        public void setPortalEntry(Location portalEntry) { this.portalEntry = portalEntry; }
    }
}