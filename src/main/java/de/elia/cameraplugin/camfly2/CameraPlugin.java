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
import de.elia.cameraplugin.mutplayer.ProtocolLibHook;
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
import java.util.Collection;
import java.util.ArrayList;
import de.elia.cameraplugin.feuer.CamFireGuard;
import de.elia.cameraplugin.body.BodyType;
import de.elia.cameraplugin.body.EquipmentVisibility;
import de.elia.cameraplugin.body.MannequinSkin;

import static org.bukkit.Sound.ENTITY_ITEM_BREAK;

@SuppressWarnings("removal")
public final class CameraPlugin extends JavaPlugin implements Listener {

    private final Map<UUID, CameraData> cameraPlayers = new HashMap<>();
    private final Map<UUID, Long> distanceMessageCooldown = new HashMap<>();
    private final Set<UUID> damageImmunityBypass = new HashSet<>();
    private final Map<UUID, UUID> bodyOwners = new HashMap<>();
    private final Map<UUID, UUID> hitboxEntities = new HashMap<>();
    private final Set<UUID> pendingDamage = new HashSet<>();
    private final Set<UUID> mutedPlayers = new HashSet<>();
    private boolean protocolLibAvailable = false;
    private boolean muteAttack;
    private boolean muteFootsteps;
    private boolean hideSprintParticles;
    private CamFireGuard camFireGuard;
    private double particleHeight;
    private int particlesPerTick;
    private boolean showOwnParticles;
    private ChatColor protocolFoundLogColor;
    private ChatColor protocolNotFoundLogColor;
    private final Map<UUID, BukkitRunnable> particleTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> actionBarTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> offMessageTasks = new HashMap<>();
    private final Map<UUID, BukkitRunnable> timeLimitTasks = new HashMap<>();
    private final Map<UUID, BossBar> bossBars = new HashMap<>();
    private final Map<UUID, Long> camCooldowns = new HashMap<>();
    private final Map<UUID, BukkitRunnable> cooldownTasks = new HashMap<>();
    private final Map<UUID, Long> lastDamageTimes = new HashMap<>();
    private boolean shuttingDown = false;
    private NamespacedKey bodyKey;
    private NamespacedKey hitboxKey;
    private NamespacedKey hiddenArmorAsset;
    private boolean hiddenArmorLogged;
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

    // Configurable values
    private boolean maxDistanceEnabled;
    private double maxDistance;
    private int distanceWarningCooldown;
    private boolean armorStandNameVisible;
    private boolean armorStandVisible;
    private boolean armorStandGravity;
    private BodyType bodyType;
    private boolean mannequinImmovable;
    private VisibilityMode playerVisibilityMode;
    private boolean allowInvisibilityPotion;
    private boolean allowLavaFlight;
    private Object Sound;

    // Damage transfer settings
    private DamageMode damageMode;
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

    @Override
    public void onEnable() {
        shuttingDown = false;
        saveDefaultConfig();
        loadConfigValues();
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
        camFireGuard = new CamFireGuard(this);
        camFireGuard.loadConfig(getConfig());
        if (muteAttack || muteFootsteps || hideSprintParticles) {
            if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
                protocolLibAvailable = true;
                new ProtocolLibHook(this, mutedPlayers, muteAttack, muteFootsteps, hideSprintParticles);
                String pfMessage = getMessage("protocol-found");
                if (protocolFoundLogColor != null) {
                    pfMessage = protocolFoundLogColor + pfMessage + ChatColor.RESET;
                }
                getLogger().info(ChatColor.stripColor(pfMessage));
            }
        }
        // Beim Start ist niemand im Cam-Modus -> ein uebrig gebliebenes Team entfernen.
        deleteNoCollisionTeam();
        this.getCommand("cam").setExecutor(new CamCommand(this));
        this.getCommand("cam").setTabCompleter(new CamTabCompleter());
        this.getServer().getPluginManager().registerEvents(this, this);
        refreshNoCollisionTeam();
        getLogger().info("CameraPlugin wurde aktiviert!");
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
        mutedPlayers.clear();
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
        boolean originalSilent = player.isSilent();
        int originalRemainingAir = player.getRemainingAir();

        // *** Inventar und Rüstung leeren ***
        playerInventory.clear();
        playerInventory.setArmorContents(new ItemStack[4]);// Leeres Array für Rüstungsslots
        player.updateInventory();

