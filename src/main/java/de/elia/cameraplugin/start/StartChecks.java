package de.elia.cameraplugin.start;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Whether a player may start camera mode right now: not straight after a hit,
 * not as a spectator, not with the wrong effects on him and not in a forbidden
 * area.
 */
public final class StartChecks implements Listener {

    private final CamSettings settings;
    private final Messages messages;
    private final Map<UUID, Long> lastDamageTimes = new HashMap<>();

    public StartChecks(CamSettings settings, Messages messages) {
        this.settings = settings;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void recordLastDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            lastDamageTimes.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    /** Forgets the last hit of a player who left the server. */
    public void forget(UUID playerId) {
        lastDamageTimes.remove(playerId);
    }

    public boolean checkCamSafety(Player player) {
        if (!settings.isCamSafetyEnabled()) return true;
        Long last = lastDamageTimes.get(player.getUniqueId());
        if (last == null) return true;
        long elapsed = System.currentTimeMillis() - last;
        long delayMillis = settings.getCamSafetyDelay() * 1000L;
        if (elapsed >= delayMillis) return true;
        long remaining = (delayMillis - elapsed + 999) / 1000;
        String msg = settings.getCamSafetyMessage().replace("%seconds%", String.valueOf(remaining));
        if (messages.isMessageEnabled("cam-safety")) {
            player.sendMessage(ChatColor.RED + ChatColor.translateAlternateColorCodes('&', msg));
        }
        return false;
    }

    /**
     * Checks whether the player may start camera mode where he is: out of the
     * spectator mode he may not, whatever {@code camera-mode.gamemode} says.
     *
     * <p>On {@code keep} he would stay a spectator, and that is the mode
     * camera mode has no answer to: he passes through blocks, and that walks
     * straight through {@code max-distance}, through the areas of
     * {@code cam-area} and through the portal rules, which all measure a
     * player who has to fly around a wall. With one click he also puts himself
     * next to any entity on the server. Nobody outside the spectator mode sees
     * him either, so the body left behind, the glowing outline and the
     * particles would say nothing about where he is.</p>
     *
     * <p>The three fixed modes would take him out of the spectator mode, and
     * camera mode used to do exactly that. He is turned away there as well: a
     * spectator is watching already and is not where his body would be put
     * down, so starting camera mode would drop a body at a spot he only flew
     * past and hand him back a mode he did not ask for when he leaves.</p>
     *
     * @return whether he may start; if not, he has been told why
     */
    public boolean checkCamSpectator(Player player) {
        if (player.getGameMode() != GameMode.SPECTATOR) {
            return true;
        }
        messages.sendMessage(player, "cam-spectator-start");
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
        if (settings.getStartWithEffects() == EffectStart.ANY) {
            return true;
        }
        List<String> blocking = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            PotionEffectType type = effect.getType();
            if (settings.getStartWithEffects() == EffectStart.POSITIVE
                    && type.getCategory() != PotionEffectTypeCategory.HARMFUL) {
                continue;
            }
            blocking.add(type.getKey().getKey());
        }
        if (blocking.isEmpty()) {
            return true;
        }
        messages.sendMessage(player, "cam-effect-start", "{effect}", String.join(", ", blocking));
        return false;
    }

    /**
     * Checks whether camera mode may be started where the player is standing,
     * as long as {@code cam-area.level} is not 0.
     *
     * <p>Which areas those are is decided by
     * {@link de.elia.cameraplugin.area.CamAreaRules}: the dimension the world
     * belongs to and the biome the player stands in.</p>
     *
     * @return {@code true} when he may start, otherwise {@code false} and he
     *         has been told why
     */
    public boolean checkCamArea(Player player) {
        if (!settings.getCamAreaRules().getLevel().blocksStart()) {
            return true;
        }
        String area = settings.getCamAreaRules().forbiddenArea(player.getLocation());
        if (area == null) {
            return true;
        }
        messages.sendMessage(player, "cam-area-start", "{area}", area);
        return false;
    }
}
