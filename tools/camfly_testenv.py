#!/usr/bin/env python3
"""
CamFly - Testumgebung aufbauen und Tests fahren.

Das Skript macht alles, was sonst von Hand gemacht wurde:

  1. JDK 25 holen (das System hat meist nur 21)
  2. Plugin bauen (mvn -B clean package gegen spigot-api)
  3. Zusaetzlich gegen paper-api uebersetzen (die APIs sind nicht deckungsgleich)
  4. ApiCheck: jeden Bukkit-Aufruf im fertigen Jar gegen paper-api aufloesen
  5. Namen: die Namenslisten der Sprachdatei gegen die Daten des Spiels
  6. Paper-Testserver holen, einrichten und starten (mit FIFO fuer die Konsole)
  7. mineflayer holen, auf Protokoll 26.2 flicken, seine Kollision wie im
     echten Client rechnen lassen, Bot verbinden
  8. Tests im laufenden Spiel fahren
  9. Aufraeumen: Server und Bot beenden

Aufruf:
    python3 tools/camfly_testenv.py                  # alles
    python3 tools/camfly_testenv.py --steps build,apicheck
    python3 tools/camfly_testenv.py --steps names    # nach einem Update: fehlen Namen?
    python3 tools/camfly_testenv.py --keep-running   # Server laeuft weiter
    python3 tools/camfly_testenv.py --stop           # laufenden Server beenden

Das Skript aendert NICHTS am Plugin. Es baut, prueft und berichtet.
Alles, was es herunterlaedt, liegt unter --workdir (Standard:
~/camfly-testenv) und wird beim naechsten Lauf wiederverwendet.
"""

import argparse
import gzip
import io
import json
import math
import os
import re
import shutil
import signal
import struct
import subprocess
import sys
import time
import zipfile
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
# Dieser Pruefer zaehlt zurzeit 665 Methoden- und Feldzugriffe. Alle 665 gibt
# es auch in paper-api. Der Hinweis steht also bei jedem Lauf da.
EXPECTED_API_CALLS = 348

# Der Bot-Name steht fest im Skript. Ueber eine Umgebungsvariable geht er
# beim nohup-Start verloren, der zweite Bot joint dann als "TestBot" und
# kickt den ersten mit duplicate_login.
BOT_NAME = "CamFlyTester"

SERVER_HOST = "127.0.0.1"
SERVER_PORT = 25565

STEPS = ["jdk", "build", "paperapi", "crosscheck", "apicheck", "names",
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
        """Das gebaute Jar, aus target/ herauskopiert - das naechste
        mvn clean raeumt target/ leer, und ein Lauf ohne den Schritt build
        braucht das Jar trotzdem."""
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
# 6. Namen: die Listen der Sprachdatei gegen die Daten des Spiels
# ---------------------------------------------------------------------------

# Die Mobs mit Spawn-Ei, die nichts angreifen und deshalb keinen Namen in
# mob-names brauchen. Ein Mob mit Spawn-Ei, der weder dort noch hier steht,
# ist mit einer neuen Version dazugekommen: Greift er an, gehoert er mit
# einem Namen in mob-names, sonst hierher.
MOBS_OHNE_ANGRIFF = (
    "allay", "armadillo", "axolotl", "bat", "camel", "camel_husk", "cat", "chicken",
    "cod", "copper_golem", "cow", "donkey", "fox", "frog", "glow_squid", "happy_ghast",
    "horse", "mooshroom", "mule", "ocelot", "parrot", "pig", "pufferfish", "rabbit",
    "salmon", "sheep", "skeleton_horse", "sniffer", "squid", "strider", "tadpole",
    "tropical_fish", "turtle", "villager", "wandering_trader", "zombie_horse",
)

# Wo im Jar des Servers die Schadensarten, Biome und Strukturen liegen, eine
# Datei je Eintrag.
SCHADENSARTEN_ORDNER = "data/minecraft/damage_type/"
BIOME_ORDNER = "data/minecraft/worldgen/biome/"
STRUKTUREN_ORDNER = "data/minecraft/worldgen/structure/"


def spiel_jar(env):
    """Das Jar des Servers, in dem die Daten des Spiels lesbar liegen.

    Der Download von Paper traegt sie nur als Patch auf das Jar von Mojang.
    Zusammengesetzt werden sie beim ersten Start des Servers - oder hier,
    mit paperclip.patchonly, ohne dass ein Server startet. Der Server findet
    das Jar danach fertig vor und setzt es nicht noch einmal zusammen.
    """
    jar = env.server / "versions" / MC_VERSION / f"paper-{MC_VERSION}.jar"
    if not jar.exists():
        if not env.java.exists():
            raise RuntimeError("Erst Schritt 'jdk' laufen lassen")
        url, name = paper_jar_url()
        paperclip = download(url, env.cache / name, f"Paper {MC_VERSION} Build {PAPER_BUILD}")
        env.server.mkdir(parents=True, exist_ok=True)
        Log.info("Paperclip setzt das Jar des Servers zusammen ...")
        run([str(env.java), "-Dpaperclip.patchonly=true", "-jar", str(paperclip)],
            cwd=env.server, timeout=900)
    return jar


def namen_in(text, abschnitt):
    """Die Schluessel eines Abschnitts der Sprachdatei. Er reicht bis zur
    naechsten Zeile, die ganz links anfaengt, wie in replace_option."""
    head = re.search(rf"(?m)^{re.escape(abschnitt)}:\s*$", text)
    if head is None:
        return set()
    rest = text[head.end():]
    nxt = re.search(r"(?m)^\S", rest)
    return set(re.findall(r"(?m)^\s+([a-z0-9_]+):", rest[:nxt.start() if nxt else len(rest)]))


def step_names(env):
    """Ob die Namenslisten der Sprachdatei zum Spiel passen.

    Jede Schadensart, jeder Effekt, jedes Biom und jede Struktur des Spiels
    braucht einen Namen unter damage-names, effect-names, biome-names und
    structure-names, und jeder Mob mit Spawn-Ei steht unter mob-names oder in
    MOBS_OHNE_ANGRIFF. Umgekehrt muss es alles, was in den Listen steht, im
    Spiel auch geben. So faellt nach einem Update auf, was eine neue Version
    dazugebracht, umbenannt oder entfernt hat - das Plugin selbst sagt dazu
    nichts, es nennt etwas ohne Namen nur bei seinem Schluessel und einen Mob
    so, wie das Spiel ihn nennt.

    Mobs ohne Spawn-Ei, etwa den Illusioner, sieht diese Pruefung nicht.
    dimension-names und portal-names auch nicht: Ihre Schluessel gibt das
    Plugin vor, nicht das Spiel.
    """
    Log.step("6. Namen der Sprachdatei gegen die Daten des Spiels")
    jar = spiel_jar(env)
    if not FIND.test("Daten des Spiels lesbar", jar.exists(), str(jar)):
        return False
    with zipfile.ZipFile(jar) as z:
        lang = json.loads(z.read("assets/minecraft/lang/en_us.json"))
        dateien = z.namelist()

    def im_ordner(ordner):
        return {n[len(ordner):-len(".json")] for n in dateien
                if n.startswith(ordner) and n.endswith(".json") and "/" not in n[len(ordner):]}

    def aus_sprache(vorn, hinten=""):
        return {k[len(vorn):len(k) - len(hinten)] for k in lang
                if k.startswith(vorn) and k.endswith(hinten) and k.count(".") == 2}

    entitaeten = aus_sprache("entity.minecraft.")
    mit_ei = aus_sprache("item.minecraft.", "_spawn_egg")
    text = (env.repo / "src" / "main" / "resources" / SPRACHDATEI).read_text(encoding="utf-8")
    mob_names = namen_in(text, "mob-names")

    def liste(namen):
        return ", ".join(sorted(namen))

    ergebnisse = []
    for abschnitt, im_spiel, jede, zu_einer, mehrzahl in (
            ("damage-names", im_ordner(SCHADENSARTEN_ORDNER), "Jede Schadensart", "einer Schadensart",
             "Schadensarten"),
            ("effect-names", aus_sprache("effect.minecraft."), "Jeder Effekt", "einem Effekt", "Effekte"),
            ("biome-names", im_ordner(BIOME_ORDNER), "Jedes Biom", "einem Biom", "Biome"),
            ("structure-names", im_ordner(STRUKTUREN_ORDNER), "Jede Struktur", "einer Struktur",
             "Strukturen")):
        namen = namen_in(text, abschnitt)
        fehlen = im_spiel - namen
        veraltet = namen - im_spiel
        ergebnisse.append(FIND.test(
            f"{jede} des Spiels hat einen Namen in {abschnitt}", bool(im_spiel) and not fehlen,
            f"{len(im_spiel)} {mehrzahl}" if not fehlen else f"es fehlen: {liste(fehlen)}"))
        ergebnisse.append(FIND.test(
            f"Jeder Name in {abschnitt} gehoert zu {zu_einer} des Spiels", not veraltet,
            "" if not veraltet else f"gibt es nicht: {liste(veraltet)}"))

    neu = mit_ei - mob_names - set(MOBS_OHNE_ANGRIFF)
    unbekannt = (mob_names | set(MOBS_OHNE_ANGRIFF)) - entitaeten
    ergebnisse.append(FIND.test("Jeder Mob mit Spawn-Ei steht in mob-names oder in MOBS_OHNE_ANGRIFF", not neu,
                                f"{len(mit_ei)} Mobs" if not neu else f"neu: {liste(neu)}"))
    ergebnisse.append(FIND.test("Jeden Mob aus mob-names und MOBS_OHNE_ANGRIFF gibt es im Spiel", not unbekannt,
                                "" if not unbekannt else f"gibt es nicht: {liste(unbekannt)}"))
    return all(ergebnisse)


# ---------------------------------------------------------------------------
# 7. Paper-Testserver
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
    # waere eine Falle: Ein Lauf ohne den Schritt build faende dort die
    # Fassung vom letzten Bauen, womoeglich eine alte, und pruefte das Plugin
    # gegen eine Konfiguration, in der die neuen Schluessel gar nicht stehen.
    # Zu holen gibt es dort ohnehin nichts: Maven kopiert die Datei nur.
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
    # Die Sprachdatei ebenso, aus demselben Grund. Das Plugin legt sie zwar
    # selbst an, aber nur, wenn sie fehlt - was ein abgebrochener Lauf an
    # ihren Schaltern gedreht hat, bliebe sonst fuer den naechsten stehen.
    (data_dir / "lang").mkdir(exist_ok=True)
    shutil.copy2(env.repo / "src" / "main" / "resources" / SPRACHDATEI, data_dir / SPRACHDATEI)
    return text


def step_server(env):
    Log.step("7. Paper-Testserver")
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
    # Die Spielregeln heissen seit 26.x anders: doMobSpawning ist spawn_mobs,
    # doDaylightCycle ist advance_time. Mit den alten Namen lehnte der Server
    # beide ab, und Tiere wie Nacht kamen trotzdem.
    console(env, "gamerule spawn_mobs false")
    console(env, "gamerule advance_time false")
    console(env, "time set day")
    return True


# ---------------------------------------------------------------------------
# 8. Bot (mineflayer)
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
// Das Explosionspaket von 26.2 traegt Partikel mit Nummern, die 26.1 anders
// vergibt. mineflayer verschluckt sich daran und wirft das ganze Paket weg,
// samt dem Rueckstoss darin. Gelesen wird deshalb nur bis zum Rueckstoss, der
// Rest bleibt ein ungelesener Puffer.
{
  const play = idx && idx.protocol && idx.protocol.play && idx.protocol.play.toClient
    && idx.protocol.play.toClient.types;
  const explosion = play && play.packet_explosion;
  if (explosion && explosion[0] === 'container') {
    const cut = explosion[1].findIndex((f) => f.name === 'explosionParticle');
    if (cut > 0) {
      play.packet_explosion = ['container',
        explosion[1].slice(0, cut).concat([{ name: 'rest', type: 'restBuffer' }])];
    }
  }
}
// mineflayer/lib/version.js ist schon auf der Platte geflickt - loader.js
// liest den Wert genau einmal beim Laden.
const mineflayer = require('mineflayer');

function out(obj) { process.stdout.write(JSON.stringify(obj) + '\n'); }

const messages = [];
let spawned = false;
let dead = false;
// Was knock_start gerade mitschreibt, bis knock_stop es abholt.
let knock = null;

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
  const Vec3 = require('vec3');
  // Mit 'at' die Entitaet, die einer Stelle am naechsten steht, sonst die
  // naechste am Bot.
  const me = cmd.at ? new Vec3(cmd.at[0], cmd.at[1], cmd.at[2])
                    : bot.entity && bot.entity.position;
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
      // voraus. Die Wahrheit steht in den Entitaetsdaten. 'selector' fragt
      // nach einer anderen Entitaet als dem Bot selbst.
      const since = messages.length;
      bot.chat('/data get entity ' + (cmd.selector || '@s') + ' Pos');
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
          pos: [e.position.x, e.position.y, e.position.z],
          vehicle: e.vehicle ? e.vehicle.id : null
        });
      }
      return { entities: list };
    }
    case 'fly': {
      // Selbst geflogen, im selben Schritt wie bot.creative.flyTo: ein halber
      // Block alle 50 ms. flyTo selbst laesst sich nicht abbrechen - haelt das
      // Plugin die Kamera an einer Grenze fest, kommt es nie an und zieht den
      // Bot auch nach dem Timeout weiter zu seinem alten Ziel, gegen jeden
      // spaeteren Flug und jedes /tp. Diese Schleife hoert am Timeout auf.
      const Vec3 = require('vec3');
      const p = bot.entity.position;
      const target = new Vec3(
        cmd.x !== undefined ? cmd.x : p.x + (cmd.dx || 0),
        cmd.y !== undefined ? cmd.y : p.y + (cmd.dy || 0),
        cmd.z !== undefined ? cmd.z : p.z + (cmd.dz || 0));
      const STEP = 0.5;
      const deadline = Date.now() + (cmd.timeout || 12000);
      try { bot.creative.startFlying(); } catch (e) { /* egal */ }
      let done = 'Zeit abgelaufen';
      while (Date.now() < deadline) {
        const vector = target.minus(bot.entity.position);
        const magnitude = vector.norm();
        if (magnitude <= STEP) {
          bot.entity.position = target.clone();
          await new Promise((r) => {
            const t = setTimeout(r, 1000);
            bot.once('move', () => { clearTimeout(t); r(); });
          });
          done = 'angekommen';
          break;
        }
        bot.physics.gravity = 0;
        bot.entity.velocity = new Vec3(0, 0, 0);
        bot.entity.position.add(vector.scaled(STEP / magnitude));
        await new Promise((r) => setTimeout(r, 50));
      }
      const now = bot.entity.position;
      return { result: done, target: [target.x, target.y, target.z],
               pos: [now.x, now.y, now.z] };
    }
    case 'stop_fly':
      try { bot.creative.stopFlying(); } catch (e) { /* egal */ }
      return { ok: true };
    case 'walk': {
      // Mit der Physik des Clients bewegt und nicht selbst versetzt wie in
      // 'fly': Nur so stoesst der Bot an Bloecke, die der Server allein ihm
      // geschickt hat - wie ein echter Client an die Wand von border-mode:
      // barrier. 'forced' zaehlt, wie oft der Server ihn dabei auf eine
      // Stelle zurueckgesetzt hat; genau das tut push-back und barrier nicht.
      const Vec3 = require('vec3');
      let forced = 0;
      const onForced = () => { forced += 1; };
      bot.on('forcedMove', onForced);
      try {
        try { bot.creative.startFlying(); } catch (e) { /* egal */ }
        bot.physics.gravity = 0;
        bot.entity.velocity = new Vec3(0, 0, 0);
        const p = bot.entity.position;
        await Promise.race([
          bot.lookAt(new Vec3(p.x + (cmd.dx || 0) * 20, p.y + 1.62, p.z + (cmd.dz || 0) * 20), true),
          new Promise((r) => setTimeout(r, 2000))
        ]);
        bot.setControlState('forward', true);
        await new Promise((r) => setTimeout(r, cmd.ms || 3000));
      } finally {
        bot.clearControlStates();
        bot.removeListener('forcedMove', onForced);
      }
      await new Promise((r) => setTimeout(r, 500));
      const now = bot.entity.position;
      return { forced, pos: [now.x, now.y, now.z] };
    }
    case 'fall': {
      // Mit Schwerkraft fallen, mit der Physik des Clients - wie ein echter
      // Client, der mitten ueber etwas aufhoert zu fliegen. So landet der Bot
      // auf einem Block, den der Server nur ihm geschickt hat. 'forced'
      // zaehlt wie bei 'walk', wie oft der Server ihn dabei zurueckgesetzt hat.
      const Vec3 = require('vec3');
      let forced = 0;
      const onForced = () => { forced += 1; };
      bot.on('forcedMove', onForced);
      try {
        bot.entity.velocity = new Vec3(0, 0, 0);
        bot.physics.gravity = 0.08;
        await new Promise((r) => setTimeout(r, cmd.ms || 2500));
      } finally {
        bot.physics.gravity = 0;
        bot.entity.velocity = new Vec3(0, 0, 0);
        bot.removeListener('forcedMove', onForced);
      }
      await new Promise((r) => setTimeout(r, 500));
      const now = bot.entity.position;
      return { forced, pos: [now.x, now.y, now.z] };
    }
    case 'knock_start': {
      // Schreibt mit, was den Bot nach einem Treffer bewegt: jedes Paket mit
      // seiner Geschwindigkeit, jede Explosion und Tick fuer Tick, wo er
      // steht. Dabei faellt er mit der Schwerkraft wie ein echter Client -
      // mit 'nachTeleport' erst, sobald der Server ihn versetzt hat: Im
      // Cam-Modus wartet er in der Luft, und erst das Ende des Cam-Modus
      // setzt ihn an seinen Koerper.
      //
      // Die Geschwindigkeit aus dem Paket setzt der Abschnitt selbst.
      // mineflayer teilt das lpVec3 von 26.x noch durch 8000, als kaeme es im
      // alten Format, und der Bot ruehrte sich nach einem Treffer kaum. Im
      // Paket steht sie schon in Bloecken je Tick.
      if (knock) knock.stop();
      const log = { velocity: [], explosion: [], teleport: [], path: [] };
      const start = Date.now();
      const where = () => {
        const p = bot.entity.position;
        return [p.x, p.y, p.z];
      };
      const gravity = bot.physics.gravity;
      const fallen = () => { bot.physics.gravity = 0.08; };
      const onVelocity = (packet) => {
        if (!bot.entity || packet.entityId !== bot.entity.id) return;
        const v = packet.velocity;
        bot.entity.velocity.set(v.x, v.y, v.z);
        log.velocity.push({ t: Date.now() - start, v: [v.x, v.y, v.z], pos: where() });
      };
      const onExplosion = (packet) => {
        const k = packet.playerKnockback;
        log.explosion.push({ t: Date.now() - start, knockback: k ? [k.x, k.y, k.z] : null });
      };
      const onTeleport = () => {
        log.teleport.push({ t: Date.now() - start, pos: where(), schritt: log.path.length });
        fallen();
      };
      const onTick = () => { log.path.push(where()); };
      if (!cmd.nachTeleport) fallen();
      bot._client.on('entity_velocity', onVelocity);
      bot._client.on('explosion', onExplosion);
      bot.on('forcedMove', onTeleport);
      bot.on('physicsTick', onTick);
      knock = {
        log,
        stop: () => {
          bot._client.removeListener('entity_velocity', onVelocity);
          bot._client.removeListener('explosion', onExplosion);
          bot.removeListener('forcedMove', onTeleport);
          bot.removeListener('physicsTick', onTick);
          bot.physics.gravity = gravity;
          bot.entity.velocity.set(0, 0, 0);
        }
      };
      return { pos: where() };
    }
    case 'knock_stop': {
      if (!knock) return { log: null };
      const p = bot.entity.position;
      const pos = [p.x, p.y, p.z];
      knock.stop();
      const log = knock.log;
      knock = null;
      return { log, pos };
    }
    case 'block_at': {
      // Was der CLIENT an dieser Stelle sieht, samt allem, was der Server
      // nur ihm geschickt hat. Die Welt selbst fragt der Test beim Server.
      const Vec3 = require('vec3');
      const block = bot.blockAt(new Vec3(cmd.x, cmd.y, cmd.z));
      return block ? { name: block.name, state: block.stateId } : { name: null };
    }
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
    case 'attack_entity': {
      // Ein Linksklick auf eine Entitaet. Der Schlag hat sein eigenes Paket,
      // nur mit der Nummer der Entitaet darin, und der Arm schwingt danach -
      // wie beim echten Client. Geschrieben wird wie bei activate_entity
      // selbst, nach einem harten Blick auf das Ziel. Der Blick geht erst mit
      // dem naechsten Physik-Tick hinaus, deshalb die kurze Pause: Ohne sie
      // kaeme der Schlag mit der alten Blickrichtung an, und die bestimmt,
      // wohin der Sulfur Cube fliegt.
      const e = pickEntity(cmd);
      if (!e) return { done: false, reason: 'keine solche Entitaet in der Naehe' };
      try {
        await Promise.race([
          bot.lookAt(e.position.offset(0, cmd.aim === undefined ? 0.5 : cmd.aim, 0), true),
          new Promise((r) => setTimeout(r, 2000))
        ]);
        await new Promise((r) => setTimeout(r, 150));
        bot._client.write('attack', { entityId: e.id });
        bot.swingArm();
        await new Promise((r) => setTimeout(r, 100));
        return { done: true, id: e.id, type: e.name };
      } catch (err) {
        return { done: false, id: e.id, type: e.name,
                 reason: String(err && err.message || err) };
      }
    }
    case 'stab': {
      // Ein Stich mit dem Speer. Der Speer schlaegt nicht ueber das Paket
      // fuer den Schlag, das nimmt der Server mit einem Speer in der Hand
      // gar nicht an: Der Client meldet einen Stich als Aktion Nummer 7
      // (STAB), und der Server sucht selbst entlang des Blicks, was er
      // trifft. Deshalb erst hart hinsehen und den Blick einen Tick lang
      // hinausgehen lassen.
      const e = pickEntity(cmd);
      if (!e) return { done: false, reason: 'keine solche Entitaet in der Naehe' };
      try {
        await Promise.race([
          bot.lookAt(e.position.offset(0, cmd.aim === undefined ? 1.0 : cmd.aim, 0), true),
          new Promise((r) => setTimeout(r, 2000))
        ]);
        await new Promise((r) => setTimeout(r, 150));
        bot._client.write('block_dig', {
          status: 7, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0
        });
        bot.swingArm();
        await new Promise((r) => setTimeout(r, 100));
        return { done: true, id: e.id, type: e.name };
      } catch (err) {
        return { done: false, id: e.id, type: e.name,
                 reason: String(err && err.message || err) };
      }
    }
    case 'sprint':
      // Sprinten meldet der Client dem Server mit einem eigenen Paket. Das
      // Paket schreibt der Abschnitt selbst: mineflayer schickt fuer 26.x
      // die Nummer aus alten Versionen, und die heisst dort etwas anderes.
      bot._client.write('entity_action', {
        entityId: bot.entity.id,
        actionId: cmd.on === false ? 'stop_sprinting' : 'start_sprinting',
        jumpBoost: 0
      });
      return { sprint: cmd.on !== false };
    case 'hotbar':
      bot.setQuickBarSlot(cmd.slot || 0);
      return { slot: bot.quickBarSlot };
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


# Um wie viel eine Box in einen Block ragen darf und trotzdem noch davor
# steht, in Bloecken. So rechnet der echte Client (VoxelShape.collideX/Y/Z).
KOLLISIONS_TOLERANZ = "1e-7"


def patch_physics(env):
    """Die Kollision von prismarine-physics so rechnen lassen wie der Client.

    Nach dem Anstossen setzt die Bibliothek die Position aus der Kante der
    Box zusammen, und an manchen Koordinaten ragt die Box danach um einen
    Rundungsfehler in den Block, etwa an einer Wand bei x=-2. Ihr Vergleich
    kennt keine Toleranz, haelt den Block damit fuer schon betreten und
    laesst den Bot im naechsten Tick hindurch. Der echte Client zieht 1e-7 ab
    und bleibt stehen - so auch der Bot nach diesem Flicken.
    """
    aabb_js = env.bot / "node_modules" / "prismarine-physics" / "lib" / "aabb.js"
    if not aabb_js.exists():
        FIND.problem("prismarine-physics/lib/aabb.js nicht gefunden - der Bot "
                     "laeuft womoeglich durch Bloecke")
        return False
    text = aabb_js.read_text(encoding="utf-8")
    if KOLLISIONS_TOLERANZ in text:
        return True
    patched = text
    for achse in "XYZ":
        patched = patched.replace(f"other.max{achse} <= this.min{achse}",
                                  f"other.max{achse} - {KOLLISIONS_TOLERANZ} <= this.min{achse}")
        patched = patched.replace(f"other.min{achse} >= this.max{achse}",
                                  f"other.min{achse} + {KOLLISIONS_TOLERANZ} >= this.max{achse}")
    if patched.count(KOLLISIONS_TOLERANZ) != 6:
        FIND.problem("prismarine-physics/lib/aabb.js hat nicht die erwarteten sechs "
                     "Vergleiche - der Bot laeuft womoeglich durch Bloecke")
        return False
    aabb_js.write_text(patched, encoding="utf-8")
    Log.detail(f"prismarine-physics rechnet die Kollision mit {KOLLISIONS_TOLERANZ} Toleranz")
    return True


def step_bot(env):
    Log.step("8. Bot (mineflayer)")
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
    ok = patch_mineflayer(env) and patch_physics(env)
    (env.bot / "bot.js").write_text(BOT_JS, encoding="utf-8")
    FIND.test("mineflayer bereit", ok, f"{MC_VERSION} / Protokoll {PROTOCOL_VERSION}")
    return ok


# ---------------------------------------------------------------------------
# 9. Tests im laufenden Spiel
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


# Die Sprachdatei, die das Plugin mitbringt und der Testserver benutzt, unter
# src/main/resources wie unter plugins/CamFly.
SPRACHDATEI = "lang/en.yml"

