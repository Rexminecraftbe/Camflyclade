package de.elia.cameraplugin.display;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.config.SettingValues;
import org.bukkit.Particle;

/**
 * The section {@code camera-particles} of the config file: the particles
 * that show where a camera is. See {@link de.elia.cameraplugin.config.CamSettings#particles()}.
 */
public final class ParticleSettings {

    /**
     * Largest value that is accepted for
     * {@code camera-particles.particles-per-tick}. Every player who sees the
     * camera player gets that many each tick, and the client has to draw all
     * of them.
     */
    private static final double MAX_PARTICLES_PER_TICK = 100.0;

    /** Whether the particles are switched on, {@code camera-particles.enabled}. */
    private boolean particlesEnabled;
    /** Which particle it is, {@code camera-particles.particle}. */
    private Particle particleType;
    private double particleHeight;
    /** Particles per tick, below one meaning one every few ticks. */
    private double particlesPerTick;
    /** How far they scatter around the spot, {@code camera-particles.spread}. */
    private double particleSpread;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public ParticleSettings(ConfigReader config, Messages messages) {
        particlesEnabled = config.getBoolean("camera-particles.enabled", true);
        particleType = SettingValues.particle(config, messages, "camera-particles.particle", Particle.SOUL_FIRE_FLAME);
        particleHeight = config.getDouble("camera-particles.height", 0.2);
        particlesPerTick = config.getDouble("camera-particles.particles-per-tick", 1.0, 0.0,
                MAX_PARTICLES_PER_TICK);
        particleSpread = config.getDouble("camera-particles.spread", 0.0, 0.0);
    }

    /**
     * Whether a camera player gets particles at all: switched off by
     * {@code camera-particles.enabled: false}, and just as well by
     * {@code particles-per-tick: 0}.
     */
    public boolean enabled() {
        return particlesEnabled && particlesPerTick > 0;
    }

    public Particle type() {
        return particleType;
    }

    public double height() {
        return particleHeight;
    }

    public double perTick() {
        return particlesPerTick;
    }

    public double spread() {
        return particleSpread;
    }
}
