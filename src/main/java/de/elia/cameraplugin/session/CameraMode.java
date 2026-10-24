package de.elia.cameraplugin.session;

import de.elia.cameraplugin.body.BodySpawner;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.display.GlowMode;
import de.elia.cameraplugin.scoreboard.NoCollisionTeam;
import de.elia.cameraplugin.visibility.VisibilityMode;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
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
        // *** Save inventory and armour ***
        // getContents() holds every slot, armour and off hand included.
        // The armour is needed once more on its own for the body.
        PlayerInventory playerInventory = player.getInventory();
        ItemStack[] originalInventory = playerInventory.getContents();
        ItemStack[] originalArmor = playerInventory.getArmorContents();
        // Before the effects are paused: the absorption goes with its effect,
        // and the yellow hearts over the body are to show what he had.
        double absorption = player.getAbsorptionAmount();
        Collection<PotionEffect> pausedEffects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            pausedEffects.add(effect);
            player.removePotionEffect(effect.getType());
        }
        boolean originalGlowing = player.isGlowing();
        int remainingAir = player.getRemainingAir();
        int freezeTicks = player.getFreezeTicks();

        // *** Empty inventory and armour ***
        playerInventory.clear();
        player.updateInventory();

        Location playerLocation = player.getLocation();

        BodySpawner bodies = plugin.getBodySpawner();
        LivingEntity body = bodies.spawnCameraBody(player, playerLocation, originalArmor);

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
        // It breathes and freezes in his place from where he left off, and he
        // takes both back from it when he leaves. The mannequin and not the
        // armour stand of the armorstand body: it has his eye height, so it
        // goes under water when he would, and it wears the copies of his
        // armour, so leather keeps the frost off it as it keeps it off him.
        damageTarget.setRemainingAir(remainingAir);
        damageTarget.setFreezeTicks(freezeTicks);
        TextDisplay nameDisplay = bodies.spawnNameDisplay(player, body, absorption);
        TextDisplay heartsFront = bodies.spawnHeartsFront(player, body, absorption);
        Chunk bodyChunk = bodies.holdChunk(body);

        GameMode originalGameMode = player.getGameMode();
        boolean originalAllowFlight = player.getAllowFlight();
        boolean originalFlying = player.isFlying();
        // Read here and not a tick later: the creative mode below is the kick
        // that gets the flight going, and on "keep" it would be the answer to
        // "which mode was he in" from then on.
        GameMode flyingGameMode = cameraGameMode(originalGameMode);

        // Before the creative mode below, too: the game names a player in
        // creative mode as no mob's target, so the mobs after him would not be
        // found and would keep him as their target. The ones steered by their
        // brain are only noted down here and handed over in the creative tick.
        plugin.getMobTargeting().turnMobsFromPlayer(player, damageTarget);

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

        // Two ticks and not one: the world has to tick once with him in
        // creative mode. A mob steered by its brain takes no setTarget, but it
        // lets go of a player in creative mode by itself - in that tick, and
        // that is when turnMobsFromPlayer hands it over. The command that
        // starts camera mode runs between two ticks, so a task one tick later
        // would come before the world had ticked at all.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !cameraPlayers.contains(player.getUniqueId())) {
                    // Camera mode is over already, and his own game mode is back.
                    return;
                }
                player.setGameMode(flyingGameMode);
                player.setAllowFlight(true); // ensure flight remains enabled
                player.setFlying(true);       // keep player flying
                if (plugin.getVisibility().needsInvisibility() && !player.hasPotionEffect(PotionEffectType.INVISIBILITY)) {
                    player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
                }
            }
        }.runTaskLater(plugin, 2L);

        // *** Hand the saved inventory to CameraData ***
        CameraData cameraData = new CameraData(body, hitbox, nameDisplay, bodyChunk, originalGameMode, originalAllowFlight, originalFlying, originalGlowing, originalInventory, pausedEffects);
        cameraData.setHeartsFront(heartsFront);
        cameraPlayers.put(player.getUniqueId(), cameraData);
        // Before the flight speed below: the file is to hold the one he had.
        plugin.getCrashGuard().save(player, cameraData);
        cameraPlayers.addBody(body.getUniqueId(), player.getUniqueId());
        if (hitbox != null) {
            cameraPlayers.addHitbox(hitbox.getUniqueId(), player.getUniqueId());
        }

        plugin.getParticles().startCameraParticles(player);
        plugin.getSightGlow().startSightGlow(player);
        plugin.getNameTag().startFor(player);
        plugin.getActionBar().startActionBar(player);
        plugin.getFlightSpeed().startFor(player);
        plugin.getNightVision().startFor(player);
        plugin.getFireGuard().startFor(player);
        plugin.getHungerGuard().startFor(player);
        plugin.getGhastGuard().startFor(player);
        plugin.getSulfurCubeGuard().startFor(player);
        plugin.getInventoryGuard().startFor(player);
        plugin.getBorderWall().startFor(player);
        // The entity taking the hits is the mannequin for both body types, so the
        // movement check always runs on it. Both calls look at the sensitivity
        // level and only one of them does anything.
        plugin.getBodyWatch().startBodyMovementCheck(player, damageTarget);
        plugin.getBodyWatch().startBodyPin(player, body, hitbox);
        // After the pin: in the same tick the name then finds the body back in
        // its spot already, instead of following it away for that one tick.
        plugin.getBodyWatch().keepNameOverBody(player, body, nameDisplay, heartsFront, absorption);
        plugin.getMobTargeting().startMobTargeting(player, damageTarget);
        plugin.getBodyAlarm().startFor(player, damageTarget);
        // Creates the teams when needed and sets all members anew - the
        // player is in cameraPlayers already and so joins it as well, and
        // his body joins the one that keeps players and mobs from pushing it.
        plugin.getNoCollisionTeam().refreshNoCollisionTeam();
        plugin.getVisibility().updateVisibilityForAll();
        plugin.getCamModeObjective().setScore(player, 1);
        plugin.getTimeLimit().startTimeLimit(player);
        plugin.getBattery().startFor(player);
        plugin.getAfk().startFor(player);
        plugin.getCamLog().started(player, cameraData);
        plugin.getTrail().startFor(player, playerLocation);

        if (settings.isCameraHeadEnabled()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    ItemStack camHead = CameraHead.create(plugin.getLogger());
                    player.getInventory().setHelmet(camHead);
                }
            }.runTaskLater(plugin, 2L); // delay to ensure armour is restored
        }
        settings.getStartSound().play(player);
    }

    public void exitCameraMode(Player player, EndReason reason) {
        exitCameraMode(player, true, reason);
    }

    /**
     * Ends camera mode for a player a command or another plugin is
     * teleporting away right now, see
     * {@link de.elia.cameraplugin.teleport.CamTeleportGuard}. He is not put
     * back at his body first: the teleport under way takes him on from where
     * he is.
     */
    public void exitCameraModeForTeleport(Player player) {
        exitCameraMode(player, false, EndReason.TELEPORT);
    }

    /**
     * @param toBody whether he is put back where his body stands; only not
     *               when a teleport carries him off anyway
     * @param reason why it ends, which picks the sound and goes into the log
     */
    private void exitCameraMode(Player player, boolean toBody, EndReason reason) {
        CameraData cameraData = cameraPlayers.get(player.getUniqueId());
        NoCollisionTeam team = plugin.getNoCollisionTeam();
        if (cameraData == null) {
            // Ensure players are removed from the no-collision team and get
            // their hunger back even if the CameraData has already been
            // cleaned up by another call. updateViewerTeam takes him out of
            // the team, he is no camera player any more.
            plugin.getHungerGuard().stopFor(player);
            plugin.getGhastGuard().stopFor(player);
            plugin.getSulfurCubeGuard().stopFor(player);
            plugin.getInventoryGuard().stopFor(player);
            plugin.getBorderWall().stopFor(player);
            plugin.getFlightSpeed().stopFor(player);
            plugin.getNightVision().stopFor(player);
            plugin.getBattery().stopFor(player);
            plugin.getAfk().stopFor(player);
            team.updateViewerTeam(player);
            plugin.getCamModeObjective().setScore(player, 0);
            return;
        }
        plugin.getTimeLimit().cancelTimeLimit(player);
        plugin.getBattery().stopFor(player);
        plugin.getAfk().stopFor(player);
        plugin.getMobTargeting().stopMobTargeting(player);
        plugin.getBodyAlarm().stopFor(player);
        LivingEntity body = cameraData.getBody();
        Mannequin hitbox = cameraData.getHitbox();
        // For the log, before he is put back at his body.
        Location cameraAt = player.getLocation();
        // Before the teleport to the body as well, which is no part of the flight.
        plugin.getTrail().stopFor(player, cameraAt);

        // The name comes off before the teleport: while it sits on the
        // player as a passenger, Spigot turns the teleport down.
        plugin.getNameTag().stopFor(player);
        if (toBody) {
            plugin.getTeleportGuard().teleport(player, body.getLocation());
        }
        plugin.getParticles().stopCameraParticles(player);
        plugin.getSightGlow().stopSightGlow(player);
        plugin.getActionBar().showActionBarOffMessage(player);
        // Asked where he stands: at his body - or, carried off by a
        // teleport, at the camera, whose fire he never stood in.
        boolean standingInFire = plugin.getFireGuard().stopFor(player) && toBody;
        plugin.getGhastGuard().stopFor(player);
        plugin.getSulfurCubeGuard().stopFor(player);
        // After the teleport to the body: the wall may have stood in another
        // world, and then there is nothing left to give back there.
        plugin.getBorderWall().stopFor(player);
        // Before the inventory is given back: the sweep empties the pockets
        // of the camera player and would otherwise take the player's own
        // inventory away again.
        plugin.getInventoryGuard().stopFor(player);

        // *** Restore inventory and armour ***
        // setContents() writes every slot back, armour included.
        PlayerInventory playerInventory = player.getInventory();
        playerInventory.clear(); // Emptied to be safe, in case items were added
        playerInventory.setContents(cameraData.getOriginalInventoryContents());
        player.updateInventory();

        player.removePotionEffect(PotionEffectType.INVISIBILITY);
        // Before the paused effects come back: night vision among them would
        // otherwise be taken off along with the one of camera mode.
        plugin.getNightVision().stopFor(player);

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
        plugin.getFlightSpeed().stopFor(player);
        // He stands where the mannequin stood, in the same water or powder
        // snow: what air it has left and how frozen it got is his now.
        LivingEntity standIn = cameraData.getDamageTarget();
        player.setRemainingAir(standIn.getRemainingAir());
        player.setFreezeTicks(standIn.getFreezeTicks());
        plugin.getHungerGuard().stopFor(player);

        cameraPlayers.remove(player.getUniqueId());
        // Takes him out of the team - or the whole team away, when he was the
        // last camera player.
        team.updateViewerTeam(player);
        plugin.getCamModeObjective().setScore(player, 0);

        // The aggro goes back to the player, who is standing where his body
        // stood. Deliberately only here, after he has stopped being a camera
        // player: onMobTarget keeps mobs off a camera player and would send
        // them straight back to the body that is removed a moment later.
        plugin.getMobTargeting().turnMobsBackToPlayer(player, body, hitbox);

        // Clean up
        cameraPlayers.removeBody(body.getUniqueId());
        if (hitbox != null) {
            cameraPlayers.removeHitbox(hitbox.getUniqueId());
        }

        BodySpawner bodies = plugin.getBodySpawner();
        bodies.remove(body);
        if (hitbox != null) {
            bodies.remove(hitbox);
        }
        TextDisplay nameDisplay = cameraData.getNameDisplay();
        if (nameDisplay != null) {
            bodies.remove(nameDisplay);
        }
        if (cameraData.getHeartsFront() != null) {
            bodies.remove(cameraData.getHeartsFront());
        }
        // Last: until the body is gone, its chunk has to stay.
        bodies.releaseChunk(cameraData.getBodyChunk());

        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(plugin, player);
        }
        plugin.getVisibility().updateVisibilityForAll();
        // Only now that he has everything back: until here the file is the
        // one thing that would give it to him after a crash.
        plugin.getCrashGuard().delete(player);
        plugin.getStats().record(player, cameraData);
        plugin.getCamLog().ended(player, cameraData, reason, cameraAt);
        if (!plugin.isShuttingDown()) {
            plugin.getTimeLimit().startCooldown(player, reason);
        }
        // One write for all three, the battery left over, the flight counted
        // and the cooldown begun.
        plugin.getPlayerStore().save(player.getUniqueId());
        if (!plugin.isShuttingDown()) {
            // Last, at his body: the sound says he is back, and why.
            settings.getEndSound(reason).play(player);
        }
    }
}