# Die Abschnitte, die nicht in der config.yml stehen, sondern in der
# Sprachdatei: die Texte und ihre Schalter.
SPRACH_ABSCHNITTE = ("messages", "message-settings")


def set_options(env, changes):
    """Mehrere Werte in der Konfiguration des Testservers setzen und einmal
    neu laden. Jede Aenderung ist (Schluessel, Wert) oder, wo der Name
    mehrdeutig ist, (Schluessel, Wert, Abschnitt). Was unter messages oder
    message-settings steht, kommt in die Sprachdatei, alles andere in die
    config.yml - fuer einen Text braucht es deshalb immer den Abschnitt."""
    data_dir = env.server / "plugins" / "CamFly"
    texte = {}
    for change in changes:
        key, value = change[0], change[1]
        section = change[2] if len(change) > 2 else None
        datei = SPRACHDATEI if section in SPRACH_ABSCHNITTE else "config.yml"
        if datei not in texte:
            texte[datei] = (data_dir / datei).read_text(encoding="utf-8")
        texte[datei], n = replace_option(texte[datei], key, value, section)
        if n != 1:
            where = f" unter {section}" if section else ""
            FIND.problem(f"{key} steht {n} Mal in {datei} auf dem Testserver{where}")
    for datei, text in texte.items():
        (data_dir / datei).write_text(text, encoding="utf-8")
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
    if body is None:
        FIND.test(f"Koerperstelle bekannt ({label})", False,
                  "keine serverseitige Position")
        return
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
    #
    # Hingestellt und nicht hingeflogen: Ein Flug bleibt an allem haengen,
    # was ihm im Weg steht - schon an der Grasschicht, wenn er auf einem
    # Trampelpfad losging, der 1/16 tiefer liegt. Dann stand der Bot noch
    # neben seinem Koerper, und jeder Trank traf beide. Gestellt wird er auf
    # die Hoehe eines ganzen Blocks, auch wenn er tiefer losging: Die Wolke
    # eines verweilenden Tranks liegt nur einen halben Block hoch ueber dem
    # Boden, und schwebte er darueber, ginge sie auch ohne das Plugin an ihm
    # vorbei. hinstellen und cam_off stehen weiter unten - gesucht werden sie
    # erst beim Aufruf.
    hinstellen(bot, body[0] + 12, _floor(body[1] + 0.5), body[2])
    weg = bot.server_pos()
    abstand = None if weg is None else math.dist(weg, body)
    # Doppelt so weit, wie ein Trank reicht.
    if not FIND.test(f"Der Bot steht zum Trankstest weit genug vom Koerper weg ({label})",
                     abstand is not None and abstand > 8,
                     f"{abstand:.1f} Bloecke" if abstand is not None
                     else "keine serverseitige Position"):
        cam_off(bot)
        return
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


# ---------------------------------------------------------------------------
# Was den Koerper getroffen hat
# ---------------------------------------------------------------------------

# Wie hoch ueber dem Koerper der Amboss losfaellt, in Bloecken. Vier Bloecke
# Fall machen sechs Schaden, und ein Amboss trifft erst, wenn er aufschlaegt.
AMBOSS_HOEHE = 4

# Wie lange nach einem Schaden kein /cam geht, in Sekunden: cam-safety.delay
# in der ausgelieferten config.yml.
CAM_SAFETY_SEKUNDEN = 5


def grund_checks(env, bot):
    """Die Meldung, mit der ein Treffer auf den Koerper den Cam-Modus beendet,
    sagt, was ihn getroffen hat.

    Ein fallender Amboss ist dabei kein Angriff, auch wenn er als fallender
    Block eine Entitaet ist: Die Meldung heisst "damaged by a falling anvil"
    und nicht mehr "attacked by FALLING_BLOCK". Ein Kaktus heisst Kaktus statt
    CONTACT. Ein Mob heisst, wie mob-names ihn nennt, und ein Mob mit eigenem
    Namen so, wie er heisst. Eine eigene Sprachdatei liefert die Namen, die
    sie hat; was ihr fehlt, kommt aus der englischen. Das gilt fuer einen
    Effekt genauso, geprueft an der Ablehnung beim Start, die ihn nennt.

    Der Amboss faellt wirklich auf den Koerper. Kaktus und Golem kommen ueber
    /damage: Ob ein echter Kaktus den Koerper piekst, haengt daran, wo der
    Koerper auf den Bruchteil eines Blocks genau steht, und einen Golem dann
    zuschlagen zu lassen, wann er soll, braucht einen ganzen Abschnitt, siehe
    rueckstoss_mob_checks. Das Plugin liest ohnehin nur die Schadensart des
    Treffers und wer hinter ihm steht, und beides setzt /damage genauso.

    Jeder Treffer geht an den Bot weiter. Resistenz 255 haelt ihn heil, und
    cam-safety ist solange aus - sonst ginge nach jedem Treffer fuenf Sekunden
    lang kein /cam.
    """
    if not FIND.test("Cam-Modus ist vor dem Test der Meldungen aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    golem = f"@e[tag={INTERACT_TAG},type=minecraft:iron_golem,limit=1]"
    sprachdatei = env.server / "plugins" / "CamFly" / "lang" / f"{TESTSPRACHE}.yml"

    def probe(name, treffen, erwartet):
        """Einmal /cam, den Bot vom Koerper wegstellen, den Koerper treffen und
        nachsehen, was im Chat steht. treffen bekommt die Stelle des Koerpers
        und einen Selektor fuer das Mannequin, das die Treffer nimmt."""
        if not FIND.test(f"/cam startet fuer die Meldung ({name})", cam_on(bot), ""):
            return
        koerper = bot.server_pos()
        if koerper is None:
            FIND.test(f"Koerperstelle bekannt ({name})", False, "keine serverseitige Position")
            cam_off(bot)
            return
        x, y, z = koerper
        # Weg vom Koerper, wie beim Trankstest: Der Amboss traefe sonst beide.
        hinstellen(bot, x - 6, _floor(y + 0.5), z)
        mannequin = f"@e[type=minecraft:mannequin,x={x},y={y},z={z},distance=..1,limit=1]"
        since = bot.mark()
        treffen(x, y, z, mannequin)
        told = bot.expect(erwartet, since, 8000)
        FIND.test(f"Die Meldung nennt {name}", bool(told),
                  strip_colors(told["text"]) if told else "keine Meldung im Chat")
        if not told:
            # Endete der Cam-Modus gar nicht, raeumt das hier ab.
            cam_off(bot)

    def amboss(x, y, z, mannequin):
        # CancelDrop: Er bleibt nicht als Block liegen, genau dort, wohin der
        # Bot beim Ende des Cam-Modus zurueckkommt.
        console(env, f"summon minecraft:falling_block {x} {y + AMBOSS_HOEHE} {z} "
                     '{BlockState:{Name:"minecraft:anvil"},CancelDrop:1b,'
                     "HurtEntities:1b,FallHurtAmount:2.0f,FallHurtMax:40}", pause=0)

    def schaden(art, von=None):
        def treffen(x, y, z, mannequin):
            console(env, f"damage {mannequin} 1 {art}" + (f" by {von}" if von else ""), pause=0)
        return treffen

    bot.chat(f"/effect give {BOT_NAME} minecraft:resistance infinite 255 true")
    time.sleep(0.5)
    set_option(env, "enabled", "false", "cam-safety")
    try:
        probe("den fallenden Amboss", amboss, "damaged by a falling anvil")
        probe("den Kaktus", schaden("minecraft:cactus"), "damaged by a cactus")

        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
        console(env, f"summon minecraft:iron_golem {heim[0] + 4} {heim[1]} {heim[2]} "
                     f'{{NoAI:1b,Silent:1b,PersistenceRequired:1b,Tags:["{INTERACT_TAG}"]}}', pause=0.5)
        probe("den Mob", schaden("minecraft:mob_attack", golem), "attacked by an iron golem")
        console(env, f'data merge entity {golem} {{CustomName:"Bob"}}', pause=0.3)
        probe("den Mob mit eigenem Namen", schaden("minecraft:mob_attack", golem), "attacked by Bob")

        # Eine eigene Sprache, mit nur zwei Namen darin.
        sprachdatei.write_text(
            'messages:\n  body-env-damage: "&cDer Koerper wurde durch {cause} verletzt."\n'
            'damage-names:\n  cactus: "einen Kaktus"\n'
            'effect-names:\n  slowness: "Langsamkeit"\n', encoding="utf-8")
        set_option(env, "language", TESTSPRACHE)
        probe(f"den Kaktus aus lang/{TESTSPRACHE}.yml", schaden("minecraft:cactus"),
              "durch einen Kaktus verletzt")
        probe("den Amboss aus der englischen Datei, wo die eigene keinen Namen hat", amboss,
              "durch a falling anvil verletzt")

        # Langsamkeit sperrt den Start und tut niemandem weh.
        bot.chat(f"/effect give {BOT_NAME} minecraft:slowness 30 0 true")
        time.sleep(0.5)
        since = bot.mark()
        bot.chat("/cam")
        hit = bot.expect("cannot start cam mode with", since, 8000)
        FIND.test(f"Ein Effekt heisst, wie lang/{TESTSPRACHE}.yml ihn nennt",
                  bool(hit) and "with Langsamkeit on you" in strip_colors(hit["text"]),
                  strip_colors(hit["text"]) if hit else "keine Ablehnung im Chat")
        if not hit:
            cam_off(bot)
        bot.chat(f"/effect clear {BOT_NAME} minecraft:slowness")
    finally:
        try:
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
            sprachdatei.unlink(missing_ok=True)
            set_options(env, [("language", "en"), ("enabled", "true", "cam-safety")])
            bot.chat(f"/effect clear {BOT_NAME}")
            hinstellen(bot, heim[0], heim[1], heim[2])
            # Der letzte Treffer hat die cam-safety-Sperre angeworfen, auch
            # waehrend sie aus war: Das Plugin merkt sich jeden Schaden. Sie
            # laeuft fuenf Sekunden, und der naechste Abschnitt faengt mit
            # /cam an - bis hierher sind erst gut drei vergangen.
            time.sleep(CAM_SAFETY_SEKUNDEN)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der Meldungen: {exc}")


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
# Meldungen einzeln abschalten
# ---------------------------------------------------------------------------

def message_switch_checks(env, bot):
    """Die Schalter der Action-Bar und der Startzeilen unter message-settings,
    in der Sprachdatei.

    actionbar-on und actionbar-off schalten die beiden Zeilen der Action-Bar
    einzeln ab, der Hauptschalter enabled nimmt beide mit. Ob der Cam-Modus
    dabei trotzdem laeuft, wird am Spielmodus gefragt: camera-on und
    camera-off sind in der ausgelieferten Datei aus, ohne die Action-Bar sagt
    das Plugin zu /cam also gar nichts.

    Ist actionbar-off aus, kommt beim Ende eine leere Zeile - ohne sie stuende
    "Cam mode activated" danach noch bis zu drei Sekunden da.

    Die Startzeilen erscheinen nur beim Serverstart. Geprueft wird deshalb
    nur, dass sie mit der Voreinstellung im Log stehen; zum Abschalten
    muesste der Server neu starten.

    Beide Schalter der Action-Bar heissen wie ihre Texte unter messages,
    die in derselben Datei stehen - gesetzt wird deshalb immer mit dem
    Abschnitt.
    """
    def cam():
        """Einmal /cam, und alles, was danach ankommt, ohne Farbcodes."""
        since = bot.mark()
        bot.chat("/cam")
        time.sleep(2.5)
        return [strip_colors(m["text"])
                for m in bot.call("messages", since=since).get("messages", [])]

    def said(lines, pattern):
        return any(re.search(pattern, line) for line in lines)

    def shown(lines):
        return "; ".join(line for line in lines if line) or "nichts"

    log = strip_colors(server_log(env))
    FIND.test("Voreingestellt stehen die Startzeilen im Log",
              "Loading skins..." in log
              and ("Skins loaded successfully." in log
                   or "Skins could not be preloaded at startup" in log),
              "")

    try:
        # Gegenprobe zur leeren Zeile weiter unten: Voreingestellt folgt auf die
        # Zeile zum Start die zum Ende, und geleert wird nichts. Kaeme hier
        # auch eine leere an, sagte die Probe dort nichts ueber das Plugin.
        cam()
        ende = cam()
        FIND.test("Gegenprobe: voreingestellt kommt beim Ende die Zeile und keine leere",
                  said(ende, r"[Cc]am mode ended") and "" not in ende, shown(ende))

        set_option(env, "actionbar-on", "false", "message-settings")
        start = cam()
        drin = spielmodus_ist(bot, "adventure")
        FIND.test("actionbar-on: false - /cam startet ohne Zeile in der Action-Bar",
                  drin and not said(start, r"[Cc]am mode activated"),
                  shown(start) if drin else "der Cam-Modus startete nicht")
        ende = cam()
        FIND.test("actionbar-on: false - die Zeile zum Ende kommt weiter",
                  said(ende, r"[Cc]am mode ended"), shown(ende))

        set_options(env, [("actionbar-on", "true", "message-settings"),
                          ("actionbar-off", "false", "message-settings")])
        start = cam()
        FIND.test("actionbar-off: false - die Zeile zum Start kommt weiter",
                  said(start, r"[Cc]am mode activated"), shown(start))
        ende = cam()
        draussen = not spielmodus_ist(bot, "adventure")
        FIND.test("actionbar-off: false - /cam endet ohne Zeile in der Action-Bar",
                  draussen and not said(ende, r"[Cc]am mode ended"),
                  shown(ende) if draussen else "der Cam-Modus lief weiter")
        FIND.test("actionbar-off: false - die Zeile zum Start wird geleert",
                  "" in ende, shown(ende))

        set_options(env, [("actionbar-off", "true", "message-settings"),
                          ("enabled", "false", "message-settings")])
        start = cam()
        drin = spielmodus_ist(bot, "adventure")
        ende = cam()
        FIND.test("enabled: false nimmt auch die Action-Bar mit",
                  drin and not said(start + ende, r"[Cc]am mode (activated|ended)"),
                  shown(start + ende) if drin else "der Cam-Modus startete nicht")
    finally:
        # Der Reload dabei holt ihn auch aus dem Cam-Modus, falls eine Probe
        # mittendrin abgebrochen ist.
        set_options(env, [("actionbar-on", "true", "message-settings"),
                          ("actionbar-off", "true", "message-settings"),
                          ("enabled", "true", "message-settings")])


# ---------------------------------------------------------------------------
# Die Sprache der Texte
# ---------------------------------------------------------------------------

# Die Sprache, die der Test neben der mitgelieferten anlegt. Ein Code, den es
# als echte Sprache nicht gibt - so steht er keiner Datei im Weg, die jemand
# auf dem Testserver von Hand angelegt hat.
TESTSPRACHE = "xx"


def language_checks(env, bot):
    """Die Sprachdatei, die language in der config.yml waehlt.

    Mitgeliefert wird nur lang/en.yml. Der Test legt daneben eine eigene
    Sprache an, mit einem einzigen Text: Alles andere muss aus der englischen
    Datei im Plugin kommen. Geprueft wird an den Zeilen der Action-Bar, die
    voreingestellt bei jedem /cam kommen - camera-on und camera-off sind in
    der ausgelieferten Datei aus.

    Dazu: Eine kaputte Sprachdatei laesst beim Reload alles, wie es war, auch
    die config.yml, die mit ihr zusammen gelesen wird. Eine Sprache ohne
    Datei wird gemeldet und faellt auf Englisch zurueck. Und stehen die alten
    Abschnitte noch in der config.yml, sagt das Plugin, dass es sie nicht mehr
    liest.

    Was set_options nicht kann, schreibt der Test selbst: die Sprachdatei,
    die es vorher nicht gibt, einen ganzen Abschnitt in der config.yml, und
    eine Aenderung, die der Bot neu laden soll statt der Konsole - nur dann
    kommt die Meldung ueber die kaputte Datei bei ihm im Chat an.
    """
    data_dir = env.server / "plugins" / "CamFly"
    sprachdatei = data_dir / "lang" / f"{TESTSPRACHE}.yml"
    config = data_dir / "config.yml"

    def cam():
        """Einmal /cam, und alles, was danach ankommt, ohne Farbcodes."""
        since = bot.mark()
        bot.chat("/cam")
        time.sleep(2.5)
        return [strip_colors(m["text"])
                for m in bot.call("messages", since=since).get("messages", [])]

    def said(lines, text):
        return any(text in line for line in lines)

    def shown(lines):
        return "; ".join(line for line in lines if line) or "nichts"

    config_vorher = config.read_text(encoding="utf-8")
    FIND.test("Voreingestellt steht language: en in der config.yml",
              re.search(r"(?m)^language:\s*en\s*$", config_vorher) is not None, "")
    FIND.test("Die englische Sprachdatei liegt auf dem Server",
              (data_dir / SPRACHDATEI).is_file(), "")

    try:
        # --- Eine eigene Sprache mit nur einem Text ---
        sprachdatei.write_text('messages:\n  actionbar-on: "&aKamera an"\n', encoding="utf-8")
        set_option(env, "language", TESTSPRACHE)
        start = cam()
        FIND.test(f"language: {TESTSPRACHE} - der Text kommt aus lang/{TESTSPRACHE}.yml",
                  said(start, "Kamera an") and not said(start, "Cam mode activated"), shown(start))
        ende = cam()
        FIND.test(f"language: {TESTSPRACHE} - was dort fehlt, kommt aus der englischen Datei",
                  said(ende, "Cam mode ended"), shown(ende))

        # --- Eine kaputte Sprachdatei: der Reload laesst alles, wie es war ---
        # In derselben Runde geht der Spielmodus auf creative. Bleibt er bei
        # adventure, ist auch die config.yml nicht uebernommen worden.
        sprachdatei.write_text('messages:\n  actionbar-on: "&aKamera kaputt\n', encoding="utf-8")
        config.write_text(re.sub(r"(?m)^(\s*gamemode:\s*).*$", r"\g<1>creative",
                                 config.read_text(encoding="utf-8")), encoding="utf-8")
        since = bot.mark()
        bot.chat("/cam reload")
        time.sleep(2.5)
        antwort = [strip_colors(m["text"])
                   for m in bot.call("messages", since=since).get("messages", [])]
        FIND.test("Eine kaputte Sprachdatei wird beim Reload gemeldet, mit Datei und Zeile",
                  said(antwort, f"has an error in lang/{TESTSPRACHE}.yml") and said(antwort, "line 2"),
                  shown(antwort))
        FIND.test("Der Reload meldet dann keinen Erfolg",
                  not said(antwort, "reload successful"), shown(antwort))
        start = cam()
        adventure = spielmodus_ist(bot, "adventure")
        FIND.test("Die bisherigen Texte gelten weiter", said(start, "Kamera an"), shown(start))
        FIND.test("Die config.yml aus derselben Runde wird auch nicht uebernommen", adventure,
                  "" if adventure else "der Cam-Modus laeuft schon in creative")
        cam()

        # --- Eine Sprache ohne Datei ---
        sprachdatei.unlink()
        config.write_text(config_vorher, encoding="utf-8")
        vorher = len(server_log(env))
        set_option(env, "language", "fr")
        neu = strip_colors(server_log(env)[vorher:])
        FIND.test("Eine Sprache ohne Datei wird gemeldet",
                  "Unknown value for language: 'fr'" in neu, "")
        start = cam()
        FIND.test("Sie faellt auf Englisch zurueck", said(start, "Cam mode activated"), shown(start))
        cam()

        # --- Die alten Abschnitte in der config.yml ---
        config.write_text(config_vorher + '\nmessages:\n  camera-on: "alt"\n', encoding="utf-8")
        vorher = len(server_log(env))
        console(env, "cam reload", pause=2)
        FIND.test("Texte, die noch in der config.yml stehen, werden als nicht mehr gelesen gemeldet",
                  'The sections "messages" and "message-settings" in config.yml are not read any more'
                  in strip_colors(server_log(env)[vorher:]), "")
    finally:
        # Der Reload holt ihn auch aus dem Cam-Modus, falls eine Probe
        # mittendrin abgebrochen ist.
        sprachdatei.unlink(missing_ok=True)
        config.write_text(config_vorher, encoding="utf-8")
        console(env, "cam reload", pause=2)


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
    bot.chat(f"/fill {bx + 1} {by} {bz - 1} {bx + 11} {by + 8} {bz + 8} minecraft:air")
    time.sleep(0.8)
    # Auch, was herumliegt: Der Abbau im Durchgang ohne Cam-Modus laesst eine
    # Blume fallen, und die zaehlte beim Klick auf den eigenen Koerper als
    # naechste Entitaet mit. Erst nach dem /fill: Der nimmt dem Hebel den
    # Stein unter ihm weg, und der Hebel faellt dabei als Item ab. Liegen
    # blieb er, wo der Sulfur-Cube-Test danach zuschlaegt - und ein Schlag auf
    # ein Item wirft den Bot vom Server ("Attempting to attack an invalid
    # entity").
    bot.chat("/kill @e[type=minecraft:item]")
    time.sleep(0.4)


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
# Der Sulfur Cube
# ---------------------------------------------------------------------------

# Der Wuerfel, an dem geprobt wird. Er hat einen Block geschluckt: Nur dann
# rollt er, wenn man hineinlaeuft, und nur dann fliegt er beim Schlag weg,
# statt ganz normal Schaden zu nehmen - den haelt der Interaktionsschutz
# ohnehin ab.
WUERFEL = f"@e[type=minecraft:sulfur_cube,tag={INTERACT_TAG},limit=1]"

# Ab welcher Strecke der Wuerfel als bewegt zaehlt, in Bloecken. Geschoben
# rollt er ein gutes Dutzend, geschlagen zweieinhalb.
WUERFEL_BEWEGT = 0.5

# Der Rueckstosswiderstand, den das Plugin dem Wuerfel gibt, solange eine
# Kamera ihn schieben koennte - als NBT-Pfad fuer /execute if data.
WUERFEL_FEST = ('attributes[{id:"minecraft:knockback_resistance"}]'
                '.modifiers[{id:"camfly:cam_no_push"}]')


def wuerfel_setzen(bot, x, y, z):
    """Einen frischen Sulfur Cube hinsetzen, mit Erde darin.

    Gibt zurueck, ob er wirklich einen Block traegt: Ohne ihn rollte er gar
    nicht erst, und jede Probe hiesse "nicht bewegt", ohne dass das Plugin
    etwas dazu getan haette.
    """
    bot.chat(f"/kill @e[type=minecraft:sulfur_cube,tag={INTERACT_TAG}]")
    time.sleep(0.5)
    bot.chat(f'/summon minecraft:sulfur_cube {x} {y} {z} '
             f'{{Tags:["{INTERACT_TAG}"],equipment:{{body:{{id:"minecraft:dirt",count:1}}}}}}')
    time.sleep(1.5)
    return server_says(bot, f"/execute if data entity {WUERFEL} equipment.body "
                            f"run say {{marke}}")


def wuerfel_pos(bot):
    """Wo der Wuerfel steht, vom Server gelesen."""
    return bot.call("server_pos", wait=20, timeout=8000, selector=WUERFEL).get("pos")


def wuerfel_fest(bot):
    """Ob der Wuerfel gerade den Rueckstosswiderstand des Plugins traegt."""
    return nbt_frage(bot, WUERFEL, WUERFEL_FEST)


def wuerfel_strecke(vorher, nachher):
    """Wie weit der Wuerfel gerollt ist, oder None, wenn eine Position fehlt."""
    if vorher is None or nachher is None:
        return None
    return math.dist(vorher, nachher)


def wuerfel_schieben(bot, base):
    """Einmal quer durch den Wuerfel fliegen und sagen, wie weit er rollte.

    Der Flug geht mitten durch ihn hindurch, einen halben Block alle 50 ms.
    Geschoben wird er dabei von der Beruehrung, nicht vom Zusammenstoss: Den
    nimmt der Cam-Modus ohnehin weg, und der Wuerfel rollte trotzdem - das ist
    der Fehler, um den es hier geht.
    """
    bx, by, bz = base
    if not wuerfel_setzen(bot, bx + 6.5, by, bz + 0.5):
        return None
    vorher = wuerfel_pos(bot)
    hinstellen(bot, bx + 3.5, by, bz + 0.5)
    bot.call("fly", wait=30, x=bx + 10.5, y=by, z=bz + 0.5, timeout=6000)
    # Er rollt noch eine Weile aus.
    time.sleep(2.0)
    return wuerfel_strecke(vorher, wuerfel_pos(bot))


def wuerfel_schlagen(bot, base):
    """Den Wuerfel einmal schlagen und sagen, wie weit er flog.

    Der Bot steht zweieinhalb Bloecke vor ihm: nah genug fuer den Schlag und
    zu weit, um ihn schon zu beruehren. So prueft die Probe den Schlag allein
    - aus der Naehe hielte ihn schon der Schutz gegen das Schieben fest.
    """
    bx, by, bz = base
    if not wuerfel_setzen(bot, bx + 6.5, by, bz + 0.5):
        return None
    vorher = wuerfel_pos(bot)
    hinstellen(bot, bx + 4.0, by, bz + 0.5)
    # Drei Bloecke: Der Koerper steht im Cam-Modus dreieinhalb hinter dem
    # Bot und darf nicht getroffen werden.
    klicken(bot, "attack_entity", radius=3)
    time.sleep(2.0)
    return wuerfel_strecke(vorher, wuerfel_pos(bot))


def wuerfel_entladen(bot, base):
    """Einen Wuerfel mit dem Widerstand darauf entladen und wieder laden.

    Der Widerstand wird mit /attribute von Hand aufgesetzt - so, wie ihn ein
    Absturz auf der Platte zurueckliesse. Der Wuerfel steht dazu weit weg in
    einem Chunk, den nur /forceload haelt; ohne das haelt ihn der Bot selbst
    geladen.

    Gibt zurueck: (trug er ihn vorher, ist er wieder da, traegt er ihn noch).
    """
    bx, by, bz = base
    fx, fz = bx + 160, bz + 160
    bot.chat(f"/forceload add {fx} {fz}")
    time.sleep(2.0)
    try:
        if not wuerfel_setzen(bot, fx + 0.5, by, fz + 0.5):
            return False, False, False
        bot.chat(f"/attribute {WUERFEL} minecraft:knockback_resistance modifier add "
                 f"camfly:cam_no_push 1024 add_value")
        time.sleep(0.8)
        vorher = wuerfel_fest(bot)
        bot.chat(f"/forceload remove {fx} {fz}")
        time.sleep(4.0)
        bot.chat(f"/forceload add {fx} {fz}")
        time.sleep(3.0)
        da = server_says(bot, f"/execute if entity {WUERFEL} run say {{marke}}")
        return vorher, da, da and wuerfel_fest(bot)
    finally:
        bot.chat(f"/kill @e[type=minecraft:sulfur_cube,tag={INTERACT_TAG}]")
        time.sleep(0.5)
        bot.chat(f"/forceload remove {fx} {fz}")
        time.sleep(0.5)


def sulfur_cube_checks(env, bot):
    """Den Sulfur Cube kann die Kamera weder schieben noch wegschlagen.

    Ein Wuerfel mit einem Block darin rollt, wenn ein Spieler hineinlaeuft,
    und fliegt weg, wenn er ihn schlaegt. Beides ging auch im Cam-Modus: Das
    Schieben kommt ohne jedes Event, und der Schlag macht ihm keinen Schaden,
    der sich abfangen liesse, sondern stoesst ihn nur weg.

    Das Plugin haelt beides auf zwei Wegen ab. Den Schlag faengt es am
    Rueckstoss ab. Das Schieben laesst sich nur ueber den Rueckstosswiderstand
    des Wuerfels aufhalten - den bekommt er, solange eine Kamera ihn beruehren
    koennte, und verliert ihn wieder, sobald keine mehr so nah ist.

    Jede Probe steht zweimal da, ohne Cam-Modus und darin. Ohne die
    Gegenprobe sagte "nicht bewegt" nur, dass sich nichts geruehrt hat - und
    das sagt sie auch dann, wenn der Flug oder der Schlag des Bots gar nicht
    erst ankommt.
    """
    if not FIND.test("Cam-Modus ist vor dem Sulfur-Cube-Test aus", cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Sulfur-Cube-Test lesbar", pos is not None, str(pos)):
        return
    base = (_floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2]))
    bx, by, bz = base
    Log.detail(f"Testplatz fuer den Sulfur Cube bei {base}")
    # Die Bahn freiraeumen, auf der er rollt: Geschoben kommt er ein gutes
    # Dutzend Bloecke weit, und die Testwelt bleibt zwischen zwei Laeufen
    # stehen.
    bahn = f"{bx + 1} {by} {bz - 1} {bx + 26} {by + 3} {bz + 2}"
    bot.chat(f"/fill {bahn} minecraft:air")
    time.sleep(0.8)

    try:
        # --- Erst ohne Cam-Modus: rollt er ueberhaupt? ---
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        geschoben_ohne = wuerfel_schieben(bot, base)
        geschlagen_ohne = wuerfel_schlagen(bot, base)

        # --- Und nun im Cam-Modus ---
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        if not FIND.test("/cam startet fuer den Sulfur-Cube-Test", cam_on(bot), ""):
            return
        geschoben_drin = wuerfel_schieben(bot, base)
        geschlagen_drin = wuerfel_schlagen(bot, base)

        # --- Der Widerstand: nur, solange die Kamera ihn beruehren koennte ---
        wuerfel_setzen(bot, bx + 6.5, by, bz + 0.5)
        hinstellen(bot, bx + 6.5, by, bz + 0.5)
        fest_drin = wuerfel_fest(bot)
        hinstellen(bot, bx + 6.5, by, bz + 4.5)
        fest_daneben = wuerfel_fest(bot)
        hinstellen(bot, bx + 6.5, by, bz + 0.5)
        noch_im_cam = spielmodus_ist(bot, "adventure")
        cam_off(bot)
        time.sleep(1.0)
        fest_danach = wuerfel_fest(bot)
        geladen = wuerfel_entladen(bot, base)

        # --- Die Ergebnisse gegenueberstellen ---
        for name, ohne, drin in (("wegschieben", geschoben_ohne, geschoben_drin),
                                 ("wegschlagen", geschlagen_ohne, geschlagen_drin)):
            gerollt = ohne is not None and ohne > WUERFEL_BEWEGT
            FIND.test(f"Gegenprobe: ohne Cam-Modus laesst sich ein Sulfur Cube {name}",
                      gerollt,
                      f"{ohne:.2f} Bloecke" if gerollt else
                      f"Strecke {ohne} - die Probe daneben sagt damit nichts")
            FIND.test(f"Im Cam-Modus laesst sich kein Sulfur Cube {name}",
                      drin is not None and drin <= WUERFEL_BEWEGT,
                      f"Strecke {drin}")
        FIND.test("Steckt die Kamera im Sulfur Cube, steht er fest", fest_drin,
                  "" if fest_drin else "kein Widerstand am Wuerfel")
        FIND.test("Vier Bloecke daneben ist der Sulfur Cube wieder frei", not fest_daneben,
                  "" if not fest_daneben else "der Widerstand blieb")
        FIND.test("Der Cam-Modus lief bei der Probe am Sulfur Cube weiter", noch_im_cam,
                  "" if noch_im_cam else "der Cam-Modus war vorher zu Ende")
        FIND.test("Nach dem Cam-Modus ist der Sulfur Cube wieder frei", not fest_danach,
                  "" if not fest_danach else "der Widerstand blieb")
        vorher, da, noch = geladen
        FIND.test("Gegenprobe: /attribute setzt den Widerstand von Hand", vorher, "")
        FIND.test("Ein Sulfur Cube, mit dem Widerstand entladen, kommt ohne ihn wieder",
                  da and not noch,
                  "" if da and not noch else
                  ("der Wuerfel kam nicht wieder" if not da else "der Widerstand blieb"))
    finally:
        try:
            cam_off(bot)
            bot.chat(f"/kill @e[type=minecraft:sulfur_cube,tag={INTERACT_TAG}]")
            time.sleep(0.5)
            hinstellen(bot, bx + 0.5, by, bz + 0.5)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Sulfur-Cube-Test: {exc}")


