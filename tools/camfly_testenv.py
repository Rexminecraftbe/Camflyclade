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
# Dieser Pruefer zaehlt zurzeit 473 Methoden- und Feldzugriffe. Alle 473 gibt
# es auch in paper-api. Der Hinweis steht also bei jedem Lauf da.
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
allow-flight=true
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

    # Die Konfiguration kommt aus dem Quellordner. Aus target/classes zu lesen
    # waere eine Falle: restore_target setzt den Ordner nach jedem Lauf wieder
    # auf HEAD, ein Lauf ohne den Schritt build faende dort also die
    # eingecheckte alte Fassung und pruefte das Plugin gegen eine
    # Konfiguration, in der die neuen Schluessel gar nicht stehen. Zu holen
    # gibt es dort ohnehin nichts: Platzhalter ersetzt Maven in plugin.yml,
    # config.yml hat keine.
    source = env.repo / "src" / "main" / "resources" / "config.yml"
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

// Die naechste Entitaet einer Art. Der Kamera-Koerper des Bots ist auch eine
// Entitaet und kann dieselbe Art haben wie das Testobjekt - genommen wird
// deshalb die naechste, und der Test stellt den Bot direkt neben sein Ziel.
function pickEntity(cmd) {
  const me = bot.entity && bot.entity.position;
  let best = null;
  for (const id of Object.keys(bot.entities)) {
    const e = bot.entities[id];
    if (!e || e === bot.entity || !e.position) continue;
    if (cmd.type && e.name !== cmd.type) continue;
    if (!me) continue;
    const weg = e.position.distanceTo(me);
    if (weg > (cmd.radius || 6)) continue;
    if (!best || weg < best.position.distanceTo(me)) best = e;
  }
  return best;
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
    case 'activate_block': {
      // Rechtsklick auf einen Block. Was schiefgeht, kommt als Ergebnis
      // zurueck und nicht als Ausnahme: Ob der Klick durchgeht, ist genau die
      // Frage des Tests - er soll sie beantworten und nicht daran sterben.
      const Vec3 = require('vec3');
      const at = new Vec3(cmd.x, cmd.y, cmd.z);
      const block = bot.blockAt(at);
      if (!block) return { done: false, reason: 'kein Block bekannt' };
      try {
        await bot.lookAt(at.offset(0.5, 0.5, 0.5), true);
        await Promise.race([
          bot.activateBlock(block),
          new Promise((_, rej) => setTimeout(
            () => rej(new Error('Zeit abgelaufen')), cmd.timeout || 5000))
        ]);
        return { done: true, block: block.name };
      } catch (e) {
        return { done: false, block: block.name, reason: String(e && e.message || e) };
      }
    }
    case 'dig_block': {
      const Vec3 = require('vec3');
      const at = new Vec3(cmd.x, cmd.y, cmd.z);
      const block = bot.blockAt(at);
      if (!block) return { done: false, reason: 'kein Block bekannt' };
      try {
        await bot.lookAt(at.offset(0.5, 0.5, 0.5), true);
        const fertig = await Promise.race([
          bot.dig(block).then(() => true).catch(() => false),
          new Promise((r) => setTimeout(() => r(false), cmd.timeout || 8000))
        ]);
        try { bot.stopDigging(); } catch (e) { /* egal */ }
        return { done: !!fertig, block: block.name };
      } catch (e) {
        return { done: false, block: block.name, reason: String(e && e.message || e) };
      }
    }
    case 'activate_entity': {
      // Ein Rechtsklick geht beim echten Client zweimal hinaus: erst die
      // "interact at"-Fassung mit dem Trefferpunkt, dann die schlichte. Welche
      // von beiden wirkt, haengt an der Entitaet - der Ruestungsstaender
      // haengt an der ersten, das Boot an der zweiten -, also werden hier
      // beide geschickt. 'aim' ist die Hoehe des Treffers ueber ihren Fuessen;
      // am Ruestungsstaender entscheidet sie, welches Teil abgenommen wird.
      //
      // Geschrieben werden die Pakete selbst und nicht ueber activateEntity
      // und activateEntityAt: Die drehen den Kopf weich (lookAt ohne force)
      // und warten dabei auf den Physik-Tick, und dieses Warten hat den Bot
      // schon einmal haengen lassen. Hier wird einmal hart hingesehen und
      // dann geschrieben - der Inhalt der Pakete ist derselbe.
      const Vec3 = require('vec3');
      const e = pickEntity(cmd);
      if (!e) return { done: false, reason: 'keine solche Entitaet in der Naehe' };
      const hoehe = cmd.aim === undefined ? 0.5 : cmd.aim;
      try {
        await Promise.race([
          bot.lookAt(e.position.offset(0, hoehe, 0), true),
          new Promise((r) => setTimeout(r, 2000))
        ]);
        bot._client.write('use_entity', {
          target: e.id, mouse: 2, sneaking: false, hand: 0,
          x: 0, y: hoehe, z: 0, location: new Vec3(0, hoehe, 0)
        });
        await new Promise((r) => setTimeout(r, 200));
        bot._client.write('use_entity', {
          target: e.id, mouse: 0, sneaking: false, hand: 0,
          location: new Vec3(0, 0, 0)
        });
        await new Promise((r) => setTimeout(r, 100));
        return { done: true, id: e.id, type: e.name };
      } catch (err) {
        return { done: false, id: e.id, type: e.name,
                 reason: String(err && err.message || err) };
      }
    }
    case 'window':
      return { open: !!bot.currentWindow,
               title: bot.currentWindow ? String(bot.currentWindow.title || '') : null };
    case 'close_window':
      try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow); } catch (e) { /* egal */ }
      return { open: !!bot.currentWindow };
    case 'inventory': {
      const items = bot.inventory ? bot.inventory.items() : [];
      return { count: items.length, names: items.map((i) => i.name) };
    }
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


# Ein geworfener Trank, den /summon einen Block ueber dem Ziel absetzt: er
# faellt, zerschellt und wirkt vier Bloecke weit. Der Splash-Trank tut es auf
# einen Schlag, der verweilende ueber die Wolke, die er liegen laesst und die
# etwa jede Sekunde neu fragt, wer in ihr steht. Genommen wird Langsamkeit:
# sie tut niemandem weh und legt damit die cam-safety-Sperre nicht an, die
# jeder Schaden ausloesen wuerde.
SLOWNESS = 'active_effects[{id:"minecraft:slowness"}].duration'


def replace_option(text, key, value, section=None):
    """Den Wert eines Schluessels in der Konfiguration ersetzen.

    Ohne section wird die ganze Datei durchsucht, mit section nur der
    Abschnitt dieses Namens auf der obersten Ebene. Das braucht es, wo
    derselbe Schluesselname zweimal vorkommt: nether steht unter portals und
    noch einmal unter cam-area.dimensions.

    Gibt den neuen Text und die Zahl der Treffer zurueck.
    """
    pattern = rf"(?m)^(\s*{re.escape(key)}:\s*).*$"
    if section is None:
        return re.subn(pattern, rf"\g<1>{value}", text)
    head = re.search(rf"(?m)^{re.escape(section)}:.*$", text)
    if head is None:
        return text, 0
    rest = text[head.end():]
    # Der Abschnitt reicht bis zur naechsten Zeile, die ganz links anfaengt.
    nxt = re.search(r"(?m)^\S", rest)
    cut = nxt.start() if nxt else len(rest)
    block, n = re.subn(pattern, rf"\g<1>{value}", rest[:cut])
    return text[:head.end()] + block + rest[cut:], n


def set_options(env, changes):
    """Mehrere Werte in der Konfiguration des Testservers setzen und einmal
    neu laden. Jede Aenderung ist (Schluessel, Wert) oder, wo der Name
    mehrdeutig ist, (Schluessel, Wert, Abschnitt)."""
    path = env.server / "plugins" / "CamFly" / "config.yml"
    text = path.read_text(encoding="utf-8")
    for change in changes:
        key, value = change[0], change[1]
        section = change[2] if len(change) > 2 else None
        text, n = replace_option(text, key, value, section)
        if n != 1:
            where = f" unter {section}" if section else ""
            FIND.problem(f"{key} steht {n} Mal in der Testkonfiguration{where}")
    path.write_text(text, encoding="utf-8")
    console(env, "cam reload", pause=2)


def set_option(env, key, value, section=None):
    """Einen Wert in der Konfiguration des Testservers setzen und neu laden."""
    set_options(env, [(key, value, section)])


def potion_item(kind):
    """Die Gegenstandsdaten fuer /summon minecraft:<kind>."""
    return ('{Item:{id:"minecraft:' + kind + '",count:1,'
            'components:{"minecraft:potion_contents":'
            '{potion:"minecraft:slowness"}}}}')


