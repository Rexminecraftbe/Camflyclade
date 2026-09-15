import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Prueft jeden Aufruf, den das fertige Plugin-Jar in die Server-API macht,
 * gegen paper-api.
 *
 * <p>Das Plugin wird gegen spigot-api uebersetzt, laeuft aber auf Paper. Die
 * beiden APIs sind nicht deckungsgleich, und was nur in spigot-api steht,
 * faellt erst im Spiel als NoSuchMethodError auf. Darum werden hier alle
 * Klassen aus target/classes mit javap zerlegt, jede Referenz auf
 * org/bukkit, net/md_5 und io/papermc herausgezogen und per Reflection gegen
 * paper-api aufgeloest - mitsamt Oberklassen und Superinterfaces, und bei
 * Interfaces zusaetzlich java.lang.Object.</p>
 *
 * <p>Aufruf: {@code ApiCheck <javap> <klassenordner> <klassenpfad> [erwartete Anzahl]}</p>
 */
public final class ApiCheck {

    /** Eine Referenz auf ein Feld, eine Methode oder einen Konstruktor der Server-API. */
    private record Ref(String owner, String name, String desc, boolean field) {
        String render() {
            return owner + " " + name + " " + desc;
        }
    }

    /** Wonach wir suchen. Alles andere geht uns nichts an. */
    private static final String[] API_PREFIXES = { "org/bukkit/", "net/md_5/", "io/papermc/" };

    /** javap schreibt Referenzen als Kommentar hinter den Bytecode. */
    private static final Pattern REF_LINE =
            Pattern.compile("//\\s+(Method|InterfaceMethod|Field)\\s+(\\S.*?)\\s*$");

