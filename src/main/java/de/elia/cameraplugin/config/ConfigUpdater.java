package de.elia.cameraplugin.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Completes a file of the server owner with what the file built into the jar
 * has and it lacks, see {@link ConfigFile}.
 *
 * <p>Without this a file stays the way the first version that wrote it left
 * it: every setting added since is read from the jar, at its default, but
 * nowhere to be seen. The owner never learns that it is there, let alone what
 * it does. So each key the file lacks is written into it, with the comment
 * above it in the built-in file and at the place it has there - after the key
 * before it, or before the key after it. Nothing else of the file changes:
 * not a comment, not a blank line, not a value.</p>
 *
 * <p>A key is only added where leaving it out means its default anyway. Two
 * kinds of keys are therefore left alone:</p>
 * <ul>
 *   <li>the entries of a section whose keys the file chooses itself, such as
 *       {@code hide-ores.blocks}: an ore left out there is one that is not
 *       hidden. Such a section is added whole when it is missing, as it is
 *       then read from the jar as a whole.</li>
 *   <li>keys whose absence means something, such as a renamed key, read under
 *       its old name while the new one is missing. These are taken care of
 *       by {@link Migration}s before anything is added.</li>
 * </ul>
 *
 * <p>A {@link Migration} may also take a key out that is no setting any more,
 * move one into another section, or give a value its new name. Such a key is
 * written anew from the built-in file, with its comment from there, which
 * speaks of it as it is now; the old one goes with its comment.</p>
 *
 * <p>Before the text is handed back it is read once more, and every value the
 * file had must still be there, the same - under its new name, if it was
 * renamed - and nothing else may have come in but the added keys and the
 * version. Should that ever not be so, the file stays as it is: a setting the
 * file lacks is still read from the jar, a setting changed behind the owner's
 * back would not be.</p>
 */
public final class ConfigUpdater {

    /** The key of the version of the layout of the config file, see {@link Migration}. */
    public static final String VERSION_KEY = "config-version";

    /**
     * A change to a file, made before the missing keys are added. Each has to
     * be harmless on a file that already looks as it should: it is run on
     * every file with a lower version, including one written by hand from the
     * start.
     *
     * @param version the version that has the change, run on files below it;
     *                {@link #ALWAYS} for one that is run on every file, because
     *                the file itself shows whether it is needed
     * @param change  what it does
     */
    public record Migration(int version, java.util.function.Consumer<ConfigUpdater> change) {

        /** The {@code version} of a change that is run on every file. */
        public static final int ALWAYS = 0;
    }

    /**
     * What an update did.
     *
     * @param text    the file as it is now, or {@code null} when nothing had to change or when it failed
     * @param added   the keys that were added, the topmost of each - a section added whole is one
     * @param renamed old and new path of each key that was renamed or moved
     * @param removed the keys that were taken out, because they are no settings any more
     * @param values  each key whose value was given its new name, with the old value and the new one
     * @param failure why the file was not changed although it lacked something, or {@code null}: {@link #LAYOUT}
     *                or the key at which the completed file would not have said the same as before
     */
    public record Result(String text, List<String> added, Map<String, String> renamed, List<String> removed,
                         Map<String, List<String>> values, String failure) {

        /** Nothing changed, nothing failed. */
        static Result unchanged(String failure) {
            return new Result(null, List.of(), Map.of(), List.of(), Map.of(), failure);
        }

        /** The {@code failure} of a file whose lines could not all be placed, see {@link YamlOutline#isReliable()}. */
        public static final String LAYOUT = "";

        /** Whether the text of the file has changed and needs writing. */
        public boolean changed() {
            return text != null;
        }
    }