        Location playerLocation = player.getLocation();

        LivingEntity body = spawnCameraBody(player, playerLocation, originalRemainingAir);

        // A mannequin always wears the player's armour and always takes the hits,
        // so the server calculates the damage the same way for both body types.
        Mannequin hitbox = null;
        if (bodyType.usesSeparateHitbox()) {
            // The mannequin next to the armour stand is invisible, so it wears
            // copies whose armour is not rendered either.
            hitbox = spawnHitbox(player, playerLocation, createHiddenArmor(originalArmor));
            hitbox.teleport(body.getLocation());
        } else {
            EntityEquipment bodyEquipment = body.getEquipment();
            if (bodyEquipment != null) {
                bodyEquipment.setArmorContents(createMirrorArmor(originalArmor));
            }
        }
        // Whoever wears the armour is the entity that takes the hits.
        LivingEntity damageTarget = hitbox != null ? hitbox : body;

        GameMode originalGameMode = player.getGameMode();
        boolean originalAllowFlight = player.getAllowFlight();
        boolean originalFlying = player.isFlying();

        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        if (!protocolLibAvailable && (muteAttack || muteFootsteps)) {
            player.setSilent(true);
        }
        if (!protocolLibAvailable && (muteAttack || muteFootsteps)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        }


        new BukkitRunnable() {
            @Override
            public void run() {
                player.setGameMode(GameMode.ADVENTURE);
                player.setAllowFlight(true); // ensure flight remains enabled
                player.setFlying(true);       // keep player flying
                if (allowInvisibilityPotion && !player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
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
        cameraPlayers.put(player.getUniqueId(), new CameraData(body, hitbox, originalGameMode, originalAllowFlight, originalFlying, originalSilent, originalInventory, originalArmor, pausedEffects, originalRemainingAir));
        bodyOwners.put(body.getUniqueId(), player.getUniqueId());
        if (hitbox != null) {
            hitboxEntities.put(hitbox.getUniqueId(), player.getUniqueId());
        }
        if (protocolLibAvailable) {
            mutedPlayers.add(player.getUniqueId());
        }

        if (hitbox != null) {
            startHitboxSync(body, hitbox);
        }
        startCameraParticles(player);
        startActionBar(player);
        camFireGuard.startFor(player);
        startBodyHealthCheck(player, body);
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
     * Copies the player's armour for the entity that mirrors the damage. The real
     * items are used when they are supposed to lose durability, clones otherwise.
     */
    private ItemStack[] createMirrorArmor(ItemStack[] originalArmor) {
        if (damageArmor) {
            return originalArmor;
        }
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
     * rendering, so that it protects the body without the pieces floating in
     * front of the armour stand.
     *
     * <p>Copies are enough here: the body never really takes the damage, its
     * damage event is cancelled. The durability is taken from the player's own
     * armour when the damage is mirrored onto him, and his items are therefore
     * left untouched.</p>
     */
    private ItemStack[] createHiddenArmor(ItemStack[] originalArmor) {
        ItemStack[] hiddenArmor = new ItemStack[originalArmor.length];
        boolean stillVisible = false;
        ItemStack sample = null;
        for (int i = 0; i < originalArmor.length && i < ARMOR_SLOTS.length; i++) {
            if (originalArmor[i] == null) {
                continue;
            }
            ItemStack copy = originalArmor[i].clone();
            if (!EquipmentVisibility.hide(copy, ARMOR_SLOTS[i], hiddenArmorAsset)) {
                stillVisible = true;
            }
            hiddenArmor[i] = copy;
            if (sample == null) {
                sample = copy;
            }
        }
        if (stillVisible) {
            getLogger().warning("Die Rüstung des unsichtbaren Mannequins konnte nicht ausgeblendet werden, "
                    + "sie bleibt am Körper sichtbar. Setter: " + EquipmentVisibility.describeAssetSetter());
        } else if (sample != null && !hiddenArmorLogged) {
            // Once per start, so it can be checked whether the component really
            // reaches the item when the armour is still visible on the client.
            hiddenArmorLogged = true;
            getLogger().info("Rüstung des unsichtbaren Mannequins ausgeblendet über "
                    + EquipmentVisibility.describeAssetSetter()
                    + ", Komponente am Item: " + EquipmentVisibility.describe(sample));
        }
        return hiddenArmor;
    }

    /**
     * Creates the invisible mannequin that takes the hits for an armour stand
     * body. A mannequin has the same hitbox as a player and wears the player's
     * armour, so the server calculates the damage just like it would for the
     * player himself.
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
        hitbox.setInvisible(true);
        hitbox.setSilent(true);
        hitbox.setGravity(false);
        hitbox.setImmovable(mannequinImmovable);
        hitbox.setInvulnerable(false);
        hitbox.setCustomName(getMessage("hitbox.name-format").replace("{player}", player.getName()));
        hitbox.setCustomNameVisible(false);
        hitbox.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        hitbox.setCanPickupItems(false);
        return hitbox;
    }

    /**
     * Spawns the body that stays behind while the player is in camera mode.
     * Depending on {@code body.type} this is either an armour stand wearing the
     * player's head or, on Minecraft 1.21.9 and newer, a mannequin that uses the
     * player's own skin.
     */
    private LivingEntity spawnCameraBody(Player player, Location location, int remainingAir) {
        LivingEntity body = bodyType == BodyType.MANNEQUIN
                ? spawnMannequinBody(player, location)
                : spawnArmorStandBody(player, location);

        body.setRemainingAir(remainingAir);
        body.getPersistentDataContainer().set(bodyKey, PersistentDataType.INTEGER, 1);
        body.setGravity(armorStandGravity);
        body.setCanPickupItems(false);
        body.setCustomName(getMessage("armorstand.name-format").replace("{player}", player.getName()));
        body.setCustomNameVisible(armorStandNameVisible);
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
        mannequin.setImmovable(mannequinImmovable);
        return mannequin;
    }

    /** Creates the classic body: an armour stand wearing the player's head. */
    private ArmorStand spawnArmorStandBody(Player player, Location location) {
        ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);
        armorStand.setVisible(armorStandVisible);
        armorStand.setMarker(false);
        armorStand.addEquipmentLock(EquipmentSlot.HEAD, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.CHEST, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.LEGS, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.FEET, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.HAND, ArmorStand.LockType.REMOVING_OR_CHANGING);
        armorStand.addEquipmentLock(EquipmentSlot.OFF_HAND, ArmorStand.LockType.REMOVING_OR_CHANGING);

        ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
        if (skullMeta != null) {
            skullMeta.setOwningPlayer(player);
            playerHead.setItemMeta(skullMeta);
        }
        armorStand.getEquipment().setHelmet(playerHead);
        return armorStand;
    }

    public void exitCameraMode(Player player) {
        CameraData cameraData = cameraPlayers.get(player.getUniqueId());
        if (cameraData == null) {
            // Ensure players are removed from the no-collision team even if the
            // CameraData has already been cleaned up by another call.
            removePlayerFromNoCollisionTeam(player);
            mutedPlayers.remove(player.getUniqueId());
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
        player.setSilent(cameraData.getOriginalSilent());
        player.setRemainingAir(cameraData.getOriginalRemainingAir());

        removePlayerFromNoCollisionTeam(player);

        cameraPlayers.remove(player.getUniqueId());
        mutedPlayers.remove(player.getUniqueId());
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
     * Watches the body while camera mode is running. Drowning, suffocation, fire
     * and lava are not checked here any more: the mannequin takes that damage
     * itself and {@link #onBodyDamage(EntityDamageEvent)} ends camera mode. All
     * that is left is noticing when the body is moved away from its spot.
     */
    private void startBodyHealthCheck(Player player, LivingEntity body) {
        final Location initialLocation = body.getLocation().clone();
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!cameraPlayers.containsKey(player.getUniqueId()) || !player.isOnline() || body.isDead()) {
                    this.cancel();
                    return;
                }
                if (!body.getLocation().getWorld().equals(initialLocation.getWorld()) ||
                        body.getLocation().distanceSquared(initialLocation) > 0.01) {
                    sendConfiguredMessage(player, "body-moved");
                    exitCameraMode(player);
                    this.cancel();
                }
            }
        }.runTaskTimer(this, 20L, 1L);
    }

    /** Keeps the invisible mannequin exactly where the armour stand body is. */
    private void startHitboxSync(LivingEntity body, Mannequin hitbox) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (body.isDead() || hitbox.isDead()) {
                    this.cancel();
                    return;
                }
                hitbox.teleport(body.getLocation());
            }
        }.runTaskTimer(this, 1L, 1L);
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
     * Every hit on the body ends camera mode. The mannequin takes the hit for
     * both body types, so the damage is always calculated the same way, and
     * there is no separate check for lava, water or blocks any more: the
     * mannequin takes that damage itself and the damage event is all that is
     * needed to notice it.
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

        CameraData data = cameraPlayers.get(ownerUUID);

        // The armour stand is a little taller than the mannequin, so a hit can
        // land on it instead. Such a hit is passed on to the mannequin, so that
        // the damage is calculated on the entity wearing the player's armour in
        // every case. The player's own hit just ends camera mode below.
        if (damagedBody && data != null && data.getHitbox() != null && !data.getHitbox().isDead()
                && event instanceof EntityDamageByEntityEvent byEntity
                && !byEntity.getDamager().getUniqueId().equals(owner.getUniqueId())
                && !pendingDamage.contains(ownerUUID)) {
            event.setCancelled(true);
            data.getHitbox().damage(event.getDamage(), byEntity.getDamager());
            if (!cameraPlayers.containsKey(ownerUUID)) {
                return; // the mannequin took the hit and camera mode has ended
            }
            // The mannequin ignored the hit, so it is handled here after all.
        }

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


        boolean ignoreBodyArmor = event.getCause() == DamageCause.ENTITY_EXPLOSION ||
                event.getCause() == DamageCause.BLOCK_EXPLOSION ||
                event.getCause() == DamageCause.FALLING_BLOCK;
        double applyDamage;
        switch (damageMode) {
            case MIRROR -> {
                if (damageArmor) {
                    // TNT explosions and falling anvils should only count the player's armour once
                    if (ignoreBodyArmor) {
                        applyDamage = event.getDamage();
                    } else {
                        applyDamage = event.getFinalDamage();
                    }
                } else {
                    if (ignoreBodyArmor) {
                        // use raw damage and let the player's armour reduce it later
                        applyDamage = event.getDamage();
                    } else {
                        // armour shouldn't lose durability, so apply the already reduced amount
                        applyDamage = event.getFinalDamage();
                    }
                }
            }
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
            double finalDamage = applyDamage;
            Entity finalDamager = damagerEntity == null ? damagedEntity : damagerEntity;
            final org.bukkit.damage.DamageSource damageSource;
            if (event.getCause() == DamageCause.ENTITY_EXPLOSION || event.getCause() == DamageCause.BLOCK_EXPLOSION) {
                org.bukkit.damage.DamageType type = event.getCause() == DamageCause.ENTITY_EXPLOSION
                        ? org.bukkit.damage.DamageType.PLAYER_EXPLOSION
                        : org.bukkit.damage.DamageType.EXPLOSION;
                var builder = org.bukkit.damage.DamageSource.builder(type);
                if (damagerEntity != null) {
                    builder.withCausingEntity(damagerEntity).withDirectEntity(damagerEntity);
                }
                damageSource = builder.build();
            } else if (event.getCause() == DamageCause.FALLING_BLOCK) {
                damageSource = null;
            } else {
                damageSource = null;
            }
            new BukkitRunnable() {
                @Override
                public void run() {
                    ItemStack[] saved = null;
                    if (!damageArmor) {
                        saved = owner.getInventory().getArmorContents();
                        ItemStack[] temp;
                        if (ignoreBodyArmor) {
                            temp = new ItemStack[saved.length];
                            for (int i = 0; i < saved.length; i++) {
                                if (saved[i] != null) temp[i] = saved[i].clone();
                            }
                        } else {
                            temp = new ItemStack[4];
                        }
                        owner.getInventory().setArmorContents(temp);
                        owner.updateInventory();
                    }
                    if (damageSource != null) {
                        owner.damage(finalDamage, damageSource);
                    } else {
                        owner.damage(finalDamage, finalDamager);
                    }
                    if (finalDamager instanceof LivingEntity attacker) {
                        ItemStack weapon = attacker.getEquipment().getItemInMainHand();
                        int fireLevel = weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
                        if (fireLevel > 0) {
                            int ticks = Math.max(owner.getFireTicks(), fireLevel * 80);
                            owner.setFireTicks(ticks);
                        }
                    } else if (finalDamager instanceof AbstractArrow arr) {
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
            }.runTaskLater(this, 2L);
        }

        pendingDamage.remove(ownerUUID);
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
        mutedPlayers.remove(event.getPlayer().getUniqueId());
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

    @EventHandler
    public void onPotionEffectChange(EntityPotionEffectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!allowInvisibilityPotion) return;
        if (playerVisibilityMode == VisibilityMode.NONE) return;
        if (cameraPlayers.containsKey(player.getUniqueId())) return;
        if (!PotionEffectType.INVISIBILITY.equals(event.getModifiedType())) return;

        Bukkit.getScheduler().runTask(this, () -> updateViewerTeam(player));
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

    private void loadConfigValues() {
        maxDistanceEnabled = getConfig().getBoolean("camera-mode.max-distance-enabled", true);
        maxDistance = getConfig().getDouble("camera-mode.max-distance", 100.0);
        distanceWarningCooldown = getConfig().getInt("camera-mode.distance-warning-cooldown", 3);
        String visibility = getConfig().getString("camera-mode.player_visibility_mode", "cam").toLowerCase();
        playerVisibilityMode = switch (visibility) {
            case "true" -> VisibilityMode.ALL;
            case "false" -> VisibilityMode.NONE;
            default -> VisibilityMode.CAM;
        };
        allowInvisibilityPotion = getConfig().getBoolean("camera-mode.allow_invisibility_potion", true);
        allowLavaFlight = getConfig().getBoolean("camera-mode.allow_lava_flight", false);
        cameraHeadEnabled = getConfig().getBoolean("camera-head.enabled", false);
        armorStandNameVisible = getConfig().getBoolean("armorstand.name-visible", true);
        armorStandVisible = getConfig().getBoolean("armorstand.visible", true);
        armorStandGravity = getConfig().getBoolean("armorstand.gravity", true);
        bodyType = resolveBodyType(getConfig().getInt("body.type", BodyType.ARMOR_STAND.getId()));
        mannequinImmovable = getConfig().getBoolean("body.mannequin-immovable", true);
        muteAttack = getConfig().getBoolean("mute.attack", false);
        muteFootsteps = getConfig().getBoolean("mute.footsteps", false);
        hideSprintParticles = getConfig().getBoolean("mute.hide-sprint-particles", true);
        particleHeight = getConfig().getDouble("camera-particles.height", 1.0);
        particlesPerTick = getConfig().getInt("camera-particles.particles-per-tick", 5);
        showOwnParticles = getConfig().getBoolean("camera-particles.show-own-particles", false);
        protocolFoundLogColor = parseColor(getConfig().getString("log-colors.protocol-found", ""));
        actionBarEnabled = getConfig().getBoolean("action-bar.enabled", true);
        actionBarOffDuration = getConfig().getInt("action-bar.off-duration", 10);
        actionBarOnMessage = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.actionbar-on", "&aCam-Modus aktiviert"));
        actionBarOffMessage = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.actionbar-off", "&cCam-Modus beendet"));
        timeLimitEnabled = getConfig().getBoolean("time-limit.enabled", false);
        cooldownsEnabled = getConfig().getBoolean("time-limit.cooldowns-enabled", false);
        durationSeconds = getConfig().getInt("time-limit.duration-seconds", 300);
        cooldownSeconds = getConfig().getInt("time-limit.cooldown-seconds", 120);
        showBossbar = getConfig().getBoolean("time-limit.show-bossbar", true);
        String colorName = getConfig().getString("time-limit.bossbar-color", "BLUE");
        try {
            bossbarColor = BarColor.valueOf(colorName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            bossbarColor = BarColor.BLUE;
        }
        bossbarText = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.bossbar-text", "Cam-Modus endet in: %time%"));
        cooldownText = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.cooldown-text", "Du kannst den Cam-Modus erst in %time% erneut starten."));
        cooldownAvailableText = ChatColor.translateAlternateColorCodes('&', getConfig().getString("messages.cooldown-available", "&aCam-Modus wieder verf\u00fcgbar"));
        camSafetyEnabled = getConfig().getBoolean("cam-safety.enabled", true);
        camSafetyDelay = getConfig().getInt("cam-safety.delay", 5);
        camSafetyMessage = getConfig().getString("messages.cam-safety",
                "§cDu kannst den Cam-Modus nicht starten! Du musst noch %seconds% Sekunden in Sicherheit bleiben.");

        damageArmor = getConfig().getBoolean("mirror-damage.damage-armor", true);
        String modeRaw = getConfig().getString("mirror-damage.damage-mode", "mirror");
        if ("custom".equalsIgnoreCase(modeRaw)) {
            damageMode = DamageMode.CUSTOM;
        } else if ("false".equalsIgnoreCase(modeRaw) || "off".equalsIgnoreCase(modeRaw)) {
            damageMode = DamageMode.OFF;
        } else {
            damageMode = DamageMode.MIRROR;
        }
        customDamageHearts = getConfig().getDouble("mirror-damage.custom-damage-hearts", 0.5);
        if (camFireGuard != null) {
            camFireGuard.loadConfig(getConfig());
        }
    }

    /**
     * Turns the number configured in {@code body.type} into a body type and
     * falls back to the armour stand when the value is unknown.
     */
    private BodyType resolveBodyType(int configuredId) {
        BodyType requested = BodyType.fromId(configuredId);
        if (requested == null) {
            getLogger().warning("Unbekannter Wert für body.type: " + configuredId
                    + ". Es wird 1 (Rüstungsständer) verwendet.");
            return BodyType.ARMOR_STAND;
        }
        return requested;
    }

    private ChatColor parseColor(String colorName) {
        if (colorName == null || colorName.isEmpty()) {
            return null;
        }
        try {
            return ChatColor.valueOf(colorName.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
        team.setCanSeeFriendlyInvisibles(true);
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

    private void updateViewerTeam(Player player) {
        if (cameraPlayers.isEmpty()) {
            // Niemand im Cam-Modus -> das Team wird nicht gebraucht.
            deleteNoCollisionTeam();
            return;
        }
        Team team = ensureNoCollisionTeam();

        boolean inCam = cameraPlayers.containsKey(player.getUniqueId());
        boolean shouldBeMember;

        if (inCam) {
            shouldBeMember = true;
        } else if (allowInvisibilityPotion && playerVisibilityMode == VisibilityMode.ALL) {
            shouldBeMember = !player.hasPotionEffect(PotionEffectType.INVISIBILITY);
        } else {
            shouldBeMember = false;
        }

        if (shouldBeMember) {
            if (!team.hasEntry(player.getName())) {
                team.addEntry(player.getName());
            }
        } else {
            if (team.hasEntry(player.getName())) {
                team.removeEntry(player.getName());
            }
        }
    }

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
            case ALL -> viewer.showPlayer(this, camPlayer);
            case NONE -> viewer.hidePlayer(this, camPlayer);
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
        loadConfigValues();
        protocolLibAvailable = false;
        mutedPlayers.clear();
        if (muteAttack || muteFootsteps || hideSprintParticles) {
            if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
                protocolLibAvailable = true;
                new ProtocolLibHook(this, mutedPlayers, muteAttack, muteFootsteps, hideSprintParticles);
                String pfMessage = getMessage("protocol-found");
                if (protocolFoundLogColor != null) {
                    pfMessage = protocolFoundLogColor + pfMessage + ChatColor.RESET;
                }
                getLogger().info(ChatColor.stripColor(pfMessage));
            }
        }
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
        private final boolean originalSilent;
        private final int originalRemainingAir;
        private final ItemStack[] originalInventoryContents; // Für Inventar
        private final ItemStack[] originalArmorContents;     // Für Rüstung
        private final Collection<PotionEffect> pausedEffects;

        public CameraData(LivingEntity body, Mannequin hitbox, GameMode originalGameMode, boolean originalAllowFlight, boolean originalFlying, boolean originalSilent, ItemStack[] originalInventoryContents, ItemStack[] originalArmorContents, Collection<PotionEffect> pausedEffects, int originalRemainingAir) {
            this.body = body;
            this.hitbox = hitbox;
            this.originalGameMode = originalGameMode;
            this.originalAllowFlight = originalAllowFlight;
            this.originalFlying = originalFlying;
            this.originalSilent = originalSilent;
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
        public boolean getOriginalSilent() { return originalSilent; }
        public ItemStack[] getOriginalInventoryContents() { return originalInventoryContents; }
        public ItemStack[] getOriginalArmorContents() { return originalArmorContents; }
        public Collection<PotionEffect> getPausedEffects() { return pausedEffects; }
        public int getOriginalRemainingAir() { return originalRemainingAir; }
    }
}