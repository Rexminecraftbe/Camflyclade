package de.elia.cameraplugin.session;

import org.bukkit.entity.Player;

/**
 * Sends a player's frost to his own client once more, even when the server's
 * count has not changed.
 *
 * <p>The client counts the frost of its player up by itself while it sees him
 * in powder snow; only the server counts it down, and it sends the count only
 * when its own changes. A camera the server kept from where its client had
 * flown already - a step over a border it stopped, the camera of a guest on a
 * tour, sent after the guide - may have been in powder snow on the client
 * only. Its frost would stay on the player's screen, without a single point
 * of damage, until he joins the server anew.</p>
 *
 * <p>Set once more through a value in between, the count is new to the
 * server, and the next tick sends it.</p>
 */
public final class ClientFrost {

    private ClientFrost() {
    }

    /** Sends him the frost the server counts for him. */
    public static void resend(Player player) {
        resend(player, player.getFreezeTicks());
    }

    /** Gives him this frost and sends it to his client, whatever it counted by itself. */
    public static void resend(Player player, int ticks) {
        player.setFreezeTicks(ticks + 1);
        player.setFreezeTicks(ticks);
    }
}
