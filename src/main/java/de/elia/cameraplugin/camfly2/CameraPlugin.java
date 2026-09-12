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
import org.bukkit.event.player.*;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.block.Block;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import de.elia.cameraplugin.mirrordamage.DamageMode;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import de.elia.cameraplugin.feuer.CamFireGuard;
import de.elia.cameraplugin.body.BodyType;
import de.elia.cameraplugin.body.EquipmentVisibility;
import de.elia.cameraplugin.body.MannequinLabel;
import de.elia.cameraplugin.body.MannequinSkin;
import de.elia.cameraplugin.body.MovementSensitivity;
import de.elia.cameraplugin.config.ConfigIssue;
import de.elia.cameraplugin.config.ConfigReader;

import static org.bukkit.Sound.ENTITY_ITEM_BREAK;

@SuppressWarnings("removal")
public final class CameraPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, CameraData> cameraPlayers = new HashMap<>();
    private final Map<UUID, Long> distanceMessageCooldown = new HashMap<>();
    private final Set<UUID> damageImmunityBypass = new HashSet<>();
    private final Map<UUID, UUID> bodyOwners = new HashMap<>();
    private final Map<UUID, UUID> hitboxEntities = new HashMap<>();
    private final Set<UUID> pendingDamage = new HashSet<>();
    private CamFireGuard camFireGuard;
    private double particleHeight;
    private int particlesPerTick;
    private boolean showOwnParticles;
    private final Map<UUID, BukkitRunnable> particleTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> sightGlowTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> actionBarTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> offMessageTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> timeLimitTasks = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Long> camCooldowns = new HashMap<>();
    private final Map<UUID, BukkitRunnable> cooldownTasks = new HashMap<>();
    private final Map<UUID, Long> lastDamageTimes = new HashMap<>();
    private boolean shuttingDown = false;
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

    // Configurable values
    private boolean maxDistanceEnabled;
    private double maxDistance;
    private int distanceWarningCooldown;
    private boolean bodyNameVisible;
    private boolean bodyVisible;
    private BodyType bodyType;
    private MovementSensitivity movementSensitivity;
    /** {@code body.move-threshold} squared, so the square root can be skipped. */
    private double moveThresholdSquared;
    private VisibilityMode playerVisibilityMode;
    private boolean allowInvisibilityPotion;
    private GlowMode glowMode;
    private boolean allowLavaFlight;
    private Object Sound;

    // Damage transfer settings
    private DamageMode damageMode;
    /** Whether the player's armour loses durability from the transferred hit. */
    private boolean damageArmor;
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

    /** The values of {@code camera-mode.glowing-outline}: true, false and sight. */
    private enum GlowMode { ALWAYS, OFF, SIGHT }

    @Override
    public void onEnable() {
        shuttingDown = false;
        saveDefaultConfig();
        // Created before the config is read, so its values go through the same
        // load and its notes end up in the same report.
        camFireGuard = new CamFireGuard(this);
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
        getLogger().log(level, error == null ? text : text.replace("{error}", error));
    }

    @Override
    public void onDisable() {
        shuttingDown = true;
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

        LivingEntity body = spawnCameraBody(player, playerLocation, originalRemainingAir);

        // A mannequin takes the hits for both body types and is the entity the
        // movement check watches. It carries the player's armour so the body
        // looks like him; the damage itself is calculated on the player, see
        // onBodyDamage.
        Mannequin hitbox = null;
        if (usesMannequinBody()) {
            EntityEquipment bodyEquipment = body.getEquipment();
            if (bodyEquipment != null) {
                bodyEquipment.setArmorContents(createMirrorArmor(originalArmor));
            }
        } else {
            // The mannequin next to the armour stand is invisible, so it wears
            // copies whose armour is not rendered either.
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
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                if (player.equals(mob.getTarget())) {
                    mob.setTarget(damageTarget); // redirect aggro away from the player
                }
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
        // The entity taking the hits is the mannequin for both body types, so the
        // movement check always runs on it. Both calls look at the sensitivity
        // level and only one of them does anything.
        startBodyMovementCheck(player, damageTarget);
        startBodyPin(player, body, hitbox);
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
     * Copies the player's armour for the invisible mannequin and takes away its
     * rendering, so that the pieces do not float in front of the armour stand.
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
            getLogger().warning("Die Rüstung des unsichtbaren Mannequins konnte nicht ausgeblendet werden, "
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
     */
    private LivingEntity spawnCameraBody(Player player, Location location, int remainingAir) {
        LivingEntity body = usesMannequinBody()
                ? spawnMannequinBody(player, location)
                : spawnArmorStandBody(player, location);

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
            getLogger().warning("Der Skin von " + player.getName()
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
        getLogger().warning("Die Zeile \"NPC\" unter dem Namen des Mannequins konnte nicht abgeschaltet werden."
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

    /** Creates the classic body: an armour stand wearing the player's head. */
    private ArmorStand spawnArmorStandBody(Player player, Location location) {
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
            // Only a body that is meant to be seen gets the head: on an
            // invisible armour stand it would go on floating by itself.
            ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
            if (skullMeta != null) {
                skullMeta.setOwningPlayer(player);
                playerHead.setItemMeta(skullMeta);
            }
            armorStand.getEquipment().setHelmet(playerHead);
        }
        return armorStand;
    }

    public void exitCameraMode(Player player) {
        CameraData cameraData = cameraPlayers.get(player.getUniqueId());
        if (cameraData == null) {
            // Ensure players are removed from the no-collision team even if the
            // CameraData has already been cleaned up by another call.
            removePlayerFromNoCollisionTeam(player);
            updateViewerTeam(player);
            if (camModeObjective != null) {
                camModeObjective.getScore(player.getName()).setScore(0);
            }
            return;
        }
        cancelTimeLimit(player);
        LivingEntity body = cameraData.getBody();
        Mannequin hitbox = cameraData.getHitbox();

        double reaggroRadius = 64.0;
        for (Entity entity : body.getNearbyEntities(reaggroRadius, reaggroRadius, reaggroRadius)) {
            if (entity instanceof Mob) {
                Mob mob = (Mob) entity;
                if (body.equals(mob.getTarget()) || (hitbox != null && hitbox.equals(mob.getTarget()))) {
                    mob.setTarget(player);
                }
            }
        }

        // Zuerst zum Körper teleportieren
        player.teleport(body.getLocation());
        stopCameraParticles(player);
        stopSightGlow(player);
        stopActionBar(player);
        if (!shuttingDown) {
            showActionBarOffMessage(player);
        }
        boolean standingInFire = camFireGuard.stopFor(player);

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

        removePlayerFromNoCollisionTeam(player);

        cameraPlayers.remove(player.getUniqueId());
        updateViewerTeam(player);
        if (camModeObjective != null) {
            camModeObjective.getScore(player.getName()).setScore(0);
        }

        // Safety check to ensure the player really left the no-collision team
        removePlayerFromNoCollisionTeam(player);

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
                    if (shouldShowParticlesTo(viewer)) {
                        viewer.spawnParticle(Particle.SOUL_FIRE_FLAME, particleLoc,
                                particlesPerTick, 0.1, 0.1, 0.1, 0);
                    }
                }
            }
        };
        task.runTaskTimer(this, 0L, 1L);
        particleTasks.put(player.getUniqueId(), task);
    }

    private boolean shouldShowParticlesTo(Player viewer) {
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
                if (event instanceof EntityDamageByEntityEvent ede) {
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

        if (applyDamage > 0) {
            // The hit keeps the damage source it had. Only with it does the
            // server treat it as the fall, the drowning or the arrow it really
            // was: the source decides whether armour counts at all, which
            // protection enchantment counts, and who gets the kill.
            mirrorDamageToPlayer(owner, applyDamage, event.getDamageSource(), damagerEntity);
        }

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
    private void mirrorDamageToPlayer(Player owner, double amount, org.bukkit.damage.DamageSource source, Entity attacker) {
        int restoredAt = owner.getTicksLived();
        new BukkitRunnable() {
            private int waited = 0;

            @Override
            public void run() {
                if (!owner.isOnline() || owner.isDead()) {
                    cancel();
                    return;
                }
                if (owner.getTicksLived() <= restoredAt && ++waited < MAX_ARMOR_WAIT_TICKS) {
                    return; // his tick is still to come, his armour is not on yet
                }
                cancel();
                applyMirroredDamage(owner, amount, source, attacker);
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
     */
    private void applyMirroredDamage(Player owner, double amount, org.bukkit.damage.DamageSource source, Entity attacker) {
        ItemStack[] saved = null;
        if (!damageArmor) {
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
        if (source != null) {
            owner.damage(amount, source);
        } else {
            owner.damage(amount, attacker);
        }
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
        if (!damageArmor && saved != null) {
            owner.getInventory().setArmorContents(saved);
            owner.updateInventory();
        }
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

    private void handleBodyInteract(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        Player player = event.getPlayer();
        UUID ownerUUID = getBodyOrHitboxOwner(entity);
        if (ownerUUID == null) {
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

    @EventHandler
    public void onMobTarget(EntityTargetEvent event) {
        if (!(event.getTarget() instanceof Player)) return;
        Player player = (Player) event.getTarget();
        if (cameraPlayers.containsKey(player.getUniqueId())) {
            CameraData data = cameraPlayers.get(player.getUniqueId());
            if (data != null && !data.getDamageTarget().isDead()) {
                event.setTarget(data.getDamageTarget());
            }
        }
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

    @EventHandler
    public void onPlayerDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player &&
                cameraPlayers.containsKey(event.getEntity().getUniqueId()) &&
                !damageImmunityBypass.contains(event.getEntity().getUniqueId())) {
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

        Location standLoc = cameraPlayers.get(player.getUniqueId()).getBody().getLocation();
        // Always prevent players from switching worlds, optionally limit distance
        if (!to.getWorld().equals(standLoc.getWorld()) ||
                (maxDistanceEnabled && to.distanceSquared(standLoc) > maxDistance * maxDistance)) {
            event.setCancelled(true);
            long now = System.currentTimeMillis();
            if (distanceMessageCooldown.getOrDefault(player.getUniqueId(), 0L) < now) {
                if (isMessageEnabled("distance-limit")) {
                    player.sendMessage(getMessage("distance-limit").replace("{distance}", String.valueOf(maxDistance)));
                }
                distanceMessageCooldown.put(player.getUniqueId(), now + TimeUnit.SECONDS.toMillis(distanceWarningCooldown));
            }
        }
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
     */
    @EventHandler
    public void onPotionSplash(PotionSplashEvent event) {
        for (LivingEntity entity : event.getAffectedEntities()) {
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
        cameraHeadEnabled = config.getBoolean("camera-head.enabled", false);
        bodyType = resolveBodyType(config, config.getInt("body.type", BodyType.ARMOR_STAND.getId()));
        bodyNameVisible = config.getBoolean("body.name-visible", true);
        bodyVisible = config.getBoolean("body.visible", true);
        movementSensitivity = resolveMovementSensitivity(config,
                config.getInt("body.movement-sensitivity", MovementSensitivity.NORMAL.getId()));
        double moveThreshold = config.getDouble("body.move-threshold", 0.05, MIN_MOVE_THRESHOLD);
        moveThresholdSquared = moveThreshold * moveThreshold;
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

        damageArmor = config.getBoolean("mirror-damage.damage-armor", true);
        String modeRaw = config.getChoice("mirror-damage.damage-mode", "mirror", "mirror", "custom", "off", "false");
        if ("custom".equalsIgnoreCase(modeRaw)) {
            damageMode = DamageMode.CUSTOM;
        } else if ("false".equalsIgnoreCase(modeRaw) || "off".equalsIgnoreCase(modeRaw)) {
            damageMode = DamageMode.OFF;
        } else {
            damageMode = DamageMode.MIRROR;
        }
        customDamageHearts = config.getDouble("mirror-damage.custom-damage-hearts", 0.5, 0.0);
        // Read one by one while the server runs, so a wrong value would show up
        // again and again instead of once. Checked here in one go instead.
        config.checkBooleanSection("message-settings");
        warnAboutOldArmorStandSection();
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
            getLogger().warning(ChatColor.stripColor(text));
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

    public void reloadPlugin(Player initiator) {
        for (UUID uuid : new HashSet<>(cameraPlayers.keySet())) {
            Player camPlayer = Bukkit.getPlayer(uuid);
            if (camPlayer != null) {
                sendConfiguredMessage(camPlayer, "reload-exit");
                exitCameraMode(camPlayer);
            }
        }
        reloadConfig();
        reportConfigWarnings(loadConfigValues(), initiator);
        refreshNoCollisionTeam();
        for (BukkitRunnable task : cooldownTasks.values()) {
            task.cancel();
        }
        cooldownTasks.clear();
        camCooldowns.clear();
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
    }
}