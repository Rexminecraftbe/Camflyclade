package de.elia.cameraplugin.shader;

/**
 * What one post effect hands the shader of CamFly, {@code EffectConfig} in
 * {@code effect.fsh}: every value the shader knows. Each starts at what does
 * nothing, so that a look only has to name what it changes, e.g.
 * {@code new ShaderValues(0xff0000).flash(0.8)}.
 */
final class ShaderValues {

    /** The colour of the tint, as {@code 0xRRGGBB}. */
    final int tint;
    double tintAmount;
    double staticSnow;
    double scanlines;
    double lineJitter;
    double colorSplit;
    double dropouts;
    double desaturate;
    double vignette;
    double speed = 1.0;
    double flash;
    double glow;
    double pulse;
    double shake;
    double black;
    double collapse;
    double shrink;

    ShaderValues(int tint) {
        this.tint = tint;
    }

    /**
     * The values of one stage of the noise of a weak signal: {@code share} of
     * each value of {@code signal.shader}, the tint as well; the speed stays.
     */
    static ShaderValues signal(ShaderLook look, double share) {
        return new ShaderValues(look.tint()).tintAmount(share).staticSnow(look.staticSnow() * share)
                .scanlines(look.scanlines() * share).lineJitter(look.lineJitter() * share)
                .colorSplit(look.colorSplit() * share).dropouts(look.dropouts() * share)
                .desaturate(look.desaturate() * share).vignette(look.vignette() * share).speed(look.speed());
    }

    ShaderValues tintAmount(double value) {
        tintAmount = share(value);
        return this;
    }

    ShaderValues staticSnow(double value) {
        staticSnow = share(value);
        return this;
    }

    ShaderValues scanlines(double value) {
        scanlines = share(value);
        return this;
    }

    ShaderValues lineJitter(double value) {
        lineJitter = share(value);
        return this;
    }

    ShaderValues colorSplit(double value) {
        colorSplit = share(value);
        return this;
    }

    ShaderValues dropouts(double value) {
        dropouts = share(value);
        return this;
    }

    ShaderValues desaturate(double value) {
        desaturate = share(value);
        return this;
    }

    ShaderValues vignette(double value) {
        vignette = share(value);
        return this;
    }

    /** Not held to 1 like the others: {@code signal.shader.speed} goes up to 4. */
    ShaderValues speed(double value) {
        speed = Math.max(0.0, value);
        return this;
    }

    ShaderValues flash(double value) {
        flash = share(value);
        return this;
    }

    ShaderValues glow(double value) {
        glow = share(value);
        return this;
    }

    ShaderValues pulse(double value) {
        pulse = share(value);
        return this;
    }

    ShaderValues shake(double value) {
        shake = share(value);
        return this;
    }

    ShaderValues black(double value) {
        black = share(value);
        return this;
    }

    ShaderValues collapse(double value) {
        collapse = share(value);
        return this;
    }

    ShaderValues shrink(double value) {
        shrink = share(value);
        return this;
    }

    private static double share(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