    private final List<String> lines;
    private final YamlOutline builtIn;
    private final Set<String> ownKeys;
    private final List<String> added = new ArrayList<>();
    private final Map<String, String> renamed = new LinkedHashMap<>();
    /** The keys taken out because they are no settings any more, for the report. */
    private final List<String> removed = new ArrayList<>();
    /** Each key whose value got its new name: the old value and the new one, for the report. */
    private final Map<String, List<String>> newValues = new LinkedHashMap<>();
    /** Every key taken out of the file, also the old place of one that moved: the check leaves them be. */
    private final Set<String> dropped = new java.util.HashSet<>();
    /** Every key written into the file anew, also a moved one and a section it needed: the check lets them in. */
    private final Set<String> placed = new java.util.HashSet<>();
    /** Old and new path of each key that moved with its value as it was, for the check. */
    private final Map<String, String> movedAsIs = new LinkedHashMap<>();
    private YamlOutline outline;
    /** Whether any line has changed. */
    private boolean changed;

    private ConfigUpdater(List<String> lines, YamlOutline builtIn, Set<String> ownKeys) {
        this.lines = lines;
        this.builtIn = builtIn;
        this.ownKeys = ownKeys;
        this.outline = YamlOutline.of(lines);
    }

    /**
     * Completes a file.
     *
     * @param text        the file of the server owner
     * @param builtInText the same file as the jar has it
     * @param ownKeys     sections whose keys the file chooses itself: nothing is added inside them
     * @param migrations  changes for older files, see {@link Migration}
     */
    public static Result update(String text, String builtInText, Set<String> ownKeys, List<Migration> migrations) {
        Text file = Text.of(text);
        Text model = Text.of(builtInText);
        YamlOutline builtIn = YamlOutline.of(model.lines);
        YamlConfiguration before = parse(file.body());
        YamlConfiguration reference = parse(model.body());
        if (before == null || reference == null || !builtIn.isReliable()) {
            return Result.unchanged(null);
        }
        ConfigUpdater updater = new ConfigUpdater(file.lines, builtIn, ownKeys);
        if (!updater.outline.isReliable()) {
            // Told only when it matters: a file that lacks nothing is fine
            // however it is written.
            return Result.unchanged(lacksAnything(before, reference, ownKeys) ? Result.LAYOUT : null);
        }
        int fileVersion = before.getInt(VERSION_KEY, 0);
        int builtInVersion = reference.getInt(VERSION_KEY, 0);
        for (Migration migration : migrations) {
            if (migration.version() == Migration.ALWAYS
                    || (fileVersion < migration.version() && migration.version() <= builtInVersion)) {
                migration.change().accept(updater);
            }
        }
        updater.addMissing(builtIn.root());
        if (fileVersion < builtInVersion && before.contains(VERSION_KEY)) {
            updater.setValue(VERSION_KEY, String.valueOf(builtInVersion));
        }
        if (!updater.changed) {
            return Result.unchanged(null);
        }
        String updated = file.text();
        String failure = updater.check(before, file.body());
        List<String> added = Collections.unmodifiableList(updater.added);
        Map<String, String> renames = Collections.unmodifiableMap(updater.renamed);
        List<String> removed = Collections.unmodifiableList(updater.removed);
        Map<String, List<String>> values = Collections.unmodifiableMap(updater.newValues);
        return new Result(failure == null ? updated : null, added, renames, removed, values, failure);
    }

    // ------------------------------------------------------------ migrations

    /** Whether the file has a key, rather than leaving it to the default. */
    public boolean has(String path) {
        return outline.node(path) != null;
    }

    /**
     * Gives a key a new name in the file, unless it has none under the old
     * name or already one under the new. The value, the comment and the
     * place stay.
     *
     * @param path   where it stands now
     * @param newKey its new name, the last part of the path only
     */
    public void rename(String path, String newKey) {
        YamlOutline.Node node = outline.node(path);
        if (node == null) {
            return;
        }
        String newPath = node.parent.path.isEmpty() ? newKey : node.parent.path + "." + newKey;
        if (outline.node(newPath) != null) {
            return;
        }
        String line = lines.get(node.line);
        lines.set(node.line, line.substring(0, node.keyStart) + newKey + line.substring(node.keyEnd));
        renamed.put(path, newPath);
        changed = true;
        outline = YamlOutline.of(lines);
    }

