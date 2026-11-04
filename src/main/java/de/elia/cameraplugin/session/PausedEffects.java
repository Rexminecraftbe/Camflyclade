package de.elia.cameraplugin.session;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;

/**
 * Gives a player back the effects camera mode paused, together with the
 * hearts they stood for.
 *
 * <p>Taking an effect off and putting it on again is not quite a pause. Two of
 * them come back different from how they went:</p>
 * <ul>
 *     <li>Absorption fills its yellow hearts up again when it is put on, to
 *     what its level gives, however many of them had been used up. Without
 *     this {@code /cam} twice would be a free refill.</li>
 *     <li>Health boost takes the health above the normal maximum with it when
 *     it goes, and comes back with the extra hearts empty.</li>
 * </ul>
 *
 * <p>Both are set back to how they stood when camera mode began - never
 * lower than they are now, so whatever reached the player while the effects
 * were paused counts, an absorption arrow say or a heal.</p>
 */
public final class PausedEffects {

    private PausedEffects() {
    }

    /**
     * Puts the paused effects back on the player, and the hearts with them.
     *
     * @param health     his health when camera mode began, or {@code null} to
     *                   leave it as the effects make it
     * @param absorption his absorption when camera mode began, or
     *                   {@code null} to leave it as the effects make it
     */
    public static void giveBack(Player player, Collection<PotionEffect> effects, Double health, Double absorption) {
        // Read before the effects come back: absorption fills up as it does.
        double absorptionBefore = player.getAbsorptionAmount();
        for (PotionEffect effect : effects) {
            player.addPotionEffect(effect);
        }
        if (player.isDead()) {
            // Dying: there is nothing to give back to.
            return;
        }
        if (absorption != null && contains(effects, PotionEffectType.ABSORPTION)) {
            player.setAbsorptionAmount(Math.max(0.0, Math.max(absorptionBefore, absorption)));
        }
        if (health != null && contains(effects, PotionEffectType.HEALTH_BOOST)) {
            AttributeInstance max = player.getAttribute(Attribute.MAX_HEALTH);
            double wanted = max == null ? health : Math.min(health, max.getValue());
            if (player.getHealth() < wanted) {
                player.setHealth(wanted);
            }
        }
    }

    private static boolean contains(Collection<PotionEffect> effects, PotionEffectType type) {
        for (PotionEffect effect : effects) {
            if (effect.getType().equals(type)) {
                return true;
            }
        }
        return false;
    }
}
