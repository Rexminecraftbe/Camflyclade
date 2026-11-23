package de.elia.cameraplugin.timelimit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link CamTimeLimit}: how the texts write a duration, and the arithmetic of
 * a cooldown - how long it still runs and how full its bossbar is.
 */
class CamTimeLimitTest {

    private static final double DELTA = 1e-9;

    @Test
    void underAMinuteInSecondsAlone() {
        assertEquals("0 seconds", CamTimeLimit.formatDuration(0));
        assertEquals("1 second", CamTimeLimit.formatDuration(1));
        assertEquals("59 seconds", CamTimeLimit.formatDuration(59));
    }

    @Test
    void underAnHourInMinutesAndSeconds() {
        assertEquals("1m 0s", CamTimeLimit.formatDuration(60));
        assertEquals("2m 5s", CamTimeLimit.formatDuration(125));
        assertEquals("59m 59s", CamTimeLimit.formatDuration(3_599));
    }

    @Test
    void underADayInHoursMinutesAndSeconds() {
        assertEquals("1h 0m 0s", CamTimeLimit.formatDuration(3_600));
        assertEquals("23h 59m 59s", CamTimeLimit.formatDuration(86_399));
    }

    @Test
    void fromADayOnInDaysHoursAndMinutesWithoutSeconds() {
        assertEquals("1d 0h 0m", CamTimeLimit.formatDuration(86_400));
        assertEquals("1d 1h 1m", CamTimeLimit.formatDuration(86_400 + 3_600 + 60 + 59));
        assertEquals("36500d 0h 0m", CamTimeLimit.formatDuration(36_500L * 86_400L));
    }

    @Test
    void secondsAloneHowEverManyThereAre() {
        assertEquals("1 second", CamTimeLimit.formatSeconds(1));
        assertEquals("0 seconds", CamTimeLimit.formatSeconds(0));
        assertEquals("120 seconds", CamTimeLimit.formatSeconds(120));
    }

    @Test
    void cooldownRunsForItsSecondsFromItsStart() {
        assertEquals(120_000L, CamTimeLimit.cooldownLeft(1_000L, 120, 1_000L));
        assertEquals(500L, CamTimeLimit.cooldownLeft(1_000L, 120, 120_500L));
    }

    @Test
    void cooldownThatIsOverHasNothingLeft() {
        assertEquals(0L, CamTimeLimit.cooldownLeft(1_000L, 120, 121_000L));
        assertEquals(0L, CamTimeLimit.cooldownLeft(1_000L, 120, 999_999L));
    }

    @Test
    void newCooldownSecondsCountForACooldownThatRuns() {
        // cooldown-seconds changed with /cam reload: the start stays, the end moves.
        assertEquals(50_000L, CamTimeLimit.cooldownLeft(0L, 60, 10_000L));
        assertEquals(0L, CamTimeLimit.cooldownLeft(0L, 5, 10_000L));
    }

    @Test
    void aMomentLeftIsStillASecond() {
        assertEquals(0L, CamTimeLimit.wholeSeconds(0L));
        assertEquals(1L, CamTimeLimit.wholeSeconds(1L));
        assertEquals(1L, CamTimeLimit.wholeSeconds(1_000L));
        assertEquals(2L, CamTimeLimit.wholeSeconds(1_001L));
    }

    @Test
    void cooldownBarFillsUpUntilItIsOver() {
        assertEquals(0.0, CamTimeLimit.cooldownProgress(60_000L, 60), DELTA);
        assertEquals(0.5, CamTimeLimit.cooldownProgress(30_000L, 60), DELTA);
        assertEquals(1.0, CamTimeLimit.cooldownProgress(0L, 60), DELTA);
    }

    @Test
    void cooldownBarStaysEmptyWhileMoreIsLeftThanTheCooldownLasts() {
        // cooldown-seconds made shorter with /cam reload, while the bar shows a longer one.
        assertEquals(0.0, CamTimeLimit.cooldownProgress(90_000L, 60), DELTA);
    }
}
