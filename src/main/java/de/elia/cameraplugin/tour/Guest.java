package de.elia.cameraplugin.tour;

import org.bukkit.util.Vector;

import java.util.ArrayDeque;

/** One guest on a {@link Tour}, and what {@link TourFollowing} keeps of him. */
final class Guest {
    /** Since when the guide is out of his reach, 0 while he is within it. */
    long outSince;
    /** The seconds his warning showed last, -1 while it shows none. */
    long shownSeconds = -1L;
    /** Whether he was last sent no speed at all, so standing still is not sent again every tick. */
    boolean resting;
    /**
     * Whether his client may see through the eyes of the guide: from the
     * first time the guide's view went out until his own camera is sent
     * back.
     */
    boolean viewing;
    /** Ticks since the guide's view was last sent. */
    int sinceView;
    /**
     * Whether he sees through the guide's eyes by being turned along with
     * him, on Bedrock, rather than through the guide's camera, see
     * {@link CamTour#turnsAlong}.
     */
    boolean turning;
    /** Where he is held turned while he sees through the guide's eyes, see {@link TourFollowing#holdTurned}. */
    float yaw;
    float pitch;
    /** Ticks he was held turned before seeing through the guide's eyes, see {@link TourFollowing#CENTER_TICKS}. */
    int centered;
    /** Ticks since his frost was last sent to his client. */
    int sinceFrost;
    /** The speeds he was sent in the last ticks, the newest last, see {@link TourFollowing#ahead}. */
    final ArrayDeque<Vector> sent = new ArrayDeque<>();
}
