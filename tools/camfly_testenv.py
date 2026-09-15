#!/usr/bin/env python3
"""
CamFly - Testumgebung aufbauen und Tests fahren.

Das Skript macht alles, was sonst von Hand gemacht wurde:

  1. JDK 25 holen (das System hat meist nur 21)
  2. Plugin bauen (mvn -B clean package gegen spigot-api)
  3. Zusaetzlich gegen paper-api uebersetzen (die APIs sind nicht deckungsgleich)
  4. ApiCheck: jeden Bukkit-Aufruf im fertigen Jar gegen paper-api aufloesen
  5. Paper-Testserver holen, einrichten und starten (mit FIFO fuer die Konsole)
  6. mineflayer holen, auf Protokoll 26.2 flicken, Bot verbinden
  7. Tests im laufenden Spiel fahren
  8. Aufraeumen: target/ aus HEAD zuruecksetzen, Server und Bot beenden

Aufruf:
    python3 tools/camfly_testenv.py                  # alles
    python3 tools/camfly_testenv.py --steps build,apicheck
    python3 tools/camfly_testenv.py --keep-running   # Server laeuft weiter
    python3 tools/camfly_testenv.py --stop           # laufenden Server beenden

Das Skript aendert NICHTS am Plugin. Es baut, prueft und berichtet.
Alles, was es herunterlaedt, liegt unter --workdir (Standard:
~/camfly-testenv) und wird beim naechsten Lauf wiederverwendet.
"""

import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# Feste Werte. Alles, was eine Version hat, steht hier an einer Stelle.
# ---------------------------------------------------------------------------

MC_VERSION = "26.2"
PAPER_BUILD = 121
PAPER_API_VERSION = f"{MC_VERSION}.build.{PAPER_BUILD}-stable"
PROTOCOL_VERSION = 776          # 26.2; minecraft-data kennt nur 775 (26.1)
FALLBACK_DATA_VERSION = "26.1"  # von dort borgen wir die Paketdaten

JDK_URL = ("https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/"
           "jdk/hotspot/normal/eclipse")

PAPER_REPO = "https://repo.papermc.io/repository/maven-public"
CENTRAL = "https://repo1.maven.org/maven2"
FILL_API = f"https://fill.papermc.io/v3/projects/paper/versions/{MC_VERSION}/builds/{PAPER_BUILD}"

# paper-api und alles, was es zum Uebersetzen braucht.
# groupId, artifactId, version, repo
PAPER_API_DEPS = [
    ("io.papermc.paper", "paper-api", PAPER_API_VERSION, PAPER_REPO),
    ("com.google.guava", "guava", "33.6.0-jre", CENTRAL),
    ("org.jetbrains", "annotations", "26.0.2", CENTRAL),
    ("org.joml", "joml", "1.10.8", CENTRAL),
    ("org.yaml", "snakeyaml", "2.6", CENTRAL),
    ("net.md-5", "bungeecord-chat", "1.21-R0.4", CENTRAL),
    ("net.md-5", "bungeecord-serializer", "1.21-R0.4", CENTRAL),
    ("net.kyori", "adventure-api", "5.2.0", CENTRAL),
    ("net.kyori", "adventure-key", "5.2.0", CENTRAL),
    ("net.kyori", "adventure-text-minimessage", "5.2.0", CENTRAL),
    ("net.kyori", "adventure-text-logger-slf4j", "5.2.0", CENTRAL),
    ("net.kyori", "examination-api", "1.3.0", CENTRAL),
    ("org.slf4j", "slf4j-api", "2.0.17", CENTRAL),
]

# Sollmarke aus der Anleitung. Weicht die Zahl ab, ist das kein Fehler - nur
# ein Hinweis, dass sich am Plugin etwas geaendert hat.
# Dieser Pruefer zaehlt zurzeit 440: 373 Methoden- und 67 Feldzugriffe. Alle
# 440 gibt es auch in paper-api. Der Hinweis steht also bei jedem Lauf da.
EXPECTED_API_CALLS = 348

# Der Bot-Name steht fest im Skript. Ueber eine Umgebungsvariable geht er
# beim nohup-Start verloren, der zweite Bot joint dann als "TestBot" und
# kickt den ersten mit duplicate_login.
BOT_NAME = "CamFlyTester"

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 25565

STEPS = ["jdk", "build", "paperapi", "crosscheck", "apicheck",
         "server", "bot", "tests"]

# ---------------------------------------------------------------------------
# Ausgabe
# ---------------------------------------------------------------------------

class Log:
    BLUE, GREEN, RED, YELLOW, GREY, OFF = (
        "\033[94m", "\033[92m", "\033[91m", "\033[93m", "\033[90m", "\033[0m")

    @staticmethod
    def step(text):
        print(f"\n{Log.BLUE}=== {text} ==={Log.OFF}", flush=True)

    @staticmethod
    def info(text):
        print(f"  {text}", flush=True)

    @staticmethod
    def detail(text):
        print(f"{Log.GREY}  {text}{Log.OFF}", flush=True)

    @staticmethod
    def ok(text):
        print(f"  {Log.GREEN}OK{Log.OFF}   {text}", flush=True)

    @staticmethod
    def fail(text):
        print(f"  {Log.RED}FEHLT{Log.OFF} {text}", flush=True)

    @staticmethod
    def warn(text):
        print(f"  {Log.YELLOW}HINWEIS{Log.OFF} {text}", flush=True)


class Findings:
    """Sammelt alles, was am Ende berichtet wird."""

    def __init__(self):
        self.tests = []     # (name, ok, detail)
        self.problems = []  # Texte, die der Mensch lesen muss

    def test(self, name, passed, detail=""):
        self.tests.append({"name": name, "ok": bool(passed), "detail": detail})
        (Log.ok if passed else Log.fail)(f"{name}{(' - ' + detail) if detail else ''}")
        if not passed:
            self.problems.append(f"{name}: {detail}" if detail else name)
        return passed

    def problem(self, text):
        self.problems.append(text)
        Log.warn(text)

    @property
    def failed(self):
        return [t for t in self.tests if not t["ok"]]


FIND = Findings()

# ---------------------------------------------------------------------------
# Kleine Helfer
# ---------------------------------------------------------------------------

def run(cmd, cwd=None, env=None, check=True, capture=True, timeout=None):
    """Ein Kommando ausfuehren. Gibt CompletedProcess zurueck."""
    proc = subprocess.run(
        cmd, cwd=cwd and str(cwd), env=env, timeout=timeout,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        text=True, errors="replace")
    if check and proc.returncode != 0:
        out = (proc.stdout or "")[-4000:]
        raise RuntimeError(
            f"Kommando fehlgeschlagen ({proc.returncode}): {' '.join(map(str, cmd))}\n{out}")
    return proc


def download(url, dest, what=None):
    """Laedt eine Datei, wenn sie noch nicht da ist. Der Cache ist der Sinn."""
    dest = Path(dest)
    if dest.exists() and dest.stat().st_size > 0:
        Log.detail(f"aus dem Cache: {dest.name}")
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    Log.info(f"lade {what or dest.name} ...")
    run(["curl", "-sSL", "--fail", "--retry", "4", "--retry-delay", "2",
         "-o", str(tmp), url])
    tmp.replace(dest)
    Log.detail(f"{dest.name} ({dest.stat().st_size // 1024} KiB)")
    return dest


def maven_url(group, artifact, version, repo):
    return f"{repo}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.jar"


# ---------------------------------------------------------------------------
# Wo was liegt
# ---------------------------------------------------------------------------

