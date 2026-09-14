package de.elia.cameraplugin.portal;

import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

/**
 * The two portals the section {@code portals} has a say over, each with the
 * name it carries in the config file.
 *
 * <p>A portal is recognised by the cause of the transit and not by the block
 * the player stands in: the cause is what the server itself calls the trip,
 * so it also fits a portal that leads the other way - out of the Nether, say,
 * where the very same {@code nether} decides.</p>
 */
public enum PortalKind {
    /** Nether portal, in either direction. */
    NETHER("nether", TeleportCause.NETHER_PORTAL),
    /** End portal, in either direction. */
    END("end", TeleportCause.END_PORTAL);

    private final String configName;
    private final TeleportCause cause;

    PortalKind(String configName, TeleportCause cause) {
        this.configName = configName;
        this.cause = cause;
    }

    /** The name of this portal the way it is written in the config file. */
    public String getConfigName() {
        return configName;
    }

    /**
     * The portal a transit belongs to.
     *
     * @return the portal, or {@code null} when the trip is none of the two -
     *         an end gateway or a chorus fruit, which no setting here rules
     *         over
     */
    public static PortalKind of(TeleportCause cause) {
        for (PortalKind kind : values()) {
            if (kind.cause == cause) {
                return kind;
            }
        }
        return null;
    }
}