# ---------------------------------------------------------------------------
# Die Ruestung im Cam-Modus
# ---------------------------------------------------------------------------

def armor_checks(env, bot):
    """Die Ruestung: im Cam-Modus abgelegt, danach wieder angezogen.

    Das Plugin nimmt dem Spieler beim Start das ganze Inventar ab und gibt es
    ihm beim Aussteigen zurueck. Die Ruestung reist dabei in denselben Slots
    mit - einen eigenen Weg fuer sie gibt es nicht. Diese Probe haelt fest,
    dass sie das auch wirklich tut: Der Interaktionstest weiter oben prueft das
    Inventar nur an einem Stein in der Hand.

    Gefragt wird serverseitig mit /execute if items, am Brustpanzer.
    """
    frage = ("/execute if items entity @s armor.chest minecraft:iron_chestplate "
             "run say {marke}")
    if not FIND.test("Cam-Modus ist vor dem Ruestungstest aus", cam_off(bot), ""):
        return
    try:
        bot.chat(f"/clear {BOT_NAME}")
        time.sleep(0.5)
        bot.chat(f"/item replace entity {BOT_NAME} armor.chest "
                 f"with minecraft:iron_chestplate")
        time.sleep(0.8)
        if not FIND.test("Der Bot traegt fuer den Ruestungstest einen Brustpanzer",
                         server_says(bot, frage), ""):
            return
        if not FIND.test("/cam startet fuer den Ruestungstest", cam_on(bot), ""):
            return
        abgelegt = not server_says(bot, frage)
        FIND.test("Im Cam-Modus ist die Ruestung abgelegt", abgelegt,
                  "" if abgelegt else "er traegt den Brustpanzer noch")
        cam_off(bot)
        time.sleep(1)
        wieder = server_says(bot, frage)
        FIND.test("Nach dem Cam-Modus ist die Ruestung wieder angezogen", wieder,
                  "" if wieder else "der Brustpanzer fehlt")
    finally:
        try:
            bot.chat(f"/clear {BOT_NAME}")
            time.sleep(0.5)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Ruestungstest: {exc}")


# ---------------------------------------------------------------------------
# Der Rueckstoss eines Treffers auf den Koerper
# ---------------------------------------------------------------------------

# Wie hoch der Bot im Cam-Modus ueber seinem Koerper wartet. TNT reicht acht
# Bloecke weit; zehn Bloecke darueber erreicht ihn weder die Explosion noch
# ein Geschoss, getroffen wird allein der Koerper.
RUECKSTOSS_HOEHE = 10

# Wie weit der Landeplatz im Cam-Modus von dem ohne Cam-Modus abweichen darf,
# in Bloecken. Gemessen liegen beide auf ein Zehntausendstel beieinander: Die
# Geschwindigkeit kommt in 26.x als lpVec3 und ist damit ein wenig gerundet,
# der Stoss einer Explosion ohne Cam-Modus nicht.
RUECKSTOSS_TOLERANZ = 0.05

# Fuer eine Windkugel mehr: Sie explodiert, wo sie den Koerper trifft, und
# trifft im Cam-Modus den Ruestungsstaender - 0,5 Bloecke breit statt 0,6 wie
# ein Spieler. Die Explosion sitzt damit 0,05 Bloecke naeher, ihr Stoss geht
# ein wenig steiler: Gemessen landet der Spieler bis zu 0,09 Bloecke anders.
RUECKSTOSS_TOLERANZ_WINDKUGEL = 0.15

# Wie weit die Platte aus Obsidian um das Ziel herum reicht. TNT risse die
# Grasschicht sonst auf, und in der Grube stuende der Bot bei der naechsten
# Probe tiefer.
RUECKSTOSS_PLATTE = 10


def rueckstoss_proben(x, y, z):
    """Was den Bot oder seinen Koerper trifft, alles von Osten her: Die Fuesse
    des Ziels stehen bei x, y, z, der Stoss geht nach Westen. Jede Probe traegt
    die Marke, ein liegengebliebener Pfeil oder Dreizack wird damit am Ende
    weggeraeumt."""
    marke = f'Tags:["{INTERACT_TAG}"]'
    flug = "Motion:[-2.0d,0.0d,0.0d]"
    bogen = ('weapon:{id:"minecraft:bow",count:1,components:'
             '{"minecraft:enchantments":{"minecraft:punch":2}}}')
    # Langsam und ohne Beschleunigung: Schneller fliegt eine Windkugel durch
    # einen Spieler hindurch, ohne ihn zu treffen.
    wind = "Motion:[-0.3d,0.0d,0.0d],acceleration_power:0.0d"
    return [
        ("Pfeil", f"summon minecraft:arrow {x + 4} {y + 1} {z} {{{flug},{marke}}}"),
        ("Pfeil mit Schlag II",
         f"summon minecraft:arrow {x + 4} {y + 1} {z} {{{flug},{bogen},{marke}}}"),
        ("Dreizack", f"summon minecraft:trident {x + 4} {y + 1} {z} {{{flug},{marke}}}"),
        ("TNT", f"summon minecraft:tnt {x + 2.5} {y} {z} {{fuse:1,{marke}}}"),
        ("Windkugel", f"summon minecraft:wind_charge {x + 4} {y + 1} {z} {{{wind},{marke}}}"),
        ("Windkugel eines Breeze",
         f"summon minecraft:breeze_wind_charge {x + 4} {y + 1} {z} {{{wind},{marke}}}"),
    ]


def rueckstoss_toleranz(name):
    """Wie weit die beiden Landeplaetze dieser Probe auseinander liegen duerfen."""
    return RUECKSTOSS_TOLERANZ_WINDKUGEL if name.startswith("Windkugel") else RUECKSTOSS_TOLERANZ


def rueckstoss_schlaege(env, schlaeger, x, y, z):
    """Was der zweite Spieler mit der Hand, dem Schwert, dem Speer und dem
    Streitkolben austeilt, alles von Osten her: Er steht oestlich des Ziels
    und sieht nach Westen, der Stoss geht nach Westen.

    Jeder Eintrag ist ein Name, die Vorbereitung - Waffe in die Hand, an
    seinen Platz und warten, bis er wieder voll ausgeholt hat - und der Schlag
    selbst. Geschlagen wird, was dem Ziel am naechsten steht: ohne Cam-Modus
    der Bot, im Cam-Modus sein Koerper. Der Streitkolben schlaegt ein Schwein
    daneben und stoesst das Ziel nur weg.
    """
    marke = f'Tags:["{INTERACT_TAG}"]'

    def bereit(waffe, abstand, warten, hilfe=None):
        def vorbereiten():
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
            # Gleich in das erste Fach und nicht mit give: Was er zwischen
            # clear und give aufhebt, laege sonst dort, und er schluege damit.
            console(env, f"clear {ZUSCHAUER_NAME}", pause=0.3)
            if waffe:
                console(env, f"item replace entity {ZUSCHAUER_NAME} hotbar.0 with {waffe}", pause=0.5)
            schlaeger.call("hotbar", slot=0)
            console(env, f"tp {ZUSCHAUER_NAME} {x + abstand} {y} {z} 90 0", pause=0.3)
            if hilfe:
                console(env, hilfe, pause=0.3)
            # Ein Wechsel der Waffe faengt das Ausholen von vorn an.
            time.sleep(warten)
        return vorbereiten

    def aufs_ziel(art="attack_entity"):
        return schlaeger.call(art, wait=20, radius=1.5, at=[x, y, z])

    def sprinten():
        schlaeger.call("sprint", on=True)

    def sprintschlag():
        sprinten()
        return aufs_ziel()

    def halber_sprintschlag():
        # Der erste Schlag geht voll ausgeholt auf einen Ruestungsstaender
        # daneben, der zweite gleich hinterher - mit dem Schwert ist der
        # dann erst halb ausgeholt. Ein voller Sprintschlag stoppt den Sprint
        # auf dem Server, deshalb wird vor dem zweiten neu gesprintet.
        sprinten()
        schlaeger.call("attack_entity", wait=20, radius=0.8, at=[x + 2, y, z + 1.5])
        sprinten()
        return aufs_ziel()

    def schwungschlag():
        return schlaeger.call("attack_entity", wait=20, radius=0.8, at=[x + 1.2, y, z])

    def streitkolben():
        # Von oben auf das Schwein neben dem Ziel: Nach gut 1,5 Bloecken Fall
        # schlaegt der Streitkolben auf und stoesst alles drumherum weg - ohne
        # es zu verletzen. Frueher kaeme der Schlag ohne Fall, spaeter erst
        # nach der Landung oder ausser Reichweite.
        console(env, f"tp {ZUSCHAUER_NAME} {x + 2.4} {y + 3.4} {z} 90 70", pause=0)
        time.sleep(0.3)
        return schlaeger.call("attack_entity", wait=20, radius=1.0, at=[x + 2, y, z], aim=0.4)

    # Ohne Beute: Ein getoetetes Schwein liesse sonst Fleisch fallen.
    schwein = (f"summon minecraft:pig {x + 1.2} {y} {z} {{NoAI:1b,{marke},Health:1000.0f,"
               f'DeathLootTable:"minecraft:empty",'
               f'attributes:[{{id:"minecraft:max_health",base:1000.0}}]}}')
    schwein_daneben = schwein.replace(f"{x + 1.2} {y} {z}", f"{x + 2} {y} {z}")
    staender = f"summon minecraft:armor_stand {x + 2} {y} {z + 1.5} {{{marke}}}"
    return [
        ("Schlag eines Spielers", bereit(None, 2, 1.0), aufs_ziel),
        ("Sprintschlag", bereit(None, 2, 1.0), sprintschlag),
        ("Halb ausgeholter Sprintschlag",
         bereit("minecraft:iron_sword", 2, 1.0, staender), halber_sprintschlag),
        ("Schwert mit Rueckstoss II",
         bereit("minecraft:iron_sword[enchantments={knockback:2}]", 2, 1.0), aufs_ziel),
        ("Schwungschlag", bereit("minecraft:iron_sword", 2.8, 1.0, schwein), schwungschlag),
        ("Speerstich", bereit("minecraft:iron_spear", 3, 2.0), lambda: aufs_ziel("stab")),
        ("Speerstich mit Rueckstoss II",
         bereit("minecraft:iron_spear[enchantments={knockback:2}]", 3, 2.0), lambda: aufs_ziel("stab")),
        ("Streitkolben", bereit("minecraft:mace", 4, 2.0, schwein_daneben), streitkolben),
    ]


def rueckstoss_wie(name):
    """Wie der Schlag den Koerper trifft, fuer die Pruefzeile."""
    return "neben dem Koerper" if name == "Streitkolben" else "auf den Koerper"


def rueckstoss_lauf(env, bot, ziel, cam, treffen):
    """Einmal treffen lassen und zusehen, wohin es den Bot traegt.

    Ohne Cam-Modus steht er selbst am Ziel. Im Cam-Modus steht dort sein
    Koerper, und er wartet hoch darueber: Der Treffer beendet den Cam-Modus,
    setzt ihn an den Koerper und gibt ihm den Stoss mit. `treffen` loest den
    Treffer aus.

    Gibt zurueck, wo er liegen bleibt, vom Ziel aus gerechnet, und die erste
    Geschwindigkeit, die er bekommt - oder None, wenn der Cam-Modus gar nicht
    erst startete. Dazu, fuer eine Probe, die durchfaellt: wo sein Flug
    anfing und wie hoch er ging, beides vom Ziel aus, und wie viele Pakete
    mit einer Geschwindigkeit kamen.
    """
    x, y, z = ziel
    hinstellen(bot, x, y, z)
    if cam:
        if not cam_on(bot):
            return None
        bot.call("fly", wait=30, dy=RUECKSTOSS_HOEHE, timeout=15000)
        time.sleep(1.0)
    anfang = bot.call("knock_start", nachTeleport=cam).get("pos")
    treffen()
    time.sleep(3.0)
    antwort = bot.call("knock_stop")
    log = antwort["log"]
    ende = antwort["pos"]
    erste = log["velocity"][0]["v"] if log["velocity"] else None
    explosion = next((e["knockback"] for e in log["explosion"] if e["knockback"]), None)
    beendet = not cam or not spielmodus_ist(bot, "adventure")
    if not beendet:
        cam_off(bot)
    # Im Cam-Modus faengt der Flug erst dort an, wo das Ende des Cam-Modus
    # ihn hinsetzt - davor wartet er hoch ueber dem Koerper.
    pfad = log["path"]
    if cam:
        versetzt = log["teleport"][0] if log["teleport"] else None
        anfang = versetzt and versetzt["pos"]
        pfad = pfad[versetzt["schritt"]:] if versetzt else []
    return {"weg": [ende[0] - x, ende[1] - y, ende[2] - z],
            "erste": erste or explosion, "beendet": beendet,
            "anfang": None if anfang is None else [anfang[0] - x, anfang[1] - y, anfang[2] - z],
            "hoehe": max((p[1] for p in pfad), default=y) - y,
            "pakete": len(log["velocity"])}


def _zahlen(werte):
    return "-" if werte is None else "(" + ", ".join(f"{w:.4f}" for w in werte) + ")"


def rueckstoss_vergleich(name, ohne, mit, wie="auf den Koerper", gegenprobe=True, toleranz=RUECKSTOSS_TOLERANZ):
    """Ob der Treffer auf den Koerper den Spieler dorthin stoesst, wo derselbe
    Treffer ihn ohne Cam-Modus hinstoesst.

    Verglichen wird, wo er liegen bleibt: Darin steckt alles - wohin der Stoss
    geht, wie weit er traegt und wie hoch er hebt. Die erste Geschwindigkeit
    steht zum Nachlesen dabei. Die Gegenprobe ohne Cam-Modus steht nur beim
    ersten Vergleich mit ihr als eigene Probe da.
    """
    weg_ohne = ohne is not None and ohne["weg"][0] < -0.3
    if gegenprobe:
        FIND.test(f"{name} ohne Cam-Modus stoesst den Bot weg (Gegenprobe)", weg_ohne,
                  "" if ohne is None else f"Weg {_zahlen(ohne['weg'])}")
    if not weg_ohne:
        return
    if not FIND.test(f"{name} {wie} beendet den Cam-Modus",
                     mit is not None and mit["beendet"], ""):
        return
    gleich = max(abs(a - b) for a, b in zip(ohne["weg"], mit["weg"])) <= toleranz
    erklaerung = (f"Weg ohne {_zahlen(ohne['weg'])}, mit {_zahlen(mit['weg'])}; "
                  f"erste Geschwindigkeit ohne {_zahlen(ohne['erste'])}, mit {_zahlen(mit['erste'])}")
    if not gleich:
        # Woran es lag: Fing der Flug woanders an, stiess er oben an, kam noch
        # ein Stoss hinterher?
        erklaerung += (f"; Anfang ohne {_zahlen(ohne['anfang'])}, mit {_zahlen(mit['anfang'])}; "
                       f"hoechster Punkt ohne {ohne['hoehe']:.4f}, mit {mit['hoehe']:.4f}; "
                       f"Pakete mit Geschwindigkeit ohne {ohne['pakete']}, mit {mit['pakete']}")
    FIND.test(f"{name} {wie} stoesst den Spieler wie ohne Cam-Modus", gleich, erklaerung)


def rueckstoss_checks(env, bot):
    """Der Rueckstoss eines Treffers auf den Koerper.

    Jeder Treffer auf den Koerper geht an den Spieler weiter, und mit ihm der
    Stoss - so, wie derselbe Treffer ihn ohne Cam-Modus gestossen haette. Jede
    Probe steht deshalb zweimal da: einmal trifft es den Bot selbst, einmal
    seinen Koerper, und am Ende muss er beide Male an derselben Stelle liegen.

    Geprueft werden ein Pfeil, ein Pfeil aus einem Bogen mit Schlag II, ein
    geworfener Dreizack, TNT und was ein zweiter Spieler austeilt: ein
    Schlag, ein Sprintschlag voll und halb ausgeholt, ein Schwert mit
    Rueckstoss II, ein Schwungschlag, der den Koerper neben seinem Ziel
    trifft, ein Speerstich, auch mit Rueckstoss II, und ein Streitkolben, der
    neben dem Koerper aufschlaegt und ihn ohne Schaden wegstoesst - alles von
    Osten her. Dazu zwei Windkugeln, die mit ihrem Treffer explodieren, und
    Pfeil, TNT, Windkugel, Sprintschlag und Speerstich noch einmal mit
    damage-mode: false - dort gibt das Plugin den Stoss ganz von Hand weiter.

    Der Bot traegt Resistenz 255: Jeder Treffer landet und stoesst, aber
    keiner verletzt ihn. cam-safety ist fuer die Dauer des Abschnitts aus,
    sonst ginge nach jedem Treffer fuenf Sekunden lang kein /cam.
    """
    if not FIND.test("Cam-Modus ist vor dem Rueckstosstest aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    bx, bz = _floor(heim[0]), _floor(heim[2])
    ziel = (bx + 0.5, BODEN_Y + 1, bz + 0.5)
    x, y, z = ziel
    weit = RUECKSTOSS_PLATTE
    bot.chat(f"/fill {bx - weit} {BODEN_Y} {bz - weit} {bx + weit} {BODEN_Y} {bz + weit} "
             f"minecraft:obsidian")
    bot.chat("/gamemode survival")
    bot.chat(f"/effect give {BOT_NAME} minecraft:resistance infinite 255 true")
    time.sleep(0.5)
    set_option(env, "enabled", "false", "cam-safety")
    damage_mode_umgestellt = False
    try:
        ohne_cam = {}
        for name, kommando in rueckstoss_proben(x, y, z):
            def treffen(k=kommando):
                console(env, k, pause=0)
            ohne_cam[name] = rueckstoss_lauf(env, bot, ziel, False, treffen)
            mit = rueckstoss_lauf(env, bot, ziel, True, treffen)
            rueckstoss_vergleich(name, ohne_cam[name], mit, toleranz=rueckstoss_toleranz(name))

        # Die Schlaege eines zweiten Spielers, von Osten her. Liegengebliebene
        # Geschosse raeumt der Test vorher weg: Ein Schlag trifft die naechste
        # Entitaet, und das waere sonst womoeglich ein Pfeil im Boden.
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.5)
        schlaeger = BotClient(env, name=ZUSCHAUER_NAME)
        try:
            schlaeger.start()
            if FIND.test("Zweiter Spieler fuer die Schlaege kommt herein",
                         schlaeger.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
                time.sleep(1.5)
                console(env, f"gamemode survival {ZUSCHAUER_NAME}", pause=0.3)
                schlaege = rueckstoss_schlaege(env, schlaeger, x, y, z)
                for name, vorbereiten, schlagen in schlaege:
                    for cam in (False, True):
                        vorbereiten()
                        lauf = rueckstoss_lauf(env, bot, ziel, cam, schlagen)
                        schlaeger.call("sprint", on=False)
                        if cam:
                            rueckstoss_vergleich(name, ohne_cam[name], lauf, rueckstoss_wie(name))
                        else:
                            ohne_cam[name] = lauf

                # Ohne Schaden gibt das Plugin den Stoss ganz von Hand weiter -
                # den eines Sprints und den eines Stichs, der mit seinem
                # Schaden gar keinen traegt, eingeschlossen.
                set_option(env, "damage-mode", "false")
                damage_mode_umgestellt = True
                for name, vorbereiten, schlagen in schlaege:
                    if name not in ("Sprintschlag", "Speerstich"):
                        continue
                    vorbereiten()
                    mit = rueckstoss_lauf(env, bot, ziel, True, schlagen)
                    schlaeger.call("sprint", on=False)
                    rueckstoss_vergleich(name, ohne_cam[name], mit, "auf den Koerper mit damage-mode: false",
                                         gegenprobe=False)
        finally:
            schlaeger.stop()

        # Ohne Schaden gibt das Plugin den Stoss ganz von Hand weiter.
        if not damage_mode_umgestellt:
            set_option(env, "damage-mode", "false")
            damage_mode_umgestellt = True
        for name, kommando in rueckstoss_proben(x, y, z):
            if name not in ("Pfeil", "TNT", "Windkugel"):
                continue
            def treffen(k=kommando):
                console(env, k, pause=0)
            mit = rueckstoss_lauf(env, bot, ziel, True, treffen)
            rueckstoss_vergleich(name, ohne_cam[name], mit, "auf den Koerper mit damage-mode: false",
                                 gegenprobe=False, toleranz=rueckstoss_toleranz(name))
    finally:
        try:
            if damage_mode_umgestellt:
                set_option(env, "damage-mode", "mirror")
            set_option(env, "enabled", "true", "cam-safety")
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.5)
            bot.chat(f"/effect clear {BOT_NAME}")
            boden_ebnen(bot)
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Rueckstosstest: {exc}")


