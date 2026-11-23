package de.elia.cameraplugin.battery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link BatteryCharge}: how a battery empties in flight and fills up outside camera mode. */
class BatteryChargeTest {

    private static final double DELTA = 1e-9;
    private static final double CAPACITY = 300.0;
    private static final double RECHARGE = 0.5;

    @Test
    void flightTakesASecondOfChargeEverySecond() {
        assertEquals(100.0, BatteryCharge.inFlight(100.0, 5_000L, 5_000L), DELTA);
        assertEquals(70.0, BatteryCharge.inFlight(100.0, 5_000L, 35_000L), DELTA);
        assertEquals(99.75, BatteryCharge.inFlight(100.0, 0L, 250L), DELTA);
    }

    @Test
    void flightStopsAtAnEmptyBattery() {
        assertEquals(0.0, BatteryCharge.inFlight(10.0, 0L, 10_000L), DELTA);
        assertEquals(0.0, BatteryCharge.inFlight(10.0, 0L, 60_000L), DELTA);
    }

    @Test
    void chargingAddsTheRechargeOfEverySecond() {
        assertEquals(20.0, BatteryCharge.charged(10.0, 0L, 20_000L, CAPACITY, RECHARGE), DELTA);
        assertEquals(10.25, BatteryCharge.charged(10.0, 0L, 500L, CAPACITY, RECHARGE), DELTA);
    }

    @Test
    void chargingStopsAtAFullBattery() {
        assertEquals(CAPACITY, BatteryCharge.charged(290.0, 0L, 60_000L, CAPACITY, RECHARGE), DELTA);
        assertEquals(CAPACITY, BatteryCharge.charged(0.0, 0L, Long.MAX_VALUE / 2, CAPACITY, RECHARGE), DELTA);
    }

    @Test
    void chargeAboveTheCapacityOfNowIsCutDown() {
        // battery.capacity made smaller in the config file: what was more than that is a full battery now.
        assertEquals(100.0, BatteryCharge.charged(250.0, 0L, 0L, 100.0, RECHARGE), DELTA);
    }

    @Test
    void clockThatWentBackGivesNothingBackAndTakesNothing() {
        assertEquals(50.0, BatteryCharge.charged(50.0, 10_000L, 4_000L, CAPACITY, RECHARGE), DELTA);
    }

    @Test
    void timeUntilEnoughIsRoundedUp() {
        assertEquals(20L, BatteryCharge.secondsUntil(10.0, 20.0, RECHARGE));
        assertEquals(1L, BatteryCharge.secondsUntil(10.0, 10.2, RECHARGE));
        assertEquals(3L, BatteryCharge.secondsUntil(0.0, 1.0, 0.4));
    }

    @Test
    void timeUntilEnoughIsNeverLessThanASecond() {
        assertEquals(1L, BatteryCharge.secondsUntil(20.0, 20.0, RECHARGE));
        assertEquals(1L, BatteryCharge.secondsUntil(30.0, 20.0, RECHARGE));
    }

    @Test
    void bossbarShowsHowFullTheBatteryIs() {
        assertEquals(0.5, BatteryCharge.progress(150.0, CAPACITY), DELTA);
        assertEquals(1.0, BatteryCharge.progress(CAPACITY, CAPACITY), DELTA);
        assertEquals(0.0, BatteryCharge.progress(0.0, CAPACITY), DELTA);
    }

    @Test
    void bossbarStaysBetweenEmptyAndFull() {
        assertEquals(0.0, BatteryCharge.progress(-5.0, CAPACITY), DELTA);
        assertEquals(1.0, BatteryCharge.progress(400.0, CAPACITY), DELTA);
    }

    @Test
    void startNeedsMinStart() {
        assertTrue(BatteryCharge.enoughToStart(10.0, 10.0));
        assertTrue(BatteryCharge.enoughToStart(250.0, 10.0));
        assertFalse(BatteryCharge.enoughToStart(9.99, 10.0));
    }

    @Test
    void emptyBatteryNeverStartsEvenWithoutMinStart() {
        assertFalse(BatteryCharge.enoughToStart(0.0, 0.0));
        assertTrue(BatteryCharge.enoughToStart(0.1, 0.0));
    }
}