def potion_probe(env, bot, kind, label):
    """Ein Trank auf den Spieler und einer auf seinen Koerper.

    Den Spieler selbst benetzt im Cam-Modus keiner mehr. Seinen Koerper schon:
    der Ruestungsstaender nimmt von Traenken ohnehin nichts an, das Mannequin
    in ihm sehr wohl, und von dort geht die Wirkung wie bisher an den Spieler
    weiter und beendet den Cam-Modus.

    Gemessen wird in den Entitaetsdaten statt an einer Chatmeldung: haengt die
    Langsamkeit am Spieler, steht sie unter active_effects.
    """
    def throw(where):
        bot.chat(f"/summon minecraft:{kind} {where} {potion_item(kind)}")
        # Drei Sekunden: der Splash wirkt sofort, die Wolke erst nach ihrer
        # Wartezeit, und dann noch ein paar Mal hintereinander.
        time.sleep(3)

    def clear():
        bot.chat("/effect clear @s minecraft:slowness")
        # Die Wolke eines verweilenden Tranks liegt noch da und legte die
        # Langsamkeit sofort wieder auf - mit start-with-effects: positive
        # kaeme der Bot damit nicht mehr in den Cam-Modus.
        bot.chat("/kill @e[type=area_effect_cloud]")
        time.sleep(0.5)

    # Gegenprobe zuerst: ohne Cam-Modus wirkt derselbe Trank sehr wohl. Ohne
    # sie hiesse "keine Wirkung" nur, dass der Trank nicht angekommen ist.
    clear()
    throw("~ ~1 ~")
    FIND.test(f"Ohne Cam-Modus wirkt der Trank auf den Spieler ({label})",
              bot.server_data(SLOWNESS) is not None,
              f"Langsamkeit {bot.server_data(SLOWNESS)}")
    clear()

    body = bot.server_pos()
    since = bot.mark()
    bot.chat("/cam")
    started = bot.expect("Camera mode activated|Cam mode activated", since, 8000)
    if not FIND.test(f"/cam startet fuer den Trankstest ({label})", bool(started),
                     "" if started else "keine Bestaetigung im Chat"):
        return
    time.sleep(1)

    # Weg vom Koerper, sonst erwischt ein Trank beide auf einmal und die Probe
    # sagt nicht mehr, wen von beiden er getroffen hat. Vier Bloecke reicht er
    # weit, zwoelf sind Abstand genug.
    fly = bot.call("fly", wait=30, dx=12, timeout=15000)
    Log.detail(f"Flug vom Koerper weg: {fly.get('result')}")
    time.sleep(1)
    throw("~ ~1 ~")
    FIND.test(f"Im Cam-Modus geht der Trank am Spieler vorbei ({label})",
              bot.server_data(SLOWNESS) is None,
              f"Langsamkeit {bot.server_data(SLOWNESS)}")
    FIND.test(f"Der Cam-Modus laeuft nach dem Trank weiter ({label})",
              bot.call("state").get("gameMode") == "adventure",
              str(bot.call("state").get("gameMode")))

    # Und nun auf den Koerper: den trifft der Trank weiterhin. Dass der
    # Cam-Modus endet, wird am Spielmodus gemessen; dass der Spieler auch
    # erfaehrt, warum, steht im Chat und nennt den Effekt beim Namen.
    if body is None:
        FIND.test(f"Koerperstelle bekannt ({label})", False,
                  "keine serverseitige Position")
        bot.chat("/cam")
        return
    clear()
    since = bot.mark()
    throw(f"{body[0]} {body[1] + 1} {body[2]}")
    FIND.test(f"Der Koerper wird weiterhin getroffen und beendet den "
              f"Cam-Modus ({label})",
              bot.call("state").get("gameMode") != "adventure",
              f"Spielmodus {bot.call('state').get('gameMode')}")
    told = bot.expect("hit by the effect slowness", since, 8000)
    FIND.test(f"Die Meldung nennt den Effekt, an dem es lag ({label})",
              bool(told),
              strip_colors(told["text"]) if told else "keine Meldung im Chat")
    if kind == "splash_potion":
        # Nur beim Splash ist das eindeutig: er wirkt einmal und ist vorbei.
        # Die Wolke liegt noch da, wenn der Spieler nach dem Ende des
        # Cam-Modus wieder an seinem Koerper steht - sie traefe ihn dann ganz
        # regulaer, und die Probe sagte nichts mehr ueber den Weg ueber den
        # Koerper aus.
        FIND.test("Ueber den Koerper kommt die Wirkung beim Spieler an",
                  bot.server_data(SLOWNESS) is not None,
                  f"Langsamkeit {bot.server_data(SLOWNESS)}")

    # Steht der Cam-Modus wider Erwarten noch, wird er hier abgeraeumt.
    if bot.call("state").get("gameMode") == "adventure":
        bot.chat("/cam")
        time.sleep(1)
    clear()


def potion_checks(env, bot):
    """Beide Sorten geworfener Traenke: der eine zerschellt, der andere bleibt
    als Wolke liegen und fragt immer wieder nach."""
    potion_probe(env, bot, "splash_potion", "Splash")
    potion_probe(env, bot, "lingering_potion", "verweilend")


def effect_start_checks(env, bot):
    """Der Schalter camera-mode.start-with-effects.

    Der Cam-Modus nimmt dem Spieler seine Effekte ab und gibt sie ihm beim
    Aussteigen zurueck - wer vergiftet ist, koennte das Gift dort oben also
    aussitzen. Der Schalter entscheidet, womit er ueberhaupt starten darf:
    true mit allem, false mit gar nichts, positive nur mit dem, was ihm nicht
    schadet.

    Genommen werden Schnelligkeit (positiv), Leuchten (neutral) und
    Langsamkeit (schaedlich, aber ohne Schaden - Gift wuerde die
    cam-safety-Sperre anwerfen und vor der Ablehnung stehen).
    """
    def probe(name, effect, darf, genannt=None):
        bot.chat("/effect clear @s")
        time.sleep(0.4)
        if effect:
            bot.chat(f"/effect give @s minecraft:{effect} 60 0")
            time.sleep(0.6)
        since = bot.mark()
        bot.chat("/cam")
        if darf:
            hit = bot.expect("Camera mode activated|Cam mode activated", since, 8000)
            FIND.test(name, bool(hit), "" if hit else "der Start wurde abgelehnt")
        else:
            hit = bot.expect(f"cannot start cam mode with {genannt} on you",
                             since, 8000)
            FIND.test(name, bool(hit),
                      strip_colors(hit["text"]) if hit else "keine Ablehnung im Chat")
        # Steht er drin - gewollt oder nicht -, kommt er hier wieder heraus.
        if bot.call("state").get("gameMode") == "adventure":
            bot.chat("/cam")
            time.sleep(1)

    try:
        # Die Voreinstellung wird nicht gesetzt, sondern nachgesehen: so faellt
        # auf, wenn in der ausgelieferten Datei etwas anderes steht.
        probe("Voreingestellt startet /cam mit einem positiven Effekt",
              "speed", True)
        probe("Voreingestellt startet /cam auch mit einem neutralen Effekt",
              "glowing", True)
        probe("Voreingestellt sperrt ein schaedlicher Effekt den Start",
              "slowness", False, "slowness")

        # Mehrere auf einmal: genannt wird alles, an dem es liegt, und nur
        # das. Auf die Reihenfolge wird nicht geprueft - in welcher der Server
        # seine Effekte herausgibt, ist nicht zugesichert.
        bot.chat("/effect clear @s")
        time.sleep(0.4)
        for effect in ("slowness", "blindness", "speed"):
            bot.chat(f"/effect give @s minecraft:{effect} 60 0")
        time.sleep(1)
        since = bot.mark()
        bot.chat("/cam")
        hit = bot.expect("cannot start cam mode with", since, 8000)
        text = strip_colors(hit["text"]) if hit else ""
        FIND.test("Die Ablehnung nennt jeden schaedlichen Effekt und nur die",
                  "slowness" in text and "blindness" in text and "speed" not in text,
                  text or "keine Ablehnung im Chat")
        if bot.call("state").get("gameMode") == "adventure":
            bot.chat("/cam")
            time.sleep(1)

        set_option(env, "start-with-effects", "false")
        probe("Auf false sperrt auch ein positiver Effekt den Start",
              "speed", False, "speed")
        probe("Auf false geht es ohne jeden Effekt", None, True)

        set_option(env, "start-with-effects", "true")
        probe("Auf true geht es auch mit einem schaedlichen Effekt",
              "slowness", True)
    finally:
        set_option(env, "start-with-effects", "positive")
        bot.chat("/effect clear @s")
        time.sleep(0.5)


# ---------------------------------------------------------------------------
# Interaktionen: Bloecke und Entitaeten
# ---------------------------------------------------------------------------