# ---------------------------------------------------------------------------
# Was Mobs beim Zuschlagen selbst stossen
# ---------------------------------------------------------------------------

def rueckstoss_erste_vergleich(name, ohne, mit, nur_hoehe=False):
    """Ob die erste Geschwindigkeit nach dem Treffer auf den Koerper die ist,
    die derselbe Treffer ohne Cam-Modus bringt. Fuer Mobs, die danach weiter
    zuschlagen: Wo der Bot liegen bleibt, sagt dort nichts mehr.

    Mit nur_hoehe zaehlt allein, wie hoch sie geht. Zur Seite haengt sie dann
    davon ab, wo der Mob beim Schlag steht und ob er den Bot vorher schon
    angerempelt hat - den Koerper rempelt auf Stufe 1 niemand an.
    """
    erste_ohne = ohne and ohne["erste"]
    if not FIND.test(f"{name} ohne Cam-Modus stoesst den Bot (Gegenprobe)", bool(erste_ohne),
                     "" if ohne is None else f"erste Geschwindigkeit {_zahlen(erste_ohne)}"):
        return
    if not FIND.test(f"{name} auf den Koerper beendet den Cam-Modus",
                     mit is not None and mit["beendet"] and bool(mit["erste"]), ""):
        return
    erste_mit = mit["erste"]
    if nur_hoehe:
        abstand = abs(erste_ohne[1] - erste_mit[1])
    else:
        abstand = max(abs(a - b) for a, b in zip(erste_ohne, erste_mit))
    FIND.test(f"{name} auf den Koerper stoesst den Spieler wie ohne Cam-Modus",
              abstand <= RUECKSTOSS_TOLERANZ_ERSTE,
              f"erste Geschwindigkeit ohne {_zahlen(erste_ohne)}, mit {_zahlen(erste_mit)}")


# Wie weit die erste Geschwindigkeit nach einem Mob-Treffer abweichen darf.
# Gemessen stimmen beide auf ein Zehntausendstel.
RUECKSTOSS_TOLERANZ_ERSTE = 0.01

# Wie weit oestlich des Ziels der Waerter in seinem Kaefig steht, in Bloecken:
# zu weit, um zuzuschlagen, nah genug fuer den Schallstoss, der 15 weit reicht.
WAERTER_ABSTAND = 8

# Wie lange auf den Schallstoss gewartet wird, in Sekunden. Gereizt haelt der
# Waerter ihn zehn Sekunden zurueck und laedt dann noch 1,7 Sekunden auf.
WAERTER_WARTEN = 13.5


def mob_lauf(env, bot, ziel, cam, vorbereiten, treffen):
    """Ein Lauf von rueckstoss_lauf mit einem frischen Mob - und noch einer,
    wenn er im ganzen Zeitfenster nicht zugeschlagen hat. Wann ein Mob
    zuschlaegt, entscheidet er am Ende selbst."""
    lauf = None
    for _ in range(2):
        vorbereiten()
        lauf = rueckstoss_lauf(env, bot, ziel, cam, treffen)
        if lauf is not None and lauf["erste"]:
            break
    return lauf


def rueckstoss_mob_checks(env, bot):
    """Was ein Mob beim Zuschlagen selbst stoesst, auf den Koerper wie ohne
    Cam-Modus.

    Geprueft werden ein Wuestenzombie mit einem Schwert mit Rueckstoss II, der
    mit dem Koerper nach Sueden steht und mit dem Kopf zum Ziel sieht - der
    Server stoesst entlang des Koerpers, Bukkit zeigt nur den Kopf -, ein
    Eisengolem, der hochwirft, und der Schallstoss eines Waerters, der den
    Spieler weit wegschleudert. Alle drei schlagen nach dem ersten Treffer
    weiter zu, verglichen wird deshalb die erste Geschwindigkeit; beim Golem
    nur, wie hoch sie geht - zur Seite haengt sie davon ab, wo er beim Schlag
    steht und ob er den Bot vorher angerempelt hat. Eine rammende Ziege fehlt:
    Sie sucht sich ihr Ziel selbst und nahm den Koerper im Cam-Modus nicht
    immer, siehe TESTUMGEBUNG.md.

    Feindliche Mobs gibt es erst ab easy, und den Koerper nehmen sie nur mit
    body.mob-target ins Ziel. Zombie, Golem und Waerter reizt ein /damage:
    ohne Cam-Modus vom Bot aus, im Cam-Modus vom Koerper aus. Der Golem daechte
    sonst gar nicht an den Koerper und saehe den Kamera-Spieler nicht, und der
    Zombie suchte sich sein Ziel erst irgendwann - womoeglich nachdem er sich
    schon umgedreht hat.

    Der Waerter steht in einem Kaefig aus Barrieren, weiter weg, als er
    zuschlagen kann: So bleibt ihm nur der Schallstoss, und der geht durch
    Waende. Er kommt erst nach dem Start des Cam-Modus dazu - seine Dunkelheit
    liesse /cam sonst nicht starten, start-with-effects steht auf positive.
    """
    if not FIND.test("Cam-Modus ist vor dem Mob-Rueckstosstest aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    bx, bz = _floor(heim[0]), _floor(heim[2])
    ziel = (bx + 0.5, BODEN_Y + 1, bz + 0.5)
    x, y, z = ziel
    weit = RUECKSTOSS_PLATTE
    marke = f'Tags:["{INTERACT_TAG}"]'
    mob = f"@e[tag={INTERACT_TAG},limit=1]"
    bot.chat(f"/fill {bx - weit} {BODEN_Y} {bz - weit} {bx + weit} {BODEN_Y} {bz + weit} "
             f"minecraft:obsidian")
    bot.chat("/gamemode survival")
    bot.chat(f"/effect give {BOT_NAME} minecraft:resistance infinite 255 true")
    time.sleep(0.5)
    set_options(env, [("enabled", "false", "cam-safety"), ("mob-target", "vanilla", "body")])
    console(env, "difficulty easy", pause=0.5)

    def mob_hin(befehl):
        def vorbereiten():
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
            console(env, befehl, pause=0.5)
        return vorbereiten

    def loslassen(sekunden, reizen=None, schaden="minecraft:mob_attack"):
        def treffen():
            console(env, f"data merge entity {mob} {{NoAI:0b}}", pause=0)
            if reizen:
                console(env, f"damage {mob} 0.5 {schaden} by {reizen}", pause=0)
            time.sleep(sekunden)
            console(env, f"data merge entity {mob} {{NoAI:1b}}", pause=0)
        return treffen

    zombie = mob_hin(f"summon minecraft:husk {x + 1.1} {y} {z} {{NoAI:1b,Rotation:[0f,0f],{marke},"
                     f"PersistenceRequired:1b,Silent:1b,drop_chances:{{mainhand:0.0f}},"
                     f'equipment:{{mainhand:{{id:"minecraft:iron_sword",components:'
                     f'{{"minecraft:enchantments":{{"minecraft:knockback":2}}}}}}}}}}')
    golem = mob_hin(f"summon minecraft:iron_golem {x + 2.2} {y} {z} {{NoAI:1b,Rotation:[90f,0f],{marke},"
                    f"PersistenceRequired:1b,Silent:1b}}")
    koerper = "@e[type=minecraft:mannequin,sort=nearest,limit=1]"

    wx = bx + WAERTER_ABSTAND
    kaefig = f"{wx - 1} {y} {bz - 1} {wx + 1} {y + 3} {bz + 1}"

    def waerter_kaefig():
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
        console(env, f"effect clear {BOT_NAME} minecraft:darkness", pause=0.3)
        console(env, f"fill {kaefig} minecraft:barrier", pause=0.3)
        console(env, f"fill {wx} {y} {bz} {wx} {y + 2} {bz} minecraft:air", pause=0.3)

    def waerter_reizen(reizen):
        def treffen():
            waerter_hin(env, wx + 0.5, y, bz + 0.5)
            console(env, f"damage {mob} 0.5 minecraft:mob_attack by {reizen}", pause=0)
            time.sleep(WAERTER_WARTEN)
        return treffen

    # Den Zombie reizt ein Schaden ohne Stoss: So schlaegt er sofort zu, noch
    # ehe er einen Schritt tut und sich mit dem Koerper zum Ziel dreht.
    try:
        for name, vorbereiten, ohne_treffen, mit_treffen, vergleich in (
                ("Wuestenzombie mit Rueckstoss II, nach Sueden gedreht", zombie,
                 loslassen(1.5, BOT_NAME, "minecraft:generic"), loslassen(1.5, koerper, "minecraft:generic"),
                 rueckstoss_erste_vergleich),
                ("Eisengolem", golem, loslassen(4.0, BOT_NAME), loslassen(4.0, koerper),
                 lambda n, o, m: rueckstoss_erste_vergleich(n, o, m, nur_hoehe=True)),
                ("Schallstoss eines Waerters", waerter_kaefig, waerter_reizen(BOT_NAME), waerter_reizen(koerper),
                 rueckstoss_erste_vergleich)):
            ohne = mob_lauf(env, bot, ziel, False, vorbereiten, ohne_treffen)
            mit = mob_lauf(env, bot, ziel, True, vorbereiten, mit_treffen)
            vergleich(name, ohne, mit)
    finally:
        try:
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.5)
            console(env, f"fill {kaefig} minecraft:air", pause=0.3)
            console(env, "difficulty peaceful", pause=0.3)
            set_options(env, [("enabled", "true", "cam-safety"), ("mob-target", "false", "body")])
            bot.chat(f"/effect clear {BOT_NAME}")
            boden_ebnen(bot)
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Mob-Rueckstosstest: {exc}")


# ---------------------------------------------------------------------------
# Der Waerter und body.mob-target
# ---------------------------------------------------------------------------

# Wie lange nach dem Reiz auf den Angriff eines freien Waerters gewartet wird,
# in Sekunden. Ist er ueber den Koerper wuetend, bruellt er erst gut vier
# Sekunden lang und schlaegt dann zu.
WAERTER_ZIEL_WARTEN = 10.0


def waerter_hin(env, x, y, z):
    """Einen Waerter mit der Marke hinsetzen, das Gesicht nach Westen.

    Mit dig_cooldown im Gedaechtnis: Einem Waerter, den /summon mit Daten
    setzt, fehlt es, und er graebt sich sofort ein - dabei ist er
    unverwundbar, und das /damage, das ihn reizen soll, prallt ab.
    """
    console(env, f'summon minecraft:warden {x} {y} {z} {{Rotation:[90f,0f],Tags:["{INTERACT_TAG}"],'
                 f"PersistenceRequired:1b,Silent:1b,"
                 f'Brain:{{memories:{{"minecraft:dig_cooldown":{{value:{{}},ttl:6000L}}}}}}}}', pause=1.0)


def waerter_ziel_checks(env, bot):
    """Ob sich der Waerter an body.mob-target haelt.

    Ein Waerter geht auf den los, ueber den er am wuetendsten ist, und keines
    der Ereignisse fuer ein Ziel kommt dabei vorbei. Wuetend machen ihn, was
    er riecht und hoert - und den Koerper riecht er wie jeden anderen. Das
    Plugin lenkt deshalb seine Wut: Der Kamera-Spieler macht ihn nie wuetend,
    der Koerper nur mit vanilla oder custom, und dort geht die Wut, die dem
    Kamera-Spieler gegolten haette, auf seinen Koerper ueber.

    Gereizt wird der Waerter mit /damage, einmal vom Koerper aus und einmal
    vom Kamera-Spieler aus, je mit mob-target vanilla und false. Mit vanilla
    greift er beide Male den Koerper an, mit false keinen. Er steht frei zwei
    Bloecke neben dem Koerper und kommt erst nach dem Start des Cam-Modus
    dazu, siehe rueckstoss_mob_checks.
    """
    if not FIND.test("Cam-Modus ist vor dem Waerter-Test aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    waerter = f"@e[type=minecraft:warden,tag={INTERACT_TAG},limit=1]"
    koerper = "@e[type=minecraft:mannequin,sort=nearest,limit=1]"
    bot.chat("/gamemode survival")
    bot.chat(f"/effect give {BOT_NAME} minecraft:resistance infinite 255 true")
    time.sleep(0.5)
    set_option(env, "enabled", "false", "cam-safety")
    console(env, "difficulty easy", pause=0.5)

    def lauf(modus, reizen):
        """Den Waerter reizen und sagen, ob er den Koerper angegriffen hat:
        (angegriffen, Meldung dazu) - oder None, wenn der Cam-Modus gar nicht
        erst startete."""
        # Vor dem Start: Ein cam reload wirft jeden Kamera-Spieler hinaus.
        set_option(env, "mob-target", modus, "body")
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
        console(env, f"effect clear {BOT_NAME} minecraft:darkness", pause=0.3)
        hinstellen(bot, x, y, z)
        if not cam_on(bot):
            return None
        bot.call("fly", wait=30, dy=RUECKSTOSS_HOEHE, timeout=15000)
        time.sleep(1.0)
        since = bot.mark()
        waerter_hin(env, x + 2, y, z)
        console(env, f"damage {waerter} 0.5 minecraft:mob_attack by {reizen}", pause=0)
        time.sleep(WAERTER_ZIEL_WARTEN)
        laeuft = spielmodus_ist(bot, "adventure")
        if laeuft:
            cam_off(bot)
        meldung = next((strip_colors(m["text"]) for m in bot.call("messages", since=since).get("messages", [])
                        if "by a warden!" in strip_colors(m["text"])), "")
        return not laeuft and bool(meldung), meldung

    try:
        for wer, reizen in (("vom Koerper", koerper), ("vom Kamera-Spieler", BOT_NAME)):
            mit = lauf("vanilla", reizen)
            ohne = lauf("false", reizen)
            FIND.test(f"Waerter, {wer} aus gereizt, greift mit mob-target: vanilla den Koerper an",
                      mit is not None and mit[0],
                      "der Cam-Modus startete nicht" if mit is None else mit[1] or "kein Angriff")
            FIND.test(f"Waerter, {wer} aus gereizt, laesst mit mob-target: false den Koerper in Ruhe",
                      ohne is not None and not ohne[0],
                      "der Cam-Modus startete nicht" if ohne is None else ohne[1])
    finally:
        try:
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.5)
            console(env, "difficulty peaceful", pause=0.3)
            set_options(env, [("enabled", "true", "cam-safety"), ("mob-target", "false", "body")])
            bot.chat(f"/effect clear {BOT_NAME}")
            boden_ebnen(bot)
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Waerter-Test: {exc}")


# ---------------------------------------------------------------------------
# Mobs, die beim Start hinter dem Spieler her sind
# ---------------------------------------------------------------------------

# Wie lange nach dem Start auf den Angriff eines Mobs gewartet wird, der den
# Koerper uebernommen hat, in Sekunden. Er steht fuenf Bloecke entfernt.
UEBERGABE_WARTEN = 8.0

# Wann nach dem Start nachgesehen wird, ob ein Mob den Kamera-Spieler noch als
# Ziel hat, in Sekunden. Die Uebergabe ist nach einem Tick durch; ein Hoglin,
# den sie verfehlt, gibt den Kamera-Spieler erst nach gut zehn Sekunden auf -
# so lange, dass das Warten auf den Angriff ihn sonst mitnaehme.
UEBERGABE_FRUEH = 1.0


def uebergabe_checks(env, bot):
    """Was aus einem Mob wird, der hinter dem Spieler her ist, wenn der
    Cam-Modus startet: Mit body.mob-target vanilla geht er auf den Koerper
    los, mit false verliert er sein Ziel. Und wenn der Koerper ihn mit vanilla
    angezogen hat, ist er nach dem Ende wieder hinter dem Spieler her.

    Geprueft an einem Eisengolem, einem Zombie, einem Hoglin und einem Piglin,
    alle vom Bot mit /damage gereizt. Den Golem erfasst nur die Uebergabe beim
    Start - er ist kein feindlicher Mob, und die Suche nach solchen um den
    Koerper laesst ihn aus. Den Zombie faende sie mit vanilla, mit false sucht
    sie nicht. Hoglin und Piglin steuert ihr Gehirn, setTarget erreicht sie
    nicht: Sie lassen den Spieler im Kreativ-Tick beim Start selbst los und
    bekommen dabei den Koerper. Der Piglin ginge nach seinen eigenen Regeln nur
    auf Spieler los und liesse den Koerper gleich wieder fallen, das Plugin
    haelt ihn dort. Ob ein Mob ein Ziel hat, sagt /execute on target: Hat er
    eines, sagt es die Marke.
    """
    if not FIND.test("Cam-Modus ist vor dem Uebergabe-Test aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    mob = f"@e[tag={INTERACT_TAG},limit=1]"
    hat_ziel = f"/execute as {mob} on target run say {{marke}}"
    zielt_auf_spieler = f"/execute as {mob} on target if entity @s[type=minecraft:player] run say {{marke}}"
    mobs = {
        "Eisengolem": f'summon minecraft:iron_golem {x + 5} {y} {z} {{Tags:["{INTERACT_TAG}"],'
                      f"PersistenceRequired:1b,Silent:1b}}",
        "Zombie": f'summon minecraft:zombie {x + 5} {y} {z} {{Tags:["{INTERACT_TAG}"],'
                  f"PersistenceRequired:1b,Silent:1b,IsBaby:0b}}",
        # In der Oberwelt wuerden beide ohne den Schutz zu Zombies.
        "Hoglin": f'summon minecraft:hoglin {x + 5} {y} {z} {{Tags:["{INTERACT_TAG}"],'
                  f"PersistenceRequired:1b,Silent:1b,IsImmuneToZombification:1b}}",
        "Piglin": f'summon minecraft:piglin {x + 5} {y} {z} {{Tags:["{INTERACT_TAG}"],'
                  f"PersistenceRequired:1b,Silent:1b,IsImmuneToZombification:1b,IsBaby:0b}}",
    }
    # Wie die Meldung des Plugins den Angreifer nennt, aus mob-names.
    name_der_art = {"Eisengolem": "an iron golem", "Zombie": "a zombie", "Hoglin": "a hoglin", "Piglin": "a piglin"}
    bot.chat("/gamemode survival")
    bot.chat(f"/effect give {BOT_NAME} minecraft:resistance infinite 255 true")
    time.sleep(0.5)
    set_option(env, "enabled", "false", "cam-safety")
    console(env, "difficulty easy", pause=0.5)

    def lauf(name, modus):
        """Den Mob reizen und den Cam-Modus starten. Gibt zurueck: (war er
        vorher hinter dem Bot her, hat er ihn kurz nach dem Start noch als
        Ziel, hat er den Koerper angegriffen, hat er danach noch ein Ziel, ist
        er nach dem Ende wieder hinter dem Bot her) - oder None, wenn der
        Cam-Modus nicht startete."""
        # Vor dem Start: Ein cam reload wirft jeden Kamera-Spieler hinaus.
        set_option(env, "mob-target", modus, "body")
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
        hinstellen(bot, x, y, z)
        console(env, mobs[name], pause=1.0)
        console(env, f"damage {mob} 0.5 minecraft:mob_attack by {BOT_NAME}", pause=1.0)
        vorher = server_says(bot, hat_ziel)
        since = bot.mark()
        bot.chat("/cam")
        if not bot.expect("Camera mode activated|Cam mode activated", since, 8000):
            return None
        # Steht der Mob schon neben dem Bot, schlaegt er den Koerper womoeglich
        # gleich, und der Cam-Modus ist schon wieder vorbei - dann ist auch
        # nicht mehr zu fragen, wen er beim Start uebernommen hat.
        time.sleep(UEBERGABE_FRUEH)
        laeuft = spielmodus_ist(bot, "adventure")
        noch_beim_spieler = laeuft and server_says(bot, zielt_auf_spieler)
        if laeuft:
            bot.call("fly", wait=30, dy=RUECKSTOSS_HOEHE, timeout=15000)
        ende = time.time() + UEBERGABE_WARTEN
        while laeuft and time.time() < ende:
            time.sleep(1.0)
            laeuft = spielmodus_ist(bot, "adventure")
        # Angegriffen hat er, wenn die Meldung des Plugins ihn nennt - und nicht
        # irgendetwas anderes den Cam-Modus beendet hat.
        art = name_der_art[name]
        angegriffen = not laeuft and any(
            f"by {art}!" in strip_colors(m["text"]) for m in bot.call("messages", since=since).get("messages", []))
        nachher = None if not laeuft else server_says(bot, hat_ziel)
        # Mit dem Ende verschwindet der Koerper, und wer hinter ihm her war,
        # ist wieder hinter dem Spieler her.
        wieder_beim_spieler = angegriffen and server_says(bot, zielt_auf_spieler)
        if laeuft:
            cam_off(bot)
        return vorher, noch_beim_spieler, angegriffen, nachher, wieder_beim_spieler

    try:
        for name, modus in (("Eisengolem", "vanilla"), ("Eisengolem", "false"), ("Zombie", "false"),
                            ("Hoglin", "vanilla"), ("Hoglin", "false"), ("Piglin", "vanilla"), ("Piglin", "false")):
            ergebnis = lauf(name, modus)
            if not FIND.test(f"{name}, vom Spieler gereizt, ist vor dem Start hinter ihm her (mob-target: {modus})",
                             ergebnis is not None and ergebnis[0],
                             "der Cam-Modus startete nicht" if ergebnis is None else ""):
                continue
            _, noch_beim_spieler, angegriffen, nachher, wieder_beim_spieler = ergebnis
            FIND.test(f"{name}, beim Start hinter dem Spieler her, hat ihn eine Sekunde danach nicht mehr "
                      f"als Ziel (mob-target: {modus})",
                      not noch_beim_spieler, "er ist noch hinter dem Kamera-Spieler her" if noch_beim_spieler else "")
            if modus == "vanilla":
                if FIND.test(f"{name}, beim Start hinter dem Spieler her, geht mit mob-target: vanilla auf den Koerper los",
                             angegriffen, "" if angegriffen else "kein Angriff auf den Koerper"):
                    FIND.test(f"{name}, vom Koerper angezogen, ist nach dem Ende wieder hinter dem Spieler her",
                              wieder_beim_spieler, "" if wieder_beim_spieler else "er hat den Spieler nicht als Ziel")
            else:
                FIND.test(f"{name}, beim Start hinter dem Spieler her, verliert mit mob-target: false sein Ziel",
                          nachher is False,
                          "der Cam-Modus endete" if nachher is None else
                          ("er hat noch ein Ziel" if nachher else ""))
    finally:
        try:
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.5)
            console(env, "difficulty peaceful", pause=0.3)
            set_options(env, [("enabled", "true", "cam-safety"), ("mob-target", "false", "body")])
            bot.chat(f"/effect clear {BOT_NAME}")
            boden_ebnen(bot)
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Uebergabe-Test: {exc}")


# ---------------------------------------------------------------------------
# Mobs, die den Kamera-Spieler anschauen
# ---------------------------------------------------------------------------

# Wie steil ein Mob mindestens nach oben sehen muss, um den Bot ueber sich
# anzusehen, in Grad (nach oben ist negativ). Wer sich nur umsieht, sieht auf
# Augenhoehe, also mit 0. Wer jemanden ansieht, hebt den Kopf in jedem Tick
# neu von 0 aus, um hoechstens 40 Grad - steiler als -40 kommt keiner.
BLICK_NACH_OBEN = -30

# Wie lange jede Probe hinsieht, in Sekunden. Eine Kuh sucht sich jede zweite
# Runde ihrer Ziele mit 2 % Wahrscheinlichkeit jemanden zum Ansehen, also im
# Schnitt alle fuenf Sekunden, und sieht dann zwei bis vier Sekunden hin. Drei
# Kuehe zusammen tun es im Schnitt nach knapp zwei Sekunden; der Haendler, der
# jede Runde sucht, sofort.
BLICK_DAUER = 8.0

# Die Mobs der Probe, nach dem Namen, mit dem sie im Chat sprechen.
BLICK_KUH = "BlickKuh"
BLICK_HAENDLER = "BlickHaendler"


def blick_runde(bot, dauer=BLICK_DAUER):
    """Wer von den Mobs der Probe in `dauer` Sekunden mindestens einmal steil
    nach oben sieht. Gibt die Namen zurueck, mit denen sie gesprochen haben.

    Jeder Mob, dessen Kopf gerade steiler als BLICK_NACH_OBEN steht, sagt die
    Marke der Runde; im Chat steht davor sein Name.
    """
    gesehen = set()
    ende = time.time() + dauer
    while time.time() < ende:
        _server_yes_zaehler[0] += 1
        marke = f"{SERVER_YES}-{_server_yes_zaehler[0]}"
        since = bot.mark()
        bot.chat(f"/execute as @e[tag={INTERACT_TAG},x_rotation=-90..{BLICK_NACH_OBEN}] run say {marke}")
        time.sleep(0.4)
        for m in bot.call("messages", since=since).get("messages", []):
            text = strip_colors(m["text"])
            if marke in text:
                gesehen.update(n for n in (BLICK_KUH, BLICK_HAENDLER) if n in text)
    return gesehen


