#version 330
#extension GL_ARB_separate_shader_objects : require

// CamFly: everything its post effects do to the picture of the camera - the
// noise of a weak radio signal, the camera switching on and off, the warning
// before the end, a body low on health, the border close by. One shader for
// all of them: what each looks like is in its post effect, camfly:signal_1,
// camfly:start_1 and so on, written by the plugin out of its config.yml.
// An animation is a row of post effects, one for each tick.

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform SamplerInfo {
    vec2 OutSize;
    vec2 InSize;
};

// In the order the post effect lists them: that is the order they are
// written in.
layout(std140) uniform EffectConfig {
    // The colour the picture takes on, and how much of it. Flash, Glow and
    // the line of a tube switching off are of this colour as well.
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
    // How fast it all flickers, 1 being a new picture of noise every tick,
    // and how fast it beats: once a second at 1.
    float Speed;
    // The whole picture lit up in the colour of Tint.
    float Flash;
    // The edges glowing in the colour of Tint.
    float Glow;
    // How much Flash, Glow and Vignette beat like a heart, 0 not at all.
    float Pulse;
    // The picture jumping about.
    float Shake;
    // The picture going dark, 1 being black.
    float Black;
    // The picture squeezed into a line across the middle, like a tube
    // screen switching off, 1 being nothing but the line.
    float Collapse;
    // That line shrinking to a dot in the middle, 1 being the dot.
    float Shrink;
};

layout(location = 0) out vec4 fragColor;

// A number from 0 to 1 that looks random and is the same for the same input.
float hash(vec2 p) {
    vec3 q = fract(vec3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// A heartbeat, from 0 to 1: two beats close together, then a pause. One
// heartbeat for each whole number of t.
float heartbeat(float t) {
    float f = fract(t);
    float first = exp(-pow((f - 0.08) * 14.0, 2.0));
    float second = 0.7 * exp(-pow((f - 0.30) * 14.0, 2.0));
    return max(first, second);
}

void main() {
    // GameTime is the time of day, 0 to 1 over the 24000 ticks of a day.
    float ticks = GameTime * 24000.0;
    float frame = floor(ticks * Speed);
    vec2 uv = texCoord;
    vec2 pixel = 1.0 / OutSize;

    // --- The beat, 1 where nothing beats ---
    float beat = mix(1.0, heartbeat(ticks * Speed / 20.0), Pulse);

    // --- The whole picture jumping, a new place every frame ---
    if (Shake > 0.0) {
        vec2 jump = vec2(hash(vec2(frame, 11.0)), hash(vec2(frame, 23.0))) - 0.5;
        uv += jump * 0.05 * Shake;
    }

    // --- A tube switching off: the picture squeezed into a band ---
    float bandHalf = mix(0.5, 1.5 * pixel.y, Collapse);
    float lineHalf = mix(0.5, 2.0 * pixel.x, Shrink);
    if (Collapse > 0.0) {
        uv.y = 0.5 + (uv.y - 0.5) * 0.5 / bandHalf;
    }

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
    uv.x += shift;
    uv = clamp(uv, 0.0, 1.0);

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

    // --- Lit up in the colour of the tint ---
    color = mix(color, Tint.rgb, clamp(Flash * beat, 0.0, 1.0));

    // --- The edges: glowing, and darker ---
    float edge = distance(texCoord, vec2(0.5));
    color = mix(color, Tint.rgb, clamp(Glow * beat, 0.0, 1.0) * smoothstep(0.25, 0.75, edge));
    color *= 1.0 - Vignette * beat * smoothstep(0.35, 0.85, edge);

    // --- The tube switching off: black around the band, which glows the
    // --- narrower it gets, and shrinks to a dot ---
    if (Collapse > 0.0 || Shrink > 0.0) {
        color = mix(color, Tint.rgb, smoothstep(0.5, 1.0, Collapse) * 0.8);
        float inBand = 1.0 - smoothstep(bandHalf, bandHalf + 2.0 * pixel.y, abs(texCoord.y - 0.5));
        float inLine = 1.0 - smoothstep(lineHalf, lineHalf + 2.0 * pixel.x, abs(texCoord.x - 0.5));
        color *= inBand * inLine;
    }

    // --- Going dark ---
    color *= 1.0 - Black;

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
