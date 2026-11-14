package de.elia.cameraplugin.session;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Logger;

/** The head the camera player wears while {@code camera-head.enabled} is on. */
public final class CameraHead {

    /**
     * The profile the head carries, always the same one. The client keeps a
     * skin it loaded under the profile and its texture: with a new profile
     * each time - a random id, a texture with a new time stamp in it - it
     * loads the skin anew and shows the plain head meanwhile, so the button
     * of the menu, drawn anew every second, flickered.
     */
    private static final UUID PROFILE_ID =
            UUID.nameUUIDFromBytes("CamFly:camera_head".getBytes(StandardCharsets.UTF_8));

    private static final String TEXTURE =
            "http://textures.minecraft.net/texture/bfef5141d0d29154efa496144e117d18c257b4705ad10d29ba07ec7f45fcabc3";

    /** The head as it was made the first time; every other one is a copy of it, the very same item. */
    private static ItemStack template;

    private CameraHead() {
    }

    /** A new camera head, the same in everything as every other one. */
    public static ItemStack create(Logger logger) {
        if (template == null) {
            template = build(logger);
        }
        return template.clone();
    }

    private static ItemStack build(Logger logger) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            try {
                PlayerProfile profile = Bukkit.createPlayerProfile(PROFILE_ID);
                profile.getTextures().setSkin(new java.net.URL(TEXTURE));
                meta.setOwnerProfile(profile);
            } catch (java.net.MalformedURLException e) {
                logger.warning("Failed to set camera head texture: " + e.getMessage());
            }
            meta.setLore(java.util.Collections.singletonList("https://namemc.com/skin/437c1be7c3e403c1"));
            head.setItemMeta(meta);
        }
        return head;
    }
}