def blick_checks(env, bot):
    """camera-mode.mobs-look-at-player: ob Mobs den Kamera-Spieler ansehen.

    Drei Kuehe und ein fahrender Haendler stehen um eine Stelle herum: die
    Kuehe im Westen, Sueden und Suedwesten, mit Bewegungstempo 0, der Haendler
    ueber Eck im Nordosten, in einer Zelle aus Barrieren. Der Bot schwebt im
    Cam-Modus 1,2 Bloecke ueber dieser Stelle - nah genug, dass alle ihn auch
    unsichtbar bemerken: zwei Bloecke weit, wie in Vanilla. Ob einer ihn
    ansieht, sagt sein Kopf, der dann steil nach oben zeigt. Die Kuehe sehen
    mit dem Ziel LOOK_AT_PLAYER hin, der Haendler mit INTERACT.

    Der Haendler braucht die Zelle statt des Tempos 0: INTERACT haelt neben
    dem Blick auch die Bewegung, und mit Tempo 0 kaeme sein Spaziergang nie an
    und hielte sie ihm fuer immer weg - er saehe niemanden an, auch ohne das
    Plugin. Die Zelle steht ueber Eck, damit sie seinen Blick nicht verdeckt:
    Er geht ueber die Kante zweier Waende hinweg, gut zwei Zehntel Bloecke
    ueber ihnen.

    Mit der Voreinstellung false darf keiner hinaufsehen. Mit true sehen sie
    ihn an - die Gegenprobe, ohne die die erste nichts saehe. Und einen
    Spieler ohne Cam-Modus sehen sie auch mit false weiter an: den Zuschauer,
    auf einer Barriere mitten zwischen ihnen, waehrend der Bot im Cam-Modus
    fuenf Bloecke daneben schwebt - nah genug, dass die Mobs das Ziel des
    Plugins tragen, zu weit, um ihn unsichtbar zu bemerken.
    """
    if not FIND.test("Cam-Modus ist vor dem Blicktest aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    # Sechs Bloecke suedoestlich des Startplatzes: Der Koerper bleibt dort
    # stehen, wo der Cam-Modus startet, und gehoert nicht in die Runde.
    cx, cz = _floor(heim[0]) + 6.5, _floor(heim[2]) + 6.5
    y = BODEN_Y + 1
    ueber = (cx, y + 1.2, cz)
    daneben = (cx + 5, y + 1.2, cz)
    mobs = [("cow", BLICK_KUH, cx - 1, cz), ("cow", BLICK_KUH, cx, cz + 1),
            ("cow", BLICK_KUH, cx - 1, cz + 1), ("wandering_trader", BLICK_HAENDLER, cx + 1, cz - 1)]
    # Die Waende der Zelle des Haendlers, zwei Bloecke hoch.
    hx, hz = _floor(cx) + 1, _floor(cz) - 1
    zelle = [(hx, hz - 1), (hx + 1, hz), (hx - 1, hz), (hx, hz + 1)]

    def schweben(wo):
        """Im Cam-Modus an die Stelle fliegen und nachsehen, ob er dort ist.

        Erst drei Bloecke darueber und dann senkrecht hinab: Schraeg von unten
        her streifte er die Zelle des Haendlers, und der Server setzte ihn
        zurueck.
        """
        if not cam_on(bot):
            return False
        bot.call("fly", wait=30, x=wo[0], y=wo[1] + 3, z=wo[2], timeout=15000)
        bot.call("fly", wait=30, x=wo[0], y=wo[1], z=wo[2], timeout=15000)
        time.sleep(1.0)
        pos = bot.server_pos()
        return pos is not None and max(abs(a - b) for a, b in zip(pos, wo)) < 0.5

    def probe(name, wo, erwartet):
        """Eine Runde: an die Stelle schweben und zusehen. erwartet sagt, ob
        Kuh und Haendler hinaufsehen sollen."""
        if not FIND.test(f"Der Bot schwebt fuer den Blicktest an seiner Stelle ({name})", schweben(wo), ""):
            return
        gesehen = blick_runde(bot)
        for art, wer in (("eine Kuh", BLICK_KUH), ("der Haendler", BLICK_HAENDLER)):
            FIND.test(f"{name}: {art} sieht {'hin' if erwartet else 'nicht hin'}",
                      (wer in gesehen) == erwartet,
                      "" if (wer in gesehen) == erwartet else
                      ("sah den Kamera-Spieler an" if not erwartet else "sah nicht hinauf"))

    try:
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.5)
        console(env, f"setblock {_floor(cx)} {y} {_floor(cz)} minecraft:barrier", pause=0.3)
        for wx, wz in zelle:
            console(env, f"fill {wx} {y} {wz} {wx} {y + 1} {wz} minecraft:barrier", pause=0.2)
        for art, name, mx, mz in mobs:
            console(env, f'summon minecraft:{art} {mx} {y} {mz} {{Tags:["{INTERACT_TAG}"],'
                         f'CustomName:"{name}",PersistenceRequired:1b,Silent:1b}}', pause=0.3)
        console(env, f"execute as @e[tag={INTERACT_TAG},type=minecraft:cow] run attribute @s "
                     f"minecraft:movement_speed base set 0", pause=0.3)
        for art, name in (("cow", BLICK_KUH), ("wandering_trader", BLICK_HAENDLER)):
            if not FIND.test(f"Fuer den Blicktest steht {name} da", entity_da(bot, f"minecraft:{art}"), ""):
                return

        # Die Voreinstellung wird nachgesehen und nicht gesetzt: So faellt auf,
        # wenn in der ausgelieferten Datei etwas anderes steht.
        probe("mobs-look-at-player: false (Voreinstellung), Kamera-Spieler ueber ihnen", ueber, False)

        # Ein Spieler ohne Cam-Modus mitten zwischen ihnen, auf der Barriere.
        zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
        try:
            zuschauer.start()
            if FIND.test("Ein zweiter Spieler kommt fuer den Blicktest herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ZUSCHAUER_NAME):
                time.sleep(1.5)
                console(env, f"tp {ZUSCHAUER_NAME} {cx} {y + 1} {cz}", pause=1.0)
                probe("mobs-look-at-player: false, Spieler ohne Cam-Modus zwischen ihnen", daneben, True)
        finally:
            zuschauer.stop()

        # Die Gegenprobe. cam reload wirft den Bot aus dem Cam-Modus,
        # schweben bringt ihn wieder hinein.
        set_option(env, "mobs-look-at-player", "true")
        probe("mobs-look-at-player: true, Kamera-Spieler ueber ihnen", ueber, True)
    finally:
        try:
            set_option(env, "mobs-look-at-player", "false")
            cam_off(bot)
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.5)
            console(env, f"setblock {_floor(cx)} {y} {_floor(cz)} minecraft:air", pause=0.3)
            for wx, wz in zelle:
                console(env, f"fill {wx} {y} {wz} {wx} {y + 1} {wz} minecraft:air", pause=0.2)
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Blicktest: {exc}")


# ---------------------------------------------------------------------------
# Der Name ueber dem Koerper
# ---------------------------------------------------------------------------

# Das TextDisplay, das den Namen traegt. Es steht gut zwei Bloecke ueber den
# Fuessen des Koerpers, und der Bot steht zu Beginn des Cam-Modus genau dort.
NAME = "@e[type=minecraft:text_display,distance=..4,limit=1,sort=nearest]"

# Wie hoch der Name ueber den Fuessen steht: Hoehe des Koerpers plus 0,275 -
# da beginnt das Namensschild, das das Spiel selbst ueber ihn setzen wuerde.
NAME_UEBER_STAENDER = 1.975 + 0.275
NAME_UEBER_MANNEQUIN = 1.8 + 0.275

# Die Voreinstellungen unter body, auf die der Abschnitt am Ende zurueckstellt.
# Die Schalter des Namens stehen mit demselben Namen auch unter camera-mode,
# fuer den Namen ueber dem Kamera-Spieler - deshalb mit Abschnitt.
NAME_VOREINSTELLUNG = [
    ("type", "1"), ("visible", "true"), ("name-visible", "true", "body"),
    ("color", "yellow", "body"), ("through-walls", "false", "body"),
    ("view-distance", "64", "body"), ("background", "true", "body"),
    ("shadow", "false", "body"), ("scale", "1.0", "body"),
    ("armorstand.name-format", '"{player}\'s Body"', "messages"),
]


def name_ueber(bot, art, hoehe):
    """Ob der Name genau ueber der naechsten Entitaet dieser Art steht.

    Gefragt wird von ihren Fuessen aus, um `hoehe` hinauf: Dort muss das
    TextDisplay stehen, auf ein paar Hundertstel genau.
    """
    return server_says(bot, f"/execute as @e[type=minecraft:{art},distance=..3,limit=1,"
                            f"sort=nearest] at @s positioned ~ ~{hoehe} ~ if entity "
                            f"@e[type=minecraft:text_display,distance=..0.05] "
                            f"run say {{marke}}")


def anzahl(bot, auswahl):
    """Wie viele Entitaeten auf diese Auswahl passen, vom Server gezaehlt.

    Jede von ihnen sagt dieselbe Marke einmal, und gezaehlt wird, wie oft sie
    im Chat steht - derselbe Weg wie bei server_says. Die Antwort von
    /execute if entity ohne run ("Test passed, count: N") kommt beim Bot
    nicht mit der Zahl an.
    """
    _server_yes_zaehler[0] += 1
    marke = f"{SERVER_YES}-{_server_yes_zaehler[0]}"
    since = bot.mark()
    bot.chat(f"/execute as {auswahl} run say {marke}")
    time.sleep(1.5)
    gesagt = bot.call("messages", since=since).get("messages", [])
    return sum(1 for m in gesagt if marke in (m.get("text") or ""))


def daten_von(bot, auswahl, pfad):
    """Was /data get ueber einen Wert einer Entitaet sagt, als Text."""
    since = bot.mark()
    bot.chat(f"/data get entity {auswahl} {pfad}")
    hit = bot.expect("entity data", since, 4000)
    return strip_colors(hit["text"]) if hit else None


def name_da(bot):
    """Ob ueberhaupt ein Name in der Naehe steht."""
    return server_says(bot, "/execute if entity @e[type=minecraft:text_display,"
                            "distance=..6] run say {marke}")


def name_checks(env, bot):
    """Der Name ueber dem Koerper: ein TextDisplay fuer sich.

    Er ist nicht mehr das Namensschild des Koerpers, sondern eine eigene
    Entitaet ohne Hitbox, die ueber ihm steht. Nur so lassen sich die
    Schalter unter body.name umsetzen - Farbe, durch Waende, Sichtweite,
    Hintergrund, Schatten, Groesse -, und nur so behaelt ein unsichtbarer
    Koerper seinen Namen, ohne dass ein Ruestungsstaender ihn tragen muss:
    Unsichtbar ist der Koerper bei beiden Typen ein einziges Mannequin.

    Geprueft wird am Server, mit /execute und /data: wo der Name steht, was
    er traegt und welche Entitaeten den Koerper bilden. Was der Client daraus
    zeichnet, sieht der Bot nicht.
    """
    if not FIND.test("Cam-Modus ist vor dem Namenstest aus", cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Namenstest lesbar", pos is not None, str(pos)):
        return
    bx, by, bz = (_floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2]))
    Log.detail(f"Testplatz fuer den Namen bei {(bx, by, bz)}")
    # Der Abschnitt zaehlt die Ruestungsstaender und Mannequins um den Koerper
    # herum. Der Cam-Modus ist aus, einen Koerper gibt es also gerade nicht:
    # Was hier steht, ist aus einem frueheren, abgebrochenen Lauf uebrig.
    for art in ("armor_stand", "mannequin", "text_display"):
        bot.chat(f"/kill @e[type=minecraft:{art},distance=..8]")
    time.sleep(0.5)
    # Der Name ueber dem Kamera-Spieler bleibt hier aus. Zu Beginn des
    # Cam-Modus steht der Bot genau ueber seinem Koerper, sein eigener Name
    # stuende also gleich neben dem des Koerpers - und NAME nimmt das naechste
    # TextDisplay. Er hat einen Abschnitt fuer sich, spielername_checks.
    set_option(env, "name-visible", "false", "camera-mode")

    def start():
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        return cam_on(bot)

    try:
        # --- Voreingestellt: Typ 1, sichtbar ---
        if not FIND.test("/cam startet fuer den Namenstest", start(), ""):
            return
        FIND.test("Der Name steht als TextDisplay ueber dem Ruestungsstaender, "
                  "wo sonst sein Namensschild hinge",
                  name_ueber(bot, "armor_stand", NAME_UEBER_STAENDER), "")
        text = daten_von(bot, NAME, "text")
        FIND.test("Der Name traegt den Text aus armorstand.name-format",
                  bool(text) and f"{BOT_NAME}'s Body" in text, text or "keine Antwort")
        FIND.test("Voreingestellt ist der Name gelb",
                  bool(text) and 'color: "yellow"' in text, text or "keine Antwort")
        eigener = server_says(bot, "/execute if data entity @e[type=minecraft:armor_stand,"
                                   "distance=..3,limit=1,sort=nearest] CustomName "
                                   "run say {marke}")
        FIND.test("Der Ruestungsstaender selbst traegt keinen Namen mehr", not eigener,
                  "" if not eigener else "er hat noch einen CustomName")
        # Die Voreinstellung wird nachgesehen und nicht gesetzt: So faellt
        # auf, wenn in der ausgelieferten Datei etwas anderes steht. Jede
        # Eigenschaft wird fuer sich gefragt - ein Kommando ueber 256 Zeichen
        # nimmt der Server nicht an und wirft den Bot hinaus.
        for name, nbt in (("Voreingestellt ist der Name nicht durch Waende zu sehen",
                           "{see_through:0b}"),
                          ("Voreingestellt hat er den Hintergrund eines Namensschilds",
                           "{default_background:1b}"),
                          ("Voreingestellt hat er keinen Schatten", "{shadow:0b}"),
                          ("Voreingestellt hat er die Groesse 1",
                           "{transformation:{scale:[1.0f,1.0f,1.0f]}}"),
                          ("Voreingestellt ist er 64 Bloecke weit zu sehen", "{view_range:1.0f}"),
                          ("Voreingestellt ist er immer hell", "{brightness:{block:15,sky:15}}"),
                          ("Er dreht sich wie ein Namensschild zum Betrachter",
                           '{billboard:"center"}')):
            FIND.test(name, nbt_frage(bot, NAME, nbt), "")

        # Der Ruestungsstaender wird von Hand versetzt. Der Cam-Modus laeuft
        # dabei weiter: Auf Bewegung prueft er das Mannequin, das in ihm steht.
        bot.chat("/execute as @e[type=minecraft:armor_stand,distance=..3,limit=1,"
                 "sort=nearest] at @s run tp @s ~1 ~ ~")
        time.sleep(1.0)
        FIND.test("Der Name folgt dem Koerper an seine neue Stelle",
                  name_ueber(bot, "armor_stand", NAME_UEBER_STAENDER), "")
        cam_off(bot)
        time.sleep(1.0)
        FIND.test("Der Name wird mit dem Koerper eingesammelt", not name_da(bot), "")

        # --- Unsichtbar: bei beiden Typen ein einziges Mannequin ---
        for typ in ("1", "2"):
            set_options(env, [("type", typ), ("visible", "false")])
            if not FIND.test(f"/cam startet mit type: {typ}, visible: false", start(), ""):
                continue
            staender = anzahl(bot, "@e[type=minecraft:armor_stand,distance=..3]")
            puppen = anzahl(bot, "@e[type=minecraft:mannequin,distance=..3]")
            FIND.test(f"Unsichtbar ist der Koerper von type: {typ} ein einziges Mannequin",
                      staender == 0 and puppen == 1,
                      f"{staender} Ruestungsstaender, {puppen} Mannequins")
            unsichtbar = nbt_frage(bot, "@e[type=minecraft:mannequin,distance=..3,limit=1,"
                                        "sort=nearest]",
                                   '{active_effects:[{id:"minecraft:invisibility"}]}')
            FIND.test(f"Das Mannequin von type: {typ} ist unsichtbar", unsichtbar, "")
            FIND.test(f"Unsichtbar steht der Name von type: {typ} ueber dem Mannequin",
                      name_ueber(bot, "mannequin", NAME_UEBER_MANNEQUIN), "")
            cam_off(bot)
            time.sleep(1.0)
            FIND.test(f"Unsichtbar wird bei type: {typ} alles eingesammelt",
                      not name_da(bot) and anzahl(bot, "@e[type=minecraft:mannequin,"
                                                       "distance=..6]") == 0, "")

        # --- Sichtbares Mannequin: der Name ueber ihm, nicht an ihm ---
        set_options(env, [("type", "2"), ("visible", "true")])
        if FIND.test("/cam startet mit type: 2", start(), ""):
            FIND.test("Ueber dem sichtbaren Mannequin steht der Name als TextDisplay",
                      name_ueber(bot, "mannequin", NAME_UEBER_MANNEQUIN), "")
            eigener = server_says(bot, "/execute if data entity @e[type=minecraft:mannequin,"
                                       "distance=..3,limit=1,sort=nearest] CustomName "
                                       "run say {marke}")
            FIND.test("Das Mannequin selbst traegt keinen Namen", not eigener,
                      "" if not eigener else "es hat einen CustomName")
            cam_off(bot)
            time.sleep(1.0)

        # --- Die Schalter unter body.name ---
        set_options(env, [("type", "1"), ("color", "red", "body"), ("through-walls", "true", "body"),
                          ("view-distance", "32", "body"), ("background", "false", "body"),
                          ("shadow", "true", "body"), ("scale", "2.0", "body")])
        if FIND.test("/cam startet mit geaenderten Schaltern fuer den Namen", start(), ""):
            text = daten_von(bot, NAME, "text")
            FIND.test("color: red faerbt den Namen rot",
                      bool(text) and 'color: "red"' in text and '"yellow"' not in text,
                      text or "keine Antwort")
            for name, nbt in (("through-walls: true zeigt ihn durch Waende", "{see_through:1b}"),
                              ("view-distance: 32 halbiert die Sichtweite", "{view_range:0.5f}"),
                              ("background: false nimmt den Hintergrund weg",
                               "{default_background:0b,background:0}"),
                              ("shadow: true gibt ihm einen Schatten", "{shadow:1b}"),
                              ("scale: 2.0 macht ihn doppelt so gross",
                               "{transformation:{scale:[2.0f,2.0f,2.0f]}}"),
                              # Durch Waende zeichnet der Client ihn ohnehin voll hell;
                              # fest hell ist er deshalb auch ohne.
                              ("Er bleibt auch dabei fest hell", "{brightness:{block:15,sky:15}}")):
                FIND.test(name, nbt_frage(bot, NAME, nbt), "")
            FIND.test("Auch doppelt so gross steht er auf derselben Hoehe",
                      name_ueber(bot, "armor_stand", NAME_UEBER_STAENDER), "")
            cam_off(bot)
            time.sleep(1.0)

        # Etwas, das keine Farbe ist, wird gemeldet und faellt auf gelb zurueck.
        set_option(env, "color", "blau", "body")
        meldung = any("Unknown value" in zeile and "body.name.color" in zeile
                      for zeile in server_log(env).splitlines())
        FIND.test("Eine unbekannte Farbe wird gemeldet", meldung,
                  "" if meldung else "keine Meldung im Server-Log")
        if start():
            text = daten_von(bot, NAME, "text")
            FIND.test("Eine unbekannte Farbe faellt auf gelb zurueck",
                      bool(text) and 'color: "yellow"' in text, text or "keine Antwort")
            cam_off(bot)
            time.sleep(1.0)

        # --- Mehrere Zeilen, und ein Farbcode vorn im Text ---
        # So stand der Name frueher in der Datei: &e vorn. color ersetzt ihn,
        # der Farbcode der zweiten Zeile bleibt.
        # Doppelt geschuetzt: replace_option reicht den Wert durch re.subn,
        # das aus \\ einen einzelnen Rueckstrich macht. In der Datei steht
        # danach \n in Anfuehrungszeichen, und YAML macht daraus die neue Zeile.
        set_options(env, [("color", "red", "body"),
                          ("armorstand.name-format", '"&e{player}\'s Body\\\\n&7Kamera"', "messages")])
        if FIND.test("/cam startet mit einem Namen ueber zwei Zeilen", start(), ""):
            # /data zeigt den Zeilenumbruch als \n, oder er steht selbst da.
            text = daten_von(bot, NAME, "text")
            FIND.test("\\n im Namen beginnt eine neue Zeile",
                      bool(text) and ("\\n" in text or "\n" in text) and "Kamera" in text,
                      text or "keine Antwort")
            FIND.test("color ersetzt den Farbcode vorn im Text, weiter hinten gilt der eigene",
                      bool(text) and 'color: "red"' in text and 'color: "gray"' in text
                      and '"yellow"' not in text, text or "keine Antwort")
            cam_off(bot)
            time.sleep(1.0)

        # --- name-visible: false ---
        set_option(env, "name-visible", "false", "body")
        if FIND.test("/cam startet ohne Namen", start(), ""):
            FIND.test("name-visible: false setzt keinen Namen", not name_da(bot), "")
            cam_off(bot)
            time.sleep(1.0)
    finally:
        try:
            cam_off(bot)
            set_options(env, NAME_VOREINSTELLUNG + [("name-visible", "true", "camera-mode")])
            hinstellen(bot, bx + 0.5, by, bz + 0.5)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Namenstest: {exc}")


# ---------------------------------------------------------------------------
# Der Name ueber dem Kamera-Spieler
# ---------------------------------------------------------------------------

# Wo der Name im Modus 1 steht: die Hoehe eines Spielers plus 0,275, wie beim
# Koerper. Dort beginnt das Namensschild, das das Spiel ueber ihn setzen wuerde.
NAME_UEBER_SPIELER = "2.075"

# Wo er im Modus 2 steht: als Passagier oben auf dem Kopf, 1,8 ueber den
# Fuessen. Die Schrift ist von dort um 0,275 hinaufgeschoben, ueber die
# translation des TextDisplays - sie beginnt also an derselben Stelle.
NAME_AUF_SPIELER = "1.8"

# Die Voreinstellungen, auf die der Abschnitt zurueckstellt. Die Schalter des
# Namens heissen unter body genauso, deshalb mit Abschnitt; nether steht unter
# portals und unter cam-area.
SPIELERNAME_VOREINSTELLUNG = [
    ("name-visible", "true", "camera-mode"), ("name-mode", "1"),
    ("color", "white", "camera-mode"), ("through-walls", "false", "camera-mode"),
    ("view-distance", "64", "camera-mode"), ("background", "true", "camera-mode"),
    ("shadow", "false", "camera-mode"), ("scale", "1.0", "camera-mode"),
    ("player_visibility_mode", "true"), ("allow_invisibility_potion", "true"),
    ("nether", "false", "portals"), ("nether", "false", "cam-area"),
    ("player.name-format", '"{player}\'s Cam"', "messages"),
]


def spielername_frage(bot, nbt="", hoehe=NAME_UEBER_SPIELER):
    """Ob genau ueber den Fuessen des Bots, um hoehe hinauf, ein TextDisplay
    steht, auf ein paar Hundertstel genau - mit nbt auch, ob seine Daten diesen
    Ausschnitt enthalten. Gefragt wird am Server; die Klammern des NBT werden
    fuer server_says verdoppelt."""
    auswahl = "@e[type=minecraft:text_display,distance=..0.05"
    if nbt:
        auswahl += ",nbt=" + nbt.replace("{", "{{").replace("}", "}}")
    return server_says(bot, f"/execute at {BOT_NAME} positioned ~ ~{hoehe} ~ "
                            f"if entity {auswahl}] run say {{marke}}")


def spielername_daten(bot, pfad, hoehe=NAME_UEBER_SPIELER):
    """Was /data get ueber einen Wert des Namens des Bots sagt, als Text."""
    since = bot.mark()
    bot.chat(f"/execute at {BOT_NAME} positioned ~ ~{hoehe} ~ run data get "
             f"entity @e[type=minecraft:text_display,distance=..0.05,limit=1] {pfad}")
    hit = bot.expect("entity data", since, 4000)
    return strip_colors(hit["text"]) if hit else None


def passagier(bot):
    """Ob ein TextDisplay als Passagier auf dem Bot sitzt, am Server gefragt."""
    return server_says(bot, f"/execute as {BOT_NAME} on passengers if entity "
                            f"@s[type=minecraft:text_display] run say {{marke}}")


def sieht_ueber(client, pos):
    """Ob dieser Client eine Entitaet dort kennt, wo der Name im Modus 1 ueber
    pos steht.

    Gefragt wird nach der Stelle, nicht nach der Art: Der Bot liest die
    Entitaetsarten mit den Daten von 26.1, und dort ist hinter dem Sulfur Cube
    alles um eins verrutscht - das TextDisplay auch.
    """
    ziel = (pos[0], pos[1] + float(NAME_UEBER_SPIELER), pos[2])
    for e in client.call("entities", radius=16).get("entities", []):
        if max(abs(a - b) for a, b in zip(e["pos"], ziel)) < 0.3:
            return True
    return False


def sitzt_auf(client, pos):
    """Ob dieser Client eine Entitaet kennt, die auf dem Spieler an pos sitzt.

    Fuer den Modus 2. Die Stelle des Namens hilft dort nicht: Einem Passagier
    schickt der Server keine eigenen Positionen, der Client setzt ihn auf sein
    Fahrzeug - mineflayer nur nicht, bei ihm bleibt er stehen, wo er ihn zuerst
    sah. Gefragt wird deshalb nach dem Fahrzeug. Den Spieler selbst findet der
    Bot nur an der Stelle: Auch die Art "player" liegt hinter dem Sulfur Cube,
    er haelt ihn fuer etwas anderes und kennt seinen Namen nicht.
    """
    liste = client.call("entities", radius=64).get("entities", [])
    traeger = {e["id"] for e in liste
               if max(abs(a - b) for a, b in zip(e["pos"], pos)) < 0.3}
    return any(e.get("vehicle") in traeger for e in liste)


def nbt_lesen(pfad):
    """Eine NBT-Datei, wie der Server sie speichert: gzip, darin ein Compound.
    Zurueck kommt sie als dict, Listen als list, Text als str."""
    daten = io.BytesIO(gzip.open(pfad).read())

    def lies(n):
        return daten.read(n)

    def wert(art):
        if art in (1, 2, 3, 4, 5, 6):
            form, groesse = {1: (">b", 1), 2: (">h", 2), 3: (">i", 4), 4: (">q", 8),
                             5: (">f", 4), 6: (">d", 8)}[art]
            return struct.unpack(form, lies(groesse))[0]
        if art == 7:
            return list(lies(struct.unpack(">i", lies(4))[0]))
        if art == 8:
            return lies(struct.unpack(">H", lies(2))[0]).decode("utf-8", "replace")
        if art == 9:
            innen = lies(1)[0]
            return [wert(innen) for _ in range(struct.unpack(">i", lies(4))[0])]
        if art == 10:
            ergebnis = {}
            while True:
                innen = lies(1)[0]
                if innen == 0:
                    return ergebnis
                name = lies(struct.unpack(">H", lies(2))[0]).decode("utf-8", "replace")
                ergebnis[name] = wert(innen)
        if art in (11, 12):
            form, groesse = (">i", 4) if art == 11 else (">q", 8)
            return [struct.unpack(form, lies(groesse))[0]
                    for _ in range(struct.unpack(">i", lies(4))[0])]
        raise ValueError(f"unbekannte NBT-Art {art}")

    art = lies(1)[0]
    lies(struct.unpack(">H", lies(2))[0])
    return wert(art)