    /**
     * Writes a key the file lacks with a value of its own instead of the
     * default, for a file whose meaning would change with the default.
     */
    public void add(String path, String value) {
        YamlOutline.Node model = builtIn.node(path);
        if (model == null || !model.hasInlineValue() || has(path)) {
            return;
        }
        insert(model, value, true);
    }

    /**
     * The value of a key that has it on its own line, as YAML reads it, or
     * {@code null} when the file has no such key.
     */
    public String value(String path) {
        YamlOutline.Node node = outline.node(path);
        if (node == null || !node.hasInlineValue()) {
            return null;
        }
        YamlConfiguration line = parse("v: " + lines.get(node.line).substring(node.valueStart));
        Object value = line == null ? null : line.get("v");
        return value == null ? null : value.toString();
    }

    /**
     * Takes a key out of the file that is no setting any more, with its
     * comment and everything below it - and the section it stood in, when
     * nothing is left in that.
     */
    public void remove(String path) {
        if (cut(path)) {
            removed.add(path);
        }
    }

    /**
     * Moves a key to another place, with its value as it is - under another
     * name, or into another section. It is written there as the built-in file
     * has it, with its comment, and taken out where it was. Has the file a
     * key at the new place already, the old one only goes.
     *
     * @param path    where it stands now
     * @param newPath where the built-in file has it
     */
    public void move(String path, String newPath) {
        YamlOutline.Node node = outline.node(path);
        if (node == null || !node.hasInlineValue() || builtIn.node(newPath) == null) {
            return;
        }
        if (has(newPath) && !isPlaced(newPath)) {
            remove(path);
            return;
        }
        String text = lines.get(node.line).substring(node.valueStart);
        cut(path);
        place(newPath, text);
        renamed.put(path, newPath);
        movedAsIs.put(path, newPath);
    }

    /**
     * Writes the value of a key a second time into a section that a
     * {@link #move} has just written anew, for a setting that two now hold
     * where it was one. The key itself stays. Nothing happens where the
     * section was there before, with what the owner set in it.
     */
    public void copy(String path, String newPath) {
        YamlOutline.Node node = outline.node(path);
        if (node == null || !node.hasInlineValue() || !has(newPath) || !isPlaced(newPath)) {
            return;
        }
        setValue(newPath, lines.get(node.line).substring(node.valueStart));
    }

    /**
     * Moves a key into a section where the built-in file has no such key, at
     * its end and without a comment - for a key that only an older file has,
     * still read there. The section is added first, when the file lacks it.
     */
    public void moveOutside(String path, String newPath) {
        YamlOutline.Node node = outline.node(path);
        int dot = newPath.lastIndexOf('.');
        if (node == null || !node.hasInlineValue() || dot < 0 || has(newPath)) {
            return;
        }
        String text = lines.get(node.line).substring(node.valueStart);
        String parentPath = newPath.substring(0, dot);
        // Checked before anything is cut, so that the value cannot get lost on
        // the way - and only into another section: one that the cut leaves
        // empty would go with it.
        YamlOutline.Node section = has(parentPath) ? outline.node(parentPath) : builtIn.node(parentPath);
        if (section == null || section.hasInlineValue() || section.holdsList || path.startsWith(parentPath + ".")) {
            return;
        }
        cut(path);
        if (!has(parentPath)) {
            placeSection(parentPath);
        }
        YamlOutline.Node parent = outline.node(parentPath);
        int indent = parent.children.isEmpty() ? parent.indent + 2 : parent.children.getFirst().indent;
        lines.add(parent.end + 1, " ".repeat(Math.max(indent, 0)) + newPath.substring(dot + 1) + ": " + text);
        placed.add(newPath);
        renamed.put(path, newPath);
        movedAsIs.put(path, newPath);
        changed = true;
        outline = YamlOutline.of(lines);
    }

