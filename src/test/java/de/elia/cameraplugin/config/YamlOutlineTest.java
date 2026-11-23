package de.elia.cameraplugin.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link YamlOutline}: which lines belong to which key, and which files it does not try to follow. */
class YamlOutlineTest {

    private static YamlOutline outline(String text) {
        return YamlOutline.of(new ArrayList<>(Arrays.asList(text.split("\n", -1))));
    }

    @Test
    void keysKnowTheirLines() {
        YamlOutline outline = outline("""
                # The top
                first:
                  # A switch
                  enabled: true
                  'quoted key': 1
                list:
                  - a
                  - b
                text: |
                  line one
                  line two
                last: x
                """);

        assertTrue(outline.isReliable());
        YamlOutline.Node first = outline.node("first");
        assertEquals(List.of("first.enabled", "first.quoted key"), first.children.stream().map(n -> n.path).toList());
        assertEquals(4, first.end);
        assertEquals(0, outline.commentStart(first));
        assertEquals(2, outline.commentStart(outline.node("first.enabled")));
        assertTrue(outline.node("first.enabled").hasInlineValue());
        assertFalse(first.hasInlineValue());

        YamlOutline.Node list = outline.node("list");
        assertTrue(list.holdsList);
        assertEquals(7, list.end);

        assertEquals(10, outline.node("text").end);
        assertNotNull(outline.node("last"));
        assertTrue(outline.isKeyLine(11));
        assertFalse(outline.isKeyLine(10));
    }

    @Test
    void commentBelongsToAKeyOnlyRightAboveItAndAtItsIndentation() {
        YamlOutline outline = outline("""
                # Not about a

                a: 1
                section:
                  b: 1
                # About nothing below
                  c: 2
                """);

        assertEquals(2, outline.commentStart(outline.node("a")));
        assertEquals(6, outline.commentStart(outline.node("section.c")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "first:\n\tkey: 1",            // a tab in the indentation
            "a: 1\na: 2",                  // the same key twice
            "a: &defaults\n  b: 1",        // an anchor
            "- a\n- b",                    // a list at the top
            "a: 1\n---\nb: 2",             // a second document
            "a:\n  b: 1\n  - c",           // a list next to keys
    })
    void layoutItCannotFollowIsUnreliable(String text) {
        assertFalse(outline(text).isReliable());
    }
}