def namensschild(env):
    """Wie der Server das Namensschild der Kamera-Spieler fuehrt: die Option
    NameTagVisibility des Teams cam_no_push, oder None, wenn es kein Team gibt.

    Gelesen aus scoreboard.dat, nach einem save-all. Den Client zu fragen
    geht nicht: mineflayer liest das Team-Paket von 26.2 mit den Daten von
    26.1 falsch - Optionen wie Mitglieder kommen verdreht an, selbst die
    Kollisionsregel, die das Plugin seit jeher auf never setzt.
    """
    console(env, "save-all flush", pause=3.0)
    pfad = env.server / "world" / "data" / "minecraft" / "scoreboard.dat"
    if not pfad.exists():
        return None
    teams = nbt_lesen(pfad).get("data", {}).get("Teams", [])
    for team in teams:
        if team.get("Name") == "cam_no_push":
            # Den Standardwert schreibt der Server nicht mit hinein.
            return team.get("NameTagVisibility", "always")
    return None


def spielername_checks(env, bot):
    """Der Name ueber dem Kamera-Spieler: ein TextDisplay an Stelle seines
    Namensschilds.

    Ueber einem unsichtbaren Spieler zeichnet der Client ein Namensschild nur
    fuer dessen Team, und ein Namensschild kann nur den Namen sagen. Das
    Plugin schaltet es fuer die Kamera-Spieler ab (Team cam_no_push) und stellt
    ein eigenes TextDisplay ueber sie, mit denselben Schaltern wie der Name
    ueber dem Koerper und freiem Text, etwa "Cam von {player}".

    Zwei Modi: 1 setzt ihn jeden Tick ueber den Spieler, 2 setzt ihn als
    Passagier auf ihn. Ein Passagier verhindert player.teleport() - Spigot
    lehnt jeden ab, Paper den in eine andere Welt - und faellt am Portal ab.
    Modus 2 nimmt ihn vor jedem Teleport ab; geprueft wird das am Beenden,
    an /tp in derselben und in eine andere Welt und an der Reise durch das
    Netherportal.

    Wo der Name steht und was er traegt, fragt der Abschnitt am Server. Wer
    ihn sieht, fragt er die Clients: den Bot selbst und einen zweiten Spieler,
    der den ganzen Abschnitt ueber dabei ist.
    """
    if not FIND.test("Cam-Modus ist vor dem Test des Spielernamens aus", cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Test des Spielernamens lesbar", pos is not None, str(pos)):
        return
    bx, by, bz = (_floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2]))
    Log.detail(f"Testplatz fuer den Spielernamen bei {(bx, by, bz)}")
    for art in ("armor_stand", "mannequin", "text_display"):
        bot.chat(f"/kill @e[type=minecraft:{art},distance=..8]")
    time.sleep(0.5)
    # Das Portal steht einen Block ueber dem Boden: Sein Rahmen reicht eine
    # Reihe unter das Innere, und auf dem Boden stuende er in der Grasschicht -
    # das Aufraeumen mit /fill ... air hinterliesse dort ein Loch. In der
    # Testwelt, die stehen bleibt, fiel spaeter der Sulfur Cube hinein.
    px, py, pz = bx + 6, by + 1, bz
    ankuenfte = []

    def start():
        """Den Cam-Modus am Testplatz starten und drei Bloecke hochfliegen,
        weg vom Namen ueber dem Koerper. Gibt zurueck, wo er dann ist."""
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        if not cam_on(bot):
            return None
        bot.call("fly", wait=30, dy=3, timeout=15000)
        time.sleep(1.0)
        return bot.server_pos()

    def am_koerper():
        zurueck = bot.server_pos()
        return (not in_nether(bot) and zurueck is not None
                and abs(zurueck[0] - (bx + 0.5)) < 1.5 and abs(zurueck[2] - (bz + 0.5)) < 1.5), zurueck

    def kein_name_mehr():
        return anzahl(bot, "@e[type=minecraft:text_display,distance=..10]") == 0

    def reise(modus):
        """Durch das Netherportal und mit /cam zurueck zum Koerper."""
        hoehe = NAME_UEBER_SPIELER if modus == 1 else NAME_AUF_SPIELER
        set_options(env, SPIELERNAME_VOREINSTELLUNG + [("name-mode", str(modus)),
                                                       ("nether", "true", "portals"),
                                                       ("nether", "true", "cam-area")])
        if not FIND.test(f"Modus {modus}: Portal fuer die Reise steht",
                         build_portal(bot, "minecraft:overworld", px, py, pz), ""):
            return
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
        if not FIND.test(f"Modus {modus}: /cam startet vor der Reise", cam_on(bot), ""):
            return
        since = bot.mark()
        bot.chat(f"/execute in minecraft:overworld run tp @s {px + 0.5} {py} {pz + 0.5}")
        time.sleep(PORTAL_TRAVEL_WAIT)
        ankunft = None
        drueben = in_nether(bot)
        gesagt = "" if drueben else "; ".join(
            strip_colors(m["text"]) for m in bot.call("messages", since=since).get("messages", [])
            if SERVER_YES not in m["text"])
        if FIND.test(f"Modus {modus}: der Kamera-Spieler reist durch das Portal in den Nether",
                     drueben, gesagt):
            ankunft = bot.server_pos()
            ankuenfte.append(ankunft)
            FIND.test(f"Modus {modus}: im Nether steht sein Name wieder ueber ihm",
                      spielername_frage(bot, hoehe=hoehe), "")
            if modus == 2:
                FIND.test("Modus 2: im Nether sitzt er wieder auf ihm", passagier(bot), "")
            rest = server_says(bot, f"/execute in minecraft:overworld positioned {px + 1} {py + 1} {pz} "
                                    f"if entity @e[type=minecraft:text_display,distance=..3] "
                                    f"run say {{marke}}")
            FIND.test(f"Modus {modus}: am Portal in der Overworld bleibt kein Name zurueck", not rest, "")
        cam_off(bot)
        time.sleep(1.5)
        daheim, zurueck = am_koerper()
        FIND.test(f"Modus {modus}: aus dem Nether bringt das Beenden ihn zurueck zu seinem Koerper",
                  daheim, str(zurueck))
        if ankunft:
            rest = server_says(bot, "/execute in minecraft:the_nether positioned "
                                    f"{ankunft[0]:.1f} {ankunft[1]:.1f} {ankunft[2]:.1f} if entity "
                                    "@e[type=minecraft:text_display,distance=..16] run say {marke}")
            FIND.test(f"Modus {modus}: im Nether bleibt kein Name zurueck", not rest, "")
        bot.chat(f"/execute in minecraft:overworld run fill {px - 1} {py - 1} {pz} "
                 f"{px + 2} {py + 3} {pz} minecraft:air")
        time.sleep(0.5)

    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    try:
        zuschauer.start()
        da = zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned")
        if not FIND.test("Ein zweiter Spieler kommt fuer den Spielernamen herein", da, ZUSCHAUER_NAME):
            return
        time.sleep(1.5)
        console(env, f"tp {ZUSCHAUER_NAME} {bx + 0.5} {by} {bz + 4.5}", pause=2.0)

        # --- Voreingestellt: Modus 1 ---
        oben = start()
        if not FIND.test("/cam startet fuer den Test des Spielernamens", oben is not None, ""):
            return
        FIND.test("Ueber dem unsichtbaren Kamera-Spieler steht sein Name als TextDisplay, "
                  "wo sonst sein Namensschild hinge", spielername_frage(bot), "")
        text = spielername_daten(bot, "text")
        FIND.test("Der Name traegt den Text aus player.name-format",
                  bool(text) and f"{BOT_NAME}'s Cam" in text, text or "keine Antwort")
        FIND.test("Voreingestellt ist der Spielername weiss",
                  bool(text) and 'color: "white"' in text, text or "keine Antwort")
        # Nachgesehen und nicht gesetzt, wie beim Namen ueber dem Koerper.
        for name, nbt in (("Voreingestellt ist der Spielername nicht durch Waende zu sehen",
                           "{see_through:0b}"),
                          ("Voreingestellt hat er den Hintergrund eines Namensschilds",
                           "{default_background:1b}"),
                          ("Voreingestellt hat er keinen Schatten", "{shadow:0b}"),
                          ("Voreingestellt hat er die Groesse 1",
                           "{transformation:{scale:[1.0f,1.0f,1.0f]}}"),
                          ("Voreingestellt ist er 64 Bloecke weit zu sehen", "{view_range:1.0f}"),
                          ("Der Spielername ist immer hell", "{brightness:{block:15,sky:15}}"),
                          ("Er dreht sich wie ein Namensschild zum Betrachter",
                           '{billboard:"center"}')):
            FIND.test(name, spielername_frage(bot, nbt), "")
        FIND.test("Im Modus 1 sitzt der Name nicht als Passagier auf ihm", not passagier(bot), "")
        bot.call("fly", wait=30, dx=3, timeout=15000)
        time.sleep(1.0)
        FIND.test("Der Name folgt ihm im Flug", spielername_frage(bot), "")
        oben = bot.server_pos()

        # Wer ihn sieht. Das Namensschild ist fuer die Kamera-Spieler im Team
        # abgeschaltet, auch andere Kamera-Spieler sehen also das TextDisplay.
        FIND.test("Ein Spieler ohne Cam-Modus sieht den Namen ueber dem Kamera-Spieler",
                  sieht_ueber(zuschauer, oben), "")
        FIND.test("Der Kamera-Spieler selbst sieht keinen Namen ueber sich",
                  not sieht_ueber(bot, oben), "")
        schild = namensschild(env)
        FIND.test("Das Namensschild der Kamera-Spieler ist abgeschaltet (cam_no_push: never)",
                  schild is not None and "never" in schild.lower(), str(schild))
        if FIND.test("Der zweite Spieler startet selbst den Cam-Modus", cam_on(zuschauer), ""):
            time.sleep(1.0)
            FIND.test("Ein anderer Kamera-Spieler sieht den Namen auch, als TextDisplay",
                      sieht_ueber(zuschauer, oben), "")
            cam_off(zuschauer)
            time.sleep(1.5)
        cam_off(bot)
        time.sleep(1.0)
        FIND.test("Mit dem Cam-Modus geht auch der Name", kein_name_mehr(), "")

        # --- Die Schalter unter camera-mode.name ---
        set_options(env, [("color", "red", "camera-mode"), ("through-walls", "true", "camera-mode"),
                          ("view-distance", "32", "camera-mode"),
                          ("background", "false", "camera-mode"), ("shadow", "true", "camera-mode"),
                          ("scale", "2.0", "camera-mode"),
                          ("player.name-format", '"&eCam von {player}\\\\n&7Kamera"', "messages")])
        if FIND.test("/cam startet mit geaenderten Schaltern fuer den Spielernamen",
                     start() is not None, ""):
            text = spielername_daten(bot, "text")
            FIND.test("Der Text ist frei: Cam von {player}",
                      bool(text) and f"Cam von {BOT_NAME}" in text, text or "keine Antwort")
            FIND.test("color: red faerbt den Spielernamen rot, statt des Farbcodes vorn",
                      bool(text) and 'color: "red"' in text and '"yellow"' not in text,
                      text or "keine Antwort")
            FIND.test("\\n im Spielernamen beginnt eine neue Zeile, ihr Farbcode gilt",
                      bool(text) and ("\\n" in text or "\n" in text) and "Kamera" in text
                      and 'color: "gray"' in text, text or "keine Antwort")
            for name, nbt in (("through-walls: true zeigt den Spielernamen durch Waende",
                               "{see_through:1b}"),
                              ("view-distance: 32 halbiert seine Sichtweite", "{view_range:0.5f}"),
                              ("background: false nimmt ihm den Hintergrund",
                               "{default_background:0b,background:0}"),
                              ("shadow: true gibt ihm einen Schatten", "{shadow:1b}"),
                              ("scale: 2.0 macht ihn doppelt so gross, auf derselben Hoehe",
                               "{transformation:{scale:[2.0f,2.0f,2.0f]}}")):
                FIND.test(name, spielername_frage(bot, nbt), "")
            koerper = server_says(
                bot, f"/execute as @e[type=minecraft:armor_stand,distance=..8,limit=1] at @s "
                     f"positioned ~ ~{NAME_UEBER_STAENDER:.3f} ~ if entity @e[type=minecraft:"
                     f"text_display,distance=..0.05,nbt={{{{see_through:0b}}}}] run say {{marke}}")
            FIND.test("Der Name ueber dem Koerper bleibt bei seinen eigenen Schaltern", koerper, "")
            cam_off(bot)
            time.sleep(1.0)

        # Etwas, das keine Farbe ist, wird gemeldet und faellt auf weiss zurueck.
        set_options(env, SPIELERNAME_VOREINSTELLUNG + [("color", "blau", "camera-mode")])
        meldung = any("Unknown value" in zeile and "camera-mode.name.color" in zeile
                      for zeile in server_log(env).splitlines())
        FIND.test("Eine unbekannte Farbe fuer den Spielernamen wird gemeldet", meldung,
                  "" if meldung else "keine Meldung im Server-Log")
        if start() is not None:
            text = spielername_daten(bot, "text")
            FIND.test("Sie faellt auf weiss zurueck",
                      bool(text) and 'color: "white"' in text, text or "keine Antwort")
            cam_off(bot)
            time.sleep(1.0)

        # Ein Modus, den es nicht gibt, ebenso - auf Modus 1.
        set_options(env, SPIELERNAME_VOREINSTELLUNG + [("name-mode", "3")])
        meldung = any("Unknown value" in zeile and "camera-mode.name-mode" in zeile
                      for zeile in server_log(env).splitlines())
        FIND.test("Ein unbekannter name-mode wird gemeldet", meldung,
                  "" if meldung else "keine Meldung im Server-Log")
        if start() is not None:
            FIND.test("Er faellt auf Modus 1 zurueck",
                      spielername_frage(bot) and not passagier(bot), "")
            cam_off(bot)
            time.sleep(1.0)

        # --- Sichtbar traegt er ebenfalls den Namen statt seines Namensschilds ---
        set_options(env, SPIELERNAME_VOREINSTELLUNG + [("allow_invisibility_potion", "false")])
        oben = start()
        if FIND.test("/cam startet mit allow_invisibility_potion: false", oben is not None, ""):
            FIND.test("Auch sichtbar steht der Name als TextDisplay ueber ihm", spielername_frage(bot), "")
            FIND.test("Ein Spieler ohne Cam-Modus sieht ihn", sieht_ueber(zuschauer, oben), "")
            cam_off(bot)
            time.sleep(1.0)

        # --- player_visibility_mode: cam - nur Kamera-Spieler sehen ihn ---
        set_options(env, SPIELERNAME_VOREINSTELLUNG + [("player_visibility_mode", "cam")])
        oben = start()
        if FIND.test("/cam startet mit player_visibility_mode: cam", oben is not None, ""):
            FIND.test("player_visibility_mode: cam: der Name steht ueber ihm", spielername_frage(bot), "")
            FIND.test("Wer ihn nicht sehen darf, sieht auch den Namen nicht",
                      not sieht_ueber(zuschauer, oben), "")
            if FIND.test("Der zweite Spieler geht dafuer in den Cam-Modus", cam_on(zuschauer), ""):
                time.sleep(1.0)
                FIND.test("Ein anderer Kamera-Spieler sieht ihn samt Namen", sieht_ueber(zuschauer, oben), "")
                cam_off(zuschauer)
                time.sleep(1.5)
            cam_off(bot)
            time.sleep(1.0)

        # --- Wann es keinen Namen gibt ---
        set_options(env, SPIELERNAME_VOREINSTELLUNG + [("player_visibility_mode", "false")])
        if FIND.test("/cam startet mit player_visibility_mode: false", start() is not None, ""):
            FIND.test("player_visibility_mode: false: kein Name, niemand soll ihn sehen",
                      not spielername_frage(bot), "")
            cam_off(bot)
            time.sleep(1.0)
        set_options(env, SPIELERNAME_VOREINSTELLUNG + [("name-visible", "false", "camera-mode")])
        if FIND.test("/cam startet mit name-visible: false", start() is not None, ""):
            FIND.test("name-visible: false: kein Name ueber dem Spieler", not spielername_frage(bot), "")
            schild = namensschild(env)
            FIND.test("name-visible: false laesst ihm sein Namensschild (cam_no_push: always)",
                      schild is not None and "always" in schild.lower(), str(schild))
            cam_off(bot)
            time.sleep(1.0)

        # --- Durch das Netherportal, Modus 1 ---
        reise(1)

        # --- Modus 2: der Name sitzt auf ihm ---
        set_options(env, SPIELERNAME_VOREINSTELLUNG + [("name-mode", "2")])
        oben = start()
        if FIND.test("/cam startet mit name-mode: 2", oben is not None, ""):
            FIND.test("Modus 2: der Name sitzt als Passagier auf ihm", passagier(bot), "")
            FIND.test("Modus 2: er sitzt oben auf seinem Kopf",
                      spielername_frage(bot, hoehe=NAME_AUF_SPIELER), "")
            FIND.test("Modus 2: die Schrift beginnt dort, wo das Namensschild hinge",
                      spielername_frage(bot, "{transformation:{translation:[0.0f,0.275f,0.0f]}}",
                                        NAME_AUF_SPIELER), "")
            text = spielername_daten(bot, "text", NAME_AUF_SPIELER)
            FIND.test("Modus 2: er traegt den Text aus player.name-format",
                      bool(text) and BOT_NAME in text, text or "keine Antwort")
            bot.call("fly", wait=30, dx=3, timeout=15000)
            time.sleep(1.0)
            FIND.test("Modus 2: im Flug bleibt er auf ihm sitzen",
                      passagier(bot) and spielername_frage(bot, hoehe=NAME_AUF_SPIELER), "")
            FIND.test("Modus 2: ein Spieler ohne Cam-Modus sieht ihn auf dem Kamera-Spieler sitzen",
                      sitzt_auf(zuschauer, bot.server_pos()), "")
            # /tp in derselben Welt: Der Server nimmt den Passagier mit.
            hier = bot.server_pos()
            console(env, f"tp {BOT_NAME} {hier[0]:.1f} {hier[1]:.1f} {hier[2] + 2:.1f}", pause=1.5)
            neu = bot.server_pos()
            FIND.test("Modus 2: /tp in derselben Welt bewegt ihn",
                      neu is not None and abs(neu[2] - (hier[2] + 2)) < 0.5, f"{hier} -> {neu}")
            FIND.test("Modus 2: der Name sitzt danach auf ihm",
                      passagier(bot) and spielername_frage(bot, hoehe=NAME_AUF_SPIELER), "")
            # Beenden: CamFly teleportiert ihn zum Koerper, in derselben Welt.
            # Mit dem Namen auf ihm lehnte Spigot das ab.
            cam_off(bot)
            time.sleep(1.5)
            daheim, wo = am_koerper()
            FIND.test("Modus 2: nach /cam steht er wieder an seinem Koerper", daheim, str(wo))
            FIND.test("Modus 2: mit dem Cam-Modus geht auch der Name", kein_name_mehr(), "")

        # /tp in eine andere Welt: Der Name kommt vorher ab und drueben wieder
        # auf ihn. Ueber dem Netherdach, wo nichts erstickt. Zurueck geht es mit
        # /cam - ein Teleport von CamFly in eine andere Welt. Den Bot drueben
        # selbst fliegen zu lassen, damit CamFly ihn zurueckholt, geht nicht
        # verlaesslich: Nach einem Weltwechsel stimmt bei mineflayer die eigene
        # Position nicht mehr.
        if FIND.test("/cam startet mit name-mode: 2 vor dem /tp in den Nether", start() is not None, ""):
            console(env, f"execute in minecraft:the_nether run tp {BOT_NAME} 0.5 130 0.5", pause=2.0)
            FIND.test("Modus 2: nach /tp in den Nether sitzt der Name dort wieder auf ihm",
                      in_nether(bot) and passagier(bot), "")
            cam_off(bot)
            time.sleep(1.5)
            daheim, wo = am_koerper()
            FIND.test("Modus 2: aus dem Nether bringt das Beenden ihn zurueck zu seinem Koerper",
                      daheim, str(wo))
            rest = server_says(bot, "/execute in minecraft:the_nether positioned 0.5 130 0.5 if entity "
                                    "@e[type=minecraft:text_display,distance=..16] run say {marke}")
            FIND.test("Modus 2: im Nether bleibt kein Name zurueck", not rest, "")
            FIND.test("Modus 2: auch hier geht der Name mit dem Cam-Modus", kein_name_mehr(), "")

        # --- Durch das Netherportal, Modus 2 ---
        reise(2)
    finally:
        try:
            zuschauer.stop()
            cam_off(bot)
            bot.chat(f"/execute in minecraft:overworld run tp @s {bx + 0.5} {by} {bz + 0.5}")
            time.sleep(1.0)
            bot.chat(f"/execute in minecraft:overworld run fill {px - 1} {py - 1} {pz} "
                     f"{px + 2} {py + 3} {pz} minecraft:air")
            for ankunft in ankuenfte:
                # Das Portal, das der Server drueben gebaut oder genommen hat:
                # Die Testwelt bleibt stehen, und der Portaltest soll es nicht
                # fuer seines halten.
                ax, ay, az = (_floor(ankunft[0]), _floor(ankunft[1]), _floor(ankunft[2]))
                bot.chat(f"/execute in minecraft:the_nether run fill {ax - 4} {ay - 4} {az - 4} "
                         f"{ax + 4} {ay + 4} {az + 4} minecraft:air replace minecraft:nether_portal")
                time.sleep(0.5)
            set_options(env, SPIELERNAME_VOREINSTELLUNG)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test des Spielernamens: {exc}")


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
        # und "Unknown value" allein traefe auch auf eine Meldung von
        # irgendwoher zu.
        meldung = any("Unknown value" in zeile and "camera-mode.gamemode" in zeile
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
# Lava, Wasser und Pulverschnee
# ---------------------------------------------------------------------------

# Wie lange ein Flug in diesem Abschnitt hoechstens dauert, in Millisekunden.
# Haelt das Plugin die Kamera an der Kante fest, kommt flyTo nie an und
# versucht es bis zu dieser Grenze wieder und wieder.
MEDIUM_FLY_TIMEOUT = 5000


def medium_fliegen(bot, *wegpunkte):
    """Die Wegpunkte der Reihe nach anfliegen, jeden mit hartem Timeout."""
    for x, y, z in wegpunkte:
        bot.call("fly", wait=MEDIUM_FLY_TIMEOUT / 1000 + 10,
                 x=x, y=y, z=z, timeout=MEDIUM_FLY_TIMEOUT)
    time.sleep(1.0)


def medium_gesagt(bot, since):
    """Was seit der Marke im Chat stand, ohne Farbcodes."""
    return [strip_colors(m["text"])
            for m in bot.call("messages", since=since).get("messages", [])]


def medium_flug(bot, start, wegpunkte):
    """Bei `start` in den Cam-Modus, die Wegpunkte abfliegen, aussteigen.

    Gibt zurueck, wo der Server die Kamera am Ende gefuehrt hat, ob der
    Cam-Modus da noch lief und was unterwegs im Chat stand - oder None, wenn
    /cam gar nicht erst startete.
    """
    hinstellen(bot, *start)
    if not cam_on(bot):
        return None
    since = bot.mark()
    medium_fliegen(bot, *wegpunkte)
    pos = bot.server_pos()
    lief = spielmodus_ist(bot, "adventure")
    gesagt = medium_gesagt(bot, since)
    cam_off(bot)
    time.sleep(1.0)
    return pos, lief, gesagt


def medium_start(bot, wo, heim):
    """Mitten in Lava, Wasser oder Pulverschnee /cam versuchen.

    Gibt zurueck, ob der Cam-Modus danach lief - am Spielmodus gefragt, siehe
    spielmodus_ist - und was im Chat stand. Danach ist er wieder aus und der
    Bot wieder draussen: Der Pulverschnee friert ihn sonst ein, und der
    Schaden daraus legte die cam-safety-Sperre auf die naechsten Proben.
    """
    hinstellen(bot, *wo)
    since = bot.mark()
    bot.chat("/cam")
    time.sleep(1.5)
    lief = spielmodus_ist(bot, "adventure")
    gesagt = medium_gesagt(bot, since)
    if lief:
        cam_off(bot)
    hinstellen(bot, *heim)
    return lief, gesagt


def medium_checks(env, bot):
    """Die Schalter allow_lava_flight, allow_water_flight und
    allow_powder_snow_flight unter camera-mode.

    Steht einer auf false, kommt die Kamera nicht hinein: Der Schritt an der
    Kante wird abgebrochen, und der Cam-Modus laeuft weiter. Darin starten
    laesst er sich auch nicht. Alle drei gehen denselben Weg. Mit
    border-mode: barrier, der Voreinstellung, steht an der Kante zudem eine
    Wand - Magma, blaues Glas und Schnee -, an der ein echter Client landet,
    ohne dass etwas abgebrochen werden muss; das prueft der Fall auf die Lava
    am Schluss, samt der Gegenprobe mit push-back.

    Aufgebaut wird viererlei, alles neben dem Testplatz:
      * ein Becken aus Glas, drei Bloecke tief voll Wasser, oben offen,
      * ein zweites daneben voll Lava,
      * ein Wuerfel aus Pulverschnee, drei Bloecke hoch,
      * eine Decke aus Pulverschnee, eine Lage dick, hoch in der Luft.

    Die Decke prueft den Kopf. Von unten kommt er als Erstes an, und eine
    einzige Lage ist so duenn, dass die Augen darueber herausschauen, noch
    ehe die Fuesse sie erreichen. Eine Sperre, die nur auf die Fuesse sieht,
    liesse die Kamera also hindurchschauen.

    Jede Probe steht zweimal da: mit der Voreinstellung true, die
    nachgesehen und nicht gesetzt wird, und mit false. Die erste ist die
    Gegenprobe - ohne sie sagte die zweite nur, dass die Kamera nicht
    ankam, und das sagt sie auch, wenn der Flug des Bots gar nicht erst
    losgeht.
    """
    if not FIND.test("Cam-Modus ist vor dem Test an Lava, Wasser und Pulverschnee aus",
                     cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Test an Lava, Wasser und Pulverschnee lesbar",
                     pos is not None, str(pos)):
        return
    bx, by, bz = _floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2])
    Log.detail(f"Testplatz fuer Lava, Wasser und Pulverschnee bei {(bx, by, bz)}")

    becken = f"{bx + 6} {by} {bz - 2} {bx + 10} {by + 2} {bz + 2}"
    wasser = f"{bx + 7} {by} {bz - 1} {bx + 9} {by + 2} {bz + 1}"
    lavabecken = f"{bx - 10} {by} {bz - 2} {bx - 6} {by + 2} {bz + 2}"
    lava = f"{bx - 9} {by} {bz - 1} {bx - 7} {by + 2} {bz + 1}"
    wuerfel = f"{bx - 1} {by} {bz + 6} {bx + 1} {by + 2} {bz + 8}"
    decke = f"{bx - 1} {by + 5} {bz - 8} {bx + 1} {by + 5} {bz - 6}"
    heim = (bx + 0.5, by, bz + 0.5)
    im_becken = (bx + 8.5, by, bz + 0.5)
    in_der_lava = (bx - 7.5, by, bz + 0.5)
    im_wuerfel = (bx + 0.5, by, bz + 7.5)
    unter_der_decke = (bx + 0.5, by, bz - 6.5)
    # Ueber den Rand hinweg und dann senkrecht hinein, bis knapp ueber den
    # Boden. Becken und Wuerfel sind oben alle bei by + 3 zu Ende.
    ins_becken = [(bx + 8.5, by + 6, bz + 0.5), (bx + 8.5, by + 0.2, bz + 0.5)]
    in_die_lava = [(bx - 7.5, by + 6, bz + 0.5), (bx - 7.5, by + 0.2, bz + 0.5)]
    in_den_wuerfel = [(bx + 0.5, by + 6, bz + 7.5), (bx + 0.5, by + 0.2, bz + 7.5)]
    oben = by + 3
    # Senkrecht hinauf, bis die Augen ueber der Decke waeren. Sie liegt bei
    # by + 5 und ist oben bei by + 6 zu Ende; die Augen sitzen 1,62 ueber den
    # Fuessen, der Kopf endet bei 1,8.
    durch_die_decke = [(bx + 0.5, by + 4.5, bz - 6.5)]
    schalter = ("allow_lava_flight", "allow_water_flight", "allow_powder_snow_flight")
    # Fuer die Wand: je ein Block vorn an Lava, Wasser und Pulverschnee, und
    # der Weg dorthin, anderthalb Bloecke davor. Die Lava und das Wasser zeigen
    # ihre oberste Lage, der Pulverschnee seine Seite zum Testplatz hin.
    lava_oben = (bx - 8, by + 2, bz)
    wasser_oben = (bx + 8, by + 2, bz)
    pulverschnee_vorn = (bx, by + 1, bz + 6)
    ueber_der_lava = [(bx - 7.5, by + 6, bz + 0.5), (bx - 7.5, oben + 1.5, bz + 0.5)]
    zum_wasser = [(bx - 7.5, by + 6, bz + 0.5), (bx + 8.5, by + 6, bz + 0.5),
                  (bx + 8.5, oben + 1.5, bz + 0.5)]
    zum_pulverschnee = [(bx + 8.5, by + 6, bz + 0.5), (bx + 0.5, by + 6, bz + 4.5),
                        (bx + 0.5, by + 1, bz + 4.5)]

    def wand_sicht(wegpunkte, zelle):
        """Im Cam-Modus die Wegpunkte abfliegen und sagen, was der Client an
        der Zelle sieht. Gewartet wird eine Auffrischung der Wand lang, und
        der Cam-Modus laeuft danach weiter."""
        medium_fliegen(bot, *wegpunkte)
        time.sleep(1.5)
        return bot.call("block_at", x=zelle[0], y=zelle[1], z=zelle[2]).get("name")

    def aufraeumen():
        for bereich in (becken, lavabecken, wuerfel, decke):
            bot.chat(f"/fill {bereich} minecraft:air")
            time.sleep(0.5)

    def zeige(ergebnis):
        if ergebnis is None:
            return "/cam startete nicht"
        pos, lief, gesagt = ergebnis
        return f"{pos}, Cam-Modus {'laeuft' if lief else 'aus'}, Chat: " \
               f"{'; '.join(g for g in gesagt if g) or 'nichts'}"

    def drin_bis(ergebnis, hoehe):
        """Ob die Kamera bis unter diese Hoehe kam, im laufenden Cam-Modus."""
        return ergebnis is not None and ergebnis[0] is not None \
            and ergebnis[1] and ergebnis[0][1] < hoehe

    def gestoppt(ergebnis, meldung, grenze):
        """Ob die Kamera an der Grenze haengen blieb, der Cam-Modus weiterlief
        und die Meldung dazu kam."""
        return ergebnis is not None and ergebnis[0] is not None \
            and ergebnis[1] and grenze(ergebnis[0][1]) \
            and any(meldung in g.lower() for g in ergebnis[2])

    def gesagt_hat(gesagt, text):
        return any(text in g.lower() for g in gesagt)

    def start_in_lava():
        """/cam mitten in der Lava, mit Feuerschutz: Ohne ihn verletzte die
        Lava den Bot, und die cam-safety-Sperre laege auf allen weiteren
        Proben. Danach loescht ihn das Wasserbecken, erst dann geht der
        Feuerschutz wieder weg - er brennt noch eine Weile nach."""
        bot.chat(f"/effect give {BOT_NAME} minecraft:fire_resistance 60 0 true")
        time.sleep(0.5)
        try:
            return medium_start(bot, in_der_lava, heim)
        finally:
            hinstellen(bot, *im_becken)
            hinstellen(bot, *heim)
            bot.chat(f"/effect clear {BOT_NAME} minecraft:fire_resistance")
            time.sleep(0.5)

    try:
        # Die Testwelt bleibt zwischen zwei Laeufen stehen: erst wegraeumen,
        # was ein abgebrochener Lauf hinterlassen hat.
        aufraeumen()
        for bereich, block in ((becken, "glass"), (wasser, "water"),
                               (lavabecken, "glass"), (lava, "lava"),
                               (wuerfel, "powder_snow"), (decke, "powder_snow")):
            bot.chat(f"/fill {bereich} minecraft:{block}")
            time.sleep(0.5)
        time.sleep(0.5)
        welt = "minecraft:overworld"
        gebaut = (block_is(bot, welt, f"{bx + 8} {by + 2} {bz}", "minecraft:water")
                  and block_is(bot, welt, f"{bx - 8} {by + 2} {bz}", "minecraft:lava")
                  and block_is(bot, welt, f"{bx} {by + 1} {bz + 7}", "minecraft:powder_snow")
                  and block_is(bot, welt, f"{bx} {by + 5} {bz - 7}", "minecraft:powder_snow"))
        if not FIND.test("Becken mit Wasser und Lava und der Pulverschnee stehen",
                         gebaut, ""):
            return

        # --- Voreinstellung: alles offen, nachgesehen und nicht gesetzt ---
        ergebnis = medium_flug(bot, heim, in_die_lava)
        FIND.test("Gegenprobe: voreingestellt fliegt die Kamera in die Lava",
                  drin_bis(ergebnis, oben - 1), zeige(ergebnis))
        ergebnis = medium_flug(bot, heim, ins_becken)
        FIND.test("Gegenprobe: voreingestellt fliegt die Kamera ins Wasser",
                  drin_bis(ergebnis, oben - 1), zeige(ergebnis))
        ergebnis = medium_flug(bot, heim, in_den_wuerfel)
        FIND.test("Gegenprobe: voreingestellt fliegt die Kamera in den Pulverschnee",
                  drin_bis(ergebnis, oben - 1), zeige(ergebnis))
        ergebnis = medium_flug(bot, unter_der_decke, durch_die_decke)
        FIND.test("Gegenprobe: voreingestellt schauen die Augen der Kamera ueber "
                  "die Pulverschnee-Decke",
                  ergebnis is not None and ergebnis[0] is not None
                  and ergebnis[0][1] + 1.62 > by + 6, zeige(ergebnis))
        # Am Chat gemessen und nicht am Spielmodus: Der Koerper steht mit in
        # der Lava, nimmt dort sofort Schaden und beendet den Cam-Modus
        # gleich wieder. Die Zeile der Action-Bar kommt vorher.
        lief, gesagt = start_in_lava()
        FIND.test("Gegenprobe: voreingestellt startet /cam in der Lava",
                  gesagt_hat(gesagt, "cam mode activated"),
                  "; ".join(gesagt) or "nichts im Chat")
        lief, gesagt = medium_start(bot, im_becken, heim)
        FIND.test("Gegenprobe: voreingestellt startet /cam im Wasser", lief,
                  "; ".join(gesagt) or "nichts im Chat")
        lief, gesagt = medium_start(bot, im_wuerfel, heim)
        FIND.test("Gegenprobe: voreingestellt startet /cam im Pulverschnee", lief,
                  "; ".join(gesagt) or "nichts im Chat")
        # Und keine Wand: Die Lava ist frei, also sieht der Client auch dicht
        # davor Lava und kein Magma.
        hinstellen(bot, *heim)
        if FIND.test("Gegenprobe: /cam startet fuer den Blick auf die freie Lava",
                     cam_on(bot), ""):
            gesehen = wand_sicht(ueber_der_lava, lava_oben)
            cam_off(bot)
            FIND.test("Gegenprobe: voreingestellt bleibt die Lava auch dicht vor der "
                      "Kamera Lava", gesehen == "lava", f"sieht: {gesehen}")

        # --- Alles zu ---
        set_options(env, [(name, "false") for name in schalter])

        ergebnis = medium_flug(bot, heim, in_die_lava)
        FIND.test("allow_lava_flight: false - die Kamera bleibt ueber der Lava "
                  "stehen, der Cam-Modus laeuft weiter, die Meldung kommt",
                  gestoppt(ergebnis, "cannot fly into lava",
                           lambda y: y >= oben - 0.01), zeige(ergebnis))
        lief, gesagt = start_in_lava()
        FIND.test("allow_lava_flight: false - in der Lava startet /cam nicht, "
                  "und die Ablehnung sagt warum",
                  not lief and not gesagt_hat(gesagt, "cam mode activated")
                  and gesagt_hat(gesagt, "cannot start cam mode in lava"),
                  "; ".join(gesagt) or "nichts im Chat")

        ergebnis = medium_flug(bot, heim, ins_becken)
        FIND.test("allow_water_flight: false - die Kamera bleibt ueber dem Wasser "
                  "stehen, der Cam-Modus laeuft weiter, die Meldung kommt",
                  gestoppt(ergebnis, "cannot fly into water",
                           lambda y: y >= oben - 0.01), zeige(ergebnis))

        # Wer schon drin ist - hier per /tp -, kommt heraus, aber nicht tiefer.
        hinstellen(bot, *heim)
        if FIND.test("allow_water_flight: false - /cam startet neben dem Becken",
                     cam_on(bot), ""):
            bot.chat(f"/tp {BOT_NAME} {bx + 8.5} {by + 1} {bz + 0.5}")
            time.sleep(1.0)
            medium_fliegen(bot, (bx + 8.5, by + 0.2, bz + 0.5))
            tiefer = bot.server_pos()
            medium_fliegen(bot, (bx + 8.5, by + 5, bz + 0.5))
            heraus = bot.server_pos()
            cam_off(bot)
            time.sleep(1.0)
            FIND.test("allow_water_flight: false - wer schon im Wasser ist, "
                      "kommt nicht tiefer hinein",
                      tiefer is not None and tiefer[1] > by + 0.9, str(tiefer))
            FIND.test("allow_water_flight: false - wer schon im Wasser ist, "
                      "kommt heraus", heraus is not None and heraus[1] > by + 4.5,
                      str(heraus))

        lief, gesagt = medium_start(bot, im_becken, heim)
        FIND.test("allow_water_flight: false - im Wasser startet /cam nicht, "
                  "und die Ablehnung sagt warum",
                  not lief and gesagt_hat(gesagt, "cannot start cam mode in water"),
                  "; ".join(gesagt) or "nichts im Chat")

        ergebnis = medium_flug(bot, heim, in_den_wuerfel)
        FIND.test("allow_powder_snow_flight: false - die Kamera bleibt auf dem "
                  "Pulverschnee stehen, der Cam-Modus laeuft weiter, die Meldung kommt",
                  gestoppt(ergebnis, "cannot fly into powder snow",
                           lambda y: y >= oben - 0.01), zeige(ergebnis))
        ergebnis = medium_flug(bot, unter_der_decke, durch_die_decke)
        FIND.test("allow_powder_snow_flight: false - der Kopf der Kamera bleibt "
                  "unter der Pulverschnee-Decke",
                  gestoppt(ergebnis, "cannot fly into powder snow",
                           lambda y: y + 1.8 <= by + 5.01), zeige(ergebnis))
        lief, gesagt = medium_start(bot, im_wuerfel, heim)
        FIND.test("allow_powder_snow_flight: false - im Pulverschnee startet /cam "
                  "nicht, und die Ablehnung sagt warum",
                  not lief and gesagt_hat(gesagt, "cannot start cam mode in powder snow"),
                  "; ".join(gesagt) or "nichts im Chat")

        # --- Mit border-mode: barrier wird das Gesperrte zur Wand ---
        # Die Proben oben fliegen mit 'fly' und stossen an nichts - fuer sie
        # haelt die Pruefung hinter der Wand, wie bei push-back. Hier faellt
        # der Bot mit der Physik des Clients auf die Lava, wie ein echter
        # Client, der ueber ihr aufhoert zu fliegen: Er landet auf Magma, das
        # nur er hat, und niemand setzt ihn zurueck.
        hinstellen(bot, *heim)
        if FIND.test("allow_lava_flight: false - /cam startet fuer die Wand in der Lava",
                     cam_on(bot), ""):
            magma = wand_sicht(ueber_der_lava, lava_oben)
            since = bot.mark()
            fall = bot.call("fall", wait=20, ms=2500)
            gelandet = bot.server_pos()
            gesagt = medium_gesagt(bot, since)
            glas = wand_sicht(zum_wasser, wasser_oben)
            schnee = wand_sicht(zum_pulverschnee, pulverschnee_vorn)
            cam_off(bot)
            FIND.test("allow_lava_flight: false - mit border-mode: barrier landet die "
                      "Kamera auf der Lava wie auf einem Block, ohne zurueckgesetzt zu "
                      "werden, und die Meldung kommt",
                      gelandet is not None and oben - 0.01 <= gelandet[1] <= oben + 0.1
                      and fall.get("forced") == 0 and gesagt_hat(gesagt, "cannot fly into lava"),
                      f"{gelandet}, {fall.get('forced')}x zurueckgesetzt, Chat: "
                      f"{'; '.join(g for g in gesagt if g) or 'nichts'}")
            FIND.test("Die Wand zeigt gesperrte Lava als Magma, gesperrtes Wasser als "
                      "blaues Glas und gesperrten Pulverschnee als Schnee",
                      magma == "magma_block" and glas == "blue_stained_glass"
                      and schnee == "snow_block",
                      f"Lava -> {magma}, Wasser -> {glas}, Pulverschnee -> {schnee}")

        # Gegenprobe mit push-back: keine Wand, die Lava bleibt Lava, und wer
        # hineinfaellt, wird zurueckgesetzt.
        set_option(env, "border-mode", "push-back")
        hinstellen(bot, *heim)
        if FIND.test("border-mode: push-back - /cam startet fuer den Fall auf die Lava",
                     cam_on(bot), ""):
            gesehen = wand_sicht(ueber_der_lava, lava_oben)
            fall = bot.call("fall", wait=20, ms=2500)
            gelandet = bot.server_pos()
            cam_off(bot)
            FIND.test("allow_lava_flight: false - mit border-mode: push-back bleibt die "
                      "Lava Lava, und wer hineinfaellt, wird zurueckgesetzt",
                      gesehen == "lava" and fall.get("forced", 0) > 0
                      and gelandet is not None and gelandet[1] >= oben - 0.01,
                      f"Lava -> {gesehen}, {gelandet}, {fall.get('forced')}x zurueckgesetzt")
        set_option(env, "border-mode", "barrier")
    finally:
        try:
            # Der Reload holt ihn auch aus dem Cam-Modus, falls eine Probe
            # mittendrin abgebrochen ist.
            set_options(env, [(name, "true") for name in schalter]
                        + [("border-mode", "barrier")])
            cam_off(bot)
            aufraeumen()
            hinstellen(bot, *heim)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test an Lava, Wasser und "
                         f"Pulverschnee: {exc}")


