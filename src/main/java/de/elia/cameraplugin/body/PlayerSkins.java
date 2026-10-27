package de.elia.cameraplugin.body;

import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

/**
 * The head of a player on an item: the menu shows players by their heads, and
 * the armour stand body wears the head of its player.
 *
 * <p>A head whose player has no skin in his profile - on a server that does
 * not check the players with Mojang, every player - is filled in by the
 * server afterwards, from Mojang, in the background, for every single head.
 * For an id Mojang does not know there is nothing to find, and whenever Mojang
 * is slow to answer, the console fills up with timeouts. So such a player
 * gets a plain head: the skin it would have shown is the plain one anyway.</p>
 */
public final class PlayerSkins {

    private PlayerSkins() {
    }

    /** Puts his head onto the skull, when the server has his skin; else it stays a plain head. */
    public static void putOn(SkullMeta skull, Player player) {
        if (known(player)) {
            skull.setOwningPlayer(player);
        }
    }

    /**
     * Whether the server has the skin of a player: the textures in his
     * profile, which a server that checks the players with Mojang hands every
     * player at the login. Read at runtime, as {@code getPlayerProfile} hands
     * out a type of its own on Paper, see {@link MannequinSkin}; whatever
     * cannot be read counts as known, as before.
     */
    static boolean known(Player player) {
        try {
            Object profile = Player.class.getMethod("getPlayerProfile").invoke(player);
            return !(profile instanceof PlayerProfile bukkit) || !bukkit.getTextures().isEmpty();
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return true;
        }
    }
}
