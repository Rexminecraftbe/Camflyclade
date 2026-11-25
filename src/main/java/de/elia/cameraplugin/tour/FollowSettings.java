package de.elia.cameraplugin.tour;

import de.elia.cameraplugin.config.CamSettings;
import de.elia.cameraplugin.config.ConfigReader;

/**
 * The section {@code follow} of the config file, for {@code /cam follow}:
 * how far a follower may fall behind, how many there may be, and what of the
 * leader they see. Called {@code tour} before, which an older file still reads
 * as. See {@link CamSettings#follow()}.
 */
public final class FollowSettings {

    /**
     * The widest {@code follow.radius}: about as far as the server sends other
     * players to a client, and a guest is to be seen close by anyway.
     */
    private static final double MAX_TOUR_RADIUS = 128.0;

    /** The longest {@code follow.out-of-reach-seconds} accepts, like {@code signal.lost-seconds}. */
    private static final int MAX_TOUR_OUT_OF_REACH_SECONDS = 60;

    /** The most guests {@code follow.max-guests} lets one tour take. */
    private static final int MAX_TOUR_GUESTS = 100;

    /**
     * The longest {@code follow.invitation-seconds} and
     * {@code follow.invite-cooldown-seconds} accept: an hour.
     */
    private static final int MAX_TOUR_INVITATION_SECONDS = 3600;

    /** The shortest {@code follow.invitation-seconds} accepts: time enough to read it and click. */
    private static final int MIN_TOUR_INVITATION_SECONDS = 5;

    /** Whether {@code /cam follow} lets a camera player show others round, {@code follow.enabled}. */
    private boolean tourEnabled;
    /** How close a camera has to be to be invited, {@code follow.radius}. */
    private double tourRadius;
    /** How long a guest may be out of reach before his tour ends, {@code follow.out-of-reach-seconds}. */
    private int tourOutOfReachSeconds;
    /** Whether a guest sees through the eyes of his guide, {@code follow.guide-view}. */
    private boolean tourGuideView;
    /** How close a player out of camera mode has to be to the guide to come along, {@code follow.start-distance}. */
    private double tourStartDistance;
    /** How many guests one tour takes at once, {@code follow.max-guests}; 0 for no limit. */
    private int tourMaxGuests;
    /** How long an invitation holds, {@code follow.invitation-seconds}. */
    private int tourInvitationSeconds;
    /** How long before a guide may invite the same player again, {@code follow.invite-cooldown-seconds}. */
    private int tourInviteCooldownSeconds;
    /** Whether a player out of camera mode may show others round as well, {@code follow.lead-without-cam}. */
    private boolean tourLeadWithoutCam;
    /**
     * Whether the names of a tour take the place of the name tag of a guide
     * out of camera mode, {@code follow.guide-name: replace}, rather than
     * standing over it, {@code above}.
     */
    private boolean tourGuideNameReplaces;
    /** Whether the guests of a guide out of camera mode see his hearts as theirs, {@code follow.mirror-health}. */
    private boolean tourMirrorHealth;
    /** Whether they see the item in his hand in theirs, {@code follow.mirror-main-hand}. */
    private boolean tourMirrorMainHand;
    /** Whether they see the item in his off hand in theirs, {@code follow.mirror-off-hand}. */
    private boolean tourMirrorOffHand;
    /** Whether their arm with his item swings when his does, {@code follow.mirror-swing}. */
    private boolean tourMirrorSwing;
    /** Whether they see the item on his head on theirs, {@code follow.mirror-head}. */
    private boolean tourMirrorHead;
    /** Whether they see his effects as theirs, {@code follow.mirror-effects}. */
    private boolean tourMirrorEffects;
    /** Whether they see his frost as theirs, {@code follow.mirror-frost}. */
    private boolean tourMirrorFrost;
    /** Whether they see his experience bar and level as theirs, {@code follow.mirror-experience}. */
    private boolean tourMirrorExperience;
    /**
     * Whether they see all there is to his items - how many are in a stack,
     * their enchantments, their wear and the names he gave them -,
     * {@code follow.mirror-item-details}.
     */
    private boolean tourMirrorItemDetails;
    /** Whether guests in the guide's view are held turned between the ticks too, {@code follow.fine-hold}. */
    private boolean tourFineHold;
    /** Whether a guide may invite everybody he could invite at once, {@code follow.invite-all}. */
    private boolean tourInviteAll;
    /** Whether the guests see the guide's time of day, {@code follow.guide-time}. */
    private boolean tourGuideTime;
    /** Whether the guests see the guide's weather, {@code follow.guide-weather}. */
    private boolean tourGuideWeather;
    /** Whether the guests have the guide's night vision, {@code follow.guide-night-vision}. */
    private boolean tourGuideNightVision;