# ---------------------------------------------------------------------------
# Start mitten im Fall
# ---------------------------------------------------------------------------

# Wie hoch ueber dem Becken der Bot fuer die Fallproben losgelassen wird.
FALL_HOEHE = 24

# Das unsichtbare Mannequin im Koerper, das die Treffer nimmt - bei
# Koerpertyp 1, der Voreinstellung, steht es im Ruestungsstaender.
MANNEQUIN = "@e[type=minecraft:mannequin,distance=..12,sort=nearest,limit=1]"


def becken_bauen(bot, bx, by, bz):
    """Ein Becken aus Glas, drei Bloecke tief voll Wasser, oben offen. Gibt
    den Bereich zum Aufraeumen und die Stelle auf seinem Grund zurueck."""
    becken = f"{bx - 2} {by} {bz - 2} {bx + 2} {by + 2} {bz + 2}"
    bot.chat(f"/fill {becken} minecraft:glass")
    time.sleep(0.5)
    bot.chat(f"/fill {bx - 1} {by} {bz - 1} {bx + 1} {by + 2} {bz + 1} minecraft:water")
    time.sleep(0.5)
    return becken, (bx + 0.5, by, bz + 0.5)


def zahl_aus(text):
    """Die Zahl aus einer Antwort von /data get, oder None. Ein Wert, den
    das Spiel nicht speichert - TicksFrozen bei null -, kommt gar nicht erst
    als Zahl zurueck."""
    m = re.search(r"entity data:\s*(-?[\d.]+)", text or "")
    return float(m.group(1)) if m else None


def fall_checks(env, bot):
    """Kein Start des Cam-Modus mitten in einem Fall, der noch weh tun kann.

    Der Cam-Modus nahm den Fall sonst ab: Der Spieler fliegt ab dem Start,
    und sein Koerper faellt von der Startstelle aus neu, aus dem Stand. Wer
    nach einem langen Fall knapp ueber dem Boden /cam tippte, kam ohne
    Schaden davon.

    Dreierlei wird probiert:
      * ein echter Fall, FALL_HOEHE Bloecke ueber einem Becken losgelassen,
      * hoch in der Luft mit Sanftem Fall: Die Fallstrecke waechst dabei kaum,
        unter ihm liegen aber viele Bloecke, und sein Koerper faellt ohne
        den Effekt,
      * die Gegenprobe, mit Sanftem Fall anderthalb Bloecke ueber dem Boden,
        so hoch wie ein Sprung: Das tut niemandem weh, /cam muss gehen.

    Nach jeder Probe in der Luft kommt der Bot per /tp ins Wasser des
    Beckens: Es nimmt ihm die Fallstrecke, und kein Aufprall legt die
    cam-safety-Sperre auf die naechsten Proben.
    """
    if not FIND.test("Cam-Modus ist vor dem Test des Starts im Fall aus",
                     cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Test des Starts im Fall lesbar",
                     pos is not None, str(pos)):
        return
    bx, by, bz = _floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2])
    heim = (bx + 0.5, by, bz + 0.5)
    becken, im_becken = becken_bauen(bot, bx + 14, by, bz)
    hoch_ueber_becken = (im_becken[0], by + FALL_HOEHE, im_becken[2])
    knapp_ueber_boden = (bx + 0.5, by + 1.5, bz + 0.5)

    def probe(name, wo, warten, darf):
        """Den Bot nach `wo` setzen, `warten` Sekunden fallen lassen, /cam."""
        bot.chat(f"/tp {BOT_NAME} {wo[0]} {wo[1]} {wo[2]}")
        time.sleep(warten)
        since = bot.mark()
        bot.chat("/cam")
        if darf:
            hit = bot.expect("Camera mode activated|Cam mode activated", since, 6000)
            FIND.test(name, bool(hit), "" if hit else "der Start wurde abgelehnt")
        else:
            hit = bot.expect("cannot start cam mode while falling", since, 6000)
            time.sleep(0.5)
            lief = spielmodus_ist(bot, "adventure")
            FIND.test(name, bool(hit) and not lief,
                      "Cam-Modus laeuft" if lief else
                      strip_colors(hit["text"]) if hit else "keine Ablehnung im Chat")
        if spielmodus_ist(bot, "adventure"):
            cam_off(bot)
        hinstellen(bot, *im_becken)
        hinstellen(bot, *heim)

    try:
        probe("Mitten im Fall startet /cam nicht", hoch_ueber_becken, 0.8, False)

        bot.chat("/effect give @s minecraft:slow_falling 60 0")
        time.sleep(0.5)
        probe("Mit Sanftem Fall hoch in der Luft startet /cam nicht",
              hoch_ueber_becken, 0.8, False)
        # Kurz nach dem /tp, damit er noch in der Luft ist - aber erst nach
        # der ersten Bewegung, die sein Client meldet: Bis dahin steht er fuer
        # den Server noch, und die Probe sagte nichts.
        probe("Anderthalb Bloecke ueber dem Boden startet /cam wie nach einem "
              "Sprung", knapp_ueber_boden, 0.3, True)
    finally:
        bot.chat("/effect clear @s")
        time.sleep(0.4)
        if spielmodus_ist(bot, "adventure"):
            cam_off(bot)
        hinstellen(bot, *heim)
        bot.chat(f"/fill {becken} minecraft:air")
        time.sleep(0.5)


# ---------------------------------------------------------------------------
# Luft und Frost gehen auf den Koerper ueber
# ---------------------------------------------------------------------------

def zustand_checks(env, bot):
    """Die Luft unter Wasser und der Frost im Pulverschnee gehen beim Start
    auf das Mannequin ueber und beim Aussteigen von ihm zurueck.

    Der Koerper steht fuer den Spieler da: Er atmet und friert von dem Stand
    an weiter, den der Spieler beim Start hatte, und was er dabei verliert,
    hat der Spieler beim Aussteigen verloren. Vorher begann das Mannequin
    mit voller Luft und ohne Frost, und der Spieler bekam beim Aussteigen
    seine Luft vom Start zurueck.

    Gelesen wird am Mannequin, nicht am Ruestungsstaender: Es nimmt die
    Treffer, auch das Ertrinken und das Erfrieren.

    Beide Proben gehen knapp: Der Frost erreicht nach 140 Ticks den vollen
    Wert, und von da an tut er weh - am Bot und am Koerper. Gelesen wird
    deshalb gleich nach dem /cam, ohne die Pause von cam_on und cam_off, und
    der Bot kommt gleich danach wieder heraus.
    """
    if not FIND.test("Cam-Modus ist vor dem Test von Luft und Frost aus",
                     cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Test von Luft und Frost lesbar",
                     pos is not None, str(pos)):
        return
    bx, by, bz = _floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2])
    heim = (bx + 0.5, by, bz + 0.5)
    becken, im_becken = becken_bauen(bot, bx + 14, by, bz)
    schnee = f"{bx - 15} {by} {bz - 1} {bx - 13} {by + 2} {bz + 1}"
    im_schnee = (bx - 13.5, by, bz + 0.5)

    def schalten(an, wert):
        """/cam und gleich danach einen Wert lesen. Gewartet wird auf die eine
        Zeile, die zum Schalten gehoert: Die Action-Bar wiederholt "Cam mode
        activated" alle zwei Sekunden, und eine Wiederholung kurz nach dem
        /cam zum Aussteigen liesse den Wert zu frueh lesen."""
        since = bot.mark()
        bot.chat("/cam")
        bot.expect("Cam mode activated" if an else "Cam mode ended", since, 4000)
        return wert()

    def am_mannequin(pfad):
        return zahl_aus(daten_von(bot, MANNEQUIN, pfad))

    try:
        # --- Luft ---
        hinstellen(bot, *im_becken)
        time.sleep(1.5)
        luft_vorher = bot.server_data("Air")
        im_cam = schalten(True, lambda: am_mannequin("Air"))
        FIND.test("Das Mannequin atmet mit der Luft weiter, die der Spieler "
                  "beim Start hatte",
                  luft_vorher is not None and im_cam is not None
                  and luft_vorher - 60 <= im_cam <= luft_vorher,
                  f"Spieler vorher {luft_vorher}, Mannequin {im_cam}")
        # Hinaus aus dem Wasser, damit die eigene Luft der Kamera wieder
        # aufgeht und nicht mit der des Mannequins verwechselt wird.
        medium_fliegen(bot, (im_becken[0], by + 6, im_becken[2]))
        luft_mannequin = am_mannequin("Air")
        luft_nachher = schalten(False, lambda: bot.server_data("Air"))
        FIND.test("Nach dem Aussteigen hat der Spieler die Luft des Mannequins",
                  luft_mannequin is not None and luft_nachher is not None
                  and luft_mannequin - 60 <= luft_nachher <= luft_mannequin
                  and luft_nachher < luft_vorher - 20,
                  f"Spieler vorher {luft_vorher}, Mannequin am Ende {luft_mannequin}, "
                  f"Spieler danach {luft_nachher}")
        hinstellen(bot, *heim)

        # --- Frost ---
        bot.chat(f"/fill {schnee} minecraft:powder_snow")
        time.sleep(0.5)
        hinstellen(bot, *im_schnee)
        time.sleep(1.5)
        frost_vorher = bot.server_data("TicksFrozen")
        im_cam = schalten(True, lambda: am_mannequin("TicksFrozen"))
        FIND.test("Das Mannequin friert von dem Frost an weiter, den der "
                  "Spieler beim Start hatte",
                  frost_vorher is not None and im_cam is not None
                  and frost_vorher <= im_cam <= frost_vorher + 60,
                  f"Spieler vorher {frost_vorher}, Mannequin {im_cam}")
        medium_fliegen(bot, (im_schnee[0], by + 6, im_schnee[2]))
        frost_mannequin = am_mannequin("TicksFrozen")
        frost_nachher = schalten(False, lambda: bot.server_data("TicksFrozen"))
        hinstellen(bot, *heim)
        # Im Schnee steigt sein Frost nach dem Aussteigen weiter, um einen
        # Tick je Tick; draussen haette die Kamera ihren laengst verloren.
        FIND.test("Nach dem Aussteigen hat der Spieler den Frost des Mannequins",
                  frost_mannequin is not None and frost_nachher is not None
                  and frost_mannequin <= frost_nachher <= frost_mannequin + 60,
                  f"Spieler vorher {frost_vorher}, Mannequin am Ende {frost_mannequin}, "
                  f"Spieler danach {frost_nachher}")
    finally:
        if spielmodus_ist(bot, "adventure"):
            cam_off(bot)
        hinstellen(bot, *heim)
        for bereich in (becken, schnee):
            bot.chat(f"/fill {bereich} minecraft:air")
            time.sleep(0.5)