class Env:
    def __init__(self, repo, workdir):
        self.repo = Path(repo).resolve()
        self.work = Path(workdir).resolve()
        self.cache = self.work / "cache"
        self.jdk = self.work / "jdk25"
        self.paperapi = self.work / "paper-api"
        self.crosscheck = self.work / "crosscheck"
        self.apicheck = self.work / "apicheck"
        self.server = self.work / "server"
        self.bot = self.work / "bot"
        self.artifacts = self.work / "artifacts"
        for d in (self.cache, self.artifacts):
            d.mkdir(parents=True, exist_ok=True)

    # --- JDK 25 ---
    @property
    def java_home(self):
        return self.jdk

    @property
    def javac(self):
        return self.jdk / "bin" / "javac"

    @property
    def java(self):
        return self.jdk / "bin" / "java"

    @property
    def javap(self):
        return self.jdk / "bin" / "javap"

    def build_env(self):
        """Umgebung mit JDK 25 vorne dran."""
        env = dict(os.environ)
        env["JAVA_HOME"] = str(self.java_home)
        env["PATH"] = f"{self.java_home / 'bin'}{os.pathsep}{env.get('PATH', '')}"
        return env

    @property
    def paper_api_jars(self):
        return sorted(self.paperapi.glob("*.jar"))

    @property
    def paper_api_cp(self):
        return os.pathsep.join(str(j) for j in self.paper_api_jars)

    @property
    def plugin_jar(self):
        """Das gebaute Jar, aus target/ herauskopiert - target/ wird ja
        wieder auf HEAD zurueckgesetzt."""
        return self.artifacts / "CamFly.jar"


def clean_dir(path):
    """Ausgabeordner leeren. Eine alte Klassenkopie verdeckt sonst die
    frisch gebaute - schon passiert."""
    path = Path(path)
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)
    return path


# ---------------------------------------------------------------------------
# 1. JDK 25
# ---------------------------------------------------------------------------

def step_jdk(env):
    Log.step("1. JDK 25")
    if env.javac.exists():
        ver = run([str(env.javac), "-version"], check=False).stdout.strip()
        Log.detail(f"schon da: {ver}")
    else:
        tar = download(JDK_URL, env.cache / "jdk25.tar.gz", "JDK 25 (~140 MiB)")
        tmp = clean_dir(env.work / ".jdk-extract")
        run(["tar", "-xzf", str(tar), "-C", str(tmp)])
        roots = [p for p in tmp.iterdir() if (p / "bin" / "javac").exists()]
        if not roots:
            raise RuntimeError("Im JDK-Archiv ist kein bin/javac zu finden")
        if env.jdk.exists():
            shutil.rmtree(env.jdk)
        roots[0].replace(env.jdk)
        shutil.rmtree(tmp, ignore_errors=True)

    out = run([str(env.java), "-version"], check=False).stdout
    major = re.search(r'version "(\d+)', out)
    ok = bool(major) and major.group(1) == "25"
    FIND.test("JDK 25 vorhanden", ok,
              (out.strip().splitlines() or ["?"])[-1] if not ok else f"{env.jdk}")
    return ok


# ---------------------------------------------------------------------------
# 2. Bauen (spigot-api)
# ---------------------------------------------------------------------------

def step_build(env):
    Log.step("2. Plugin bauen (mvn gegen spigot-api)")
    if not env.javac.exists():
        raise RuntimeError("Erst Schritt 'jdk' laufen lassen")
    Log.info("mvn -B clean package (Maven holt spigot-api selbst)")
    proc = run(["mvn", "-B", "clean", "package"], cwd=env.repo,
               env=env.build_env(), check=False, timeout=1800)
    ok = proc.returncode == 0
    if not ok:
        tail = "\n".join((proc.stdout or "").strip().splitlines()[-40:])
        FIND.test("mvn package", False, "siehe Ausgabe unten")
        print(tail)
        return False

    jars = sorted((env.repo / "target").glob("*.jar"))
    main = [j for j in jars if "original" not in j.name and "sources" not in j.name]
    if not main:
        FIND.test("mvn package", False, "kein Jar in target/")
        return False
    shutil.copy2(main[0], env.plugin_jar)
    classes = env.repo / "target" / "classes"
    n = len(list(classes.rglob("*.class")))
    warnings = [l for l in (proc.stdout or "").splitlines() if "[WARNING]" in l]
    FIND.test("mvn package", True, f"{main[0].name}, {n} Klassen, {len(warnings)} Warnungen")
    for w in warnings[:10]:
        Log.detail(w.strip())
    return True


# ---------------------------------------------------------------------------
# 3. paper-api holen
# ---------------------------------------------------------------------------

def step_paperapi(env):
    Log.step("3. paper-api und Abhaengigkeiten")
    env.paperapi.mkdir(parents=True, exist_ok=True)
    for group, artifact, version, repo in PAPER_API_DEPS:
        name = f"{artifact}-{version}.jar"
        cached = download(maven_url(group, artifact, version, repo),
                          env.cache / name, name)
        target = env.paperapi / name
        if not target.exists():
            shutil.copy2(cached, target)
    got = len(env.paper_api_jars)
    FIND.test("paper-api Klassenpfad", got == len(PAPER_API_DEPS),
              f"{got} von {len(PAPER_API_DEPS)} Jars")
    return got == len(PAPER_API_DEPS)


# ---------------------------------------------------------------------------
# 4. Gegen paper-api uebersetzen
# ---------------------------------------------------------------------------

def step_crosscheck(env):
    Log.step("4. Quelltext gegen paper-api uebersetzen")
    out = clean_dir(env.crosscheck / "classes")
    sources = sorted((env.repo / "src" / "main" / "java").rglob("*.java"))
    if not sources:
        FIND.test("javac gegen paper-api", False, "keine Quelldateien gefunden")
        return False
    argfile = env.crosscheck / "sources.txt"
    argfile.write_text("\n".join(str(s) for s in sources), encoding="utf-8")
    proc = run([str(env.javac), "-nowarn", "--release", "25",
                "-cp", env.paper_api_cp, "-d", str(out), f"@{argfile}"],
               check=False, timeout=900)
    ok = proc.returncode == 0
    errors = [l for l in (proc.stdout or "").splitlines() if ": error:" in l]
    FIND.test("javac gegen paper-api", ok,
              f"{len(sources)} Dateien" if ok else f"{len(errors)} Fehler")
    for e in errors[:20]:
        Log.detail(e.strip())
    return ok


# ---------------------------------------------------------------------------
# 5. ApiCheck: jeder Aufruf aus dem fertigen Jar gegen paper-api
# ---------------------------------------------------------------------------

