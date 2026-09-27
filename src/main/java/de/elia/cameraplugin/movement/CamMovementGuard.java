package de.elia.cameraplugin.movement;

import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.portal.PortalKind;
import de.elia.cameraplugin.portal.PortalReturn;
import de.elia.cameraplugin.portal.PortalRules;
import de.elia.cameraplugin.session.CameraData;
import de.elia.cameraplugin.session.CameraPlayers;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Keeps the camera player where camera mode allows him to be: out of lava,
 * water and powder snow as far as their switches say, within
 * {@code camera-mode.max-distance} of his body, out of the areas
 * {@code cam-area} forbids, and only through the portals that let him.
 */
public final class CamMovementGuard implements Listener {

    /**
     * How long a portal is left alone after it turned a camera player away, in
     * ticks. Without it the server would try the very same trip again straight
     * away - an end portal is stepped on and not waited in, so it would ask
     * every single tick - and the player would stand in a portal that tells him
     * off without end.
     *
     * <p>The same waiting time is put on a player who is brought back, in case
     * he is set down in a portal himself: the one he set out through.</p>
     */
    private static final int PORTAL_COOLDOWN_TICKS = 100;

    private final CameraPlugin plugin;
    private final CamSettings settings;
    private final Messages messages;
    private final CameraPlayers cameraPlayers;
    private final Map<UUID, Long> distanceMessageCooldown = new HashMap<>();
    /** Players who were just told that camera mode is not allowed where they are heading. */
    private final Map<UUID, Long> areaMessageCooldown = new HashMap<>();
    /** Players who were just told that a portal does not let them through. */
    private final Map<UUID, Long> portalMessageCooldown = new HashMap<>();
    /** Players who were just told that lava, water or powder snow does not let them in. */
    private final Map<UUID, Long> mediumMessageCooldown = new HashMap<>();

    public CamMovementGuard(CameraPlugin plugin) {
        this.plugin = plugin;
        this.settings = plugin.getSettings();
        this.messages = plugin.getMessages();
        this.cameraPlayers = plugin.getCameraPlayers();
    }

    /** Forgets the message cooldowns of a player who left the server. */
    public void forget(UUID playerId) {
        distanceMessageCooldown.remove(playerId);
        areaMessageCooldown.remove(playerId);
        portalMessageCooldown.remove(playerId);
        mediumMessageCooldown.remove(playerId);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!cameraPlayers.contains(player.getUniqueId())) return;
        Location to = event.getTo();
        if (to == null) return;

        if (entersClosedMedium(player, event.getFrom(), to)) {
            event.setCancelled(true);
            return;
        }

