package de.elia.cameraplugin.shader;

/**
 * How the players get CamFly's resource pack, the values of
 * {@code resource-pack.send}.
 */
public enum PackSending {
    /** {@code built-in}: from a web server of CamFly's own, see {@link PackServer}. */
    BUILT_IN,
    /** {@code url}: from the address in {@code resource-pack.url}, where somebody put it. */
    URL,
    /** {@code false}: CamFly sends nothing, the players have the shader some other way. */
    OFF
}