APICHECK_JAVA = r'''
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Nimmt target/classes auseinander und loest jeden Aufruf auf org/bukkit,
 * net/md_5 und io/papermc gegen paper-api auf. Das pom baut gegen spigot-api,
 * der Server laeuft auf Paper - was nur Spigot kennt, fliegt hier auf, bevor
 * es im Spiel als NoSuchMethodError landet.
 */
public final class ApiCheck {

    private static final String[] PREFIXES = {"org/bukkit", "net/md_5", "io/papermc"};

    /** Ein Aufruf: Besitzer, Name, Deskriptor. */
    private record Ref(String owner, String name, String desc, boolean field) {
        String pretty() {
            return owner.replace('/', '.') + "." + name + " " + desc;
        }
    }

    public static void main(String[] args) throws Exception {
        Path classesDir = Paths.get(args[0]);
        String classpath = args[1];
        Path reportFile = Paths.get(args[2]);
        String javap = args[3];

        List<Path> classFiles;
        try (Stream<Path> walk = Files.walk(classesDir)) {
            classFiles = walk.filter(p -> p.toString().endsWith(".class"))
                             .sorted().collect(Collectors.toList());
        }
        if (classFiles.isEmpty()) {
            System.out.println("KEINE KLASSEN in " + classesDir);
            System.exit(2);
        }

        Set<Ref> refs = new LinkedHashSet<>();
        // javap in Haeppchen, sonst wird die Kommandozeile zu lang
        for (int i = 0; i < classFiles.size(); i += 40) {
            List<Path> chunk = classFiles.subList(i, Math.min(i + 40, classFiles.size()));
            List<String> cmd = new ArrayList<>(List.of(javap, "-p", "-c"));
            for (Path p : chunk) cmd.add(p.toString());
            Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Ref ref = parse(line);
                    if (ref != null) refs.add(ref);
                }
            }
            if (proc.waitFor() != 0) {
                System.out.println("javap ist ausgestiegen");
                System.exit(2);
            }
        }

        List<URL> urls = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank()) urls.add(new File(entry).toURI().toURL());
        }
        URLClassLoader loader = new URLClassLoader(
                urls.toArray(new URL[0]), ApiCheck.class.getClassLoader().getParent());

        List<String> missing = new ArrayList<>();
        List<String> unclear = new ArrayList<>();
        int resolved = 0;

        for (Ref ref : refs) {
            Class<?> owner;
            try {
                owner = Class.forName(ref.owner().replace('/', '.'), false, loader);
            } catch (ClassNotFoundException e) {
                missing.add(ref.pretty() + "   (Klasse gibt es in paper-api nicht)");
                continue;
            } catch (Throwable t) {
                // Die Klasse selbst ist da, ihre Signaturen zeigen aber auf
                // etwas, das nicht im Klassenpfad liegt. Kein Befund.
                unclear.add(ref.pretty() + "   (" + t.getClass().getSimpleName() + ")");
                continue;
            }
            boolean[] soft = new boolean[1];
            if (find(owner, ref, soft)) {
                resolved++;
            } else if (soft[0]) {
                unclear.add(ref.pretty() + "   (Signaturen zeigen auf fehlende Fremdklassen)");
            } else {
                missing.add(ref.pretty());
            }
        }

        System.out.println("Aufrufe insgesamt: " + refs.size());
        System.out.println("aufgeloest:        " + resolved);
        System.out.println("fehlen:            " + missing.size());
        System.out.println("unklar:            " + unclear.size());
        for (String m : missing) System.out.println("  FEHLT   " + m);
        for (String u : unclear) System.out.println("  UNKLAR  " + u);

        StringBuilder json = new StringBuilder();
        json.append("{\"total\":").append(refs.size())
            .append(",\"resolved\":").append(resolved)
            .append(",\"missing\":").append(jsonList(missing))
            .append(",\"unclear\":").append(jsonList(unclear)).append("}");
        Files.writeString(reportFile, json.toString(), StandardCharsets.UTF_8);
        System.exit(missing.isEmpty() ? 0 : 1);
    }

    private static String jsonList(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(items.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Zieht aus einer javap-Zeile den Aufruf heraus. javap schreibt ihn hinter
     * den Kommentar, etwa
     *   invokevirtual #12  // Method org/bukkit/entity/Player.getName:()Ljava/lang/String;
     * Konstruktoren stehen dort als ."&lt;init&gt;": in Anfuehrungszeichen.
     */
    private static Ref parse(String line) {
        int at = line.indexOf("// ");
        if (at < 0) return null;
        String rest = line.substring(at + 3).trim();
        boolean field;
        if (rest.startsWith("Method ")) { rest = rest.substring(7); field = false; }
        else if (rest.startsWith("InterfaceMethod ")) { rest = rest.substring(16); field = false; }
        else if (rest.startsWith("Field ")) { rest = rest.substring(6); field = true; }
        else return null;

        rest = rest.trim();
        int colon = rest.lastIndexOf(':');
        if (colon < 0) return null;
        String desc = rest.substring(colon + 1).trim();
        String ownerAndName = rest.substring(0, colon);
        int dot = ownerAndName.lastIndexOf('.');
        if (dot < 0) return null;           // Aufruf in der eigenen Klasse
        String owner = ownerAndName.substring(0, dot);
        String name = ownerAndName.substring(dot + 1);
        if (name.startsWith("\"") && name.endsWith("\"") && name.length() > 1) {
            name = name.substring(1, name.length() - 1);   // ."<init>":
        }
        if (owner.startsWith("[")) return null;            // Arraytyp
        boolean wanted = false;
        for (String p : PREFIXES) if (owner.startsWith(p)) wanted = true;
        return wanted ? new Ref(owner, name, desc, field) : null;
    }

    /** Sucht den Aufruf in der Klasse, ihren Oberklassen und allen Interfaces. */
    private static boolean find(Class<?> start, Ref ref, boolean[] soft) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        List<Class<?>> todo = new ArrayList<>();
        todo.add(start);
        if (start.isInterface()) todo.add(Object.class);   // auch Interfaces erben von Object
        while (!todo.isEmpty()) {
            Class<?> c = todo.remove(0);
            if (c == null || !seen.add(c)) continue;
            try {
                if (ref.field()) {
                    for (Field f : c.getDeclaredFields()) {
                        if (f.getName().equals(ref.name()) && desc(f.getType()).equals(ref.desc())) return true;
                    }
                } else if (ref.name().equals("<init>")) {
                    for (Constructor<?> ct : c.getDeclaredConstructors()) {
                        if (paramDesc(ct.getParameterTypes(), "V").equals(ref.desc())) return true;
                    }
                } else {
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getName().equals(ref.name())
                                && paramDesc(m.getParameterTypes(), desc(m.getReturnType())).equals(ref.desc())) {
                            return true;
                        }
                    }
                }
            } catch (Throwable t) {
                soft[0] = true;   // Signatur zeigt auf eine Klasse ausserhalb des Pfades
            }
            if (c.getSuperclass() != null) todo.add(c.getSuperclass());
            try {
                todo.addAll(List.of(c.getInterfaces()));
            } catch (Throwable t) {
                soft[0] = true;
            }
        }
        return false;
    }

    private static String paramDesc(Class<?>[] params, String ret) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : params) sb.append(desc(p));
        return sb.append(')').append(ret).toString();
    }

    private static String desc(Class<?> c) {
        if (c == void.class) return "V";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == double.class) return "D";
        if (c == float.class) return "F";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c.isArray()) return "[" + desc(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }
}
'''


def step_apicheck(env):
    Log.step("5. ApiCheck: Aufrufe im fertigen Jar gegen paper-api aufloesen")
    classes = env.repo / "target" / "classes"
    if not classes.exists():
        FIND.test("ApiCheck", False, "target/classes fehlt - erst bauen")
        return False
    if not env.paper_api_jars:
        FIND.test("ApiCheck", False, "paper-api fehlt - erst Schritt 'paperapi'")
        return False

    src = clean_dir(env.apicheck / "src")
    out = clean_dir(env.apicheck / "classes")   # sauber halten, sonst verdeckt
    (src / "ApiCheck.java").write_text(APICHECK_JAVA, encoding="utf-8")
    run([str(env.javac), "-nowarn", "-d", str(out), str(src / "ApiCheck.java")])

    report = env.apicheck / "report.json"
    proc = run([str(env.java), "-cp", str(out), "ApiCheck",
                str(classes), env.paper_api_cp, str(report), str(env.javap)],
               check=False, timeout=900)
    print(proc.stdout.rstrip())

    data = json.loads(report.read_text()) if report.exists() else {}
    total, missing = data.get("total", 0), data.get("missing", [])
    ok = bool(data) and not missing
    FIND.test("Alle Bukkit-Aufrufe in paper-api vorhanden", ok,
              f"{total} Aufrufe" if ok else f"{len(missing)} fehlen")
    if total and total != EXPECTED_API_CALLS:
        Log.warn(f"Sollmarke waren {EXPECTED_API_CALLS} Aufrufe, gezaehlt sind {total} "
                 f"- am Plugin hat sich etwas geaendert")
    return ok


# ---------------------------------------------------------------------------
# 6. Paper-Testserver
# ---------------------------------------------------------------------------

SERVER_PROPERTIES = f"""\
online-mode=false
level-type=flat
level-name=world
view-distance=2
simulation-distance=2
spawn-protection=0
server-port={SERVER_PORT}
max-players=10
motd=CamFly Testserver
enable-command-block=false
sync-chunk-writes=false
allow-nether=true
spawn-monsters=false
"""


