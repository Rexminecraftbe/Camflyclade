package de.elia.cameraplugin.cost;

import org.bukkit.entity.Player;

/**
 * Experience as points, for {@code start-cost.xp-type: points}.
 *
 * <p>Worked out from the level and the bar, not taken from
 * {@code getTotalExperience()}: the game does not lower that one when
 * levels are spent at the enchanting table or the anvil, so it says more
 * than the player has.</p>
 */
final class XpPoints {

    private XpPoints() {
    }

    /** All the points a player has: his levels and what fills the bar towards the next one. */
    static int total(Player player) {
        long total = forLevel(player.getLevel()) + Math.round(player.getExp() * player.getExpToLevel());
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    /**
     * Gives a player exactly this many points, with the levels and the bar
     * that go with them.
     */
    static void set(Player player, int points) {
        player.setLevel(0);
        player.setExp(0.0f);
        player.setTotalExperience(0);
        player.giveExp(Math.max(0, points));
    }

    /** The points it takes to get from nothing to a level, by the formulas of the game. */
    private static long forLevel(int level) {
        long l = level;
        if (level <= 16) {
            return l * l + 6 * l;
        }
        if (level <= 31) {
            return Math.round(2.5 * l * l - 40.5 * l + 360);
        }
        return Math.round(4.5 * l * l - 162.5 * l + 2220);
    }
}
