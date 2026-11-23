package de.elia.cameraplugin.ban;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BanTime} and {@link BanList.Ban}: the times of {@code /cam ban}, how
 * long a ban still holds, and how the texts tell that a ban changed.
 */
class BanTimeTest {

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60L * MINUTE;

    private static BanList.Ban until(long until) {
        return new BanList.Ban("Alex", until, "", "console");
    }

    private static BanList.Ban forGood() {
        return until(0L);
    }

    @Test
    void timesAreNumbersWithTheirUnits() {
        assertTrue(BanTime.isTime("2h"));
        assertTrue(BanTime.isTime("1d12h"));
        assertTrue(BanTime.isTime("30M"));
        assertTrue(BanTime.isTime("1w2d3h4m5s"));
    }

    @Test
    void otherWordsAreNoTimeButTheStartOfTheReason() {
        assertFalse(BanTime.isTime("90"));
        assertFalse(BanTime.isTime("h"));
        assertFalse(BanTime.isTime("2x"));
        assertFalse(BanTime.isTime("2h30"));
        assertFalse(BanTime.isTime("griefing"));
        assertFalse(BanTime.isTime(""));
        assertFalse(BanTime.isTime("1234567890s"));
    }

    @Test
    void eachUnitCountsWhatItSays() {
        assertEquals(45_000L, BanTime.millis("45s"));
        assertEquals(30L * MINUTE, BanTime.millis("30m"));
        assertEquals(2L * HOUR, BanTime.millis("2h"));
        assertEquals(24L * HOUR, BanTime.millis("1d"));
        assertEquals(7L * 24L * HOUR, BanTime.millis("1w"));
    }

    @Test
    void partsAddUp() {
        assertEquals(36L * HOUR, BanTime.millis("1d12h"));
        assertEquals(4L * HOUR, BanTime.millis("2h2h"));
        assertEquals(90L * MINUTE, BanTime.millis("1H30M"));
    }

    @Test
    void longestTimeIsAHundredYears() {
        assertEquals(BanTime.MAX_MILLIS, BanTime.millis("999999999w"));
        assertEquals(BanTime.MAX_MILLIS, BanTime.millis("999999999w999999999w"));
        assertEquals(BanTime.MAX_MILLIS, BanTime.minutes(100L * 365L * 24L * 60L + 1L));
    }

    @Test
    void minutesOfTheMenu() {
        assertEquals(90L * MINUTE, BanTime.minutes(90L));
        assertEquals(MINUTE, BanTime.minutes(1L));
    }

    @Test
    void banWithoutAnEndHoldsForGood() {
        BanList.Ban ban = forGood();

        assertTrue(ban.forGood());
        assertTrue(ban.holds(0L));
        assertTrue(ban.holds(Long.MAX_VALUE));
    }

    @Test
    void banForAWhileHoldsUntilItsEnd() {
        BanList.Ban ban = until(10_000L);

        assertFalse(ban.forGood());
        assertTrue(ban.holds(9_999L));
        assertFalse(ban.holds(10_000L));
    }

    @Test
    void secondsLeftAreRoundedUp() {
        BanList.Ban ban = until(10_000L);

        assertEquals(2L, ban.secondsLeft(8_500L));
        assertEquals(1L, ban.secondsLeft(9_999L));
        assertEquals(0L, ban.secondsLeft(10_000L));
        assertEquals(0L, ban.secondsLeft(12_000L));
    }

    @Test
    void changedBanSaysLongerOrShorter() {
        assertEquals("banned-changed-longer", BanTime.changed("banned-changed-", until(HOUR), until(3L * HOUR)));
        assertEquals("banned-changed-shorter", BanTime.changed("banned-changed-", until(3L * HOUR), until(HOUR)));
    }

    @Test
    void lessThanAMinuteIsNoChangeWorthTelling() {
        assertEquals("banned-changed-now", BanTime.changed("banned-changed-", until(HOUR), until(HOUR + 59_999L)));
        assertEquals("banned-changed-longer",
                BanTime.changed("banned-changed-", until(HOUR), until(HOUR + MINUTE)));
    }

    @Test
    void changedBanSaysWhenItIsForGoodNowOrNotAnyMore() {
        assertEquals("banned-everybody-changed-forever",
                BanTime.changed("banned-everybody-changed-", until(HOUR), forGood()));
        assertEquals("banned-everybody-changed-until",
                BanTime.changed("banned-everybody-changed-", forGood(), until(HOUR)));
        assertEquals("banned-everybody-changed-now",
                BanTime.changed("banned-everybody-changed-", forGood(), forGood()));
    }

    @Test
    void differenceInWholeMinutes() {
        assertEquals("1h 30m 0s", BanTime.difference(until(30L * MINUTE), until(2L * HOUR)));
        assertEquals("1h 30m 0s", BanTime.difference(until(2L * HOUR), until(30L * MINUTE)));
        assertEquals("1m 0s", BanTime.difference(until(HOUR), until(HOUR + 31_000L)));
        assertEquals("0 seconds", BanTime.difference(until(HOUR), until(HOUR + 29_000L)));
    }

    @Test
    void noDifferenceWithoutTwoBansForAWhile() {
        assertEquals("", BanTime.difference(null, until(HOUR)));
        assertEquals("", BanTime.difference(forGood(), until(HOUR)));
        assertEquals("", BanTime.difference(until(HOUR), forGood()));
    }
}