def paper_jar_url():
    out = run(["curl", "-sSL", "--fail", "--retry", "4", "--retry-delay", "2", FILL_API]).stdout
    info = json.loads(out)
    dl = info["downloads"]["server:default"]
    return dl["url"], dl["name"]


def server_log(env):
    path = env.server / "server.log"
    return path.read_text(encoding="utf-8", errors="replace") if path.exists() else ""


def console(env, command, pause=0.3):
    """Ein Kommando in die Server-Konsole schicken. Der tail -f auf der FIFO
    leitet es an den Server weiter."""
    fifo = env.server / "console.fifo"
    if not fifo.exists():
        raise RuntimeError("Die Konsolen-FIFO gibt es nicht - laeuft der Server?")
    with open(fifo, "w") as f:
        f.write(command + "\n")
    Log.detail(f"> {command}")
    time.sleep(pause)


def server_running(env):
    pidfile = env.server / "server.pgid"
    if not pidfile.exists():
        return None
    try:
        pgid = int(pidfile.read_text().strip())
        os.killpg(pgid, 0)
        return pgid
    except (ValueError, ProcessLookupError, PermissionError):
        return None


def stop_server(env, quiet=False):
    """Erst freundlich ueber die Konsole, dann die ganze Prozessgruppe.
    Kein pkill mit einem Muster, das in der eigenen Kommandozeile steht -
    das schiesst die eigene Shell ab."""
    pgid = server_running(env)
    if pgid is None:
        return
    if not quiet:
        Log.info("Server wird beendet ...")
    try:
        console(env, "stop", pause=0)
    except Exception:
        pass
    for _ in range(60):
        time.sleep(0.5)
        if server_running(env) is None:
            break
    if server_running(env) is not None:
        try:
            os.killpg(pgid, signal.SIGTERM)
            time.sleep(3)
            os.killpg(pgid, signal.SIGKILL)
        except ProcessLookupError:
            pass
    (env.server / "server.pgid").unlink(missing_ok=True)


