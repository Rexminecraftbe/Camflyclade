package de.elia.cameraplugin.tour;

import org.bukkit.util.Vector;

/**
 * The way of a guide that the cameras of his guests fly after him, two ticks
 * behind: where he was in the last three ticks, and the steps that took him
 * there, see {@link #next}. Each {@link Tour} has one. Nothing in here needs
 * the server, so the fast tests check it without one.
 */
final class TourTrail {

    /** Where the guide was in the last three ticks, the oldest first. */
    private final Vector[] path = new Vector[3];
    /** The steps that took him there, the oldest first. */
    private final Vector[] steps = new Vector[3];

    /**
     * Remembers where the guide is now, and gives the cameras of his guests
     * their way: they fly his way two ticks after him. Each should be where
     * he was two ticks ago, {@link #behind()}, and go on from there at the
     * pace returned - the step he took next, smoothed.
     *
     * <p>Pulled to where the guide is now, a camera reached his place a tick
     * or two after him, still going: when he stopped, it shot past him and
     * came back. Where he was two ticks ago, he really was: when he stops, the
     * cameras stop there two ticks later, and when he turns back, they turn
     * where he did.</p>
     *
     * <p>His client says where he is every tick, but the network hands the
     * server none of it in one tick and two in the next. Flown as it comes, a
     * camera would stop and leap after him by turns. So the pace is the middle
     * one of the step he took next and the steps on either side of it, axis
     * by axis - two ticks after him, the step after it is known already. The
     * middle one leaves out a single step too short or too long, even a jump
     * of the guide, which {@link TourFollowing#CATCH_UP} takes care of, and still follows
     * him in the very tick he speeds up or stops.</p>
     *
     * <p>A tenth of a second after him nobody sees: looking round freely, a
     * guest does not see his guide, see {@link TourVisibility#keepsApart}, and through the
     * guide's eyes, he does not see his own camera.</p>
     *
     * @param at    where the guide is now
     * @param step  the step he took since the last tick
     * @param along whether he was in this world last tick, too
     * @return the pace at which the cameras go on from where they should be
     */
    Vector next(Vector at, Vector step, boolean along) {
        if (!along || path[0] == null) {
            // A new tour, or a new world: he has been standing here.
            for (int i = 0; i < path.length; i++) {
                path[i] = at.clone();
                steps[i] = new Vector();
            }
            return new Vector();
        }
        path[0] = path[1];
        path[1] = path[2];
        path[2] = at.clone();
        steps[0] = steps[1];
        steps[1] = steps[2];
        steps[2] = step.clone();
        return new Vector(middle(steps[0].getX(), steps[1].getX(), steps[2].getX()),
                middle(steps[0].getY(), steps[1].getY(), steps[2].getY()),
                middle(steps[0].getZ(), steps[1].getZ(), steps[2].getZ()));
    }

    /**
     * Where the cameras of the guests should be now: where the guide was two
     * ticks ago. {@code null} until {@link #next} was first asked.
     */
    Vector behind() {
        return path[0];
    }

    /** The middle one of three numbers. */
    static double middle(double a, double b, double c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }
}