    /** {@code cam-time.enabled}, without which the leader's time is not shown either. */
    private final boolean camTimeEnabled;
    /** {@code cam-weather.enabled}, without which the leader's weather is not shown either. */
    private final boolean camWeatherEnabled;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public FollowSettings(ConfigReader config, boolean camTimeEnabled, boolean camWeatherEnabled) {
        this.camTimeEnabled = camTimeEnabled;
        this.camWeatherEnabled = camWeatherEnabled;
        // The section of /cam follow, which was called tour before: an older
        // file is read as it was written.
        String follow = config.renamed(CamSettings.FOLLOW_SECTION, CamSettings.OLD_FOLLOW_SECTION);
        tourEnabled = config.getBoolean(follow + ".enabled", true);
        tourRadius = config.getDouble(follow + ".radius", 16.0, 1.0, MAX_TOUR_RADIUS);
        tourOutOfReachSeconds = config.getInt(follow + ".out-of-reach-seconds", 5, 1,
                MAX_TOUR_OUT_OF_REACH_SECONDS);
        tourGuideView = config.getBoolean(follow + ".guide-view", true);
        tourStartDistance = config.getDouble(follow + ".start-distance", 16.0, 0.0, CamSettings.MAX_START_DISTANCE);
        tourMaxGuests = config.getInt(follow + ".max-guests", 5, 0, MAX_TOUR_GUESTS);
        tourInvitationSeconds = config.getInt(follow + ".invitation-seconds", 60, MIN_TOUR_INVITATION_SECONDS,
                MAX_TOUR_INVITATION_SECONDS);
        tourInviteCooldownSeconds = config.getInt(follow + ".invite-cooldown-seconds", 60, 0,
                MAX_TOUR_INVITATION_SECONDS);
        tourLeadWithoutCam = config.getBoolean(follow + ".lead-without-cam", true);
        tourGuideNameReplaces = config.getChoice(follow + ".guide-name", "replace", "replace", "above")
                .equalsIgnoreCase("replace");
        tourMirrorHealth = config.getBoolean(follow + ".mirror-health", true);
        tourMirrorMainHand = config.getBoolean(follow + ".mirror-main-hand", true);
        tourMirrorOffHand = config.getBoolean(follow + ".mirror-off-hand", true);
        tourMirrorSwing = config.getBoolean(follow + ".mirror-swing", true);
        tourMirrorHead = config.getBoolean(follow + ".mirror-head", true);
        tourMirrorEffects = config.getBoolean(follow + ".mirror-effects", true);
        tourMirrorFrost = config.getBoolean(follow + ".mirror-frost", true);
        tourMirrorExperience = config.getBoolean(follow + ".mirror-experience", false);
        tourMirrorItemDetails = config.getBoolean(follow + ".mirror-item-details", false);
        tourFineHold = config.getBoolean(follow + ".fine-hold", true);
        tourInviteAll = config.getBoolean(follow + ".invite-all", true);
        tourGuideTime = config.getBoolean(follow + ".guide-time", true);
        tourGuideWeather = config.getBoolean(follow + ".guide-weather", true);
        tourGuideNightVision = config.getBoolean(follow + ".guide-night-vision", true);
    }

    /** Whether {@code /cam follow} is there. */
    public boolean enabled() {
        return tourEnabled;
    }

    /** How close, camera to camera, a player has to be to be invited to a tour, in blocks. */
    public double radius() {
        return tourRadius;
    }

