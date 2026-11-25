package de.elia.cameraplugin.display;

import de.elia.cameraplugin.config.ConfigReader;
import de.elia.cameraplugin.config.NameStyle;
import org.bukkit.ChatColor;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The colour of a name the plugin puts up, {@link NameDisplay#styled} and
 * {@link NameDisplay#styledLike}: the colour code at the start of its text,
 * the default colour of the name without one - and the {@code color} key that
 * an older file may still have, which goes before both.
 */
class NameDisplayTest {

    private static NameStyle style(ChatColor color, ChatColor defaultColor) {
        return new NameStyle(color, defaultColor, false, 64.0, true, false, 1.0);
    }

    private static NameStyle read(String yaml) throws InvalidConfigurationException {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return NameStyle.read(new ConfigReader(config), "body.name", ChatColor.YELLOW);
    }

    @Test
    void aNameKeepsTheColourOfItsText() {
        assertEquals("§bSteve's Body", NameDisplay.styled("§bSteve's Body", style(null, ChatColor.YELLOW)));
        assertEquals("§x§1§2§3§4§5§6Steve", NameDisplay.styled("§x§1§2§3§4§5§6Steve", style(null, ChatColor.YELLOW)));
    }

    @Test
    void aNameWithoutAColourCodeHasTheDefaultColour() {
        assertEquals("§eSteve's Body", NameDisplay.styled("Steve's Body", style(null, ChatColor.YELLOW)));
        assertEquals("§fSteve's Cam §7(away)", NameDisplay.styled("Steve's Cam §7(away)", style(null, ChatColor.WHITE)));
    }

    @Test
    void theColorKeyOfAnOlderFileGoesBeforeTheText() {
        assertEquals("§cSteve's Body§7 x", NameDisplay.styled("§eSteve's Body§7 x", style(ChatColor.RED, ChatColor.YELLOW)));
    }

    @Test
    void aFollowerLineTakesTheColourOfTheNameUnlessItBringsItsOwn() {
        NameStyle style = style(null, ChatColor.WHITE);

        assertEquals("§bAlex §7(Follower)", NameDisplay.styledLike("Alex §7(Follower)", "§bAlex's Cam", style));
        assertEquals("§fAlex §7(Follower)", NameDisplay.styledLike("Alex §7(Follower)", "Alex's Cam", style));
        assertEquals("§6Alex §7(Follower)", NameDisplay.styledLike("§6Alex §7(Follower)", "§bAlex's Cam", style));
        assertEquals("§cAlex", NameDisplay.styledLike("§6Alex", "§bAlex's Cam", style(ChatColor.RED, ChatColor.WHITE)));
    }

    @Test
    void onlyAColorKeyTheFileHasIsRead() throws InvalidConfigurationException {
        NameStyle without = read("body:\n  name:\n    shadow: true\n");
        assertNull(without.color());
        assertEquals(ChatColor.YELLOW, without.defaultColor());

        assertEquals(ChatColor.RED, read("body:\n  name:\n    color: red\n").color());
        // Not a colour: reported, and the default colour in its place.
        assertEquals(ChatColor.YELLOW, read("body:\n  name:\n    color: blau\n").color());
    }
}
