package de.elia.cameraplugin.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where each key of a YAML file stands in its text, line by line - for
 * {@link ConfigUpdater}, which adds to a file without rewriting it.
 *
 * <p>Bukkit can save a configuration again, but only by writing it anew:
 * every comment the server owner wrote is gone, and so is every blank line
 * and every quote they chose. Adding a key by hand keeps all of that, and for
 * that the lines have to be known - where a key starts, which lines belong to
 * it, which comment above it is its description.</p>
 *
 * <p>Read is the block style that the files of the plugin are written in:
 * keys with their value on the same line, sections indented below their key,
 * lists with a dash. A line that does not fit - a key written as a flow map
 * over several lines, an anchor, a second document - makes the outline
 * {@link #isReliable() unreliable}, and the file is left alone. Bukkit itself
 * has already read the file by the time this runs, so a file that is wrong
 * YAML never gets here.</p>
 */
final class YamlOutline {

    /**
     * A key that may start a line: quoted with double or single quotes, or
     * plain - then neither starting with a character that means something
     * else in YAML nor holding a colon followed by a space.
     */
    private static final Pattern KEY = Pattern.compile(
            "(\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^']|'')*'|[^\\s#'\"\\[\\]{}&*!|>%@`?,-][^#]*?|-[^\\s#][^#]*?)"
                    + "\\s*:(?=\\s|$)");

    /** A value that is only an anchor or a tag, with what it names on the lines below. */
    private static final Pattern BARE_ANCHOR_OR_TAG = Pattern.compile("[&!]\\S+\\s*(#.*)?");

    /** One key of the file, and the lines that belong to it. */
    static final class Node {
        /** The key without quotes, as Bukkit names it. */
        final String key;
        /** The keys from the top of the file down to this one, joined with dots. */
        final String path;
        /** How many spaces stand before the key. -1 for the top of the file, which has no line. */
        final int indent;
        /** The line of the key, counted from 0. -1 for the top of the file. */
        final int line;
        /** Where the key starts and ends on its line, quotes included. */
        final int keyStart;
        final int keyEnd;
        /** Where the value on the line of the key starts, or -1 when it has none and the lines below hold it. */
        final int valueStart;
        /** The keys indented below this one, in the order of the file. */
        final List<Node> children = new ArrayList<>();
        final Node parent;
        /** The last line that belongs to the key: its value, its list, everything indented below it. */
        int end;
        /** Whether the value is a list on the lines below, so that no key can be indented below it. */
        boolean holdsList;

        Node(String key, String path, int indent, int line, int keyStart, int keyEnd, int valueStart, Node parent) {
            this.key = key;
            this.path = path;
            this.indent = indent;
            this.line = line;
            this.keyStart = keyStart;
            this.keyEnd = keyEnd;
            this.valueStart = valueStart;
            this.parent = parent;
            this.end = line;
        }

        /** Whether the value stands on the line of the key, so that nothing can be indented below it as a key. */
        boolean hasInlineValue() {
            return valueStart >= 0;
        }
    }

    private final List<String> lines;
    private final Node root = new Node(null, "", -1, -1, -1, -1, -1, null);
    private final Map<String, Node> byPath = new HashMap<>();
    private final java.util.BitSet keyLines = new java.util.BitSet();
    private boolean reliable = true;

    private YamlOutline(List<String> lines) {
        this.lines = lines;
    }

    /** Reads the outline of the lines given. The list is not copied: it is the text the outline speaks of. */
    static YamlOutline of(List<String> lines) {
        YamlOutline outline = new YamlOutline(lines);
        outline.read();
        return outline;
    }

    /** Whether every line could be placed. If not, nothing may be changed going by this outline. */
    boolean isReliable() {
        return reliable;
    }

    /** The top of the file, whose children are the keys that are not indented. */
    Node root() {
        return root;
    }

    /** The key at this path, or {@code null} when the text has none there. */
    Node node(String path) {
        return byPath.get(path);
    }

    List<String> lines() {
        return lines;
    }

    /** Whether a key starts on this line. */
    boolean isKeyLine(int line) {
        return line >= 0 && keyLines.get(line);
    }

    // ---------------------------------------------------------------- reading

    private void read() {
        List<Node> open = new ArrayList<>();
        open.add(root);
        // A key whose value goes on below its line - a long text, a list -
        // owns every line indented deeper than this, and with a list also
        // every further dash at this indentation.
        Node owner = null;
        int ownerIndent = -1;
        boolean ownsList = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (line.substring(0, indent).indexOf('\t') >= 0) {
                reliable = false;
                return;
            }
            if (owner != null) {
                if (indent > ownerIndent || (ownsList && indent == ownerIndent && isListItem(trimmed))) {
                    extend(owner, i);
                    continue;
                }
                owner = null;
            }
            if (isListItem(trimmed)) {
                while (top(open).indent > indent) {
                    open.remove(open.size() - 1);
                }
                Node list = top(open);
                if (list == root || !list.children.isEmpty()) {
                    // A list at the top of the file, or next to keys of the
                    // same section: no file of the plugin is written so.
                    reliable = false;
                    return;
                }
                extend(list, i);
                list.holdsList = true;
                owner = list;
                ownerIndent = indent;
                ownsList = true;
                continue;
            }
            java.util.regex.Matcher key = KEY.matcher(line).region(indent, line.length());
            if (!key.lookingAt() || trimmed.equals("---") || trimmed.equals("...")) {
                reliable = false;
                return;
            }
            while (top(open).indent >= indent) {
                open.remove(open.size() - 1);
            }
            Node parent = top(open);
            String name = unquote(key.group(1));
            String path = parent == root ? name : parent.path + "." + name;
            int afterColon = key.end();
            String rest = line.substring(afterColon).strip();
            int valueStart = rest.isEmpty() || rest.startsWith("#") ? -1 : line.indexOf(rest, afterColon);
            if (valueStart >= 0 && BARE_ANCHOR_OR_TAG.matcher(rest).matches()) {
                reliable = false;
                return;
            }
            Node node = new Node(name, path, indent, i, key.start(1), key.end(1), valueStart, parent);
            if (byPath.put(path, node) != null) {
                // The same key twice: which of them counts is up to the parser.
                reliable = false;
                return;
            }
            parent.children.add(node);
            keyLines.set(i);
            extend(parent, i);
            if (node.hasInlineValue()) {
                owner = node;
                ownerIndent = indent;
                ownsList = false;
            } else {
                open.add(node);
            }
        }
    }

    private static Node top(List<Node> open) {
        return open.get(open.size() - 1);
    }

    /** Counts a line to a key and to every key it stands in. */
    private static void extend(Node node, int line) {
        for (Node n = node; n != null; n = n.parent) {
            n.end = Math.max(n.end, line);
        }
    }

    private static boolean isListItem(String trimmed) {
        return trimmed.equals("-") || trimmed.startsWith("- ");
    }

    /** The key as Bukkit names it: without its quotes, and what they escape as it is meant. */
    private static String unquote(String key) {
        if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            return key.substring(1, key.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if (key.length() >= 2 && key.startsWith("'") && key.endsWith("'")) {
            return key.substring(1, key.length() - 1).replace("''", "'");
        }
        return key;
    }

    // ------------------------------------------------------------- around a key

    /**
     * The first line of the description of a key: the comment lines right
     * above it, at its own indentation. A blank line ends the description, and
     * so does a comment indented otherwise - that one belongs to what stands
     * before, like an example left commented out at the end of a section.
     */
    int commentStart(Node node) {
        int start = node.line;
        for (int i = node.line - 1; i >= 0; i--) {
            String line = lines.get(i);
            String trimmed = line.strip();
            if (!trimmed.startsWith("#") || line.length() - line.stripLeading().length() != node.indent) {
                break;
            }
            start = i;
        }
        return start;
    }

    /** How many blank lines stand right above a line. */
    int blankLinesAbove(int line) {
        int count = 0;
        for (int i = line - 1; i >= 0 && lines.get(i).isBlank(); i--) {
            count++;
        }
        return count;
    }

    /** How many blank lines stand right below a line. */
    int blankLinesBelow(int line) {
        int count = 0;
        for (int i = line + 1; i < lines.size() && lines.get(i).isBlank(); i++) {
            count++;
        }
        return count;
    }
}