# Gefragt wird ueber server_says, block_is und cam_on/cam_off aus dem
# Portalteil. Die stehen unter diesem Abschnitt - gesucht werden sie erst beim
# Aufruf, und beisammen bleiben sie dort, wo sie hergekommen sind.

# Die Marke an allem, was dieser Abschnitt in die Welt setzt. Die Testwelt
# bleibt zwischen zwei Laeufen stehen; ohne die Marke faende der naechste Lauf
# die Entitaeten eines abgebrochenen wieder vor und klickte auf die alten.
INTERACT_TAG = "camflytest"

# Wie lange nach einem Klick gewartet wird, ehe am Server nachgesehen wird.
# Der Klick geht als Paket hinaus, gewirkt hat er fruehestens im naechsten Tick.
INTERACT_WAIT = 1.0


def _floor(wert):
    """Ganze Zahl nach unten, auch unter null. int() schnitte dort zur
    falschen Seite ab, und der Boden der Flachwelt liegt bei -64."""
    return int(wert // 1)


def hinstellen(bot, x, y, z):
    """Den Bot an eine Stelle setzen und ihm einen Moment geben.

    Ein Teleport und kein Flug: Wohin er kommt, steht damit fest, und der
    Abstand zum Ziel entscheidet darueber, ob sein Klick ueberhaupt in
    Reichweite ist.
    """
    bot.chat(f"/tp {BOT_NAME} {x} {y} {z}")
    time.sleep(1.0)


def nbt_frage(bot, auswahl, nbt):
    """Ob die Daten dieser Entitaet diesen Ausschnitt enthalten.

    Das NBT wird verdoppelt weitergereicht: server_says schickt das Kommando
    durch format(), und geschweifte Klammern, die stehen bleiben sollen,
    muessen dort doppelt stehen.
    """
    geschuetzt = nbt.replace("{", "{{").replace("}", "}}")
    return server_says(bot, f"/execute if data entity {auswahl} {geschuetzt} "
                            f"run say {{marke}}")


def entity_da(bot, art):
    """Ob eine Entitaet dieser Art mit unserer Marke dasteht."""
    return server_says(bot, f"/execute if entity @e[type={art},tag={INTERACT_TAG}] "
                            f"run say {{marke}}")


def klicken(bot, op, **kw):
    """Einen Klick des Bots absetzen und ins Protokoll schreiben, was er
    daraus gemacht hat.

    Der Grund zaehlt: Faellt spaeter eine Gegenprobe durch, steht sonst nur
    da, dass sich nichts geruehrt hat - und nicht, ob der Klick ueberhaupt
    hinausging.
    """
    antwort = bot.call(op, wait=25, **kw)
    if not antwort.get("done"):
        Log.detail(f"{op}: {antwort.get('reason') or 'ohne Angabe'}")
    return antwort


def interact_aufraeumen(bot, base):
    """Alles wegnehmen, was dieser Abschnitt in die Welt gesetzt hat."""
    bx, by, bz = base
    bot.chat(f"/kill @e[tag={INTERACT_TAG}]")
    time.sleep(0.4)
    # Auch, was herumliegt: Der Abbau im Durchgang ohne Cam-Modus laesst eine
    # Blume fallen, und die zaehlte beim Klick auf den eigenen Koerper als
    # naechste Entitaet mit.
    bot.chat("/kill @e[type=minecraft:item]")
    time.sleep(0.4)
    bot.chat(f"/fill {bx + 1} {by} {bz - 1} {bx + 11} {by + 8} {bz + 8} minecraft:air")
    time.sleep(0.8)


def interact_proben(bot, base):
    """Einmal alles anfassen und sagen, was davon durchging.

    Jeder Eintrag im Ergebnis ist wahr, wenn die Interaktion gewirkt hat - der
    Hebel also umgelegt wurde, der Block weg ist, das Fenster aufging. Der
    Abschnitt spielt das zweimal durch, einmal ohne Cam-Modus und einmal darin;
    verglichen werden die beiden Ergebnisse.

    Aufgebaut wird bei jedem Durchgang neu. Der erste Durchgang laesst den
    Hebel umgelegt und die Blume abgebaut zurueck, und ohne den Neuaufbau
    pruefte der zweite an einer Welt, die schon so aussieht, wie sie am Ende
    aussehen soll.
    """
    bx, by, bz = base
    ergebnis = {}

    # Mit etwas in der Hand legt ein Rechtsklick auf einen Ruestungsstaender
    # das Mitgebrachte an, statt etwas abzunehmen - und die Probe sagte dann
    # nichts mehr darueber, ob der Klick angekommen ist. Was der Bot aus den
    # Abschnitten davor noch hat, kommt deshalb weg.
    bot.chat(f"/clear {BOT_NAME}")
    time.sleep(0.6)

    # --- Hebel: der Rechtsklick auf einen Block ---
    bot.chat(f"/setblock {bx + 3} {by} {bz} minecraft:stone")
    time.sleep(0.4)
    bot.chat(f"/setblock {bx + 3} {by + 1} {bz} "
             f"minecraft:lever[face=floor,facing=north,powered=false]")
    time.sleep(0.6)
    hinstellen(bot, bx + 3.5, by, bz + 2.5)
    klicken(bot, "activate_block", x=bx + 3, y=by + 1, z=bz, timeout=5000)
    time.sleep(INTERACT_WAIT)
    ergebnis["hebel"] = block_is(bot, "minecraft:overworld", f"{bx + 3} {by + 1} {bz}",
                                 "minecraft:lever[powered=true]")

    # --- Abbauen: die Blume geht mit einem Schlag, Stein dauerte zu lange ---
    bot.chat(f"/setblock {bx + 5} {by} {bz} minecraft:dandelion")
    time.sleep(0.6)
    # Erst nachsehen, ob sie steht: Eine Blume, die gar nicht gesetzt wurde,
    # ist hinterher auch weg, und die Probe hiesse "abgebaut", ohne dass
    # jemand sie angefasst haette.
    steht = block_is(bot, "minecraft:overworld", f"{bx + 5} {by} {bz}",
                     "minecraft:dandelion")
    hinstellen(bot, bx + 5.5, by, bz + 2.5)
    klicken(bot, "dig_block", x=bx + 5, y=by, z=bz, timeout=8000)
    time.sleep(INTERACT_WAIT)
    ergebnis["abbau"] = steht and not block_is(
        bot, "minecraft:overworld", f"{bx + 5} {by} {bz}", "minecraft:dandelion")

    # --- Druckplatte: die Interaktion ohne Klick, Action.PHYSICAL ---
    bot.chat(f"/setblock {bx + 7} {by} {bz} minecraft:stone_pressure_plate")
    time.sleep(0.6)
    hinstellen(bot, bx + 7.5, by, bz + 0.5)
    time.sleep(0.8)
    ergebnis["platte"] = block_is(bot, "minecraft:overworld", f"{bx + 7} {by} {bz}",
                                  "minecraft:stone_pressure_plate[powered=true]")

    # --- Item-Rahmen: das Bild drehen ---
    rahmen = f"@e[type=minecraft:item_frame,tag={INTERACT_TAG},limit=1]"
    bot.chat(f"/kill @e[type=minecraft:item_frame,tag={INTERACT_TAG}]")
    time.sleep(0.4)
    bot.chat(f"/setblock {bx + 3} {by} {bz + 5} minecraft:stone")
    time.sleep(0.4)
    bot.chat(f'/summon minecraft:item_frame {bx + 3} {by + 1} {bz + 5} '
             f'{{Facing:1b,ItemRotation:0b,Item:{{id:"minecraft:stone",count:1}},'
             f'Tags:["{INTERACT_TAG}"]}}')
    time.sleep(0.8)
    hinstellen(bot, bx + 3.5, by, bz + 3.5)
    # Dass er dasteht, gehoert zur Antwort: Ein Rahmen, der gar nicht erst
    # erschienen ist, hat auch keine Drehung auf null - und die Probe hiesse
    # "gedreht", ohne dass jemand ihn angefasst haette.
    da = entity_da(bot, "minecraft:item_frame")
    klicken(bot, "activate_entity", type="item_frame", radius=4)
    time.sleep(INTERACT_WAIT)
    ergebnis["rahmen"] = da and not nbt_frage(bot, rahmen, "{ItemRotation:0b}")

    # Den fremden Ruestungsstaender laesst dieser Abschnitt aus. Nicht, weil
    # das Plugin ihn nicht abwiese - sondern weil der Bot ihn gar nicht erst
    # ausziehen kann, auch ohne Cam-Modus nicht: Vanilla wickelt das Abnehmen
    # allein ueber interactAt ab, und der Trefferpunkt dieses Pakets uebersteht
    # die geflickten Paketdaten nicht. Nachgemessen: Der Staender trug Stiefel
    # und Stock vor dem Klick und danach immer noch, in beiden Durchgaengen.
    # Eine Probe, deren Gegenprobe nie durchkommt, sagt ueber das Plugin
    # nichts - sie stuende nur bei jedem Lauf rot da.
    #
    # Was sie gesagt haette, sagen zwei andere mit: Der Item-Rahmen zeigt, dass
    # ein Rechtsklick auf eine fremde Entitaet abgewiesen wird, und der Klick
    # auf den eigenen Koerper zeigt, dass ein Klick auf einen Ruestungsstaender
    # beim Plugin ankommt - der Koerper ist selbst einer.
    # --- Kistenlore: das Fenster einer Entitaet ---
    bot.chat(f"/kill @e[type=minecraft:chest_minecart,tag={INTERACT_TAG}]")
    time.sleep(0.4)
    bot.chat(f'/summon minecraft:chest_minecart {bx + 7} {by} {bz + 5} '
             f'{{NoGravity:1b,Tags:["{INTERACT_TAG}"]}}')
    time.sleep(0.8)
    hinstellen(bot, bx + 7.5, by, bz + 3.5)
    bot.call("close_window", wait=10)
    klicken(bot, "activate_entity", type="chest_minecart", radius=3)
    time.sleep(INTERACT_WAIT)
    ergebnis["fenster"] = bool(bot.call("window", wait=10).get("open"))
    bot.call("close_window", wait=10)

    # --- Boot: aufsteigen, ueber /ride statt ueber den Klick ---
    # Der Klick taugt hier nicht: Er kommt an, das Boot nimmt ihn nur nicht an
    # - der Bot spricht auf geflickten Paketdaten, und an dieser einen Stelle
    # reicht das nicht. /ride geht denselben Weg im Server (startRiding, und
    # damit EntityMountEvent und VehicleEnterEvent), nur ohne Client dazwischen
    # - und genau die beiden sind es, die das Plugin abfaengt.
    boot = f"@e[type=minecraft:oak_boat,tag={INTERACT_TAG},limit=1]"
    bot.chat(f"/kill @e[type=minecraft:oak_boat,tag={INTERACT_TAG}]")
    time.sleep(0.4)
    bot.chat(f'/summon minecraft:oak_boat {bx + 9} {by} {bz + 5} '
             f'{{Tags:["{INTERACT_TAG}"]}}')
    time.sleep(0.8)
    hinstellen(bot, bx + 9.5, by, bz + 3.5)
    bot.chat(f"/ride {BOT_NAME} mount {boot}")
    time.sleep(INTERACT_WAIT)
    ergebnis["boot"] = server_says(bot, "/execute on vehicle run say {marke}")
    bot.chat(f"/ride {BOT_NAME} dismount")
    time.sleep(0.4)
    # Wieder heraus: Ein Bot, der im Boot sitzt, laesst sich nicht mehr
    # hinstellen, und die Proben danach liefen alle an derselben Stelle.
    bot.chat(f"/kill @e[type=minecraft:oak_boat,tag={INTERACT_TAG}]")
    time.sleep(0.6)

    return ergebnis


def ghast_probe(bot, base):
    """Den Bot auf einen Happy Ghast setzen und sagen, wo er danach steht.

    Der Ghast steht still: NoAI und NoGravity, sonst zoege er davon und die
    Stelle, an der der Bot aufgesetzt wird, waere jedes Mal eine andere. Er ist
    vier Bloecke hoch, sein Ruecken liegt also vier ueber seinen Fuessen.

    Gibt die Hoehe zurueck, an der der Bot danach steht, oder None.
    """
    bx, by, bz = base
    bot.chat(f"/kill @e[type=minecraft:happy_ghast,tag={INTERACT_TAG}]")
    time.sleep(0.5)
    bot.chat(f'/summon minecraft:happy_ghast {bx + 11} {by + 2} {bz} '
             f'{{NoAI:1b,NoGravity:1b,Silent:1b,Tags:["{INTERACT_TAG}"]}}')
    time.sleep(1.2)
    if not entity_da(bot, "minecraft:happy_ghast"):
        return None
    bot.chat(f"/tp {BOT_NAME} {bx + 11} {by + 6} {bz}")
    time.sleep(2.0)
    pos = bot.server_pos()
    return None if pos is None else pos[1]


def interact_checks(env, bot):
    """Was der Cam-Modus anfassen darf - und was nicht.

    Im Cam-Modus geht kein Block mehr auf und keine Entitaet mehr an. Das
    Einzige, was dem Spieler bleibt, ist sein eigener Koerper, und der Klick
    darauf beendet den Cam-Modus.

    Jede Probe steht zweimal da: einmal ohne Cam-Modus und einmal darin. Ohne
    die Gegenprobe sagte dieser Abschnitt nur, dass sich nichts geruehrt hat -
    und das sagt er auch dann, wenn der Klick des Bots gar nicht erst ankommt.
    Faellt eine Gegenprobe durch, ist die Probe daneben nichts wert, und das
    steht dann auch so da.
    """
    if not FIND.test("Cam-Modus ist vor dem Interaktionstest aus", cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Interaktionstest lesbar", pos is not None, str(pos)):
        return
    base = (_floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2]))
    bx, by, bz = base
    Log.detail(f"Testplatz bei {base}")

    try:
        # --- Erst ohne Cam-Modus: geht der Klick des Bots ueberhaupt durch? ---
        interact_aufraeumen(bot, base)
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        ohne = interact_proben(bot, base)
        ghast_ohne = ghast_probe(bot, base)

        # --- Und nun im Cam-Modus, von derselben Stelle aus ---
        interact_aufraeumen(bot, base)
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        bot.chat(f"/give {BOT_NAME} minecraft:stone 1")
        time.sleep(0.8)
        if not FIND.test("/cam startet fuer den Interaktionstest", cam_on(bot), ""):
            return

        # Das leere Inventar gehoert zum Schutz: Ohne Gegenstand in der Hand
        # gibt es auch keinen mit CanPlaceOn oder CanDestroy, mit dem sich im
        # Abenteuermodus doch bauen liesse.
        inv = bot.call("inventory", wait=10)
        FIND.test("Im Cam-Modus ist das Inventar leer", inv.get("count") == 0,
                  ", ".join(inv.get("names") or []) or "leer")

        drin = interact_proben(bot, base)
        ghast_drin = ghast_probe(bot, base)

        # --- Der eigene Koerper ist das Einzige, was ihm bleibt ---
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        klicken(bot, "activate_entity", radius=3)
        time.sleep(INTERACT_WAIT)
        # Am Spielmodus gemessen und nicht an der Meldung: adventure heisst im
        # Cam-Modus, alles andere heisst beendet.
        beendet = server_says(bot, "/execute if entity @s[gamemode=survival] "
                                   "run say {marke}")
        FIND.test("Der Klick auf den eigenen Koerper beendet den Cam-Modus", beendet,
                  "" if beendet else "der Cam-Modus lief weiter")
        cam_off(bot)
        time.sleep(1)

        inv = bot.call("inventory", wait=10)
        FIND.test("Nach dem Cam-Modus ist das Inventar wieder da",
                  inv.get("count", 0) > 0,
                  ", ".join(inv.get("names") or []) or "leer geblieben")

        # --- Die Ergebnisse gegenueberstellen ---
        proben = [
            ("hebel", "ein Hebel umlegen", "kein Hebel umlegen"),
            ("abbau", "ein Block abbauen", "kein Block abbauen"),
            ("platte", "eine Druckplatte ausloesen", "keine Druckplatte ausloesen"),
            ("rahmen", "ein Bild im Rahmen drehen", "kein Bild im Rahmen drehen"),
            ("fenster", "das Fenster einer Kistenlore oeffnen",
                        "kein Fenster einer Kistenlore oeffnen"),
            ("boot", "ein Boot besteigen", "kein Boot besteigen"),
        ]
        for schluessel, ja, nein in proben:
            FIND.test(f"Gegenprobe: ohne Cam-Modus laesst sich {ja}",
                      ohne.get(schluessel),
                      "" if ohne.get(schluessel) else
                      "kam nicht durch - die Probe daneben sagt damit nichts")
            FIND.test(f"Im Cam-Modus laesst sich {nein}",
                      not drin.get(schluessel),
                      "" if not drin.get(schluessel) else "es ging doch")

        # --- Der Happy Ghast ---
        # Die Gegenprobe wird nach beiden Seiten eingegrenzt. Nur "nicht
        # abgehoben" hiesse sie auch gut, wenn der Bot glatt durch den Ghast
        # hindurchgefallen waere - und dann sagte die Probe darunter nichts
        # mehr darueber, wer ihn angehoben hat.
        rueckenhoehe = by + 6            # Fuesse bei by+2, vier Bloecke hoch
        steht = (ghast_ohne is not None
                 and rueckenhoehe - 0.5 <= ghast_ohne <= rueckenhoehe + 0.5)
        FIND.test("Gegenprobe: ohne Cam-Modus bleibt der Bot auf dem Happy Ghast stehen",
                  steht, f"Hoehe {ghast_ohne}, Ruecken bei {rueckenhoehe}")
        FIND.test("Im Cam-Modus wird die Kamera vom Happy Ghast abgehoben",
                  ghast_drin is not None and ghast_drin >= rueckenhoehe + 1.5,
                  f"Hoehe {ghast_drin}, Ruecken bei {rueckenhoehe}")
    finally:
        # Auch dann aufraeumen, wenn unterwegs etwas schiefging: Die Testwelt
        # bleibt stehen, und der naechste Lauf faende sonst alles wieder vor.
        try:
            cam_off(bot)
            interact_aufraeumen(bot, base)
            bot.chat(f"/clear {BOT_NAME}")
            hinstellen(bot, bx + 0.5, by, bz + 0.5)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Interaktionstest: {exc}")


