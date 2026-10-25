package de.elia.cameraplugin.visit;

import org.bukkit.Location;

import java.util.UUID;

/**
 * A visit of {@code /cam visit}: the shared spot of another player the camera
 * has been taken to, which takes the place of the body while it lasts - the
 * distance, the signal and the wall go out from it.
 *
 * @param owner     whose spot it is
 * @param ownerName his name, as it was when he shared it last
 * @param spotKey   the key of the spot, see {@link de.elia.cameraplugin.spot.Spot#key()}
 * @param spotName  the name of the spot, capitals and all
 * @param anchor    where the spot is, in the world of the body
 */
public record Visit(UUID owner, String ownerName, String spotKey, String spotName, Location anchor) {

    /** Where the spot is, as a copy to be changed at will. */
    @Override
    public Location anchor() {
        return anchor.clone();
    }

    /** How the spot is named in a message and in a command: {@code Owner:spot}. */
    public String label() {
        return ownerName + ":" + spotName;
    }
}
