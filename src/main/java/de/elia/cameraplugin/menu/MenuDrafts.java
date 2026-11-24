package de.elia.cameraplugin.menu;

import de.elia.cameraplugin.ban.CamBan;
import de.elia.cameraplugin.camfly2.CameraPlugin;
import de.elia.cameraplugin.spot.Spot;
import de.elia.cameraplugin.spot.SpotBans;
import de.elia.cameraplugin.spot.SpotLook;
import de.elia.cameraplugin.tour.TourBans;
import org.bukkit.Location;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What is set up on a page of {@link CamMenu} before it holds: a ban, a
 * restriction of the spots or of the tours, a new spot - and whom a page of a
 * player is about. Which of them each player has open the menu keeps.
 */
final class MenuDrafts {

    /** A day and an hour in minutes, for the buttons of the time of a ban. */
    static final long DAY = 24L * 60L;
    static final long HOUR = 60L;

    /** The longest ban the buttons of the time add up to, in minutes: 999 days. */
    static final long MAX_BAN_MINUTES = 999L * DAY;

    private MenuDrafts() {
    }

    /** Whom the page of a player is about: one player, by his id and name, or everybody. */
    record Target(UUID id, String name) {

        static Target everybody(String name) {
            return new Target(null, name);
        }

        boolean everybody() {
            return id == null;
        }

        boolean inCam(CameraPlugin plugin) {
            return id != null && plugin.getCameraPlayers().contains(id);
        }

        boolean banned(CamBan ban) {
            return id == null ? ban.everybodyBanned() : ban.isBanned(id);
        }
    }

    /**
     * A ban being set up on its page: whom, for how long and why - a new one,
     * or the one he has already, to be changed.
     */
    static final class BanDraft {
        final Target target;
        /** Whether he is banned already, and the page changes his ban. */
        final boolean change;
        /** How long, in minutes; 0 for good. */
        long minutes;
        String reason = "";

        BanDraft(Target target, boolean change) {
            this.target = target;
            this.change = change;
        }
    }

    /**
     * A restriction being set up on its page - of the spots or of the
     * tours, of one player or of everybody -: whom, for how long and why - a
     * new one, or the one that holds already, to be changed. What it keeps
     * him from is up to the kind of restriction.
     */
    abstract static class RestrictDraft {
        /** The player, or {@code null} for everybody. */
        final UUID id;
        final String name;
        /** Whether something keeps him already, and the page changes it. */
        final boolean change;
        /** How long, in minutes; 0 for good. */
        long minutes;
        String reason = "";

        RestrictDraft(UUID id, String name, boolean change) {
            this.id = id;
            this.name = name;
            this.change = change;
        }

        boolean everybody() {
            return id == null;
        }

        /** Whether it keeps him from anything at all. */
        abstract boolean any();
    }

    /** A restriction of the spots: how far it keeps him from each kind of spots. */
    static final class SpotBanDraft extends RestrictDraft {
        final Map<SpotBans.Kind, SpotBans.Stage> stages = new EnumMap<>(SpotBans.Kind.class);

        SpotBanDraft(UUID id, String name, boolean change) {
            super(id, name, change);
        }

        /** How far it keeps him from this kind of spots. */
        SpotBans.Stage stage(SpotBans.Kind kind) {
            return stages.getOrDefault(kind, SpotBans.Stage.FREE);
        }

        @Override
        boolean any() {
            return stages.values().stream().anyMatch(stage -> stage != SpotBans.Stage.FREE);
        }
    }

    /** A restriction of the tours: whether it keeps him from leading them, and from taking part. */
    static final class TourBanDraft extends RestrictDraft {
        final Set<TourBans.What> kept = EnumSet.noneOf(TourBans.What.class);

        TourBanDraft(UUID id, String name, boolean change) {
            super(id, name, change);
        }

        @Override
        boolean any() {
            return !kept.isEmpty();
        }
    }

    /**
     * A new spot being set up on its page, {@code viewpoints.create-page}:
     * where it is - where its owner was when he named it -, which way it
     * looks and what it shows, which are fixed; its name, its icon, whether
     * it is to be public and whom it is to be offered to, which the page
     * changes until it is saved - only then is any of it shared.
     */
    static final class NewSpot {
        String name;
        final Location where;
        final SpotLook look;
        String icon;
        boolean open;
        /** Whom it is offered to once it is saved, by id, with their names, in the order he picked them. */
        final Map<UUID, String> invited = new LinkedHashMap<>();

        NewSpot(String name, Location where, SpotLook look, String icon) {
            this.name = name;
            this.where = where;
            this.look = look;
            this.icon = icon;
        }

        /** The spot as it would be saved now, to show it. */
        Spot spot() {
            return Spot.at(name, where, System.currentTimeMillis(), look, icon);
        }
    }

    /** How many days, hours or minutes a ban of so many minutes has: of {@code unit}, in minutes. */
    static long amount(long minutes, long unit) {
        return unit == DAY ? minutes / DAY : unit == HOUR ? minutes % DAY / HOUR : minutes % HOUR;
    }

    /** The time a restriction that holds still has, rounded up to whole minutes, and its reason, into its draft. */
    static void keepTime(RestrictDraft draft, boolean forGood, long secondsLeft, String reason) {
        draft.minutes = forGood ? 0L : Math.max(1L, (secondsLeft + 59L) / 60L);
        draft.reason = reason;
    }
}