# ---------------------------------------------------------------------------
# Der Spielmodus, in dem der Cam-Modus laeuft
# ---------------------------------------------------------------------------

# Gefragt wird auch hier ueber server_says, block_is und cam_on/cam_off aus
# dem Portalteil weiter unten - gesucht werden sie erst beim Aufruf.


def spielmodus_ist(bot, modus):
    """Ob der Server den Bot gerade in diesem Spielmodus fuehrt.

    Serverseitig gefragt und nicht bei mineflayer: bot.game.gameMode ist die
    Sicht des Clients, die hier auf geflickten Paketdaten laeuft, und eine
    falsche Auskunft liesse genau die Probe durchgehen, um die es geht.
    """
    return server_says(bot, f"/execute if entity @s[gamemode={modus}] "
                            f"run say {{marke}}")


def spielmodus_setzen(bot, modus):
    """Den Bot in diesen Spielmodus setzen und ihm einen Moment geben."""
    bot.chat(f"/gamemode {modus} {BOT_NAME}")
    time.sleep(0.8)


def blume_abbauen(bot, base):
    """Einmal versuchen, eine Blume abzubauen, und sagen, was daraus wurde.

    True heisst: Sie ist weg. False heisst: Sie steht noch. None heisst: Sie
    stand gar nicht erst da, und dann sagt die Probe nichts - eine Blume, die
    nie gesetzt wurde, ist hinterher auch weg, und das hiesse sonst
    "abgebaut", ohne dass jemand sie angefasst haette.

    Eine Blume und kein Stein: Sie geht mit einem Schlag, Stein dauerte zu
    lange.
    """
    bx, by, bz = base
    wo = f"{bx + 5} {by} {bz}"
    bot.chat(f"/setblock {wo} minecraft:dandelion")
    time.sleep(0.6)
    if not block_is(bot, "minecraft:overworld", wo, "minecraft:dandelion"):
        return None
    hinstellen(bot, bx + 5.5, by, bz + 2.5)
    klicken(bot, "dig_block", x=bx + 5, y=by, z=bz, timeout=8000)
    time.sleep(INTERACT_WAIT)
    return not block_is(bot, "minecraft:overworld", wo, "minecraft:dandelion")


