package de.elia.cameraplugin.bedrock;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.Messages;
import de.elia.cameraplugin.config.SettingValues;
import org.bukkit.Particle;

/**
 * The section {@code bedrock} of the config file: what players on Bedrock
 * get in place of what Bedrock cannot do. See {@link de.elia.cameraplugin.config.CamSettings#bedrock()}.
 */
public final class BedrockSettings {

    /** For whom the adjustments for Bedrock hold, {@code bedrock.apply-to}. */
    private BedrockScope bedrockScope;
    /** The start of a name that marks a player on Bedrock, {@code bedrock.name-prefix}; empty for none. */
    private String bedrockNamePrefix;
    /** How {@code /cam menu} comes to Bedrock players, {@code bedrock.menu}. */
    private BedrockMenu bedrockMenu;
    /** Whether an invitation comes to them as a window as well, {@code bedrock.invitation-forms}. */
    private boolean bedrockInvitationForms;
    /** Whether the buttons in the chat say the command they run, {@code bedrock.chat-commands}. */
    private boolean bedrockChatCommands;
    /**
     * Whether a guest on Bedrock who sees through the eyes of his guide is
     * turned along with him rather than shown Geyser's camera,
     * {@code bedrock.follow-view: turn}.
     */
    private boolean bedrockFollowTurn;
    /**
     * The particle of the outline Bedrock players see of a camera player in
     * place of the glowing one, {@code bedrock.glow-particle}; {@code null}
     * for none.
     */
    private Particle bedrockGlowParticle;

    /**
     * Reads the section from the config file. What does not fit is replaced
     * and noted in the reader, see {@link ConfigReader#getWarnings()}.
     */
    public BedrockSettings(ConfigReader config, Messages messages) {
        readBedrock(config, messages);
    }

    /**
     * Reads {@code bedrock}: for whom its adjustments hold, how a Bedrock
     * player is told apart by his name, and each adjustment that can be
     * switched off. {@code glow-particle: false} is no outline at all, any
     * other value a particle as {@code camera-particles.particle} takes it.
     */
    private void readBedrock(ConfigReader config, Messages messages) {
        String scope = config.getChoice("bedrock.apply-to", "bedrock", "bedrock", "all", "false");
        bedrockScope = scope.equalsIgnoreCase("all") ? BedrockScope.ALL
                : scope.equalsIgnoreCase("false") ? BedrockScope.OFF : BedrockScope.BEDROCK;
        bedrockNamePrefix = config.getString("bedrock.name-prefix", ".").trim();
        String menu = config.getChoice("bedrock.menu", "mixed", "forms", "mixed", "chest");
        bedrockMenu = menu.equalsIgnoreCase("forms") ? BedrockMenu.FORMS
                : menu.equalsIgnoreCase("chest") ? BedrockMenu.CHEST : BedrockMenu.MIXED;
        bedrockInvitationForms = config.getBoolean("bedrock.invitation-forms", true);
        bedrockChatCommands = config.getBoolean("bedrock.chat-commands", true);
        bedrockFollowTurn = config.getChoice("bedrock.follow-view", "camera", "camera", "turn")
                .equalsIgnoreCase("turn");
        String glow = config.getString("bedrock.glow-particle", "end_rod");
        bedrockGlowParticle = glow.trim().equalsIgnoreCase("false") ? null
                : SettingValues.particle(config, messages, "bedrock.glow-particle", Particle.END_ROD);
    }

    public BedrockScope scope() {
        return bedrockScope;
    }

    public String namePrefix() {
        return bedrockNamePrefix;
    }

    public BedrockMenu menu() {
        return bedrockMenu;
    }

    public boolean invitationForms() {
        return bedrockInvitationForms;
    }

    public boolean chatCommands() {
        return bedrockChatCommands;
    }

    public boolean followTurn() {
        return bedrockFollowTurn;
    }

    /** The particle of the outline for Bedrock players, or {@code null} for none. */
    public Particle glowParticle() {
        return bedrockGlowParticle;
    }
}
