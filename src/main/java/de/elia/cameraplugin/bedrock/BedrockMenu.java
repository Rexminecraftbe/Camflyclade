package de.elia.cameraplugin.bedrock;

/**
 * The values of {@code bedrock.menu}: how {@code /cam menu} comes to a
 * player on Bedrock whom Geyser or Floodgate on this server knows. Without
 * them every value is {@link #CHEST}: there are no windows of Bedrock then.
 */
public enum BedrockMenu {
    /** Every page as a window of Bedrock, a list of buttons one under the other. */
    FORMS,
    /**
     * The chest, as on Java; only what a chest does badly on Bedrock comes as
     * a window: what a button does besides a plain click, to pick from, and
     * what is to be typed - the name of a spot, why somebody is banned.
     */
    MIXED,
    /** The chest alone: the choices on a page of their own, the questions in the chat. */
    CHEST
}