def gamemode_checks(env, bot):
    """Der Schalter camera-mode.gamemode.

    Er sagt, in welchem Spielmodus der Cam-Modus geflogen wird: adventure wie
    von jeher, survival, creative, oder keep - dann bleibt der Modus stehen,
    in dem der Spieler gerade steht. Beim Aussteigen kommt er in jedem Fall
    in den Modus zurueck, in dem er gestartet ist, und das wird bei jeder
    Probe mitgeprueft.

    Der Zuschauermodus steht nicht zur Wahl und wird bei jedem der vier Werte
    abgelehnt. Geprueft wird beides: dass die Ablehnung im Chat steht und
    dass der Bot danach wirklich noch Zuschauer ist. Die Meldung allein
    sagte nur, dass etwas im Chat stand.

    Dazu die beiden Sperren, die frueher am Abenteuermodus hingen und jetzt
    am Kamera-Spieler:

    * Der Abbau. In Kreativ faengt ihn der abgebrochene Linksklick ab, in
      Ueberleben erst der BlockBreakEvent-Handler - in Ueberleben ist diese
      Probe also die einzige, die ihn ueberhaupt prueft.
    * Die Taschen. Was im Cam-Modus hineinkommt, raeumt der Sweep von
      CamInventoryGuard im naechsten Tick wieder ab.

    Der Mittelklick in Kreativ, an dem die Taschen aufgefallen sind, laesst
    sich vom Bot nicht schicken: Das Paket dafuer kennt der Bot nicht - er
    faehrt auf den Paketdaten von 26.1, siehe die Flickerei ganz oben.
    Geprueft wird deshalb der Griff, der ihn unschaedlich macht, und zwar mit
    /give: Der legt dem Spieler etwas in dieselben Taschen, die der
    Mittelklick fuellen wuerde. Geht der Sweep kaputt, faellt diese Probe -
    egal, auf welchem Weg etwas hineingekommen waere.

    Das Blockplatzieren bekommt keine eigene Probe: Der Sweep haelt die Haende
    leer, also ist nichts da, was sich setzen liesse. Die beiden haengen
    zusammen, und faellt der Sweep, faellt die Probe darueber.
    """
    if not FIND.test("Cam-Modus ist vor dem Spielmodus-Test aus", cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Spielmodus-Test lesbar", pos is not None,
                     str(pos)):
        return
    base = (_floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2]))
    bx, by, bz = base
    Log.detail(f"Testplatz fuer den Spielmodus bei {base}")

    def flug_probe(wert, start, erwartet):
        """Aus `start` heraus starten: Worin fliegt er, und wohin kommt er
        danach zurueck?"""
        spielmodus_setzen(bot, start)
        if not FIND.test(f"gamemode {wert}: /cam startet aus {start} heraus",
                         cam_on(bot), ""):
            return False
        drin = spielmodus_ist(bot, erwartet)
        FIND.test(f"gamemode {wert}: der Cam-Modus laeuft in {erwartet}", drin,
                  "" if drin else f"der Server fuehrt ihn nicht in {erwartet}")
        cam_off(bot)
        time.sleep(1)
        zurueck = spielmodus_ist(bot, start)
        FIND.test(f"gamemode {wert}: danach steht er wieder in {start}", zurueck,
                  "" if zurueck else f"der Server fuehrt ihn nicht in {start}")
        return drin

    def zuschauer_probe(wert):
        """Aus dem Zuschauermodus heraus /cam - das wird abgelehnt."""
        spielmodus_setzen(bot, "spectator")
        since = bot.mark()
        bot.chat("/cam")
        time.sleep(1.5)
        gesagt = [strip_colors(m["text"])
                  for m in bot.call("messages", since=since).get("messages", [])]
        abgelehnt = any("cannot start cam mode in spectator mode" in t.lower()
                        for t in gesagt)
        gestartet = any(re.search(r"[Cc]am mode activated", t) for t in gesagt)
        FIND.test(f"gamemode {wert}: /cam lehnt den Zuschauermodus ab",
                  abgelehnt and not gestartet,
                  "" if abgelehnt and not gestartet else
                  ("der Cam-Modus ist trotzdem angegangen" if gestartet
                   else "keine Ablehnung im Chat"))
        blieb = spielmodus_ist(bot, "spectator")
        FIND.test(f"gamemode {wert}: er bleibt dabei Zuschauer", blieb,
                  "" if blieb else "der Spielmodus hat sich doch geaendert")
        spielmodus_setzen(bot, "survival")

    def abbau_probe(wert, modus):
        """Der Abbau, einmal ohne Cam-Modus und einmal darin.

        Ohne die Gegenprobe sagte die zweite Haelfte nur, dass sich nichts
        geruehrt hat - und das sagt sie auch dann, wenn der Klick des Bots
        gar nicht erst ankommt.
        """
        spielmodus_setzen(bot, modus)
        interact_aufraeumen(bot, base)
        ohne = blume_abbauen(bot, base)
        FIND.test(f"Gegenprobe gamemode {wert}: ohne Cam-Modus laesst sich "
                  f"in {modus} abbauen", ohne is True,
                  "" if ohne is True else
                  ("die Blume stand nicht" if ohne is None else
                   "kam nicht durch - die Probe daneben sagt damit nichts"))
        interact_aufraeumen(bot, base)
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        if not FIND.test(f"gamemode {wert}: /cam startet fuer die Abbauprobe",
                         cam_on(bot), ""):
            return
        drin = blume_abbauen(bot, base)
        FIND.test(f"gamemode {wert}: im Cam-Modus laesst sich kein Block abbauen",
                  drin is False,
                  "" if drin is False else
                  ("die Blume stand nicht" if drin is None else "sie wurde abgebaut"))
        cam_off(bot)
        time.sleep(1)

    def taschen_probe(wert, modus):
        """Was im Cam-Modus in die Taschen geraet, ist gleich wieder weg."""
        spielmodus_setzen(bot, modus)
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        if not FIND.test(f"gamemode {wert}: /cam startet fuer die Taschenprobe",
                         cam_on(bot), ""):
            return
        leer = bot.call("inventory", wait=10)
        FIND.test(f"gamemode {wert}: der Cam-Modus faengt mit leeren Taschen an",
                  leer.get("count") == 0,
                  ", ".join(leer.get("names") or []) or "leer")
        bot.chat(f"/give {BOT_NAME} minecraft:stone 1")
        time.sleep(1.2)
        inv = bot.call("inventory", wait=10)
        FIND.test(f"gamemode {wert}: was im Cam-Modus hineinkommt, "
                  f"wird wieder abgeraeumt", inv.get("count") == 0,
                  ", ".join(inv.get("names") or []) or "leer")
        cam_off(bot)
        time.sleep(1)

    try:
        # --- Die Voreinstellung wird nicht gesetzt, sondern nachgesehen ---
        # So faellt auf, wenn in der ausgelieferten Datei etwas anderes steht.
        flug_probe("adventure (Voreinstellung)", "survival", "adventure")
        zuschauer_probe("adventure (Voreinstellung)")

        # --- Ueberleben ---
        set_option(env, "gamemode", "survival")
        flug_probe("survival", "survival", "survival")
        # Aus Kreativ heraus derselbe Wert: Das trennt den festen Modus von
        # keep, das hier denselben Ausgang haette, wenn man nur aus Ueberleben
        # heraus startet.
        flug_probe("survival", "creative", "survival")
        zuschauer_probe("survival")
        abbau_probe("survival", "survival")
        taschen_probe("survival", "survival")

        # --- Kreativ ---
        set_option(env, "gamemode", "creative")
        flug_probe("creative", "survival", "creative")
        zuschauer_probe("creative")
        abbau_probe("creative", "creative")
        taschen_probe("creative", "creative")

        # --- keep: der Modus bleibt stehen ---
        set_option(env, "gamemode", "keep")
        flug_probe("keep", "survival", "survival")
        flug_probe("keep", "creative", "creative")
        flug_probe("keep", "adventure", "adventure")
        zuschauer_probe("keep")

        # --- Ein unbekannter Wert faellt auf adventure zurueck ---
        # "zuschauer" ist mit Absicht genommen: Das ist der Modus, den jemand
        # hier am ehesten eintraegt, und genau der steht nicht zur Wahl.
        set_option(env, "gamemode", "zuschauer")
        # Beides in derselben Zeile: Das Log waechst ueber den ganzen Lauf,
        # und "Unbekannter Wert" allein traefe auch auf eine Meldung von
        # irgendwoher zu. Der Umlaut in der Meldung bleibt aussen vor - das
        # Log wird mit errors="replace" gelesen.
        meldung = any("Unbekannter Wert" in zeile and "camera-mode.gamemode" in zeile
                      for zeile in server_log(env).splitlines())
        FIND.test("Ein unbekannter Wert fuer gamemode wird gemeldet", meldung,
                  "" if meldung else "keine Meldung im Server-Log")
        flug_probe("unbekannt", "survival", "adventure")
    finally:
        try:
            set_option(env, "gamemode", "adventure")
            spielmodus_setzen(bot, "survival")
            cam_off(bot)
            interact_aufraeumen(bot, base)
            hinstellen(bot, bx + 0.5, by, bz + 0.5)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Spielmodus-Test: {exc}")


