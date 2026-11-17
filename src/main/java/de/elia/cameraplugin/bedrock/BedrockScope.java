package de.elia.cameraplugin.bedrock;

/**
 * The values of {@code bedrock.apply-to}: for whom the adjustments for
 * Bedrock hold - Bedrock players alone, everybody, or nobody.
 */
public enum BedrockScope {
    /** Only players who play on Bedrock, see {@link BedrockPlayers#isBedrock}. */
    BEDROCK,
    /** Every player, those on Java as well: to try them without a Bedrock device. */
    ALL,
    /** Nobody: everybody gets what Java players get. */
    OFF
}