    /** How long the guide may be out of a guest's reach before the tour ends for him, in seconds. */
    public int outOfReachSeconds() {
        return tourOutOfReachSeconds;
    }

    /** Whether a guest may see through the eyes of his guide, where the server lets it. */
    public boolean guideView() {
        return tourGuideView;
    }

    /**
     * How close, in blocks, a player out of camera mode has to be to the
     * guide's camera to accept an invitation, which starts camera mode for
     * him; 0 while only who is in camera mode may come along.
     */
    public double startDistance() {
        return tourStartDistance;
    }

    /** How many guests one tour takes at once; 0 for no limit. */
    public int maxGuests() {
        return tourMaxGuests;
    }

    /** How long an invitation holds, in seconds. */
    public int invitationSeconds() {
        return tourInvitationSeconds;
    }

    /** How long after inviting a player a guide may not invite him again, in seconds; 0 at once. */
    public int inviteCooldownSeconds() {
        return tourInviteCooldownSeconds;
    }

    /** Whether a player out of camera mode may show others round as well, while he builds, say. */
    public boolean leadWithoutCam() {
        return tourLeadWithoutCam;
    }

    /**
     * Whether the names of a tour take the place of the name tag of a guide
     * out of camera mode; otherwise they stand over it.
     */
    public boolean guideNameReplacing() {
        return tourGuideNameReplaces;
    }

    /** Whether the guests of a guide out of camera mode see his hearts on their screen. */
    public boolean mirrorHealth() {
        return tourMirrorHealth;
    }

    /** Whether they see the item in his hand in theirs. */
    public boolean mirrorMainHand() {
        return tourMirrorMainHand;
    }

    /** Whether they see the item in his off hand in theirs. */
    public boolean mirrorOffHand() {
        return tourMirrorOffHand;
    }

    /** Whether the guests' arm with the guide's item swings when the guide's does. */
    public boolean mirrorSwing() {
        return tourMirrorSwing;
    }

    /** Whether the guests see the item on the guide's head - a carved pumpkin, say - on theirs. */
    public boolean mirrorHead() {
        return tourMirrorHead;
    }

    /** Whether the guests see the guide's effects as theirs. */
    public boolean mirrorEffects() {
        return tourMirrorEffects;
    }

    /** Whether the guests see the guide's frost as theirs. */
    public boolean mirrorFrost() {
        return tourMirrorFrost;
    }

    /** Whether the guests see the guide's experience bar and level as theirs. */
    public boolean mirrorExperience() {
        return tourMirrorExperience;
    }

    /**
     * Whether the guests see all there is to the guide's items: how many are
     * in the stacks in his hands - in their hotbar, and in his where they see
     * through his eyes -, which enchantments they have, how worn they are and
     * the names he gave them, at an anvil say. Otherwise each of them looks as
     * one, only glinting where it is enchanted, as new and with the item's own
     * name, as the game calls it. One switch for the four since there were
     * four, {@code mirror-count}, {@code -enchantments}, {@code -durability}
     * and {@code -name}.
     */
    public boolean mirrorItemDetails() {
        return tourMirrorItemDetails;
    }

    /** Whether guests who see through the guide's eyes are held turned between the ticks too. */
    public boolean fineHold() {
        return tourFineHold;
    }

    /** Whether a guide may invite everybody he could invite at once, {@code /cam follow invite all}. */
    public boolean inviteAll() {
        return tourInviteAll;
    }

    /**
     * Whether the guests see the time of day of their guide: the one he
     * sees in camera mode, the time of the world while he is out of it. Only
     * while {@code cam-time} lets anybody have a time of his own.
     */
    public boolean guideTime() {
        return tourGuideTime && camTimeEnabled;
    }

    /** Whether the guests see the weather of their guide, the way {@link #isTourGuideTime} has it. */
    public boolean guideWeather() {
        return tourGuideWeather && camWeatherEnabled;
    }

    /**
     * Whether the guests have the night vision of their guide: the mode that
     * holds for him in camera mode, none while he is out of it.
     */
    public boolean guideNightVision() {
        return tourGuideNightVision;
    }
}