# ---------------------------------------------------------------------------
# Portale
# ---------------------------------------------------------------------------

# Die Marke, mit der der Server eine Ja-Nein-Frage beantwortet: Ein
# /execute if ... run say sagt sie nur, wenn die Bedingung zutrifft. Jede
# Frage bekommt ihre eigene Nummer, sonst koennte eine spaet eingetroffene
# Antwort von vorhin als Antwort auf die naechste Frage durchgehen.
SERVER_YES = "CAMFLY-JA"
_server_yes_zaehler = [0]

# Wie lange auf die Reise durch ein Portal gewartet wird, in Sekunden. Der
# Cam-Modus laeuft im Abenteuermodus - Kreativ steht nur einen Tick lang da -
# und dort dauert der Portalvorgang die vollen 80 Ticks. Die Abkuerzung auf
# einen einzigen Tick gilt nur fuer Unverwundbare, also Kreativ und Zuschauer.
PORTAL_TRAVEL_WAIT = 6.0

# Wie lange nach jedem Anlauf gewartet wird, in Sekunden. Das Plugin legt dem
# Spieler nach jeder Abweisung und nach jedem Zurueckholen eine Portalsperre
# von 100 Ticks auf. Wer sie nicht abwartet, steht beim naechsten Anlauf in
# einem Portal, das gar nichts mehr tut - und solange er darin steht, laeuft
# sie nicht einmal ab.
PORTAL_COOLDOWN_WAIT = 7.0

# Dasselbe nach einer Reise, die wirklich in der anderen Welt geendet hat:
# Dort hat niemand die Sperre des Plugins ueberschrieben, und Minecraft selbst
# legt nach einem Weltwechsel 300 Ticks auf.
DIMENSION_COOLDOWN_WAIT = 17.0

# Wie weit um das Reiseziel herum drueben gearbeitet wird, waagerecht, in
# Bloecken. Der Server setzt ein neues Portal in die Naehe des Ziels, nimmt
# aber auch ein vorhandenes im Umkreis von 128 - was er waehlt, steht nicht
# vorher fest, also wird grosszuegig gearbeitet.
NETHER_REACH = 22

# Der Nether ist 128 Bloecke hoch, und in welcher Hoehe der Server ein Portal
# hinsetzt, steht ebenso wenig vorher fest. Gearbeitet wird deshalb ueber die
# ganze Saeule, in Scheiben: /fillbiome und /fill vertragen hoechstens 32768
# Bloecke, und 45 * 16 * 45 sind 32400.
NETHER_MIN_Y, NETHER_MAX_Y = 0, 127
NETHER_SLICE = 16


def server_says(bot, command, timeout=4000):
    """Eine Ja-Nein-Frage an den Server. Das Kommando traegt {marke} und sagt
    diese Marke genau dann, wenn die Bedingung zutrifft."""
    _server_yes_zaehler[0] += 1
    marke = f"{SERVER_YES}-{_server_yes_zaehler[0]}"
    since = bot.mark()
    bot.chat(command.format(marke=marke))
    return bool(bot.expect(re.escape(marke), since, timeout))


def block_is(bot, dimension, where, block):
    """Ob an dieser Stelle dieser Block steht, in dieser Welt."""
    return server_says(bot, f"/execute in {dimension} if block {where} {block} "
                            f"run say {{marke}}")


def in_nether(bot):
    """Ob der Bot gerade im Nether steht."""
    return server_says(bot, "/execute if dimension minecraft:the_nether "
                            "run say {marke}")


def build_portal(bot, dimension, x, y, z):
    """Ein Netherportal setzen und anzuenden.

    Der Rahmen ist die uebliche Platte aus Obsidian, vier breit und fuenf
    hoch, innen ausgehoehlt; das Feuer im untersten Innenfeld zuendet sie an.
    Die Innenfelder liegen bei x..x+1 und y..y+2, die ganze Platte in der
    Ebene z. Gesetzt wird von ueberall aus ueber /execute in, damit es nicht
    darauf ankommt, wo der Bot gerade steht.

    Gibt zurueck, ob danach wirklich ein Portalfeld dasteht.
    """
    at = f"/execute in {dimension} run"
    bot.chat(f"{at} fill {x-1} {y-1} {z} {x+2} {y+3} {z} minecraft:obsidian")
    time.sleep(0.5)
    bot.chat(f"{at} fill {x} {y} {z} {x+1} {y+2} {z} minecraft:air")
    time.sleep(0.5)
    bot.chat(f"{at} setblock {x} {y} {z} minecraft:fire")
    time.sleep(1.0)
    return block_is(bot, dimension, f"{x} {y} {z}", "minecraft:nether_portal")


def nether_slices(center):
    """Die Saeule um diese Stelle herum, in Scheiben, jede als Koordinatenpaar
    fuer /fill und /fillbiome. Eine ganze Saeule auf einmal waere zu gross."""
    cx, cz = center
    y = NETHER_MIN_Y
    while y <= NETHER_MAX_Y:
        top = min(NETHER_MAX_Y, y + NETHER_SLICE - 1)
        yield (f"{cx - NETHER_REACH} {y} {cz - NETHER_REACH} "
               f"{cx + NETHER_REACH} {top} {cz + NETHER_REACH}")
        y = top + 1


