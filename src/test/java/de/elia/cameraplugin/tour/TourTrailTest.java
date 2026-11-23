package de.elia.cameraplugin.tour;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TourTrail}: where the cameras of the guests should be, two ticks
 * behind their guide, and how fast they go on from there - the middle one of
 * three steps, so that a step the network lost or doubled does not jerk them.
 */
class TourTrailTest {

    private static final double DELTA = 1e-9;

    private final TourTrail trail = new TourTrail();

    private static void assertVector(double x, double y, double z, Vector actual) {
        assertEquals(x, actual.getX(), DELTA, "x of " + actual);
        assertEquals(y, actual.getY(), DELTA, "y of " + actual);
        assertEquals(z, actual.getZ(), DELTA, "z of " + actual);
    }

    /** The guide one step further along x each tick, starting at {@code from}. */
    private Vector walk(double from, double step, int ticks) {
        Vector pace = null;
        for (int i = 1; i <= ticks; i++) {
            pace = trail.next(new Vector(from + i * step, 64, 0), new Vector(step, 0, 0), true);
        }
        return pace;
    }

    @Test
    void nothingBehindTheGuideBeforeTheFirstTick() {
        assertNull(trail.behind());
    }

    @Test
    void newTourStartsWhereTheGuideStands() {
        Vector pace = trail.next(new Vector(10, 64, 5), new Vector(), true);

        assertVector(0, 0, 0, pace);
        assertVector(10, 64, 5, trail.behind());
    }

    @Test
    void camerasAreWhereTheGuideWasTwoTicksAgo() {
        trail.next(new Vector(0, 64, 0), new Vector(), true);

        walk(0, 1, 5);

        assertVector(3, 64, 0, trail.behind());
    }

    @Test
    void steadyWalkGivesItsOwnPace() {
        trail.next(new Vector(0, 64, 0), new Vector(), true);

        assertVector(0.5, 0, 0, walk(0, 0.5, 3));
    }

    @Test
    void paceStartsOnlyOnceTwoStepsSayTheGuideMoves() {
        trail.next(new Vector(0, 64, 0), new Vector(), true);

        assertVector(0, 0, 0, walk(0, 1, 1));
        assertVector(1, 0, 0, walk(1, 1, 1));
    }

    @Test
    void stepTheNetworkLostOrDoubledDoesNotJerkTheCameras() {
        trail.next(new Vector(0, 64, 0), new Vector(), true);
        walk(0, 1, 3);

        // No step in one tick, two in the next: the middle one is still one.
        Vector lost = trail.next(new Vector(3, 64, 0), new Vector(0, 0, 0), true);
        Vector doubled = trail.next(new Vector(5, 64, 0), new Vector(2, 0, 0), true);

        assertVector(1, 0, 0, lost);
        assertVector(1, 0, 0, doubled);
    }

    @Test
    void jumpOfTheGuideIsLeftOutOfThePace() {
        trail.next(new Vector(0, 64, 0), new Vector(), true);
        walk(0, 1, 3);

        Vector pace = trail.next(new Vector(103, 64, 0), new Vector(100, 0, 0), true);

        assertVector(1, 0, 0, pace);
    }

    @Test
    void camerasStopTwoTicksAfterTheGuide() {
        trail.next(new Vector(0, 64, 0), new Vector(), true);
        walk(0, 1, 3);

        Vector first = trail.next(new Vector(3, 64, 0), new Vector(), true);
        Vector second = trail.next(new Vector(3, 64, 0), new Vector(), true);

        assertVector(1, 0, 0, first);
        assertVector(0, 0, 0, second);
    }

    @Test
    void eachAxisIsSmoothedOnItsOwn() {
        trail.next(new Vector(0, 64, 0), new Vector(), true);
        trail.next(new Vector(1, 65, 0), new Vector(1, 1, 0), true);
        trail.next(new Vector(1, 66, 1), new Vector(0, 1, 1), true);

        Vector pace = trail.next(new Vector(2, 66, 2), new Vector(1, 0, 1), true);

        assertVector(1, 1, 1, pace);
    }

    @Test
    void otherWorldStartsTheWayAnew() {
        trail.next(new Vector(0, 64, 0), new Vector(), true);
        walk(0, 1, 5);

        Vector pace = trail.next(new Vector(-40, 70, 8), new Vector(), false);

        assertVector(0, 0, 0, pace);
        assertVector(-40, 70, 8, trail.behind());
    }

    @Test
    void middleOfThreeWhateverTheirOrder() {
        double[][] orders = {{1, 2, 3}, {1, 3, 2}, {2, 1, 3}, {2, 3, 1}, {3, 1, 2}, {3, 2, 1}};
        for (double[] order : orders) {
            assertEquals(2.0, TourTrail.middle(order[0], order[1], order[2]), DELTA);
        }
        assertEquals(-1.0, TourTrail.middle(-1, -1, 5), DELTA);
        assertEquals(0.0, TourTrail.middle(0, 0, 0), DELTA);
    }
}
