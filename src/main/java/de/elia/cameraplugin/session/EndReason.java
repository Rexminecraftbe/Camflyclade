package de.elia.cameraplugin.session;

import java.util.Locale;

/**
 * Why camera mode ends. Its {@link Kind} decides the sound that goes with it:
 * one of {@code sounds.end}, {@code sounds.end-body} and
 * {@code sounds.end-forced}, or none at all. The reason itself goes into the
 * log of {@code cam-log}.
 */
public enum EndReason {
    /** The player ended it himself: {@code /cam}, or a click or hit on his own body. */
    COMMAND(Kind.SELF),
    /** Something happened to the body: hit, hurt, moved, drowning, suffocating, or a potion. */
    BODY(Kind.BODY),
    /** The time of {@code time-limit} ran out. */
    TIME_LIMIT(Kind.FORCED),
    /** The battery of {@code battery} ran empty. */
    BATTERY(Kind.FORCED),
    /** A teleport carried him off, or brought somebody to his camera, {@code teleports}. */
    TELEPORT(Kind.FORCED),
    /** {@code /cam reload}. */
    RELOAD(Kind.FORCED),
    /** He died. */
    DEATH(Kind.SILENT),
    /** He left the server. */
    QUIT(Kind.SILENT),
    /** The server shut down, or the plugin was switched off. */
    SHUTDOWN(Kind.SILENT);

    /** Which of the end sounds a reason plays. */
    public enum Kind {
        /** {@code sounds.end}: he ended it himself. */
        SELF,
        /** {@code sounds.end-body}: his body was hit, moved and so on. */
        BODY,
        /** {@code sounds.end-forced}: it was ended from outside. */
        FORCED,
        /** None: nobody would hear it, he is dead, gone, or the server is. */
        SILENT
    }

    private final Kind kind;

    EndReason(Kind kind) {
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    /** How the reason reads in the log, e.g. {@code time-limit}. */
    public String logName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