def hold_nether_chunks(bot, center):
    """Die Chunks um das Reiseziel herum festhalten. Das laedt sie auch, und
    ohne geladene Chunks tun /fill und /fillbiome drueben gar nichts."""
    cx, cz = center
    bot.chat(f"/execute in minecraft:the_nether run forceload add "
             f"{cx - NETHER_REACH} {cz - NETHER_REACH} "
             f"{cx + NETHER_REACH} {cz + NETHER_REACH}")
    time.sleep(2)


def fill_nether_biome(bot, center, biome):
    """Das Biom der ganzen Saeule um diese Stelle herum setzen.

    Gesetzt und nicht vorausgesetzt: Die Testwelt bleibt zwischen zwei Laeufen
    stehen, drueben kann also noch stehen, was ein frueherer Lauf dort gesetzt
    hat.
    """
    for box in nether_slices(center):
        bot.chat(f"/execute in minecraft:the_nether run fillbiome {box} {biome}")
        time.sleep(1.2)


def clear_nether_portals(bot, center):
    """Jedes Portalfeld der Saeule wegnehmen. Der Rahmen bleibt stehen -
    gesucht wird ohnehin nach Portalfeldern, nicht nach Rahmen."""
    for box in nether_slices(center):
        bot.chat(f"/execute in minecraft:the_nether run fill {box} "
                 f"minecraft:air replace minecraft:nether_portal")
        time.sleep(1.0)


def cam_schalten(bot, an):
    """Den Cam-Modus ein- oder ausschalten und am Ergebnis nachsehen.

    Nicht am Spielmodus des Clients: Nach einem Weltwechsel steht der bei
    mineflayer nicht mehr zuverlaessig - es laeuft hier auf geflickten
    Paketdaten -, und eine falsche Auskunft schaltet genau verkehrt herum.
    Gefragt wird deshalb, was das Plugin auf /cam antwortet; hat es das
    Gegenteil getan, geht noch ein /cam hinterher.
    """
    for _ in range(2):
        since = bot.mark()
        bot.chat("/cam")
        time.sleep(1.5)
        gesagt = [strip_colors(m["text"])
                  for m in bot.call("messages", since=since).get("messages", [])]
        ein = any(re.search(r"[Cc]am mode activated", t) for t in gesagt)
        aus = any(re.search(r"[Cc]am mode ended", t) for t in gesagt)
        if ein == an and aus != an:
            return True
        if not ein and not aus:
            return False    # /cam hat gar nicht geantwortet, etwa abgelehnt
    return False


def cam_off(bot):
    """Den Cam-Modus beenden. Laeuft er nicht, bleibt alles, wie es ist."""
    return cam_schalten(bot, False)


def cam_on(bot):
    """Den Cam-Modus starten, falls er nicht schon laeuft."""
    return cam_schalten(bot, True)


def portal_probe(bot, portal, heim, label=""):
    """Einmal ins Portal treten und sagen, was daraus geworden ist:

        zu            - das Portal selbst laesst Kamera-Spieler nicht durch
        abgewiesen    - cam-area hinter dem Portal, er kam gar nicht erst hin
        zurueckgeholt - er war drueben und wurde gleich wieder geholt
        zu weit       - er kam zu weit von seinem Anker heraus
        drueben       - er ist drueben und bleibt dort
        nichts        - gar keine Reaktion

    Danach steht er wieder am Koerper, im Cam-Modus, und die Portalsperre ist
    abgelaufen: Der naechste Anlauf faengt sauber an.
    """
    # Jeder Anlauf sorgt selbst dafuer, dass der Cam-Modus laeuft: Ein
    # cam reload wirft jeden Kamera-Spieler heraus, und ohne Cam-Modus ginge
    # der Bot ganz regulaer durch das Portal - die Probe sagte dann gar nichts
    # ueber das Plugin aus.
    cam_on(bot)
    since = bot.mark()
    # Mit Welt davor: Ohne sie setzt /tp ihn dorthin, wo er gerade ist, und
    # nach einer Reise ist das der Nether - dann stuende er unter dessen Boden
    # statt in seinem Portal.
    bot.chat("/execute in minecraft:overworld run tp @s {:.1f} {:d} {:.1f}".format(
        portal[0] + 0.5, portal[1], portal[2] + 0.5))
    time.sleep(PORTAL_TRAVEL_WAIT)
    where = bot.server_pos()
    said = [strip_colors(m["text"])
            for m in bot.call("messages", since=since).get("messages", [])]

    def heard(pattern):
        return any(re.search(pattern, line, re.I) for line in said)

    if heard(r"cannot go through .*portals"):
        was = "zu"
    elif heard(r"cannot go any further"):
        was = "abgewiesen"
    elif heard(r"not allowed in .*brought back"):
        was = "zurueckgeholt"
    elif heard(r"blocks away from .*brought back"):
        was = "zu weit"
    elif in_nether(bot):
        was = "drueben"
    else:
        was = "nichts"
    Log.detail(f"Anlauf{' ' + label if label else ''}: {was}, danach bei {where}")

    # Zurueck auf Anfang: aus dem Cam-Modus heraus, heim in die Overworld und
    # wieder hinein. Der Ausstieg setzt ihn zwar schon an seinen Koerper, aber
    # der Test verlaesst sich darauf nicht - und aus dem Portal heraus muss er
    # auf jeden Fall, sonst zieht die Portalsperre sich jeden Tick neu auf und
    # laeuft nie ab. Gewartet wird zum Schluss, im Cam-Modus: draussen stuende
    # er derweil als Fliegender ohne Erlaubnis da.
    cam_off(bot)
    bot.chat("/execute in minecraft:overworld run tp @s {:.1f} {:d} {:.1f}".format(
        heim[0] + 0.5, heim[1], heim[2] + 0.5))
    time.sleep(1)
    cam_on(bot)
    time.sleep(DIMENSION_COOLDOWN_WAIT if was == "drueben" else PORTAL_COOLDOWN_WAIT)
    return {"was": was, "pos": where}


def durchgelassen(ergebnis):
    """Ob das Portal ihn ueberhaupt hat reisen lassen - gleich, ob er drueben
    bleiben durfte oder gleich wieder geholt wurde. Genau das ist die Frage,
    wenn geprueft wird, ob ein gemerktes Portal wieder freigegeben wurde."""
    return ergebnis["was"] in ("zurueckgeholt", "drueben")


def sperre_herstellen(bot, portal, heim, label):
    """Dafuer sorgen, dass dieses Portal im Gedaechtnis des Plugins steht.

    Steht es schon drin, weist es den Anlauf ab und es ist nichts weiter zu
    tun. Sonst geht die Reise hinueber, und drueben muss sie in einem
    verbotenen Biom herauskommen. Wo genau, sucht sich der Server aber selbst
    aus: Er nimmt das Portal, das seinem Ziel am naechsten liegt, und baut
    eines, wo keines steht. Landet der Bot deshalb in einem erlaubten Biom,
    wird diese Stelle dazugenommen und der Anlauf wiederholt - darauf, dass
    zweimal dieselbe Ecke herauskommt, kann der Test sich nicht verlassen.

    Gibt zurueck, ob das Portal danach gesperrt ist, und den letzten Anlauf.
    """
    ergebnis = {"was": "nichts", "pos": None}
    for versuch in range(1, 4):
        ergebnis = portal_probe(bot, portal, heim, f"{label}, {versuch}. Versuch")
        if ergebnis["was"] in ("abgewiesen", "zurueckgeholt"):
            return True, ergebnis
        if ergebnis["was"] != "drueben" or not ergebnis["pos"]:
            return False, ergebnis
        dort = (int(ergebnis["pos"][0]), int(ergebnis["pos"][2]))
        Log.detail(f"Ankunft liegt in einem erlaubten Biom, wird verboten: {dort}")
        fill_nether_biome(bot, dort, "minecraft:lush_caves")
    return False, ergebnis