# ---------------------------------------------------------------------------
# Die Grenze: border-mode, border-block, border-radius
# ---------------------------------------------------------------------------

# Wie lange der Bot in einer Grenzprobe laeuft, in Millisekunden. Zu Fuss
# schafft er gut vier Bloecke in der Sekunde: Ohne Grenze kommt er damit weit
# ueber sie hinaus, mit Grenze steht er laengst an ihr.
GRENZ_LAUF_MS = 3500

# Was der Test fuer max-distance einstellt, in Bloecken. Klein, damit der Bot
# in einer Probe hinkommt; die Voreinstellung 100 steht danach wieder da.
GRENZ_ABSTAND = 6

# Der zweite Spieler, der nachsieht, ob die Wand nur beim Kamera-Spieler
# steht. Hoechstens 16 Zeichen, und nicht der Name des ersten Bots - sonst
# kickt der eine den anderen mit duplicate_login.
ZUSCHAUER_NAME = "CamFlyZuschauer"


def grenz_lauf(bot, heim, ms=GRENZ_LAUF_MS):
    """Bei heim in den Cam-Modus und mit der Physik des Clients nach Osten
    laufen. Der Cam-Modus bleibt danach an, damit der Aufrufer sich noch
    umsehen kann.

    Gibt zurueck, wo der Server den Bot danach fuehrt, wie oft er ihn
    unterwegs zurueckgesetzt hat und was im Chat stand - oder None, wenn
    /cam gar nicht erst startete.
    """
    hinstellen(bot, *heim)
    if not cam_on(bot):
        return None
    since = bot.mark()
    lauf = bot.call("walk", wait=ms / 1000 + 15, dx=1, dz=0, ms=ms)
    pos = bot.server_pos()
    return pos, lauf.get("forced", 0), medium_gesagt(bot, since)


def grenz_weg(ergebnis, heim):
    """Wie weit der Bot nach einem grenz_lauf von heim weg ist, oder None."""
    if ergebnis is None or ergebnis[0] is None:
        return None
    return math.dist(ergebnis[0], heim)


def grenz_zeige(ergebnis, heim):
    if ergebnis is None:
        return "/cam startete nicht"
    pos, forced, gesagt = ergebnis
    weg = grenz_weg(ergebnis, heim)
    weg = "?" if weg is None else f"{weg:.2f}"
    return (f"{pos}, {weg} vom Koerper, {forced}x zurueckgesetzt, Chat: "
            f"{'; '.join(g for g in gesagt if g) or 'nichts'}")


def zuschauer_sieht(env, x, y, z, wo):
    """Was ein zweiter Spieler ohne Cam-Modus an dieser Stelle sieht.

    Er kommt nur fuer diese eine Frage herein: an wo gestellt, von der
    Konsole aus, denn op hat er nicht. Gibt den Namen des Blocks zurueck,
    oder None, wenn er nicht hereinkam.
    """
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    try:
        zuschauer.start()
        if not zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"):
            return None
        time.sleep(1.5)
        console(env, f"tp {ZUSCHAUER_NAME} {wo[0]} {wo[1]} {wo[2]}", pause=2.0)
        return zuschauer.call("block_at", x=x, y=y, z=z).get("name")
    finally:
        zuschauer.stop()


def border_checks(env, bot):
    """camera-mode.border-mode, die Bloecke der Wand und border-radius.

    Gelaufen wird mit der Physik des Clients ('walk'), nicht mit 'fly': Die
    Wand von barrier steht nur im Client, und 'fly' versetzt den Bot, ohne an
    irgendetwas anzustossen - wie ein Client, der von der Wand nichts weiss.
    Ob der Server ihn unterwegs zurueckgesetzt hat, zaehlt der Bot mit; genau
    darin unterscheiden sich barrier und push-back.

    Die Grenze ist max-distance, dafuer auf GRENZ_ABSTAND gestellt, und ein
    verbotenes Biom:
      * barrier, die Voreinstellung - nachgesehen und nicht gesetzt: der Bot
        bleibt an der Grenze stehen, ohne je zurueckgesetzt zu werden, und
        die Meldung kommt. Die Wand steht nur in seinem Client: der Server
        hat dort Luft, ein zweiter Spieler ebenso, und nach dem Cam-Modus
        ist sie auch bei ihm wieder weg.
      * push-back: er kommt auch nicht weiter, wird dabei aber zurueckgesetzt.
      * false, Luft als border-block und border-radius 0: keine Grenze.
      * ein unbekannter Block und ein zu grosser Radius werden gemeldet, und
        die Wand steht mit dem, was dafuer genommen wird.
      * an der Wand des Bioms, die eben ist und deshalb Platz fuer Proben
        hat: Wasser, Lava und Pulverschnee zeigt sie voreingestellt als
        blaues Glas, Magma und Schnee, halbe Bloecke ersetzt sie, ganze
        laesst sie stehen - und in der Welt bleibt alles, wie es war.
        border-block-water nimmt einen anderen Block, und einer, durch den
        man durchkommt, gibt seinen Platz an border-block ab.
    """
    if not FIND.test("Cam-Modus ist vor dem Grenztest aus", cam_off(bot), ""):
        return
    pos = bot.server_pos()
    if not FIND.test("Standort fuer den Grenztest lesbar", pos is not None, str(pos)):
        return
    # Abseits der Becken aus dem Test davor, auf dem flachen Boden.
    bx, by, bz = _floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2]) + 20
    heim = (bx + 0.5, by, bz + 0.5)
    gang = f"{bx - 2} {by} {bz - 3} {bx + 24} {by + 3} {bz + 3}"
    Log.detail(f"Testplatz fuer die Grenze bei {(bx, by, bz)}")
    # Das verbotene Biom faengt an einer Grenze der 4er-Wuerfel an, in denen
    # das Spiel Biome fuehrt - dort steht dann auch die Wand.
    biom_x = (bx + 4 + 3) // 4 * 4
    biom = f"{biom_x} {max(-64, by - 4)} {bz - 4} {biom_x + 7} {by + 4} {bz + 4}"
    # Die Proben auf der Wand des Bioms: wo, was /setblock dort hinsetzt, was
    # der Server danach dort hat und was der Client sehen muss. Alle liegen in
    # der Ebene x = biom_x und in Reichweite des Bots, der mittig davor stehen
    # bleibt. Wasser und Lava liegen im Boden, rundum Gras, und weit genug
    # auseinander, dass sie nicht zusammenlaufen.
    wasser = (biom_x, by - 1, bz + 2)
    proben = [
        (wasser, "water", "water", "blue_stained_glass"),
        ((biom_x, by - 1, bz - 2), "lava", "lava", "magma_block"),
        ((biom_x, by, bz + 4), "powder_snow", "powder_snow", "snow_block"),
        ((biom_x, by, bz - 1), "stone_slab", "stone_slab", "barrier"),
        ((biom_x, by, bz - 4), "pointed_dripstone[vertical_direction=up,thickness=tip]",
         "pointed_dripstone", "barrier"),
        ((biom_x, by, bz + 1), "stone", "stone", "stone"),
    ]

    text = (env.server / "plugins" / "CamFly" / "config.yml").read_text(encoding="utf-8")
    voreingestellt = all(re.search(rf"(?m)^\s*{schluessel}:\s*{wert}\s*$", text)
                         for schluessel, wert in (("border-mode", "barrier"),
                                                  ("border-block", "barrier"),
                                                  ("border-block-water", "blue_stained_glass"),
                                                  ("border-block-lava", "magma_block"),
                                                  ("border-block-powder-snow", "snow_block"),
                                                  ("border-radius", "5")))
    FIND.test("Voreingestellt steht border-mode: barrier mit border-block: barrier, "
              "blauem Glas, Magma und Schnee und border-radius: 5 da", voreingestellt, "")

    def drin(ergebnis):
        """Ob der Bot nicht ueber max-distance hinaus kam, aber bis an sie heran."""
        weg = grenz_weg(ergebnis, heim)
        return weg is not None and GRENZ_ABSTAND - 1.5 <= weg <= GRENZ_ABSTAND + 0.01

    def frei(ergebnis):
        """Ob der Bot weit ueber max-distance hinaus kam."""
        weg = grenz_weg(ergebnis, heim)
        return weg is not None and weg > GRENZ_ABSTAND + 2

    def gewarnt(ergebnis, text):
        return ergebnis is not None and any(text in g.lower() for g in ergebnis[2])

    def sieht(zelle):
        """Was der Client des Bots an dieser Stelle sieht."""
        return bot.call("block_at", x=zelle[0], y=zelle[1], z=zelle[2]).get("name")

    def proben_weg():
        """Die Proben wieder wegnehmen: im Boden Gras, darueber Luft."""
        for (x, y, z), _, _, _ in proben:
            bot.chat(f"/setblock {x} {y} {z} minecraft:"
                     f"{'grass_block' if y < by else 'air'}")
            time.sleep(0.3)

    try:
        bot.chat(f"/fill {gang} minecraft:air")
        time.sleep(0.5)
        set_option(env, "max-distance", f"{GRENZ_ABSTAND}.0")

        # --- barrier ---
        ergebnis = grenz_lauf(bot, heim)
        FIND.test("border-mode: barrier - die Kamera bleibt an max-distance stehen, "
                  "ohne zurueckgesetzt zu werden, und die Meldung kommt",
                  drin(ergebnis) and ergebnis[1] == 0
                  and gewarnt(ergebnis, "cannot move further"), grenz_zeige(ergebnis, heim))
        wand_x = None
        for x in range(bx + 1, bx + GRENZ_ABSTAND + 4):
            if sieht((x, by, bz)) == "barrier":
                wand_x = x
                break
        FIND.test("border-mode: barrier - der Client sieht die Wand vor sich, "
                  "hinter max-distance und nicht davor",
                  wand_x is not None and wand_x <= bx + GRENZ_ABSTAND
                  and ergebnis is not None and ergebnis[0] is not None
                  and ergebnis[0][0] + 0.3 <= wand_x + 0.01,
                  f"Wand bei x={wand_x}, Bot bei {ergebnis[0] if ergebnis else None}")
        if wand_x is not None:
            FIND.test("border-mode: barrier - in der Welt steht dort weiter Luft",
                      block_is(bot, "minecraft:overworld", f"{wand_x} {by} {bz}",
                               "minecraft:air"), "")
            gesehen = zuschauer_sieht(env, wand_x, by, bz, (bx + 3.5, by, bz + 3.5))
            FIND.test("border-mode: barrier - ein Spieler ohne Cam-Modus sieht dort "
                      "keine Wand", gesehen == "air", f"sieht: {gesehen}")
        cam_off(bot)
        time.sleep(1.0)
        if wand_x is not None:
            nachher = sieht((wand_x, by, bz))
            FIND.test("border-mode: barrier - nach dem Cam-Modus ist die Wand auch "
                      "beim Spieler wieder weg", nachher == "air", f"sieht: {nachher}")

        # --- push-back ---
        set_option(env, "border-mode", "push-back")
        ergebnis = grenz_lauf(bot, heim)
        FIND.test("border-mode: push-back - die Kamera kommt nicht ueber max-distance, "
                  "wird dabei aber zurueckgesetzt, und die Meldung kommt",
                  drin(ergebnis) and ergebnis[1] > 0
                  and gewarnt(ergebnis, "cannot move further"), grenz_zeige(ergebnis, heim))
        cam_off(bot)

        # --- keine Grenze ---
        for aenderungen, name in (
                ([("border-mode", "false")], "border-mode: false"),
                ([("border-mode", "barrier"), ("border-block", "air")],
                 "border-block: air"),
                ([("border-block", "barrier"), ("border-radius", "0")],
                 "border-radius: 0")):
            set_options(env, aenderungen)
            ergebnis = grenz_lauf(bot, heim)
            FIND.test(f"{name} - keine Grenze, die Kamera laeuft ueber max-distance "
                      f"hinaus, ohne Meldung",
                      frei(ergebnis) and ergebnis[1] == 0
                      and not gewarnt(ergebnis, "cannot move further"),
                      grenz_zeige(ergebnis, heim))
            cam_off(bot)

        # --- was nicht passt, wird gemeldet ---
        vorher = len(server_log(env))
        set_options(env, [("border-block", "diamond"), ("border-radius", "99")])
        neu = strip_colors(server_log(env)[vorher:])
        FIND.test("Ein unbekannter border-block wird gemeldet",
                  "camera-mode.border-block: 'diamond'" in neu, "")
        FIND.test("Ein zu grosser border-radius wird gemeldet",
                  "camera-mode.border-radius is too large" in neu, "")
        ergebnis = grenz_lauf(bot, heim)
        FIND.test("Mit dem Ersatz fuer beides steht die Wand trotzdem",
                  drin(ergebnis) and ergebnis[1] == 0, grenz_zeige(ergebnis, heim))
        cam_off(bot)

        # --- ein verbotenes Biom, und was die Wand dort ersetzt ---
        set_options(env, [("max-distance", "100.0"), ("border-block", "barrier"),
                          ("border-radius", "5")])
        bot.chat(f"/fillbiome {biom} minecraft:lush_caves")
        time.sleep(1.5)
        for (x, y, z), setzen, _, _ in proben:
            bot.chat(f"/setblock {x} {y} {z} minecraft:{setzen}")
            time.sleep(0.3)
        time.sleep(0.5)
        # Was der Client vor dem Cam-Modus dort sieht. Nach dem Cam-Modus
        # muss er genau das wieder sehen - verglichen wird damit und nicht
        # mit dem Namen in der Welt: Der Bot liest die Bloecke mit den Daten
        # von 26.1, und dort tragen manche Bloecke von 26.2 eine andere
        # Nummer. Pulverschnee haelt er so fuer eine Kupfertruhe.
        vorher = {zelle: sieht(zelle) for zelle, _, _, _ in proben}
        ergebnis = grenz_lauf(bot, heim)
        stand = ergebnis[0] if ergebnis is not None else None
        FIND.test("border-mode: barrier - die Kamera bleibt am verbotenen Biom "
                  "stehen, ohne zurueckgesetzt zu werden, und die Meldung nennt es",
                  stand is not None and biom_x - 1.5 <= stand[0] + 0.3 <= biom_x + 0.01
                  and ergebnis[1] == 0 and gewarnt(ergebnis, "not allowed in lush caves"),
                  grenz_zeige(ergebnis, heim))
        # Einmal auffrischen lassen: Die Wand wird um die Stelle gebaut, an
        # der der Bot zuletzt stand, und die Proben liegen bis zu vier Bloecke
        # seitlich davon.
        time.sleep(1.5)
        im_client = {zelle: sieht(zelle) for zelle, _, _, _ in proben}
        zeige = "; ".join(f"{welt} -> {im_client[zelle]}" for zelle, _, welt, _ in proben)
        FIND.test("Die Wand zeigt voreingestellt Wasser als blaues Glas, Lava als "
                  "Magma und Pulverschnee als Schnee",
                  all(im_client[zelle] == erwartet for zelle, _, welt, erwartet in proben
                      if welt in ("water", "lava", "powder_snow")), zeige)
        FIND.test("Halbe Bloecke - eine Stufe, ein Tropfstein - werden zur Wand, ein "
                  "ganzer Block bleibt stehen",
                  all(im_client[zelle] == erwartet for zelle, _, welt, erwartet in proben
                      if welt not in ("water", "lava", "powder_snow")), zeige)
        FIND.test("In der Welt steht an den Proben weiter, was dort stand",
                  all(block_is(bot, "minecraft:overworld", f"{x} {y} {z}", f"minecraft:{welt}")
                      for (x, y, z), _, welt, _ in proben), "")
        cam_off(bot)
        time.sleep(1.0)
        danach = {zelle: sieht(zelle) for zelle, _, _, _ in proben}
        FIND.test("Nach dem Cam-Modus sieht der Spieler an den Proben wieder, was "
                  "wirklich dasteht",
                  all(danach[zelle] == vorher[zelle] for zelle, _, _, _ in proben),
                  "; ".join(f"{welt}: vorher {vorher[zelle]}, danach {danach[zelle]}"
                            for zelle, _, welt, _ in proben))

        # --- border-block-water ---
        set_option(env, "border-block-water", "light_blue_stained_glass")
        grenz_lauf(bot, heim)
        time.sleep(1.5)
        im_wasser = sieht(wasser)
        FIND.test("border-block-water: light_blue_stained_glass - diesen Block zeigt "
                  "die Wand dann im Wasser", im_wasser == "light_blue_stained_glass",
                  f"sieht: {im_wasser}")
        cam_off(bot)
        set_option(env, "border-block-water", "water")
        grenz_lauf(bot, heim)
        time.sleep(1.5)
        im_wasser = sieht(wasser)
        FIND.test("border-block-water: water - ein Block, durch den man durchkommt, "
                  "gibt seinen Platz an border-block ab", im_wasser == "barrier",
                  f"sieht: {im_wasser}")
        cam_off(bot)
    finally:
        try:
            # Der Reload holt ihn auch aus dem Cam-Modus, falls eine Probe
            # mittendrin abgebrochen ist.
            set_options(env, [("max-distance", "100.0"), ("border-mode", "barrier"),
                              ("border-block", "barrier"),
                              ("border-block-water", "blue_stained_glass"),
                              ("border-radius", "5")])
            cam_off(bot)
            proben_weg()
            bot.chat(f"/fillbiome {biom} minecraft:plains")
            time.sleep(1.0)
            # Dorthin zurueck, wo er herkam, und nicht an den Testplatz: Die
            # Welt bleibt stehen, und jeder Lauf finge sonst 20 Bloecke weiter
            # an - mit ihm die Tests, die danach kommen.
            hinstellen(bot, *pos)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Grenztest: {exc}")


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

    Auf die letzte der beiden Zeilen ist Verlass: Nach der Antwort auf /cam
    kommt keine Wiederholung der anderen mehr - das Plugin bricht die eine
    Zeile ab, sobald es die andere schickt.

    Den Server statt der Action-Bar zu fragen geht hier nicht: Am Spielmodus
    ist der Cam-Modus nicht zu erkennen, sobald camera-mode.gamemode etwas
    anderes als adventure sagt, und der zweite Bot, den der Test des
    Spielernamens damit schaltet, hat kein op fuer /execute.
    """
    for _ in range(2):
        since = bot.mark()
        bot.chat("/cam")
        time.sleep(1.5)
        gesagt = [strip_colors(m["text"])
                  for m in bot.call("messages", since=since).get("messages", [])]
        # Die letzte der beiden Zeilen sagt, wie es jetzt steht. Die
        # Action-Bar wiederholt "Cam mode activated" alle zwei Sekunden:
        # Kommt eine Wiederholung kurz vor dem Ausschalten an, steht sie mit
        # im Chat, und ein zweites /cam schaltete den Cam-Modus wieder ein.
        jetzt = None
        for t in gesagt:
            if re.search(r"[Cc]am mode activated", t):
                jetzt = True
            elif re.search(r"[Cc]am mode ended", t):
                jetzt = False
        if jetzt is None:
            return False    # /cam hat gar nicht geantwortet, etwa abgelehnt
        if jetzt == an:
            return True
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
      5. Mit border-mode: false gibt es keine Grenze: Drueben bleibt er, auch
         im verbotenen Biom.
      6. Mit forget-changed: false muss das Freigeben aufhoeren - der Eintrag
         steht dann, bis /cam reload ihn wegraeumt.
      7. Zum Schluss der Schalter portals.nether selbst.

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
    # Abgerundet wie in den anderen Abschnitten, nicht mit int(): Das schnitte
    # unter null zur falschen Seite ab, und heim laege dort einen Block
    # oestlich oder suedlich neben dem Bot. Dorthin stellt ihn der Test am
    # Ende, und der naechste Lauf finge dann dort an - jeder einen Block
    # weiter.
    bx, by, bz = _floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2])
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

        # --- Ohne Grenze holt ihn auch hinter dem Portal niemand zurueck ---
        # Das Umstellen leert das Gemerkte, und drueben ist es weiter
        # verboten: Mit einer Grenze kaeme er gleich wieder zurueck.
        set_option(env, "border-mode", "false")
        ohne = portal_probe(bot, portal, heim, "border-mode false")
        FIND.test("Auf border-mode: false holt ihn auch ein verbotenes Biom hinter "
                  "dem Portal nicht zurueck", ohne["was"] == "drueben", ohne["was"])
        set_option(env, "border-mode", "barrier")

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
                          ("forget-changed", "true"),
                          ("border-mode", "barrier")])


# ---------------------------------------------------------------------------
# Der Boden unter dem Testplatz
# ---------------------------------------------------------------------------

# Die oberste Lage der Flachwelt: Gras bei y=-61, darunter zwei Lagen Erde und
# bei -64 Grundgestein. Wer darauf steht, steht bei -60.
BODEN_Y = -61

# Wie weit die Grasschicht um den Startplatz herum neu gelegt wird, in Bloecken:
# nach Westen und Norden, nach Osten und Sueden. Die Abschnitte bauen von dort
# aus bis zu zehn Bloecke nach Westen und Norden und bis zu 26 nach Osten und
# Sueden. Weiter als zwei Chunks haelt der Server bei view-distance=2 nicht
# sicher geladen, und /fill braucht die Chunks.
BODEN_WEIT = (16, 32)


def boden_ebnen(bot):
    """Die Grasschicht um den Startplatz herum neu legen und den Bot mitten
    auf einen Block stellen.

    Die Testwelt bleibt zwischen zwei Laeufen stehen, und ein Lauf faengt dort
    an, wo der letzte aufgehoert hat. Was dort im Boden steckt, bleibt also
    liegen: ein Loch, wie es das Abraeumen eines Portalrahmens hinterlaesst,
    oder ein Trampelpfad - die Flachwelt erzeugt Doerfer, und deren Wege sind
    Trampelpfade. Ein Trampelpfad ist 1/16 niedriger als das Gras daneben;
    wer von ihm aus waagerecht losfliegt, stoesst mit den Fuessen an jeden
    Grasblock, und der Server setzt ihn bei jedem Schritt zurueck ("moved
    wrongly").

    Gesetzt wird ausdruecklich in der Overworld: Ein abgebrochener Lauf kann
    den Bot im Nether zurueckgelassen haben, und dort laege y=-60 unter der
    Welt.
    """
    pos = bot.server_pos()
    if pos is None:
        return
    x, z = _floor(pos[0]), _floor(pos[2])
    west, ost = BODEN_WEIT
    Log.detail(f"Grasschicht um {(x, z)} wird neu gelegt")
    bot.chat(f"/execute in minecraft:overworld run fill {x - west} {BODEN_Y} {z - west} "
             f"{x + ost} {BODEN_Y} {z + ost} minecraft:grass_block")
    time.sleep(1.0)
    bot.chat(f"/execute in minecraft:overworld run tp @s {x + 0.5} {BODEN_Y + 1} {z + 0.5}")
    time.sleep(1.0)


def step_tests(env):
    Log.step("9. Tests im laufenden Spiel")
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
        # Erst den Boden: Alles Weitere baut vom Startplatz aus.
        boden_ebnen(bot)
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

        # --- Meldungen einzeln abschalten ---
        message_switch_checks(env, bot)

        # --- Die Sprache der Texte ---
        language_checks(env, bot)

        # --- Geworfene Traenke im Cam-Modus ---
        potion_checks(env, bot)

        # --- Was den Koerper getroffen hat ---
        grund_checks(env, bot)

        # --- Bloecke und Entitaeten im Cam-Modus ---
        interact_checks(env, bot)

        # --- Der Sulfur Cube: schieben und wegschlagen ---
        sulfur_cube_checks(env, bot)

        # --- Die Ruestung im Cam-Modus ---
        armor_checks(env, bot)

        # --- Der Rueckstoss eines Treffers auf den Koerper ---
        rueckstoss_checks(env, bot)
        rueckstoss_mob_checks(env, bot)

        # --- Der Waerter haelt sich an body.mob-target ---
        waerter_ziel_checks(env, bot)

        # --- Mobs, die beim Start hinter dem Spieler her sind ---
        uebergabe_checks(env, bot)

        # --- Mobs, die den Kamera-Spieler ansehen ---
        blick_checks(env, bot)

        # --- Der Name ueber dem Koerper ---
        name_checks(env, bot)

        # --- Der Name ueber dem Kamera-Spieler ---
        spielername_checks(env, bot)

        # --- Der Spielmodus, in dem der Cam-Modus laeuft ---
        gamemode_checks(env, bot)

        # --- Lava, Wasser und Pulverschnee ---
        medium_checks(env, bot)

        # --- Start mitten im Fall ---
        fall_checks(env, bot)

        # --- Luft und Frost gehen auf den Koerper ueber ---
        zustand_checks(env, bot)

        # --- Die Grenze: barrier, push-back und keine ---
        border_checks(env, bot)

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
# 10. Zusammenfassung
# ---------------------------------------------------------------------------

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
        summary(env, results)

    if args.keep_running and "server" in plan:
        print(f"\n  Der Server laeuft weiter. Konsole: "
              f"echo \"say hallo\" > {env.server / 'console.fifo'}")
        print(f"  Beenden mit: python3 {Path(__file__).name} --stop")

    return 1 if FIND.failed else 0


if __name__ == "__main__":
    sys.exit(main())
