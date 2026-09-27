package de.elia.cameraplugin.session;

import de.elia.cameraplugin.body.BodySpawner;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.display.CamActionBar;
import de.elia.cameraplugin.display.GlowMode;
import de.elia.cameraplugin.scoreboard.NoCollisionTeam;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;

/**
 * Puts a player into camera mode and takes him out again: his things are put
 * away and given back, his body is set down and cleared up, and everything
 * that runs alongside camera mode is started and stopped.
 */
public final class CameraMode {

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final CameraPlayers cameraPlayers;

    public CameraMode(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /**
     * The mode the camera player flies in, as {@code camera-mode.gamemode} has
     * it.
     *
     * <p>On {@code keep} that is the mode he walked in with, and camera mode
     * leaves it alone - the spectator mode excepted, which nobody flies in:
     * {@link de.elia.cameraplugin.start.StartChecks#checkCamSpectator(Player)}
     * turns a spectator away before camera mode starts, in every one of these
     * modes. That check sits at the command, and {@link #enterCameraMode} can
     * be called past it, so the same answer is given here once more; the
     * adventure mode is what camera mode ran in before this setting
     * existed.</p>
     *
     * @param startMode the mode the player stood in when he started, read
     *                  before the creative tick in {@link #enterCameraMode}
     *                  overwrites it
     */
    private GameMode cameraGameMode(GameMode startMode) {
        return switch (settings.getCamGameMode()) {
            case SURVIVAL -> GameMode.SURVIVAL;
            case CREATIVE -> GameMode.CREATIVE;
            case KEEP -> startMode == GameMode.SPECTATOR ? GameMode.ADVENTURE : startMode;
            case ADVENTURE -> GameMode.ADVENTURE;
        };
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

        BodySpawner bodies = plugin.getBodySpawner();
        LivingEntity body = bodies.spawnCameraBody(player, playerLocation, originalRemainingAir, originalArmor);

        // A mannequin takes the hits for both body types and is the entity the
        // movement check watches. It carries the player's armour so the body
        // looks like him; the damage itself is calculated on the player, see
        // onBodyDamage.
        Mannequin hitbox = null;
        if (bodies.usesMannequinBody()) {
            EntityEquipment bodyEquipment = body.getEquipment();
            if (bodyEquipment != null) {
                // Here body and hitbox are one and the same entity, so it wears
                // the armour either way: shown the way the player wears it, or
                // with its rendering taken away.
                bodyEquipment.setArmorContents(bodies.showsBodyArmor()
                        ? bodies.createMirrorArmor(originalArmor)
                        : bodies.createHiddenArmor(originalArmor));
            }
        } else {
            // The mannequin next to the armour stand is invisible, so it wears
            // copies whose armour is not rendered either. What is seen of the
            // armour hangs on the armour stand, see spawnArmorStandBody.
            hitbox = bodies.spawnHitbox(player, playerLocation, bodies.createHiddenArmor(originalArmor));
            hitbox.teleport(body.getLocation());
        }
        // The mannequin is the entity that takes the hits.
        LivingEntity damageTarget = hitbox != null ? hitbox : body;

        GameMode originalGameMode = player.getGameMode();
        boolean originalAllowFlight = player.getAllowFlight();
        boolean originalFlying = player.isFlying();
        // Read here and not a tick later: the creative mode below is the kick
        // that gets the flight going, and on "keep" it would be the answer to
        // "which mode was he in" from then on.
        GameMode flyingGameMode = cameraGameMode(originalGameMode);

        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlight(true);
        player.setFlying(true);
        if (settings.getGlowMode() == GlowMode.ALWAYS && settings.getPlayerVisibilityMode() != VisibilityMode.NONE) {
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
                player.setGameMode(flyingGameMode);
                player.setAllowFlight(true); // ensure flight remains enabled
                player.setFlying(true);       // keep player flying
                if (plugin.getVisibility().needsInvisibility() && !player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
                }
            }
        }.runTaskLater(plugin, 1L);

        plugin.getMobTargeting().turnMobsFromPlayer(player, damageTarget);

        // *** Gespeichertes Inventar an CameraData übergeben ***
        cameraPlayers.put(player.getUniqueId(), new CameraData(body, hitbox, originalGameMode, originalAllowFlight, originalFlying, originalGlowing, originalInventory, originalArmor, pausedEffects, originalRemainingAir));
        cameraPlayers.addBody(body.getUniqueId(), player.getUniqueId());
        if (hitbox != null) {
            cameraPlayers.addHitbox(hitbox.getUniqueId(), player.getUniqueId());
        }

        plugin.getParticles().startCameraParticles(player);
        plugin.getSightGlow().startSightGlow(player);
        plugin.getActionBar().startActionBar(player);
        plugin.getFireGuard().startFor(player);
        plugin.getHungerGuard().startFor(player);
        plugin.getGhastGuard().startFor(player);
        plugin.getInventoryGuard().startFor(player);
        // The entity taking the hits is the mannequin for both body types, so the
        // movement check always runs on it. Both calls look at the sensitivity
        // level and only one of them does anything.
        plugin.getBodyWatch().startBodyMovementCheck(player, damageTarget);
        plugin.getBodyWatch().startBodyPin(player, body, hitbox);
        plugin.getMobTargeting().startMobTargeting(player, damageTarget);
        NoCollisionTeam team = plugin.getNoCollisionTeam();
        team.addPlayerToNoCollisionTeam(player);
        // Team wurde evtl. gerade neu erstellt -> alle Mitglieder neu setzen.
        team.refreshNoCollisionTeam();
        plugin.getVisibility().updateVisibilityForAll();
        plugin.getCamModeObjective().setScore(player, 1);
        plugin.getTimeLimit().startTimeLimit(player);

        if (settings.isCameraHeadEnabled()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    ItemStack camHead = CameraHead.create(plugin.getLogger());
                    player.getInventory().setHelmet(camHead);
                }
            }.runTaskLater(plugin, 2L); // delay to ensure armour is restored
        }
    }

    public void exitCameraMode(Player player) {
        CameraData cameraData = cameraPlayers.get(player.getUniqueId());
        NoCollisionTeam team = plugin.getNoCollisionTeam();
        if (cameraData == null) {
            // Ensure players are removed from the no-collision team and get
            // their hunger back even if the CameraData has already been
            // cleaned up by another call.
            team.removePlayerFromNoCollisionTeam(player);
            plugin.getHungerGuard().stopFor(player);
            plugin.getGhastGuard().stopFor(player);
            plugin.getInventoryGuard().stopFor(player);
            team.updateViewerTeam(player);
            plugin.getCamModeObjective().setScore(player, 0);
            return;
        }
        plugin.getTimeLimit().cancelTimeLimit(player);
        plugin.getMobTargeting().stopMobTargeting(player);
        LivingEntity body = cameraData.getBody();
        Mannequin hitbox = cameraData.getHitbox();

        // Zuerst zum Körper teleportieren
        player.teleport(body.getLocation());
        plugin.getParticles().stopCameraParticles(player);
        plugin.getSightGlow().stopSightGlow(player);
        CamActionBar actionBar = plugin.getActionBar();
        actionBar.stopActionBar(player);
        if (!plugin.isShuttingDown()) {
            actionBar.showActionBarOffMessage(player);
        }
        boolean standingInFire = plugin.getFireGuard().stopFor(player);
        plugin.getGhastGuard().stopFor(player);
        // Vor der Rückgabe: Der Sweep räumt die Taschen des Kamera-Spielers
        // leer und nähme dem Spieler sonst sein eigenes Inventar wieder ab.
        plugin.getInventoryGuard().stopFor(player);

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
        plugin.getHungerGuard().stopFor(player);

        team.removePlayerFromNoCollisionTeam(player);

        cameraPlayers.remove(player.getUniqueId());
        team.updateViewerTeam(player);
        plugin.getCamModeObjective().setScore(player, 0);

        // Safety check to ensure the player really left the no-collision team
        team.removePlayerFromNoCollisionTeam(player);

        // The aggro goes back to the player, who is standing where his body
        // stood. Deliberately only here, after he has stopped being a camera
        // player: onMobTarget keeps mobs off a camera player and would send
        // them straight back to the body that is removed a moment later.
        plugin.getMobTargeting().turnMobsBackToPlayer(player, body, hitbox);

        // Aufräumen
        cameraPlayers.removeBody(body.getUniqueId());
        if (hitbox != null) {
            cameraPlayers.removeHitbox(hitbox.getUniqueId());
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
            other.showPlayer(plugin, player);
        }
        plugin.getVisibility().updateVisibilityForAll();
        if (!plugin.isShuttingDown()) {
            plugin.getTimeLimit().startCooldown(player);
        }
    }
}