    /**
     * Gives the value of a key its new name, for a setting whose values are
     * called otherwise now. The key is written anew, as the built-in file has
     * it with its comment, which speaks of the new values.
     *
     * @param rename the new value for the one the file has, or {@code null} when it needs none
     */
    public void renameValue(String path, java.util.function.UnaryOperator<String> rename) {
        String old = value(path);
        String now = old == null || builtIn.node(path) == null ? null : rename.apply(old);
        if (now == null || now.equals(old)) {
            return;
        }
        cut(path);
        place(path, now);
        newValues.put(path, List.of(old, now));
    }

    /** Whether a key was written into the file by this update, itself or with a section it stands in. */
    private boolean isPlaced(String path) {
        for (String p : placed) {
            if (path.equals(p) || path.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes a key of the built-in file with a value of its own - and first
     * the section it stands in, with the defaults of the built-in file, when
     * the file lacks that.
     */
    private void place(String path, String value) {
        if (has(path)) {
            setValue(path, value);
            return;
        }
        int dot = path.lastIndexOf('.');
        String parentPath = dot < 0 ? "" : path.substring(0, dot);
        if (!parentPath.isEmpty() && !has(parentPath)) {
            placeSection(parentPath);
            setValue(path, value);
            return;
        }
        insert(builtIn.node(path), value, false);
        placed.add(path);
    }

    /** Writes a section of the built-in file whole, with its defaults - and the one it stands in, when missing. */
    private void placeSection(String path) {
        int dot = path.lastIndexOf('.');
        String parentPath = dot < 0 ? "" : path.substring(0, dot);
        if (!parentPath.isEmpty() && !has(parentPath)) {
            placeSection(parentPath);
            return;
        }
        insert(builtIn.node(path), null, false);
        placed.add(path);
    }

    /**
     * Takes a key out of the lines: its comment, the key, everything below
     * it. The blank lines around it are left so that one gap stays where two
     * met, and none where a section would start with one. A section left
     * empty goes with it.
     *
     * @return whether the file had the key
     */
    private boolean cut(String path) {
        YamlOutline.Node node = outline.node(path);
        if (node == null) {
            return false;
        }
        int from = outline.commentStart(node);
        int to = node.end;
        if (commentBelongsBelow(node, from)) {
            from = node.line;
        }
        int blanksBelow = outline.blankLinesBelow(to);
        boolean blankAbove = from == 0 || lines.get(from - 1).isBlank();
        boolean startsSection = node.parent != outline.root() && from == node.parent.line + 1;
        if (blanksBelow > 0 && (blankAbove || startsSection)) {
            to += blanksBelow;
        }
        if (to + 1 >= lines.size()) {
            // Nothing after it: no blank line is left at the end of the file.
            while (from > 0 && lines.get(from - 1).isBlank()) {
                from--;
            }
        }
        lines.subList(from, to + 1).clear();
        dropped.add(path);
        changed = true;
        outline = YamlOutline.of(lines);
        int dot = path.lastIndexOf('.');
        if (dot > 0) {
            YamlOutline.Node parent = outline.node(path.substring(0, dot));
            if (parent != null && parent.children.isEmpty() && !parent.hasInlineValue() && !parent.holdsList) {
                cut(parent.path);
            }
        }
        return true;
    }

    /**
     * Whether the comment above a key speaks of the keys right below it as
     * well, so that it stays when the key goes: the built-in file has the
     * same comment over one of them. Looked at are the keys that follow
     * without a comment or a blank line of their own, up to the first that
     * the built-in file has.
     */
    private boolean commentBelongsBelow(YamlOutline.Node node, int from) {
        if (from == node.line) {
            return false;
        }
        List<String> comment = lines.subList(from, node.line);
        List<YamlOutline.Node> siblings = node.parent.children;
        int end = node.end;
        for (int i = siblings.indexOf(node) + 1; i < siblings.size(); i++) {
            YamlOutline.Node below = siblings.get(i);
            if (below.line != end + 1) {
                return false;
            }
            YamlOutline.Node model = builtIn.node(below.path);
            if (model != null) {
                List<String> there = builtIn.lines().subList(builtIn.commentStart(model), model.line);
                return there.size() == comment.size() && sameComment(from, there);
            }
            end = below.end;
        }
        return false;
    }

    // ---------------------------------------------------------------- adding

    /** Adds every key below this one of the built-in file that the file lacks, in the order of the built-in file. */
    private void addMissing(YamlOutline.Node model) {
        for (YamlOutline.Node child : model.children) {
            YamlOutline.Node mine = outline.node(child.path);
            if (mine == null) {
                YamlOutline.Node parent = model == builtIn.root() ? outline.root() : outline.node(model.path);
                // Below a key that has a value of its own nothing is read anyway.
                if (parent != null && !parent.hasInlineValue() && !parent.holdsList) {
                    insert(child, null, true);
                }
            } else if (!mine.hasInlineValue() && !mine.holdsList && !child.hasInlineValue()
                    && !ownKeys.contains(child.path)) {
                addMissing(child);
            }
        }
    }

    /**
     * Writes a key of the built-in file into the file, with its comment and
     * everything below it, next to the keys it stands next to there.
     *
     * @param value  the value to write instead of the built-in one, or {@code null} for that
     * @param report whether it counts among the keys the file lacked, see {@link Result#added()}
     */
    private void insert(YamlOutline.Node model, String value, boolean report) {
        List<String> source = builtIn.lines();
        int from = builtIn.commentStart(model);
        List<String> block = new ArrayList<>(source.subList(from, model.end + 1));
        if (value != null) {
            int keyLine = model.line - from;
            block.set(keyLine, block.get(keyLine).substring(0, model.valueStart) + value);
        }
        YamlOutline.Node modelParent = model.parent;
        YamlOutline.Node parent = modelParent == builtIn.root() ? outline.root() : outline.node(modelParent.path);
        List<YamlOutline.Node> siblings = modelParent.children;
        int index = siblings.indexOf(model);

        int at;
        int indent;
        int blanksBefore = 0;
        int blanksAfter = 0;
        // Blank lines of the file that go, because the built-in file says how
        // many stand around the key.
        int removeBlanks = 0;
        YamlOutline.Node before = null;
        for (int i = index - 1; i >= 0 && before == null; i--) {
            before = outline.node(siblings.get(i).path);
        }
        YamlOutline.Node after = null;
        for (int i = index + 1; i < siblings.size() && before == null && after == null; i++) {
            after = outline.node(siblings.get(i).path);
        }
        if (before != null) {
            at = before.end + 1;
            indent = before.indent;
            blanksBefore = Math.min(builtIn.blankLinesAbove(from), 2);
            // Between two keys of the section, the blank lines are those of
            // the built-in file above the key and below it. Anywhere else -
            // at the end of the section - the ones of the file stay below.
            int gap = outline.blankLinesBelow(before.end);
            if (startsChildOf(parent, at + gap)) {
                removeBlanks = gap;
                blanksAfter = Math.min(builtIn.blankLinesBelow(model.end), 2);
            }
        } else if (after != null) {
            at = outline.commentStart(after);
            indent = after.indent;
            blanksAfter = Math.min(builtIn.blankLinesBelow(model.end), 2);
        } else if (parent == outline.root()) {
            at = lines.size();
            indent = 0;
            blanksBefore = lines.isEmpty() || lines.get(lines.size() - 1).isBlank() ? 0 : 1;
        } else {
            at = parent.end + 1;
            indent = parent.indent + (model.indent - modelParent.indent);
        }

        // A key deleted on its own often leaves its comment behind, right
        // where the key goes back. That comment is used then, instead of a
        // second one.
        int comments = model.line - from;
        if (comments > 0) {
            List<String> description = block.subList(0, comments);
            int below = at;
            while (below < lines.size() && lines.get(below).isBlank()) {
                below++;
            }
            int above = at - 1;
            while (above >= 0 && lines.get(above).isBlank()) {
                above--;
            }
            // Below, it must stand on its own: a comment right above a key is that key's.
            boolean leftBelow = sameComment(below, description) && !outline.isKeyLine(below + comments);
            boolean leftAbove = !leftBelow && sameComment(above - comments + 1, description);
            if (leftBelow || leftAbove) {
                at = leftBelow ? below + comments : above + 1;
                blanksBefore = 0;
                blanksAfter = 0;
                removeBlanks = 0;
                block = new ArrayList<>(block.subList(comments, block.size()));
            }
        }

        List<String> insertion = new ArrayList<>();
        for (int i = 0; i < blanksBefore; i++) {
            insertion.add("");
        }
        for (String line : block) {
            insertion.add(shift(line, indent - model.indent));
        }
        for (int i = 0; i < blanksAfter; i++) {
            insertion.add("");
        }
        for (int i = 0; i < removeBlanks; i++) {
            lines.remove(at);
        }
        lines.addAll(at, insertion);
        if (report) {
            added.add(model.path);
        }
        changed = true;
        outline = YamlOutline.of(lines);
    }

    /** Whether a key of this section, or the comment above it, starts on this line of the file. */
    private boolean startsChildOf(YamlOutline.Node parent, int line) {
        for (YamlOutline.Node child : parent.children) {
            if (outline.commentStart(child) == line) {
                return true;
            }
        }
        return false;
    }

    /** Whether the lines of the file from this one on are this comment, indentation aside. */
    private boolean sameComment(int start, List<String> comment) {
        if (start < 0 || start + comment.size() > lines.size()) {
            return false;
        }
        for (int i = 0; i < comment.size(); i++) {
            if (!lines.get(start + i).strip().equals(comment.get(i).strip())) {
                return false;
            }
        }
        return true;
    }

    /** Moves a line to the right, or to the left by as many spaces as it has. */
    private static String shift(String line, int by) {
        if (by > 0) {
            return line.isEmpty() ? line : " ".repeat(by) + line;
        }
        int remove = 0;
        while (remove < -by && remove < line.length() && line.charAt(remove) == ' ') {
            remove++;
        }
        return line.substring(remove);
    }

    /** Replaces the value on the line of a key. */
    private void setValue(String path, String value) {
        YamlOutline.Node node = outline.node(path);
        if (node == null || !node.hasInlineValue()) {
            return;
        }
        String line = lines.get(node.line);
        String now = line.substring(0, node.valueStart) + value;
        if (!now.equals(line)) {
            lines.set(node.line, now);
            changed = true;
            outline = YamlOutline.of(lines);
        }
    }

    // -------------------------------------------------------------- checking

    /**
     * Makes sure the new text says everything the old one said, see the
     * description of the class.
     *
     * @return the key at which it does not, or {@code null} when it does
     */
    private String check(YamlConfiguration before, String text) {
        YamlConfiguration after = parse(text);
        if (after == null) {
            return Result.LAYOUT;
        }
        for (String path : before.getKeys(true)) {
            if (before.isConfigurationSection(path) || path.equals(VERSION_KEY)) {
                continue;
            }
            String moved = movedAsIs.get(path);
            if (moved != null) {
                if (!Objects.equals(before.get(path), after.get(moved))) {
                    return path;
                }
                continue;
            }
            if (under(path, dropped)) {
                continue;
            }
            if (!Objects.equals(before.get(path), after.get(newPath(path)))) {
                return path;
            }
        }
        for (String path : after.getKeys(true)) {
            if (after.isConfigurationSection(path) || path.equals(VERSION_KEY)) {
                continue;
            }
            if (!before.contains(oldPath(path)) && !under(path, added) && !under(path, placed)) {
                return path;
            }
        }
        for (String path : added) {
            if (!after.contains(path)) {
                return path;
            }
        }
        for (String path : placed) {
            if (!after.contains(path)) {
                return path;
            }
        }
        return null;
    }

    /** Whether a key is one of these or stands below one of them. */
    private static boolean under(String path, java.util.Collection<String> keys) {
        for (String key : keys) {
            if (path.equals(key) || path.startsWith(key + ".")) {
                return true;
            }
        }
        return false;
    }

    /** Where a key of the old text stands in the new one, renamed or not. */
    private String newPath(String path) {
        for (Map.Entry<String, String> rename : renamed.entrySet()) {
            if (path.equals(rename.getKey()) || path.startsWith(rename.getKey() + ".")) {
                return rename.getValue() + path.substring(rename.getKey().length());
            }
        }
        return path;
    }

    /** Where a key of the new text stood in the old one, renamed or not. */
    private String oldPath(String path) {
        for (Map.Entry<String, String> rename : renamed.entrySet()) {
            if (path.equals(rename.getValue()) || path.startsWith(rename.getValue() + ".")) {
                return rename.getKey() + path.substring(rename.getValue().length());
            }
        }
        return path;
    }

    /**
     * Whether the file leaves out anything of the built-in one, outside the
     * sections whose keys it chooses itself and below keys that are sections
     * in it.
     */
    private static boolean lacksAnything(YamlConfiguration file, YamlConfiguration builtIn, Set<String> ownKeys) {
        for (String path : builtIn.getKeys(true)) {
            if (file.contains(path)) {
                continue;
            }
            int dot = path.lastIndexOf('.');
            String parent = dot < 0 ? "" : path.substring(0, dot);
            if (!ownKeys.contains(parent) && (parent.isEmpty() || file.isConfigurationSection(parent))) {
                return true;
            }
        }
        return false;
    }

    /** Reads a text as Bukkit does, or {@code null} when it cannot be read. */
    private static YamlConfiguration parse(String text) {
        YamlConfiguration config = new YamlConfiguration();
        try {
            config.loadFromString(text);
            return config;
        } catch (InvalidConfigurationException ex) {
            return null;
        }
    }

    /**
     * A text as lines, with what is around them: a byte order mark at the
     * start, the line ending, and whether the last line has one.
     */
    private static final class Text {
        private final boolean byteOrderMark;
        private final String lineEnding;
        private final boolean endsWithLineEnding;
        private final List<String> lines;

        private Text(boolean byteOrderMark, String lineEnding, boolean endsWithLineEnding, List<String> lines) {
            this.byteOrderMark = byteOrderMark;
            this.lineEnding = lineEnding;
            this.endsWithLineEnding = endsWithLineEnding;
            this.lines = lines;
        }

        static Text of(String text) {
            boolean mark = text.startsWith("﻿");
            String body = mark ? text.substring(1) : text;
            String ending = body.contains("\r\n") ? "\r\n" : "\n";
            List<String> lines = new ArrayList<>(Arrays.asList(body.split("\r?\n", -1)));
            boolean endsWithEnding = lines.size() > 1 && lines.get(lines.size() - 1).isEmpty();
            if (endsWithEnding) {
                lines.remove(lines.size() - 1);
            }
            return new Text(mark, ending, endsWithEnding, lines);
        }

        /** The text without its byte order mark, for the parser. */
        String body() {
            return String.join(lineEnding, lines) + (endsWithLineEnding ? lineEnding : "");
        }

        /** The text with its lines as they are now, everything around them as it was. */
        String text() {
            return (byteOrderMark ? "﻿" : "") + body();
        }
    }
}
