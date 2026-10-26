package de.elia.cameraplugin.scout;

/**
 * Where {@code anti-scouting.radius} is measured from, the values of
 * {@code anti-scouting.measured-from}: body and camera.
 */
public enum ScoutAnchor {
    /**
     * {@code body}: from the body - or the spot he visits, which takes its
     * place. He sees whom he would see standing there, wherever the camera
     * is.
     */
    BODY,
    /** {@code camera}: from the camera itself. */
    CAMERA
}