        if (entersForbiddenArea(player, event.getFrom(), to)) {
            event.setCancelled(true);
            return;
        }

        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (!settings.isMaxDistanceEnabled() || !beyondMaxDistance(data, to)) {
            return;
        }
        if (beyondMaxDistance(data, event.getFrom())) {
            // He is out there already, so stopping the step alone would nail him
            // to the spot instead of keeping him near his body - which is
            // exactly what a portal used to do to him. He is brought back.
            //
            // The step is deliberately not cancelled here: a cancelled step
            // puts the player back where he came from once this returns, and
            // that is out there - it would undo the teleport that just brought
            // him in. The step is sent to the spot he belongs at instead.
            warnDistanceLimit(player, "portal-return-distance", data, event.getFrom());
            event.setTo(bringBack(player, data));
            return;
        }
        event.setCancelled(true);
        warnDistanceLimit(player, "distance-limit", data, to);
    }

    /**
     * Tells the player that he has reached the end of his leash, at most once
     * every {@code camera-mode.distance-warning-cooldown} seconds - the step is
     * stopped over and over as long as he keeps walking against the border.
     *
     * @param key   the message: the one for a step that does not go through, or
     *              the one for a player who is brought back
     * @param where the spot the distance was measured at, which decides what
     *              {@code {from}} names
     */
    private void warnDistanceLimit(Player player, String key, CameraData data, Location where) {
        if (!mayWarn(distanceMessageCooldown, player, settings.getDistanceWarningCooldown())) {
            return;
        }
        messages.sendMessage(player, key, "{distance}", String.valueOf(settings.getMaxDistance()),
                "{from}", distanceAnchorName(data, where));
    }

    /**
     * Whether a warning of this kind may go out to the player now. If so, the
     * next one has to wait {@code seconds}: the border and the portal ask over
     * and over, and the chat would fill up with the same line.
     */
    private static boolean mayWarn(Map<UUID, Long> cooldowns, Player player, int seconds) {
        long now = System.currentTimeMillis();
        if (cooldowns.getOrDefault(player.getUniqueId(), 0L) >= now) {
            return false;
        }
        cooldowns.put(player.getUniqueId(), now + TimeUnit.SECONDS.toMillis(seconds));
        return true;
    }

    /**
     * What the distance is measured from at this spot, written out for a
     * message: his body, or - in a world his body is not in - the portal he
     * came out of there.
     *
     * <p>Follows {@link #distanceAnchor(CameraData, Location)}, so the message
     * never sends a player looking for his body while he is a world away from
     * it.</p>
     */
    private String distanceAnchorName(CameraData data, Location where) {
        boolean fromPortal = !data.getBody().getWorld().equals(where.getWorld())
                && data.getPortalAnchor() != null;
        String text = messages.getMessage(fromPortal ? "distance-from-portal" : "distance-from-body");
        // Ein Satzteil, kein ganzer Satz: Fehlt er in einer Konfiguration aus
        // einer aelteren Version, stuende sonst "... blocks from !" im Chat.
        if (!text.isEmpty()) {
            return text;
        }
        return fromPortal ? "the portal you came out of" : "your body";
    }

    /**
     * Whether a portal lets the camera player through, and what he finds on the
     * other side.
     *
     * <p>Two things can shut a portal: the switch of its own kind under
     * {@code portals}, and {@code cam-area} forbidding the dimension behind it -
     * but only on level 2, the level that keeps him out of such a place while he
     * flies. A portal that is shut simply does not carry him anywhere; he keeps
     * standing where he is.</p>
     *
     * <p>Where a portal comes out cannot be asked beforehand, so a forbidden
     * biome or structure over there is found only by walking through once, see
     * {@link #afterPortal(Player, Location)}. From then on the portal is known
     * and shuts like the other two, as long as {@code portals.remember-blocked}
     * is on - and, under {@code portals.forget-changed}, only for as long as
     * what was found over there still holds, which is looked over at every
     * attempt.</p>
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCameraPortal(PlayerPortalEvent event) {
        Player player = event.getPlayer();
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        PortalKind kind = PortalKind.of(event.getCause());
        if (kind == null) {
            // An end gateway or a chorus fruit: no setting here rules over
            // those, only the distance to the body does.
            return;
        }
        PortalRules portalRules = settings.getPortalRules();
        boolean open = portalRules.letsThrough(kind);
        String area = null;
        if (open) {
            // Die Dimension hinter dem Portal steht schon vor der Reise fest.
            // Ein verbotenes Biom oder eine verbotene Struktur erst danach -
            // deshalb zaehlt hier, was eine fruehere Reise ergeben hat.
            area = forbiddenPortalDimension(event.getTo());
            if (area == null) {
                area = portalRules.forbiddenAreaBehind(event.getFrom(),
                        this::forbiddenCamArea);
            }
        }
        if (!open || area != null) {
            event.setCancelled(true);
            player.setPortalCooldown(PORTAL_COOLDOWN_TICKS);
            warnPortalShut(player, kind, area);
            return;
        }
        // The trip itself happens after this event, so the far side can only be
        // looked at from the next tick on.
        Location entry = event.getFrom().clone();
        Bukkit.getScheduler().runTask(plugin, () -> afterPortal(player, entry));
    }

    /**
     * A portal newly built lets go of every remembered portal it stands close
     * enough to, see
     * {@link PortalRules#forgetPortalsNear(org.bukkit.World, java.util.Collection)}.
     *
     * <p>Watched in every world and not only where camera players are: the one
     * building the portal is usually not the one who will be turned away by
     * it.</p>
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortalCreated(PortalCreateEvent event) {
        // Die Endplattform zaehlt nicht dazu: Sie wird jedes Mal neu gesetzt,
        // wenn jemand im End ankommt, und wuerde das Gemerkte dort bei jedem
        // fremden Besuch wegwerfen.
        if (event.getReason() == PortalCreateEvent.CreateReason.END_PLATFORM) {
            return;
        }
        settings.getPortalRules().forgetPortalsNear(event.getWorld(), event.getBlocks());
    }

    /**
     * The dimension a portal leads into, when {@code cam-area} does not allow
     * camera mode in it at all.
     *
     * <p>Only level 2 keeps the player out of it: level 1 has a say over the
     * start alone and lets him fly wherever he likes afterwards, portals
     * included.</p>
     *
     * @return the name of the dimension, or {@code null} when the portal may be
     *         used
     */
    private String forbiddenPortalDimension(Location to) {
        return settings.getCamAreaRules().getLevel().blocksFlight() ? settings.getCamAreaRules().forbiddenDimension(to) : null;
    }

    /**
     * The dimension, biome or structure that keeps camera mode out of this
     * spot, gated by the same level as {@link #forbiddenPortalDimension}.
     *
     * <p>Asked at the far side of a portal twice: once when a trip comes out
     * there, and from then on every time that portal is stepped into again -
     * an area that has been taken apart in the meantime is not to hold the
     * portal shut, see
     * {@link PortalRules#forbiddenAreaBehind(Location, PortalRules.AreaCheck)}.</p>
     *
     * @return the name of the area, or {@code null} when camera mode is allowed
     *         there
     */
    private String forbiddenCamArea(Location where) {
        return settings.getCamAreaRules().getLevel().blocksFlight() ? settings.getCamAreaRules().forbiddenArea(where) : null;
    }

    /**
     * Tells the player why the portal did not take him along: the portal itself
     * is shut, or camera mode is not allowed in what lies behind it.
     *
     * <p>He keeps standing in the portal, which asks again and again, so the
     * message waits {@code portals.warning-cooldown} seconds before it is sent
     * a second time.</p>
     *
     * @param area the forbidden dimension behind the portal, or the biome or
     *             structure an earlier trip through it came out in, or
     *             {@code null} when the portal is shut on its own
     */
    private void warnPortalShut(Player player, PortalKind kind, String area) {
        if (!mayWarn(portalMessageCooldown, player, settings.getPortalRules().getWarningCooldown())) {
            return;
        }
        if (area != null) {
            messages.sendMessage(player, "cam-area-limit", "{area}", area);
        } else {
            messages.sendMessage(player, "portal-blocked", "{portal}", kind.getConfigName());
        }
    }

    /**
     * What the camera player finds on the far side of a portal, one tick after
     * the trip.
     *
     * <p>His body stays where it was, so the far side decides what
     * {@code camera-mode.max-distance} is measured from: in another world it is
     * the portal he came out of, and once he is back in the world of his body
     * that body counts again. Whoever comes out too far away from it is brought
     * back, and so is whoever comes out in a biome or a structure camera mode is
     * not allowed in - a portal is not asked beforehand where it comes out. The
     * second kind is written down, together with the spot the trip came out at,
     * so that the same portal turns the next camera player away instead of
     * sending him over first - and so that the note can be checked against the
     * world later on instead of being believed.</p>
     *
     * @param entry where he stepped into the portal, the spot he is brought back
     *              to under {@code portals.return-to: portal}
     */
    private void afterPortal(Player player, Location entry) {
        CameraData data = cameraPlayers.get(player.getUniqueId());
        if (data == null || !player.isOnline()) {
            return;
        }
        Location arrival = player.getLocation();
        boolean withBody = data.getBody().getWorld().equals(arrival.getWorld());
        data.setPortalAnchor(withBody ? null : arrival.clone());
        if (!withBody && data.getPortalEntry() == null) {
            // The first portal of the trip is the one he is brought back to,
            // not whichever he went through last: that one is in the world he
            // is being taken out of.
            data.setPortalEntry(entry);
        }
        String area = forbiddenCamArea(arrival);
        if (area != null) {
            // Jetzt ist bekannt, wo dieses Portal herauskommt. Ohne das wuerde
            // es ihn bei jedem Durchgang aufs Neue hinueber und gleich wieder
            // zurueck schicken.
            settings.getPortalRules().rememberForbiddenArea(entry, area, arrival);
            messages.sendMessage(player, "portal-return-area", "{area}", area);
            bringBack(player, data);
            return;
        }
        if (settings.isMaxDistanceEnabled() && beyondMaxDistance(data, arrival)) {
            messages.sendMessage(player, "portal-return-distance", "{distance}",
                    String.valueOf(settings.getMaxDistance()), "{from}", distanceAnchorName(data, arrival));
            bringBack(player, data);
            return;
        }
        if (withBody) {
            // He is home, the trip is over: the next one gets a starting portal
            // of its own.
            data.setPortalEntry(null);
        }
    }

    /**
     * Puts a player back where camera mode holds him: at his body, or at the
     * portal he set out through, see {@code portals.return-to}.
     *
     * <p>The portal is only taken when it is a spot he would be allowed to stand
     * in anyway - in the world of his body and inside the distance to it -
     * otherwise he would be brought back to a place he would be brought back
     * from again. Without a portal to go back to it is the body, which is always
     * there.</p>
     *
     * @return the spot he was put down at
     */
    private Location bringBack(Player player, CameraData data) {
        // Whatever he was measured against over there is done with.
        data.setPortalAnchor(null);
        Location entry = data.getPortalEntry();
        data.setPortalEntry(null);
        Location target = returnTarget(data, entry);
        player.teleport(target);
        // He may well have been set down in the portal he set out through.
        player.setPortalCooldown(PORTAL_COOLDOWN_TICKS);
        keepFlying(player);
        return target;
    }

    /** Where {@link #bringBack(Player, CameraData)} puts the player down. */
    private Location returnTarget(CameraData data, Location entry) {
        if (settings.getPortalRules().getReturnTo() == PortalReturn.PORTAL && entry != null
                && entry.getWorld().equals(data.getBody().getWorld())
                && (!settings.isMaxDistanceEnabled() || !beyondMaxDistance(data, entry))) {
            return entry;
        }
        return data.getBody().getLocation();
    }

    /**
     * Keeps the player in the air after he was teleported, which takes flight
     * away from him. Done once more a tick later: the client is sent its own
     * flight state along with the teleport and would otherwise let him drop.
     *
     * <p>Also used by {@link de.elia.cameraplugin.ghast.CamGhastGuard} after it
     * has lifted the camera off a happy ghast.</p>
     */
    public void keepFlying(Player player) {
        player.setAllowFlight(true);
        player.setFlying(true);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (player.isOnline() && cameraPlayers.contains(player.getUniqueId())) {
                    player.setAllowFlight(true);
                    player.setFlying(true);
                }
            }
        }.runTaskLater(plugin, 1L);
    }

    /**
     * Whether this spot lies further from the body than
     * {@code camera-mode.max-distance} allows.
     *
     * <p>Measured from the body, or, while the player is in a world his body is
     * not in, from the portal he came out of there: his body cannot be reached
     * from that world, so the portal takes its place. A player in a world
     * neither of the two is in - one that something else carried him into - has
     * nothing left to measure against and counts as too far away, which brings
     * him back to his body.</p>
     */
    private boolean beyondMaxDistance(CameraData data, Location location) {
        Location anchor = distanceAnchor(data, location);
        return anchor == null || location.distanceSquared(anchor) > settings.getMaxDistance() * settings.getMaxDistance();
    }

    /**
     * What the distance to the body is measured from in the world of this spot,
     * or {@code null} when nothing there can be measured against.
     */
    private Location distanceAnchor(CameraData data, Location location) {
        Location body = data.getBody().getLocation();
        if (body.getWorld().equals(location.getWorld())) {
            return body;
        }
        Location portal = data.getPortalAnchor();
        return portal != null && portal.getWorld().equals(location.getWorld()) ? portal : null;
    }

    /**
     * Whether this step would carry the camera into lava, water or powder snow
     * while its switch under {@code camera-mode} is off, see
     * {@link FlightMedium}.
     *
     * <p>The step is simply cancelled, the way {@code cam-area} and
     * {@code camera-mode.max-distance} stop it at their border: the camera
     * stays at the edge, and camera mode goes on. Only a block the body is not
     * in already counts, so a camera the water has run over can still get out
     * of it, but no further in.</p>
     *
     * <p>The warning waits as long as the one at the distance border,
     * {@code camera-mode.distance-warning-cooldown}: the step is stopped again
     * and again as long as the player keeps pushing against the edge.</p>
     */
    private boolean entersClosedMedium(Player player, Location from, Location to) {
        FlightMedium medium = FlightMedium.reachedInto(player, settings, from, to);
        if (medium == null) {
            return false;
        }
        if (mayWarn(mediumMessageCooldown, player, settings.getDistanceWarningCooldown())) {
            messages.sendMessage(player, medium.getFlightMessage());
        }
        return true;
    }

    /**
     * Whether this step would carry the player into an area camera mode is not
     * allowed in, which only level 2 keeps him out of.
     *
     * <p>He is stopped at the border and not sent back to his body: the step is
     * simply cancelled, the same way {@code camera-mode.max-distance} does it.
     * Whoever already stands in such an area - because the rule only came later,
     * say - may keep moving until he is out of it, otherwise he would sit there
     * stuck.</p>
     *
     * <p>Only a step that leaves the block is looked at. Looking around fires
     * this event as well, and the biome of a spot does not change from that.</p>
     */
    private boolean entersForbiddenArea(Player player, Location from, Location to) {
        if (!settings.getCamAreaRules().getLevel().blocksFlight()
                || (from.getBlockX() == to.getBlockX()
                    && from.getBlockY() == to.getBlockY()
                    && from.getBlockZ() == to.getBlockZ()
                    && from.getWorld().equals(to.getWorld()))) {
            return false;
        }
        String area = settings.getCamAreaRules().forbiddenArea(to);
        if (area == null || settings.getCamAreaRules().forbiddenArea(from) != null) {
            return false;
        }
        if (mayWarn(areaMessageCooldown, player, settings.getCamAreaRules().getWarningCooldown())) {
            messages.sendMessage(player, "cam-area-limit", "{area}", area);
        }
        return true;
    }
}
