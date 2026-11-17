package de.elia.cameraplugin.bedrock;

/**
 * The values of {@code bedrock.follow-view}: how a guest on Bedrock sees
 * through the eyes of the guide he follows, {@code follow.guide-view}.
 */
public enum BedrockFollowView {
    /**
     * Geyser's camera, which follows the guide's view smoothly but draws no
     * hands. The guide's items go into the guest's own hands, and his own
     * invisible player - which holds them, where he stands - is turned the
     * way the guide looks: they turn with the view, a little after it.
     */
    CAMERA,
    /**
     * Geyser's camera, the guide's items in the guest's hotbar alone, next to
     * the slot he holds: nothing hangs in the view.
     */
    HOTBAR,
    /**
     * No camera of Geyser's: the guest himself is turned the way the guide
     * looks, every tick, and sees from his own eyes right behind him, the
     * guide's items in his hands. The view turns in twenty steps a second.
     */
    TURN
}
