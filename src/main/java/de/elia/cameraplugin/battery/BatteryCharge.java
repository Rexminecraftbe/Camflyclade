package de.elia.cameraplugin.battery;

/**
 * The arithmetic of the battery, see {@link CamBattery}: what it holds after
 * a while in flight or charging, how long until it holds enough, and how full
 * its bossbar is. Nothing in here needs the server, so the fast tests check
 * it without one.
 *
 * <p>Times are moments as {@link System#currentTimeMillis()} counts them,
 * charges seconds of flight.</p>
 */
final class BatteryCharge {

    private BatteryCharge() {
    }

    /**
     * What a battery in flight holds at {@code now}: a second of it for every
     * second since it held {@code startCharge}, down to empty.
     */
    static double inFlight(double startCharge, long startedAt, long now) {
        return Math.max(0.0, startCharge - (now - startedAt) / 1000.0);
    }

    /**
     * What a battery outside camera mode holds at {@code now}: what it held at
     * {@code at}, and {@code recharge} for every second since, up to
     * {@code capacity}. A clock that went back gives nothing back.
     */
    static double charged(double charge, long at, long now, double capacity, double recharge) {
        double back = Math.max(0L, now - at) / 1000.0 * recharge;
        return Math.min(capacity, charge + back);
    }

    /**
     * How long until a battery holding {@code charge} holds {@code wanted},
     * charging at {@code recharge} a second, in whole seconds rounded up - one
     * at least, so that no text says it takes no time at all.
     */
    static long secondsUntil(double charge, double wanted, double recharge) {
        return Math.max(1L, (long) Math.ceil((wanted - charge) / recharge));
    }

    /** How full the bossbar of a battery holding {@code charge} is, 0 to 1. */
    static double progress(double charge, double capacity) {
        return Math.max(0.0, Math.min(1.0, charge / capacity));
    }

    /**
     * Whether a battery holding {@code charge} is enough for a start,
     * {@code min-start}. Any charge at all is needed, also with
     * {@code min-start: 0} - an empty battery would end camera mode in the
     * very second it starts.
     */
    static boolean enoughToStart(double charge, double minStart) {
        return charge >= minStart && charge > 0.0;
    }
}