    /** Klassen, deren Signaturen auf Fremdklassen zeigen, die uns fehlen. */
    private static final Set<String> incomplete = new TreeSet<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Aufruf: ApiCheck <javap> <klassenordner> <klassenpfad> [erwartete Anzahl]");
            System.exit(2);
        }
        String javap = args[0];
        Path classesDir = Paths.get(args[1]);
        String classpath = args[2];
        int expected = args.length > 3 ? Integer.parseInt(args[3]) : -1;

        if (!Files.isDirectory(classesDir)) {
            System.err.println("Klassenordner fehlt: " + classesDir);
            System.exit(2);
        }

        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(classesDir)) {
            classFiles = walk.filter(p -> p.toString().endsWith(".class"))
                    .sorted()
                    .toList();
        }
        if (classFiles.isEmpty()) {
            System.err.println("Keine Klassen in " + classesDir);
            System.exit(2);
        }
        System.out.println("Klassen im Jar: " + classFiles.size());

        // owner/name/desc -> die Plugin-Klassen, die den Aufruf machen
        Map<Ref, Set<String>> refs = new TreeMap<>(
                Comparator.comparing(Ref::owner).thenComparing(Ref::name).thenComparing(Ref::desc));
        for (List<Path> batch : batches(classFiles, 60)) {
            disassemble(javap, batch, refs);
        }

        long ctors = refs.keySet().stream().filter(r -> !r.field() && r.name().equals("<init>")).count();
        long fields = refs.keySet().stream().filter(Ref::field).count();
        long methods = refs.size() - ctors - fields;
        System.out.println("Referenzen in die Server-API: " + refs.size());
        System.out.println("    Methodenaufrufe: " + methods
                + (expected > 0 ? "   (Sollmarke " + expected + ")" : ""));
        System.out.println("    Konstruktoren:   " + ctors);
        System.out.println("    Feldzugriffe:    " + fields);

        URLClassLoader loader = loaderFor(classpath);
        List<String> missing = new ArrayList<>();
        List<String> unknown = new ArrayList<>();

        for (Map.Entry<Ref, Set<String>> e : refs.entrySet()) {
            Ref ref = e.getKey();
            incomplete.clear();
            Result r = resolve(loader, ref);
            String where = String.join(", ", e.getValue());
            switch (r) {
                case FOUND -> { }
                case MISSING -> missing.add(ref.render() + "\n        benutzt von: " + where);
                case UNKNOWN -> unknown.add(ref.render() + "\n        unvollstaendig: " + String.join(", ", incomplete)
                        + "\n        benutzt von: " + where);
            }
        }

        if (!unknown.isEmpty()) {
            System.out.println();
            System.out.println("Nicht entscheidbar (" + unknown.size() + "): die Signaturen der Klasse "
                    + "zeigen auf Fremdklassen, die nicht im Klassenpfad liegen.");
            unknown.forEach(s -> System.out.println("    " + s));
        }

        System.out.println();
        if (missing.isEmpty()) {
            System.out.println("Alle " + refs.size() + " Referenzen sind in paper-api vorhanden.");
        } else {
            System.out.println("FEHLT IN PAPER-API (" + missing.size() + "):");
            missing.forEach(s -> System.out.println("    " + s));
        }

        if (expected > 0 && methods != expected) {
            System.out.println();
            System.out.println("Hinweis: die Sollmarke steht auf " + expected + " Methodenaufrufen, gezaehlt"
                    + " wurden " + methods + ". Der Code hat sich veraendert - Sollmarke in lib.sh nachziehen,"
                    + " wenn das so gewollt ist.");
        }

        System.exit(missing.isEmpty() ? 0 : 1);
    }

    private enum Result { FOUND, MISSING, UNKNOWN }

    // --- javap ------------------------------------------------------------

    private static void disassemble(String javap, List<Path> files, Map<Ref, Set<String>> refs) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(javap);
        cmd.add("-p");
        cmd.add("-c");
        for (Path p : files) {
            cmd.add(p.toString());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();

        String current = "?";
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                String trimmed = line.trim();
                // Kopfzeile einer Klasse, damit wir sagen koennen, wer den Aufruf macht.
                if (trimmed.startsWith("Compiled from ")) {
                    continue;
                }
                if (!line.startsWith(" ") && trimmed.endsWith("{")) {
                    String head = classNameOf(trimmed);
                    if (head != null) {
                        current = head;
                    }
                    continue;
                }
                Matcher m = REF_LINE.matcher(line);
                if (!m.find()) {
                    continue;
                }
                Ref ref = parse(m.group(2), "Field".equals(m.group(1)));
                if (ref != null) {
                    refs.computeIfAbsent(ref, k -> new LinkedHashSet<>()).add(current);
                }
            }
        }
        int code = proc.waitFor();
        if (code != 0) {
            throw new IllegalStateException("javap endete mit " + code);
        }
    }

    /** Aus "public final class de.elia.Foo extends Bar {" den Klassennamen holen. */
    private static String classNameOf(String head) {
        String[] parts = head.split("\\s+");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals("class") || parts[i].equals("interface")
                    || parts[i].equals("enum") || parts[i].equals("record")) {
                String name = parts[i + 1];
                int lt = name.indexOf('<');
                return lt < 0 ? name : name.substring(0, lt);
            }
        }
        return null;
    }

    /**
     * Zerlegt {@code org/bukkit/Foo.bar:(I)V} in Besitzer, Name und Deskriptor.
     *
     * <p>Zwei Fallen: Konstruktoren schreibt javap als {@code ."<init>":} mit
     * Anfuehrungszeichen, und Aufrufe auf Arrays haben einen Besitzer wie
     * {@code "[Lorg/bukkit/Material;"} - die loesen gegen Object auf und
     * interessieren hier nicht.</p>
     */
    private static Ref parse(String payload, boolean field) {
        if (payload.startsWith("\"[")) {
            return null;
        }
        int colon = payload.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String left = payload.substring(0, colon);
        String desc = payload.substring(colon + 1).trim();
        int dot = left.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        String owner = left.substring(0, dot);
        String name = unquote(left.substring(dot + 1));
        boolean interesting = false;
        for (String prefix : API_PREFIXES) {
            if (owner.startsWith(prefix)) {
                interesting = true;
                break;
            }
        }
        if (!interesting) {
            return null;
        }
        return new Ref(owner.replace('/', '.'), name, desc, field);
    }

    private static String unquote(String s) {
        return s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")
                ? s.substring(1, s.length() - 1) : s;
    }

    // --- Aufloesen --------------------------------------------------------

    private static Result resolve(ClassLoader loader, Ref ref) {
        Class<?> owner;
        try {
            owner = Class.forName(ref.owner(), false, loader);
        } catch (ClassNotFoundException | LinkageError ex) {
            return Result.MISSING;
        }

        if (!ref.field() && ref.name().equals("<init>")) {
            try {
                for (Constructor<?> c : owner.getDeclaredConstructors()) {
                    if (descriptorOf(c.getParameterTypes(), void.class).equals(ref.desc())) {
                        return Result.FOUND;
                    }
                }
            } catch (Throwable t) {
                incomplete.add(owner.getName() + " (" + reason(t) + ")");
                return Result.UNKNOWN;
            }
            return Result.MISSING;
        }

        for (Class<?> c : hierarchy(owner)) {
            try {
                if (ref.field()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getName().equals(ref.name()) && typeDesc(f.getType()).equals(ref.desc())) {
                            return Result.FOUND;
                        }
                    }
                } else {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals(ref.name())
                                && descriptorOf(m.getParameterTypes(), m.getReturnType()).equals(ref.desc())) {
                            return Result.FOUND;
                        }
                    }
                }
            } catch (Throwable t) {
                // getDeclaredMethods() wirft alles oder nichts: Zeigt auch nur
                // eine Signatur auf eine fehlende Fremdklasse, bekommen wir
                // keine einzige Methode zu sehen. Dann duerfen wir nicht
                // "fehlt" melden.
                incomplete.add(c.getName() + " (" + reason(t) + ")");
            }
        }
        return incomplete.isEmpty() ? Result.MISSING : Result.UNKNOWN;
    }

    private static String reason(Throwable t) {
        String msg = t.getMessage();
        return t.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    /** Die Klasse selbst, alle Oberklassen, alle Superinterfaces - und bei Interfaces Object. */
    private static List<Class<?>> hierarchy(Class<?> start) {
        List<Class<?>> out = new ArrayList<>();
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> todo = new ArrayDeque<>();
        todo.add(start);
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (c == null || !seen.add(c)) {
                continue;
            }
            out.add(c);
            try {
                if (c.getSuperclass() != null) {
                    todo.add(c.getSuperclass());
                }
                for (Class<?> i : c.getInterfaces()) {
                    todo.add(i);
                }
            } catch (Throwable t) {
                incomplete.add(c.getName() + " (" + reason(t) + ")");
            }
        }
        // Ein Interface erbt Object nicht ueber getSuperclass(), aber toString()
        // und Konsorten lassen sich darauf trotzdem aufrufen.
        if (start.isInterface() && seen.add(Object.class)) {
            out.add(Object.class);
        }
        return out;
    }

    // --- Deskriptoren -----------------------------------------------------

    private static String descriptorOf(Class<?>[] params, Class<?> ret) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) {
            sb.append(typeDesc(p));
        }
        return sb.append(')').append(typeDesc(ret)).toString();
    }

    private static String typeDesc(Class<?> c) {
        if (c.isArray()) {
            return "[" + typeDesc(c.getComponentType());
        }
        if (!c.isPrimitive()) {
            return "L" + c.getName().replace('.', '/') + ";";
        }
        return switch (c.getName()) {
            case "void" -> "V";
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            default -> throw new IllegalStateException(c.getName());
        };
    }

    // --- Kleinkram --------------------------------------------------------

    private static URLClassLoader loaderFor(String classpath) throws Exception {
        List<URL> urls = new ArrayList<>();
        for (String part : classpath.split(File.pathSeparator)) {
            if (!part.isBlank()) {
                urls.add(new File(part).toURI().toURL());
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), ApiCheck.class.getClassLoader().getParent());
    }

    private static List<List<Path>> batches(List<Path> all, int size) {
        List<List<Path>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return out;
    }

    private ApiCheck() {
    }
}
