#version 330
#extension GL_ARB_separate_shader_objects : require

// CamFly: the picture of a camera whose radio signal is weak, for
// camera-mode.max-distance-mode: signal. One shader for all stages - what
// each stage looks like is in its post effect, camfly:signal_1 and up,
// written by the plugin out of signal.shader in its config.yml.

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// In the order the post effect lists them: that is the order they are
// written in.
layout(std140) uniform SignalConfig {
    // The colour the picture takes on, and in a how much of it.
    vec4 Tint;
    // Snow over the picture.
    float Static;
    // The dark lines of a tube screen.
    float Scanlines;
    // Lines slipping sideways, and a tear running down the picture.
    float LineJitter;
    // Red and blue running apart.
    float ColorSplit;
    // Bands in which the picture drops out for a moment.
    float Dropouts;
    // Colours fading, 1 being black and white.
    float Desaturate;
    // Darker edges.
    float Vignette;
    // How fast it all flickers, 1 being a new picture of noise every tick.
    float Speed;
};

layout(location = 0) out vec4 fragColor;

// A number from 0 to 1 that looks random and is the same for the same input.
float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

void main() {
    // GameTime is the time of day, 0 to 1 over the 24000 ticks of a day.
    float ticks = GameTime * 24000.0;
    float frame = floor(ticks * Speed);
    vec2 uv = texCoord;

    // --- Lines slipping sideways: some bands jump a little each frame ---
    float band = floor(uv.y * 90.0);
    float shift = 0.0;
    if (hash(vec2(band, frame)) > 1.0 - LineJitter * 0.35) {
        shift = (hash(vec2(band * 1.7, frame + 3.1)) - 0.5) * 0.1 * LineJitter;
    }
    shift += sin(uv.y * 25.0 + ticks * 0.7 * Speed) * 0.002 * LineJitter;
    // The tear: a band running slowly down the picture, where it slips most.
    float tear = fract(ticks * 0.004 * Speed);
    float nearTear = abs(uv.y - (1.0 - tear));
    if (nearTear < 0.03) {
        shift += 0.035 * LineJitter * (1.0 - nearTear / 0.03);
    }
    uv.x = clamp(uv.x + shift, 0.0, 1.0);

    // --- Red and blue running apart ---
    float split = ColorSplit * 0.012 * (0.6 + 0.4 * hash(vec2(frame, 7.0)));
    vec3 color;
    color.r = texture(InSampler, vec2(clamp(uv.x + split, 0.0, 1.0), uv.y)).r;
    color.g = texture(InSampler, uv).g;
    color.b = texture(InSampler, vec2(clamp(uv.x - split, 0.0, 1.0), uv.y)).b;

    // --- Fading colours and the tint ---
    float luma = dot(color, vec3(0.299, 0.587, 0.114));
    color = mix(color, vec3(luma), Desaturate);
    color *= mix(vec3(1.0), Tint.rgb, Tint.a);

    // --- Snow: noise on cells of two pixels, a new one each frame ---
    float snow = hash(floor(uv * InSize / 2.0) + frame * vec2(13.1, 7.7));
    color = mix(color, vec3(snow), Static * 0.75);
    if (snow > 0.985) {
        color += vec3(0.3 * Static);
    }
    // The whole picture flickers a little with it.
    color *= 1.0 - 0.08 * Static * hash(vec2(frame, 1.0));

    // --- The lines of a tube screen, about three pixels each ---
    float lineCount = clamp(InSize.y / 3.0, 120.0, 360.0);
    float scan = 0.5 + 0.5 * cos(uv.y * lineCount * 6.2831853);
    color *= 1.0 - Scanlines * 0.45 * scan;

    // --- Drop-outs: a band where the picture is gone for a moment, dark or
    // --- nothing but snow ---
    float dropBand = floor(uv.y * 24.0);
    float drop = hash(vec2(dropBand, floor(frame / 3.0)));
    if (drop < Dropouts * 0.12) {
        float bright = step(Dropouts * 0.06, drop);
        color = mix(color, vec3(snow * mix(0.3, 0.9, bright)), 0.85);
    }

    // --- Darker edges ---
    float edge = distance(texCoord, vec2(0.5));
    color *= 1.0 - Vignette * smoothstep(0.35, 0.85, edge);

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
