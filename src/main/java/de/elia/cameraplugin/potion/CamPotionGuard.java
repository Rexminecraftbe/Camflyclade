package de.elia.cameraplugin.potion;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Effects in camera mode: potions pass the camera player by, and what reaches
 * his body is passed on to him.
 */
public final class CamPotionGuard implements Listener {

    private final CameraPlugin plugin;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;

    public CamPotionGuard(CameraPlugin plugin) {
        this.plugin = plugin;
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
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
        UUID ownerUUID = cameraPlayers.getBodyOrHitboxOwner(entity);

        if (ownerUUID == null) return;

        Player owner = Bukkit.getPlayer(ownerUUID);
        if (owner != null) {
            PotionEffect newEffect = event.getNewEffect();
            if (newEffect != null) {
                owner.addPotionEffect(newEffect);
            }
            plugin.exitCameraMode(owner);
            // After the exit, like the message about a hit on the body: first
            // he is back in his body, then he reads why.
            messages.sendMessage(owner, "body-got-effect", "{effect}",
                    event.getModifiedType().getKey().getKey());
        }

        event.setCancelled(true);
    }

    /** Apply potion effects from a tipped arrow to the player. */
    public static void applyArrowEffects(Arrow arrow, Player player) {
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
                    && cameraPlayers.contains(camPlayer.getUniqueId())) {
                // Intensity zero is how the API says "not affected": he is
                // dropped from the list the potion works through.
                event.setIntensity(entity, 0.0);
                continue;
            }
            UUID owner = cameraPlayers.getBodyOrHitboxOwner(entity);
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
                && cameraPlayers.contains(player.getUniqueId()));
    }

    /** Transfer potion effects from arrows that hit the camera body. */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Entity hit = event.getHitEntity();
        if (hit == null) return;
        UUID owner = cameraPlayers.getBodyOrHitboxOwner(hit);
        if (owner == null) return;
        Player player = Bukkit.getPlayer(owner);
        if (player == null) return;
        Projectile proj = event.getEntity();
        if (proj instanceof Arrow arrow) {
            applyArrowEffects(arrow, player);
        }
    }
}
