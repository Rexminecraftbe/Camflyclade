package de.elia.cameraplugin.portal;

import de.elia.cameraplugin.config.ConfigReader;

import java.util.EnumSet;
import java.util.Set;

/**
 * Whether a portal lets a camera player through at all: the section
 * {@code portals} of the config file.
 *
 * <p>Both portals are shut out of the box. Camera mode leaves a body behind,
 * and everything it is measured by sits with that body: how far the player may
 * get away from it and, once he is back beside it, where camera mode ends. A
 * portal takes him into a world his body is not in, so it is opened only where
 * that is wanted.</p>
 *
 * <p>This class only says what the config file says. Whether a portal is shut
 * for a second reason - {@code cam-area} forbidding the dimension behind it -
 * and what happens on the other side is decided by the plugin, which has the
 * body and the area rules at hand.</p>
 */
public final class PortalRules {

    /** How long the message at a shut portal waits before it is sent again, in seconds. */
    private static final int DEFAULT_WARNING_COOLDOWN = 3;

    private final Set<PortalKind> open = EnumSet.noneOf(PortalKind.class);
    private PortalReturn returnTo = PortalReturn.BODY;
    private int warningCooldown = DEFAULT_WARNING_COOLDOWN;

    /** Reads the whole section out of the config file. */
    public void load(ConfigReader config) {
        open.clear();
        for (PortalKind kind : PortalKind.values()) {
            if (config.getBoolean("portals." + kind.getConfigName(), false)) {
                open.add(kind);
            }
        }
        returnTo = PortalReturn.byName(config.getChoice("portals.return-to",
                PortalReturn.BODY.getConfigName(), PortalReturn.configNames()));
        warningCooldown = config.getInt("portals.warning-cooldown", DEFAULT_WARNING_COOLDOWN, 0);
    }

    /** Whether this kind of portal lets a camera player through. */
    public boolean letsThrough(PortalKind kind) {
        return open.contains(kind);
    }

    /** Where a player who has to be brought back is put down. */
    public PortalReturn getReturnTo() {
        return returnTo;
    }

    /** How long the message at a shut portal waits before it is sent again, in seconds. */
    public int getWarningCooldown() {
        return warningCooldown;
    }
}