def portal_checks(env, bot):
    """Portale im Cam-Modus, und wie lange sich das Plugin ein gesperrtes
    Portal merkt.

    Wo ein Portal herauskommt, laesst sich nicht vorher erfragen - das steht
    erst nach der Reise fest. Kommt der Kamera-Spieler drueben in einem
    verbotenen Biom heraus, holt das Plugin ihn zurueck und merkt sich das
    Portal; beim naechsten Mal laesst es ihn gar nicht mehr durch. Dieses
    Gemerkte wird aber nachgeprueft, und darum geht es hier: Ein Portal fuehrt
    dorthin, wohin die Welt es fuehren laesst, und die wird umgebaut.

    Der Ablauf baut aufeinander auf, jeder Anlauf setzt den naechsten auf:

      1. Ein Portal in der Overworld bauen und einmal hindurchgehen. Das legt
         das Portal drueben an, laedt die Chunks und sagt, wo die Reise
         herauskommt.
      2. Das Biom um die Ankunft herum auf lush_caves setzen, eines der
         verbotenen. Der naechste Anlauf muss ihn zurueckholen, der uebernaechste
         am Portal abgewiesen werden.
      3. Drueben ein zweites Portal in die Naehe bauen. Damit kaeme die Reise
         woanders heraus, also muss der Eintrag fallen.
      4. Drueben alle Portalfelder wegnehmen. Dasselbe noch einmal, nur ueber
         den anderen Weg: Das Nachsehen an der Ankunft.
      5. Mit forget-changed: false muss beides aufhoeren - der Eintrag steht
         dann, bis /cam reload ihn wegraeumt.
      6. Zum Schluss der Schalter portals.nether selbst.

    Die Chunks drueben werden festgehalten (/forceload): Ohne einen Spieler
    dort fallen sie weg, und /fill und /fillbiome brauchen sie geladen.

    Zwei Wartezeiten stecken drin, beide unvermeidlich. Der Portalvorgang
    dauert im Abenteuermodus 80 Ticks, und nach jedem Anlauf liegt eine
    Portalsperre von 100 Ticks auf dem Spieler.

    Und: Jedes cam reload leert das Gemerkte. Zwischen dem Anlauf, der ein
    Portal sperrt, und dem, der die Sperre prueft, darf deshalb nichts an der
    Konfiguration gedreht werden.
    """
    bot.chat("/effect clear @s")
    time.sleep(0.5)
    # Die Welt bleibt zwischen zwei Laeufen stehen, und ein abgebrochener Lauf
    # kann den Bot drueben zurueckgelassen haben. Von dort aus baute dieser
    # Test sein Portal in den Nether und nichts passte mehr zusammen.
    cam_off(bot)
    if in_nether(bot):
        Log.detail("Der Bot steht noch im Nether - erst zurueck in die Overworld")
        bot.chat("/execute in minecraft:overworld run tp @s 0 -59 0")
        time.sleep(2)
    pos = bot.server_pos()
    if not FIND.test("Position fuer den Portaltest lesbar", pos is not None, str(pos)):
        return
    bx, by, bz = int(pos[0]), int(pos[1]), int(pos[2])
    # Weit genug vom Koerper, dass der Rahmen ihn nicht einmauert, und nah
    # genug, dass max-distance nicht dazwischenfunkt.
    portal = (bx + 5, by, bz + 6)
    # Wohin jeder Anlauf ihn zwischendurch zuruecksetzt: dorthin, wo er steht,
    # und ausdruecklich in die Overworld.
    heim = (bx, by, bz)
    # Wohin die Reise fuehrt, rechnet der Server aus: Im Nether gilt ein Achtel
    # der Koordinaten. Das Portal drueben setzt er in die Naehe dieser Stelle.
    ziel = (portal[0] // 8, portal[2] // 8)
    vorbereitet = False

    try:
        set_options(env, [("nether", "true", "portals"),
                          ("nether", "true", "cam-area")])

        steht = build_portal(bot, "minecraft:overworld", *portal)
        if not FIND.test("Testportal steht in der Overworld", steht, str(portal)):
            return

        # Erst drueben aufraeumen, dann hinuebergehen: Die Testwelt bleibt
        # zwischen zwei Laeufen stehen, und was ein frueherer Lauf dort gesetzt
        # hat, darf diesen hier nicht entscheiden.
        Log.detail(f"Reiseziel drueben liegt um {ziel} herum")
        hold_nether_chunks(bot, ziel)
        vorbereitet = True
        fill_nether_biome(bot, ziel, "minecraft:nether_wastes")

        cam_on(bot)
        erste = portal_probe(bot, portal, heim, "1: hinueber")
        FIND.test("Ein offenes Portal traegt den Kamera-Spieler in den Nether",
                  erste["was"] == "drueben", erste["was"])
        if erste["pos"] is None or erste["was"] != "drueben":
            return
        arrival = (int(erste["pos"][0]), int(erste["pos"][1]), int(erste["pos"][2]))
        Log.detail(f"Ankunft drueben: {arrival}")

        # --- Verbotenes Biom hinter dem Portal ---
        fill_nether_biome(bot, ziel, "minecraft:lush_caves")
        gesperrt, zweite = sperre_herstellen(bot, portal, heim, "2: verbotenes Biom")
        FIND.test("Ein verbotenes Biom hinter dem Portal holt ihn zurueck",
                  zweite["was"] == "zurueckgeholt", zweite["was"])
        dritte = portal_probe(bot, portal, heim, "3: gemerkt")
        FIND.test("Danach laesst dasselbe Portal ihn gar nicht mehr durch",
                  dritte["was"] == "abgewiesen", dritte["was"])

        # --- Ein neu gebautes Portal drueben gibt das gemerkte wieder frei ---
        neben = (arrival[0] + 10, arrival[1], arrival[2])
        gebaut = build_portal(bot, "minecraft:the_nether", *neben)
        FIND.test("Zweites Portal drueben steht", gebaut, str(neben))
        vierte = portal_probe(bot, portal, heim, "4: nach dem Neubau drueben")
        FIND.test("Ein neu gebautes Portal drueben gibt das gemerkte wieder frei",
                  durchgelassen(vierte), vierte["was"])
        gesperrt, fuenfte = sperre_herstellen(bot, portal, heim, "5: wieder sperren")
        FIND.test("Nach der Reise steht das Portal wieder im Gedaechtnis",
                  gesperrt, fuenfte["was"])

        # --- Ist das Portal drueben weg, wird das Gemerkte nachgeprueft ---
        clear_nether_portals(bot, ziel)
        sechste = portal_probe(bot, portal, heim, "6: Portal drueben weg")
        FIND.test("Ist das Portal drueben abgebaut, wird das Gemerkte verworfen",
                  durchgelassen(sechste), sechste["was"])

        # --- Und mit forget-changed: false bleibt es stehen ---
        # Das Umstellen leert das Gemerkte, der Eintrag muss also erst wieder
        # angelegt werden.
        set_option(env, "forget-changed", "false")
        gesperrt, siebte = sperre_herstellen(bot, portal, heim,
                                            "7: sperren mit forget-changed false")
        FIND.test("Auch mit forget-changed: false wird ein Portal gemerkt",
                  gesperrt, siebte["was"])
        achte = portal_probe(bot, portal, heim, "8: gemerkt")
        FIND.test("Mit forget-changed: false greift das Gemerkte genauso",
                  achte["was"] == "abgewiesen", achte["was"])
        clear_nether_portals(bot, ziel)
        neunte = portal_probe(bot, portal, heim, "9: Portal drueben weg, ohne Freigabe")
        FIND.test("Mit forget-changed: false bleibt der Eintrag trotzdem stehen",
                  neunte["was"] == "abgewiesen", neunte["was"])

        # --- Der Schalter fuer das Portal selbst ---
        set_options(env, [("forget-changed", "true"),
                          ("nether", "false", "portals")])
        zehnte = portal_probe(bot, portal, heim, "10: portals.nether false")
        FIND.test("Auf portals.nether: false traegt das Portal ihn gar nicht",
                  zehnte["was"] == "zu", zehnte["was"])
    finally:
        cam_off(bot)
        # Nicht im Nether stehen lassen: Die Welt bleibt stehen, und der
        # naechste Lauf faengt sonst drueben an.
        bot.chat("/execute in minecraft:overworld run tp @s {:.1f} {:d} {:.1f}".format(
            heim[0] + 0.5, heim[1], heim[2] + 0.5))
        time.sleep(1)
        if vorbereitet:
            # Drueben wieder wie vorher: kein Portal, ein erlaubtes Biom, und
            # die Chunks los. Sonst finge der naechste Lauf mit dem an, was
            # dieser hier stehen gelassen hat.
            clear_nether_portals(bot, ziel)
            fill_nether_biome(bot, ziel, "minecraft:nether_wastes")
            bot.chat("/execute in minecraft:the_nether run forceload remove all")
            time.sleep(1)
        # Das Testportal wieder abraeumen, samt Rahmen.
        px, py, pz = portal
        bot.chat(f"/execute in minecraft:overworld run fill "
                 f"{px-1} {py-1} {pz} {px+2} {py+3} {pz} minecraft:air")
        time.sleep(0.5)
        set_options(env, [("nether", "false", "portals"),
                          ("nether", "false", "cam-area"),
                          ("forget-changed", "true")])


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

        # --- Start mit Trankeffekten ---
        effect_start_checks(env, bot)

        # --- Geworfene Traenke im Cam-Modus ---
        potion_checks(env, bot)

        # --- Bloecke und Entitaeten im Cam-Modus ---
        interact_checks(env, bot)

        # --- Der Spielmodus, in dem der Cam-Modus laeuft ---
        gamemode_checks(env, bot)

        # --- Portale und das Gedaechtnis fuer gesperrte Portale ---
        portal_checks(env, bot)

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
