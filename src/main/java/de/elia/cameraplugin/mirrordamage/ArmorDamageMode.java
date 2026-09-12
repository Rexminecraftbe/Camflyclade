package de.elia.cameraplugin.mirrordamage;

/**
 * Available modes for the wear the transferred hit puts on the armour.
 *
 * <p>Read apart from {@link DamageMode}: how many hearts the hit costs the
 * player and how hard it wears his armour are two separate settings, so that
 * neither of them quietly follows the other.</p>
 */
public enum ArmorDamageMode {
    /** Wear the armour the way the real hit on the body would wear it. */
    MIRROR,
    /** Take a fixed number of durability points off on every hit. */
    CUSTOM,
    /** Leave the armour alone. */
    OFF
}
