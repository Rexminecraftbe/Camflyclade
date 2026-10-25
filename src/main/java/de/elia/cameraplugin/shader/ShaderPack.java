package de.elia.cameraplugin.shader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * CamFly's resource pack: the shader for a weak signal, and one post effect
 * for each stage of it, {@code camfly:signal_1} up to the number of
 * {@code signal.shader.stages}.
 *
 * <p>The shader is the same for all of them, it comes out of the jar. What
 * tells the stages apart are the values each post effect hands it: the last
 * stage gets those of {@code signal.shader} as they are, every stage before
 * it its share of them - stage 2 of 4 half of each. The tint goes along with
 * them, and the speed stays the same throughout.</p>
 *
 * <p>Put together the same way every time - the same entries in the same
 * order and with the same date -, so that the same settings give the very
 * same file. Its SHA-1 is what tells a client that it has the pack already
 * and need not fetch it again.</p>
 */
public final class ShaderPack {

    /** The namespace of everything in the pack. */
    public static final String NAMESPACE = "camfly";

    /** Where the shader lies in the jar, under the path it has in the pack. */
    private static final String SOURCE = "resourcepack/";

    private static final String SHADER = "assets/camfly/shaders/post/signal_noise.fsh";

    /**
     * The format of resource packs of 26.3, 97.1, as the lowest; any 97
     * as the highest. Post effects sent by the server only came with 26.3.
     */
    private static final String PACK_META = """
            {
              "pack": {
                "description": "CamFly: the picture of a camera with a weak signal",
                "min_format": [97, 1],
                "max_format": 97
              }
            }
            """;

    /**
     * The date every entry carries, so that the bytes do not change with the
     * day they were packed. Set as it is written into the file, without the
     * time zone of the server in between.
     */
    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 1, 1, 0, 0);

    private final byte[] bytes;
    private final String sha1;

    private ShaderPack(byte[] bytes) {
        this.bytes = bytes;
        this.sha1 = HexFormat.of().formatHex(sha1(bytes));
    }

    /** The key of the post effect of a stage, 1 being the weakest noise. */
    public static String effectKey(int stage) {
        return NAMESPACE + ":signal_" + stage;
    }

    /**
     * Puts the pack together.
     *
     * @throws IOException when the shader is missing from the jar
     */
    public static ShaderPack build(ShaderLook look) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            put(zip, "pack.mcmeta", PACK_META.getBytes(StandardCharsets.UTF_8));
            put(zip, SHADER, readShader());
            for (int stage = 1; stage <= look.stages(); stage++) {
                double share = (double) stage / look.stages();
                put(zip, "assets/camfly/post_effect/signal_" + stage + ".json",
                        postEffect(look, share).getBytes(StandardCharsets.UTF_8));
            }
        }
        return new ShaderPack(out.toByteArray());
    }

    public byte[] bytes() {
        return bytes;
    }

    /** The SHA-1 of the file, as 40 hexadecimal digits. */
    public String sha1() {
        return sha1;
    }

    /** The SHA-1 as the 20 bytes a client is sent. */
    public byte[] sha1Bytes() {
        return HexFormat.of().parseHex(sha1);
    }

    private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(FIXED_TIME);
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static byte[] readShader() throws IOException {
        try (InputStream in = ShaderPack.class.getClassLoader().getResourceAsStream(SOURCE + SHADER)) {
            if (in == null) {
                throw new IOException("the shader " + SOURCE + SHADER + " is missing from the jar");
            }
            return in.readAllBytes();
        }
    }

    /**
     * The post effect of one stage: the shader from the picture of the world
     * into a buffer of its own, and from there back unchanged - the way the
     * game's own {@code minecraft:invert} does it.
     *
     * <p>The values of {@code SignalConfig} stand in the order the shader
     * declares them: that is the order the client writes them in.</p>
     *
     * @param share how much of each value of {@code signal.shader}, 0 to 1
     */
    static String postEffect(ShaderLook look, double share) {
        int tint = look.tint();
        String tintValue = number((tint >> 16 & 0xFF) / 255.0) + ", " + number((tint >> 8 & 0xFF) / 255.0)
                + ", " + number((tint & 0xFF) / 255.0) + ", " + number(share);
        return """
                {
                  "targets": {
                    "swap": {}
                  },
                  "passes": [
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "camfly:post/signal_noise",
                      "inputs": [
                        { "sampler_name": "In", "target": "minecraft:main" }
                      ],
                      "output": "swap",
                      "uniforms": {
                        "SignalConfig": [
                          { "name": "Tint", "type": "vec4", "value": [ %s ] },
                          { "name": "Static", "type": "float", "value": %s },
                          { "name": "Scanlines", "type": "float", "value": %s },
                          { "name": "LineJitter", "type": "float", "value": %s },
                          { "name": "ColorSplit", "type": "float", "value": %s },
                          { "name": "Dropouts", "type": "float", "value": %s },
                          { "name": "Desaturate", "type": "float", "value": %s },
                          { "name": "Vignette", "type": "float", "value": %s },
                          { "name": "Speed", "type": "float", "value": %s }
                        ]
                      }
                    },
                    {
                      "vertex_shader": "minecraft:core/screenquad",
                      "fragment_shader": "minecraft:post/blit",
                      "inputs": [
                        { "sampler_name": "In", "target": "swap" }
                      ],
                      "uniforms": {
                        "BlitConfig": [
                          { "name": "ColorModulate", "type": "vec4", "value": [ 1.0, 1.0, 1.0, 1.0 ] }
                        ]
                      },
                      "output": "minecraft:main"
                    }
                  ]
                }
                """.formatted(tintValue, number(look.staticSnow() * share), number(look.scanlines() * share),
                number(look.lineJitter() * share), number(look.colorSplit() * share),
                number(look.dropouts() * share), number(look.desaturate() * share),
                number(look.vignette() * share), number(look.speed()));
    }

    /** A number as JSON writes it, a point and not a comma whatever the language of the server. */
    private static String number(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static byte[] sha1(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(bytes);
        } catch (NoSuchAlgorithmException ex) {
            // Every Java has SHA-1, the specification asks for it.
            throw new IllegalStateException(ex);
        }
    }
}