def prepare_server_files(env):
    env.server.mkdir(parents=True, exist_ok=True)
    (env.server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (env.server / "server.properties").write_text(SERVER_PROPERTIES, encoding="utf-8")

    plugins = env.server / "plugins"
    plugins.mkdir(exist_ok=True)
    if not env.plugin_jar.exists():
        raise RuntimeError("Kein gebautes Jar in artifacts/ - erst Schritt 'build'")
    shutil.copy2(env.plugin_jar, plugins / "CamFly.jar")

    # Die Konfiguration kommt aus dem Bau, nicht aus dem Quellordner: im Jar
    # sind die Platzhalter schon ersetzt.
    built_config = env.repo / "target" / "classes" / "config.yml"
    source = built_config if built_config.exists() else env.repo / "src" / "main" / "resources" / "config.yml"
    text = source.read_text(encoding="utf-8")
    # Partikel aus. Sonst stirbt jeder Bot in Sichtweite eines Cam-Spielers am
    # Partikel-Paket: minecraft-data 26.1 kennt die 26.2-IDs nicht.
    text, n = re.subn(r"(?m)^(\s*particles-per-tick:\s*).*$", r"\g<1>0", text)
    if n == 0:
        Log.warn("particles-per-tick nicht in der Konfiguration gefunden")
    data_dir = plugins / "CamFly"
    data_dir.mkdir(exist_ok=True)
    (data_dir / "config.yml").write_text(text, encoding="utf-8")
    return text


def step_server(env):
    Log.step("6. Paper-Testserver")
    stop_server(env, quiet=True)

    url, name = paper_jar_url()
    jar = download(url, env.cache / name, f"Paper {MC_VERSION} Build {PAPER_BUILD}")
    prepare_server_files(env)
    shutil.copy2(jar, env.server / "paper.jar")

    log = env.server / "server.log"
    log.unlink(missing_ok=True)
    fifo = env.server / "console.fifo"
    fifo.unlink(missing_ok=True)
    os.mkfifo(fifo)

    started = time.time()
    # tail -f haelt die FIFO offen, sonst bekaeme der Server nach dem ersten
    # Kommando ein EOF auf stdin.
    cmd = (f'tail -f "{fifo}" | "{env.java}" -Xms1G -Xmx2G '
           f'-jar paper.jar nogui > "{log}" 2>&1')
    proc = subprocess.Popen(["bash", "-c", cmd], cwd=str(env.server),
                            start_new_session=True,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    (env.server / "server.pgid").write_text(str(proc.pid))

    Log.info("warte auf den Server ...")
    ok = False
    for _ in range(600):           # bis zu 5 Minuten, meist ~9 Sekunden
        time.sleep(0.5)
        text = server_log(env)
        if "Done (" in text:
            ok = True
            break
        if proc.poll() is not None:
            break
    took = time.time() - started
    if not ok:
        FIND.test("Server gestartet", False, "kein 'Done (' im Log")
        print("\n".join(server_log(env).splitlines()[-30:]))
        return False
    FIND.test("Server gestartet", True, f"{took:.1f}s")

    # Ohne "peaceful" erschlaegt ein Zombie den Bot, und danach verweigert das
    # Plugin /cam wegen der cam-safety-Sperre.
    console(env, "difficulty peaceful")
    console(env, "gamerule doMobSpawning false")
    console(env, "gamerule doDaylightCycle false")
    console(env, "time set day")
    return True


# ---------------------------------------------------------------------------
# 7. Bot (mineflayer)
# ---------------------------------------------------------------------------

BOT_JS = r'''
// Wird vom Python-Skript geschrieben. Nimmt JSON-Zeilen auf stdin entgegen
// und antwortet mit JSON-Zeilen auf stdout.
const MC = process.env.CAMFLY_MC || '26.2';
const FALLBACK = process.env.CAMFLY_FALLBACK || '26.1';
const PROTO = parseInt(process.env.CAMFLY_PROTO || '776', 10);
const NAME = process.env.CAMFLY_BOT || 'CamFlyTester';

// --- Alles vor dem require von mineflayer: minecraft-data hat Paketdaten nur
// --- bis 26.1 (Protokoll 775), der Server spricht 26.2 (776).
const rawData = require('minecraft-data/data.js');
if (!rawData.pc[MC]) rawData.pc[MC] = rawData.pc[FALLBACK];
const md = require('minecraft-data');
const idx = md(MC);
if (idx && idx.version) {
  idx.version.version = PROTO;
  idx.version.minecraftVersion = MC;
}
if (md.supportedVersions && md.supportedVersions.pc && !md.supportedVersions.pc.includes(MC)) {
  md.supportedVersions.pc.push(MC);
}
const protoVersion = require('minecraft-protocol/src/version.js');
if (protoVersion.supportedVersions && !protoVersion.supportedVersions.includes(MC)) {
  protoVersion.supportedVersions.push(MC);
}
// mineflayer/lib/version.js ist schon auf der Platte geflickt - loader.js
// liest den Wert genau einmal beim Laden.
const mineflayer = require('mineflayer');

function out(obj) { process.stdout.write(JSON.stringify(obj) + '\n'); }

const messages = [];
let spawned = false;
let dead = false;

const bot = mineflayer.createBot({
  host: process.env.CAMFLY_HOST || '127.0.0.1',
  port: parseInt(process.env.CAMFLY_PORT || '25565', 10),
  username: NAME,
  auth: 'offline',
  version: MC,
  checkTimeoutInterval: 120 * 1000
});

bot.on('message', (msg) => {
  let text = '';
  try { text = msg.toString(); } catch (e) { text = String(msg); }
  messages.push({ t: Date.now(), text });
});
bot.once('spawn', () => { spawned = true; out({ event: 'spawn' }); });
bot.on('death', () => { dead = true; out({ event: 'death' }); });
bot.on('kicked', (reason) => out({ event: 'kicked', reason: String(reason) }));
bot.on('error', (err) => out({ event: 'error', message: String(err && err.message || err) }));
bot.on('end', (reason) => { out({ event: 'end', reason: String(reason) }); });

function waitFor(check, timeout) {
  return new Promise((resolve) => {
    const started = Date.now();
    const tick = () => {
      const hit = check();
      if (hit) return resolve(hit);
      if (Date.now() - started > timeout) return resolve(null);
      setTimeout(tick, 100);
    };
    tick();
  });
}

const POS_RE = /\[\s*(-?[\d.]+)d,\s*(-?[\d.]+)d,\s*(-?[\d.]+)d\s*\]/;
const DATA_RE = /entity data:\s*(-?[\d.]+)/;

async function handle(cmd) {
  switch (cmd.op) {
    case 'wait_spawn': {
      const hit = await waitFor(() => spawned, cmd.timeout || 60000);
      return { spawned: !!hit };
    }
    case 'chat':
      bot.chat(cmd.text);
      return { sent: cmd.text };
    case 'messages':
      return { messages: messages.slice(cmd.since || 0), count: messages.length };
    case 'mark':
      return { count: messages.length };
    case 'wait_message': {
      const re = new RegExp(cmd.pattern, 'i');
      const since = cmd.since || 0;
      const hit = await waitFor(() => {
        for (let i = since; i < messages.length; i++) {
          if (re.test(messages[i].text)) return messages[i];
        }
        return null;
      }, cmd.timeout || 5000);
      return { matched: hit, count: messages.length };
    }
    case 'pos': {
      const p = bot.entity && bot.entity.position;
      return { pos: p ? [p.x, p.y, p.z] : null };
    }
    case 'server_pos': {
      // bot.entity.position ist die Sicht des CLIENTS und laeuft optimistisch
      // voraus. Die Wahrheit steht in den Entitaetsdaten.
      const since = messages.length;
      bot.chat('/data get entity @s Pos');
      const hit = await waitFor(() => {
        for (let i = since; i < messages.length; i++) {
          const m = POS_RE.exec(messages[i].text);
          if (m) return m;
        }
        return null;
      }, cmd.timeout || 5000);
      return hit ? { pos: [parseFloat(hit[1]), parseFloat(hit[2]), parseFloat(hit[3])] }
                 : { pos: null };
    }
    case 'server_data': {
      // Eine einzelne Zahl aus den Entitaetsdaten, serverseitig gelesen.
      // Vom Hunger kennt der Client nur den Balken; Saettigung und
      // Erschoepfung stehen allein auf dem Server.
      const since = messages.length;
      bot.chat('/data get entity @s ' + cmd.path);
      const hit = await waitFor(() => {
        for (let i = since; i < messages.length; i++) {
          const m = DATA_RE.exec(messages[i].text);
          if (m) return m;
        }
        return null;
      }, cmd.timeout || 5000);
      return { value: hit ? parseFloat(hit[1]) : null };
    }
    case 'state':
      return {
        gameMode: bot.game && bot.game.gameMode,
        health: bot.health,
        food: bot.food,
        dead,
        pos: bot.entity ? [bot.entity.position.x, bot.entity.position.y, bot.entity.position.z] : null
      };
    case 'entities': {
      const radius = cmd.radius || 8;
      const me = bot.entity && bot.entity.position;
      const list = [];
      for (const id of Object.keys(bot.entities)) {
        const e = bot.entities[id];
        if (!e || e === bot.entity || !e.position) continue;
        if (me && e.position.distanceTo(me) > radius) continue;
        list.push({
          id: e.id,
          type: e.name || (e.entityType !== undefined ? String(e.entityType) : '?'),
          kind: e.type,
          username: e.username || null,
          displayName: e.displayName ? String(e.displayName) : null,
          pos: [e.position.x, e.position.y, e.position.z]
        });
      }
      return { entities: list };
    }
    case 'fly': {
      // Harter Timeout drumherum, sonst haengt es an der Sperre des Plugins fest.
      const Vec3 = require('vec3');
      const p = bot.entity.position;
      const target = new Vec3(
        cmd.x !== undefined ? cmd.x : p.x + (cmd.dx || 0),
        cmd.y !== undefined ? cmd.y : p.y + (cmd.dy || 0),
        cmd.z !== undefined ? cmd.z : p.z + (cmd.dz || 0));
      try { bot.creative.startFlying(); } catch (e) { /* egal */ }
      const done = await Promise.race([
        bot.creative.flyTo(target).then(() => 'angekommen').catch((e) => 'abgebrochen: ' + e.message),
        new Promise((r) => setTimeout(() => r('Zeit abgelaufen'), cmd.timeout || 12000))
      ]);
      const now = bot.entity.position;
      return { result: done, target: [target.x, target.y, target.z],
               pos: [now.x, now.y, now.z] };
    }
    case 'stop_fly':
      try { bot.creative.stopFlying(); } catch (e) { /* egal */ }
      return { ok: true };
    case 'quit':
      setTimeout(() => process.exit(0), 200);
      try { bot.quit(); } catch (e) { /* egal */ }
      return { bye: true };
    default:
      return { error: 'unbekannter Befehl: ' + cmd.op };
  }
}

const readline = require('readline');
readline.createInterface({ input: process.stdin }).on('line', async (line) => {
  if (!line.trim()) return;
  let cmd;
  try { cmd = JSON.parse(line); } catch (e) { return out({ event: 'badinput', line }); }
  try {
    const result = await handle(cmd);
    out({ id: cmd.id, ok: true, result });
  } catch (err) {
    out({ id: cmd.id, ok: false, error: String(err && err.stack || err) });
  }
});
'''


def patch_mineflayer(env):
    """mineflayer/lib/version.js kennt 26.2 nicht. Der Wert wird beim Laden
    von loader.js genau einmal gelesen, deshalb muss die Datei selbst weg."""
    version_js = env.bot / "node_modules" / "mineflayer" / "lib" / "version.js"
    if not version_js.exists():
        FIND.problem("mineflayer/lib/version.js nicht gefunden - Bot laeuft vielleicht nicht")
        return False
    text = version_js.read_text(encoding="utf-8")
    if f"'{MC_VERSION}'" in text:
        return True
    # In neueren Fassungen wird latestSupportedVersion aus der Liste
    # testedVersions abgeleitet - dann muss die Version dort hinein.
    patched, n = re.subn(r"(const testedVersions = \[)(.*?)(\])",
                         rf"\g<1>\g<2>, '{MC_VERSION}'\g<3>", text, flags=re.S)
    if n == 0:
        patched, n = re.subn(r"(latestSupportedVersion\s*[:=]\s*)['\"][\d.]+['\"]",
                             rf"\g<1>'{MC_VERSION}'", text)
    if n == 0:
        FIND.problem("In mineflayer/lib/version.js ist weder testedVersions noch "
                     "latestSupportedVersion zu finden - der Bot kennt 26.2 nicht")
        return False
    if patched != text:
        version_js.write_text(patched, encoding="utf-8")
        Log.detail(f"mineflayer/lib/version.js auf {MC_VERSION} gesetzt")
    return True


def step_bot(env):
    Log.step("7. Bot (mineflayer)")
    env.bot.mkdir(parents=True, exist_ok=True)
    if not (env.bot / "node_modules" / "mineflayer").exists():
        (env.bot / "package.json").write_text(
            json.dumps({"name": "camfly-bot", "private": True, "version": "1.0.0"}, indent=2),
            encoding="utf-8")
        Log.info("npm install mineflayer ...")
        run(["npm", "install", "--no-audit", "--no-fund", "mineflayer"],
            cwd=env.bot, timeout=1200)
    else:
        Log.detail("mineflayer liegt schon im Arbeitsordner")
    ok = patch_mineflayer(env)
    (env.bot / "bot.js").write_text(BOT_JS, encoding="utf-8")
    FIND.test("mineflayer bereit", ok, f"{MC_VERSION} / Protokoll {PROTOCOL_VERSION}")
    return ok


# ---------------------------------------------------------------------------
# 8. Tests im laufenden Spiel
# ---------------------------------------------------------------------------

class BotClient:
    """Treibt bot.js ueber stdin/stdout. Jede Anfrage bekommt eine Nummer,
    die Antwort traegt dieselbe."""

    def __init__(self, env, name=BOT_NAME):
        self.env = env
        self.name = name
        self.proc = None
        self.next_id = 1
        self.replies = {}
        self.events = []
        self._thread = None

    def start(self):
        import threading
        botenv = dict(os.environ)
        botenv.update({
            "CAMFLY_MC": MC_VERSION, "CAMFLY_FALLBACK": FALLBACK_DATA_VERSION,
            "CAMFLY_PROTO": str(PROTOCOL_VERSION), "CAMFLY_BOT": self.name,
            "CAMFLY_HOST": SERVER_HOST, "CAMFLY_PORT": str(SERVER_PORT)})
        self.proc = subprocess.Popen(
            ["node", "bot.js"], cwd=str(self.env.bot), env=botenv,
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, text=True, bufsize=1)

        def reader():
            for line in self.proc.stdout:
                line = line.strip()
                if not line:
                    continue
                try:
                    msg = json.loads(line)
                except json.JSONDecodeError:
                    self.events.append({"event": "stdout", "line": line})
                    continue
                if "id" in msg:
                    self.replies[msg["id"]] = msg
                else:
                    self.events.append(msg)

        self._thread = threading.Thread(target=reader, daemon=True)
        self._thread.start()
        return self

    def call(self, op, wait=30, **kw):
        if self.proc is None or self.proc.poll() is not None:
            raise RuntimeError("Der Bot laeuft nicht mehr")
        rid = self.next_id
        self.next_id += 1
        payload = dict(kw)
        payload.update({"op": op, "id": rid})
        self.proc.stdin.write(json.dumps(payload) + "\n")
        self.proc.stdin.flush()
        deadline = time.time() + wait
        while time.time() < deadline:
            if rid in self.replies:
                msg = self.replies.pop(rid)
                if not msg.get("ok"):
                    raise RuntimeError(f"Bot-Befehl {op} fehlgeschlagen: {msg.get('error')}")
                return msg.get("result", {})
            if self.proc.poll() is not None:
                raise RuntimeError(f"Der Bot ist gestorben, waehrend {op} lief")
            time.sleep(0.05)
        raise TimeoutError(f"Keine Antwort auf {op}")

    def stop(self):
        if self.proc is None:
            return
        try:
            if self.proc.poll() is None:
                self.call("quit", wait=5)
        except Exception:
            pass
        time.sleep(0.5)
        if self.proc.poll() is None:
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()

    # --- Bequemlichkeiten ---
    def mark(self):
        return self.call("mark")["count"]

    def chat(self, text):
        return self.call("chat", text=text)

    def expect(self, pattern, since, timeout=8000):
        """Wartet auf eine Chatzeile. timeout ist Millisekunden fuer den Bot."""
        return self.call("wait_message", wait=timeout / 1000 + 10,
                         pattern=pattern, since=since, timeout=timeout).get("matched")

    def server_pos(self):
        return self.call("server_pos", wait=20, timeout=8000).get("pos")

    def server_data(self, path):
        """Ein Wert aus den Entitaetsdaten des Bots, vom Server gelesen."""
        return self.call("server_data", wait=20, path=path, timeout=8000).get("value")

    def hunger(self):
        """Balken, Saettigung und Erschoepfung, so wie der Server sie fuehrt."""
        return (self.server_data("foodLevel"),
                self.server_data("foodSaturationLevel"),
                self.server_data("foodExhaustionLevel"))


def strip_colors(text):
    return re.sub(r"[§&][0-9a-fk-or]", "", text or "")


def log_problems(env):
    """Alles im Server-Log, was nach einem Fehler des Plugins aussieht."""
    bad = re.compile(
        r"NoSuchMethodError|NoClassDefFoundError|IncompatibleClassChangeError|"
        r"NoSuchFieldError|AbstractMethodError|Exception in thread|"
        r"Could not pass event|Error occurred while|"
        r"java\.lang\.\w*(Exception|Error)")
    hits = []
    for line in server_log(env).splitlines():
        if bad.search(line):
            hits.append(line.strip())
    return hits


def hunger_checks(env, bot):
    """Der Hungerbalken im Cam-Modus.

    Der Spieler bleibt im Cam-Modus im Abenteuermodus, und dort laeuft der
    Hunger des Servers weiter. Das Plugin haelt ihn an: keine Erschoepfung,
    kein Abzug am Balken, und beim Aussteigen steht alles wieder so da wie
    beim Einsteigen.

    Gemessen wird serverseitig ueber /data. Der Bot braucht dafuer op - das
    hat der Testlauf vorher schon erledigt.
    """
    # In "peaceful" nimmt der Server vom Balken ohnehin nichts weg, dort
    # zeigte sich der Fehler gar nicht erst. Fuer diese Pruefungen geht die
    # Schwierigkeit hoch. Monster kommen deswegen keine: das Spawnen ist
    # ueber server.properties und die Spielregel abgeschaltet.
    console(env, "difficulty easy")
    try:
        since = bot.mark()
        bot.chat("/cam")
        started = bot.expect("Camera mode activated|Cam mode activated", since, 8000)
        if not FIND.test("/cam startet fuer den Hungertest", bool(started),
                         "" if started else "keine Bestaetigung im Chat"):
            return
        time.sleep(1)

        before = bot.hunger()
        if not FIND.test("Hungerwerte sind serverseitig lesbar",
                         all(v is not None for v in before),
                         f"Balken {before[0]}, Saettigung {before[1]}, "
                         f"Erschoepfung {before[2]}"):
            bot.chat("/cam")
            time.sleep(1)
            return

        # Der Hungereffekt fuellt die Erschoepfung schneller als alles andere,
        # der Flug dazu ist die Bewegung, die sie im Spiel fuellt. Fuenf
        # Sekunden auf der hoechsten Stufe sind gut dreissig Abzuege - genug,
        # um erst die Saettigung und dann den halben Balken zu verbrauchen.
        bot.chat("/effect give @s minecraft:hunger 5 255")
        bot.call("fly", wait=30, dy=4, dx=4, timeout=15000)
        time.sleep(6)
        during = bot.hunger()
        FIND.test("Im Cam-Modus faellt der Hungerbalken nicht",
                  during[0] == before[0], f"{before[0]} -> {during[0]}")
        FIND.test("Im Cam-Modus zehrt auch nichts an Saettigung und Erschoepfung",
                  during[1:] == before[1:],
                  f"Saettigung {before[1]} -> {during[1]}, "
                  f"Erschoepfung {before[2]} -> {during[2]}")

        # Erst den Effekt weg, sonst zehrt er nach dem Aussteigen sofort
        # weiter und die Probe danach misst ihn statt des Plugins.
        bot.chat("/effect clear @s minecraft:hunger")
        time.sleep(1)
        since = bot.mark()
        bot.chat("/cam")
        bot.expect("Camera mode ended|Cam mode ended", since, 8000)
        time.sleep(1)
        after = bot.hunger()
        FIND.test("Nach dem Cam-Modus ist der Hunger der von vorher",
                  after == before, f"{before} -> {after}")

        # Gegenprobe: derselbe Effekt ohne Cam-Modus zehrt sehr wohl. Ohne
        # sie sagte dieser Abschnitt nur, dass sich nichts bewegt hat - und
        # das tut er auch, wenn gar nichts zehrt. Sie steht am Ende, weil der
        # Balken danach unten ist: verhungert der Bot, laesst die
        # cam-safety-Sperre danach kein /cam mehr zu.
        plain_before = bot.server_data("foodLevel")
        bot.chat("/effect give @s minecraft:hunger 5 255")
        time.sleep(6)
        plain_after = bot.server_data("foodLevel")
        FIND.test("Gegenprobe: ohne Cam-Modus faellt der Balken sehr wohl",
                  None not in (plain_before, plain_after) and plain_after < plain_before,
                  f"{plain_before} -> {plain_after}")
    finally:
        # Die Schwierigkeit muss auch dann zurueck, wenn der Bot unterwegs
        # abgehaengt ist - sonst steht der Server fuer alles Weitere falsch da.
        try:
            bot.chat("/effect clear @s minecraft:hunger")
        except Exception:
            pass
        console(env, "difficulty peaceful")


def heal_probe(bot, label):
    """Eine Heilprobe: verletzen, draussen heilen lassen, drinnen nicht.

    Der Schaden richtet sich nach dem, was der Bot noch hat - ein fester Wert
    wuerde ihn erschlagen, sobald eine Probe auf die andere folgt.
    """
    now = bot.server_data("Health")
    if not FIND.test(f"Leben des Bots lesbar ({label})", now is not None, str(now)):
        return
    if now > 3:
        bot.chat(f"/damage @s {int(now) - 2}")
        time.sleep(0.5)
    hurt = bot.server_data("Health")
    if not FIND.test(f"Der Bot laesst sich verletzen ({label})",
                     hurt is not None and hurt < 20.0, f"Leben {hurt}"):
        return

    # Die Gegenprobe steht vorn und laeuft dabei die cam-safety-Sperre ab:
    # fuenf Sekunden nach dem letzten Schaden laesst das Plugin /cam wieder zu.
    time.sleep(4)
    healed = bot.server_data("Health")
    FIND.test(f"Ohne Cam-Modus heilt der Spieler nach ({label})",
              None not in (hurt, healed) and healed > hurt, f"{hurt} -> {healed}")
    time.sleep(2)

    since = bot.mark()
    bot.chat("/cam")
    started = bot.expect("Camera mode activated|Cam mode activated", since, 8000)
    if not FIND.test(f"/cam startet fuer den Heiltest ({label})", bool(started),
                     "" if started else "keine Bestaetigung im Chat"):
        return
    time.sleep(1)
    before = bot.server_data("Health")
    FIND.test(f"Der Bot geht verletzt in den Cam-Modus ({label})",
              before is not None and before < 20.0, f"Leben {before}")
    # Sechs Sekunden: geheilt wuerde in dieser Zeit in jedem Fall, ob nun
    # schnell aus der Saettigung oder langsam aus dem vollen Balken. Geprueft
    # wird auf Gleichstand, ein einziges halbes Herz reicht also zum Durchfall.
    time.sleep(6)
    after = bot.server_data("Health")
    FIND.test(f"Im Cam-Modus heilt der Spieler nicht nach ({label})",
              None not in (before, after) and after == before, f"{before} -> {after}")

    since = bot.mark()
    bot.chat("/cam")
    bot.expect("Camera mode ended|Cam mode ended", since, 8000)
    time.sleep(1)


def heal_checks(env, bot):
    """Die Heilung im Cam-Modus.

    Seit der Hunger im Cam-Modus steht, waere die Regeneration dort umsonst zu
    haben: draussen bezahlt sie Saettigung und am Ende den Balken. Das Plugin
    haelt sie deshalb an, solange der Spieler zuschaut.

    Zwei Durchgaenge, weil der Server zwei Wege kennt, auf denen von selbst
    Leben nachwaechst, und das Plugin beide abfangen muss:

    * "peaceful" heilt jede Sekunde ein halbes Herz, gleichmaessig und ohne
      dass ihm die Saettigung ausgeht - der Grund heisst dort REGEN.
    * "easy" ist der Fall des echten Servers: aus der Saettigung heraus geht
      es schnell, das ist SATIATED, und wenn sie leer ist, langsam weiter
      ueber den vollen Balken.
    """
    heal_probe(bot, "peaceful")

    # Satt in den zweiten Durchgang: ohne vollen Balken regeneriert "easy"
    # gar nicht erst. Der Saettigungseffekt fuellt Balken und Saettigung auf
    # einen Schlag - er greift nur, solange der Balken nicht voll ist.
    bot.chat("/effect give @s minecraft:saturation 1 255")
    time.sleep(2)
    console(env, "difficulty easy")
    try:
        food = bot.server_data("foodLevel")
        sat = bot.server_data("foodSaturationLevel")
        if FIND.test("Der Bot geht satt in den zweiten Heiltest",
                     food == 20 and sat is not None and sat > 0,
                     f"Balken {food}, Saettigung {sat}"):
            heal_probe(bot, "easy")
    finally:
        console(env, "difficulty peaceful")


def step_tests(env):
    Log.step("8. Tests im laufenden Spiel")
    if server_running(env) is None:
        FIND.test("Tests", False, "Der Server laeuft nicht")
        return False

    # --- Plugin ueberhaupt geladen? ---
    log = server_log(env)
    enabled = "Enabling CamFly" in log or re.search(r"CamFly.*Enabl", log) is not None
    broken = "Could not load 'plugins/CamFly.jar'" in log or "Could not load plugin" in log
    FIND.test("Plugin geladen", enabled and not broken,
              "CamFly ist aktiviert" if enabled and not broken else "siehe server.log")
    plugin_warnings = [l.strip() for l in log.splitlines()
                       if "CamFly" in l and ("WARN" in l or "ERROR" in l)]
    for w in plugin_warnings[:15]:
        FIND.problem(f"Server-Log: {w}")

    bot = BotClient(env)
    passed = True
    try:
        bot.start()
        spawned = bot.call("wait_spawn", wait=90, timeout=75000).get("spawned")
        if not FIND.test("Bot verbindet sich", spawned, BOT_NAME):
            for e in bot.events[-10:]:
                Log.detail(str(e))
            return False
        time.sleep(2)

        # --- /cam ohne op: camplugin.use ist Standardrecht ---
        since = bot.mark()
        before_entities = {e["id"] for e in bot.call("entities", radius=6)["entities"]}
        bot.chat("/cam")
        hit = bot.expect("Camera mode activated|Cam mode activated", since, 8000)
        FIND.test("/cam startet ohne op", bool(hit),
                  strip_colors(hit["text"]) if hit else "keine Bestaetigung im Chat")
        time.sleep(1.5)

        after = bot.call("entities", radius=6)["entities"]
        new = [e for e in after if e["id"] not in before_entities]
        FIND.test("Koerper wird gesetzt", bool(new),
                  ", ".join(sorted({e["type"] or "?" for e in new})) if new else "keine neue Entitaet")

        state = bot.call("state")
        Log.detail(f"Spielmodus im Cam-Modus: {state.get('gameMode')}")

        since = bot.mark()
        bot.chat("/cam")
        hit = bot.expect("Camera mode ended|Cam mode ended", since, 8000)
        FIND.test("/cam beendet sich wieder", bool(hit),
                  strip_colors(hit["text"]) if hit else "keine Bestaetigung im Chat")
        time.sleep(1)
        rest = [e for e in bot.call("entities", radius=6)["entities"]
                if e["id"] not in before_entities]
        FIND.test("Koerper wird wieder eingesammelt", not rest,
                  "" if not rest else f"{len(rest)} Entitaeten bleiben stehen")

        # --- ab hier mit op: /data und /fillbiome brauchen es ---
        console(env, f"op {BOT_NAME}")
        time.sleep(1.5)
        start_pos = bot.server_pos()
        if not FIND.test("Serverseitige Position lesbar", start_pos is not None,
                         str(start_pos)):
            return False

        # --- Fliegen im Cam-Modus ---
        since = bot.mark()
        bot.chat("/cam")
        bot.expect("Camera mode activated|Cam mode activated",
                 since=since, timeout=8000)
        time.sleep(1)
        fly = bot.call("fly", wait=30, dy=6, dx=4, timeout=15000)
        Log.detail(f"Flug: {fly.get('result')}")
        time.sleep(1)
        flown = bot.server_pos()
        moved = (flown is not None and start_pos is not None
                 and max(abs(a - b) for a, b in zip(flown, start_pos)) > 2.0)
        FIND.test("Fliegen im Cam-Modus bewegt den Spieler", moved,
                  f"{start_pos} -> {flown}")

        # --- Zurueck zum Koerper ---
        since = bot.mark()
        bot.chat("/cam")
        bot.expect("Camera mode ended|Cam mode ended", since, 8000)
        time.sleep(1.5)
        back = bot.server_pos()
        near = (back is not None and start_pos is not None
                and max(abs(a - b) for a, b in zip(back, start_pos)) < 2.5)
        FIND.test("Nach dem Cam-Modus steht der Spieler wieder am Koerper", near,
                  f"{start_pos} -> {back}")

        # --- Reload, von der Konsole und vom Spieler ---
        console(env, "cam reload", pause=2)
        FIND.test("/cam reload von der Konsole",
                  "reload successful" in strip_colors(server_log(env)).lower(),
                  "")
        since = bot.mark()
        bot.chat("/cam reload")
        hit = bot.expect("reload successful|Plugin is restarting", since, 8000)
        FIND.test("/cam reload vom Spieler (op)", bool(hit),
                  strip_colors(hit["text"]) if hit else "keine Antwort im Chat")

        # --- Verbotenes Biom: cam-area ---
        pos = bot.server_pos() or start_pos
        x, y, z = (int(pos[0]), int(pos[1]), int(pos[2]))
        # /fillbiome: hoechstens 32768 Bloecke, und y muss in die Welt passen.
        # Der Boden der Flachwelt liegt bei -64, "~-8" geht dort schief.
        y1, y2 = max(-64, y - 4), min(320, y + 4)
        area = f"{x-8} {y1} {z-8} {x+8} {y2} {z+8}"
        bot.chat(f"/fillbiome {area} minecraft:lush_caves")
        time.sleep(2)
        since = bot.mark()
        bot.chat("/cam")
        hit = bot.expect("cannot start cam mode|cannot go any further", since, 8000)
        FIND.test("Verbotenes Biom sperrt /cam", bool(hit),
                  strip_colors(hit["text"]) if hit else "der Cam-Modus startete trotzdem")
        if not hit:
            # aufraeumen, falls er doch gestartet ist
            bot.chat("/cam")
            time.sleep(1)
        bot.chat(f"/fillbiome {area} minecraft:plains")
        time.sleep(2)
        since = bot.mark()
        bot.chat("/cam")
        hit = bot.expect("Camera mode activated|Cam mode activated", since, 8000)
        FIND.test("Nach der Rueckkehr ins erlaubte Biom geht /cam wieder", bool(hit), "")
        if hit:
            bot.chat("/cam")
            time.sleep(1)

        # --- Hunger im Cam-Modus ---
        hunger_checks(env, bot)

        # --- Heilung im Cam-Modus ---
        heal_checks(env, bot)

    except Exception as exc:
        FIND.test("Testlauf", False, f"{type(exc).__name__}: {exc}")
        passed = False
    finally:
        for e in bot.events:
            if e.get("event") in ("kicked", "error", "death"):
                FIND.problem(f"Bot-Ereignis: {e}")
        bot.stop()

    # --- Das Log zum Schluss ---
    hits = log_problems(env)
    FIND.test("Server-Log ohne Fehler des Plugins", not hits,
              "" if not hits else f"{len(hits)} verdaechtige Zeilen")
    for h in hits[:25]:
        Log.detail(h)
    return passed and not hits


# ---------------------------------------------------------------------------
# 9. Aufraeumen und Zusammenfassung
# ---------------------------------------------------------------------------

def restore_target(env):
    """target/ ist im Repo eingecheckt. Nach jedem mvn package muss der Ordner
    wieder auf den Stand von HEAD, damit nur src/ im Commit landet."""
    if not (env.repo / ".git").exists() or not (env.repo / "target").exists():
        return
    run(["git", "restore", "--source=HEAD", "--worktree", "target/"],
        cwd=env.repo, check=False)
    run(["git", "clean", "-fdq", "target/"], cwd=env.repo, check=False)
    dirty = run(["git", "status", "--porcelain", "target/"], cwd=env.repo,
                check=False).stdout.strip()
    if dirty:
        FIND.problem("target/ ist nach dem Zuruecksetzen noch veraendert:\n" + dirty)
    else:
        Log.detail("target/ steht wieder auf HEAD")


def summary(env, results):
    print(f"\n{Log.BLUE}=== Zusammenfassung ==={Log.OFF}")
    ok = [t for t in FIND.tests if t["ok"]]
    print(f"  {len(ok)} von {len(FIND.tests)} Pruefungen bestanden")
    for t in FIND.failed:
        print(f"  {Log.RED}durchgefallen{Log.OFF}  {t['name']}"
              f"{(' - ' + t['detail']) if t['detail'] else ''}")
    if FIND.problems:
        print(f"\n  {Log.YELLOW}Das solltest du dir ansehen:{Log.OFF}")
        for p in FIND.problems:
            print(f"    - {p}")
    print(f"\n  Arbeitsordner: {env.work}")
    print(f"  Server-Log:    {env.server / 'server.log'}")
    print(f"  ApiCheck:      {env.apicheck / 'report.json'}")
    report = env.work / "ergebnis.json"
    report.write_text(json.dumps(
        {"tests": FIND.tests, "problems": FIND.problems, "steps": results},
        indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"  Ergebnis:      {report}")


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="CamFly-Testumgebung aufbauen und Tests fahren",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="Schritte: " + ", ".join(STEPS))
    here = Path(__file__).resolve().parent.parent
    parser.add_argument("--repo", default=str(here), help="Ordner des Plugins")
    parser.add_argument("--workdir", default=str(Path.home() / "camfly-testenv"),
                        help="Wohin alles Heruntergeladene kommt")
    parser.add_argument("--steps", default=",".join(STEPS),
                        help="Nur diese Schritte, mit Komma getrennt")
    parser.add_argument("--skip", default="", help="Diese Schritte auslassen")
    parser.add_argument("--keep-running", action="store_true",
                        help="Server nach den Tests weiterlaufen lassen")
    parser.add_argument("--no-restore", action="store_true",
                        help="target/ nicht auf HEAD zuruecksetzen")
    parser.add_argument("--stop", action="store_true",
                        help="Nur einen laufenden Testserver beenden")
    args = parser.parse_args(argv)

    env = Env(args.repo, args.workdir)

    if args.stop:
        stop_server(env)
        print("Server beendet.")
        return 0

    wanted = [s.strip() for s in args.steps.split(",") if s.strip()]
    skip = {s.strip() for s in args.skip.split(",") if s.strip()}
    unknown = [s for s in wanted if s not in STEPS]
    if unknown:
        parser.error(f"Unbekannte Schritte: {', '.join(unknown)}")
    plan = [s for s in STEPS if s in wanted and s not in skip]

    print(f"{Log.BLUE}CamFly-Testumgebung{Log.OFF}")
    print(f"  Plugin:        {env.repo}")
    print(f"  Arbeitsordner: {env.work}")
    print(f"  Schritte:      {', '.join(plan)}")

    results = {}
    try:
        for name in plan:
            try:
                results[name] = bool(globals()["step_" + name](env))
            except Exception as exc:
                results[name] = False
                FIND.test(f"Schritt {name}", False, f"{type(exc).__name__}: {exc}")
                if name in ("jdk", "build", "paperapi"):
                    break   # ohne die geht nichts weiter
    finally:
        if not args.keep_running and "server" in plan:
            stop_server(env)
        if not args.no_restore and "build" in plan:
            restore_target(env)
        summary(env, results)

    if args.keep_running and "server" in plan:
        print(f"\n  Der Server laeuft weiter. Konsole: "
              f"echo \"say hallo\" > {env.server / 'console.fifo'}")
        print(f"  Beenden mit: python3 {Path(__file__).name} --stop")

    return 1 if FIND.failed else 0


if __name__ == "__main__":
    sys.exit(main())
