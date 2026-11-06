package de.elia.cameraplugin.potion;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.session.CameraPlayers;
import de.elia.cameraplugin.session.EndReason;
import org.bukkit.Bukkit;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Set;
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

        if (event.getNewEffect() == null) {
            // An effect taken off, not one that hit: /effect clear on the
            // body, or the body being cleared away, whose effects go with it.
            // That ended camera mode as well, "hit by the effect
            // invisibility" - the one that keeps the mannequin standing in
            // the armour stand out of sight, which is to stay on it.
            event.setCancelled(true);
            return;
        }

        Player owner = Bukkit.getPlayer(ownerUUID);
        if (owner != null) {
            plugin.exitCameraMode(owner, EndReason.BODY);
            // After the exit: it takes off the invisibility and the night
            // vision of camera mode, and would take the same effect from the
            // body along with them.
            owner.addPotionEffect(event.getNewEffect());
            // After the exit, like the message about a hit on the body: first
            // he is back in his body, then he reads why.
            messages.sendMessage(owner, "body-got-effect", "{effect}",
                    messages.getName("effect-names", event.getModifiedType().getKey()));
        }

        event.setCancelled(true);
    }

    /**
     * Passes the effects of a tipped arrow that hit the body on to its
     * player. The body takes no damage, so the arrow hands them to nobody by
     * itself.
     *
     * <p>Called by {@link de.elia.cameraplugin.mirrordamage.DamageMirror} once
     * the hit counts, and not as soon as the arrow touches the body: a hit a
     * protected region turned away left the camera running and the player
     * poisoned all the same.</p>
     */
    public static void passOnArrowEffects(Arrow arrow, Player player) {
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
     *
     * <p>Last of all, and only for a throw that still counts: what a
     * protection plugin turns away - the whole throw, or the body by an
     * intensity of zero - does not reach him through his body either.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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

    /**
     * What the world hands to the players around it gives nothing to the
     * camera: a beacon, a conduit, the warden, the sculk shrieker, the elder
     * guardian and the wither rose.
     *
     * <p>All of them hand their effect to every player in their reach, and
     * the camera is one to the game. With regeneration camera mode healed: fly
     * to the beacon, wait, and be back at the body with full hearts. Whatever
     * else it gives, strength or resistance, he took along to his body - and
     * the same went for the darkness of a warden or a shrieker the camera flew
     * past, the mining fatigue of an elder guardian in its monument and the
     * wither of a wither rose it flew through. His body is no player and gets
     * nothing either, as it stands where he stood.</p>
     *
     * <p>The elder guardian hands its effect out as an attack. A real attack
     * never reaches the camera - every hit on it is turned away, and whatever
     * the hit on his body brings comes once camera mode is over -, so whatever
     * comes as one while camera mode runs is the elder guardian's.</p>
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCameraPlayerEffect(EntityPotionEffectEvent event) {
        if (event.getNewEffect() != null && FROM_THE_WORLD.contains(event.getCause())
                && event.getEntity() instanceof Player player && cameraPlayers.contains(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** The causes of what the world hands out to the players around it, see {@link #onCameraPlayerEffect}. */
    private static final Set<EntityPotionEffectEvent.Cause> FROM_THE_WORLD = EnumSet.of(
            EntityPotionEffectEvent.Cause.BEACON,
            EntityPotionEffectEvent.Cause.CONDUIT,
            EntityPotionEffectEvent.Cause.WARDEN,
            EntityPotionEffectEvent.Cause.ATTACK,
            EntityPotionEffectEvent.Cause.WITHER_ROSE);
}
