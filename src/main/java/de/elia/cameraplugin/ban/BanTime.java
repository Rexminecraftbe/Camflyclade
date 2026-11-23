package de.elia.cameraplugin.ban;

import de.elia.cameraplugin.timelimit.CamTimeLimit;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The times of {@link CamBan}: a time as {@code /cam ban} takes it, such as
 * {@code 2h} or {@code 1d12h}, and how a ban that is given again changed.
 * Nothing in here needs the server, so the fast tests check it without one.
 */
final class BanTime {

    /** A time as the commands take it: numbers with s, m, h, d or w after them, such as 2h or 1d12h. */
    private static final Pattern TIME = Pattern.compile("(?i)(\\d{1,9}[smhdw])+");
    private static final Pattern TIME_PART = Pattern.compile("(?i)(\\d{1,9})([smhdw])");

    /**
     * The longest ban for a while, some hundred years: anything longer is as
     * good as for good, and the end of it is to fit into a number of
     * milliseconds.
     */
    static final long MAX_MILLIS = 100L * 365L * 86_400_000L;

    private BanTime() {
    }

    /** Whether a word is a time as the commands take it, see {@link #millis}. */
    static boolean isTime(String word) {
        return TIME.matcher(word).matches();
    }

    /** A time such as 2h or 1d12h in milliseconds, {@link #MAX_MILLIS} at most. */
    static long millis(String time) {
        long total = 0L;
        Matcher part = TIME_PART.matcher(time);
        while (part.find()) {
            long amount = Long.parseLong(part.group(1));
            long unit = switch (part.group(2).toLowerCase(Locale.ROOT)) {
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                default -> 604_800_000L;
            };
            total = Math.min(MAX_MILLIS, total + Math.min(MAX_MILLIS, amount * unit));
        }
        return total;
    }

    /** A number of minutes, as the menu takes it, in milliseconds, {@link #MAX_MILLIS} at most. */
    static long minutes(long minutes) {
        return Math.min(MAX_MILLIS, minutes * 60_000L);
    }

    /**
     * Which text tells whom a changed ban holds how it changed, after its
     * {@code prefix}: {@code forever} when it is for good now, {@code until}
     * when it was for good and is not any more, {@code longer} or
     * {@code shorter} by a minute or more, else {@code now}.
     */
    static String changed(String prefix, BanList.Ban old, BanList.Ban ban) {
        if (ban.forGood() || old.forGood()) {
            return prefix + (ban.forGood() == old.forGood() ? "now" : ban.forGood() ? "forever" : "until");
        }
        long diff = ban.until() - old.until();
        return prefix + (Math.abs(diff) < 60_000L ? "now" : diff > 0L ? "longer" : "shorter");
    }

    /**
     * By how much a ban lasts longer or shorter than the one it changed, in
     * whole minutes, the way the texts write a duration; nothing where either
     * is for good, or where there was none before.
     */
    static String difference(BanList.Ban old, BanList.Ban ban) {
        if (old == null || old.forGood() || ban.forGood()) {
            return "";
        }
        long minutes = Math.round(Math.abs(ban.until() - old.until()) / 60_000.0);
        return CamTimeLimit.formatDuration(minutes * 60L);
    }
}
