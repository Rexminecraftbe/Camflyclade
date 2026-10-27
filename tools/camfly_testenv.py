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
  8. Tests im laufenden Spiel fahren: die Grundprobe und die Abschnitte, die
     die Aenderungen gegenueber master betreffen (tools/testauswahl.py)
  9. Aufraeumen: Server und Bot beenden

Aufruf:
    python3 tools/camfly_testenv.py                  # alles, nur betroffene Abschnitte
    python3 tools/camfly_testenv.py --alle           # alle Abschnitte
    python3 tools/camfly_testenv.py --nur-auswahl    # nur zeigen, welche liefen
    python3 tools/camfly_testenv.py --steps build,apicheck
    python3 tools/camfly_testenv.py --steps names    # nach einem Update: fehlen Namen?
    python3 tools/camfly_testenv.py --keep-running   # Server laeuft weiter
    python3 tools/camfly_testenv.py --stop           # laufenden Server beenden

Das Skript aendert NICHTS am Plugin. Es baut, prueft und berichtet.
Alles, was es herunterlaedt, liegt unter --workdir (Standard:
~/camfly-testenv) und wird beim naechsten Lauf wiederverwendet.
"""

import argparse
import contextlib
import gzip
import hashlib
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
import uuid
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
# Dieser Pruefer zaehlt zurzeit 664 Methoden- und Feldzugriffe. Alle 664 gibt
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
        # Haengt an jedem Namen, solange ein Abschnitt mit einem bestimmten
        # Koerpertyp laeuft, siehe mit_koerpertyp.
        self.zusatz = ""

    def test(self, name, passed, detail=""):
        name += self.zusatz
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
    # Was ein frueherer Lauf fuer eine andere Version hingelegt hat, muss weg:
    # Zwei paper-api nebeneinander auf dem Klassenpfad, und javac nimmt sich,
    # was es zuerst findet.
    gewollt = {f"{artifact}-{version}.jar" for _, artifact, version, _ in PAPER_API_DEPS}
    for alt in env.paperapi.glob("*.jar"):
        if alt.name not in gewollt:
            alt.unlink()
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


# Ein Hilfsplugin fuer den Testserver, das teleportiert wie ein tpa-Plugin:
# ueber player.teleport(), also mit der Ursache PLUGIN. Vanilla /tp meldet
# sich als COMMAND, und teleports.plugin liesse sich ohne ein zweites Plugin
# gar nicht pruefen. Nur von der Konsole aus gebraucht:
#   plugintp <spieler> <zu-spieler>
#   plugintp <spieler> <x> <y> <z>
TPA_HELFER_NAME = "CamFlyTpaHelfer"

TPA_HELFER_JAVA = r'''
package camflytest;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class TpaHelfer extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 2 && args.length != 4) {
            return false;
        }
        Player wer = Bukkit.getPlayerExact(args[0]);
        if (wer == null) {
            sender.sendMessage("plugintp: kein Spieler " + args[0]);
            return true;
        }
        Location ziel;
        if (args.length == 2) {
            Player zu = Bukkit.getPlayerExact(args[1]);
            if (zu == null) {
                sender.sendMessage("plugintp: kein Spieler " + args[1]);
                return true;
            }
            ziel = zu.getLocation();
        } else {
            ziel = new Location(wer.getWorld(), Double.parseDouble(args[1]),
                    Double.parseDouble(args[2]), Double.parseDouble(args[3]));
        }
        sender.sendMessage("plugintp: " + (wer.teleport(ziel) ? "ok" : "abgelehnt"));
        return true;
    }
}
'''

TPA_HELFER_YML = f"""\
name: {TPA_HELFER_NAME}
version: 1.0
main: camflytest.TpaHelfer
api-version: '{MC_VERSION}'
commands:
  plugintp:
    permission: camflytest.plugintp
permissions:
  camflytest.plugintp:
    default: op
"""


def tpa_helfer_bauen(env, plugins):
    """Das Hilfsplugin gegen paper-api uebersetzen und zu den Plugins legen.

    Ohne paper-api (Schritt paperapi) bleibt es weg; die Proben, die es
    brauchen, fallen dann mit diesem Hinweis durch.
    """
    ziel = plugins / f"{TPA_HELFER_NAME}.jar"
    ziel.unlink(missing_ok=True)
    if not env.paper_api_jars:
        Log.warn(f"{TPA_HELFER_NAME} fehlt: ohne paper-api nicht zu bauen")
        return
    ordner = clean_dir(env.work / "tpa-helfer")
    quelle = ordner / "TpaHelfer.java"
    quelle.write_text(TPA_HELFER_JAVA, encoding="utf-8")
    klassen = ordner / "classes"
    klassen.mkdir()
    proc = run([str(env.javac), "-nowarn", "--release", "25", "-cp", env.paper_api_cp,
                "-d", str(klassen), str(quelle)], check=False, timeout=300)
    if proc.returncode != 0:
        Log.warn(f"{TPA_HELFER_NAME} liess sich nicht uebersetzen: {(proc.stdout or '').strip()[:300]}")
        return
    with zipfile.ZipFile(ziel, "w") as jar:
        jar.writestr("plugin.yml", TPA_HELFER_YML)
        for datei in klassen.rglob("*.class"):
            jar.write(datei, datei.relative_to(klassen).as_posix())


def prepare_server_files(env):
    env.server.mkdir(parents=True, exist_ok=True)
    (env.server / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (env.server / "server.properties").write_text(SERVER_PROPERTIES, encoding="utf-8")

    plugins = env.server / "plugins"
    plugins.mkdir(exist_ok=True)
    if not env.plugin_jar.exists():
        raise RuntimeError("Kein gebautes Jar in artifacts/ - erst Schritt 'build'")
    shutil.copy2(env.plugin_jar, plugins / "CamFly.jar")
    tpa_helfer_bauen(env, plugins)

    # Die Konfiguration kommt aus dem Quellordner. Aus target/classes zu lesen
    # waere eine Falle: Ein Lauf ohne den Schritt build faende dort die
    # Fassung vom letzten Bauen, womoeglich eine alte, und pruefte das Plugin
    # gegen eine Konfiguration, in der die neuen Schluessel gar nicht stehen.
    # Zu holen gibt es dort ohnehin nichts: Maven kopiert die Datei nur.
    source = env.repo / "src" / "main" / "resources" / "config.yml"
    text = source.read_text(encoding="utf-8")
    # Partikel aus. Der Bot liest ein Partikelpaket nur bis vor den Partikel -
    # minecraft-data 26.1 kennt die 26.2-IDs nicht, und frueher starb er daran -,
    # aber die eines Cam-Spielers mischten sich sonst unter die, die der Test
    # von /cam trail zaehlt.
    text, n = re.subn(r"(?m)^(\s*particles-per-tick:\s*).*$", r"\g<1>0", text)
    if n == 0:
        Log.warn("particles-per-tick nicht in der Konfiguration gefunden")
    # /tp beendet den Cam-Modus nicht. Die Abschnitte stellen die Kamera mit
    # /tp an ihren Platz, und mit teleports.command: true waere sie danach
    # keine Kamera mehr. Nur teleport_checks schaltet ihn fuer sich ein.
    text, n = replace_option(text, "command", "false", "teleports")
    if n != 1:
        Log.warn("teleports.command nicht in der Konfiguration gefunden")
    # Jeder Flug beginnt mit flight-speed.start. Die Abschnitte fliegen mit
    # festen Zeiten, und ein Tempo, das ein frueherer Abschnitt am Mausrad
    # eingestellt hat, haette sie sonst verschoben - bis in den naechsten Lauf,
    # die Datei des Bots bleibt stehen. Nur einstellungen_checks schaltet es
    # fuer sich ein.
    text, n = replace_option(text, "flight-speed", "false", "player-settings")
    if n != 1:
        Log.warn("player-settings.flight-speed nicht in der Konfiguration gefunden")
    # Voreingestellt gehen spot, photo, time und die anderen nur ueber das
    # Menue. Die Abschnitte tippen sie als Befehle; nur menue_checks stellt
    # menu.access fuer sich um.
    text, n = replace_option(text, "access", "both", "menu")
    if n != 1:
        Log.warn("menu.access nicht in der Konfiguration gefunden")
    # Ebenso list, stop, ban, trail und die anderen fuer Admins.
    text, n = replace_option(text, "admin-access", "both", "menu")
    if n != 1:
        Log.warn("menu.admin-access nicht in der Konfiguration gefunden")
    # /cam stop, /cam ban und /cam trail sind voreingestellt an. Die
    # Abschnitte schalten sie fuer sich ein und pruefen dabei auch, was sie
    # ausgeschaltet sagen; der Testserver startet sie deshalb aus, und
    # cam-trail zeichnet nicht die Fluege aller anderen Abschnitte auf.
    for abschnitt in ("stop-command", "cam-ban", "cam-trail"):
        text, n = replace_option(text, "enabled", "false", abschnitt)
        if n != 1:
            Log.warn(f"{abschnitt}.enabled nicht in der Konfiguration gefunden")
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
  // Das Partikelpaket ebenso: An den Daten des Partikels, nach seiner Nummer,
  // verschluckt sich der Bot. Gelesen wird bis vor den Partikel - Ort, Menge
  // und das Flag fuer die Sichtweite -, der Rest bleibt ein ungelesener Puffer.
  const particles = play && play.packet_world_particles;
  if (particles && particles[0] === 'container') {
    const cut = particles[1].findIndex((f) => f.name === 'particle');
    if (cut > 0) {
      play.packet_world_particles = ['container',
        particles[1].slice(0, cut).concat([{ name: 'rest', type: 'restBuffer' }])];
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
// Was 'velocity' an die Pakete der Geschwindigkeit haengt, solange es laeuft.
let follower = null;
// Durch welche Entitaet der Server den Bot sehen liess (Paket camera), mit der Zeit.
const cameras = [];
// Was watch_blocks gerade mitschreibt, bis watch_stop es abholt.
let watched = null;
// Die Toene, die der Server schickt, als Text des ganzen Pakets: Wo der Name
// des Tons darin steht, haengt an den geflickten Paketdaten, gesucht wird
// deshalb im ganzen Text.
const sounds = [];

const bot = mineflayer.createBot({
  host: process.env.CAMFLY_HOST || '127.0.0.1',
  port: parseInt(process.env.CAMFLY_PORT || '25565', 10),
  username: NAME,
  auth: 'offline',
  version: MC,
  checkTimeoutInterval: 120 * 1000,
  // Das Plugin fuer Partikel liest den Partikel, den das geflickte Paket
  // ungelesen laesst, und stuerzte daran ab. Gezaehlt wird unten selbst.
  plugins: { particle: false }
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
function soundText(packet) {
  try {
    return JSON.stringify(packet, (k, v) => (typeof v === 'bigint' ? String(v) : v));
  } catch (e) { return String(packet); }
}
for (const name of ['sound_effect', 'entity_sound_effect']) {
  bot._client.on(name, (packet) => sounds.push({ t: Date.now(), text: soundText(packet) }));
}
// Die Partikel, die beim Bot ankommen: wo und ob mit dem Flag fuer die
// Sichtweite, das sie auch von weit weg zeigt. Fuer /cam trail.
const particleLog = [];
bot._client.on('world_particles', (packet) => particleLog.push(
  { t: Date.now(), pos: [packet.x, packet.y, packet.z], far: !!packet.longDistance }));
// Die Chatzeilen des Servers als Text des ganzen Pakets, wie bei den Toenen:
// Klick und Hover stehen nur dort, der Text der Zeile hat sie nicht mehr.
const chatPackets = [];
bot._client.on('system_chat', (packet) => chatPackets.push({ t: Date.now(), text: soundText(packet) }));
// Was Paper ueber Adventure in die Action-Bar schickt - die Zeile mit dem
// Ping-Symbol des Funksignals -, kommt als eigenes Paket action_bar und nicht
// als system_chat, und mineflayer meldet es gar nicht. Hier landet es bei den
// Zeilen und den Paketen wie die anderen.
bot._client.on('action_bar', (packet) => {
  chatPackets.push({ t: Date.now(), text: soundText(packet) });
  let text = '';
  try { text = require('prismarine-chat')(bot.registry).fromNotch(packet.text).toString(); }
  catch (e) { text = soundText(packet); }
  messages.push({ t: Date.now(), text });
});
// Die Bossbars, die gerade zu sehen sind, nach ihrer UUID: der Titel als Text
// des ganzen Titels, wie bei den Toenen, der Fuellstand und die Einteilung
// (dividers: 0 keine, 1 sechs, 2 zehn, 3 zwoelf, 4 zwanzig Abschnitte).
const bossbars = {};
bot._client.on('boss_bar', (packet) => {
  const id = String(packet.entityUUID);
  const action = packet.action;
  if (action === 0 || action === 'add') {
    bossbars[id] = { title: soundText(packet.title), health: packet.health, dividers: packet.dividers };
  } else if (action === 1 || action === 'remove') {
    delete bossbars[id];
  } else if (bossbars[id] && (action === 2 || action === 'update_health')) {
    bossbars[id].health = packet.health;
  } else if (bossbars[id] && (action === 3 || action === 'update_title')) {
    bossbars[id].title = soundText(packet.title);
  } else if (bossbars[id] && (action === 4 || action === 'update_style')) {
    bossbars[id].dividers = packet.dividers;
  }
});
// Die Tageszeit und das Wetter, wie der Server sie diesem Client schickt, fuer
// /cam time und /cam weather: Die geben einem Spieler eine eigene, und die
// sieht nur sein Client. Das Zeitpaket als Text des ganzen Pakets, wie bei den
// Toenen, dazu was mineflayer daraus liest.
let skyTime = null;
bot._client.on('update_time', (packet) => { skyTime = soundText(packet); });
// Das Paket camera: durch welche Entitaet der Client sieht.
bot._client.on('camera', (packet) => cameras.push({ t: Date.now(), id: packet.cameraId }));
// Der Frost des eigenen Spielers (Eintrag 7 der Entitaetsdaten), wie der
// Server ihn diesem Client schickt.
const frosts = [];
bot._client.on('entity_metadata', (packet) => {
  if (!bot.entity || packet.entityId !== bot.entity.id) return;
  for (const m of packet.metadata || []) {
    if (m.key === 7) frosts.push({ t: Date.now(), value: m.value });
  }
});
// Wie stark es regnet, so wie der echte Client es rechnet: Ereignis 1
// (START_RAINING) setzt den Regen auf 0, von wo ihn die Ereignisse 7 steigen
// lassen, Ereignis 2 (STOP_RAINING) auf 1, von wo sie ihn fallen lassen.
// bot.isRaining nimmt die Namen woertlich und sagt nach /cam weather reset
// "Regen", obwohl der Client ihn auf 0 hat: Bukkit schickt fuer eigenes
// Wetter die beiden Ereignisse genau verkehrt herum, und der Client zeigt,
// was die Staerke sagt.
const RAIN_EVENTS = ['no_respawn_block_available', 'start_raining', 'stop_raining', 'change_game_mode',
  'win_game', 'demo_event', 'play_arrow_hit_sound', 'rain_level_change'];
let rainLevel = 0;
const rainEvents = [];
bot._client.on('game_state_change', (packet) => {
  const reason = typeof packet.reason === 'number' ? packet.reason : RAIN_EVENTS.indexOf(packet.reason);
  if (reason === 1) rainLevel = 0;
  else if (reason === 2) rainLevel = 1;
  else if (reason === 7) rainLevel = packet.gameMode;
  if (reason === 1 || reason === 2 || reason === 7) rainEvents.push([reason, packet.gameMode]);
});
bot.on('blockUpdate', (alt, neu) => {
  if (!watched || !neu || !neu.position) return;
  const folge = watched[`${neu.position.x},${neu.position.y},${neu.position.z}`];
  if (folge) folge.push(neu.name);
});

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
    case 'sounds':
      return { sounds: sounds.slice(cmd.since || 0), count: sounds.length };
    case 'chat_packets':
      return { packets: chatPackets.slice(cmd.since || 0), count: chatPackets.length };
    case 'particles':
      return { particles: particleLog.slice(cmd.since || 0), count: particleLog.length };
    case 'tab': {
      // Was der Server zur Tab-Vervollstaendigung vorschlaegt, fuer den Text
      // samt dem Schraegstrich vorn, so wie der Client ihn schickt. Hat er
      // nichts vorzuschlagen, antwortet er gar nicht: keine Antwort ist eine
      // leere Liste.
      let matches;
      try {
        matches = await bot.tabComplete(cmd.text, true, false, cmd.timeout || 2500);
      } catch (e) {
        return { matches: [], answered: false };
      }
      return { matches: (matches || []).map((m) => (typeof m === 'string' ? m : m.match)), answered: true };
    }
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
    case 'sky':
      return { timeOfDay: bot.time ? bot.time.timeOfDay : null, rain: rainLevel, packet: skyTime,
               events: rainEvents.slice(-6) };
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
    case 'cameras': {
      // Die Pakete camera seit einer Marke, dazu die eigene Nummer und die
      // der Spieler, wie der Bot sie kennt.
      const players = {};
      for (const [name, p] of Object.entries(bot.players)) {
        players[name] = p.entity ? p.entity.id : null;
      }
      return { count: cameras.length, packets: cameras.slice(cmd.since || 0),
               self: bot.entity ? bot.entity.id : null, players };
    }
    case 'players':
      // Wer in der Tab-Liste steht: hidePlayer nimmt einen Spieler dort heraus.
      return { players: Object.keys(bot.players) };
    case 'frosts':
      return { count: frosts.length, values: frosts.slice(cmd.since || 0).map((f) => f.value) };
    case 'velocity': {
      // Fliegt mit der Geschwindigkeit, die der Server schickt, wie ein
      // echter Client im Flug: ohne Schwerkraft, und mit der Zahl aus dem
      // Paket - mineflayer teilt das lpVec3 von 26.x noch durch 8000, siehe
      // knock_start. 'on: false' hoert damit auf.
      if (follower) {
        bot._client.removeListener('entity_velocity', follower);
        follower = null;
      }
      if (cmd.on) {
        follower = (packet) => {
          if (!bot.entity || packet.entityId !== bot.entity.id) return;
          const v = packet.velocity;
          bot.entity.velocity.set(v.x, v.y, v.z);
        };
        bot._client.on('entity_velocity', follower);
        try { bot.creative.startFlying(); } catch (e) { /* egal */ }
        bot.physics.gravity = 0;
      }
      return { ok: true };
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
      //
      // 'tick' an jedem Paket zaehlt, wie viele Physik-Ticks der Bot bis
      // dahin gerechnet hat. Daran liest der Test den Takt ab, in dem die
      // Stoesse ankamen, siehe stoss_takt.
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
        log.velocity.push({ t: Date.now() - start, v: [v.x, v.y, v.z], pos: where(),
                            tick: log.path.length });
      };
      const onExplosion = (packet) => {
        const k = packet.playerKnockback;
        log.explosion.push({ t: Date.now() - start, knockback: k ? [k.x, k.y, k.z] : null,
                             tick: log.path.length });
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
    case 'watch_blocks': {
      // Jeden Block, den der Client an diesen Stellen bekommt, der Reihe
      // nach - auch einen, der zwischen zwei Abfragen mit block_at schon
      // wieder weg waere.
      watched = {};
      for (const [x, y, z] of cmd.cells) watched[`${x},${y},${z}`] = [];
      return { ok: true };
    }
    case 'watch_stop': {
      const log = watched;
      watched = null;
      return { log };
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
    case 'bossbars':
      return { bars: Object.values(bossbars) };
    case 'swap_hands':
      // Die Taste zum Tauschen der Haende (F). Der echte Client schickt sie
      // auch mit leeren Haenden, als Paket ohne Ort.
      bot._client.write('block_dig', { status: 6, location: { x: 0, y: 0, z: 0 }, face: 0, sequence: 0 });
      return { swapped: true };
    case 'window':
      return { open: !!bot.currentWindow,
               title: bot.currentWindow ? String(bot.currentWindow.title || '') : null };
    case 'close_window':
      try { if (bot.currentWindow) bot.closeWindow(bot.currentWindow); } catch (e) { /* egal */ }
      return { open: !!bot.currentWindow };
    case 'click_window': {
      // Ein Klick in das offene Fenster: button 0 links, 1 rechts, mode 1
      // mit gedrueckter Umschalttaste. Der Server weist jeden Klick in das
      // Menue ab und schickt das Fenster neu - eine Antwort, auf die
      // mineflayer warten koennte, gibt es nicht, deshalb nach drei Sekunden
      // weiter.
      if (!bot.currentWindow) return { clicked: false, reason: 'kein Fenster' };
      try {
        await Promise.race([
          bot.clickWindow(cmd.slot, cmd.button || 0, cmd.mode || 0),
          new Promise((r) => setTimeout(r, 3000))]);
      } catch (e) {
        return { clicked: false, reason: String(e && e.message || e) };
      }
      return { clicked: true };
    }
    case 'window_slots': {
      // Was in den Plaetzen des offenen Fensters liegt, ohne das eigene
      // Inventar darunter. Die Namen der Arten kennt mineflayer nur aus den
      // Daten von 26.1, verlassen wird sich darauf nicht: Es zaehlt, wo etwas
      // liegt.
      const w = bot.currentWindow;
      if (!w) return { open: false, title: null, slots: {} };
      // Der Titel kommt bei 26.x als Textbaustein, nicht als Zeichenkette:
      // als JSON steht der Text darin.
      const title = typeof w.title === 'string' ? w.title : JSON.stringify(w.title);
      const slots = {};
      const end = w.inventoryStart !== undefined ? w.inventoryStart : w.slots.length;
      for (let i = 0; i < end; i++) {
        const it = w.slots[i];
        if (!it) continue;
        // Name und Zeilen darunter, wie mineflayer sie aus den Bausteinen des
        // Items liest - als JSON, wo es Textbausteine sind.
        const text = (v) => (v === undefined || v === null) ? null
          : (typeof v === 'string' ? v : JSON.stringify(v));
        let custom = null;
        let lore = null;
        try { custom = text(it.customName); } catch (e) { /* egal */ }
        try { lore = text(it.customLore); } catch (e) { /* egal */ }
        // Ob es glaenzt: der Baustein enchantment_glint_override, so wie
        // der Server ihn schickt.
        let glint = false;
        try {
          const glanz = it.componentMap && it.componentMap.get('enchantment_glint_override');
          glint = !!glanz && glanz.data !== false;
        } catch (e) { /* egal */ }
        slots[i] = { name: it.name, count: it.count, custom, lore, glint };
      }
      return { open: true, title: title || '', slots };
    }
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


# Die beiden Koerpertypen, body.type: armorstand ist ein Ruestungsstaender mit
# einem unsichtbaren Mannequin darin, mannequin ein Mannequin allein.
KOERPERTYPEN = ("armorstand", "mannequin")


@contextlib.contextmanager
def mit_koerpertyp(env, typ):
    """Fuer die Dauer des Blocks mit diesem body.type, danach wieder mit der
    Voreinstellung armorstand. Jede Probe darin traegt den Typ in ihrem Namen,
    sonst stuende in der Zusammenfassung zweimal dasselbe da."""
    set_option(env, "type", typ)
    FIND.zusatz = f" (Koerpertyp {typ})"
    try:
        yield
    finally:
        FIND.zusatz = ""
        set_option(env, "type", "armorstand")


def fuer_beide_koerpertypen(env, bot, *abschnitte):
    """Dieselben Abschnitte einmal mit jedem Koerpertyp.

    Was den Koerper trifft, trifft bei armorstand den Ruestungsstaender oder
    das Mannequin darin, bei mannequin das Mannequin allein. Dass ein Treffer
    bei beiden ankommt und bei beiden gleich weitergeht, sagt die Probe mit
    nur einem von ihnen nicht: Bei mannequin flogen Pfeil, Dreizack, Windkugel
    und Speerstich durch den Koerper hindurch, waehrend armorstand bestand.
    """
    for typ in KOERPERTYPEN:
        with mit_koerpertyp(env, typ):
            for abschnitt in abschnitte:
                abschnitt(env, bot)


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
# /cam list, die Action-Bar mit dem Abstand, die Fluggeschwindigkeit, die
# Nachtsicht und die Toene
# ---------------------------------------------------------------------------

def zeilen_seit(bot, since):
    """Was seit der Marke im Chat und in der Action-Bar stand, ohne Farbcodes."""
    return [strip_colors(m["text"])
            for m in bot.call("messages", since=since).get("messages", [])]


def gesagt(zeilen, muster):
    return any(re.search(muster, z) for z in zeilen)


def zeige(zeilen):
    return "; ".join(z for z in zeilen if z) or "nichts"


def liste_checks(env, bot):
    """/cam list und wer es benutzen darf, cam-list.access.

    admin heisst: wer camplugin.list hat, und das haben Operatoren von
    selbst. all heisst: jeder, der /cam benutzen darf, false: kein Spieler.
    Die Konsole darf immer. Geprueft wird mit dem Bot einmal mit und einmal
    ohne op; das op bekommt er am Ende in jedem Fall zurueck.
    """
    def liste():
        since = bot.mark()
        bot.chat("/cam list")
        time.sleep(1.5)
        return zeilen_seit(bot, since)

    try:
        cam_off(bot)
        z = liste()
        FIND.test("/cam list: niemand im Cam-Modus", gesagt(z, r"Nobody is in cam mode"), zeige(z))

        if FIND.test("Cam-Modus fuer /cam list gestartet", cam_on(bot), ""):
            z = liste()
            FIND.test("/cam list nennt den Spieler im Cam-Modus, seit wann und wie weit vom Koerper",
                      gesagt(z, r"In cam mode \(1\)")
                      and gesagt(z, rf"{BOT_NAME}: for .+, \d+ blocks from the body"), zeige(z))
            vorher = len(server_log(env))
            console(env, "cam list", pause=1.5)
            # Die Konsole schreibt Farben womoeglich als ANSI-Folgen ins Log.
            log = re.sub(r"\x1b\[[0-9;]*m", "", strip_colors(server_log(env)[vorher:]))
            FIND.test("/cam list geht auch von der Konsole", f"{BOT_NAME}: for" in log,
                      log.strip()[-200:] or "nichts im Log")
            cam_off(bot)

        set_option(env, "access", "false", "cam-list")
        z = liste()
        FIND.test("cam-list.access: false sperrt auch einen Operator", gesagt(z, r"permission"), zeige(z))

        set_option(env, "access", "admin", "cam-list")
        console(env, f"deop {BOT_NAME}", pause=1.5)
        z = liste()
        FIND.test("cam-list.access: admin sperrt einen Spieler ohne op", gesagt(z, r"permission"), zeige(z))

        set_option(env, "access", "all", "cam-list")
        z = liste()
        FIND.test("cam-list.access: all laesst auch einen Spieler ohne op",
                  gesagt(z, r"Nobody is in cam mode"), zeige(z))
    finally:
        console(env, f"op {BOT_NAME}", pause=1.5)
        set_option(env, "access", "admin", "cam-list")


# Die Pfeile der Action-Bar, die zur Seite zeigen - von vorn im Uhrzeigersinn.
PFEILE_ZUR_SEITE = ("\u2191", "\u2197", "\u2192", "\u2198", "\u2193", "\u2199", "\u2190", "\u2196")


def abstaende(zeilen):
    """Die Abstaende aus den Zeilen "Body: N blocks", mit dem Pfeil dahinter."""
    return [(int(m.group(1)), m.group(2)) for z in zeilen
            for m in [re.search(r"Body: (\d+) blocks (\S+)", z)] if m]


def aktionsleiste_checks(env, bot):
    """action-bar.display: distance und text.

    distance ist voreingestellt - nachgesehen und nicht gesetzt. Zu Beginn
    kommt auch dort "Cam mode activated", danach "Body: N blocks" mit einem
    Pfeil, am Koerper ein Punkt. Zehn Bloecke ueber dem Koerper steht dort 10
    und ein Pfeil nach unten, drei Bloecke daneben noch immer: Steiler als
    60 Grad zeigt er nach unten. Acht Bloecke daneben ist es flacher, und er
    zeigt zur Seite. Hinauf geht es mit 'fly' und nicht mit /tp: Der Bot
    fiele danach mit der Physik des Clients wieder hinunter, bevor die Zeile
    kommt. text ist die alte feste Zeile, ohne Abstand. false zeigt die
    Startzeile nur zu Beginn - mit on-duration 60 ein einziges Mal -, danach
    kommt nichts mehr.
    """
    text = (env.server / "plugins" / "CamFly" / "config.yml").read_text(encoding="utf-8")
    FIND.test("Voreingestellt steht action-bar.display: distance da",
              re.search(r"(?m)^\s*display:\s*distance\s*$", text) is not None, "")
    try:
        cam_off(bot)
        since = bot.mark()
        if not FIND.test("Cam-Modus fuer die Action-Bar gestartet", cam_on(bot), ""):
            return
        time.sleep(3.5)
        z = zeilen_seit(bot, since)
        nah = abstaende(z)
        FIND.test("display: distance - erst Cam mode activated, dann der Abstand zum Koerper",
                  gesagt(z, r"[Cc]am mode activated") and bool(nah), zeige(z[-3:]))
        FIND.test("display: distance - am Koerper ist der Abstand hoechstens 1, mit einem Punkt",
                  bool(nah) and nah[-1][0] <= 1 and nah[-1][1] == "\u25cf", str(nah[-3:]))

        bot.call("fly", wait=30, dy=10, timeout=15000)
        time.sleep(1.0)
        since = bot.mark()
        time.sleep(1.0)
        weit = abstaende(zeilen_seit(bot, since))
        FIND.test("display: distance - zehn Bloecke ueber dem Koerper: 10 und ein Pfeil nach unten",
                  bool(weit) and weit[-1] == (10, "\u25bc"), str(weit[-3:]))

        bot.call("fly", wait=30, dx=3, timeout=15000)
        time.sleep(1.0)
        since = bot.mark()
        time.sleep(1.0)
        schraeg = abstaende(zeilen_seit(bot, since))
        FIND.test("display: distance - zehn hoch und drei daneben (73 Grad): noch nach unten",
                  bool(schraeg) and schraeg[-1] == (10, "\u25bc"), str(schraeg[-3:]))

        bot.call("fly", wait=30, dx=5, timeout=15000)
        time.sleep(1.0)
        since = bot.mark()
        time.sleep(1.0)
        flach = abstaende(zeilen_seit(bot, since))
        FIND.test("display: distance - zehn hoch und acht daneben (51 Grad): zur Seite",
                  bool(flach) and flach[-1][0] == 13 and flach[-1][1] in PFEILE_ZUR_SEITE,
                  str(flach[-3:]))
        cam_off(bot)

        set_option(env, "display", "text", "action-bar")
        since = bot.mark()
        cam_on(bot)
        time.sleep(3.0)
        z = zeilen_seit(bot, since)
        FIND.test("display: text - nur die feste Zeile, kein Abstand",
                  gesagt(z, r"[Cc]am mode activated") and not abstaende(z), zeige(z[-3:]))
        cam_off(bot)

        set_option(env, "display", "false", "action-bar")
        since = bot.mark()
        cam_on(bot)
        z = zeilen_seit(bot, since)
        FIND.test("display: false - zu Beginn die Startzeile mit dem Hinweis",
                  gesagt(z, r"[Cc]am mode activated") and gesagt(z, r"to leave"), zeige(z))
        since = bot.mark()
        time.sleep(5.0)
        z = zeilen_seit(bot, since)
        FIND.test("display: false - danach kommt nichts mehr, weder die Startzeile noch der Abstand",
                  not gesagt(z, r"[Cc]am mode activated") and not abstaende(z), zeige(z))
    finally:
        cam_off(bot)
        set_option(env, "display", "distance", "action-bar")


def tempo_checks(env, bot):
    """Die Fluggeschwindigkeit: flight-speed.start und das Mausrad.

    Das Mausrad schickt dem Server nur einen neuen Platz in der Hotbar, und
    genau das tut mineflayer mit setQuickBarSlot. Von Platz 0 auf 8 ist ein
    Dreh nach oben (schneller), auf 1 einer nach unten. Der Server laesst den
    Platz bei 0; vor jedem Dreh setzt sich der Bot deshalb selbst wieder auf 0,
    falls er die Antwort des Servers nicht uebernommen hat - der Server sieht
    darin keinen Wechsel.

    Gelesen wird abilities.flySpeed. Das Spiel fuehrt dort die Haelfte dessen,
    was Bukkit setFlySpeed nennt: 0.05 ist die normale Geschwindigkeit 1.
    """
    def tempo():
        return bot.server_data("abilities.flySpeed")

    def gleich(wert, soll):
        return wert is not None and abs(wert - soll) < 0.001

    def drehen(hoch):
        bot.call("hotbar", slot=0)
        time.sleep(0.3)
        bot.call("hotbar", slot=8 if hoch else 1)
        time.sleep(0.8)

    try:
        cam_off(bot)
        bot.call("hotbar", slot=0)
        time.sleep(0.5)
        vorher = tempo()
        if not FIND.test("Cam-Modus fuer die Fluggeschwindigkeit gestartet", cam_on(bot), ""):
            return
        FIND.test("flight-speed.start: 1 - im Cam-Modus normal schnell", gleich(tempo(), 0.05), str(tempo()))
        since = bot.mark()
        drehen(True)
        schneller = tempo()
        FIND.test("Mausrad hoch - schneller, eine Stufe von 0.5", gleich(schneller, 0.075), str(schneller))
        z = zeilen_seit(bot, since)
        FIND.test("Die Action-Bar sagt die neue Geschwindigkeit", gesagt(z, r"Flight speed: 1\.5x"), zeige(z))
        platz = bot.server_data("SelectedItemSlot")
        FIND.test("Der Platz in der Hotbar bleibt dabei, wie er war", platz == 0, str(platz))
        drehen(False)
        FIND.test("Mausrad runter - wieder normal", gleich(tempo(), 0.05), str(tempo()))
        for _ in range(6):
            drehen(True)
        FIND.test("Nicht schneller als flight-speed.max (3)", gleich(tempo(), 0.15), str(tempo()))
        for _ in range(8):
            drehen(False)
        FIND.test("Nicht langsamer als flight-speed.min (0.5)", gleich(tempo(), 0.025), str(tempo()))
        cam_off(bot)
        FIND.test("Nach dem Cam-Modus wieder die Geschwindigkeit von vorher",
                  gleich(tempo(), vorher), f"{vorher} -> {tempo()}")

        set_option(env, "mouse-wheel", "false", "flight-speed")
        cam_on(bot)
        drehen(True)
        FIND.test("mouse-wheel: false - das Mausrad aendert nichts", gleich(tempo(), 0.05), str(tempo()))
        platz = bot.server_data("SelectedItemSlot")
        FIND.test("mouse-wheel: false - der Platz in der Hotbar wechselt wie sonst", platz == 8, str(platz))
    finally:
        cam_off(bot)
        set_option(env, "mouse-wheel", "true", "flight-speed")
        bot.call("hotbar", slot=0)


NACHTSICHT = '{active_effects:[{id:"minecraft:night_vision"}]}'
NACHTSICHT_DAUER = 'active_effects[{id:"minecraft:night_vision"}].duration'


def nachtsicht_checks(env, bot):
    """night-vision.mode: dark, true und false.

    dark ist voreingestellt. Der Bot steht auf der Flachwelt unter freiem
    Himmel: mittags ist es dort hell (15), um Mitternacht dunkel (4) - das
    Licht am Auge rechnet die Tageszeit mit. Die Nachtsicht des Cam-Modus ist
    endlos (Dauer -1); eine eigene, die er vorher hatte, ruht im Cam-Modus und
    kommt danach mit ihrer Restzeit zurueck.
    """
    def hat():
        return nbt_frage(bot, "@s", NACHTSICHT)

    def zeit(wann):
        bot.chat(f"/time set {wann}")
        time.sleep(1.5)

    try:
        cam_off(bot)
        bot.chat("/effect clear @s")
        bot.chat("/weather clear")
        zeit("noon")
        if not FIND.test("Cam-Modus fuer die Nachtsicht gestartet", cam_on(bot), ""):
            return
        time.sleep(1.0)
        FIND.test("night-vision: dark - am hellen Tag keine Nachtsicht", not hat(), "")
        zeit("midnight")
        dauer = bot.server_data(NACHTSICHT_DAUER)
        FIND.test("night-vision: dark - um Mitternacht unter freiem Himmel Nachtsicht, endlos",
                  dauer == -1, str(dauer))
        zeit("noon")
        FIND.test("night-vision: dark - wieder Tag, wieder ohne", not hat(), "")
        zeit("midnight")
        cam_off(bot)
        time.sleep(0.5)
        FIND.test("Nach dem Cam-Modus ist die Nachtsicht wieder weg", not hat(), "")

        bot.chat("/effect give @s minecraft:night_vision 120 0")
        time.sleep(0.5)
        cam_on(bot)
        time.sleep(1.0)
        dauer = bot.server_data(NACHTSICHT_DAUER)
        FIND.test("Im Cam-Modus ruht die eigene Nachtsicht, die des Cam-Modus ist da",
                  dauer == -1, str(dauer))
        cam_off(bot)
        time.sleep(0.5)
        dauer = bot.server_data(NACHTSICHT_DAUER)
        FIND.test("Die eigene Nachtsicht kommt nach dem Cam-Modus mit ihrer Restzeit zurueck",
                  dauer is not None and 0 < dauer <= 2400, str(dauer))
        bot.chat("/effect clear @s")

        set_option(env, "mode", "false", "night-vision")
        cam_on(bot)
        time.sleep(1.0)
        FIND.test("night-vision: false - auch um Mitternacht keine", not hat(), "")
        cam_off(bot)

        set_option(env, "mode", "true", "night-vision")
        zeit("noon")
        cam_on(bot)
        time.sleep(1.0)
        FIND.test("night-vision: true - auch am hellen Tag", hat(), "")
    finally:
        cam_off(bot)
        set_option(env, "mode", "dark", "night-vision")
        bot.chat("/time set noon")
        bot.chat("/effect clear @s")
        time.sleep(0.5)


def toene_checks(env, bot):
    """Die Toene von sounds: beim Start, beim Ende je nach Grund und an der
    Grenze.

    Nachgesehen wird an den Paketen, die beim Bot ankommen. Das Ende kommt
    einmal mit /cam (end), einmal von aussen mit /cam reload (end-forced) und
    einmal von einem Treffer auf den Koerper (end-body) - mit Resistenz, und
    ohne cam-safety, die den naechsten Start sonst fuenf Sekunden sperrte.
    Die Grenze ist max-distance, dafuer auf GRENZ_ABSTAND gestellt; der Bot
    laeuft wie im Grenztest nach Osten, auf einem frisch geraeumten Streifen.
    """
    def anzahl():
        return bot.call("sounds", since=10 ** 9).get("count", 0)

    def gehoert(seit, name):
        toene = [t["text"] for t in bot.call("sounds", since=seit).get("sounds", [])]
        return any(name in t for t in toene), toene

    try:
        cam_off(bot)
        seit = anzahl()
        cam_on(bot)
        time.sleep(1.0)
        da, toene = gehoert(seit, "block.beacon.activate")
        FIND.test("sounds.start - der Ton beim Start", da, f"{len(toene)} Toene")
        seit = anzahl()
        cam_off(bot)
        time.sleep(1.0)
        da, toene = gehoert(seit, "block.beacon.deactivate")
        FIND.test("sounds.end - der Ton beim Ende mit /cam", da, f"{len(toene)} Toene")

        cam_on(bot)
        seit = anzahl()
        console(env, "cam reload", pause=2)
        da, toene = gehoert(seit, "block.respawn_anchor.deplete")
        anders, _ = gehoert(seit, "block.beacon.deactivate")
        FIND.test("sounds.end-forced - ein eigener Ton, wenn /cam reload den Cam-Modus beendet",
                  da and not anders, f"{len(toene)} Toene")

        bot.chat(f"/effect give {BOT_NAME} minecraft:resistance infinite 255 true")
        set_option(env, "enabled", "false", "cam-safety")
        if cam_on(bot):
            koerper = bot.server_pos()
            if koerper is not None:
                x, y, z = koerper
                hinstellen(bot, x - 6, _floor(y + 0.5), z)
                seit = anzahl()
                console(env, f"damage @e[type=minecraft:mannequin,x={x},y={y},z={z},distance=..1,limit=1] "
                             f"1 minecraft:cactus", pause=2)
                da, toene = gehoert(seit, "entity.illusioner.mirror_move")
                anders, _ = gehoert(seit, "block.beacon.deactivate")
                FIND.test("sounds.end-body - ein eigener Ton, wenn der Koerper getroffen wird",
                          da and not anders, f"{len(toene)} Toene")

        pos = bot.server_pos()
        if pos is None:
            FIND.test("Standort fuer den Ton an der Grenze lesbar", False, "")
            return
        bx, by, bz = _floor(pos[0]), _floor(pos[1] + 0.5), _floor(pos[2]) - 20
        bot.chat(f"/fill {bx - 2} {by} {bz - 3} {bx + 16} {by + 3} {bz + 3} minecraft:air")
        bot.chat(f"/fill {bx - 2} {by - 1} {bz - 3} {bx + 16} {by - 1} {bz + 3} minecraft:grass_block")
        time.sleep(1.0)
        set_option(env, "max-distance", f"{GRENZ_ABSTAND}.0")
        seit = anzahl()
        ergebnis = grenz_lauf(bot, (bx + 0.5, by, bz + 0.5))
        da, toene = gehoert(seit, "block.note_block.bass")
        FIND.test("sounds.border - der Ton an der Grenze", da,
                  grenz_zeige(ergebnis, (bx + 0.5, by, bz + 0.5)))
    finally:
        cam_off(bot)
        bot.chat(f"/effect clear {BOT_NAME}")
        set_options(env, [("max-distance", "100.0"), ("enabled", "true", "cam-safety")])



def absturz_checks(env, bot):
    """crash-protection: die Datei unter plugins/CamFly/playerdata/crash, und was
    nach einem Absturz aus ihr wird.

    Im Cam-Modus liegt eine Datei da, nach dem Ende nicht mehr; mit
    enabled: false gibt es gar keine. Fuer den Absturz traegt der Bot sieben
    Diamanten, steht im Ueberlebensmodus und fliegt im Cam-Modus sechs Bloecke
    hoch, schneller gedreht. save-all schreibt ihn so auf die Platte, wie er
    im Cam-Modus ist - ohne Diamanten, im Abenteuermodus, in der Luft -, und
    dann wird der Server mit SIGKILL beendet, ohne dass das Plugin noch etwas
    tun koennte. Nach dem Neustart muss der Bot alles zurueckhaben, am Koerper
    stehen, und die Datei muss weg sein.

    Der Neustart beginnt ein neues server.log: Was im alten stand, wird
    deshalb vorher geprueft. Der Test steht am Ende, damit kein anderer nach
    ihm auf einem frisch gestarteten Server mit zurueckgesetzter
    Konfiguration landet.
    """
    ordner = env.server / "plugins" / "CamFly" / "playerdata" / "crash"

    def dateien():
        return sorted(p.name for p in ordner.glob("*.yml")) if ordner.exists() else []

    if not FIND.test("Cam-Modus ist vor dem Absturztest aus", cam_off(bot), ""):
        return
    cam_on(bot)
    time.sleep(0.5)
    FIND.test("crash-protection: im Cam-Modus liegt eine Datei unter playerdata/crash",
              len(dateien()) == 1, str(dateien()))
    if dateien():
        kopf = (ordner / dateien()[0]).read_text(encoding="utf-8").splitlines()[:3]
        FIND.test("crash-protection: oben in der Datei steht, wozu sie da ist, mit der Warnung",
                  len(kopf) == 3
                  and kopf[0] == "# CamFly crash protection: backup of what cam mode took from this player."
                  and kopf[1] == "# Only used after a server crash, on the next join. Deleted when cam mode ends."
                  and kopf[2] == "# \u26a0 Do not edit or delete it while the server is running! \u26a0",
                  str(kopf))
    cam_off(bot)
    time.sleep(0.5)
    FIND.test("crash-protection: nach dem Ende ist die Datei wieder weg", not dateien(), str(dateien()))

    set_option(env, "enabled", "false", "crash-protection")
    cam_on(bot)
    time.sleep(0.5)
    FIND.test("crash-protection.enabled: false - keine Datei", not dateien(), str(dateien()))
    cam_off(bot)
    set_option(env, "enabled", "true", "crash-protection")

    bot.chat("/clear @s")
    bot.chat("/give @s minecraft:diamond 7")
    bot.chat("/gamemode survival")
    time.sleep(1.0)
    heim = bot.server_pos()
    if not FIND.test("Cam-Modus fuer den Absturz gestartet", heim is not None and cam_on(bot), ""):
        return
    bot.call("fly", wait=30, dy=6, timeout=15000)
    bot.call("hotbar", slot=0)
    time.sleep(0.3)
    bot.call("hotbar", slot=8)
    time.sleep(0.5)
    console(env, "save-all", pause=3)
    hits = log_problems(env)
    FIND.test("Server-Log vor dem Absturz ohne Fehler des Plugins", not hits,
              "" if not hits else "; ".join(hits[:5]))

    ereignisse = len(bot.events)
    pgid = server_running(env)
    if pgid is not None:
        os.killpg(pgid, signal.SIGKILL)
    for _ in range(40):
        if server_running(env) is None:
            break
        time.sleep(0.25)
    (env.server / "server.pgid").unlink(missing_ok=True)
    FIND.test("Nach dem Absturz liegt die Datei noch da", len(dateien()) == 1, str(dateien()))
    bot.stop()
    # Was der Absturz beim Bot ausloest - die Verbindung reisst ab -, ist
    # kein Fehler des Plugins.
    del bot.events[ereignisse:]

    if not step_server(env):
        return
    bot.start()
    if not FIND.test("Bot verbindet sich nach dem Neustart",
                     bot.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
        return
    time.sleep(3)
    z = zeilen_seit(bot, 0)
    FIND.test("Nach dem Neustart: die Meldung, dass er alles zurueckhat",
              gesagt(z, r"server went down while you were in cam mode"), zeige(z))
    FIND.test("Nach dem Neustart: die Diamanten sind wieder da",
              nbt_frage(bot, "@s", '{Inventory:[{id:"minecraft:diamond"}]}'), "")
    FIND.test("Nach dem Neustart: wieder im Ueberlebensmodus", spielmodus_ist(bot, "survival"), "")
    pos = bot.server_pos()
    FIND.test("Nach dem Neustart: er steht wieder am Koerper",
              pos is not None and math.dist(pos, heim) < 1.5, f"{heim} -> {pos}")
    FIND.test("Nach dem Neustart: keine Unsichtbarkeit mehr",
              not nbt_frage(bot, "@s", '{active_effects:[{id:"minecraft:invisibility"}]}'), "")
    tempo = bot.server_data("abilities.flySpeed")
    FIND.test("Nach dem Neustart: die Fluggeschwindigkeit ist wieder normal",
              tempo is not None and abs(tempo - 0.05) < 0.001, str(tempo))
    FIND.test("Nach dem Neustart: die Datei ist geloescht", not dateien(), str(dateien()))
    hx, hy, hz = heim
    FIND.test("Nach dem Neustart: kein Koerper liegt mehr herum",
              not server_says(bot, f"/execute positioned {hx} {hy} {hz} if entity "
                                   f"@e[type=minecraft:armor_stand,distance=..2] run say {{marke}}"), "")
    bot.chat("/clear @s")
    time.sleep(0.5)


# ---------------------------------------------------------------------------
# Taste zum Beenden, Protokoll, Akku, Startkosten und Statistik
# ---------------------------------------------------------------------------

def befehl(bot, text, warten=1.5):
    """Einen Befehl in den Chat, und was danach ankommt, ohne Farbcodes."""
    since = bot.mark()
    bot.chat(text)
    time.sleep(warten)
    return zeilen_seit(bot, since)


def neu_anmelden(bot, dazwischen=None):
    """Den Bot ab- und wieder anmelden, mit dazwischen() dazwischen.

    Was das Abmelden bei ihm an Ereignissen hinterlaesst - die Verbindung
    endet -, ist kein Fehler des Plugins und wird verworfen.
    """
    ereignisse = len(bot.events)
    bot.stop()
    time.sleep(2)
    del bot.events[ereignisse:]
    if dazwischen is not None:
        dazwischen()
    bot.start()
    da = bool(bot.call("wait_spawn", wait=90, timeout=75000).get("spawned"))
    time.sleep(2)
    return da


def spielerdatei(env):
    """Die Datei des Bots unter plugins/CamFly/playerdata/stats, oder None."""
    ordner = env.server / "plugins" / "CamFly" / "playerdata" / "stats"
    if not ordner.exists():
        return None
    for datei in ordner.glob("*.yml"):
        if re.search(rf"(?m)^name:\s*{BOT_NAME}\s*$", datei.read_text(encoding="utf-8")):
            return datei
    return None


def taste_checks(env, bot):
    """exit-key, der Hinweis darauf in der Action-Bar und action-bar.on-duration.

    Voreingestellt ist exit-key an - nachgesehen in der ausgelieferten
    config.yml. Zu Beginn steht in der Action-Bar "Cam mode activated" mit
    dem Hinweis "or type /cam to leave". Die Taste zum Tauschen der Haende beendet den
    Cam-Modus, und der Stein, den er vorher hielt, ist danach wieder in der
    Hand und nicht in der anderen. Gegenprobe: ohne Cam-Modus tauscht
    dieselbe Taste die Haende wie immer. Mit exit-key: false nennt der
    Hinweis nur noch /cam, und die Taste laesst den Cam-Modus laufen. on-duration: 100
    haelt die Startzeile fuenf Sekunden, ehe der Abstand kommt; mit 0 kommt
    gleich der Abstand.

    Ob der Cam-Modus laeuft, sagt der Spielmodus: Er laeuft im
    Abenteuermodus, der Bot steht sonst in dem, in dem er ankam - gesetzt
    wird keiner, das nimmt ihm das Fliegen, auf das spaetere Abschnitte
    zaehlen.
    """
    def stein_in_der_hand():
        return nbt_frage(bot, "@s", '{Inventory:[{Slot:0b,id:"minecraft:stone"}]}')

    def stein_in_der_anderen():
        return (nbt_frage(bot, "@s", '{equipment:{offhand:{id:"minecraft:stone"}}}')
                or nbt_frage(bot, "@s", '{Inventory:[{Slot:-106b,id:"minecraft:stone"}]}'))

    def taste():
        since = bot.mark()
        bot.call("swap_hands")
        time.sleep(1.5)
        return zeilen_seit(bot, since)

    quelle = (env.repo / "src" / "main" / "resources" / "config.yml").read_text(encoding="utf-8")
    FIND.test("exit-key ist voreingestellt an",
              re.search(r"(?m)^exit-key:\n\s+enabled:\s*true\s*$", quelle) is not None, "")
    try:
        cam_off(bot)
        modus = next((m for m in ("survival", "creative") if spielmodus_ist(bot, m)), "survival")
        bot.chat("/clear @s")
        bot.call("hotbar", slot=0)
        bot.chat(f"/give {BOT_NAME} minecraft:stone 1")
        time.sleep(1)
        taste()
        FIND.test("Gegenprobe: ohne Cam-Modus tauscht die Taste die Haende",
                  stein_in_der_anderen() and not stein_in_der_hand(), "")
        taste()

        z = befehl(bot, "/cam", warten=2)
        # mineflayer schreibt die Taste so, wie sie im Paket steht: als Namen,
        # den der echte Client durch die belegte Taste ersetzt.
        FIND.test("exit-key: zu Beginn steht der Hinweis auf die Taste und /cam in der Action-Bar",
                  gesagt(z, r"[Cc]am mode activated")
                  and gesagt(z, r"key\.swapOffhand or type /cam to leave"), zeige(z))
        z = taste()
        FIND.test("exit-key: die Taste beendet den Cam-Modus",
                  gesagt(z, r"[Cc]am mode ended") and spielmodus_ist(bot, modus), zeige(z))
        FIND.test("exit-key: der Stein ist danach in der Hand und nicht in der anderen",
                  stein_in_der_hand() and not stein_in_der_anderen(), "")

        set_option(env, "enabled", "false", "exit-key")
        z = befehl(bot, "/cam", warten=2)
        FIND.test("exit-key: false - der Hinweis nennt nur noch /cam, keine Taste",
                  gesagt(z, r"[Cc]am mode activated") and gesagt(z, r"- type /cam to leave")
                  and not gesagt(z, r"swapOffhand"), zeige(z))
        taste()
        FIND.test("exit-key: false - die Taste laesst den Cam-Modus laufen", spielmodus_ist(bot, "adventure"), "")
        cam_off(bot)
        set_option(env, "enabled", "true", "exit-key")

        set_option(env, "on-duration", "100", "action-bar")
        z = befehl(bot, "/cam", warten=3.5)
        FIND.test("action-bar.on-duration: 100 - nach 3,5 Sekunden steht noch die Startzeile",
                  gesagt(z, r"[Cc]am mode activated") and not abstaende(z), zeige(z))
        since = bot.mark()
        time.sleep(2.5)
        z = zeilen_seit(bot, since)
        FIND.test("action-bar.on-duration: 100 - nach 6 Sekunden steht der Abstand",
                  bool(abstaende(z)), zeige(z))
        cam_off(bot)

        set_option(env, "on-duration", "0", "action-bar")
        z = befehl(bot, "/cam", warten=2)
        FIND.test("action-bar.on-duration: 0 - gleich der Abstand, ohne Startzeile",
                  bool(abstaende(z)) and not gesagt(z, r"[Cc]am mode activated"), zeige(z))
    finally:
        cam_off(bot)
        bot.chat("/clear @s")
        set_options(env, [("enabled", "true", "exit-key"), ("on-duration", "60", "action-bar")])


def protokoll_checks(env, bot):
    """cam-log: Start und Ende des Cam-Modus in plugins/CamFly/logs/<Datum>.log.

    Voreingestellt wird nichts geschrieben. Eingeschaltet steht beim Start
    eine Zeile mit Namen, UUID und dem Platz des Koerpers da, beim Ende eine
    mit dem Grund, der Dauer, der Strecke - der Bot fliegt dafuer sechs
    Bloecke hoch - und dem letzten Platz der Kamera. Ein Ende durch
    /cam reload heisst reload. console: true schreibt dieselben Zeilen ins
    Server-Log. keep-days loescht eine Datei vom 1. Januar 2000, eine
    Datei, die nicht nach einem Tag heisst, bleibt liegen.
    """
    ordner = env.server / "plugins" / "CamFly" / "logs"
    alt = ordner / "2000-01-01.log"
    fremd = ordner / "notizen.log"

    def zeilen():
        datei = ordner / f"{time.strftime('%Y-%m-%d')}.log"
        return datei.read_text(encoding="utf-8").splitlines() if datei.exists() else []

    try:
        cam_off(bot)
        vorher = len(zeilen())
        cam_on(bot)
        cam_off(bot)
        FIND.test("cam-log.enabled: false - nichts wird geschrieben", len(zeilen()) == vorher,
                  "; ".join(zeilen()[vorher:]))

        set_option(env, "enabled", "true", "cam-log")
        vorher = len(zeilen())
        if not FIND.test("Cam-Modus fuer das Protokoll gestartet", cam_on(bot), ""):
            return
        bot.call("fly", wait=30, dy=6, timeout=15000)
        time.sleep(1)
        cam_off(bot)
        neu = zeilen()[vorher:]
        FIND.test("cam-log: der Start steht mit Name, UUID und Platz des Koerpers da",
                  any(re.fullmatch(rf"\d\d:\d\d:\d\d {BOT_NAME} \([0-9a-f-]{{36}}\) started cam mode "
                                   rf"at world -?\d+ -?\d+ -?\d+", z) for z in neu), "; ".join(neu))
        ende = [m for z in neu for m in [re.search(
            rf"{BOT_NAME} ended cam mode \(command\) after .+, flew (\d+) blocks, at most (\d+) blocks "
            rf"from the body, camera last at world -?\d+ -?\d+ -?\d+$", z)] if m]
        FIND.test("cam-log: das Ende steht mit Grund, Dauer, Strecke und letztem Platz da",
                  bool(ende) and int(ende[0].group(1)) >= 4 and int(ende[0].group(2)) >= 4, "; ".join(neu))

        cam_on(bot)
        vorher = len(zeilen())
        console(env, "cam reload", pause=2)
        neu = zeilen()[vorher:]
        FIND.test("cam-log: ein Ende durch /cam reload heisst reload",
                  any(f"{BOT_NAME} ended cam mode (reload)" in z for z in neu), "; ".join(neu))

        set_option(env, "console", "true", "cam-log")
        vorher = len(server_log(env))
        cam_on(bot)
        cam_off(bot)
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("cam-log.console: true - die Zeilen stehen auch im Server-Log",
                  re.search(rf"\[cam-log\] {BOT_NAME} \([0-9a-f-]{{36}}\) started cam mode", log) is not None
                  and f"[cam-log] {BOT_NAME} ended cam mode (command)" in log, "")

        ordner.mkdir(parents=True, exist_ok=True)
        alt.write_text("alt\n", encoding="utf-8")
        fremd.write_text("bleibt\n", encoding="utf-8")
        # Der Reload laesst das Protokoll beim naechsten Eintrag aufraeumen.
        set_option(env, "keep-days", "30", "cam-log")
        cam_on(bot)
        cam_off(bot)
        FIND.test("cam-log.keep-days: eine Datei von vor 30 Tagen ist weg, eine fremde bleibt",
                  not alt.exists() and fremd.exists(), f"alt da: {alt.exists()}, fremd da: {fremd.exists()}")
    finally:
        cam_off(bot)
        alt.unlink(missing_ok=True)
        fremd.unlink(missing_ok=True)
        set_options(env, [("enabled", "false", "cam-log"), ("console", "false", "cam-log")])


def akku_checks(env, bot):
    """flight-limit: battery - der Akku leert sich im Cam-Modus und laedt sich
    draussen wieder auf.

    Der Bot faengt mit einem frischen Akku an: Seine Datei unter
    playerdata/stats wird geloescht, waehrend er abgemeldet ist. Der Akku
    haelt 5 Sekunden, laedt sich mit 0.01 pro Sekunde so gut wie nicht auf,
    und ein Start braucht 2. Im Flug zeigt die Bossbar den Akku. Nach gut
    fuenf Sekunden endet der Cam-Modus mit der Meldung dazu, und die Bossbar
    zeigt das Laden - auch nach dem Neuanmelden. Ein neuer Start wird
    abgelehnt und nennt die Zeit, bis es wieder reicht, und /cam stats zeigt
    den leeren Akku. Mit 1 pro Sekunde reicht es nach ein paar Sekunden
    wieder, und ist er voll, verschwindet die Bossbar. Mit
    show-bossbar-charging: false gibt es nach dem Ende keine.
    """
    def loeschen():
        datei = spielerdatei(env)
        if datei is not None:
            datei.unlink()

    def balken(muster):
        titel = [b.get("title", "") for b in bot.call("bossbars").get("bars", [])]
        return any(re.search(muster, t) for t in titel), titel

    try:
        cam_off(bot)
        if not FIND.test("Bot meldet sich fuer einen frischen Akku neu an", neu_anmelden(bot, loeschen), ""):
            return
        set_options(env, [("flight-limit", "battery"), ("capacity", "5", "battery"),
                          ("recharge-per-second", "0.01", "battery"), ("min-start", "2", "battery")])
        if not FIND.test("Cam-Modus mit vollem Akku gestartet", cam_on(bot), ""):
            return
        start = time.time() - 1.5
        da, titel = balken(r"Battery: \d")
        FIND.test("battery: im Flug zeigt die Bossbar den Akku", da, str(titel))
        since = bot.mark()
        leer = bot.expect(r"battery is empty", since, 10000)
        dauer = time.time() - start
        FIND.test("battery: nach 5 Sekunden ist der Akku leer und der Cam-Modus endet",
                  bool(leer) and 3.5 <= dauer <= 8.0, f"nach {dauer:.1f} s")
        time.sleep(1)
        da, titel = balken(r"Battery charging: 0 seconds, full in")
        FIND.test("battery: nach dem Ende zeigt die Bossbar, wie er laedt", da, str(titel))

        z = befehl(bot, "/cam")
        FIND.test("battery: mit leerem Akku startet der Cam-Modus nicht, und er erfaehrt, wie lange es dauert",
                  gesagt(z, r"battery holds 0 seconds of flight, a start needs 2 seconds\. "
                            r"Ready again in 3m \d+s") and not gesagt(z, r"[Cc]am mode activated"), zeige(z))
        z = befehl(bot, "/cam stats")
        FIND.test("battery: /cam stats zeigt den Ladestand", gesagt(z, r"Battery: 0 seconds of 5 seconds"), zeige(z))
        datei = spielerdatei(env)
        FIND.test("battery: der Ladestand steht in der Datei unter playerdata/stats",
                  datei is not None and "charge:" in datei.read_text(encoding="utf-8"), str(datei))
        if FIND.test("Bot meldet sich mit leerem Akku neu an", neu_anmelden(bot), ""):
            da, titel = balken(r"Battery charging")
            FIND.test("battery: nach dem Betreten zeigt die Bossbar weiter, wie er laedt", da, str(titel))

        set_option(env, "recharge-per-second", "1.0", "battery")
        time.sleep(2)
        FIND.test("battery: mit 1 pro Sekunde reicht es nach ein paar Sekunden wieder", cam_on(bot), "")
        cam_off(bot)
        time.sleep(7)
        da, titel = balken(r"Battery")
        FIND.test("battery: ist er wieder voll, verschwindet die Bossbar", not da, str(titel))

        set_option(env, "show-bossbar-charging", "false", "battery")
        cam_on(bot)
        cam_off(bot)
        da, titel = balken(r"Battery")
        FIND.test("battery.show-bossbar-charging: false - nach dem Ende keine Bossbar", not da, str(titel))

        set_option(env, "flight-limit", "time-limit")
        z = befehl(bot, "/cam stats")
        FIND.test("flight-limit: time-limit - kein Akku mehr in /cam stats", not gesagt(z, r"Battery"), zeige(z))
    finally:
        cam_off(bot)
        set_options(env, [("flight-limit", "false"), ("capacity", "300", "battery"),
                          ("recharge-per-second", "0.5", "battery"), ("min-start", "10", "battery"),
                          ("show-bossbar-charging", "true", "battery")])


def kosten_checks(env, bot):
    """start-cost: was ein Start kostet - Erfahrung, Items und Geld.

    Ein Start kostet 2 Level und 3 Diamanten. Ohne beides wird er abgelehnt,
    und die Meldung nennt Kosten und was fehlt. Mit 3 Leveln und 5 Diamanten
    startet er, die Meldung sagt, was es gekostet hat, und danach sind 1
    Level und 2 Diamanten uebrig. Reicht es nur fuer einen Teil, wird gar
    nichts genommen. Mit xp-type: points kostet er 10 Punkte: Von 25 bleiben
    15, das ist Level 1, und mit 5 fehlen 5. Geld ohne Vault: Der Start
    kostet nichts, und die Konsole sagt es einmal.
    """
    def bestand(diamanten, level):
        return (nbt_frage(bot, "@s", f'{{Inventory:[{{id:"minecraft:diamond",count:{diamanten}}}]}}')
                and nbt_frage(bot, "@s", f"{{XpLevel:{level}}}"))

    def punkte(anzahl):
        bot.chat("/xp set @s 0 levels")
        bot.chat("/xp set @s 0 points")
        bot.chat(f"/xp add @s {anzahl} points")
        time.sleep(0.5)

    try:
        cam_off(bot)
        bot.chat("/clear @s")
        bot.chat("/xp set @s 0 levels")
        time.sleep(0.5)
        set_options(env, [("enabled", "true", "start-cost"), ("xp", "2", "start-cost"),
                          ("items", "{diamond: 3}", "start-cost")])
        z = befehl(bot, "/cam")
        FIND.test("start-cost: ohne Level und Diamanten kein Start, und er erfaehrt, was fehlt",
                  gesagt(z, r"costs 2 XP levels, 3x diamond\. You are missing 2 XP levels, 3x diamond")
                  and not gesagt(z, r"[Cc]am mode activated"), zeige(z))

        bot.chat("/give @s minecraft:diamond 5")
        bot.chat("/xp set @s 3 levels")
        time.sleep(0.5)
        z = befehl(bot, "/cam", warten=2)
        FIND.test("start-cost: mit allem startet er und erfaehrt, was es gekostet hat",
                  gesagt(z, r"[Cc]am mode activated") and gesagt(z, r"cost you 2 XP levels, 3x diamond"), zeige(z))
        cam_off(bot)
        time.sleep(0.5)
        FIND.test("start-cost: danach sind 1 Level und 2 Diamanten uebrig", bestand(2, 1), "")

        z = befehl(bot, "/cam")
        FIND.test("start-cost: reicht es nur fuer einen Teil, wird gar nichts genommen",
                  gesagt(z, r"You are missing 1 XP level, 1x diamond") and bestand(2, 1), zeige(z))

        set_options(env, [("xp", "10", "start-cost"), ("xp-type", "points", "start-cost"),
                          ("items", "{}", "start-cost")])
        punkte(25)
        z = befehl(bot, "/cam", warten=2)
        FIND.test("start-cost.xp-type: points - der Start kostet 10 Punkte",
                  gesagt(z, r"[Cc]am mode activated") and gesagt(z, r"cost you 10 XP points"), zeige(z))
        cam_off(bot)
        time.sleep(0.5)
        FIND.test("start-cost.xp-type: points - von 25 Punkten bleiben 15, Level 1",
                  nbt_frage(bot, "@s", "{XpTotal:15}") and nbt_frage(bot, "@s", "{XpLevel:1}"), "")
        punkte(5)
        z = befehl(bot, "/cam")
        FIND.test("start-cost.xp-type: points - mit 5 Punkten fehlen 5",
                  gesagt(z, r"You are missing 5 XP points") and not gesagt(z, r"[Cc]am mode activated"), zeige(z))

        set_options(env, [("xp", "0", "start-cost"), ("xp-type", "levels", "start-cost"),
                          ("money", "5.0", "start-cost")])
        vorher = len(server_log(env))
        an = cam_on(bot)
        cam_off(bot)
        cam_on(bot)
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("start-cost.money ohne Vault: der Start kostet nichts, die Konsole sagt es einmal",
                  an and log.count("there is no economy") == 1, f"{log.count('there is no economy')} Mal im Log")
    finally:
        cam_off(bot)
        bot.chat("/clear @s")
        bot.chat("/xp set @s 0 levels")
        bot.chat("/xp set @s 0 points")
        set_options(env, [("enabled", "false", "start-cost"), ("xp", "0", "start-cost"),
                          ("xp-type", "levels", "start-cost"), ("items", "{}", "start-cost"),
                          ("money", "0.0", "start-cost")])


def statistik_checks(env, bot):
    """statistics, /cam stats, /cam stats player und /cam stats reset.

    Ein Flug von sechs Bloecken nach oben zaehlt einmal mehr, die Strecke
    waechst um ihn, und der weiteste Abstand zum Koerper ist mindestens so
    gross. Die Zahlen stehen in der Datei des Bots unter playerdata/stats.
    Ein unbekannter Name wird gemeldet, ein unbekanntes Wort nach stats sagt,
    wie es geht, die Konsole darf jeden ansehen. view-others: admin laesst
    den Bot ohne op nur sich selbst ansehen - auch unter seinem eigenen
    Namen -, all auch andere, false auch mit op niemanden. /cam stats reset
    setzt seine eigene auf null. Mit statistics.enabled: false sagt
    /cam stats, dass sie aus ist.
    """
    def zahl(zeilen, muster):
        for z in zeilen:
            m = re.search(muster, z)
            if m:
                return int(m.group(1))
        return 0

    try:
        cam_off(bot)
        z = befehl(bot, "/cam stats")
        mal, weg = zahl(z, r"Times in cam mode: (\d+)"), zahl(z, r"Distance flown: (\d+) blocks")
        if not FIND.test("Cam-Modus fuer die Statistik gestartet", cam_on(bot), ""):
            return
        bot.call("fly", wait=30, dy=6, timeout=15000)
        time.sleep(1)
        cam_off(bot)
        z = befehl(bot, "/cam stats")
        FIND.test("/cam stats zaehlt den Flug", zahl(z, r"Times in cam mode: (\d+)") == mal + 1, zeige(z))
        FIND.test("/cam stats - die Strecke waechst um den Flug",
                  zahl(z, r"Distance flown: (\d+) blocks") >= weg + 4, zeige(z))
        FIND.test("/cam stats - der weiteste Abstand zum Koerper",
                  zahl(z, r"Farthest from the body: (\d+) blocks") >= 4, zeige(z))
        datei = spielerdatei(env)
        FIND.test("statistics: die Zahlen stehen in der Datei unter playerdata/stats",
                  datei is not None and re.search(rf"(?m)^\s*sessions: {mal + 1}$",
                                                  datei.read_text(encoding="utf-8")) is not None, str(datei))

        z = befehl(bot, "/cam stats player NiemandHier")
        FIND.test("/cam stats player <Name> - ein unbekannter Name wird gemeldet",
                  gesagt(z, r"Nobody called NiemandHier"), zeige(z))
        z = befehl(bot, "/cam stats quatsch")
        FIND.test("/cam stats mit einem unbekannten Wort sagt, wie es geht",
                  gesagt(z, r"Use /cam stats, /cam stats reset or /cam stats player"), zeige(z))
        vorher = len(server_log(env))
        console(env, f"cam stats player {BOT_NAME}", pause=1.5)
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("/cam stats player <Name> geht auch von der Konsole",
                  f"Cam mode statistics of {BOT_NAME}" in log, log.strip()[-200:] or "nichts im Log")

        console(env, f"deop {BOT_NAME}", pause=1.5)
        z = befehl(bot, f"/cam stats player {BOT_NAME}")
        FIND.test("view-others: admin - ohne op sieht er sich unter seinem eigenen Namen",
                  gesagt(z, rf"Cam mode statistics of {BOT_NAME}"), zeige(z))
        z = befehl(bot, "/cam stats player NiemandHier")
        FIND.test("view-others: admin - ohne op darf er andere nicht ansehen", gesagt(z, r"permission"), zeige(z))
        set_option(env, "view-others", "all", "statistics")
        z = befehl(bot, "/cam stats player NiemandHier")
        FIND.test("view-others: all - auch ohne op darf er andere ansehen",
                  gesagt(z, r"Nobody called NiemandHier"), zeige(z))
        console(env, f"op {BOT_NAME}", pause=1.5)
        set_option(env, "view-others", "false", "statistics")
        z = befehl(bot, "/cam stats player NiemandHier")
        FIND.test("view-others: false - auch mit op niemand", gesagt(z, r"permission"), zeige(z))
        set_option(env, "view-others", "admin", "statistics")

        z = befehl(bot, "/cam stats reset")
        FIND.test("/cam stats reset meldet, dass die Statistik weg ist",
                  gesagt(z, r"statistics have been reset"), zeige(z))
        z = befehl(bot, "/cam stats")
        FIND.test("/cam stats reset - danach ist sie wirklich auf null",
                  gesagt(z, rf"{BOT_NAME} has not been in cam mode yet"), zeige(z))

        set_option(env, "enabled", "false", "statistics")
        z = befehl(bot, "/cam stats")
        FIND.test("statistics.enabled: false - /cam stats sagt, dass sie aus ist",
                  gesagt(z, r"statistics are switched off"), zeige(z))
    finally:
        console(env, f"op {BOT_NAME}", pause=1.5)
        set_options(env, [("enabled", "true", "statistics"), ("view-others", "admin", "statistics")])


def alte_absturzdatei_checks(env, bot):
    """Eine Datei der crash-protection an ihrem alten Platz.

    Vor dem Ordner playerdata/stats lagen die Dateien direkt unter
    playerdata. Eine, die ein Absturz vor dem Update dort hinterlassen hat,
    muss trotzdem zurueckgegeben werden. Der Test legt eine solche Datei an,
    waehrend der Bot abgemeldet ist: mit leerem Inventar, an einer Stelle
    zwei Bloecke neben ihm, und mit dem Spielmodus und dem Flug, die er
    gerade hat - die Datei gibt sie ihm zurueck, und spaetere Abschnitte
    finden ihn so vor, wie er war. Ohne Flug faellt er etwa im
    Interaktionstest vom Happy Ghast und darf danach wegen cam-safety nicht
    starten. Nach dem Anmelden steht er an der Stelle aus der Datei, und die
    Datei ist weg.
    """
    ordner = env.server / "plugins" / "CamFly" / "playerdata"
    datei = {}
    pos = bot.server_pos()
    if not FIND.test("Standort fuer die alte Absturzdatei lesbar", pos is not None, ""):
        return
    ziel = (_floor(pos[0]) + 2.5, _floor(pos[1] + 0.5), _floor(pos[2]) + 0.5)
    modus = next((m for m in ("survival", "creative", "adventure") if spielmodus_ist(bot, m)), "survival")
    darf_fliegen = bool(bot.server_data("abilities.mayfly"))

    def anlegen():
        stats = spielerdatei(env)
        if stats is None:
            return
        datei["pfad"] = ordner / f"{stats.stem}.yml"
        datei["pfad"].write_text(
            "format: 1\n"
            f"name: {BOT_NAME}\n"
            "inventory: []\n"
            "effects: []\n"
            f"game-mode: {modus.upper()}\n"
            f"allow-flight: {str(darf_fliegen).lower()}\n"
            "flying: false\n"
            "glowing: false\n"
            "fly-speed: 0.1\n"
            "body:\n"
            "  world: world\n"
            f"  x: {ziel[0]}\n  y: {ziel[1]}\n  z: {ziel[2]}\n"
            "  yaw: 0.0\n  pitch: 0.0\n", encoding="utf-8")

    try:
        cam_off(bot)
        if not FIND.test("Bot meldet sich fuer die alte Absturzdatei neu an", neu_anmelden(bot, anlegen), ""):
            return
        if not FIND.test("Die alte Absturzdatei liegt unter playerdata", "pfad" in datei, ""):
            return
        time.sleep(1)
        z = zeilen_seit(bot, 0)
        FIND.test("Alte Absturzdatei: die Meldung, dass er alles zurueckhat",
                  gesagt(z, r"server went down while you were in cam mode"), zeige(z))
        neu = bot.server_pos()
        FIND.test("Alte Absturzdatei: er steht an der Stelle aus der Datei",
                  neu is not None and math.dist(neu, ziel) < 1.0, f"{ziel} -> {neu}")
        FIND.test("Alte Absturzdatei: sie ist danach weg", not datei["pfad"].exists(), str(datei["pfad"]))
        FIND.test("Alte Absturzdatei: Spielmodus und Flug sind, wie sie waren",
                  spielmodus_ist(bot, modus) and bool(bot.server_data("abilities.mayfly")) == darf_fliegen,
                  f"{modus}, Fliegen erlaubt: {darf_fliegen}")
    finally:
        if "pfad" in datei:
            datei["pfad"].unlink(missing_ok=True)


# ---------------------------------------------------------------------------
# /cam back, die Warnung vor dem Ende, der Koerper-Alarm und die Knoepfe von /cam list
# ---------------------------------------------------------------------------

def toene_marke(bot):
    """Wie viele Toene bisher beim Bot ankamen, als Marke fuer ton_da."""
    return bot.call("sounds", since=10 ** 9).get("count", 0)


def ton_da(bot, seit, name):
    """Ob seit der Marke dieser Ton beim Bot ankam."""
    return any(name in t["text"] for t in bot.call("sounds", since=seit).get("sounds", []))


def zurueck_checks(env, bot):
    """back-command: /cam back bringt die Kamera zum Koerper, und der Cam-Modus
    laeuft weiter.

    Der Bot fliegt sechs hoch und sechs zur Seite, /cam back setzt ihn wieder
    an den Koerper - im Abenteuermodus, also noch im Cam-Modus -, mit dem Ton
    von sounds.back und "Back at your body" in der Action-Bar. Er fliegt
    danach weiter: Ein zweiter Flug kommt vom Fleck. Mit enabled: false sagt
    /cam back, dass es abgeschaltet ist, und die Kamera bleibt, wo sie ist;
    ohne Cam-Modus sagt es, dass er keinen hat.
    """
    try:
        cam_off(bot)
        heim = bot.server_pos()
        if not FIND.test("Cam-Modus fuer /cam back gestartet", heim is not None and cam_on(bot), ""):
            return
        bot.call("fly", wait=30, dy=6, dx=6, timeout=15000)
        time.sleep(1.0)
        weg = bot.server_pos()
        seit = toene_marke(bot)
        z = befehl(bot, "/cam back")
        da = bot.server_pos()
        FIND.test("/cam back: die Kamera steht wieder am Koerper",
                  weg is not None and da is not None and math.dist(weg, heim) > 5 and math.dist(da, heim) < 1.0,
                  f"{heim} -> {weg} -> {da}")
        FIND.test("/cam back: der Cam-Modus laeuft weiter", spielmodus_ist(bot, "adventure"), "")
        FIND.test("/cam back: \"Back at your body\" in der Action-Bar", gesagt(z, r"Back at your body"), zeige(z))
        FIND.test("sounds.back - der Ton bei /cam back", ton_da(bot, seit, "entity.enderman.teleport"), "")
        bot.call("fly", wait=30, dy=4, timeout=15000)
        time.sleep(1.0)
        wieder = bot.server_pos()
        FIND.test("/cam back: danach fliegt die Kamera weiter",
                  wieder is not None and wieder[1] - heim[1] > 2.5, f"{heim} -> {wieder}")

        # Der reload dabei beendet den Cam-Modus.
        set_option(env, "enabled", "false", "back-command")
        cam_on(bot)
        bot.call("fly", wait=30, dy=4, timeout=15000)
        time.sleep(1.0)
        oben = bot.server_pos()
        z = befehl(bot, "/cam back")
        noch = bot.server_pos()
        FIND.test("back-command.enabled: false - /cam back ist abgeschaltet, die Kamera bleibt oben",
                  gesagt(z, r"/cam back is switched off") and oben is not None and noch is not None
                  and math.dist(oben, noch) < 0.5, f"{zeige(z)} - {oben} -> {noch}")
        cam_off(bot)
        set_option(env, "enabled", "true", "back-command")

        z = befehl(bot, "/cam back")
        FIND.test("/cam back ohne Cam-Modus sagt, dass er keinen hat",
                  gesagt(z, r"You are not in cam mode") and not gesagt(z, r"[Cc]am mode activated"), zeige(z))
    finally:
        cam_off(bot)
        set_option(env, "enabled", "true", "back-command")


def endwarnung_checks(env, bot):
    """end-warning: kurz vor dem Ende durch Zeitlimit oder Akku ein Ton und
    eine Zeile in der Action-Bar.

    Das Zeitlimit steht auf 13 Sekunden und die Warnung auf 10 und 5: Drei
    Sekunden nach dem Start kommt "Cam mode ends in 10 seconds" mit dem Ton
    von sounds.end-warning, fuenf Sekunden spaeter "... 5 seconds", und dann
    endet der Cam-Modus. Mit enabled: false endet er ohne Warnung. Der Akku
    haelt 12 Sekunden und warnt bei 10 mit "Battery almost empty". Damit er
    beim Start voll ist, laedt er dafuer 100 Sekunden in der Sekunde: Was er
    haelt, rechnet das Plugin beim Start aus der Zeit seit dem letzten Flug
    aus, und im Flug laedt er nicht.
    """
    try:
        cam_off(bot)
        set_options(env, [("flight-limit", "time-limit"), ("duration-seconds", "13", "time-limit"),
                          ("seconds", "[10, 5]", "end-warning")])
        seit = toene_marke(bot)
        since = bot.mark()
        if not FIND.test("Cam-Modus mit Zeitlimit fuer die Warnung gestartet", cam_on(bot), ""):
            return
        zehn = bot.expect(r"Cam mode ends in 10 seconds", since, 8000)
        FIND.test("end-warning: bei 10 Sekunden Rest steht \"Cam mode ends in 10 seconds\" in der Action-Bar",
                  bool(zehn), "")
        FIND.test("sounds.end-warning - der Ton zur Warnung", ton_da(bot, seit, "item.lodestone_compass.lock"), "")
        fuenf = bot.expect(r"Cam mode ends in 5 seconds", since, 10000)
        FIND.test("end-warning: bei 5 Sekunden Rest noch einmal", bool(fuenf), "")
        ende = bot.expect(r"Time limit expired", since, 10000)
        FIND.test("end-warning: danach endet der Cam-Modus mit dem Zeitlimit", bool(ende), "")

        set_option(env, "enabled", "false", "end-warning")
        since = bot.mark()
        cam_on(bot)
        ende = bot.expect(r"Time limit expired", since, 20000)
        z = zeilen_seit(bot, since)
        FIND.test("end-warning.enabled: false - das Zeitlimit endet ohne Warnung",
                  bool(ende) and not gesagt(z, r"Cam mode ends in \d"), zeige(z[-3:]))
        set_option(env, "enabled", "true", "end-warning")

        set_options(env, [("flight-limit", "battery"), ("capacity", "12", "battery"),
                          ("recharge-per-second", "100", "battery"), ("min-start", "2", "battery")])
        since = bot.mark()
        if not FIND.test("Cam-Modus mit vollem Akku fuer die Warnung gestartet", cam_on(bot), ""):
            return
        warnung = bot.expect(r"Battery almost empty: (9|10) seconds left", since, 8000)
        FIND.test("end-warning: der Akku warnt bei 10 Sekunden mit \"Battery almost empty\"", bool(warnung),
                  strip_colors(warnung["text"]) if warnung else "")
        leer = bot.expect(r"battery is empty", since, 15000)
        FIND.test("end-warning: danach ist der Akku leer und der Cam-Modus endet", bool(leer), "")
    finally:
        cam_off(bot)
        set_options(env, [("flight-limit", "false"), ("duration-seconds", "300", "time-limit"),
                          ("seconds", "[30, 10]", "end-warning"), ("enabled", "true", "end-warning"),
                          ("capacity", "300", "battery"), ("recharge-per-second", "0.5", "battery"),
                          ("min-start", "10", "battery")])


def alarm_checks(env, bot):
    """body-alarm: ein Ton und eine Zeile in der Action-Bar, wenn etwas dem
    Koerper gefaehrlich wird.

    Ein Zombie acht Bloecke vom Koerper: Mit body.mob-target: vanilla geht er
    auf den Koerper los, und es kommt "Watch out: a zombie is after your
    body!" mit dem Ton von sounds.body-alarm, bevor er zuschlaegt. Mit der
    Voreinstellung false laesst er den Koerper in Ruhe, und es kommt kein
    Alarm. Ein zweiter Spieler drei Bloecke neben dem Koerper loest
    voreingestellt keinen aus, mit body-alarm.players: true schon. Fuer den
    Zombie steht der Server auf easy, in peaceful verschwaende er sofort; der
    Bot hat Resistenz und cam-safety ist aus, falls der Zombie ihn doch
    erwischt. Zwischen Zombie und Koerper wird die Luft geraeumt: Die
    Flachwelt erzeugt Doerfer, und durch eine Hauswand saehe er ihn nicht.
    """
    if not FIND.test("Cam-Modus ist vor dem Alarmtest aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    bot.chat(f"/fill {_floor(x) - 2} {y} {_floor(z) - 2} {_floor(x) + 10} {y + 3} {_floor(z) + 2} minecraft:air")
    time.sleep(0.5)
    zombie = (f'summon minecraft:zombie {x + 8} {y} {z} {{Tags:["{INTERACT_TAG}"],'
              f"PersistenceRequired:1b,Silent:1b,IsBaby:0b}}")
    zuschauer = None

    def alarm(modus, muster):
        """Mit diesem mob-target den Zombie hinstellen und den Cam-Modus
        starten: (kam der Alarm, kam er vor dem Treffer auf den Koerper, kam
        der Ton) - oder None, wenn der Cam-Modus nicht startete."""
        set_option(env, "mob-target", modus, "body")
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
        hinstellen(bot, x, y, z)
        seit = toene_marke(bot)
        since = bot.mark()
        bot.chat("/cam")
        if not bot.expect("Cam mode activated", since, 8000):
            return None
        bot.call("fly", wait=30, dy=RUECKSTOSS_HOEHE, timeout=15000)
        console(env, zombie, pause=0.3)
        bot.expect(muster, since, 8000)
        zeilen = zeilen_seit(bot, since)
        treffer = next((i for i, t in enumerate(zeilen) if re.search(r"body was attacked", t)), len(zeilen))
        gewarnt = next((i for i, t in enumerate(zeilen) if re.search(muster, t)), None)
        return gewarnt is not None, gewarnt is not None and gewarnt < treffer, \
            ton_da(bot, seit, "entity.warden.nearby_close")

    try:
        bot.chat(f"/effect give {BOT_NAME} minecraft:resistance infinite 255 true")
        set_option(env, "enabled", "false", "cam-safety")
        console(env, "difficulty easy", pause=0.5)

        ergebnis = alarm("vanilla", r"Watch out: a zombie is after your body!")
        if FIND.test("Cam-Modus fuer den Alarm mit mob-target: vanilla gestartet", ergebnis is not None, ""):
            FIND.test("body-alarm: mit mob-target: vanilla warnt der Zombie, der auf den Koerper losgeht",
                      ergebnis[0], "")
            FIND.test("body-alarm: die Warnung kommt, bevor er den Koerper trifft", ergebnis[1], "")
            FIND.test("sounds.body-alarm - der Ton zum Alarm", ergebnis[2], "")
        cam_off(bot)

        ergebnis = alarm("false", r"Watch out")
        if FIND.test("Cam-Modus fuer den Alarm mit mob-target: false gestartet", ergebnis is not None, ""):
            FIND.test("body-alarm: mit mob-target: false kein Alarm, der Zombie laesst den Koerper in Ruhe",
                      not ergebnis[0], "")
        cam_off(bot)
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
        console(env, "difficulty peaceful", pause=0.3)

        zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer den Alarm kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        for an in (False, True):
            set_option(env, "players", "true" if an else "false", "body-alarm")
            console(env, f"tp {ZUSCHAUER_NAME} {x + 20} {y} {z}", pause=1.0)
            hinstellen(bot, x, y, z)
            since = bot.mark()
            if not cam_on(bot):
                FIND.test(f"Cam-Modus fuer den Alarm mit players: {str(an).lower()} gestartet", False, "")
                continue
            console(env, f"tp {ZUSCHAUER_NAME} {x + 3} {y} {z}", pause=0.3)
            gewarnt = bot.expect(rf"Watch out: {ZUSCHAUER_NAME} is close to your body!", since, 4000)
            if an:
                FIND.test("body-alarm.players: true - ein Spieler nahe am Koerper loest den Alarm aus",
                          bool(gewarnt), "")
            else:
                FIND.test("body-alarm.players: voreingestellt false - ein Spieler nahe am Koerper loest nichts aus",
                          not gewarnt, "")
            cam_off(bot)
    finally:
        try:
            if zuschauer is not None:
                zuschauer.stop()
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
            console(env, "difficulty peaceful", pause=0.3)
            cam_off(bot)
            set_options(env, [("enabled", "true", "cam-safety"), ("mob-target", "false", "body"),
                              ("players", "false", "body-alarm")])
            bot.chat(f"/effect clear {BOT_NAME}")
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Alarmtest: {exc}")


def knoepfe_checks(env, bot):
    """cam-list.teleport: die Knoepfe [Camera] und [Body] hinter jeder Zeile
    von /cam list, und /cam tp, das sie schicken.

    Im Cam-Modus ist ein zweiter Spieler. In der Liste des Bots - op, also mit
    camplugin.teleport - stehen hinter seiner Zeile zwei Klicks auf
    "/cam tp <Name> camera" und "... body"; nachgesehen im Paket, denn der
    Text der Zeile hat keinen Klick mehr. Ohne op, mit access: all, sieht der
    Bot die Liste ohne Knoepfe, ebenso mit teleport: false. /cam tp ... body
    setzt den Bot neben den Koerper, nicht hinein. Zur Kamera kommt er im
    Ueberlebensmodus nicht, er fiele herunter; im Kreativmodus steht er
    danach genau bei ihr in der Luft. Der Cam-Modus des anderen laeuft dabei
    jedes Mal weiter.
    """
    if not FIND.test("Cam-Modus ist vor dem Test der Knoepfe aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    modus = next((m for m in ("survival", "creative") if spielmodus_ist(bot, m)), "survival")
    x, y, z = _floor(heim[0]) + 4.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    # Platz neben dem Koerper, falls dort ein Haus eines Dorfes steht.
    bot.chat(f"/fill {_floor(x) - 2} {y} {_floor(z) - 2} {_floor(x) + 2} {y + 3} {_floor(z) + 2} minecraft:air")
    time.sleep(0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)

    def pakete_seit_liste():
        seit = bot.call("chat_packets", since=10 ** 9).get("count", 0)
        befehl(bot, "/cam list")
        return " ".join(p["text"] for p in bot.call("chat_packets", since=seit).get("packets", []))

    def kamera_an():
        console(env, f"tp {ZUSCHAUER_NAME} {x} {y} {z}", pause=1.0)
        return cam_on(zuschauer)

    def noch_im_cam_modus():
        return server_says(bot, f"/execute if entity @a[name={ZUSCHAUER_NAME},gamemode=adventure] "
                                f"run say {{marke}}")

    try:
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer die Knoepfe kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        if not FIND.test("Der zweite Spieler ist im Cam-Modus", kamera_an(), ""):
            return
        pakete = pakete_seit_liste()
        FIND.test("cam-list.teleport: hinter der Zeile stehen die Knoepfe zur Kamera und zum Koerper",
                  f"/cam tp {ZUSCHAUER_NAME} camera" in pakete and f"/cam tp {ZUSCHAUER_NAME} body" in pakete,
                  pakete[-300:] or "kein Paket")

        set_option(env, "access", "all", "cam-list")
        console(env, f"deop {BOT_NAME}", pause=1.5)
        kamera_an()
        pakete = pakete_seit_liste()
        FIND.test("cam-list.teleport: ohne camplugin.teleport die Liste ohne Knoepfe",
                  ZUSCHAUER_NAME in pakete and "/cam tp" not in pakete, pakete[-300:] or "kein Paket")
        console(env, f"op {BOT_NAME}", pause=1.5)

        set_options(env, [("access", "admin", "cam-list"), ("teleport", "false", "cam-list")])
        kamera_an()
        pakete = pakete_seit_liste()
        FIND.test("cam-list.teleport: false - die Liste ohne Knoepfe",
                  ZUSCHAUER_NAME in pakete and "/cam tp" not in pakete, pakete[-300:] or "kein Paket")
        set_option(env, "teleport", "true", "cam-list")

        if not kamera_an():
            return
        bot.chat("/gamemode survival")
        time.sleep(0.5)
        antwort = befehl(bot, f"/cam tp {ZUSCHAUER_NAME} body")
        FIND.test("/cam tp ... body: der Bot steht neben dem Koerper, nicht darin",
                  server_says(bot, f"/execute positioned {x} {y} {z} if entity "
                                   f"@a[name={BOT_NAME},distance=0.5..1.6] run say {{marke}}"), zeige(antwort))
        FIND.test("/cam tp ... body: der Cam-Modus des anderen laeuft weiter", noch_im_cam_modus(), "")

        zuschauer.call("fly", wait=30, dy=5, timeout=15000)
        time.sleep(1.0)
        antwort = befehl(bot, f"/cam tp {ZUSCHAUER_NAME} camera")
        FIND.test("/cam tp ... camera: im Ueberlebensmodus nicht, er fiele herunter",
                  gesagt(antwort, r"only go to a camera while you can fly"), zeige(antwort))
        bot.chat("/gamemode creative")
        time.sleep(0.5)
        # Ohne Schwerkraft beim Bot, sonst fiele er auf seiner Seite gleich
        # wieder herunter, ehe gefragt wird.
        bot.call("fly", wait=30, dy=1, timeout=5000)
        befehl(bot, f"/cam tp {ZUSCHAUER_NAME} camera")
        FIND.test("/cam tp ... camera: im Kreativmodus steht der Bot bei der Kamera in der Luft",
                  server_says(bot, f"/execute as {ZUSCHAUER_NAME} at @s if entity "
                                   f"@a[name={BOT_NAME},distance=..0.5] run say {{marke}}"), "")
        FIND.test("/cam tp ... camera: der Cam-Modus des anderen laeuft weiter", noch_im_cam_modus(), "")
    finally:
        try:
            if zuschauer.proc is not None:
                zuschauer.stop()
            console(env, f"op {BOT_NAME}", pause=1.0)
            set_options(env, [("access", "admin", "cam-list"), ("teleport", "true", "cam-list")])
            hinstellen(bot, heim[0], heim[1], heim[2])
            bot.chat(f"/gamemode {modus}")
            time.sleep(0.5)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der Knoepfe: {exc}")


def quelle_config(env):
    """Die config.yml, wie das Plugin sie mitbringt - fuer Werte, die der
    Testserver anders einstellt."""
    return (env.repo / "src" / "main" / "resources" / "config.yml").read_text(encoding="utf-8")


def ausgeliefert(env):
    """Die config.yml des Testservers, wie sie gerade auf der Platte steht."""
    return (env.server / "plugins" / "CamFly" / "config.yml").read_text(encoding="utf-8")


def bossbar_suche(bot, muster):
    """Die Bossbars beim Bot, deren Titel auf das Muster passt, und die Titel
    aller, fuer die Ausgabe."""
    bars = bot.call("bossbars").get("bars", [])
    return [b for b in bars if re.search(muster, b.get("title", ""))], [b.get("title", "") for b in bars]


def zuschauer_cam_an(env, zuschauer, x, y, z):
    """Den zweiten Spieler auf seinen Platz stellen und in den Cam-Modus bringen.

    Meldet er sich in der Luft an - dort, wo ihn ein frueherer Lauf
    zurueckliess -, faellt er, nimmt Fallschaden, und cam-safety sperrt /cam
    fuenf Sekunden lang. Klappt der Start nicht, geht es deshalb nach dieser
    Zeit noch einmal.
    """
    console(env, f"tp {ZUSCHAUER_NAME} {x} {y} {z}", pause=1.0)
    if cam_on(zuschauer):
        return True
    time.sleep(5.5)
    return cam_on(zuschauer)


def stopp_checks(env, bot):
    """stop-command: /cam stop <Name> und /cam stop all beenden den Cam-Modus
    anderer, und der Knopf [Stop] hinter jeder Zeile von /cam list.

    Voreingestellt ist es aus - nachgesehen in der ausgelieferten Datei -, und
    /cam stop sagt das. Eingeschaltet beendet der Bot (op) den Cam-Modus des
    zweiten Spielers: Der erfaehrt, wer es war, und hoert sounds.end-forced,
    der Bot bekommt die Bestaetigung. Wer nicht im Cam-Modus ist, wird
    gemeldet. /cam stop all von der Konsole beendet beide, und der zweite
    Spieler liest "the server". Ohne op fehlt das Recht camplugin.stop, und
    der Cam-Modus des anderen laeuft weiter. In der Liste steht hinter seiner
    Zeile ein Klick auf "/cam stop <Name>", mit cam-list.stop: false nicht.
    """
    if not FIND.test("Cam-Modus ist vor dem Test von /cam stop aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 4.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    # Platz fuer den zweiten Spieler, falls dort ein Haus eines Dorfes steht.
    bot.chat(f"/fill {_floor(x) - 2} {y} {_floor(z) - 2} {_floor(x) + 2} {y + 3} {_floor(z) + 2} minecraft:air")
    time.sleep(0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)

    def kamera_an():
        return zuschauer_cam_an(env, zuschauer, x, y, z)

    def noch_im_cam_modus():
        return server_says(bot, f"/execute if entity @a[name={ZUSCHAUER_NAME},gamemode=adventure] "
                                f"run say {{marke}}")

    def pakete_seit_liste():
        seit = bot.call("chat_packets", since=10 ** 9).get("count", 0)
        befehl(bot, "/cam list")
        return " ".join(p["text"] for p in bot.call("chat_packets", since=seit).get("packets", []))

    try:
        FIND.test("Voreingestellt steht stop-command.enabled: true da (der Testserver startet es aus)",
                  re.search(r"(?m)^stop-command:\s*\n\s*enabled:\s*true\s*$", quelle_config(env)) is not None, "")
        zeilen = befehl(bot, "/cam stop all")
        FIND.test("stop-command.enabled: false - /cam stop ist abgeschaltet",
                  gesagt(zeilen, r"/cam stop is switched off"), zeige(zeilen))

        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer /cam stop kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        set_option(env, "enabled", "true", "stop-command")
        if not FIND.test("Der zweite Spieler ist fuer /cam stop im Cam-Modus", kamera_an(), ""):
            return
        pakete = pakete_seit_liste()
        FIND.test("cam-list.stop: hinter der Zeile steht der Knopf, der den Cam-Modus beendet",
                  f"/cam stop {ZUSCHAUER_NAME}" in pakete, pakete[-300:] or "kein Paket")

        since = zuschauer.mark()
        seit = toene_marke(zuschauer)
        zeilen = befehl(bot, f"/cam stop {ZUSCHAUER_NAME}")
        FIND.test("/cam stop <Name>: der Bot bekommt die Bestaetigung",
                  gesagt(zeilen, rf"Ended the cam mode of {ZUSCHAUER_NAME}"), zeige(zeilen))
        FIND.test("/cam stop <Name>: der Cam-Modus des anderen ist vorbei", not noch_im_cam_modus(), "")
        FIND.test("/cam stop <Name>: er erfaehrt, wer es war",
                  bool(zuschauer.expect(rf"Your cam mode was ended by {BOT_NAME}", since, 4000)), "")
        FIND.test("/cam stop <Name>: er hoert sounds.end-forced",
                  ton_da(zuschauer, seit, "block.respawn_anchor.deplete"), "")
        zeilen = befehl(bot, f"/cam stop {ZUSCHAUER_NAME}")
        FIND.test("/cam stop <Name>: wer nicht im Cam-Modus ist, wird gemeldet",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is not in cam mode"), zeige(zeilen))

        kamera_an()
        cam_on(bot)
        since = zuschauer.mark()
        vorher = len(server_log(env))
        console(env, "cam stop all", pause=1.5)
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("/cam stop all von der Konsole beendet den Cam-Modus von beiden",
                  "Ended the cam mode of 2 players" in log and not noch_im_cam_modus()
                  and not spielmodus_ist(bot, "adventure"), log.strip()[-200:] or "nichts im Log")
        FIND.test("/cam stop all von der Konsole: er liest, dass es der Server war",
                  bool(zuschauer.expect(r"Your cam mode was ended by the server", since, 4000)), "")

        kamera_an()
        console(env, f"deop {BOT_NAME}", pause=1.5)
        zeilen = befehl(bot, f"/cam stop {ZUSCHAUER_NAME}")
        console(env, f"op {BOT_NAME}", pause=1.5)
        FIND.test("/cam stop ohne camplugin.stop: keine Berechtigung, der Cam-Modus des anderen laeuft weiter",
                  gesagt(zeilen, r"permission") and noch_im_cam_modus(), zeige(zeilen))

        set_option(env, "stop", "false", "cam-list")
        kamera_an()
        pakete = pakete_seit_liste()
        FIND.test("cam-list.stop: false - die Liste ohne den Knopf",
                  ZUSCHAUER_NAME in pakete and "/cam stop" not in pakete, pakete[-300:] or "kein Paket")
    finally:
        try:
            if zuschauer.proc is not None:
                zuschauer.stop()
            console(env, f"op {BOT_NAME}", pause=1.0)
            cam_off(bot)
            set_options(env, [("enabled", "false", "stop-command"), ("stop", "true", "cam-list")])
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test von /cam stop: {exc}")


def bann_checks(env, bot):
    """cam-ban: /cam ban, /cam unban und /cam banlist.

    Voreingestellt ist es an - nachgesehen in der ausgelieferten Datei -, der
    Testserver startet es aus, und /cam ban sagt das. Eingeschaltet sperrt der
    Bot (op) den zweiten Spieler fuer eine Stunde mit einem Grund: Dessen
    laufender Cam-Modus endet, er erfaehrt, wer es war, und ein neuer Start
    wird abgelehnt, mit der Zeit, die noch bleibt, und dem Grund. Die Sperre
    steht in playerdata/bans.yml und in /cam banlist und bleibt nach
    /cam reload. Noch einmal /cam ban aendert sie, und beide erfahren es;
    er erfaehrt mehr: mit 2h die neue Zeit, bis wann und wie viel laenger,
    mit 30m wie viel weniger, ohne Zeit, dass sie dauerhaft ist, und mit 1h,
    dass sie das nicht mehr ist und bis wann. /cam unban schaltet ihn wieder
    frei, er erfaehrt von wem; ein zweites Mal sagt es, dass er nicht
    gesperrt ist. Fuer immer mit einem Grund heisst es nur "because: ...",
    ohne for good - nur /cam banlist sagt for good. Eine Sperre fuer drei
    Sekunden laeuft von selbst ab. /cam ban all von der Konsole sperrt den
    zweiten Spieler aus, den Bot nicht: Als op hat er camplugin.ban.bypass.
    Noch einmal mit 2h sagt sie ihm, wie viel laenger. /cam unban all macht
    wieder auf. Ein unbekannter Name wird gemeldet, und
    ohne op fehlt das Recht. Eine bans.yml von vor dem Umzug, noch direkt in
    plugins/CamFly, zieht beim Laden nach playerdata um und gilt weiter.
    """
    if not FIND.test("Cam-Modus ist vor dem Sperrtest aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 4.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    # Platz fuer den zweiten Spieler, falls dort ein Haus eines Dorfes steht.
    bot.chat(f"/fill {_floor(x) - 2} {y} {_floor(z) - 2} {_floor(x) + 2} {y + 3} {_floor(z) + 2} minecraft:air")
    time.sleep(0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    sperren = env.server / "plugins" / "CamFly" / "playerdata" / "bans.yml"
    # Wo die Datei vor dem Umzug nach playerdata lag.
    alte_sperren = env.server / "plugins" / "CamFly" / "bans.yml"
    # Die Zeit, die eine Stunde nach ein paar Sekunden noch hat.
    stunde = r"for (59m \d+s|1h 0m 0s)"

    def kamera_an():
        return zuschauer_cam_an(env, zuschauer, x, y, z)

    def noch_im_cam_modus():
        return server_says(bot, f"/execute if entity @a[name={ZUSCHAUER_NAME},gamemode=adventure] "
                                f"run say {{marke}}")

    def konsole(befehl_text):
        vorher = len(server_log(env))
        console(env, befehl_text, pause=1.5)
        return strip_colors(server_log(env)[vorher:])

    try:
        FIND.test("Voreingestellt steht cam-ban.enabled: true da (der Testserver startet es aus)",
                  re.search(r"(?m)^cam-ban:\s*\n(?:\s*#.*\n)*\s*enabled:\s*true\s*$", quelle_config(env)) is not None,
                  "")
        zeilen = befehl(bot, f"/cam ban {ZUSCHAUER_NAME}")
        FIND.test("cam-ban.enabled: false - /cam ban ist abgeschaltet", gesagt(zeilen, r"/cam ban is switched off"),
                  zeige(zeilen))

        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer den Sperrtest kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        for datei in (sperren, alte_sperren):
            if datei.exists():
                datei.unlink()
        set_option(env, "enabled", "true", "cam-ban")
        if not FIND.test("Der zweite Spieler ist fuer den Sperrtest im Cam-Modus", kamera_an(), ""):
            return

        since = zuschauer.mark()
        seit = toene_marke(zuschauer)
        zeilen = befehl(bot, f"/cam ban {ZUSCHAUER_NAME} 1h Testgrund")
        FIND.test("/cam ban <Name> 1h <Grund>: der Bot bekommt die Bestaetigung mit Zeit und Grund",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is banned from cam mode for 1h 0m 0s because: Testgrund\."),
                  zeige(zeilen))
        FIND.test("/cam ban: ein laufender Cam-Modus endet", not noch_im_cam_modus(), "")
        FIND.test("/cam ban: er erfaehrt, wer es war",
                  bool(zuschauer.expect(rf"{BOT_NAME} banned you from cam mode {stunde} because: Testgrund\.", since,
                                        4000)),
                  "")
        FIND.test("/cam ban: er hoert sounds.end-forced", ton_da(zuschauer, seit, "block.respawn_anchor.deplete"), "")
        zeilen = befehl(zuschauer, "/cam")
        FIND.test("/cam ban: gesperrt startet er nicht und erfaehrt, wie lange noch und warum",
                  gesagt(zeilen, rf"You are banned from cam mode {stunde} because: Testgrund\.")
                  and not gesagt(zeilen, r"[Cc]am mode activated"), zeige(zeilen))
        text = sperren.read_text(encoding="utf-8") if sperren.exists() else ""
        FIND.test("cam-ban: die Sperre steht in playerdata/bans.yml",
                  f"name: {ZUSCHAUER_NAME}" in text and "Testgrund" in text, text[-300:] or "keine Datei")
        zeilen = befehl(bot, "/cam banlist")
        FIND.test("/cam banlist nennt ihn, wie lange noch, von wem und warum",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME}: {stunde}, by {BOT_NAME} \(Testgrund\)"), zeige(zeilen))
        console(env, "cam reload", pause=2)
        zeilen = befehl(zuschauer, "/cam")
        FIND.test("cam-ban: nach /cam reload ist er immer noch gesperrt",
                  gesagt(zeilen, r"You are banned from cam mode"), zeige(zeilen))
        datum = r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}"
        since = zuschauer.mark()
        zeilen = befehl(bot, f"/cam ban {ZUSCHAUER_NAME} 2h")
        FIND.test("/cam ban noch einmal: die Sperre ist geaendert, jetzt zwei Stunden ohne Grund",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is now banned from cam mode for 2h 0m 0s\."), zeige(zeilen))
        FIND.test("/cam ban noch einmal, laenger: er erfaehrt die neue Zeit, bis wann und wie viel laenger",
                  bool(zuschauer.expect(rf"{BOT_NAME} changed your cam mode ban: it now lasts for 2h 0m 0s, "
                                        rf"until {datum}\. You have to wait 1h [01]m 0s longer\.", since, 4000)), "")
        since = zuschauer.mark()
        befehl(bot, f"/cam ban {ZUSCHAUER_NAME} 30m")
        FIND.test("/cam ban noch einmal, kuerzer: er erfaehrt, wie viel weniger",
                  bool(zuschauer.expect(rf"changed your cam mode ban: it now lasts for 30m 0s, until {datum}\. "
                                        rf"You have to wait 1h (29|30)m 0s less\.", since, 4000)), "")
        since = zuschauer.mark()
        befehl(bot, f"/cam ban {ZUSCHAUER_NAME}")
        FIND.test("/cam ban noch einmal, ohne Zeit: er erfaehrt, dass sie jetzt dauerhaft ist",
                  bool(zuschauer.expect(rf"{BOT_NAME} changed your cam mode ban: it is permanent now\.", since, 4000)),
                  "")
        since = zuschauer.mark()
        befehl(bot, f"/cam ban {ZUSCHAUER_NAME} 1h")
        FIND.test("/cam ban noch einmal, wieder mit Zeit: er erfaehrt, dass sie nicht mehr dauerhaft ist und bis wann",
                  bool(zuschauer.expect(rf"changed your cam mode ban: it is not permanent any more and ends on "
                                        rf"{datum}\.", since, 4000)), "")

        since = zuschauer.mark()
        zeilen = befehl(bot, f"/cam unban {ZUSCHAUER_NAME}")
        FIND.test("/cam unban <Name>: die Bestaetigung - er ist freigeschaltet",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is unlocked and may use cam mode again"), zeige(zeilen))
        FIND.test("/cam unban <Name>: er erfaehrt, wer ihn freigeschaltet hat",
                  bool(zuschauer.expect(rf"{BOT_NAME} unlocked you: you may use cam mode again", since, 4000)), "")
        an = kamera_an()
        cam_off(zuschauer)
        FIND.test("/cam unban <Name>: danach startet er wieder", an, "")
        zeilen = befehl(bot, f"/cam unban {ZUSCHAUER_NAME}")
        FIND.test("/cam unban: wer nicht gesperrt ist, wird gemeldet",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is not banned from cam mode"), zeige(zeilen))

        since = zuschauer.mark()
        zeilen = befehl(bot, f"/cam ban {ZUSCHAUER_NAME} Griefing")
        FIND.test("/cam ban <Name> <Grund>, fuer immer: die Bestaetigung nennt nur den Grund, kein for good",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is banned from cam mode because: Griefing\.")
                  and not gesagt(zeilen, r"for good"), zeige(zeilen))
        FIND.test("/cam ban <Name> <Grund>, fuer immer: er erfaehrt es ohne for good",
                  bool(zuschauer.expect(rf"{BOT_NAME} banned you from cam mode because: Griefing\.", since, 4000)),
                  "")
        zeilen = befehl(zuschauer, "/cam")
        FIND.test("/cam ban <Name> <Grund>, fuer immer: beim Startversuch auch",
                  gesagt(zeilen, r"You are banned from cam mode because: Griefing\.") and not gesagt(zeilen, r"for good"),
                  zeige(zeilen))
        zeilen = befehl(bot, "/cam banlist")
        FIND.test("/cam banlist sagt dabei weiter for good",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME}: for good, by {BOT_NAME} \(Griefing\)"), zeige(zeilen))
        befehl(bot, f"/cam unban {ZUSCHAUER_NAME}")

        befehl(bot, f"/cam ban {ZUSCHAUER_NAME} 3s")
        zeilen = befehl(zuschauer, "/cam")
        gesperrt = gesagt(zeilen, r"You are banned from cam mode for \d seconds\.")
        time.sleep(3.0)
        an = kamera_an()
        cam_off(zuschauer)
        FIND.test("/cam ban <Name> 3s: erst gesperrt, dann laeuft die Sperre von selbst ab", gesperrt and an,
                  zeige(zeilen))

        kamera_an()
        cam_on(bot)
        since = zuschauer.mark()
        log = konsole("cam ban all 1h Event")
        FIND.test("/cam ban all von der Konsole: die Bestaetigung",
                  "Cam mode is locked for everybody for 1h 0m 0s because: Event." in log, log.strip()[-200:] or "nichts im Log")
        FIND.test("/cam ban all: der Cam-Modus des zweiten Spielers endet", not noch_im_cam_modus(), "")
        FIND.test("/cam ban all: er erfaehrt, dass es der Server war",
                  bool(zuschauer.expect(rf"the server locked cam mode for everybody {stunde} because: Event\.", since,
                                        4000)),
                  "")
        FIND.test("camplugin.ban.bypass: der Bot (op) bleibt dabei im Cam-Modus", spielmodus_ist(bot, "adventure"), "")
        cam_off(bot)
        zeilen = befehl(zuschauer, "/cam")
        FIND.test("/cam ban all: ohne camplugin.ban.bypass startet er nicht",
                  gesagt(zeilen, rf"Cam mode is locked for everybody {stunde} because: Event\.")
                  and not gesagt(zeilen, r"[Cc]am mode activated"), zeige(zeilen))
        an = cam_on(bot)
        cam_off(bot)
        FIND.test("camplugin.ban.bypass: der Bot (op) startet trotzdem", an, "")
        zeilen = befehl(bot, "/cam banlist")
        FIND.test("/cam banlist nennt die Sperre fuer alle",
                  gesagt(zeilen, rf"Everybody: {stunde}, by the server \(Event\)"), zeige(zeilen))
        since = zuschauer.mark()
        log = konsole("cam ban all 2h Event")
        FIND.test("/cam ban all noch einmal: die Bestaetigung sagt, dass sie geaendert ist",
                  "Cam mode is now locked for everybody for 2h 0m 0s because: Event." in log,
                  log.strip()[-200:] or "nichts im Log")
        FIND.test("/cam ban all noch einmal: der zweite Spieler erfaehrt, wie viel laenger",
                  bool(zuschauer.expect(rf"the server changed the lock of cam mode for everybody: it now lasts for "
                                        rf"2h 0m 0s, until {datum}\. You have to wait 1h [01]m 0s longer\.", since,
                                        4000)), "")
        since = zuschauer.mark()
        log = konsole("cam unban all")
        FIND.test("/cam unban all von der Konsole: die Bestaetigung",
                  "Cam mode is unlocked: everybody may use it again" in log, log.strip()[-200:] or "nichts im Log")
        FIND.test("/cam unban all: der zweite Spieler erfaehrt es, und von wem",
                  bool(zuschauer.expect(r"the server unlocked cam mode: everybody may use it again", since, 4000)),
                  "")
        an = kamera_an()
        cam_off(zuschauer)
        FIND.test("/cam unban all: danach startet er wieder", an, "")

        zeilen = befehl(bot, "/cam ban NiemandHier")
        FIND.test("/cam ban: ein unbekannter Name wird gemeldet", gesagt(zeilen, r"Nobody called NiemandHier"),
                  zeige(zeilen))
        console(env, f"deop {BOT_NAME}", pause=1.5)
        zeilen = befehl(bot, f"/cam ban {ZUSCHAUER_NAME}")
        console(env, f"op {BOT_NAME}", pause=1.5)
        liste = befehl(bot, "/cam banlist")
        FIND.test("/cam ban ohne camplugin.ban: keine Berechtigung, und niemand ist gesperrt",
                  gesagt(zeilen, r"permission") and gesagt(liste, r"Nobody is banned from cam mode"),
                  f"{zeige(zeilen)} - {zeige(liste)}")

        if sperren.exists():
            sperren.unlink()
        alte_sperren.write_text("everybody:\n  until: 0\n  reason: Altdatei\n  by: Testlauf\n", encoding="utf-8")
        log = konsole("cam reload")
        text = sperren.read_text(encoding="utf-8") if sperren.exists() else ""
        FIND.test("bans.yml von vor dem Umzug: sie zieht beim Laden nach playerdata um",
                  not alte_sperren.exists() and "Altdatei" in text and "moved to playerdata/bans.yml" in log,
                  f"alt da: {alte_sperren.exists()}, neu: {text[-200:] or 'keine Datei'}")
        zeilen = befehl(bot, "/cam banlist")
        FIND.test("bans.yml von vor dem Umzug: ihre Sperre gilt weiter",
                  gesagt(zeilen, r"Everybody: for good, by Testlauf \(Altdatei\)"), zeige(zeilen))
        konsole("cam unban all")
    finally:
        try:
            if zuschauer.proc is not None:
                zuschauer.stop()
            console(env, f"op {BOT_NAME}", pause=1.0)
            cam_off(bot)
            for datei in (sperren, alte_sperren):
                if datei.exists():
                    datei.unlink()
            set_option(env, "enabled", "false", "cam-ban")
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Sperrtest: {exc}")


def welten_checks(env, bot):
    """cam-area.forbidden-worlds: Welten nach ihrem Namen.

    Voreingestellt ist die Liste aus - nachgesehen in der ausgelieferten
    Datei. Steht die Welt des Testservers, "world", darin, startet der
    Cam-Modus trotzdem, solange worlds-enabled false ist. Mit true startet
    er nicht, und die Meldung nennt die Welt; gross geschrieben steht sie
    genauso in der Liste. Mit cam-area.level: 0 zaehlt auch diese Liste
    nicht.
    """
    try:
        cam_off(bot)
        FIND.test("Voreingestellt steht cam-area.worlds-enabled: false da",
                  re.search(r"(?m)^\s*worlds-enabled:\s*false\s*$", ausgeliefert(env)) is not None, "")
        set_option(env, "forbidden-worlds", "[world]")
        an = cam_on(bot)
        cam_off(bot)
        FIND.test("worlds-enabled: false - die Welten-Liste zaehlt nicht", an, "")

        set_option(env, "worlds-enabled", "true")
        z = befehl(bot, "/cam")
        FIND.test("forbidden-worlds: in einer verbotenen Welt startet der Cam-Modus nicht, die Meldung nennt sie",
                  gesagt(z, r"cannot start cam mode in the world world") and not gesagt(z, r"[Cc]am mode activated"),
                  zeige(z))
        set_option(env, "forbidden-worlds", "[WORLD]")
        z = befehl(bot, "/cam")
        FIND.test("forbidden-worlds: der Name zaehlt ohne Gross- und Kleinschreibung",
                  gesagt(z, r"cannot start cam mode in the world world"), zeige(z))

        set_option(env, "level", "0", "cam-area")
        an = cam_on(bot)
        cam_off(bot)
        FIND.test("cam-area.level: 0 - auch die Welten-Liste zaehlt nicht", an, "")
    finally:
        cam_off(bot)
        set_options(env, [("worlds-enabled", "false"), ("forbidden-worlds", "[lobby]"), ("level", "2", "cam-area")])


def angriff_checks(env, bot):
    """cam-safety.after-attack: Wer zuschlaegt, wartet wie einer, der
    getroffen wurde - mit einem Schalter fuer Spieler, einem fuer feindliche
    Mobs und einem fuer Friedliches.

    Neben dem Bot stehen der zweite Spieler, ein Wuestenzombie und ein
    Schwein. Voreingestellt sind alle drei Schalter aus - nachgesehen in der
    ausgelieferten Datei -, und der Cam-Modus startet gleich nach einem
    Schlag. Mit players sperrt nur der Schlag auf den Spieler, mit mobs nur
    der auf den Zombie, mit friendly nur der auf das Schwein, jeweils bis
    die fuenf Sekunden von cam-safety.delay um sind. Geschlagen wird jedes
    Mal frisch vor dem /cam. Gezielt wird nach dem Platz, nicht nach der
    Art: Einen Spieler nennt mineflayer mit den Daten von 26.1 nach einer
    anderen Art, "fishing_bobber". Fuer den Zombie steht der Server auf easy,
    in peaceful verschwaende er sofort; ohne KI tut er niemandem etwas, und
    ein Wuestenzombie brennt in der Sonne nicht.
    """
    if not FIND.test("Cam-Modus ist vor dem Angriffstest aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    bot.chat(f"/fill {_floor(x) - 3} {y} {_floor(z) - 3} {_floor(x) + 3} {y + 3} {_floor(z) + 3} minecraft:air")
    time.sleep(0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    spieler, zombie, schwein = [x - 2, y, z], [x, y, z + 2], [x + 2, y, z]

    def schlag(wo):
        hinstellen(bot, x, y, z)
        return bool(bot.call("attack_entity", wait=20, radius=0.8, at=wo).get("done"))

    def start_nach(wo):
        """(kam der Schlag an, startete der Cam-Modus danach, was er sagte)"""
        geschlagen = schlag(wo)
        zeilen = befehl(bot, "/cam")
        an = gesagt(zeilen, r"[Cc]am mode activated")
        if an:
            cam_off(bot)
        return geschlagen, an, zeilen

    def schalter(players, mobs, friendly):
        set_options(env, [("players", players, "cam-safety"), ("mobs", mobs, "cam-safety"),
                          ("friendly", friendly, "cam-safety")])

    def sperrt(name, wo):
        geschlagen, an, zeilen = start_nach(wo)
        FIND.test(f"after-attack: {name}", geschlagen and not an and gesagt(zeilen, r"remain safe"), zeige(zeilen))
        time.sleep(5.5)

    def sperrt_nicht(name, wo):
        geschlagen, an, zeilen = start_nach(wo)
        FIND.test(f"after-attack: {name}", geschlagen and an, zeige(zeilen))

    try:
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer den Angriffstest kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        console(env, "difficulty easy", pause=0.5)
        console(env, f"tp {ZUSCHAUER_NAME} {spieler[0]} {y} {z}", pause=0.5)
        console(env, f"effect give {ZUSCHAUER_NAME} minecraft:resistance infinite 255 true", pause=0.3)
        for art, wo in (("husk", zombie), ("pig", schwein)):
            console(env, f'summon minecraft:{art} {wo[0]} {wo[1]} {wo[2]} {{Tags:["{INTERACT_TAG}"],NoAI:1b,'
                         f'Silent:1b,PersistenceRequired:1b}}', pause=0.3)
        time.sleep(0.5)

        block = re.search(r"(?ms)^cam-safety:.*?(?=^\S)", ausgeliefert(env))
        FIND.test("Voreingestellt stehen players, mobs und friendly unter cam-safety.after-attack auf false",
                  block is not None and all(re.search(rf"(?m)^\s*{k}:\s*false\s*$", block.group(0))
                                            for k in ("players", "mobs", "friendly")), "")
        sperrt_nicht("alle drei aus - nach einem Schlag auf einen Spieler startet der Cam-Modus gleich", spieler)

        schalter("true", "false", "false")
        sperrt_nicht("players - ein Schlag auf einen Mob sperrt nicht", zombie)
        sperrt("players - nach einem Schlag auf einen Spieler wird /cam abgelehnt", spieler)
        an = cam_on(bot)
        cam_off(bot)
        FIND.test("after-attack: nach cam-safety.delay geht es wieder", an, "")

        schalter("false", "true", "false")
        sperrt_nicht("mobs - ein Schlag auf ein Schwein sperrt nicht", schwein)
        sperrt("mobs - nach einem Schlag auf einen feindlichen Mob wird /cam abgelehnt", zombie)

        schalter("false", "false", "true")
        sperrt_nicht("friendly - ein Schlag auf einen feindlichen Mob sperrt nicht", zombie)
        sperrt("friendly - nach einem Schlag auf ein Schwein wird /cam abgelehnt", schwein)
    finally:
        try:
            if zuschauer.proc is not None:
                zuschauer.stop()
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
            console(env, "difficulty peaceful", pause=0.3)
            cam_off(bot)
            schalter("false", "false", "false")
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Angriffstest: {exc}")


def afk_checks(env, bot):
    """afk-end: Der Cam-Modus endet, wenn der Spieler eine Weile nichts tut.

    Voreingestellt ist es aus und warnt bei 80 Prozent - nachgesehen in der
    ausgelieferten Datei. Mit seconds: 12 und warning-percent: 50 steht der
    Bot im Cam-Modus still: Nach etwa sechs Sekunden kommt "You are not
    moving: cam mode ends in 6 seconds" mit dem Ton von sounds.end-warning,
    nach zwoelf endet der Cam-Modus mit "you did not move for 12 seconds" und
    dem Ton von sounds.end-forced. Mit warning-percent: 0 endet er ohne
    Warnung. Wer sich bewegt, bleibt: Der Bot fliegt alle paar Sekunden ein
    Stueck und ist nach zwanzig Sekunden noch im Cam-Modus. Weniger als 10
    Sekunden werden gemeldet und auf 10 gehoben.
    """
    try:
        cam_off(bot)
        text = ausgeliefert(env)
        FIND.test("Voreingestellt steht afk-end.enabled: false da",
                  re.search(r"(?m)^afk-end:\s*\n\s*enabled:\s*false\s*$", text) is not None, "")
        FIND.test("Voreingestellt steht afk-end.warning-percent: 80 da",
                  re.search(r"(?m)^\s*warning-percent:\s*80\s*$", text) is not None, "")
        set_options(env, [("enabled", "true", "afk-end"), ("seconds", "12", "afk-end"),
                          ("warning-percent", "50", "afk-end")])
        seit = toene_marke(bot)
        since = bot.mark()
        if not FIND.test("Cam-Modus fuer afk-end gestartet", cam_on(bot), ""):
            return
        start = time.time() - 1.5
        warnung = bot.expect(r"You are not moving: cam mode ends in [56] seconds", since, 12000)
        gewarnt = time.time() - start
        FIND.test("afk-end.warning-percent: 50 - nach der Haelfte der 12 Sekunden kommt die Warnung",
                  bool(warnung) and 5.0 <= gewarnt <= 8.5, f"nach {gewarnt:.1f} s")
        FIND.test("afk-end: die Warnung mit dem Ton von sounds.end-warning",
                  ton_da(bot, seit, "item.lodestone_compass.lock"), "")
        ende = bot.expect(r"you did not move for 12 seconds", since, 12000)
        dauer = time.time() - start
        FIND.test("afk-end: nach 12 Sekunden ohne Bewegung endet der Cam-Modus",
                  bool(ende) and 10.0 <= dauer <= 16.0, f"nach {dauer:.1f} s")
        FIND.test("afk-end: mit dem Ton von sounds.end-forced", ton_da(bot, seit, "block.respawn_anchor.deplete"), "")

        set_option(env, "warning-percent", "0", "afk-end")
        since = bot.mark()
        if FIND.test("Cam-Modus fuer afk-end ohne Warnung gestartet", cam_on(bot), ""):
            ende = bot.expect(r"you did not move for 12 seconds", since, 16000)
            z = zeilen_seit(bot, since)
            FIND.test("afk-end.warning-percent: 0 - der Cam-Modus endet ohne Warnung",
                      bool(ende) and not gesagt(z, r"You are not moving"), zeige(z[-3:]))

        if FIND.test("Cam-Modus fuer afk-end mit Bewegung gestartet", cam_on(bot), ""):
            since = bot.mark()
            for schritt in range(7):
                bot.call("fly", wait=30, dy=0.5 if schritt % 2 == 0 else -0.5, timeout=5000)
                time.sleep(2.5)
            z = zeilen_seit(bot, since)
            FIND.test("afk-end: wer sich bewegt, bleibt im Cam-Modus",
                      spielmodus_ist(bot, "adventure") and not gesagt(z, r"did not move"), zeige(z[-3:]))
            cam_off(bot)

        vorher = len(server_log(env))
        set_option(env, "seconds", "3", "afk-end")
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("afk-end.seconds: weniger als 10 wird gemeldet und auf 10 gehoben",
                  "afk-end.seconds is too small: 3. Using 10" in log, log.strip()[-200:] or "nichts im Log")
    finally:
        cam_off(bot)
        set_options(env, [("enabled", "false", "afk-end"), ("seconds", "300", "afk-end"),
                          ("warning-percent", "80", "afk-end")])


def startverzoegerung_checks(env, bot):
    """start-delay: Nach /cam beginnt der Cam-Modus erst, wenn der Spieler ein
    paar Sekunden still gestanden hat.

    Voreingestellt ist es aus, mit 3 Sekunden - nachgesehen in der
    ausgelieferten Datei. Eingeschaltet kommt nach /cam nicht gleich "Cam mode
    activated", sondern "Cam mode in 3 seconds - stand still" in der
    Action-Bar, dann 2 und 1, jede Sekunde mit dem Ton von sounds.start-delay,
    und erst nach den drei Sekunden der Cam-Modus. Ein Schritt bricht den Start
    ab ("you moved"), ebenso ein Treffer ("you took damage") und ein zweites
    /cam ("cancelled"); danach laeuft kein Cam-Modus. Fehlt, was start-cost
    verlangt, hoert er das gleich und wartet nicht erst; kann er zahlen, wird
    erst beim Start bezahlt. Mehr als 60 Sekunden werden gemeldet und auf 60
    gesenkt.
    """
    def hinstellen_auf_boden():
        pos = bot.server_pos()
        if pos is not None:
            bot.chat(f"/execute in minecraft:overworld run tp @s {_floor(pos[0]) + 0.5} {BODEN_Y + 1} "
                     f"{_floor(pos[2]) + 0.5}")
            time.sleep(1.0)

    def kein_cam(zeilen):
        return not gesagt(zeilen, r"[Cc]am mode activated") and not spielmodus_ist(bot, "adventure")

    try:
        cam_off(bot)
        FIND.test("Voreingestellt steht start-delay.enabled: false mit seconds: 3 da",
                  re.search(r"(?m)^start-delay:\s*\n\s*enabled:\s*false\s*\n(?:\s*#.*\n)*\s*seconds:\s*3\s*$",
                            ausgeliefert(env)) is not None, "")
        set_option(env, "enabled", "true", "start-delay")
        hinstellen_auf_boden()

        seit = toene_marke(bot)
        since = bot.mark()
        start = time.time()
        bot.chat("/cam")
        zaehlt = bot.expect(r"Cam mode in 3 seconds - stand still", since, 3000)
        an = bot.expect(r"[Cc]am mode activated", since, 8000)
        dauer = time.time() - start
        z = zeilen_seit(bot, since)
        FIND.test("start-delay: nach /cam steht \"Cam mode in 3 seconds - stand still\" in der Action-Bar",
                  bool(zaehlt), zeige(z[:3]))
        FIND.test("start-delay: der Cam-Modus beginnt erst nach den drei Sekunden",
                  bool(an) and 2.7 <= dauer <= 5.0, f"nach {dauer:.1f} s")
        FIND.test("start-delay: die Action-Bar zaehlt 3, 2, 1",
                  all(gesagt(z, rf"Cam mode in {n} seconds") for n in (3, 2, 1)), zeige(z))
        ticks = sum("block.note_block.hat" in t["text"] for t in bot.call("sounds", since=seit).get("sounds", []))
        FIND.test("sounds.start-delay - jede Sekunde der Ton", ticks >= 3, f"{ticks} Mal")
        cam_off(bot)
        hinstellen_auf_boden()

        since = bot.mark()
        bot.chat("/cam")
        time.sleep(1.0)
        bot.call("fly", wait=30, dx=2, timeout=5000)
        time.sleep(3.0)
        z = zeilen_seit(bot, since)
        FIND.test("start-delay: ein Schritt bricht den Start ab",
                  gesagt(z, r"Cam mode start cancelled: you moved") and kein_cam(z), zeige(z))
        hinstellen_auf_boden()

        since = bot.mark()
        bot.chat("/cam")
        time.sleep(1.0)
        console(env, f"damage {BOT_NAME} 1", pause=0.5)
        time.sleep(3.0)
        z = zeilen_seit(bot, since)
        FIND.test("start-delay: ein Treffer bricht den Start ab",
                  gesagt(z, r"Cam mode start cancelled: you took damage") and kein_cam(z), zeige(z))
        # Die Sperre von cam-safety nach dem Treffer abwarten.
        time.sleep(CAM_SAFETY_SEKUNDEN + 0.5)

        since = bot.mark()
        bot.chat("/cam")
        time.sleep(1.0)
        bot.chat("/cam")
        time.sleep(3.5)
        z = zeilen_seit(bot, since)
        FIND.test("start-delay: ein zweites /cam bricht den Start ab",
                  gesagt(z, r"Cam mode start cancelled\.") and kein_cam(z), zeige(z))

        bot.chat("/clear @s")
        time.sleep(0.5)
        set_options(env, [("enabled", "true", "start-cost"), ("items", "{diamond: 3}", "start-cost")])
        z = befehl(bot, "/cam", warten=1.5)
        FIND.test("start-delay mit start-cost: wer nicht zahlen kann, hoert es gleich und wartet nicht",
                  gesagt(z, r"You are missing 3x diamond") and not gesagt(z, r"Cam mode in"), zeige(z))
        bot.chat("/give @s minecraft:diamond 3")
        time.sleep(0.5)
        since = bot.mark()
        bot.chat("/cam")
        time.sleep(1.5)
        vorher = zeilen_seit(bot, since)
        an = bot.expect(r"[Cc]am mode activated", since, 6000)
        z = zeilen_seit(bot, since)
        FIND.test("start-delay mit start-cost: bezahlt wird erst beim Start",
                  not gesagt(vorher, r"cost you") and bool(an) and gesagt(z, r"cost you 3x diamond"), zeige(z))
        cam_off(bot)

        vorher = len(server_log(env))
        set_option(env, "seconds", "100", "start-delay")
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("start-delay.seconds: mehr als 60 wird gemeldet und auf 60 gesenkt",
                  "start-delay.seconds is too large: 100. Using 60" in log, log.strip()[-200:] or "nichts im Log")
    finally:
        set_options(env, [("enabled", "false", "start-delay"), ("seconds", "3", "start-delay"),
                          ("enabled", "false", "start-cost"), ("items", "{}", "start-cost")])
        cam_off(bot)
        bot.chat("/clear @s")
        time.sleep(0.5)


def partikel_marke(bot):
    """Wie viele Partikel bisher beim Bot ankamen, als Marke fuer partikel_seit."""
    return bot.call("particles", since=10 ** 9).get("count", 0)


def partikel_seit(bot, seit):
    """Die Partikel seit der Marke: Ort und ob mit dem Flag fuer die Sichtweite."""
    return bot.call("particles", since=seit).get("particles", [])


def abstand_zur_strecke(p, a, b):
    """Wie weit ein Punkt von der Strecke zwischen a und b weg ist."""
    ab = [b[i] - a[i] for i in range(3)]
    lang = sum(c * c for c in ab)
    t = 0.0 if lang == 0 else max(0.0, min(1.0, sum((p[i] - a[i]) * ab[i] for i in range(3)) / lang))
    return math.dist(p, [a[i] + ab[i] * t for i in range(3)])


# So hoch ueber den Fuessen zeichnet /cam trail die Linie, CamTrail.HEIGHT.
SPUR_HOEHE = 1.0


def flugspur_checks(env, bot):
    """cam-trail: /cam trail <Name> [Nummer|all] zeigt, wo die Kamera eines
    Spielers geflogen ist.

    Voreingestellt ist es aus und merkt sich 5 Fluege - nachgesehen in der
    ausgelieferten Datei -, und /cam trail sagt, dass es aus ist.
    Eingeschaltet fliegt der Bot sechs hoch und sechs zur Seite, geht mit /cam
    back zum Koerper zurueck und fliegt von dort schraeg weiter. Mitten im Flug
    nennt /cam trail von der Konsole "still in cam mode", danach die Strecke
    ohne den Sprung zurueck, den Platz des Koerpers und den letzten der
    Kamera. Der Bot selbst bekommt die Linie als Partikel, alle mit dem Flag
    fuer die Sichtweite und alle auf dem geflogenen Weg - keiner auf der Linie
    quer zurueck, die /cam back nicht geflogen ist. /cam trail ohne Namen
    blendet sie aus, und nach show-seconds hoert sie von selbst auf.

    Nach einem zweiten Flug zeigt /cam trail <Name> nur den neuen, mit Knoepfen
    fuer [1], [2] und [all], /cam trail <Name> 2 nur den alten, all beide; #3
    gibt es nicht, und die Tab-Vervollstaendigung bietet 1, 2 und all an.
    flights: 1 laesst nach dem Reload nur den letzten stehen. Ein Name ohne
    Flug wird gemeldet, ohne camplugin.trail fehlt das Recht, und
    enabled: false vergisst die Fluege.
    """
    def konsole(text):
        """Was die Konsole darauf sagt, ohne Farben - auch ohne die, die der
        Server mitten in eine Zeile setzt, wo der Text die Farbe wechselt."""
        vorher = len(server_log(env))
        console(env, text, pause=1.5)
        return re.sub(r"\x1b\[[0-9;]*m", "", strip_colors(server_log(env)[vorher:]))

    def block(pos):
        return f"{_floor(pos[0])} {_floor(pos[1])} {_floor(pos[2])}"

    def hoch(pos):
        return [pos[0], pos[1] + SPUR_HOEHE, pos[2]]

    try:
        cam_off(bot)
        FIND.test("Voreingestellt steht cam-trail.enabled: true da, mit flights: 5 (der Testserver startet es aus)",
                  re.search(r"(?m)^cam-trail:\s*\n\s*enabled:\s*true\s*\n(?:\s*#.*\n)*\s*flights:\s*5\s*$",
                            quelle_config(env)) is not None, "")
        z = befehl(bot, f"/cam trail {BOT_NAME}")
        FIND.test("cam-trail.enabled: false - /cam trail ist abgeschaltet",
                  gesagt(z, r"/cam trail is switched off"), zeige(z))

        set_options(env, [("enabled", "true", "cam-trail"), ("show-seconds", "4", "cam-trail")])
        heim = bot.server_pos()
        if not FIND.test("Cam-Modus fuer die Flugspur gestartet", heim is not None and cam_on(bot), ""):
            return
        bot.call("fly", wait=30, dy=6, timeout=15000)
        time.sleep(0.5)
        oben = bot.server_pos()
        log = konsole(f"cam trail {BOT_NAME}")
        FIND.test("/cam trail mitten im Flug: \"still in cam mode\"",
                  re.search(rf"Cam flight #1 of {BOT_NAME}: \d+ blocks in .+, still in cam mode", log) is not None,
                  log.strip()[-200:] or "nichts im Log")
        bot.call("fly", wait=30, dx=6, timeout=15000)
        time.sleep(0.5)
        ecke = bot.server_pos()
        befehl(bot, "/cam back")
        bot.call("fly", wait=30, dy=2, dz=4, timeout=15000)
        time.sleep(0.5)
        ende = bot.server_pos()
        cam_off(bot)
        if None in (oben, ecke, ende):
            FIND.test("Positionen fuer die Flugspur lesbar", False, f"{oben} {ecke} {ende}")
            return

        log = konsole(f"cam trail {BOT_NAME}")
        strecke = re.search(rf"Cam flight #1 of {BOT_NAME}: (\d+) blocks in .+, ended .+ ago", log)
        FIND.test("/cam trail von der Konsole: die Strecke ohne den Sprung von /cam back",
                  strecke is not None and 14 <= int(strecke.group(1)) <= 19, log.strip()[-300:] or "nichts im Log")
        FIND.test("/cam trail von der Konsole: der Platz des Koerpers",
                  f"From the body at {block(heim)} (world)" in log, log.strip()[-300:] or "nichts im Log")
        FIND.test("/cam trail von der Konsole: der letzte Platz der Kamera",
                  f"Camera last at {block(ende)} (world)" in log, log.strip()[-300:] or "nichts im Log")

        seit = partikel_marke(bot)
        z = befehl(bot, f"/cam trail {BOT_NAME}")
        FIND.test("/cam trail: der Bot bekommt die Zusammenfassung und wie lange die Linie steht",
                  gesagt(z, rf"Cam flight #1 of {BOT_NAME}: \d+ blocks") and gesagt(z, r"Shown for 4 seconds"), zeige(z))
        teile = partikel_seit(bot, seit)
        FIND.test("/cam trail: die Linie kommt als Partikel an", len(teile) >= 20, f"{len(teile)} Partikel")
        FIND.test("/cam trail: alle mit dem Flag fuer die Sichtweite",
                  bool(teile) and all(p["far"] for p in teile), "")
        wege = [(hoch(heim), hoch(oben)), (hoch(oben), hoch(ecke)), (hoch(heim), hoch(ende))]
        daneben = max((min(abstand_zur_strecke(p["pos"], a, b) for a, b in wege) for p in teile), default=99.0)
        FIND.test("/cam trail: jeder Partikel liegt auf dem geflogenen Weg",
                  daneben < 0.6, f"bis {daneben:.2f} Bloecke daneben")
        quer = [(ecke[i] + heim[i]) / 2 for i in range(3)]
        naechster = min((math.dist(p["pos"], hoch(quer)) for p in teile), default=99.0)
        FIND.test("/cam trail: keine Linie quer zurueck, wo /cam back die Kamera versetzt hat",
                  naechster > 1.5, f"der naechste {naechster:.2f} Bloecke von der Mitte")

        z = befehl(bot, "/cam trail", warten=0.5)
        seit = partikel_marke(bot)
        time.sleep(1.5)
        FIND.test("/cam trail ohne Namen blendet die Linie aus",
                  gesagt(z, r"The flight is hidden again") and not partikel_seit(bot, seit), zeige(z))
        z = befehl(bot, "/cam trail")
        FIND.test("/cam trail ohne Namen und ohne Linie sagt, wie es geht",
                  gesagt(z, r"Use /cam trail <player>"), zeige(z))

        befehl(bot, f"/cam trail {BOT_NAME}", warten=0.5)
        time.sleep(4.5)
        seit = partikel_marke(bot)
        time.sleep(1.5)
        FIND.test("cam-trail.show-seconds: 4 - danach kommen keine Partikel mehr",
                  not partikel_seit(bot, seit), "")

        # Ein zweiter Flug, schraeg hoch nach Norden: Seine Linie liegt weit
        # weg von der Ecke des ersten.
        if not FIND.test("Zweiter Cam-Modus fuer die Flugspur gestartet", cam_on(bot), ""):
            return
        bot.call("fly", wait=30, dy=3, dz=-5, timeout=15000)
        time.sleep(0.5)
        nord = bot.server_pos()
        cam_off(bot)
        if nord is None:
            FIND.test("Position nach dem zweiten Flug lesbar", False, "")
            return

        def bei(teile, pos):
            return any(math.dist(p["pos"], hoch(pos)) < 1.0 for p in teile)

        def gezeigt(text):
            """Erst ausblenden, was noch steht, dann zeigen lassen: was im
            Chat kam, die Chatpakete mit den Knoepfen und die Partikel."""
            befehl(bot, "/cam trail", warten=0.5)
            seit = partikel_marke(bot)
            pakete_seit = bot.call("chat_packets", since=10 ** 9).get("count", 0)
            z = befehl(bot, text)
            pakete = " ".join(p["text"] for p in bot.call("chat_packets", since=pakete_seit).get("packets", []))
            return z, partikel_seit(bot, seit), pakete

        z, teile, pakete = gezeigt(f"/cam trail {BOT_NAME}")
        FIND.test("/cam trail <Name>: der neueste Flug, nur seine Linie",
                  gesagt(z, rf"Cam flight #1 of {BOT_NAME}") and bei(teile, nord) and not bei(teile, ecke),
                  zeige(z[:1]) + f" - {len(teile)} Partikel")
        FIND.test("/cam trail: Knoepfe fuer die gemerkten Fluege und fuer alle",
                  gesagt(z, r"Kept flights: \[1\] \[2\] \[all\]") and f"/cam trail {BOT_NAME} 2" in pakete
                  and f"/cam trail {BOT_NAME} all" in pakete, zeige(z))
        z, teile, _ = gezeigt(f"/cam trail {BOT_NAME} 2")
        FIND.test("/cam trail <Name> 2: der Flug davor, nur seine Linie",
                  gesagt(z, rf"Cam flight #2 of {BOT_NAME}") and bei(teile, ecke) and not bei(teile, nord),
                  zeige(z[:1]) + f" - {len(teile)} Partikel")
        z, teile, _ = gezeigt(f"/cam trail {BOT_NAME} all")
        FIND.test("/cam trail <Name> all: beide Fluege im Chat und beide Linien",
                  gesagt(z, rf"The last 2 cam flights of {BOT_NAME}") and gesagt(z, r"^#1: \d+ blocks")
                  and gesagt(z, r"^#2: \d+ blocks") and bei(teile, ecke) and bei(teile, nord),
                  zeige(z[:3]) + f" - {len(teile)} Partikel")
        befehl(bot, "/cam trail", warten=0.5)
        z = befehl(bot, f"/cam trail {BOT_NAME} 3")
        FIND.test("/cam trail <Name> 3: den gibt es nicht",
                  gesagt(z, rf"{BOT_NAME} has no cam flight #3, only 2 kept"), zeige(z))
        vorschlag = bot.call("tab", text=f"/cam trail {BOT_NAME} ").get("matches", [])
        FIND.test("Tab-Vervollstaendigung nach dem Namen: 1, 2 und all",
                  sorted(vorschlag) == ["1", "2", "all"], str(vorschlag))
        set_option(env, "flights", "1", "cam-trail")
        log = konsole(f"cam trail {BOT_NAME} 2")
        FIND.test("cam-trail.flights: 1 - nach dem Reload ist nur noch der letzte Flug da",
                  f"{BOT_NAME} has no cam flight #2, only 1 kept" in log, log.strip()[-200:] or "nichts im Log")

        z = befehl(bot, "/cam trail Niemand")
        FIND.test("/cam trail: wer keinen Flug hat, wird gemeldet",
                  gesagt(z, r"Niemand has no recorded cam flight"), zeige(z))

        console(env, f"deop {BOT_NAME}", pause=1.5)
        z = befehl(bot, f"/cam trail {BOT_NAME}")
        console(env, f"op {BOT_NAME}", pause=1.5)
        FIND.test("/cam trail ohne camplugin.trail: keine Berechtigung",
                  gesagt(z, r"permission") and not gesagt(z, r"Cam flight"), zeige(z))

        set_option(env, "enabled", "false", "cam-trail")
        set_option(env, "enabled", "true", "cam-trail")
        log = konsole(f"cam trail {BOT_NAME}")
        FIND.test("cam-trail.enabled: false vergisst die Fluege",
                  f"{BOT_NAME} has no recorded cam flight" in log, log.strip()[-200:] or "nichts im Log")
    finally:
        console(env, f"op {BOT_NAME}", pause=1.0)
        cam_off(bot)
        set_options(env, [("enabled", "false", "cam-trail"), ("show-seconds", "30", "cam-trail"),
                          ("flights", "5", "cam-trail")])


def vorschlag_checks(env, bot):
    """Was /cam zur Tab-Vervollstaendigung vorschlaegt: nur, was gerade etwas
    tut.

    Voreingestellt stehen stats und list da (der Bot ist op).
    statistics.enabled: false nimmt stats heraus, und nach /cam stats kommt
    weder reset noch player - mit flight-limit: battery zeigt /cam stats aber
    den Akku und steht wieder da, mit player und ohne reset.
    cam-list.access: false nimmt list heraus, und /cam list ist fuer Spieler
    aus; die Konsole darf es weiter.
    """
    def tab(text):
        return bot.call("tab", text=text).get("matches", [])

    try:
        cam_off(bot)
        jetzt = tab("/cam ")
        FIND.test("Voreingestellt schlaegt /cam stats und list vor", "stats" in jetzt and "list" in jetzt, str(jetzt))

        set_option(env, "enabled", "false", "statistics")
        jetzt = tab("/cam ")
        worte = tab("/cam stats ")
        FIND.test("statistics.enabled: false - stats wird nicht mehr vorgeschlagen",
                  "stats" not in jetzt and "list" in jetzt, str(jetzt))
        FIND.test("statistics.enabled: false - nach /cam stats weder reset noch player", not worte, str(worte))

        set_option(env, "flight-limit", "battery")
        jetzt = tab("/cam ")
        worte = tab("/cam stats ")
        FIND.test("statistics aus, flight-limit: battery - stats steht fuer den Akku wieder da, ohne reset",
                  "stats" in jetzt and "player" in worte and "reset" not in worte, f"{jetzt} / {worte}")

        set_options(env, [("flight-limit", "false"), ("enabled", "true", "statistics"),
                          ("access", "false", "cam-list")])
        jetzt = tab("/cam ")
        z = befehl(bot, "/cam list")
        vorher = len(server_log(env))
        console(env, "cam list", pause=1.5)
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("cam-list.access: false - list wird nicht vorgeschlagen und ist fuer Spieler aus",
                  "list" not in jetzt and "stats" in jetzt and gesagt(z, r"permission"), f"{jetzt} / {zeige(z)}")
        FIND.test("cam-list.access: false - die Konsole darf /cam list weiter",
                  "Nobody is in cam mode" in log, log.strip()[-200:] or "nichts im Log")
    finally:
        set_options(env, [("flight-limit", "false"), ("enabled", "true", "statistics"),
                          ("access", "admin", "cam-list")])


def spots_loeschen(bot, *namen):
    """Die Aussichtspunkte des Bots loeschen, die ein frueherer Lauf
    hinterlassen haben kann. Die Datei des Bots bleibt zwischen zwei Laeufen
    stehen, und solange er online ist, haelt das Plugin sie im Speicher - in
    der Datei zu loeschen hiesse, nichts zu loeschen."""
    for name in namen:
        bot.chat(f"/cam spot delete {name}")
        time.sleep(0.3)
    time.sleep(0.5)


def aussichtspunkt_checks(env, bot):
    """viewpoints: /cam spot merkt sich Stellen mit Blickrichtung, und die
    Kamera springt dorthin - nur, wohin sie auch fliegen duerfte.

    Voreingestellt ist es an, mit 10 Punkten je Spieler, in der Datei -
    nachgesehen in der ausgelieferten Datei. Der Bot fliegt hoch und zur
    Seite, schaut nach Osten und merkt sich die Stelle als "Turm". Von
    woanders springt er mit "/cam spot turm" zurueck - gross und klein zaehlt
    nicht -, im Cam-Modus, mit dem Ton von sounds.spot und "At spot Turm" in
    der Action-Bar, und schaut danach wieder nach Osten. Ohne Cam-Modus
    springt er hin, wenn der Punkt nicht weiter als start-distance (16) weg
    ist: der Cam-Modus startet dort, wo er steht; weiter weg sagt "You are X
    blocks too far away", mit start-distance: 0 geht es nur im Cam-Modus.
    /cam spot list zeigt den Punkt mit einem Klick auf
    "/cam spot Turm" und einem, der "/cam spot delete Turm" in die Zeile
    legt, und in der Datei des Bots steht er unter spots.

    Ein Punkt, den der Bot zu Fuss zehn Bloecke weiter gesetzt hat, geht mit
    max-distance 100. Mit max-distance: 6 ist er zu weit, in einem verbotenen
    Biom gesperrt, mit einem Stein an seiner Stelle verstellt, und unter
    max-distance-mode: signal kommt das Signal durch eine Wand aus
    Grundgestein dazwischen nicht hin - jedes Mal mit der Meldung, warum, und
    die Kamera bleibt, wo sie ist.

    Ein falscher Name, ein voller Platz, ein unbekannter Punkt und das
    Loeschen sagen, was los ist; ein Name, den es schon gibt, wandert, auch
    wenn kein Platz mehr frei ist; mit max-per-player: 0 gibt es kein Limit.
    In der Datei steht bei jedem Punkt, wann er entstand. Die Tab-Vervollstaendigung bietet nach
    /cam spot set, delete, list und die Namen an, nach /cam spot delete die
    Namen. Mit storage: memory steht der Punkt nicht in der Datei, und nach
    /cam reload ist er weg.
    """
    def tab(text):
        return bot.call("tab", text=text).get("matches", [])

    def bleibt(vorher, nachher):
        return vorher is not None and nachher is not None and math.dist(vorher, nachher) < 0.5

    def datei_text():
        datei = spielerdatei(env)
        return datei.read_text(encoding="utf-8") if datei else ""

    if not FIND.test("Cam-Modus ist vor dem Test der Aussichtspunkte aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    bx, bz = _floor(x) + 10, _floor(z)
    # Luft ueber dem Platz und bis zum Punkt zehn Bloecke weiter, falls dort
    # ein Haus eines Dorfes steht.
    console(env, f"fill {_floor(x) - 3} {y} {bz - 3} {bx + 2} {y + 8} {bz + 3} minecraft:air", pause=0.5)
    biom = f"{bx - 4} {max(-64, y - 4)} {bz - 4} {bx + 4} {min(320, y + 4)} {bz + 4}"
    wand = f"{_floor(x) + 5} {y} {bz - 3} {_floor(x) + 5} {y + 4} {bz + 3}"
    try:
        FIND.test("Voreingestellt steht viewpoints mit enabled: true, max-per-player: 10 und storage: file da",
                  re.search(r"(?m)^viewpoints:\s*\n\s*enabled:\s*true\s*\n(?:\s*#.*\n)*\s*start-distance:\s*16\s*\n"
                            r"(?:\s*#.*\n)*\s*max-per-player:\s*10\s*\n"
                            r"(?:\s*#.*\n)*\s*storage:\s*file\s*$", ausgeliefert(env)) is not None, "")
        spots_loeschen(bot, "Turm", "Weit", "Mem")
        zeilen = befehl(bot, "/cam spot")
        FIND.test("/cam spot ohne Punkte sagt, wie man einen setzt", gesagt(zeilen, r"You have no spots yet"),
                  zeige(zeilen))

        hinstellen(bot, x, y, z)
        if not FIND.test("Cam-Modus fuer die Aussichtspunkte gestartet", cam_on(bot), ""):
            return
        bot.call("fly", wait=30, dy=4, dx=3, timeout=15000)
        time.sleep(1.0)
        # Nach Osten, ohne die Stelle zu aendern; teleports.command: false
        # laesst den Cam-Modus dabei laufen.
        bot.chat("/tp @s ~ ~ ~ -90 20")
        time.sleep(1.0)
        punkt = bot.server_pos()
        zeilen = befehl(bot, "/cam spot set Turm")
        FIND.test("/cam spot set Turm - der Punkt ist gemerkt", gesagt(zeilen, r"Spot Turm saved here"), zeige(zeilen))
        bot.chat("/tp @s ~ ~ ~ 90 0")
        time.sleep(0.5)
        bot.call("fly", wait=30, dy=-2, dx=-5, timeout=15000)
        time.sleep(1.0)
        weg = bot.server_pos()
        seit = toene_marke(bot)
        zeilen = befehl(bot, "/cam spot turm")
        da = bot.server_pos()
        blick = bot.server_data("Rotation[0]")
        FIND.test("/cam spot turm - die Kamera steht wieder am Punkt, gross und klein zaehlt nicht",
                  punkt is not None and weg is not None and math.dist(weg, punkt) > 3 and bleibt(punkt, da),
                  f"{punkt} -> {weg} -> {da}")
        FIND.test("/cam spot: der Cam-Modus laeuft weiter", spielmodus_ist(bot, "adventure"), "")
        FIND.test("/cam spot: die Kamera schaut wieder, wohin sie beim Setzen schaute",
                  blick is not None and abs(blick + 90.0) < 1.0, str(blick))
        FIND.test("/cam spot: \"At spot Turm\" in der Action-Bar", gesagt(zeilen, r"At spot Turm"), zeige(zeilen))
        FIND.test("sounds.spot - der Ton beim Sprung", ton_da(bot, seit, "item.chorus_fruit.teleport"), "")

        cam_off(bot)
        zeilen = befehl(bot, "/cam spot Turm", warten=2.5)
        FIND.test("/cam spot ohne Cam-Modus, nah dran: er startet dort, und die Kamera steht am Punkt",
                  gesagt(zeilen, r"Cam mode activated") and bleibt(punkt, bot.server_pos()), zeige(zeilen))
        cam_off(bot)
        FIND.test("Voreingestellt steht viewpoints.start-distance: 16 da",
                  re.search(r"(?m)^  start-distance:\s*16\s*$", quelle_config(env)) is not None, "")
        set_option(env, "start-distance", "3", "viewpoints")
        zeilen = befehl(bot, "/cam spot Turm")
        FIND.test("start-distance: 3 - weiter weg: \"You are X blocks too far away\", und nichts startet",
                  gesagt(zeilen, r"You are \d+ blocks too far away") and not spielmodus_ist(bot, "adventure"),
                  zeige(zeilen))
        set_option(env, "start-distance", "0", "viewpoints")
        zeilen = befehl(bot, "/cam spot Turm")
        FIND.test("start-distance: 0 - ohne Cam-Modus springt er nicht", gesagt(zeilen, r"only works in cam mode"),
                  zeige(zeilen))
        set_option(env, "start-distance", "16", "viewpoints")
        seit = bot.call("chat_packets", since=10 ** 9).get("count", 0)
        zeilen = befehl(bot, "/cam spot list")
        pakete = " ".join(p["text"] for p in bot.call("chat_packets", since=seit).get("packets", []))
        FIND.test("/cam spot list - der Punkt, mit Knoepfen zum Hinspringen und zum Loeschen",
                  gesagt(zeilen, r"Your spots \(1 of 10\)") and gesagt(zeilen, r"- Turm: ")
                  and "/cam spot Turm" in pakete and "/cam spot delete Turm" in pakete,
                  f"{zeige(zeilen)} / {pakete[-300:]}")
        text = datei_text()
        FIND.test("storage: file - der Punkt steht in der Datei des Bots unter spots, mit dem Tag, an dem er entstand",
                  re.search(r"(?m)^spots:\s*$", text) is not None and re.search(r"name:\s*Turm", text) is not None
                  and re.search(r"created:\s*\d{13}", text) is not None, text[-300:] or "keine Datei")

        hinstellen(bot, bx + 0.5, y, z)
        zeilen = befehl(bot, "/cam spot set Weit")
        FIND.test("/cam spot set geht auch zu Fuss", gesagt(zeilen, r"Spot Weit saved here"), zeige(zeilen))
        weit = (bx + 0.5, y, z)
        hinstellen(bot, x, y, z)
        cam_on(bot)
        zeilen = befehl(bot, "/cam spot Weit")
        FIND.test("/cam spot Weit - zehn Bloecke weg, mit max-distance 100", bleibt(weit, bot.server_pos()),
                  zeige(zeilen))
        tabs = tab("/cam spot ")
        FIND.test("Tab nach /cam spot: set, delete, list und die Namen",
                  all(w in tabs for w in ("set", "delete", "list", "Turm", "Weit")), str(tabs))
        tabs = tab("/cam spot delete W")
        FIND.test("Tab nach /cam spot delete: die Namen", tabs == ["Weit"], str(tabs))

        def gesperrt(name, muster, wann):
            """Vom Koerper aus zum Punkt Weit springen: Er ist gesperrt, die
            Meldung sagt warum, und die Kamera bleibt am Koerper."""
            hinstellen(bot, x, y, z)
            cam_on(bot)
            vorher = bot.server_pos()
            zeilen = befehl(bot, "/cam spot Weit")
            FIND.test(name, gesagt(zeilen, muster) and bleibt(vorher, bot.server_pos()),
                      f"{wann}: {zeige(zeilen)}")

        # Jeder reload beendet den Cam-Modus, gesperrt() startet ihn neu.
        set_option(env, "max-distance", "6.0")
        gesperrt("max-distance: 6 - der Punkt ist zu weit, die Kamera bleibt",
                 r"You are too far away", "max-distance 6")
        set_option(env, "max-distance", "100.0")

        bot.chat(f"/fillbiome {biom} minecraft:lush_caves")
        time.sleep(1.5)
        gesperrt("cam-area: der Punkt liegt in einem verbotenen Biom, die Kamera bleibt",
                 r"Spot Weit lies in Lush Caves", "Biom")
        bot.chat(f"/fillbiome {biom} minecraft:plains")
        time.sleep(1.5)

        bot.chat(f"/setblock {bx} {y} {bz} minecraft:stone")
        time.sleep(0.5)
        gesperrt("Ein Stein an der Stelle - der Punkt ist verstellt, die Kamera bleibt",
                 r"Something has been built at spot Weit", "Stein")
        bot.chat(f"/setblock {bx} {y} {bz} minecraft:air")
        time.sleep(0.5)

        set_option(env, "max-distance-mode", "signal")
        bot.chat(f"/fill {wand} minecraft:bedrock")
        time.sleep(0.5)
        gesperrt("max-distance-mode: signal - durch Grundgestein reicht das Signal nicht zum Punkt",
                 r"The signal does not reach spot Weit", "Signal")
        bot.chat(f"/fill {wand} minecraft:air")
        time.sleep(0.5)
        set_option(env, "max-distance-mode", "fixed")

        cam_off(bot)
        for name, eingabe, muster in (
                ("/cam spot set mit einem Punkt im Namen", "/cam spot set a.b", r"A spot name has 1 to 16"),
                ("/cam spot set list - ein Wort des Befehls ist kein Name", "/cam spot set list",
                 r"A spot name has 1 to 16"),
                ("/cam spot delete eines unbekannten Punkts", "/cam spot delete Nix", r"You have no spot called Nix")):
            zeilen = befehl(bot, eingabe)
            FIND.test(name, gesagt(zeilen, muster), zeige(zeilen))
        set_option(env, "max-per-player", "2")
        zeilen = befehl(bot, "/cam spot set Dritter")
        FIND.test("max-per-player: 2 - ein dritter Punkt hat keinen Platz",
                  gesagt(zeilen, r"You have 2 spots already"), zeige(zeilen))
        zeilen = befehl(bot, "/cam spot set turm")
        FIND.test("max-per-player: 2 - ein Name, den es schon gibt, wandert trotzdem",
                  gesagt(zeilen, r"Spot turm moved here"), zeige(zeilen))
        set_option(env, "max-per-player", "0")
        zeilen = befehl(bot, "/cam spot set Dritter")
        liste = befehl(bot, "/cam spot list")
        FIND.test("max-per-player: 0 - kein Limit, und die Liste nennt keins",
                  gesagt(zeilen, r"Spot Dritter saved here") and gesagt(liste, r"Your spots \(3\):"),
                  f"{zeige(zeilen)} / {zeige(liste)}")
        befehl(bot, "/cam spot delete Dritter")
        set_option(env, "max-per-player", "10")
        zeilen = befehl(bot, "/cam spot delete Turm")
        FIND.test("/cam spot delete Turm - der Punkt ist geloescht", gesagt(zeilen, r"Spot turm deleted"),
                  zeige(zeilen))
        cam_on(bot)
        zeilen = befehl(bot, "/cam spot Turm")
        FIND.test("Ein geloeschter Punkt ist weg", gesagt(zeilen, r"You have no spot called Turm"), zeige(zeilen))
        cam_off(bot)

        set_option(env, "storage", "memory", "viewpoints")
        zeilen = befehl(bot, "/cam spot set Mem")
        text = datei_text()
        FIND.test("storage: memory - der Punkt ist gemerkt, aber nicht in der Datei",
                  gesagt(zeilen, r"Spot Mem saved here") and re.search(r"name:\s*Mem", text) is None, zeige(zeilen))
        console(env, "cam reload", pause=2)
        zeilen = befehl(bot, "/cam spot list")
        FIND.test("storage: memory - nach /cam reload ist der Punkt weg", gesagt(zeilen, r"You have no spots yet"),
                  zeige(zeilen))
        set_option(env, "storage", "file", "viewpoints")
        zeilen = befehl(bot, "/cam spot list")
        FIND.test("storage: file - der Punkt aus der Datei ist wieder da", gesagt(zeilen, r"- Weit: "), zeige(zeilen))
    finally:
        try:
            cam_off(bot)
            set_options(env, [("max-distance", "100.0"), ("max-distance-mode", "fixed"),
                              ("max-per-player", "10"), ("storage", "file", "viewpoints"),
                              ("start-distance", "16", "viewpoints")])
            spots_loeschen(bot, "Turm", "Weit", "Mem", "Dritter")
            bot.chat(f"/fill {wand} minecraft:air")
            bot.chat(f"/setblock {bx} {y} {bz} minecraft:air")
            bot.chat(f"/fillbiome {biom} minecraft:plains")
            time.sleep(1.0)
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der Aussichtspunkte: {exc}")


def foto_checks(env, bot):
    """photo-mode: /cam photo blendet fuer den Kamera-Spieler aus, was im Bild
    stoert - und nur fuer ihn.

    Voreingestellt ist er an und blendet Spieler und Koerper aus -
    nachgesehen in der ausgelieferten Datei. Ein zweiter Spieler steht drei
    Bloecke neben dem Koerper des Bots. Im Foto-Modus ist er beim Bot nicht
    mehr da, und der Koerper des Bots mit seinem Namen auch nicht; beim
    zweiten Spieler steht der Koerper weiter. Stupst er den Koerper an,
    erfaehrt der Bot es trotzdem. Geht er selbst in den Cam-Modus, bleiben er
    und sein neuer Koerper beim Bot unsichtbar. Ein zweites /cam photo zeigt
    alles wieder, ebenso das Ende des Cam-Modus. Wer waehrend des Foto-Modus
    geht und danach wiederkommt, ist wieder zu sehen. Mit hide-players: false
    bleibt der zweite Spieler im Bild, nur der Koerper geht. Ohne Cam-Modus
    geht /cam photo nicht, mit enabled: false gar nicht, und die
    Tab-Vervollstaendigung bietet es nur im Cam-Modus an.

    Wo ein Koerper steht, zaehlt der Test an den Entitaeten um seine Stelle:
    Ruestungsstaender, Mannequin und Name. Den zweiten Spieler sucht er dort,
    wo der Server ihn hat. Nach Art und Name wird nicht gesucht, mineflayer
    nennt die Arten mit den Daten von 26.1 und haelt einen Spieler fuer etwas
    anderes.
    """
    def entitaeten(client):
        return client.call("entities", radius=24).get("entities", [])

    def sieht_spieler(client, name):
        """Ob der Client eine Entitaet dort sieht, wo der Server den Spieler
        hat. Nach dem Namen geht es nicht: mineflayer haelt einen Spieler von
        26.2 fuer etwas anderes und gibt ihm keinen."""
        wo = bot.call("server_pos", selector=name).get("pos")
        return wo is not None and any(math.dist(e["pos"], wo) < 0.5 for e in entitaeten(client))

    def koerper_bei(client, wo):
        """Wie viele Entitaeten der Client dort sieht, wo ein Koerper steht."""
        return sum(1 for e in entitaeten(client)
                   if math.dist((e["pos"][0], e["pos"][2]), (wo[0], wo[2])) < 1.0
                   and wo[1] - 0.5 <= e["pos"][1] <= wo[1] + 3.0)

    def tab(text):
        return bot.call("tab", text=text).get("matches", [])

    if not FIND.test("Cam-Modus ist vor dem Test des Foto-Modus aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    koerper = (x, y, z)
    sein_koerper = (x + 3, y, z)
    console(env, f"fill {_floor(x) - 2} {y} {_floor(z) - 2} {_floor(x) + 5} {y + 6} {_floor(z) + 2} minecraft:air",
            pause=0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)

    def zuschauer_hin():
        console(env, f"tp {ZUSCHAUER_NAME} {sein_koerper[0]} {y} {z} 90 0", pause=1.0)

    def bot_kamera():
        """Den Bot am Platz in den Cam-Modus bringen und ueber den Koerper fliegen lassen."""
        hinstellen(bot, x, y, z)
        ok = cam_on(bot)
        bot.call("fly", wait=30, dy=4, timeout=15000)
        time.sleep(1.0)
        return ok

    try:
        FIND.test("Voreingestellt steht photo-mode mit enabled, hide-players und hide-bodies auf true da",
                  re.search(r"(?m)^photo-mode:\s*\n\s*enabled:\s*true\s*\n(?:\s*#.*\n)*\s*hide-players:\s*true\s*\n"
                            r"(?:\s*#.*\n)*\s*hide-bodies:\s*true\s*$", ausgeliefert(env)) is not None, "")
        zeilen = befehl(bot, "/cam photo")
        FIND.test("/cam photo ohne Cam-Modus geht nicht", gesagt(zeilen, r"only works in cam mode"), zeige(zeilen))
        FIND.test("Ohne Cam-Modus schlaegt /cam kein photo vor", "photo" not in tab("/cam "), "")
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer den Foto-Modus kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        zuschauer_hin()
        if not FIND.test("Cam-Modus fuer den Foto-Modus gestartet", bot_kamera(), ""):
            return
        FIND.test("Im Cam-Modus schlaegt /cam photo vor", "photo" in tab("/cam "), "")
        FIND.test("Vor dem Foto-Modus sieht der Bot den zweiten Spieler und seinen Koerper",
                  sieht_spieler(bot, ZUSCHAUER_NAME) and koerper_bei(bot, koerper) >= 2,
                  f"Koerper: {koerper_bei(bot, koerper)}")

        zeilen = befehl(bot, "/cam photo")
        FIND.test("/cam photo - \"Photo mode on\"", gesagt(zeilen, r"Photo mode on"), zeige(zeilen))
        FIND.test("photo-mode: der zweite Spieler ist fuer den Bot nicht mehr da",
                  not sieht_spieler(bot, ZUSCHAUER_NAME), "")
        FIND.test("photo-mode: der eigene Koerper mit seinem Namen ist fuer den Bot nicht mehr da",
                  koerper_bei(bot, koerper) == 0, str(koerper_bei(bot, koerper)))
        FIND.test("photo-mode: der zweite Spieler sieht den Koerper weiter",
                  koerper_bei(zuschauer, koerper) >= 2, str(koerper_bei(zuschauer, koerper)))
        FIND.test("photo-mode: der Cam-Modus laeuft weiter", spielmodus_ist(bot, "adventure"), "")

        bei_bot = bot.mark()
        klicken(zuschauer, "activate_entity", at=list(koerper), radius=2)
        time.sleep(1.5)
        zeilen = zeilen_seit(bot, bei_bot)
        FIND.test("photo-mode: wer den Koerper anstupst, zaehlt trotzdem",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} nudged your body!"), zeige(zeilen))

        if FIND.test("Der zweite Spieler ist im Cam-Modus, waehrend der Bot im Foto-Modus ist",
                     zuschauer_cam_an(env, zuschauer, sein_koerper[0], y, z), ""):
            time.sleep(1.0)
            FIND.test("photo-mode: ein neuer Kamera-Spieler und sein Koerper bleiben fuer den Bot unsichtbar",
                      not sieht_spieler(bot, ZUSCHAUER_NAME) and koerper_bei(bot, sein_koerper) == 0,
                      f"Koerper: {koerper_bei(bot, sein_koerper)}")
        zeilen = befehl(bot, "/cam photo")
        FIND.test("/cam photo noch einmal - \"Photo mode off\"", gesagt(zeilen, r"Photo mode off"), zeige(zeilen))
        FIND.test("Nach dem Foto-Modus sieht der Bot den zweiten Spieler, dessen Koerper und den eigenen wieder",
                  sieht_spieler(bot, ZUSCHAUER_NAME) and koerper_bei(bot, sein_koerper) >= 2
                  and koerper_bei(bot, koerper) >= 2,
                  f"{koerper_bei(bot, sein_koerper)} / {koerper_bei(bot, koerper)}")
        cam_off(zuschauer)
        zuschauer_hin()

        befehl(bot, "/cam photo")
        cam_off(bot)
        time.sleep(1.0)
        FIND.test("Das Ende des Cam-Modus beendet den Foto-Modus: der zweite Spieler ist wieder zu sehen",
                  sieht_spieler(bot, ZUSCHAUER_NAME), "")

        bot_kamera()
        befehl(bot, "/cam photo")
        zuschauer.stop()
        time.sleep(2.0)
        cam_off(bot)
        zuschauer.start()
        da = zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned")
        time.sleep(1.5)
        zuschauer_hin()
        FIND.test("Wer waehrend des Foto-Modus geht und danach wiederkommt, ist wieder zu sehen",
                  bool(da) and sieht_spieler(bot, ZUSCHAUER_NAME), "")

        set_option(env, "hide-players", "false", "photo-mode")
        bot_kamera()
        befehl(bot, "/cam photo")
        FIND.test("hide-players: false - der zweite Spieler bleibt im Bild, nur der Koerper ist weg",
                  sieht_spieler(bot, ZUSCHAUER_NAME) and koerper_bei(bot, koerper) == 0,
                  str(koerper_bei(bot, koerper)))
        set_options(env, [("hide-players", "true", "photo-mode"), ("enabled", "false", "photo-mode")])
        bot_kamera()
        zeilen = befehl(bot, "/cam photo")
        FIND.test("photo-mode.enabled: false - /cam photo ist abgeschaltet",
                  gesagt(zeilen, r"/cam photo is switched off") and sieht_spieler(bot, ZUSCHAUER_NAME), zeige(zeilen))
    finally:
        try:
            if zuschauer.proc is not None:
                cam_off(zuschauer)
                zuschauer.stop()
            cam_off(bot)
            set_options(env, [("enabled", "true", "photo-mode"), ("hide-players", "true", "photo-mode")])
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test des Foto-Modus: {exc}")


def zeit_wetter_checks(env, bot):
    """cam-time und cam-weather: /cam time und /cam weather geben dem
    Kamera-Spieler eine eigene Tageszeit und ein eigenes Wetter, nur fuer ihn
    und nur bis zum Ende des Cam-Modus.

    Voreingestellt sind beide an - nachgesehen in der ausgelieferten Datei.
    Die Welt steht am Tag still (advance_time: false, 1000), ohne Regen. Was
    der Client sieht, fragt der Test bei ihm nach: die Tageszeit, wie
    mineflayer sie aus den Uhren des Zeitpakets liest, und den Regen, wie der
    Client ihn aus den Ereignissen rechnet, siehe die Bot-Aktion sky. Ein
    zweiter Spieler daneben sieht die ganze Zeit Tag ohne Regen.

    sunset ist 12000, eine Zahl gilt in Ticks, 24000 ist keine Tageszeit mehr,
    reset gibt die Zeit der Welt zurueck. Regen ist nach den fuenf Sekunden,
    in denen er einsetzt, voll da; ein Gewitter gibt es nicht. Das Ende des
    Cam-Modus gibt beides zurueck. Ohne Cam-Modus geht keins von beiden, mit
    enabled: false gar nicht, und die Tab-Vervollstaendigung bietet die Worte
    nur im Cam-Modus an.
    """
    def zeit(client):
        return client.call("sky").get("timeOfDay")

    def regen(client):
        return client.call("sky").get("rain") or 0

    def tab(text):
        return bot.call("tab", text=text).get("matches", [])

    if not FIND.test("Cam-Modus ist vor dem Test der eigenen Zeit aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    try:
        FIND.test("Voreingestellt stehen cam-time.enabled und cam-weather.enabled auf true da",
                  re.search(r"(?m)^cam-time:\s*\n\s*enabled:\s*true\s*$", ausgeliefert(env)) is not None
                  and re.search(r"(?m)^cam-weather:\s*\n\s*enabled:\s*true\s*$", ausgeliefert(env)) is not None, "")
        console(env, "weather clear", pause=0.5)
        console(env, "time set day", pause=1.5)
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer die eigene Zeit kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        console(env, f"tp {ZUSCHAUER_NAME} {x + 3} {y} {z}", pause=1.0)
        hinstellen(bot, x, y, z)

        zeilen = befehl(bot, "/cam time sunset", warten=2.0)
        FIND.test("/cam time ohne Cam-Modus geht nicht, die Zeit bleibt",
                  gesagt(zeilen, r"only works in cam mode") and zeit(bot) == 1000, f"{zeige(zeilen)} / {zeit(bot)}")
        jetzt = tab("/cam ")
        FIND.test("Ohne Cam-Modus schlaegt /cam weder time noch weather vor",
                  "time" not in jetzt and "weather" not in jetzt, str(jetzt))
        if not FIND.test("Cam-Modus fuer die eigene Zeit gestartet", cam_on(bot), ""):
            return
        jetzt = tab("/cam ")
        worte = tab("/cam time ")
        wetter = tab("/cam weather ")
        FIND.test("Im Cam-Modus schlaegt /cam time und weather vor, mit ihren Worten",
                  "time" in jetzt and "weather" in jetzt and "sunset" in worte and "reset" in worte
                  and sorted(wetter) == ["clear", "rain", "reset"], f"{jetzt} / {worte} / {wetter}")

        zeilen = befehl(bot, "/cam time sunset", warten=2.0)
        FIND.test("/cam time sunset - der Bot sieht 12000", gesagt(zeilen, r"Your own time: sunset")
                  and zeit(bot) == 12000, f"{zeige(zeilen)} / {zeit(bot)}")
        FIND.test("/cam time: der zweite Spieler sieht weiter die Zeit der Welt", zeit(zuschauer) == 1000,
                  str(zeit(zuschauer)))
        befehl(bot, "/cam time 18000", warten=2.0)
        FIND.test("/cam time 18000 - eine Zahl gilt in Ticks", zeit(bot) == 18000, str(zeit(bot)))
        zeilen = befehl(bot, "/cam time 24000", warten=2.0)
        FIND.test("/cam time 24000 - keine Tageszeit, sie bleibt", gesagt(zeilen, r"Use /cam time")
                  and zeit(bot) == 18000, f"{zeige(zeilen)} / {zeit(bot)}")
        zeilen = befehl(bot, "/cam time reset", warten=2.0)
        FIND.test("/cam time reset - wieder die Zeit der Welt", gesagt(zeilen, r"time of the world again")
                  and zeit(bot) == 1000, f"{zeige(zeilen)} / {zeit(bot)}")

        befehl(bot, "/cam time sunset")
        zeilen = befehl(bot, "/cam weather rain", warten=6.5)
        FIND.test("/cam weather rain - beim Bot regnet es voll", gesagt(zeilen, r"Your own weather: rain")
                  and regen(bot) > 0.99, f"{zeige(zeilen)} / {regen(bot)}")
        FIND.test("/cam weather: beim zweiten Spieler regnet es nicht", regen(zuschauer) == 0,
                  str(regen(zuschauer)))
        zeilen = befehl(bot, "/cam weather thunder")
        FIND.test("/cam weather thunder - ein Gewitter gibt es nicht", gesagt(zeilen, r"Use /cam weather"),
                  zeige(zeilen))
        cam_off(bot)
        time.sleep(2.0)
        FIND.test("Das Ende des Cam-Modus gibt Zeit und Wetter der Welt zurueck",
                  zeit(bot) == 1000 and regen(bot) == 0, f"{zeit(bot)} / {regen(bot)}")

        set_options(env, [("enabled", "false", "cam-time"), ("enabled", "false", "cam-weather")])
        cam_on(bot)
        zeit_aus = befehl(bot, "/cam time sunset", warten=2.0)
        wetter_aus = befehl(bot, "/cam weather rain")
        FIND.test("cam-time.enabled: false - /cam time ist abgeschaltet",
                  gesagt(zeit_aus, r"/cam time is switched off") and zeit(bot) == 1000, zeige(zeit_aus))
        FIND.test("cam-weather.enabled: false - /cam weather ist abgeschaltet",
                  gesagt(wetter_aus, r"/cam weather is switched off"), zeige(wetter_aus))
    finally:
        try:
            if zuschauer.proc is not None:
                zuschauer.stop()
            cam_off(bot)
            set_options(env, [("enabled", "true", "cam-time"), ("enabled", "true", "cam-weather")])
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der eigenen Zeit: {exc}")


def nachtsicht_wahl_checks(env, bot):
    """night-vision.player-choice: /cam nightvision, die Nachtsicht, die jeder
    Spieler fuer sich selbst einstellt - mit player-settings.storage: file in
    seiner Datei, mit memory, bis er den Server verlaesst.

    Voreingestellt ist es an - nachgesehen in der ausgelieferten Datei. mode
    bleibt dark: am hellen Tag keine Nachtsicht, um Mitternacht schon. on
    gibt sie auch am Tag, off nimmt sie auch um Mitternacht, /cam nightvision
    allein schaltet von off wieder an, reset gibt dark zurueck. Vor dem
    Cam-Modus gewaehlt, gilt es fuer den naechsten Flug und auch fuer den
    danach; voreingestellt (storage: file) auch nach dem Ab- und Anmelden, und
    es steht in der Datei des Bots unter settings. Mit storage: memory sagt
    die Meldung, dass es bis zum Verlassen gilt, und nach dem Ab- und Anmelden
    ist es vergessen. Mit player-choice: false sagt der Befehl, dass er aus
    ist, und eine Wahl von vorher gilt nicht mehr.
    """
    def hat():
        return nbt_frage(bot, "@s", NACHTSICHT)

    def zeit(wann):
        bot.chat(f"/time set {wann}")
        time.sleep(1.5)

    def tab(text):
        return bot.call("tab", text=text).get("matches", [])

    try:
        cam_off(bot)
        FIND.test("Voreingestellt steht night-vision.player-choice: true da",
                  re.search(r"(?m)^\s*player-choice:\s*true\s*$", ausgeliefert(env)) is not None, "")
        bot.chat("/effect clear @s")
        bot.chat("/weather clear")
        # Eine Wahl, die ein abgebrochener Lauf in der Datei liess.
        bot.chat("/cam nightvision reset")
        zeit("noon")
        tabs = tab("/cam nightvision ")
        FIND.test("Tab nach /cam nightvision: on, off, dark und reset",
                  sorted(tabs) == ["dark", "off", "on", "reset"] and "nightvision" in tab("/cam "), str(tabs))
        if not FIND.test("Cam-Modus fuer die eigene Nachtsicht gestartet", cam_on(bot), ""):
            return
        time.sleep(1.0)
        FIND.test("/cam nightvision: vorher am hellen Tag keine Nachtsicht (mode: dark)", not hat(), "")
        zeilen = befehl(bot, "/cam nightvision on")
        FIND.test("/cam nightvision on - auch am hellen Tag",
                  gesagt(zeilen, r"Night vision in cam mode: always") and hat(), zeige(zeilen))
        zeit("midnight")
        zeilen = befehl(bot, "/cam nightvision off")
        FIND.test("/cam nightvision off - auch um Mitternacht keine",
                  gesagt(zeilen, r"Night vision in cam mode: never") and not hat(), zeige(zeilen))
        zeilen = befehl(bot, "/cam nightvision")
        FIND.test("/cam nightvision allein schaltet von off wieder an",
                  gesagt(zeilen, r"Night vision in cam mode: always") and hat(), zeige(zeilen))
        zeilen = befehl(bot, "/cam nightvision reset")
        FIND.test("/cam nightvision reset - wieder dark: um Mitternacht mit",
                  gesagt(zeilen, r"only in the dark, as the server has it") and hat(), zeige(zeilen))
        zeit("noon")
        FIND.test("/cam nightvision reset - wieder dark: am Tag ohne", not hat(), "")
        zeilen = befehl(bot, "/cam nightvision bunt")
        FIND.test("/cam nightvision mit einem unbekannten Wort sagt, wie es geht",
                  gesagt(zeilen, r"Use /cam nightvision"), zeige(zeilen))

        cam_off(bot)
        zeilen = befehl(bot, "/cam nightvision on")
        cam_on(bot)
        time.sleep(1.0)
        FIND.test("Vor dem Cam-Modus gewaehlt, gilt es fuer den naechsten Flug",
                  gesagt(zeilen, r"Night vision in cam mode: always\. Kept for your next flights") and hat(),
                  zeige(zeilen))
        cam_off(bot)
        cam_on(bot)
        time.sleep(1.0)
        FIND.test("... und fuer den danach", hat(), "")
        cam_off(bot)
        if FIND.test("Neu angemeldet fuer die eigene Nachtsicht", neu_anmelden(bot), ""):
            cam_on(bot)
            time.sleep(1.0)
            FIND.test("player-settings.storage: file - nach dem Ab- und Anmelden gilt die Wahl weiter: am Tag mit",
                      hat(), "")
            cam_off(bot)
        datei = spielerdatei(env)
        text = datei.read_text(encoding="utf-8") if datei else ""
        FIND.test("storage: file - die Wahl steht in der Datei des Bots unter settings",
                  re.search(r"(?m)^settings:\s*\n(?:\s+.*\n)*?\s+night-vision:\s*'?on'?\s*$", text) is not None,
                  text[-300:] or "keine Datei")

        set_option(env, "storage", "memory", "player-settings")
        zeilen = befehl(bot, "/cam nightvision off")
        befehl(bot, "/cam nightvision on")
        FIND.test("storage: memory - die Meldung sagt, dass es bis zum Verlassen gilt",
                  gesagt(zeilen, r"Night vision in cam mode: never\. Holds until you leave the server"),
                  zeige(zeilen))
        if FIND.test("Neu angemeldet fuer die eigene Nachtsicht im Speicher", neu_anmelden(bot), ""):
            cam_on(bot)
            time.sleep(1.0)
            FIND.test("storage: memory - nach dem Ab- und Anmelden ist die Wahl vergessen: am Tag wieder ohne",
                      not hat(), "")
            cam_off(bot)
        set_option(env, "storage", "file", "player-settings")
        befehl(bot, "/cam nightvision reset")

        befehl(bot, "/cam nightvision on")
        set_option(env, "player-choice", "false")
        zeilen = befehl(bot, "/cam nightvision on")
        cam_on(bot)
        time.sleep(1.0)
        FIND.test("player-choice: false - /cam nightvision ist aus, und die Wahl von vorher gilt nicht",
                  gesagt(zeilen, r"/cam nightvision is switched off") and not hat(), zeige(zeilen))
        FIND.test("player-choice: false - /cam schlaegt nightvision nicht mehr vor", "nightvision" not in tab("/cam "),
                  "")
    finally:
        cam_off(bot)
        set_options(env, [("player-choice", "true"), ("storage", "file", "player-settings")])
        bot.chat("/cam nightvision reset")
        bot.chat("/time set noon")
        bot.chat("/effect clear @s")
        time.sleep(0.5)


def einstellungen_checks(env, bot):
    """player-settings: was jeder fuer sich im Cam-Modus waehlt, gilt auch fuer
    seine naechsten Fluege - in seiner Datei, oder bis er geht.

    Voreingestellt stehen storage: file, flight-speed: true, time: false und
    weather: false da - nachgesehen in der Datei, die das Plugin mitbringt:
    der Testserver schaltet flight-speed fuer alle anderen Abschnitte ab. Mit
    flight-speed: true beginnt der naechste Flug so schnell, wie der letzte
    aufhoerte, auch nach dem Ab- und Anmelden, und in der Datei des Bots steht
    es unter settings; mit false wieder mit flight-speed.start. Voreingestellt
    endet die eigene Zeit mit dem Cam-Modus; mit time: true und weather: true
    sagt die Meldung, dass sie bleiben, der naechste Flug beginnt mit dem
    Sonnenuntergang und dem Regen von vorher, und reset nimmt beides auch fuer
    den naechsten Flug zurueck.
    """
    def tempo():
        return bot.server_data("abilities.flySpeed")

    def gleich(wert, soll):
        return wert is not None and abs(wert - soll) < 0.001

    def drehen(hoch):
        bot.call("hotbar", slot=0)
        time.sleep(0.3)
        bot.call("hotbar", slot=8 if hoch else 1)
        time.sleep(0.8)

    def zeit():
        return bot.call("sky").get("timeOfDay")

    def regen():
        return bot.call("sky").get("rain") or 0

    quelle = (env.repo / "src" / "main" / "resources" / "config.yml").read_text(encoding="utf-8")
    FIND.test("Voreingestellt steht player-settings mit storage: file, flight-speed: true, time und weather: false da",
              re.search(r"(?m)^player-settings:\s*\n(?:\s*#.*\n)*\s*storage:\s*file\s*\n(?:\s*#.*\n)*"
                        r"\s*flight-speed:\s*true\s*\n(?:\s*#.*\n)*\s*time:\s*false\s*\n(?:\s*#.*\n)*"
                        r"\s*weather:\s*false\s*$", quelle) is not None, "")
    try:
        cam_off(bot)
        console(env, "weather clear", pause=0.5)
        console(env, "time set day", pause=1.0)
        bot.call("hotbar", slot=0)
        set_option(env, "flight-speed", "true", "player-settings")
        if not FIND.test("Cam-Modus fuer die eigenen Einstellungen gestartet", cam_on(bot), ""):
            return
        # Erst ganz hinunter, dann zwei Stufen hinauf: 1.5, was immer ein
        # frueherer Lauf in der Datei liess.
        for _ in range(8):
            drehen(False)
        drehen(True)
        drehen(True)
        FIND.test("Mausrad: 1.5", gleich(tempo(), 0.075), str(tempo()))
        cam_off(bot)
        cam_on(bot)
        FIND.test("flight-speed: true - der naechste Flug beginnt so schnell, wie der letzte aufhoerte",
                  gleich(tempo(), 0.075), str(tempo()))
        cam_off(bot)
        datei = spielerdatei(env)
        text = datei.read_text(encoding="utf-8") if datei else ""
        FIND.test("flight-speed: true - das Tempo steht in der Datei des Bots unter settings",
                  re.search(r"(?m)^\s+flight-speed:\s*1\.5\s*$", text) is not None, text[-300:] or "keine Datei")
        if FIND.test("Neu angemeldet fuer das eigene Tempo", neu_anmelden(bot), ""):
            cam_on(bot)
            FIND.test("flight-speed: true - auch nach dem Ab- und Anmelden", gleich(tempo(), 0.075), str(tempo()))
            cam_off(bot)
        set_option(env, "flight-speed", "false", "player-settings")
        cam_on(bot)
        FIND.test("flight-speed: false - jeder Flug beginnt wieder mit flight-speed.start", gleich(tempo(), 0.05),
                  str(tempo()))
        cam_off(bot)

        cam_on(bot)
        zeilen = befehl(bot, "/cam time sunset", warten=2.0)
        FIND.test("time: false - die Meldung sagt: bis der Cam-Modus endet",
                  gesagt(zeilen, r"Your own time: sunset\. Only you see it, until cam mode ends"), zeige(zeilen))
        cam_off(bot)
        cam_on(bot)
        time.sleep(1.5)
        FIND.test("time: false - der naechste Flug hat wieder die Zeit der Welt", zeit() == 1000, str(zeit()))
        cam_off(bot)

        set_options(env, [("time", "true", "player-settings"), ("weather", "true", "player-settings")])
        cam_on(bot)
        zeilen = befehl(bot, "/cam time sunset", warten=2.0)
        wetter = befehl(bot, "/cam weather rain", warten=2.0)
        FIND.test("time und weather: true - die Meldungen sagen, dass sie bleiben",
                  gesagt(zeilen, r"Your own time: sunset\. Only you see it, in every flight until you reset it")
                  and gesagt(wetter, r"Your own weather: rain\. Only you see it, in every flight until you reset it"),
                  f"{zeige(zeilen)} / {zeige(wetter)}")
        cam_off(bot)
        time.sleep(6.5)
        FIND.test("Das Ende des Cam-Modus gibt Zeit und Wetter der Welt trotzdem zurueck",
                  zeit() == 1000 and regen() == 0, f"{zeit()} / {regen()}")
        cam_on(bot)
        time.sleep(6.5)
        FIND.test("time und weather: true - der naechste Flug beginnt mit Sonnenuntergang und Regen",
                  zeit() == 12000 and regen() > 0.99, f"{zeit()} / {regen()}")
        befehl(bot, "/cam time reset")
        befehl(bot, "/cam weather reset")
        cam_off(bot)
        cam_on(bot)
        time.sleep(6.5)
        FIND.test("reset nimmt beides zurueck, auch fuer den naechsten Flug", zeit() == 1000 and regen() == 0,
                  f"{zeit()} / {regen()}")
        cam_off(bot)
    finally:
        try:
            cam_off(bot)
            set_options(env, [("flight-speed", "false", "player-settings"), ("time", "false", "player-settings"),
                              ("weather", "false", "player-settings")])
            bot.call("hotbar", slot=0)
            console(env, "weather clear", pause=0.5)
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der eigenen Einstellungen: {exc}")


def geteilte_datei(env):
    """Der Text von playerdata/shared-spots.yml des Testservers, oder ''."""
    datei = env.server / "plugins" / "CamFly" / "playerdata" / "shared-spots.yml"
    return datei.read_text(encoding="utf-8") if datei.exists() else ""


def im_cam_modus(bot, name):
    """Ob der Server diesen Spieler im Abenteuermodus fuehrt, dem des
    Cam-Modus - gefragt vom Bot, der dafuer op hat."""
    return server_says(bot, f"/execute if entity @a[name={name},gamemode=adventure] run say {{marke}}")


def teilen_checks(env, bot):
    """viewpoints.sharing und visitor-camera: Aussichtspunkte anbieten,
    oeffentlich machen, liken und die eines anderen besuchen.

    Voreingestellt sind beide an - nachgesehen in der ausgelieferten Datei.
    Der Bot setzt den Punkt "Haus" sechs Bloecke neben seinem Platz und bietet
    ihn dem zweiten Spieler an, der zweihundert Bloecke weiter steht: Der
    erfaehrt es mit Knoepfen, die /cam spot accept und decline
    CamFlyTester:Haus schicken, in playerdata/shared-spots.yml steht er unter
    pending, /cam spot list zeigt ihn unter "Offered to you", und besuchen
    kann er ihn noch nicht. Nimmt er ihn an, erfaehrt es der Bot, in der Datei
    steht er unter players, /cam spot list hat ihn unter "Shared with you",
    und die Tab-Vervollstaendigung bietet ihn nach /cam visit an. Noch einmal
    angeboten heisst es, dass er ihn schon hat. Springen wie zu einem eigenen
    Punkt geht von dort nicht, er ist mehr als max-distance vom eigenen
    Koerper weg.

    /cam visit setzt die Kamera an den Punkt - im Cam-Modus und ohne ihn,
    dann startet er erst -, die Action-Bar zeigt den Abstand zum Punkt, und
    mit max-distance: 4 haelt die Kamera am Punkt fest statt am Koerper. /cam
    back beendet den Besuch beim Koerper. Nimmt der Bot das Teilen zurueck,
    waehrend der zweite Spieler zu Besuch ist, ist dessen Kamera wieder beim
    Koerper, mit der Meldung, und der Punkt ist fuer ihn weg.

    Mit all macht der Bot ihn oeffentlich: Der andere sieht ihn ohne
    annehmen unter "Public spots of others", die Liste des Bots sagt
    "public". Der andere liked ihn, die Liste des Bots zaehlt das Like,
    noch einmal nimmt es zurueck; den eigenen liked keiner. Ein neues Angebot
    lehnt er ab - der Bot erfaehrt es, danach ist nichts mehr anzunehmen -,
    ein angenommenes nimmt er mit decline wieder von seiner Liste. Die
    Tab-Vervollstaendigung bietet nach share all und die Spieler an.
    Ein unbekannter Name und der eigene werden abgewiesen. Ein verbotenes
    Biom am Punkt sperrt den Besuch, und der Cam-Modus startet dafuer nicht.
    Mit visitor-camera.enabled: false ist /cam visit aus, mit
    viewpoints.sharing: false das Teilen. Ein geloeschter Punkt ist fuer
    niemanden mehr geteilt.
    """
    def tab(client, text):
        return client.call("tab", text=text).get("matches", [])

    def zuschauer_pos():
        return bot.call("server_pos", selector=ZUSCHAUER_NAME).get("pos")

    def nahe(a, b, weit=1.0):
        return a is not None and b is not None and math.dist(a, b) < weit

    if not FIND.test("Cam-Modus ist vor dem Test des Teilens aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    punkt = (x + 6, y, z)
    fern = (x + 200, y, z)
    label = f"{BOT_NAME}:Haus"
    biom = f"{_floor(punkt[0]) - 2} {y - 2} {_floor(z) - 2} {_floor(punkt[0]) + 2} {y + 4} {_floor(z) + 2}"
    console(env, f"fill {_floor(x) - 2} {y} {_floor(z) - 3} {_floor(punkt[0]) + 8} {y + 6} {_floor(z) + 3} "
                 f"minecraft:air", pause=0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    try:
        aus = ausgeliefert(env)
        FIND.test("Voreingestellt stehen viewpoints.sharing und visitor-camera.enabled auf true da",
                  re.search(r"(?m)^\s+sharing:\s*true\s*$", aus) is not None
                  and re.search(r"(?m)^visitor-camera:\s*\n\s*enabled:\s*true\s*$", aus) is not None, "")
        spots_loeschen(bot, "Haus")
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer das Teilen kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        cam_off(zuschauer)
        console(env, f"tp {ZUSCHAUER_NAME} {fern[0]} {y} {z}", pause=1.5)
        bot.chat(f"/tp {BOT_NAME} {punkt[0]} {y} {z} -90 0")
        time.sleep(1.0)
        befehl(bot, "/cam spot set Haus")
        hinstellen(bot, x, y, z)

        seit = zuschauer.mark()
        pakete_seit = zuschauer.call("chat_packets", since=10 ** 9).get("count", 0)
        zeilen = befehl(bot, f"/cam spot share Haus {ZUSCHAUER_NAME}")
        FIND.test("/cam spot share Haus <Spieler> - angeboten, noch nicht geteilt",
                  gesagt(zeilen, rf"Spot Haus is offered to {ZUSCHAUER_NAME} now"), zeige(zeilen))
        dort = zeilen_seit(zuschauer, seit)
        pakete = " ".join(p["text"] for p in zuschauer.call("chat_packets", since=pakete_seit).get("packets", []))
        FIND.test("Wer online ist, erfaehrt es - mit Knoepfen zum Annehmen und Ablehnen",
                  gesagt(dort, rf"{BOT_NAME} wants to share the spot Haus with you")
                  and f"/cam spot accept {label}" in pakete and f"/cam spot decline {label}" in pakete,
                  f"{zeige(dort)} / {pakete[-300:]}")
        text = geteilte_datei(env)
        FIND.test("storage: file - das Angebot steht in playerdata/shared-spots.yml unter pending",
                  re.search(r"name:\s*Haus", text) is not None and re.search(r"pending:", text) is not None
                  and ZUSCHAUER_NAME in text, text[-300:] or "keine Datei")
        dort = befehl(zuschauer, f"/cam visit {label}")
        FIND.test("Vor dem Annehmen ist der Punkt nicht seiner: kein Besuch",
                  gesagt(dort, rf"Nobody shares a spot called {label}"), zeige(dort))
        dort = befehl(zuschauer, "/cam spot list")
        FIND.test("/cam spot list beim anderen: der Punkt unter \"Offered to you\"",
                  gesagt(dort, r"Offered to you \(1\)") and gesagt(dort, rf"- {label}: ")
                  and not gesagt(dort, r"Shared with you"), zeige(dort))
        tabs = tab(zuschauer, "/cam spot accept ")
        FIND.test("Tab nach /cam spot accept: das Angebot", tabs == [label], str(tabs))
        seit = bot.mark()
        dort = befehl(zuschauer, f"/cam spot accept {label}")
        hier = zeilen_seit(bot, seit)
        FIND.test("/cam spot accept - angenommen, und der Bot erfaehrt es",
                  gesagt(dort, rf"You accepted {label}") and gesagt(hier, rf"{ZUSCHAUER_NAME} accepted your spot Haus"),
                  f"{zeige(dort)} / {zeige(hier)}")
        text = geteilte_datei(env)
        FIND.test("Angenommen steht er in playerdata/shared-spots.yml unter players, nicht mehr unter pending",
                  re.search(r"players:", text) is not None and re.search(r"pending:", text) is None,
                  text[-300:] or "keine Datei")
        dort = befehl(zuschauer, "/cam spot list")
        FIND.test("/cam spot list beim anderen: der Punkt unter \"Shared with you\"",
                  gesagt(dort, r"Shared with you \(1\)") and gesagt(dort, rf"- {label}: "), zeige(dort))
        tabs = tab(zuschauer, "/cam visit ")
        FIND.test("Tab nach /cam visit: der geteilte Punkt", tabs == [label], str(tabs))
        zeilen = befehl(bot, f"/cam spot share Haus {ZUSCHAUER_NAME}")
        FIND.test("Noch einmal angeboten - er hat ihn schon",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} has spot Haus already"), zeige(zeilen))

        if not FIND.test("Der zweite Spieler ist weit weg im Cam-Modus",
                         zuschauer_cam_an(env, zuschauer, fern[0], y, z), ""):
            return
        dort = befehl(zuschauer, f"/cam spot {label}")
        FIND.test("/cam spot Besitzer:Name springt nur wie zu einem eigenen Punkt: zu weit vom eigenen Koerper",
                  gesagt(dort, r"You are too far away")
                  and nahe(zuschauer_pos(), fern, 1.5), zeige(dort))
        dort = befehl(zuschauer, f"/cam visit {label}", warten=2.0)
        FIND.test("/cam visit im Cam-Modus - die Kamera steht am geteilten Punkt",
                  gesagt(dort, rf"Visiting {BOT_NAME}'s spot Haus") and nahe(zuschauer_pos(), punkt),
                  f"{zeige(dort)} / {zuschauer_pos()}")
        seit = zuschauer.mark()
        time.sleep(2.5)
        dort = zeilen_seit(zuschauer, seit)
        FIND.test("Beim Besuch zeigt die Action-Bar den Abstand zum Punkt", gesagt(dort, r"Spot: \d+ blocks"),
                  zeige(dort))

        # Jeder Reload beendet den Cam-Modus: der Besuch beginnt danach ohne ihn.
        set_option(env, "max-distance", "4.0")
        dort = befehl(zuschauer, f"/cam visit {label}", warten=3.0)
        FIND.test("/cam visit ohne Cam-Modus - er startet, und die Kamera steht am geteilten Punkt",
                  gesagt(dort, r"Cam mode activated") and nahe(zuschauer_pos(), punkt),
                  f"{zeige(dort)} / {zuschauer_pos()}")
        zuschauer.call("fly", wait=30, dx=10, timeout=6000)
        time.sleep(1.5)
        wo = zuschauer_pos()
        FIND.test("Beim Besuch haelt max-distance die Kamera am Punkt fest, nicht am Koerper",
                  wo is not None and math.dist(wo, punkt) <= 5.0, str(wo))
        dort = befehl(zuschauer, "/cam back", warten=2.0)
        FIND.test("/cam back beendet den Besuch: die Kamera ist beim Koerper",
                  gesagt(dort, r"Back at your body") and nahe(zuschauer_pos(), fern, 1.5),
                  f"{zeige(dort)} / {zuschauer_pos()}")
        set_option(env, "max-distance", "100.0")

        befehl(zuschauer, f"/cam visit {label}", warten=3.0)
        seit = zuschauer.mark()
        zeilen = befehl(bot, f"/cam spot unshare Haus {ZUSCHAUER_NAME}", warten=2.0)
        dort = zeilen_seit(zuschauer, seit)
        FIND.test("/cam spot unshare waehrend des Besuchs - seine Kamera ist wieder beim Koerper",
                  gesagt(zeilen, rf"no longer shared with {ZUSCHAUER_NAME}")
                  and gesagt(dort, rf"{BOT_NAME} no longer shares the spot Haus") and nahe(zuschauer_pos(), fern, 1.5),
                  f"{zeige(zeilen)} / {zeige(dort)} / {zuschauer_pos()}")
        dort = befehl(zuschauer, f"/cam visit {label}")
        FIND.test("Danach ist der Punkt fuer ihn weg", gesagt(dort, rf"Nobody shares a spot called {label}"),
                  zeige(dort))
        cam_off(zuschauer)

        zeilen = befehl(bot, "/cam spot share Haus all")
        dort = befehl(zuschauer, "/cam spot list")
        eigene = befehl(bot, "/cam spot list")
        FIND.test("/cam spot share Haus all - oeffentlich, er sieht ihn ohne annehmen",
                  gesagt(zeilen, r"Spot Haus is public now") and gesagt(dort, r"Public spots of others \(1\)")
                  and gesagt(dort, rf"- {label}: ") and gesagt(eigene, r"- Haus: .*\(public, 0 likes\)"),
                  f"{zeige(zeilen)} / {zeige(dort)} / {zeige(eigene)}")
        tabs = tab(zuschauer, "/cam spot like ")
        FIND.test("Tab nach /cam spot like: der oeffentliche Punkt", tabs == [label], str(tabs))
        dort = befehl(zuschauer, f"/cam spot like {label}")
        eigene = befehl(bot, "/cam spot list")
        FIND.test("/cam spot like - er liked ihn, und die Liste des Bots zaehlt es",
                  gesagt(dort, rf"You like {label} now\. It has 1 likes") and gesagt(eigene, r"\(public, 1 likes\)"),
                  f"{zeige(dort)} / {zeige(eigene)}")
        zeilen = befehl(bot, f"/cam spot like {label}")
        FIND.test("Den eigenen Punkt liked keiner", gesagt(zeilen, r"You cannot like your own spot"), zeige(zeilen))
        dort = befehl(zuschauer, f"/cam spot like {label}")
        FIND.test("Noch einmal like nimmt es zurueck", gesagt(dort, rf"You no longer like {label}\. It has 0 likes"),
                  zeige(dort))
        for name, eingabe, muster in (
                ("Ein unbekannter Name wird abgewiesen", "/cam spot share Haus Niemand123",
                 r"Nobody called Niemand123 has been on this server"),
                ("Mit sich selbst teilt keiner", f"/cam spot share Haus {BOT_NAME}", r"Your own spots are yours")):
            zeilen = befehl(bot, eingabe)
            FIND.test(name, gesagt(zeilen, muster), zeige(zeilen))
        tabs = tab(bot, "/cam spot share Haus ")
        FIND.test("Tab nach /cam spot share Haus: all und die Spieler",
                  "all" in tabs and ZUSCHAUER_NAME in tabs, str(tabs))
        tabs = tab(bot, "/cam spot unshare Haus ")
        FIND.test("Tab nach /cam spot unshare Haus: mit wem er geteilt ist", tabs == ["all"], str(tabs))

        seit = bot.mark()
        befehl(bot, f"/cam spot share Haus {ZUSCHAUER_NAME}")
        dort = befehl(zuschauer, f"/cam spot decline {label}")
        hier = zeilen_seit(bot, seit)
        FIND.test("/cam spot decline - abgelehnt, und der Bot erfaehrt es",
                  gesagt(dort, rf"You turned down {label}")
                  and gesagt(hier, rf"{ZUSCHAUER_NAME} turned down your spot Haus"), f"{zeige(dort)} / {zeige(hier)}")
        dort = befehl(zuschauer, f"/cam spot accept {label}")
        FIND.test("Danach ist nichts mehr anzunehmen", gesagt(dort, rf"Nobody offers you a spot called {label}"),
                  zeige(dort))
        befehl(bot, f"/cam spot share Haus {ZUSCHAUER_NAME}")
        befehl(zuschauer, f"/cam spot accept {label}")
        dort = befehl(zuschauer, f"/cam spot decline {label}")
        liste = befehl(zuschauer, "/cam spot list")
        FIND.test("decline bei einem angenommenen Punkt nimmt ihn von seiner Liste - oeffentlich bleibt er",
                  gesagt(dort, rf"Spot {label} is no longer among the spots shared with you")
                  and not gesagt(liste, r"Shared with you") and gesagt(liste, r"Public spots of others \(1\)"),
                  f"{zeige(dort)} / {zeige(liste)}")

        bot.chat(f"/fillbiome {biom} minecraft:lush_caves")
        time.sleep(1.5)
        dort = befehl(zuschauer, f"/cam visit {label}", warten=2.0)
        FIND.test("Ein verbotenes Biom am Punkt sperrt den Besuch, und der Cam-Modus startet dafuer nicht",
                  gesagt(dort, rf"Spot {label} lies in Lush Caves") and not im_cam_modus(bot, ZUSCHAUER_NAME),
                  zeige(dort))
        bot.chat(f"/fillbiome {biom} minecraft:plains")
        time.sleep(1.5)

        set_option(env, "enabled", "false", "visitor-camera")
        dort = befehl(zuschauer, f"/cam visit {label}")
        FIND.test("visitor-camera.enabled: false - /cam visit ist aus", gesagt(dort, r"/cam visit is switched off"),
                  zeige(dort))
        set_options(env, [("enabled", "true", "visitor-camera"), ("sharing", "false", "viewpoints")])
        zeilen = befehl(bot, f"/cam spot share Haus {ZUSCHAUER_NAME}")
        dort = befehl(zuschauer, "/cam spot list")
        FIND.test("viewpoints.sharing: false - kein Teilen, und geteilte Punkte sind nicht zu sehen",
                  gesagt(zeilen, r"Sharing spots is switched off") and not gesagt(dort, r"Shared with you"),
                  f"{zeige(zeilen)} / {zeige(dort)}")
        set_option(env, "sharing", "true", "viewpoints")

        befehl(bot, "/cam spot delete Haus")
        dort = befehl(zuschauer, f"/cam visit {label}")
        FIND.test("Ein geloeschter Punkt ist fuer niemanden mehr geteilt",
                  gesagt(dort, rf"Nobody shares a spot called {label}")
                  and re.search(r"name:\s*Haus", geteilte_datei(env)) is None, zeige(dort))
    finally:
        try:
            if zuschauer.proc is not None:
                cam_off(zuschauer)
                zuschauer.stop()
            cam_off(bot)
            set_options(env, [("max-distance", "100.0"), ("enabled", "true", "visitor-camera"),
                              ("sharing", "true", "viewpoints")])
            spots_loeschen(bot, "Haus")
            bot.chat(f"/fillbiome {biom} minecraft:plains")
            time.sleep(1.0)
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test des Teilens: {exc}")


def flugtempo(env, name):
    """Das Flugtempo eines Spielers, wie der Server es fuehrt - ueber die
    Konsole gefragt, die jeden fragen darf."""
    vorher = len(server_log(env))
    console(env, f"data get entity {name} abilities.flySpeed", pause=1.0)
    # Die Konsole faerbt die Zahl mit ANSI-Folgen ein.
    text = re.sub(r"\x1b\[[0-9;]*m", "", strip_colors(server_log(env)[vorher:]))
    treffer = re.search(rf"{name} has the following entity data: (-?[\d.]+)f?", text)
    return float(treffer.group(1)) if treffer else None


def fuehrung_checks(env, bot):
    """tour: /cam tour - einer zeigt seinen Bau, und die Kamera eines
    anderen fliegt mit seiner mit.

    Voreingestellt ist es an, mit radius 16 und out-of-reach-seconds 5 -
    nachgesehen in der Datei, die das Plugin mitbringt; der Test stellt
    max-distance auf 20, out-of-reach-seconds auf 8 und schaltet die
    Partikel der Kameras an. Im Cam-Modus 30 Bloecke weg, und ohne ihn
    ebenso (start-distance 16), wird er nicht eingeladen, und die
    Tab-Vervollstaendigung bietet ihn nicht an. Ohne Cam-Modus sechs Bloecke
    hinter dem Fuehrer bietet sie ihn an, die Einladung kommt mit Knoepfen
    und dem Ton von sounds.tour-invite. Steht er beim Annehmen 30 Bloecke
    weg, heisst es "You are X blocks too far away", und nichts startet; nah
    dran startet sein Cam-Modus dort, wo er steht, seine Kamera steht beim
    Fuehrer, der es erfaehrt, und sein Flugtempo ist 0. Fliegt
    der Fuehrer acht Bloecke, fliegt der Gast mit; im Menue sagt der Knopf
    der Fuehrung, wem er folgt.

    Fliegt der Fuehrer weiter, als die Kamera des Gastes von ihrem Koerper
    aus darf, lesen beide in der Action-Bar, dass die Fuehrung endet, mit dem
    Ton von sounds.tour-reach. Kommt er rechtzeitig zurueck, fliegt der Gast
    wieder mit. Mit tour.guide-view (voreingestellt) sieht der Gast durch die
    Augen des Fuehrers - der Bot bekommt das Paket camera mit dessen Nummer,
    und der Start meldet nichts dazu -, ausser Reichweite und nach dem
    Verlassen wieder aus seiner eigenen Kamera; der Knopf im Menue und
    /cam tour view schalten zwischen beidem um. Auf der Fuehrung bekommt
    keiner Namen und Partikel des anderen, der Fuehrer sieht den Gast nicht,
    der Gast den Fuehrer nur mit dessen Blick (er braucht ihn als Kamera);
    nach der Fuehrung sehen sich beide wieder. Bleibt er weg, ist die
    Fuehrung fuer den Gast vorbei, beide
    erfahren es, und das Flugtempo des Gastes ist wieder seins. Einladen
    geht dann nicht, solange der Fuehrer ausser seiner Reichweite ist.

    /cam tour leave, /cam back, kick und das Ende des Cam-Modus des Fuehrers
    beenden die Fuehrung fuer den Gast, jedes mit seiner Meldung; ablehnen
    sagt es dem Fuehrer. Im Menue steht der Gast auf der Seite der Fuehrung,
    ein Klick laedt ihn ein, bei ihm steht die Einladung, und ein Linksklick
    nimmt sie an. Endet der Cam-Modus des Gastes ohne /cam - ein Treffer auf
    seinen Koerper -, ist er von der Fuehrung und sieht wieder aus seiner
    eigenen Kamera. Mit tour.enabled: false ist /cam tour aus.

    Ist niemand nah genug, sagt die Seite der Fuehrung, wie nah: ohne
    Cam-Modus start-distance, im Cam-Modus radius. Auf der Fuehrung bleiben
    beide in der Tab-Liste des anderen (auf Paper meldet der Start nichts
    dazu), ueber der Kamera des Fuehrers stehen die Namen gestapelt - der
    Gast mit (Follower) ueber dem Fuehrer mit (Guide) -, und der Gast kann
    seinen eigenen Koerper weder anklicken noch schlagen: der Cam-Modus bleibt
    an, mit tour-body-locked. Sein Client bekommt seinen Frost immer wieder
    geschickt, und jeder am Ende des Cam-Modus: was ein Client in Pulverschnee
    selbst hochzaehlt, wo der Server ihn nicht hatte, bleibt sonst bis zum
    naechsten Beitritt auf dem Bildschirm. Mit max-guests: 1 kommt ein dritter Spieler
    nicht mehr mit, der Fuehrer kann ihn nicht mehr einladen, und der Knopf
    der Fuehrung sagt "Guests: 1/1". Der dritte sieht von aussen nur den
    Fuehrer - der Gast, der ihm folgt, steht nur in der Tab-Liste -, nach
    der Fuehrung wieder beide.
    """
    def zuschauer_pos():
        return bot.call("server_pos", selector=ZUSCHAUER_NAME).get("pos")

    def nahe(a, b, weit=1.0):
        return a is not None and b is not None and math.dist(a, b) < weit

    def tab(client, text):
        return client.call("tab", text=text).get("matches", [])

    def fenster(client):
        return client.call("window_slots", wait=10)

    def klick(client, slot, button=0, mode=0, warten=1.5):
        client.call("click_window", wait=15, slot=slot, button=button, mode=mode)
        time.sleep(warten)

    def kamera_marke():
        return zuschauer.call("cameras").get("count", 0)

    def kameras_seit(marke):
        """Durch wen der Server den Gast seit der Marke sehen liess: 'fuehrer',
        'selbst' oder die Nummer, der Reihe nach."""
        antwort = zuschauer.call("cameras", since=marke)
        # Die Nummer einer Entitaet vergibt der Server fuer alle gleich: der
        # Fuehrer kennt seine eigene.
        fuehrer = bot.call("cameras").get("self")
        return ["fuehrer" if p.get("id") == fuehrer else "selbst" if p.get("id") == antwort.get("self")
                else p.get("id") for p in antwort.get("packets", [])]

    def kennt(client, wen, radius=24):
        """Ob der Client die Entitaet mit dieser Nummer kennt, also sieht."""
        return any(e.get("id") == wen for e in client.call("entities", radius=radius).get("entities", []))

    def namen_nah(client):
        """Die Text-Displays nah an der Kamera - die Namen anderer Kameras."""
        return [e for e in client.call("entities", radius=3).get("entities", []) if "text_display" in (e.get("type") or "")]

    def in_tab(client, wer):
        return wer in client.call("players").get("players", [])

    def name_text(wer):
        """Was der Name ueber dieser Kamera sagt, am Server gelesen: das
        TextDisplay, das ihrer Stelle am naechsten ist - die eines Gastes
        steht nur fast dort."""
        since = bot.mark()
        bot.chat(f"/execute at {wer} positioned ~ ~{NAME_UEBER_SPIELER} ~ run data get entity "
                 f"@e[type=minecraft:text_display,distance=..0.3,limit=1,sort=nearest] text")
        hit = bot.expect("entity data", since, 4000)
        return strip_colors(hit["text"]) if hit else ""

    def einladen_und_annehmen():
        befehl(bot, f"/cam tour invite {ZUSCHAUER_NAME}")
        dort = befehl(zuschauer, f"/cam tour accept {BOT_NAME}", warten=2.0)
        return gesagt(dort, rf"You follow {BOT_NAME} now")

    if not FIND.test("Cam-Modus ist vor dem Test der Fuehrung aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    # Der Koerper des Gastes steht sechs Bloecke hinter dem des Fuehrers: So
    # kommt der Fuehrer aus der Reichweite des Gastes heraus und bleibt in
    # seiner eigenen - max-distance haelt auch ihn.
    gast = (x - 6, y, z)
    console(env, f"fill {_floor(x) - 10} {y} {_floor(z) - 4} {_floor(x) + 40} {y + 8} {_floor(z) + 4} "
                 f"minecraft:air", pause=0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    dritter = BotClient(env, name=DRITTER_NAME)
    try:
        FIND.test("Voreingestellt steht tour mit enabled: true, radius: 16, start-distance: 16, "
                  "out-of-reach-seconds: 5 und max-guests: 5 da",
                  re.search(r"(?m)^tour:\s*\n\s*enabled:\s*true\s*\n(?:\s*#.*\n)*\s*radius:\s*16\.0\s*\n"
                            r"(?:\s*#.*\n)*\s*start-distance:\s*16\s*\n"
                            r"(?:\s*#.*\n)*\s*out-of-reach-seconds:\s*5\s*\n"
                            r"(?:\s*#.*\n)*\s*max-guests:\s*5\s*$", quelle_config(env)) is not None, "")
        # Die Partikel der Kameras, die der Testserver sonst abschaltet.
        set_options(env, [("max-distance", "20.0"), ("out-of-reach-seconds", "8", "tour"),
                          ("particles-per-tick", "1", "camera-particles")])
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer die Fuehrung kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        cam_off(zuschauer)
        hinstellen(bot, x, y, z)
        cam_on(bot)
        if not FIND.test("Der zweite Spieler ist 30 Bloecke weit weg im Cam-Modus",
                         zuschauer_cam_an(env, zuschauer, x + 30, y, z), ""):
            return
        zeilen = befehl(bot, f"/cam tour invite {ZUSCHAUER_NAME}")
        tabs = tab(bot, "/cam tour invite ")
        FIND.test("tour.radius: 16 - wer im Cam-Modus weiter weg ist, wird nicht eingeladen und nicht angeboten",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is too far away") and ZUSCHAUER_NAME not in tabs,
                  f"{zeige(zeilen)} / {tabs}")
        befehl(bot, "/cam menu", warten=1.0)
        klick(bot, 9)
        leer = item_text(fenster(bot).get("slots", {}).get("22"))
        FIND.test("Menue: niemand nah genug - die Seite sagt, wie nah: ohne Cam-Modus start-distance, im Cam-Modus "
                  "radius",
                  "Nobody to invite" in leer and "- without cam mode: within 16 blocks" in leer
                  and "- in cam mode: within 16 blocks" in leer, leer)
        bot.call("close_window", wait=10)
        cam_off(zuschauer)
        zeilen = befehl(bot, f"/cam tour invite {ZUSCHAUER_NAME}")
        FIND.test("tour.start-distance: 16 - ohne Cam-Modus 30 Bloecke weg wird er auch nicht eingeladen",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is too far away"), zeige(zeilen))
        console(env, f"tp {ZUSCHAUER_NAME} {gast[0]} {y} {z}", pause=1.0)
        tabs = tab(bot, "/cam tour invite ")
        FIND.test("Tab nach /cam tour invite: der Spieler daneben, auch ohne Cam-Modus", tabs == [ZUSCHAUER_NAME],
                  str(tabs))
        # Wie ein echter Client im Flug: die Geschwindigkeit des Servers, ohne Schwerkraft.
        zuschauer.call("velocity", on=True)
        seit = zuschauer.mark()
        ton = toene_marke(zuschauer)
        pakete_seit = zuschauer.call("chat_packets", since=10 ** 9).get("count", 0)
        zeilen = befehl(bot, f"/cam tour invite {ZUSCHAUER_NAME}")
        dort = zeilen_seit(zuschauer, seit)
        pakete = " ".join(p["text"] for p in zuschauer.call("chat_packets", since=pakete_seit).get("packets", []))
        FIND.test("Die Einladung kommt an - mit Knoepfen zum Annehmen und Ablehnen",
                  gesagt(zeilen, rf"You invited {ZUSCHAUER_NAME} to your tour")
                  and gesagt(dort, rf"{BOT_NAME} invites you to a tour")
                  and f"/cam tour accept {BOT_NAME}" in pakete and f"/cam tour decline {BOT_NAME}" in pakete,
                  f"{zeige(zeilen)} / {zeige(dort)} / {pakete[-300:]}")
        FIND.test("sounds.tour-invite - der Eingeladene hoert den Ton", ton_da(zuschauer, ton, "block.note_block.bell"),
                  "")
        console(env, f"tp {ZUSCHAUER_NAME} {x + 30} {y} {z}", pause=1.0)
        dort = befehl(zuschauer, f"/cam tour accept {BOT_NAME}", warten=2.0)
        FIND.test("Ohne Cam-Modus zu weit weg: \"You are X blocks too far away\", und nichts startet",
                  gesagt(dort, r"You are \d+ blocks too far away") and not im_cam_modus(bot, ZUSCHAUER_NAME),
                  zeige(dort))
        console(env, f"tp {ZUSCHAUER_NAME} {gast[0]} {y} {z}", pause=1.0)
        seit = bot.mark()
        kamera = kamera_marke()
        dort = befehl(zuschauer, f"/cam tour accept {BOT_NAME}", warten=3.0)
        hier = zeilen_seit(bot, seit)
        FIND.test("Ohne Cam-Modus nah dran: annehmen startet den Cam-Modus dort, wo er steht",
                  gesagt(dort, r"Cam mode activated") and im_cam_modus(bot, ZUSCHAUER_NAME), zeige(dort))
        FIND.test("tour.guide-view: der Gast sieht durch die Augen des Fuehrers (Paket camera mit dessen Nummer)",
                  "fuehrer" in kameras_seit(kamera), str(kameras_seit(kamera)))
        FIND.test("Auf Paper meldet der Start nichts zum Blick des Fuehrers",
                  "guide's view of /cam tour does not work" not in server_log(env), "")
        FIND.test("/cam tour accept - seine Kamera steht beim Fuehrer, und der erfaehrt es",
                  gesagt(dort, rf"You follow {BOT_NAME} now") and gesagt(hier, rf"{ZUSCHAUER_NAME} follows you now")
                  and nahe(zuschauer_pos(), bot.server_pos(), 1.0),
                  f"{zeige(dort)} / {zeige(hier)} / {zuschauer_pos()} / {bot.server_pos()}")
        tempo = flugtempo(env, ZUSCHAUER_NAME)
        FIND.test("Auf der Fuehrung bewegen die eigenen Tasten die Kamera nicht: Flugtempo 0",
                  tempo is not None and abs(tempo) < 1e-6, str(tempo))
        frost = zuschauer.call("frosts").get("count", 0)
        time.sleep(1.0)
        werte = zuschauer.call("frosts", since=frost).get("values", [])
        FIND.test("Auf der Fuehrung bekommt der Client des Gastes seinen Frost immer wieder geschickt (0) - was er "
                  "selbst hochzaehlt, bleibt nicht stehen",
                  len(werte) >= 2 and all(w == 0 for w in werte), str(werte))
        FIND.test("Auf der Fuehrung bleiben beide in der Tab-Liste des anderen",
                  in_tab(bot, ZUSCHAUER_NAME) and in_tab(zuschauer, BOT_NAME),
                  f"{bot.call('players').get('players')} / {zuschauer.call('players').get('players')}")
        FIND.test("Auf Paper meldet der Start nichts zur Tab-Liste der Fuehrung",
                  "cannot keep the players it hides" not in server_log(env), "")
        text = name_text(BOT_NAME)
        FIND.test("Ueber der Kamera des Fuehrers stehen die Namen gestapelt: der Gast mit (Follower) ueber dem "
                  "Fuehrer mit (Guide)",
                  "(Follower)" in text and "(Guide)" in text and ZUSCHAUER_NAME in text
                  and text.index(ZUSCHAUER_NAME) < text.index("(Follower)") < text.index(BOT_NAME)
                  < text.index("(Guide)"), text)
        # Der Koerper des Gastes steht sechs Bloecke hinter dem Fuehrer: Der
        # fliegt hin, die Kamera des Gastes mit.
        bot.call("fly", wait=30, dx=-5, timeout=15000)
        time.sleep(1.5)
        seit = zuschauer.mark()
        zuschauer.call("activate_entity", wait=20, at=list(gast), radius=2)
        time.sleep(1.0)
        zuschauer.call("attack_entity", wait=20, at=list(gast), radius=2)
        time.sleep(1.0)
        dort = zeilen_seit(zuschauer, seit)
        gesperrt = sum(1 for z in dort if re.search(rf"You follow the tour of {BOT_NAME}: leave it first", z))
        FIND.test("Wer folgt, kann seinen eigenen Koerper weder anklicken noch schlagen: der Cam-Modus bleibt an",
                  im_cam_modus(bot, ZUSCHAUER_NAME) and gesperrt == 2, f"{gesperrt} / {zeige(dort)}")
        bot.call("fly", wait=30, dx=5, timeout=15000)
        time.sleep(1.5)
        bot.call("fly", wait=30, dx=8, dy=2, timeout=15000)
        time.sleep(2.0)
        FIND.test("Der Fuehrer fliegt acht Bloecke - die Kamera des Gastes fliegt mit",
                  nahe(zuschauer_pos(), bot.server_pos(), 2.5), f"{zuschauer_pos()} / {bot.server_pos()}")
        fuehrer_nr = bot.call("cameras").get("self")
        gast_nr = zuschauer.call("cameras").get("self")
        marke_bot, marke_gast = partikel_marke(bot), partikel_marke(zuschauer)
        time.sleep(2.0)
        FIND.test("Auf der Fuehrung bekommt keiner die Partikel des anderen",
                  not partikel_seit(bot, marke_bot) and not partikel_seit(zuschauer, marke_gast),
                  f"{len(partikel_seit(bot, marke_bot))} / {len(partikel_seit(zuschauer, marke_gast))}")
        FIND.test("Auf der Fuehrung sieht keiner den Namen des anderen",
                  not namen_nah(bot) and not namen_nah(zuschauer), f"{namen_nah(bot)} / {namen_nah(zuschauer)}")
        FIND.test("Der Fuehrer sieht den Gast nicht; der Gast mit dem Blick des Fuehrers kennt ihn (seine Kamera)",
                  not kennt(bot, gast_nr) and kennt(zuschauer, fuehrer_nr), f"{gast_nr} / {fuehrer_nr}")
        befehl(zuschauer, "/cam menu", warten=1.0)
        knopf = fenster(zuschauer).get("slots", {})
        FIND.test("Im Menue sagt der Knopf der Fuehrung auf Platz 9, wem er folgt",
                  f"You follow {BOT_NAME}" in item_text(knopf.get("9")), item_text(knopf.get("9")))
        klick(zuschauer, 9)
        knopf = fenster(zuschauer).get("slots", {})
        FIND.test("Auf der Seite der Fuehrung: der Knopf des Blicks, er steht auf dem des Fuehrers",
                  "View on tours" in item_text(knopf.get("13")) and "Now: the guide's" in item_text(knopf.get("13")),
                  item_text(knopf.get("13")))
        kamera = kamera_marke()
        seit = zuschauer.mark()
        klick(zuschauer, 13)
        dort = zeilen_seit(zuschauer, seit)
        knopf = fenster(zuschauer).get("slots", {})
        FIND.test("Ein Klick schaltet auf freies Umsehen: die eigene Kamera wieder",
                  gesagt(dort, r"On tours you look round freely now") and kameras_seit(kamera)[-1:] == ["selbst"]
                  and "Now: free" in item_text(knopf.get("13")), f"{zeige(dort)} / {kameras_seit(kamera)}")
        zuschauer.call("close_window", wait=10)
        time.sleep(1.0)
        FIND.test("Beim freien Umsehen sieht auch der Gast den Fuehrer nicht - in der Tab-Liste steht er",
                  not kennt(zuschauer, fuehrer_nr) and in_tab(zuschauer, BOT_NAME), "")
        kamera = kamera_marke()
        dort = befehl(zuschauer, "/cam tour view", warten=2.0)
        FIND.test("/cam tour view schaltet zurueck auf den Blick des Fuehrers",
                  gesagt(dort, r"On tours you see through the eyes of the guide")
                  and "fuehrer" in kameras_seit(kamera), f"{zeige(dort)} / {kameras_seit(kamera)}")

        # Der Fuehrer ist bei x + 8, max-distance ist 20: Bei x + 16 ist er 22
        # Bloecke vom Koerper des Gastes weg und 16 von seinem eigenen.
        seit, seit_bot = zuschauer.mark(), bot.mark()
        ton = toene_marke(zuschauer)
        kamera = kamera_marke()
        bot.call("fly", wait=30, dx=8, timeout=15000)
        time.sleep(1.0)
        dort, hier = zeilen_seit(zuschauer, seit), zeilen_seit(bot, seit_bot)
        FIND.test("Ausser Reichweite: beide lesen in der Action-Bar, dass die Fuehrung endet",
                  gesagt(dort, rf"{BOT_NAME} is out of your reach: the tour ends in")
                  and gesagt(hier, rf"{ZUSCHAUER_NAME} cannot follow: the tour ends for them in"),
                  f"{zeige(dort)} / {zeige(hier)}")
        FIND.test("sounds.tour-reach - der Gast hoert den Ton", ton_da(zuschauer, ton, "block.conduit.deactivate"), "")
        FIND.test("Ausser Reichweite sieht der Gast wieder aus seiner eigenen Kamera",
                  kameras_seit(kamera)[-1:] == ["selbst"], str(kameras_seit(kamera)))
        wo = zuschauer_pos()
        FIND.test("Die Kamera des Gastes bleibt in ihrer eigenen Reichweite",
                  wo is not None and math.dist(wo, gast) <= 21.0, str(wo))
        kamera = kamera_marke()
        bot.call("fly", wait=30, dx=-6, timeout=15000)
        time.sleep(2.0)
        dort = zeilen_seit(zuschauer, seit)
        FIND.test("Wieder in Reichweite: wieder der Blick des Fuehrers", "fuehrer" in kameras_seit(kamera),
                  str(kameras_seit(kamera)))
        FIND.test("Rechtzeitig zurueck: der Gast fliegt wieder mit, die Fuehrung geht weiter",
                  nahe(zuschauer_pos(), bot.server_pos(), 2.5) and not gesagt(dort, r"for too long"),
                  f"{zuschauer_pos()} / {bot.server_pos()} / {zeige(dort)}")

        seit, seit_bot = zuschauer.mark(), bot.mark()
        bot.call("fly", wait=30, dx=8, timeout=15000)
        time.sleep(10.0)
        dort, hier = zeilen_seit(zuschauer, seit), zeilen_seit(bot, seit_bot)
        FIND.test("Zu lange ausser Reichweite: die Fuehrung ist fuer den Gast vorbei, beide erfahren es",
                  gesagt(dort, rf"{BOT_NAME} was out of your reach for too long")
                  and gesagt(hier, rf"{ZUSCHAUER_NAME} could not follow you for too long"),
                  f"{zeige(dort)} / {zeige(hier)}")
        tempo = flugtempo(env, ZUSCHAUER_NAME)
        FIND.test("Danach fliegt der Gast wieder mit seinem eigenen Tempo",
                  tempo is not None and tempo > 0.0, str(tempo))
        zeilen = befehl(bot, f"/cam tour invite {ZUSCHAUER_NAME}")
        FIND.test("Einladen geht nicht, solange der Fuehrer ausser seiner Reichweite ist",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} could not follow you from there"), zeige(zeilen))

        bot.call("fly", wait=30, dx=-8, timeout=15000)
        time.sleep(1.0)
        FIND.test("Wieder in Reichweite: einladen und annehmen", einladen_und_annehmen(), "")
        seit = bot.mark()
        time.sleep(1.0)
        kamera = kamera_marke()
        dort = befehl(zuschauer, "/cam tour leave")
        hier = zeilen_seit(bot, seit)
        FIND.test("/cam tour leave - er verlaesst die Fuehrung, der Fuehrer erfaehrt es, und er sieht aus seiner "
                  "eigenen Kamera",
                  gesagt(dort, rf"You left the tour of {BOT_NAME}") and gesagt(hier, rf"{ZUSCHAUER_NAME} left your tour")
                  and kameras_seit(kamera)[-1:] == ["selbst"], f"{zeige(dort)} / {zeige(hier)} / {kameras_seit(kamera)}")
        marke_bot = partikel_marke(bot)
        time.sleep(2.0)
        FIND.test("Nach der Fuehrung sehen sich beide wieder, mit Partikeln",
                  kennt(bot, gast_nr) and kennt(zuschauer, fuehrer_nr) and len(partikel_seit(bot, marke_bot)) > 0,
                  f"{kennt(bot, gast_nr)} / {kennt(zuschauer, fuehrer_nr)} / {len(partikel_seit(bot, marke_bot))}")
        einladen_und_annehmen()
        seit = bot.mark()
        dort = befehl(zuschauer, "/cam back", warten=2.0)
        hier = zeilen_seit(bot, seit)
        FIND.test("/cam back - zurueck am Koerper und nicht mehr auf der Fuehrung",
                  gesagt(dort, rf"You left the tour of {BOT_NAME}") and gesagt(hier, rf"{ZUSCHAUER_NAME} left your tour")
                  and nahe(zuschauer_pos(), gast, 1.5), f"{zeige(dort)} / {zuschauer_pos()}")
        # Wieder nah an den Gast heran, der jetzt an seinem Koerper ist.
        bot.call("fly", wait=30, dx=-8, timeout=15000)
        time.sleep(1.0)
        einladen_und_annehmen()
        seit = zuschauer.mark()
        zeilen = befehl(bot, f"/cam tour kick {ZUSCHAUER_NAME}")
        dort = zeilen_seit(zuschauer, seit)
        FIND.test("/cam tour kick - der Gast ist weggeschickt",
                  gesagt(zeilen, rf"{ZUSCHAUER_NAME} is off your tour") and gesagt(dort, rf"{BOT_NAME} took you off"),
                  f"{zeige(zeilen)} / {zeige(dort)}")
        einladen_und_annehmen()
        seit = zuschauer.mark()
        frost = bot.call("frosts").get("count", 0)
        cam_off(bot)
        dort = zeilen_seit(zuschauer, seit)
        FIND.test("Endet der Cam-Modus des Fuehrers, endet die Fuehrung", gesagt(dort, rf"{BOT_NAME} ended the tour"),
                  zeige(dort))
        FIND.test("Am Ende des Cam-Modus bekommt der Client seinen Frost geschickt, auch wenn er gleich bleibt (0)",
                  0 in bot.call("frosts", since=frost).get("values", []),
                  str(bot.call("frosts", since=frost).get("values")))
        hinstellen(bot, x + 8, y, z)
        cam_on(bot)
        befehl(bot, f"/cam tour invite {ZUSCHAUER_NAME}")
        seit = bot.mark()
        dort = befehl(zuschauer, f"/cam tour decline {BOT_NAME}")
        hier = zeilen_seit(bot, seit)
        FIND.test("/cam tour decline - abgelehnt, der Fuehrer erfaehrt es",
                  gesagt(dort, rf"You turned down the tour of {BOT_NAME}")
                  and gesagt(hier, rf"{ZUSCHAUER_NAME} turned your tour down"), f"{zeige(dort)} / {zeige(hier)}")

        befehl(bot, "/cam menu", warten=1.0)
        klick(bot, 9)
        f = fenster(bot)
        FIND.test("Menue: auf der Seite der Fuehrung steht der Spieler daneben, zum Einladen, mit seinem Abstand",
                  "Tour" in (f.get("title") or "") and ZUSCHAUER_NAME in item_text(f.get("slots", {}).get("0"))
                  and "Click: invite" in item_text(f.get("slots", {}).get("0"))
                  and re.search(r"In cam mode, \d+ blocks away", item_text(f.get("slots", {}).get("0"))),
                  item_text(f.get("slots", {}).get("0")))
        klick(bot, 0)
        bot.call("close_window", wait=10)
        befehl(zuschauer, "/cam menu", warten=1.0)
        klick(zuschauer, 9)
        f = fenster(zuschauer)
        FIND.test("Menue: beim Eingeladenen steht die Einladung",
                  BOT_NAME in item_text(f.get("slots", {}).get("0"))
                  and "Invites you to a tour" in item_text(f.get("slots", {}).get("0")),
                  item_text(f.get("slots", {}).get("0")))
        klick(zuschauer, 0, warten=2.0)
        FIND.test("Menue: ein Linksklick nimmt die Einladung an", nahe(zuschauer_pos(), bot.server_pos(), 1.0),
                  f"{zuschauer_pos()} / {bot.server_pos()}")
        zuschauer.call("close_window", wait=10)
        time.sleep(1.0)
        # Ein Ende ohne /cam: ein Treffer auf den Koerper des Gastes, der
        # noch bei gast steht.
        kamera = kamera_marke()
        seit = bot.mark()
        console(env, f"damage @e[type=minecraft:mannequin,x={gast[0]},y={y},z={z},distance=..1,limit=1] 1 "
                     f"minecraft:cactus", pause=2.0)
        hier = zeilen_seit(bot, seit)
        FIND.test("Endet der Cam-Modus des Gastes ohne /cam (ein Treffer auf seinen Koerper): er ist von der "
                  "Fuehrung und sieht wieder aus seiner eigenen Kamera",
                  not im_cam_modus(bot, ZUSCHAUER_NAME) and gesagt(hier, rf"{ZUSCHAUER_NAME} left your tour")
                  and kameras_seit(kamera)[-1:] == ["selbst"], f"{zeige(hier)} / {kameras_seit(kamera)}")
        cam_off(zuschauer)
        cam_off(bot)

        set_option(env, "max-guests", "1", "tour")
        dritter.start()
        if FIND.test("Dritter Spieler fuer tour.max-guests kommt herein",
                     dritter.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            time.sleep(1.5)
            hinstellen(bot, x, y, z)
            cam_on(bot)
            console(env, f"tp {DRITTER_NAME} {x - 3} {y} {z}", pause=0.5)
            console(env, f"tp {ZUSCHAUER_NAME} {gast[0]} {y} {z}", pause=1.0)
            befehl(bot, f"/cam tour invite {DRITTER_NAME}")
            befehl(bot, f"/cam tour invite {ZUSCHAUER_NAME}")
            dort = befehl(zuschauer, f"/cam tour accept {BOT_NAME}", warten=3.0)
            FIND.test("max-guests: 1 - der erste Gast kommt mit", gesagt(dort, rf"You follow {BOT_NAME} now"),
                      zeige(dort))
            time.sleep(1.5)
            fuehrer_nr = bot.call("cameras").get("self")
            gast_nr = zuschauer.call("cameras").get("self")
            FIND.test("Von aussen sieht man nur den Fuehrer: der Gast, der ihm folgt, ist ausgeblendet - in der "
                      "Tab-Liste steht er",
                      kennt(dritter, fuehrer_nr) and not kennt(dritter, gast_nr) and in_tab(dritter, ZUSCHAUER_NAME),
                      f"{kennt(dritter, fuehrer_nr)} / {kennt(dritter, gast_nr)} / "
                      f"{dritter.call('players').get('players')}")
            zeilen = befehl(dritter, f"/cam tour accept {BOT_NAME}", warten=2.0)
            FIND.test("Die Fuehrung ist voll: wer eingeladen ist, kommt nicht mehr mit, und nichts startet",
                      gesagt(zeilen, rf"The tour of {BOT_NAME} is full right now")
                      and not im_cam_modus(bot, DRITTER_NAME), zeige(zeilen))
            zeilen = befehl(bot, f"/cam tour invite {DRITTER_NAME}")
            FIND.test("Die Fuehrung ist voll: der Fuehrer kann niemanden mehr einladen",
                      gesagt(zeilen, r"Your tour is full\. Guests at most: 1\."), zeige(zeilen))
            befehl(bot, "/cam menu", warten=1.0)
            knopf = item_text(fenster(bot).get("slots", {}).get("9"))
            FIND.test("Im Menue sagt der Knopf der Fuehrung \"Guests: 1/1\"", "Guests: 1/1" in knopf, knopf)
            bot.call("close_window", wait=10)
            befehl(zuschauer, "/cam tour leave")
            time.sleep(1.0)
            FIND.test("Nach der Fuehrung sieht man den Gast von aussen wieder", kennt(dritter, gast_nr), "")
            cam_off(zuschauer)
            cam_off(bot)
        dritter.stop()
        set_option(env, "max-guests", "5", "tour")

        set_option(env, "enabled", "false", "tour")
        zeilen = befehl(bot, "/cam tour leave")
        FIND.test("tour.enabled: false - /cam tour ist aus", gesagt(zeilen, r"/cam tour is switched off"),
                  zeige(zeilen))
    finally:
        try:
            for client in (zuschauer, bot):
                if client.proc is not None:
                    client.call("close_window", wait=10)
            if zuschauer.proc is not None:
                zuschauer.call("velocity", on=False)
                cam_off(zuschauer)
                zuschauer.stop()
            if dritter.proc is not None:
                dritter.stop()
            cam_off(bot)
            set_options(env, [("max-distance", "100.0"), ("out-of-reach-seconds", "5", "tour"),
                              ("max-guests", "5", "tour"), ("enabled", "true", "tour"),
                              ("particles-per-tick", "0", "camera-particles")])
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der Fuehrung: {exc}")


def weiter_checks(env, bot):
    """resume-command: /cam resume macht dort weiter, wo der letzte Flug
    aufhoerte.

    Voreingestellt ist es an, mit storage: file - nachgesehen in der
    ausgelieferten Datei. Der Bot fliegt hoch und zur Seite, schaut nach
    Osten und beendet den Cam-Modus; in seiner Datei steht das Ende unter
    last-flight. /cam resume startet den Cam-Modus, und die Kamera steht dort
    und schaut wieder nach Osten, mit sounds.spot und der Zeile in der
    Action-Bar. Im Cam-Modus springt sie gleich dorthin zurueck. Mit
    max-distance: 2 ist es zu weit, und mit einem Stein an der Stelle ist sie
    verstellt - beide Male mit der Meldung, warum, und ohne dass der
    Cam-Modus startet. Die Tab-Vervollstaendigung bietet resume an. Mit
    enabled: false ist der Befehl aus, und mit storage: memory ist nach
    /cam reload kein Flug da.
    """
    def tab(text):
        return bot.call("tab", text=text).get("matches", [])

    def bleibt(vorher, nachher):
        return vorher is not None and nachher is not None and math.dist(vorher, nachher) < 0.5

    if not FIND.test("Cam-Modus ist vor dem Test von /cam resume aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    console(env, f"fill {_floor(x) - 3} {y} {_floor(z) - 3} {_floor(x) + 6} {y + 8} {_floor(z) + 3} minecraft:air",
            pause=0.5)
    stein = None
    try:
        FIND.test("Voreingestellt steht resume-command mit enabled: true und storage: file da",
                  re.search(r"(?m)^resume-command:\s*\n\s*enabled:\s*true\s*\n(?:\s*#.*\n)*\s*storage:\s*file\s*$",
                            ausgeliefert(env)) is not None, "")
        hinstellen(bot, x, y, z)
        if not FIND.test("Cam-Modus fuer /cam resume gestartet", cam_on(bot), ""):
            return
        bot.call("fly", wait=30, dy=4, dx=3, timeout=15000)
        time.sleep(1.0)
        bot.chat("/tp @s ~ ~ ~ -90 20")
        time.sleep(1.0)
        ende = bot.server_pos()
        cam_off(bot)
        datei = spielerdatei(env)
        text = datei.read_text(encoding="utf-8") if datei else ""
        FIND.test("storage: file - das Ende des Flugs steht in der Datei des Bots unter last-flight",
                  re.search(r"(?m)^last-flight:\s*$", text) is not None, text[-300:] or "keine Datei")
        FIND.test("Nach einem Flug schlaegt /cam resume vor", "resume" in tab("/cam "), "")

        seit = toene_marke(bot)
        zeilen = befehl(bot, "/cam resume", warten=2.5)
        da = bot.server_pos()
        blick = bot.server_data("Rotation[0]")
        FIND.test("/cam resume ohne Cam-Modus - er startet, und die Kamera steht, wo der letzte Flug aufhoerte",
                  gesagt(zeilen, r"Cam mode activated") and bleibt(ende, da), f"{ende} -> {da}: {zeige(zeilen)}")
        FIND.test("/cam resume: \"Back where your last flight ended\" in der Action-Bar",
                  gesagt(zeilen, r"Back where your last flight ended"), zeige(zeilen))
        FIND.test("/cam resume: die Kamera schaut wieder, wohin sie zuletzt schaute",
                  blick is not None and abs(blick + 90.0) < 1.0, str(blick))
        FIND.test("/cam resume: der Ton von sounds.spot", ton_da(bot, seit, "item.chorus_fruit.teleport"), "")
        bot.call("fly", wait=30, dx=-4, timeout=15000)
        time.sleep(1.0)
        zeilen = befehl(bot, "/cam resume")
        FIND.test("/cam resume im Cam-Modus - die Kamera springt gleich zurueck", bleibt(ende, bot.server_pos()),
                  zeige(zeilen))
        cam_off(bot)

        set_option(env, "max-distance", "2.0")
        hinstellen(bot, x, y, z)
        zeilen = befehl(bot, "/cam resume", warten=2.0)
        FIND.test("max-distance: 2 - zu weit, und der Cam-Modus startet nicht",
                  gesagt(zeilen, r"Your last flight ended more than 2\.0 blocks from here")
                  and not spielmodus_ist(bot, "adventure"), zeige(zeilen))
        set_option(env, "max-distance", "100.0")
        stein = (_floor(ende[0]), _floor(ende[1]) + 1, _floor(ende[2]))
        bot.chat(f"/setblock {stein[0]} {stein[1]} {stein[2]} minecraft:stone")
        time.sleep(0.5)
        zeilen = befehl(bot, "/cam resume", warten=2.0)
        FIND.test("Ein Stein an der Stelle - verstellt, und der Cam-Modus startet nicht",
                  gesagt(zeilen, r"Something has been built where your last flight ended")
                  and not spielmodus_ist(bot, "adventure"), zeige(zeilen))
        bot.chat(f"/setblock {stein[0]} {stein[1]} {stein[2]} minecraft:air")
        time.sleep(0.5)

        set_option(env, "enabled", "false", "resume-command")
        zeilen = befehl(bot, "/cam resume")
        FIND.test("resume-command.enabled: false - /cam resume ist aus",
                  gesagt(zeilen, r"/cam resume is switched off") and "resume" not in tab("/cam "), zeige(zeilen))
        set_options(env, [("enabled", "true", "resume-command"), ("storage", "memory", "resume-command")])
        zeilen = befehl(bot, "/cam resume")
        FIND.test("storage: memory - nach /cam reload ist kein Flug da", gesagt(zeilen, r"no flight to resume"),
                  zeige(zeilen))
    finally:
        try:
            cam_off(bot)
            set_options(env, [("max-distance", "100.0"), ("enabled", "true", "resume-command"),
                              ("storage", "file", "resume-command")])
            if stein is not None:
                bot.chat(f"/setblock {stein[0]} {stein[1]} {stein[2]} minecraft:air")
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test von /cam resume: {exc}")


def ausspaeh_checks(env, bot):
    """anti-scouting: wer im Cam-Modus ist, sieht andere Spieler und die
    Koerper anderer im Cam-Modus nur bis radius Bloecke weit.

    Voreingestellt ist es aus, mit radius: 32.0 und measured-from: body -
    nachgesehen in der ausgelieferten Datei. Hier schaut der zweite Spieler
    durch die Kamera, er hat kein op; der Bot steht zehn Bloecke daneben.
    Voreingestellt sieht er ihn. Mit enabled: true und radius: 6 nicht mehr,
    drei Bloecke daneben wieder. Fliegt seine Kamera nah an den Bot heran,
    sieht er ihn mit measured-from: body trotzdem nicht, mit camera schon.
    Der Koerper des Bots im Cam-Modus, zehn Bloecke weit, ist fuer ihn
    ebenso weg. Nach dem Cam-Modus sieht er wieder alles. Der Bot hat op und
    damit camplugin.antiscout.bypass: er sieht den zweiten Spieler auch
    zehn Bloecke weit.
    """
    def entitaeten(client):
        return client.call("entities", radius=24).get("entities", [])

    def sieht_bei(client, wo):
        return wo is not None and any(math.dist(e["pos"], wo) < 0.5 for e in entitaeten(client))

    def koerper_bei(client, wo):
        return sum(1 for e in entitaeten(client)
                   if math.dist((e["pos"][0], e["pos"][2]), (wo[0], wo[2])) < 1.0
                   and wo[1] - 0.5 <= e["pos"][1] <= wo[1] + 3.0)

    def zuschauer_pos():
        return bot.call("server_pos", selector=ZUSCHAUER_NAME).get("pos")

    if not FIND.test("Cam-Modus ist vor dem Test des Ausspaehens aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    weit = (x + 10, y, z)
    console(env, f"fill {_floor(x) - 2} {y} {_floor(z) - 2} {_floor(x) + 12} {y + 6} {_floor(z) + 2} minecraft:air",
            pause=0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    try:
        FIND.test("Voreingestellt steht anti-scouting mit enabled: false, radius: 32.0 und measured-from: body da",
                  re.search(r"(?m)^anti-scouting:\s*\n\s*enabled:\s*false\s*\n(?:\s*#.*\n)*\s*radius:\s*32\.0\s*\n"
                            r"(?:\s*#.*\n)*\s*measured-from:\s*body\s*$", ausgeliefert(env)) is not None, "")
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer das Ausspaehen kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        hinstellen(bot, *weit)
        if not FIND.test("Der zweite Spieler ist im Cam-Modus", zuschauer_cam_an(env, zuschauer, x, y, z), ""):
            return
        time.sleep(1.0)
        FIND.test("Voreingestellt sieht er den Bot zehn Bloecke weit", sieht_bei(zuschauer, bot.server_pos()), "")

        set_options(env, [("enabled", "true", "anti-scouting"), ("radius", "6.0", "anti-scouting")])
        zuschauer_cam_an(env, zuschauer, x, y, z)
        time.sleep(1.5)
        FIND.test("enabled: true, radius: 6 - zehn Bloecke weit sieht er den Bot nicht mehr",
                  not sieht_bei(zuschauer, bot.server_pos()), "")
        hinstellen(bot, x + 3, y, z)
        time.sleep(1.0)
        FIND.test("radius: 6 - drei Bloecke daneben sieht er ihn wieder", sieht_bei(zuschauer, bot.server_pos()), "")
        hinstellen(bot, *weit)
        time.sleep(1.0)
        zuschauer.call("fly", wait=30, dx=8, timeout=10000)
        time.sleep(1.5)
        FIND.test("measured-from: body - auch mit der Kamera daneben sieht er den Bot nicht",
                  not sieht_bei(zuschauer, bot.server_pos()), str(zuschauer_pos()))

        set_option(env, "measured-from", "camera", "anti-scouting")
        zuschauer_cam_an(env, zuschauer, x, y, z)
        time.sleep(1.0)
        zuschauer.call("fly", wait=30, dx=8, timeout=10000)
        time.sleep(1.5)
        FIND.test("measured-from: camera - mit der Kamera daneben sieht er ihn",
                  sieht_bei(zuschauer, bot.server_pos()), str(zuschauer_pos()))
        set_option(env, "measured-from", "body", "anti-scouting")

        hinstellen(bot, *weit)
        if FIND.test("Der Bot ist zehn Bloecke weiter im Cam-Modus", cam_on(bot), ""):
            bot.call("fly", wait=30, dy=4, timeout=15000)
            zuschauer_cam_an(env, zuschauer, x, y, z)
            time.sleep(1.5)
            FIND.test("Der Koerper des Bots, zehn Bloecke weit, ist fuer ihn weg", koerper_bei(zuschauer, weit) == 0,
                      str(koerper_bei(zuschauer, weit)))
            FIND.test("camplugin.antiscout.bypass - der Bot mit op sieht ihn trotzdem",
                      sieht_bei(bot, zuschauer_pos()), "")
            cam_off(zuschauer)
            time.sleep(1.5)
            FIND.test("Nach dem Cam-Modus sieht er den Koerper des Bots wieder", koerper_bei(zuschauer, weit) >= 2,
                      str(koerper_bei(zuschauer, weit)))
            cam_off(bot)
    finally:
        try:
            if zuschauer.proc is not None:
                cam_off(zuschauer)
                zuschauer.stop()
            cam_off(bot)
            set_options(env, [("enabled", "false", "anti-scouting"), ("radius", "32.0", "anti-scouting"),
                              ("measured-from", "body", "anti-scouting")])
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test des Ausspaehens: {exc}")


def item_text(slot):
    """Der Text eines Items im Fenster, Name und Zeilen darunter, ohne
    Farben: mineflayer gibt beides als NBT-Textbausteine, gesammelt wird
    jeder Wert unter text."""
    teile = []

    def sammeln(knoten):
        if isinstance(knoten, dict):
            for schluessel, wert in knoten.items():
                if schluessel == "text" and isinstance(wert, dict) and isinstance(wert.get("value"), str):
                    teile.append(wert["value"])
                else:
                    sammeln(wert)
        elif isinstance(knoten, list):
            for wert in knoten:
                sammeln(wert)

    for feld in ("custom", "lore"):
        roh = (slot or {}).get(feld)
        if roh:
            try:
                sammeln(json.loads(roh))
            except ValueError:
                teile.append(roh)
        teile.append("\n")
    return "".join(teile)


def menue_knopf(text, knopf, item=None, slot=None):
    """Item und Platz eines Knopfs unter menu.buttons in einem Text der
    config.yml umstellen."""
    muster = rf"(?m)^(    {knopf}:\s*\n\s+item:\s*)(\S+)(\s*\n\s+slot:\s*)(\d+)"
    treffer = re.search(muster, text)
    if treffer is None:
        FIND.problem(f"menu.buttons.{knopf} steht nicht in der config.yml des Testservers")
        return text
    neu = (treffer.group(1) + (item if item is not None else treffer.group(2)) + treffer.group(3)
           + (str(slot) if slot is not None else treffer.group(4)))
    return text[:treffer.start()] + neu + text[treffer.end():]


def menue_checks(env, bot):
    """menu: /cam menu, ein Fenster zum Anklicken statt Befehlen.

    Voreingestellt steht access: menu da - nachgesehen in der Datei, die das
    Plugin mitbringt: der Testserver stellt fuer alle anderen Abschnitte both
    ein, sie tippen ihre Befehle. Ohne Cam-Modus oeffnet /cam menu das Menue
    mit den Knoepfen auf ihren Plaetzen - die Fuehrung auf 9, die Punkte auf
    10, die Nachtsicht auf 17, Platz 13 frei -; der auf Platz 22 - der Kamera-Kopf -
    startet den Cam-Modus und schliesst das Fenster. Im Cam-Modus oeffnet es trotzdem, wo jedes andere Fenster zu
    bleibt, und nichts kommt dabei ins Inventar. Zeit, dann Sonnenuntergang
    gibt dem Bot 12000. Aussichtspunkte, "Save a spot here" fragt im Chat
    nach dem Namen, und die naechste Zeile speichert ihn; ein Linksklick auf
    den Punkt springt hin, ein Klick mit Umschalt loescht ihn.

    Unter dem Knopf der Statistik stehen die Zahlen des Bots, und er hat als
    op den Knopf der Admin-Seite. Darauf stehen die Liste und die Statistik
    anderer, Sperren und Flugspuren nicht, solange sie aus sind. Die Liste
    zeigt den Bot im Cam-Modus, zum Hinteleportieren. Mit admin-access: menu
    verweist /cam list auf das Menue, die Konsole darf es weiter. Mit
    cam-ban und stop-command an steht auf der Seite der Spieler vorn
    Everybody mit einem im Cam-Modus, dahinter der Bot; ein Klick darauf
    bietet Beenden und Sperren an. Beenden beendet den Cam-Modus aller.
    Sperren steht auf fuer immer; Tage, Stunden und Minuten - alle mit der
    Uhr - kommen mit einem Linksklick dazu, mit Umschalt so viele wie
    menu.ban-shift-step (voreingestellt 10, im Test 5), und mit einem
    Rechtsklick weg, nur nie bis auf nichts. Der Grund kommt aus dem Chat -
    einer, der mit 1h anfaengt, bleibt der Grund -, und die Nachricht sagt
    "because:". Danach steht Banned unter dem Kopf, der Knopf Change the ban
    glaenzt; einen eigenen Knopf zum Aufheben hat die Seite nicht. Change
    the ban hat die Zeit, die noch bleibt, und statt des Grundes Lift the
    ban, das sagt, dass er wieder freigeschaltet ist; ein Tag mehr gibt die
    Nachricht, dass die Sperre geaendert ist, und Lift the ban hebt sie auf.
    Mit
    cam-trail.enabled: true und zwei Fluegen sagt der Kopf des Bots auf der
    Seite der Flugspuren, dass zwei gemerkt sind; ein Klick zeigt sie, den
    neuesten zuerst, mit Datum, Dauer, Strecke, Start und Ende. Linksklick
    zeigt einen, Rechtsklick zeigt ihn und bringt den Bot an seinen Start, der
    letzte Knopf zeigt alle.

    Foto-Modus, Zeit und Nachtsicht sagen unter ihrem Namen, wie sie stehen:
    vor dem Sonnenuntergang die Zeit der Welt, danach Sunset, der Foto-Modus
    off und nach einem Klick on; auf der Seite der Zeiten hat Sunset "Now"
    darunter, die Zeit der Welt nicht.

    menu.buttons: start auf Platz 4 steht dort und startet von dort, back auf
    dem Platz der Nachtsicht wird gemeldet und kommt auf seinen eigenen, ein
    unbekanntes Item wird gemeldet.

    access: menu laesst nur das Menue: /cam spot sagt, dass es dafuer
    /cam menu gibt, und die Tab-Vervollstaendigung bietet menu an, spot
    nicht. access: command laesst nur die Befehle, /cam menu ist aus. Mit
    false geht keins von beiden, /cam selbst aber schon.
    """
    def fenster():
        return bot.call("window_slots", wait=10)

    def klick(slot, button=0, mode=0, warten=1.5):
        bot.call("click_window", wait=15, slot=slot, button=button, mode=mode)
        time.sleep(warten)

    def tab(text):
        return bot.call("tab", text=text).get("matches", [])

    def menue():
        befehl(bot, "/cam menu", warten=1.0)
        return fenster()

    if not FIND.test("Cam-Modus ist vor dem Test des Menues aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    console(env, f"fill {_floor(x) - 6} {y} {_floor(z) - 2} {_floor(x) + 2} {y + 6} {_floor(z) + 2} minecraft:air",
            pause=0.5)
    datei = env.server / "plugins" / "CamFly" / "config.yml"
    original = None
    try:
        quelle = (env.repo / "src" / "main" / "resources" / "config.yml").read_text(encoding="utf-8")
        FIND.test("Voreingestellt steht menu.access: menu da",
                  re.search(r"(?m)^menu:\s*\n(?:\s*#.*\n)*\s*access:\s*menu\s*$", quelle) is not None, "")
        FIND.test("Voreingestellt steht menu.admin-access: menu da",
                  re.search(r"(?m)^  admin-access:\s*menu\s*$", quelle) is not None, "")
        spots_loeschen(bot, "Menue")
        console(env, "time set day", pause=1.0)
        hinstellen(bot, x, y, z)
        f = menue()
        FIND.test("/cam menu oeffnet das Menue", f.get("open") and "Cam" in (f.get("title") or ""), str(f)[:300])
        knopf = f.get("slots", {})
        FIND.test("Die eigene Statistik steht unter ihrem Knopf auf Platz 21",
                  "Times in cam mode:" in item_text(knopf.get("21")), item_text(knopf.get("21")))
        FIND.test("Der Bot mit op hat den Knopf der Admin-Seite auf Platz 23", "23" in knopf, str(sorted(knopf)))
        FIND.test("Vor dem Cam-Modus: Zeit der Welt, Foto-Modus aus, Nachtsicht nur im Dunkeln",
                  "Now: the time of the world" in item_text(knopf.get("15"))
                  and "Now: off" in item_text(knopf.get("14"))
                  and "Now: Only in the dark" in item_text(knopf.get("17")),
                  f"{item_text(knopf.get('15'))} / {item_text(knopf.get('14'))} / {item_text(knopf.get('17'))}")
        FIND.test("Die Knoepfe stehen auf ihren Plaetzen",
                  all(str(i) in f.get("slots", {}) for i in (9, 10, 11, 12, 14, 15, 16, 17, 22))
                  and "13" not in f.get("slots", {}),
                  str(sorted(f.get("slots", {})))[:200])
        klick(22, warten=2.0)
        FIND.test("Der Knopf auf Platz 22 startet den Cam-Modus und schliesst das Fenster",
                  spielmodus_ist(bot, "adventure") and not fenster().get("open"), "")

        f = menue()
        FIND.test("Im Cam-Modus oeffnet das Menue trotzdem", f.get("open") and "Cam" in (f.get("title") or ""),
                  str(f)[:300])
        klick(15)
        f = fenster()
        FIND.test("Zeit - die Seite mit den Tageszeiten", f.get("open") and "Time of day" in (f.get("title") or ""),
                  str(f)[:300])
        klick(12, warten=2.0)
        FIND.test("Sonnenuntergang - der Bot sieht 12000, und das Fenster ist zu",
                  bot.call("sky").get("timeOfDay") == 12000 and not fenster().get("open"),
                  str(bot.call("sky").get("timeOfDay")))
        FIND.test("Im Menue kommt nichts ins Inventar", bot.call("inventory").get("count") == 0,
                  str(bot.call("inventory")))
        knopf = menue().get("slots", {})
        FIND.test("Die Zeit sagt, wie sie steht: Sunset", "Now: Sunset" in item_text(knopf.get("15")),
                  item_text(knopf.get("15")))
        klick(14, warten=2.0)
        knopf = menue().get("slots", {})
        FIND.test("Der Foto-Modus sagt, wie er steht: on", "Now: on" in item_text(knopf.get("14")),
                  item_text(knopf.get("14")))
        klick(14, warten=2.0)
        menue()
        klick(15)
        knopf = fenster().get("slots", {})
        FIND.test("Auf der Seite der Zeiten hat Sunset \"Now\" darunter, die Zeit der Welt nicht",
                  "Now" in item_text(knopf.get("12")) and "Now" not in item_text(knopf.get("16")),
                  f"{item_text(knopf.get('12'))} / {item_text(knopf.get('16'))}")
        bot.call("close_window", wait=10)

        menue()
        klick(10)
        f = fenster()
        FIND.test("Aussichtspunkte - die Seite der Punkte", f.get("open") and "Spots" in (f.get("title") or ""),
                  str(f)[:300])
        seit = bot.mark()
        klick(53)
        zeilen = zeilen_seit(bot, seit)
        FIND.test("Save a spot here - das Fenster ist zu, und der Chat fragt nach dem Namen",
                  gesagt(zeilen, r"Type the name of the new spot in the chat") and not fenster().get("open"),
                  zeige(zeilen))
        punkt = bot.server_pos()
        zeilen = befehl(bot, "Menue")
        FIND.test("Die naechste Zeile im Chat ist der Name", gesagt(zeilen, r"Spot Menue saved here"), zeige(zeilen))
        bot.call("fly", wait=30, dx=-5, timeout=15000)
        time.sleep(1.0)
        menue()
        klick(10)
        klick(0, warten=2.0)
        da = bot.server_pos()
        FIND.test("Linksklick auf den Punkt springt hin",
                  punkt is not None and da is not None and math.dist(punkt, da) < 0.5, f"{punkt} / {da}")
        menue()
        klick(10)
        seit = bot.mark()
        klick(0, mode=1)
        zeilen = zeilen_seit(bot, seit)
        FIND.test("Ein Klick mit Umschalt loescht den Punkt", gesagt(zeilen, r"Spot Menue deleted"), zeige(zeilen))
        bot.call("close_window", wait=10)

        menue()
        klick(23)
        f = fenster()
        knopf = f.get("slots", {})
        FIND.test("Die Admin-Seite: wer im Cam-Modus ist und die Statistik anderer, ohne Spieler und Flugspuren, "
                  "solange stop, ban und trail aus sind",
                  "Cam admin" in (f.get("title") or "") and "10" in knopf and "14" in knopf
                  and "12" not in knopf and "16" not in knopf
                  and "teleport to them" in item_text(knopf.get("10")), f"{f.get('title')} / {sorted(knopf)}")
        klick(10)
        f = fenster()
        FIND.test("Wer im Cam-Modus ist: der Bot, mit der Zeit im Cam-Modus und dem Teleport",
                  "Who is in cam mode" in (f.get("title") or "")
                  and BOT_NAME in item_text(f.get("slots", {}).get("0"))
                  and "In cam mode for" in item_text(f.get("slots", {}).get("0"))
                  and "teleport to the camera" in item_text(f.get("slots", {}).get("0")),
                  item_text(f.get("slots", {}).get("0")))
        bot.call("close_window", wait=10)
        cam_off(bot)

        set_option(env, "admin-access", "menu", "menu")
        zeilen = befehl(bot, "/cam list")
        vorher = len(server_log(env))
        console(env, "cam list", pause=1.5)
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("admin-access: menu - /cam list verweist auf das Menue, die Konsole darf es weiter",
                  gesagt(zeilen, r"That goes through /cam menu") and "Nobody is in cam mode" in log
                  and "list" not in tab("/cam "), f"{zeige(zeilen)} / {log.strip()[-200:]}")
        FIND.test("Voreingestellt steht menu.ban-shift-step: 10 da",
                  re.search(r"(?m)^  ban-shift-step:\s*10\s*$", quelle_config(env)) is not None, "")
        set_options(env, [("enabled", "true", "cam-ban"), ("enabled", "true", "stop-command"),
                          ("ban-shift-step", "5", "menu")])
        cam_on(bot)
        menue()
        klick(23)
        klick(12)
        f = fenster()
        knopf = f.get("slots", {})
        FIND.test("Spieler: vorn Everybody mit einem im Cam-Modus, dahinter der Bot",
                  "Players" in (f.get("title") or "") and "Everybody" in item_text(knopf.get("0"))
                  and "1 in cam mode" in item_text(knopf.get("0")) and BOT_NAME in item_text(knopf.get("1")),
                  f"{item_text(knopf.get('0'))} / {item_text(knopf.get('1'))}")
        klick(0)
        f = fenster()
        knopf = f.get("slots", {})
        FIND.test("Ein Klick auf Everybody: beenden, der Kopf und sperren - kein eigener Knopf zum Aufheben",
                  "Everybody" in (f.get("title") or "") and "End everybody's cam mode" in item_text(knopf.get("11"))
                  and "Everybody" in item_text(knopf.get("13")) and "Ban" in item_text(knopf.get("15"))
                  and not (knopf.get("15") or {}).get("glint")
                  and not any("Lift the ban" in item_text(v) for v in knopf.values()),
                  f"{f.get('title')} / {sorted(knopf)}")
        seit = bot.mark()
        klick(11, warten=2.0)
        zeilen = zeilen_seit(bot, seit)
        FIND.test("Beenden beendet den Cam-Modus aller", gesagt(zeilen, r"Ended the cam mode of 1 players")
                  and not spielmodus_ist(bot, "adventure"), zeige(zeilen))
        menue()
        klick(23)
        klick(12)
        klick(0)
        klick(15)
        f = fenster()
        knopf = f.get("slots", {})
        FIND.test("Sperren: Dauer im Menue, voreingestellt fuer immer, daneben Tage, Stunden und Minuten",
                  "Ban Everybody" in (f.get("title") or "") and "Now" in item_text(knopf.get("10"))
                  and "For good" in item_text(knopf.get("10")) and "Days: 0" in item_text(knopf.get("12"))
                  and "Hours: 0" in item_text(knopf.get("13")) and "Minutes: 0" in item_text(knopf.get("14"))
                  and "Altogether: for good" in item_text(knopf.get("13")) and "26" not in knopf,
                  f"{f.get('title')} / {item_text(knopf.get('10'))} / {item_text(knopf.get('13'))}")
        arten = {(knopf.get(platz) or {}).get("name") for platz in ("12", "13", "14")}
        FIND.test("Tage, Stunden und Minuten sind dasselbe Item (die Uhr)", len(arten) == 1 and None not in arten,
                  str(arten))
        FIND.test("ban-shift-step: 5 - die Knoepfe sagen, dass Umschalt fuenf auf einmal gibt",
                  "With shift: 5 at once" in item_text(knopf.get("14")), item_text(knopf.get("14")))
        klick(13)
        klick(14, mode=1)
        klick(14, button=1)
        klick(12, button=1)
        knopf = fenster().get("slots", {})
        FIND.test("Zeit: Stunde dazu, mit Umschalt fuenf Minuten dazu, eine weg - 1h 4m, fuer immer ist es nicht mehr",
                  "Hours: 1" in item_text(knopf.get("13")) and "Minutes: 4" in item_text(knopf.get("14"))
                  and "Altogether: for 1h 4m 0s" in item_text(knopf.get("14"))
                  and "Now" not in item_text(knopf.get("10")),
                  f"{item_text(knopf.get('13'))} / {item_text(knopf.get('14'))} / {item_text(knopf.get('10'))}")
        FIND.test("Zeit: einen Tag weg geht bei 1h 4m nicht - es bleibt, wie es ist (nichts waere fuer immer)",
                  "Days: 0" in item_text(knopf.get("12")) and "Hours: 1" in item_text(knopf.get("13")),
                  item_text(knopf.get("12")))
        seit = bot.mark()
        klick(16)
        zeilen = zeilen_seit(bot, seit)
        FIND.test("Der Grund: der Chat fragt danach", gesagt(zeilen, r"Type in the chat why Everybody is banned"),
                  zeige(zeilen))
        befehl(bot, "1h Testgrund")
        f = fenster()
        FIND.test("Nach dem Grund ist die Seite wieder da, mit dem Grund und der Zeit",
                  f.get("open") and "1h Testgrund" in item_text(f.get("slots", {}).get("16"))
                  and "Minutes: 4" in item_text(f.get("slots", {}).get("14")),
                  item_text(f.get("slots", {}).get("16")))
        seit = bot.mark()
        klick(22, warten=2.0)
        zeilen = zeilen_seit(bot, seit)
        knopf = fenster().get("slots", {})
        FIND.test("Sperren sperrt alle fuer 1h 4m mit dem Grund - der mit 1h anfaengt, bleibt der Grund",
                  gesagt(zeilen, r"Cam mode is locked for everybody for 1h 4m 0s because: 1h Testgrund\."),
                  zeige(zeilen))
        FIND.test("Danach: Banned unter dem Kopf, Change the ban glaenzt - und kein eigener Knopf zum Aufheben",
                  "Banned" in item_text(knopf.get("13")) and "Change the ban" in item_text(knopf.get("15"))
                  and (knopf.get("15") or {}).get("glint") is True
                  and not any("Lift the ban" in item_text(v) for v in knopf.values()),
                  f"{item_text(knopf.get('13'))} / {knopf.get('15')}")
        klick(15)
        f = fenster()
        knopf = f.get("slots", {})
        FIND.test("Change the ban: die Seite hat die Zeit, die noch bleibt, und statt des Grundes Lift the ban",
                  "Change the ban of Everybody" in (f.get("title") or "") and "Hours: 1" in item_text(knopf.get("13"))
                  and "Minutes: 4" in item_text(knopf.get("14")) and "Lift the ban" in item_text(knopf.get("16"))
                  and "Unlock Everybody again, so they" in item_text(knopf.get("16"))
                  and "can use cam mode again" in item_text(knopf.get("16"))
                  and not any("Reason" in item_text(v) for v in knopf.values())
                  and "Change the ban" in item_text(knopf.get("22")) and "26" not in knopf,
                  f"{f.get('title')} / {item_text(knopf.get('14'))} / {item_text(knopf.get('16'))}")
        klick(12)
        seit = bot.mark()
        klick(22, warten=2.0)
        zeilen = zeilen_seit(bot, seit)
        FIND.test("Change the ban: ein Tag mehr - jetzt 1d 1h 4m, und es heisst, sie ist geaendert",
                  gesagt(zeilen, r"Cam mode is now locked for everybody for 1d 1h 4m because: 1h Testgrund\."),
                  zeige(zeilen))
        klick(15)
        seit = bot.mark()
        klick(16, warten=2.0)
        zeilen = zeilen_seit(bot, seit)
        knopf = fenster().get("slots", {})
        FIND.test("Lift the ban auf der Seite der Sperre hebt sie auf, danach steht wieder Ban da",
                  gesagt(zeilen, r"Cam mode is unlocked: everybody may use it again")
                  and "Ban" in item_text(knopf.get("15"))
                  and not (knopf.get("15") or {}).get("glint"), f"{zeige(zeilen)} / {item_text(knopf.get('15'))}")
        bot.call("close_window", wait=10)
        set_options(env, [("enabled", "false", "cam-ban"), ("enabled", "false", "stop-command"),
                          ("admin-access", "both", "menu"), ("enabled", "true", "cam-trail"),
                          ("ban-shift-step", "10", "menu")])

        # Zwei Fluege vom selben Platz, dann ein Stueck daneben.
        for weg in ({"dy": 3}, {"dx": -3, "dy": 2}):
            hinstellen(bot, x, y, z)
            cam_on(bot)
            bot.call("fly", wait=30, timeout=15000, **weg)
            time.sleep(1.0)
            cam_off(bot)
            time.sleep(1.5)
        hinstellen(bot, x - 5, y, z)
        menue()
        klick(23)
        klick(16)
        knopf = fenster().get("slots", {})
        FIND.test("Flugspuren: der Kopf des Bots sagt, wie viele Fluege gemerkt sind",
                  "2 flights kept" in item_text(knopf.get("0")), item_text(knopf.get("0")))
        klick(0)
        f = fenster()
        knopf = f.get("slots", {})
        neuester, aelterer = item_text(knopf.get("0")), item_text(knopf.get("1"))
        FIND.test("Ein Klick auf den Kopf zeigt seine Fluege, der neueste zuerst, mit Datum, Dauer, Strecke, Start "
                  "und Ende",
                  f"Flights of {BOT_NAME}" in (f.get("title") or "") and "#1" in neuester and "#2" in aelterer
                  and all(w in neuester for w in ("Started:", "Duration:", "Distance:", "From:", "To:"))
                  and "49" in knopf, f"{f.get('title')} / {neuester} / {aelterer}")
        seit = bot.mark()
        klick(1, warten=2.0)
        zeilen = zeilen_seit(bot, seit)
        FIND.test("Linksklick zeigt den aelteren Flug", gesagt(zeilen, rf"Cam flight #2 of {BOT_NAME}"), zeige(zeilen))
        menue()
        klick(23)
        klick(16)
        klick(0)
        seit = bot.mark()
        klick(0, button=1, warten=2.5)
        zeilen = zeilen_seit(bot, seit)
        da = bot.server_pos()
        FIND.test("Rechtsklick zeigt ihn und bringt den Bot zum Start, wo der Koerper stand",
                  gesagt(zeilen, rf"At the start of cam flight #1 of {BOT_NAME}")
                  and da is not None and math.dist(da, (x, y, z)) < 1.0, f"{da} / {zeige(zeilen)}")
        menue()
        klick(23)
        klick(16)
        klick(0)
        seit = bot.mark()
        klick(49, warten=2.0)
        zeilen = zeilen_seit(bot, seit)
        FIND.test("Der letzte Knopf zeigt alle Fluege", gesagt(zeilen, rf"The last 2 cam flights of {BOT_NAME}"),
                  zeige(zeilen))
        set_option(env, "enabled", "false", "cam-trail")

        original = datei.read_text(encoding="utf-8")
        text = menue_knopf(original, "start", slot=4)
        text = menue_knopf(text, "back", slot=17)
        text = menue_knopf(text, "time", item="kein_item")
        text = menue_knopf(text, "list", slot=4)
        datei.write_text(text, encoding="utf-8")
        vorher = len(server_log(env))
        console(env, "cam reload", pause=2)
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("menu.buttons: ein Knopf auf einem besetzten Platz wird gemeldet und kommt auf seinen eigenen",
                  "menu.buttons.back.slot: slot 17 is taken by menu.buttons.nightvision.slot already. "
                  "Using slot 11 instead." in log, log.strip()[-300:] or "nichts im Log")
        FIND.test("menu.buttons: ein unbekanntes Item wird gemeldet",
                  "Unknown value for menu.buttons.time.item: 'kein_item'" in log, log.strip()[-300:] or "nichts im Log")
        menue()
        klick(23)
        knopf = fenster().get("slots", {})
        FIND.test("menu.admin-buttons.list.slot: 4 - auf der Admin-Seite steht die Liste auf Platz 4",
                  "4" in knopf and "10" not in knopf, str(sorted(knopf)))
        bot.call("close_window", wait=10)
        knopf = menue().get("slots", {})
        FIND.test("menu.buttons.start.slot: 4 - der Start steht auf Platz 4, back auf 11, die Zeit auf 15",
                  all(str(i) in knopf for i in (4, 11, 15)) and "22" not in knopf, str(sorted(knopf)))
        klick(4, warten=2.0)
        FIND.test("menu.buttons.start.slot: 4 - der Knopf startet von dort", spielmodus_ist(bot, "adventure"), "")
        cam_off(bot)
        datei.write_text(original, encoding="utf-8")
        original = None
        console(env, "cam reload", pause=2)

        set_option(env, "access", "menu", "menu")
        zeilen = befehl(bot, "/cam spot list")
        jetzt = tab("/cam ")
        f = menue()
        FIND.test("access: menu - /cam spot verweist auf das Menue, und das Menue geht",
                  gesagt(zeilen, r"That goes through /cam menu") and f.get("open"), zeige(zeilen))
        FIND.test("access: menu - vorgeschlagen wird menu, aber nicht spot", "menu" in jetzt and "spot" not in jetzt,
                  str(jetzt))
        bot.call("close_window", wait=10)
        set_option(env, "access", "command", "menu")
        zeilen = befehl(bot, "/cam menu")
        spots = befehl(bot, "/cam spot list")
        FIND.test("access: command - /cam menu ist aus, /cam spot geht",
                  gesagt(zeilen, r"/cam menu is switched off") and gesagt(spots, r"You have no spots yet")
                  and "menu" not in tab("/cam "), f"{zeige(zeilen)} / {zeige(spots)}")
        set_option(env, "access", "false", "menu")
        zeilen = befehl(bot, "/cam menu")
        foto = befehl(bot, "/cam photo")
        FIND.test("access: false - weder das Menue noch /cam photo",
                  gesagt(zeilen, r"/cam menu is switched off") and gesagt(foto, r"That is switched off on this server"),
                  f"{zeige(zeilen)} / {zeige(foto)}")
        FIND.test("access: false - /cam selbst geht weiter", cam_on(bot) and cam_off(bot), "")
    finally:
        try:
            bot.call("close_window", wait=10)
            cam_off(bot)
            if original is not None:
                datei.write_text(original, encoding="utf-8")
            # Wie der Testserver es fuer alle anderen Abschnitte hat.
            set_options(env, [("access", "both", "menu"), ("admin-access", "both", "menu"),
                              ("enabled", "false", "cam-ban"), ("enabled", "false", "cam-trail"),
                              ("enabled", "false", "stop-command")])
            console(env, "cam unban all", pause=0.5)
            spots_loeschen(bot, "Menue")
            console(env, "time set day", pause=0.5)
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test des Menues: {exc}")


def spotreiter_checks(env, bot):
    """menu: die Aussichtspunkte auf drei Reitern - die eigenen, oeffentlich
    und geteilt - und das Sortieren der Listen.

    Der Bot setzt Alpha zwei Bloecke weiter, dann Beta an seinem Platz, dann
    Cee sechs Bloecke weiter, und stellt sich wieder an seinen Platz. Unten
    stehen zurueck auf 45, die Reiter auf 48, 49 und 50, der Filter auf 51,
    52 frei und das Buch fuer einen neuen Punkt auf 53. Auf dem Reiter der
    eigenen stehen die Punkte, der neueste zuerst, mit dem Tag, an dem er
    entstand, und wie nah man fuer den Sprung sein muss; das Buch sagt, wie viele Punkte er hat und wie viele noch gehen.
    Der Filter zeigt alle Sortierungen, die gewaehlte markiert, und sortiert
    nach dem Namen, nach den Aufrufen (noch keiner hat einen, "Views: 0")
    und dann nach der Reichweite, die naechsten zuerst. Ein Rechtsklick auf das Buch fragt nach
    einem oeffentlichen Punkt, Gamma ist danach oeffentlich und steht bei den
    eigenen - dort mit "Public" darunter - und bei den oeffentlichen, als
    seiner. Der zweite Spieler sieht ihn dort und liked ihn mit einem
    Rechtsklick; danach steht das Like darunter, und der Punkt glaenzt. Ein
    Linksklick springt wie zu einem eigenen Punkt: ohne Cam-Modus, aber nah
    dran, startet er dort, und seine Kamera steht danach am Punkt. Der Filter des Bots auf den
    oeffentlichen kommt nach drei Klicks zu den meisten Likes, nach vier zu
    den meisten Aufrufen - der Sprung des anderen hat Gamma einen gegeben. Umschalt-Klick
    auf den eigenen oeffentlichen loescht ihn dort nicht, ein Rechtsklick
    nimmt ihn offline - bei den eigenen bleibt er.

    Bietet der Bot Alpha an, glaenzt beim anderen der Knopf der Punkte und
    sagt, dass einer wartet, und er oeffnet auf dem Reiter der geteilten: vorn
    das Angebot, ein Linksklick nimmt es an, danach steht der Punkt dort, und
    ein Rechtsklick nimmt ihn von der Liste.

    Auf der Admin-Seite sortiert der Trichter die Statistik der Spieler nach
    dem Namen oder dem letzten Flug: der zweite Spieler, der gerade geflogen
    ist, steht dann vorn.

    viewpoints.views-cooldown-minutes steht voreingestellt auf 10: springt
    der Bot zweimal gleich hintereinander zu Beta, zaehlt das einmal; mit 0
    zaehlt jeder Sprung.
    """
    def fenster(client):
        return client.call("window_slots", wait=10)

    def klick(client, slot, button=0, mode=0, warten=1.5):
        client.call("click_window", wait=15, slot=slot, button=button, mode=mode)
        time.sleep(warten)

    def punkte(client):
        befehl(client, "/cam menu", warten=1.0)
        klick(client, 10)
        return fenster(client)

    def namen(f):
        """Die Namen der Eintraege einer Liste, nach ihrem Platz."""
        slots = f.get("slots", {})
        return [item_text(slots.get(str(i))).split("\n")[0] for i in range(45) if str(i) in slots]

    def platz_von(f, text):
        return next((i for i, n in enumerate(namen(f)) if text in n), None)

    def zuschauer_pos():
        return bot.call("server_pos", selector=ZUSCHAUER_NAME).get("pos")

    def aufrufe(name):
        """Wie oft der Punkt des Bots aufgerufen wurde, wie das Menue es sagt."""
        f = punkte(bot)
        platz = platz_von(f, name)
        text = item_text(f.get("slots", {}).get(str(platz))) if platz is not None else ""
        bot.call("close_window", wait=10)
        treffer = re.search(r"Views: (\d+)", text)
        return int(treffer.group(1)) if treffer else None

    if not FIND.test("Cam-Modus ist vor dem Test der Reiter aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    console(env, f"fill {_floor(x) - 2} {y} {_floor(z) - 2} {_floor(x) + 8} {y + 4} {_floor(z) + 2} minecraft:air",
            pause=0.5)
    label = f"{BOT_NAME}:Gamma"
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    try:
        spots_loeschen(bot, "Alpha", "Beta", "Cee", "Gamma")
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer die Reiter kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        cam_off(zuschauer)
        console(env, f"tp {ZUSCHAUER_NAME} {x + 3} {y} {z + 1}", pause=1.0)
        for name, dx in (("Alpha", 2), ("Beta", 0), ("Cee", 6)):
            hinstellen(bot, x + dx, y, z)
            befehl(bot, f"/cam spot set {name}")
            time.sleep(1.2)
        hinstellen(bot, x, y, z)

        f = punkte(bot)
        knopf = f.get("slots", {})
        FIND.test("Reiter der eigenen: der neueste zuerst, mit dem Tag, an dem er entstand",
                  "Spots: yours" in (f.get("title") or "") and namen(f)[:3] == ["Cee", "Beta", "Alpha"]
                  and "Saved:" in item_text(knopf.get("0")) and "newest first" in item_text(knopf.get("51")),
                  f"{f.get('title')} / {namen(f)} / {item_text(knopf.get('51'))}")
        FIND.test("Unter jedem Punkt steht, wie nah man fuer den Sprung sein muss: viewpoints.start-distance (16) "
                  "ohne Cam-Modus, im Cam-Modus in Reichweite der Kamera",
                  "To jump there: within 16 blocks" in item_text(knopf.get("0"))
                  and "reach of your camera" in item_text(knopf.get("0")), item_text(knopf.get("0")))
        FIND.test("Das Buch sagt, wie viele Punkte er hat und wie viele noch gehen",
                  "Your spots: 3 of 10 - 7 more possible" in item_text(knopf.get("53")), item_text(knopf.get("53")))
        filter_text = item_text(knopf.get("51"))
        FIND.test("Der Filter zeigt alle Sortierungen, die gewaehlte markiert",
                  "» newest first" in filter_text and "by name" in filter_text and "nearest first" in filter_text
                  and "» by name" not in filter_text and "» nearest first" not in filter_text, filter_text)
        FIND.test("Unten: zurueck auf 45, die Reiter auf 48 bis 50 (der offene glaenzt), der Filter auf 51, "
                  "52 frei, das Buch auf 53",
                  all(str(i) in knopf for i in (45, 48, 49, 50, 51, 53)) and "52" not in knopf
                  and (knopf.get("48") or {}).get("glint") is True and not (knopf.get("49") or {}).get("glint")
                  and "Save a spot here" in item_text(knopf.get("53")), str(sorted(knopf)))
        klick(bot, 51)
        f = fenster(bot)
        FIND.test("Der Filter sortiert nach dem Namen, jetzt ist der Name markiert",
                  namen(f)[:3] == ["Alpha", "Beta", "Cee"] and "» by name" in item_text(f.get("slots", {}).get("51"))
                  and "» newest first" not in item_text(f.get("slots", {}).get("51")), str(namen(f)))
        klick(bot, 51)
        f = fenster(bot)
        FIND.test("Noch ein Klick: nach den Aufrufen, die meisten zuerst - noch keiner hat einen, also der neueste "
                  "zuerst; unter jedem steht \"Views: 0\" und, wie oft ein Spieler zaehlt",
                  namen(f)[:3] == ["Cee", "Beta", "Alpha"]
                  and "» most views first" in item_text(f.get("slots", {}).get("51"))
                  and "Views: 0" in item_text(f.get("slots", {}).get("0"))
                  and "One view per player every 10 min" in item_text(f.get("slots", {}).get("0")), str(namen(f)))
        klick(bot, 51)
        f = fenster(bot)
        FIND.test("Noch ein Klick: nach der Reichweite, die naechsten zuerst",
                  namen(f)[:3] == ["Beta", "Alpha", "Cee"]
                  and "» nearest first" in item_text(f.get("slots", {}).get("51")), str(namen(f)))
        seit = bot.mark()
        klick(bot, 53, button=1)
        befehl(bot, "Gamma")
        zeilen = zeilen_seit(bot, seit)
        FIND.test("Rechtsklick auf das Buch: ein oeffentlicher Punkt",
                  gesagt(zeilen, r"Type the name of the new public spot") and gesagt(zeilen, r"Spot Gamma saved here")
                  and gesagt(zeilen, r"Spot Gamma is public now"), zeige(zeilen))
        f = punkte(bot)
        platz = platz_von(f, "Gamma")
        eigene = item_text(f.get("slots", {}).get(str(platz))) if platz is not None else ""
        klick(bot, 49)
        f = fenster(bot)
        FIND.test("Gamma bleibt bei den eigenen, mit \"Public\" darunter, und steht bei den oeffentlichen, als seiner",
                  "Public, 0 likes" in eigene and "Spots: public" in (f.get("title") or "")
                  and any("Gamma (yours)" in n for n in namen(f)), f"{eigene} / {namen(f)}")
        bot.call("close_window", wait=10)

        f = punkte(zuschauer)
        klick(zuschauer, 49)
        f = fenster(zuschauer)
        FIND.test("Der andere sieht den oeffentlichen Punkt", label in " ".join(namen(f)), str(namen(f)))
        platz = platz_von(f, label) or 0
        seit = zuschauer.mark()
        klick(zuschauer, platz, button=1)
        dort = zeilen_seit(zuschauer, seit)
        f = fenster(zuschauer)
        eintrag = f.get("slots", {}).get(str(platz)) or {}
        FIND.test("Rechtsklick liked ihn: danach steht das Like darunter, und er glaenzt",
                  gesagt(dort, rf"You like {label} now\. It has 1 likes") and "1 likes" in item_text(eintrag)
                  and eintrag.get("glint") is True, f"{zeige(dort)} / {item_text(eintrag)}")
        FIND.test("Auch unter dem oeffentlichen Punkt eines anderen steht, wie nah man fuer den Sprung sein muss",
                  "To jump there: within 16 blocks" in item_text(eintrag), item_text(eintrag))
        seit = zuschauer.mark()
        klick(zuschauer, platz, warten=3.0)
        dort = zeilen_seit(zuschauer, seit)
        wo = zuschauer_pos()
        FIND.test("Linksklick springt wie zu einem eigenen Punkt: ohne Cam-Modus nah dran startet er dort und springt",
                  gesagt(dort, r"Cam mode activated") and wo is not None and math.dist(wo, (x, y, z)) < 0.6,
                  f"{zeige(dort)} / {wo}")
        cam_off(zuschauer)
        zuschauer.call("close_window", wait=10)

        punkte(bot)
        klick(bot, 49)
        for _ in range(3):
            klick(bot, 51)
        f = fenster(bot)
        FIND.test("Auf den oeffentlichen sortiert der Filter nach drei Klicks nach den Likes",
                  "» most likes first" in item_text(f.get("slots", {}).get("51")), item_text(f.get("slots", {}).get("51")))
        klick(bot, 51)
        f = fenster(bot)
        platz = platz_von(f, "Gamma (yours)") or 0
        FIND.test("Noch ein Klick: nach den Aufrufen - der Sprung des anderen zu Gamma zaehlt als Aufruf",
                  "» most views first" in item_text(f.get("slots", {}).get("51"))
                  and "Views: 1" in item_text(f.get("slots", {}).get(str(platz))),
                  item_text(f.get("slots", {}).get(str(platz))))
        seit = bot.mark()
        klick(bot, platz, mode=1)
        geklickt = zeilen_seit(bot, seit)
        FIND.test("Umschalt-Klick auf den eigenen oeffentlichen loescht ihn dort nicht",
                  not gesagt(geklickt, r"Spot Gamma deleted")
                  and any("Gamma (yours)" in n for n in namen(fenster(bot))), zeige(geklickt))
        seit = bot.mark()
        klick(bot, platz_von(fenster(bot), "Gamma (yours)") or 0, button=1)
        zeilen = zeilen_seit(bot, seit)
        bot.call("close_window", wait=10)
        f = punkte(bot)
        platz = platz_von(f, "Gamma")
        eigene = item_text(f.get("slots", {}).get(str(platz))) if platz is not None else ""
        FIND.test("Rechtsklick nimmt den eigenen oeffentlichen offline - bei den eigenen bleibt er",
                  gesagt(zeilen, r"Spot Gamma is private again") and platz is not None and "Public" not in eigene,
                  f"{zeige(zeilen)} / {namen(f)}")
        bot.call("close_window", wait=10)

        befehl(bot, f"/cam spot share Alpha {ZUSCHAUER_NAME}")
        befehl(zuschauer, "/cam menu", warten=1.0)
        knopf = fenster(zuschauer).get("slots", {})
        FIND.test("Ein Angebot: beim anderen glaenzt der Knopf der Punkte und sagt, dass eins wartet",
                  (knopf.get("10") or {}).get("glint") is True
                  and "New, offered to you: 1" in item_text(knopf.get("10")), item_text(knopf.get("10")))
        klick(zuschauer, 10)
        f = fenster(zuschauer)
        FIND.test("Er oeffnet auf dem Reiter der geteilten, vorn das Angebot",
                  "shared with you" in (f.get("title") or "")
                  and f"{BOT_NAME}:Alpha" in item_text(f.get("slots", {}).get("0"))
                  and "Offered to you" in item_text(f.get("slots", {}).get("0")),
                  f"{f.get('title')} / {item_text(f.get('slots', {}).get('0'))}")
        seit = zuschauer.mark()
        klick(zuschauer, 0)
        dort = zeilen_seit(zuschauer, seit)
        f = fenster(zuschauer)
        FIND.test("Linksklick nimmt es an, danach steht der Punkt dort",
                  gesagt(dort, rf"You accepted {BOT_NAME}:Alpha")
                  and "take it off your list" in item_text(f.get("slots", {}).get("0")),
                  f"{zeige(dort)} / {item_text(f.get('slots', {}).get('0'))}")
        seit = zuschauer.mark()
        klick(zuschauer, 0, button=1)
        dort = zeilen_seit(zuschauer, seit)
        FIND.test("Rechtsklick nimmt ihn von der Liste",
                  gesagt(dort, rf"Spot {BOT_NAME}:Alpha is no longer among the spots shared with you"), zeige(dort))
        zuschauer.call("close_window", wait=10)

        cam_on(zuschauer)
        time.sleep(1.0)
        cam_off(zuschauer)
        befehl(bot, "/cam menu", warten=1.0)
        klick(bot, 23)
        klick(bot, 14)
        f = fenster(bot)
        FIND.test("Admin-Seite, Statistik: nach dem Namen", namen(f)[:2] == [BOT_NAME, ZUSCHAUER_NAME]
                  and "» by name" in item_text(f.get("slots", {}).get("53")), str(namen(f)))
        klick(bot, 53)
        f = fenster(bot)
        FIND.test("Der Trichter sortiert nach dem letzten Flug: wer gerade geflogen ist, steht vorn",
                  namen(f)[:1] == [ZUSCHAUER_NAME] and "» last flight first" in item_text(f.get("slots", {}).get("53")),
                  f"{namen(f)} / {item_text(f.get('slots', {}).get('53'))}")
        bot.call("close_window", wait=10)

        FIND.test("Voreingestellt steht viewpoints.views-cooldown-minutes: 10 da",
                  re.search(r"(?m)^\s*views-cooldown-minutes:\s*10\s*$", ausgeliefert(env)) is not None, "")
        hinstellen(bot, x, y, z)
        befehl(bot, "/cam spot Beta", warten=2.5)
        befehl(bot, "/cam spot Beta", warten=1.0)
        einmal = aufrufe("Beta")
        set_option(env, "views-cooldown-minutes", "0", "viewpoints")
        hinstellen(bot, x, y, z)
        befehl(bot, "/cam spot Beta", warten=2.5)
        befehl(bot, "/cam spot Beta", warten=1.0)
        jeder = aufrufe("Beta")
        set_option(env, "views-cooldown-minutes", "10", "viewpoints")
        FIND.test("views-cooldown-minutes: 10 - zweimal gleich hintereinander zaehlt einmal; mit 0 zaehlt jeder Sprung",
                  einmal == 1 and jeder == 3, f"{einmal} / {jeder}")
    finally:
        try:
            for client in (zuschauer, bot):
                if client.proc is not None:
                    client.call("close_window", wait=10)
            if zuschauer.proc is not None:
                cam_off(zuschauer)
                zuschauer.stop()
            cam_off(bot)
            spots_loeschen(bot, "Alpha", "Beta", "Cee", "Gamma")
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der Reiter: {exc}")


def offline_uuid(name):
    """Die Nummer eines Spielers auf einem Server ohne Anmeldung bei Mojang,
    wie Java sie bildet: UUID.nameUUIDFromBytes("OfflinePlayer:" + name)."""
    roh = bytearray(hashlib.md5(("OfflinePlayer:" + name).encode("utf-8")).digest())
    roh[6] = (roh[6] & 0x0F) | 0x30
    roh[8] = (roh[8] & 0x3F) | 0x80
    return str(uuid.UUID(bytes=bytes(roh)))


def spotmoderation_checks(env, bot):
    """menu: die Seite der Punkte auf der Admin-Seite (camplugin.spotadmin).

    Der zweite Spieler setzt drei Punkte: ModA privat, ModB oeffentlich, ModC
    dem Bot angeboten. Auf der Admin-Seite (Knopf auf 20) steht er unter den
    Spielern mit Punkten, mit "Spots: 3, public: 1, shared: 1", und ein Klick
    zeigt seine Punkte: ModA privat, ModB oeffentlich, ModC angeboten. Ein
    Linksklick nimmt ModB herunter - er ist wieder privat, und der Besitzer
    erfaehrt es -, ein Umschalt-Klick loescht ModA, auch das erfaehrt er.

    Der Knopf der Einschraenkung (49) oeffnet ihre Seite: "New spots" und
    "Making public" angewaehlt, fuer immer, bestaetigt - er erfaehrt es,
    /cam spot set eines neuen Punkts sagt ihm, dass er keine neuen speichern
    darf, /cam spot share ... all, dass er nichts oeffentlich machen darf;
    einen Punkt verschieben und teilen darf er weiter. Der Knopf glaenzt
    danach, und auf seiner Seite hebt der Milcheimer (35) die Einschraenkung
    auf: er darf wieder speichern.

    Geht er offline, steht er weiter in der Liste, und ein Umschalt-Klick
    loescht seine Punkte aus seiner Datei.
    """
    def fenster(client):
        return client.call("window_slots", wait=10)

    def klick(client, slot, button=0, mode=0, warten=1.5):
        client.call("click_window", wait=15, slot=slot, button=button, mode=mode)
        time.sleep(warten)

    def texte(f):
        """Der Text jedes Eintrags einer Liste, nach seinem Platz."""
        slots = f.get("slots", {})
        return {i: item_text(slots.get(str(i))) for i in range(45) if str(i) in slots}

    def platz(f, anfang):
        return next((i for i, t in texte(f).items() if t.startswith(anfang)), None)

    def seine_punkte():
        """Die Admin-Seite der Punkte, und darauf der zweite Spieler."""
        befehl(bot, "/cam menu", warten=1.0)
        klick(bot, 23)
        klick(bot, 20)
        liste = fenster(bot)
        wo = platz(liste, ZUSCHAUER_NAME)
        if wo is not None:
            klick(bot, wo)
        return liste, fenster(bot)

    if not FIND.test("Cam-Modus ist vor dem Test der Moderation aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
    datei = env.server / "plugins" / "CamFly" / "playerdata" / "stats" / f"{offline_uuid(ZUSCHAUER_NAME)}.yml"
    try:
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer die Moderation kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        cam_off(zuschauer)
        console(env, f"tp {ZUSCHAUER_NAME} {x + 2} {y} {z}", pause=1.0)
        spots_loeschen(zuschauer, "ModA", "ModB", "ModC", "ModD")
        for zeile in ("/cam spot set ModA", "/cam spot set ModB", "/cam spot share ModB all", "/cam spot set ModC",
                      f"/cam spot share ModC {BOT_NAME}"):
            befehl(zuschauer, zeile, warten=0.8)

        liste, f = seine_punkte()
        eintrag = texte(liste).get(platz(liste, ZUSCHAUER_NAME), "")
        FIND.test("Admin-Seite, Punkte (20): der zweite Spieler steht dort, mit seinen Punkten",
                  "Spots: 3, public: 1, shared: 1" in eintrag and "No restrictions" in eintrag, eintrag)
        alle = texte(f)
        a, b, c = (alle.get(platz(f, n), "") for n in ("ModA", "ModB", "ModC"))
        FIND.test("Ein Klick zeigt seine Punkte: privat, oeffentlich, angeboten",
                  f"Spots of {ZUSCHAUER_NAME}" in (f.get("title") or "") and "Private" in a
                  and "Public, 0 likes" in b and f"Offered to: {BOT_NAME}" in c
                  and "Left-click: take it down" in b and "Shift-click: delete" in a,
                  f"{f.get('title')} / {a} / {b} / {c}")

        seit = zuschauer.mark()
        klick(bot, platz(f, "ModB"))
        dort = zeilen_seit(zuschauer, seit)
        f = fenster(bot)
        b = texte(f).get(platz(f, "ModB"), "")
        FIND.test("Linksklick nimmt einen oeffentlichen herunter: wieder privat, und der Besitzer erfaehrt es",
                  "Private" in b and gesagt(dort, rf"{BOT_NAME} took your spot ModB down"), f"{b} / {zeige(dort)}")
        seit = zuschauer.mark()
        klick(bot, platz(f, "ModA"), mode=1)
        dort = zeilen_seit(zuschauer, seit)
        f = fenster(bot)
        FIND.test("Umschalt-Klick loescht einen Punkt, und der Besitzer erfaehrt es",
                  platz(f, "ModA") is None and gesagt(dort, rf"{BOT_NAME} deleted your spot ModA"),
                  f"{list(texte(f).values())} / {zeige(dort)}")

        klick(bot, 49)
        f = fenster(bot)
        FIND.test("Der Knopf der Einschraenkung (49) oeffnet ihre Seite",
                  f"Restrict the spots of {ZUSCHAUER_NAME}" in (f.get("title") or "")
                  and "Allowed" in item_text(f.get("slots", {}).get("11")), str(f.get("title")))
        klick(bot, 11)
        klick(bot, 13)
        f = fenster(bot)
        FIND.test("Anwaehlen: neue Punkte und oeffentlich machen sind nicht erlaubt, teilen schon",
                  "Not allowed" in item_text(f.get("slots", {}).get("11"))
                  and "Not allowed" in item_text(f.get("slots", {}).get("13"))
                  and "Allowed" in item_text(f.get("slots", {}).get("15"))
                  and "no new spots, nothing public" in item_text(f.get("slots", {}).get("31")),
                  item_text(f.get("slots", {}).get("31")))
        seit = zuschauer.mark()
        klick(bot, 31)
        dort = zeilen_seit(zuschauer, seit)
        f = fenster(bot)
        FIND.test("Bestaetigt: er erfaehrt es, und der Knopf glaenzt",
                  gesagt(dort, rf"{BOT_NAME} restricted your spots: no new spots, nothing public for good")
                  and (f.get("slots", {}).get("49") or {}).get("glint") is True, zeige(dort))
        bot.call("close_window", wait=10)
        zeilen = befehl(zuschauer, "/cam spot set ModD")
        FIND.test("Eingeschraenkt: keinen neuen Punkt speichern",
                  gesagt(zeilen, r"You may not save new spots for good"), zeige(zeilen))
        zeilen = befehl(zuschauer, "/cam spot share ModC all")
        FIND.test("Eingeschraenkt: nichts oeffentlich machen",
                  gesagt(zeilen, r"You may not make spots public for good"), zeige(zeilen))
        zeilen = befehl(zuschauer, "/cam spot set ModC") + befehl(zuschauer, f"/cam spot share ModB {BOT_NAME}")
        FIND.test("Verschieben und teilen darf er weiter", gesagt(zeilen, r"Spot ModC moved here")
                  and gesagt(zeilen, rf"offered|{BOT_NAME}"), zeige(zeilen))

        liste, f = seine_punkte()
        eintrag = texte(liste).get(platz(liste, ZUSCHAUER_NAME), "")
        FIND.test("In der Liste steht die Einschraenkung unter ihm",
                  "Restricted: no new spots, nothing public for good" in eintrag, eintrag)
        klick(bot, 49)
        seit = zuschauer.mark()
        klick(bot, 35)
        dort = zeilen_seit(zuschauer, seit)
        bot.call("close_window", wait=10)
        zeilen = befehl(zuschauer, "/cam spot set ModD")
        FIND.test("Der Milcheimer hebt sie auf: er erfaehrt es und darf wieder speichern",
                  gesagt(dort, rf"{BOT_NAME} lifted the restriction of your spots")
                  and gesagt(zeilen, r"Spot ModD saved"), f"{zeige(dort)} / {zeige(zeilen)}")

        zuschauer.stop()
        time.sleep(2.0)
        liste, f = seine_punkte()
        FIND.test("Offline steht er weiter in der Liste, mit seinen Punkten",
                  platz(liste, ZUSCHAUER_NAME) is not None and platz(f, "ModD") is not None,
                  f"{list(texte(liste).values())} / {list(texte(f).values())}")
        seit = bot.mark()
        for name in ("ModD", "ModC", "ModB"):
            wo = platz(fenster(bot), name)
            if wo is not None:
                klick(bot, wo, mode=1)
        hier = zeilen_seit(bot, seit)
        bot.call("close_window", wait=10)
        text = datei.read_text(encoding="utf-8") if datei.exists() else ""
        FIND.test("Offline loescht der Umschalt-Klick seine Punkte aus seiner Datei",
                  gesagt(hier, rf"Spot ModD of {ZUSCHAUER_NAME} deleted") and "ModD" not in text
                  and "ModB" not in text, f"{zeige(hier)} / {text[-300:]}")
    finally:
        try:
            bot.call("close_window", wait=10)
            if zuschauer.proc is not None and zuschauer.proc.poll() is None:
                spots_loeschen(zuschauer, "ModA", "ModB", "ModC", "ModD")
                zuschauer.stop()
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der Moderation: {exc}")


def naehe_checks(env, bot):
    """cam-safety.nearby-players: kein Start des Cam-Modus, solange ein anderer
    Spieler naeher ist als nearby-radius.

    Voreingestellt ist die Sperre aus, mit 16 Bloecken - nachgesehen in der
    ausgelieferten Datei; ein zweiter Spieler drei Bloecke daneben haelt dann
    niemanden auf. Eingeschaltet wird /cam abgelehnt, und die Meldung sagt,
    von wem man wie weit weg muss: 16 Bloecke, 13 mehr. Zwanzig Bloecke weg
    haelt er nicht mehr auf, mit nearby-radius: 25 schon - 5 mehr. Fuer den
    zweiten Spieler gilt dasselbe neben dem Bot. Ein Spieler im Cam-Modus
    zaehlt nicht, und ohne cam-safety.enabled gibt es auch diese Sperre
    nicht.
    """
    if not FIND.test("Cam-Modus ist vor dem Test der Kampfsperre aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    console(env, f"fill {_floor(x) - 1} {y} {_floor(z) - 1} {_floor(x) + 21} {y + 3} {_floor(z) + 1} minecraft:air",
            pause=0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)

    def zuschauer_bei(abstand):
        console(env, f"tp {ZUSCHAUER_NAME} {x + abstand} {y} {z}", pause=1.0)

    def startet():
        """Ob /cam den Bot jetzt in den Cam-Modus bringt, und was es dazu sagt."""
        hinstellen(bot, x, y, z)
        zeilen = befehl(bot, "/cam")
        an = spielmodus_ist(bot, "adventure")
        cam_off(bot)
        return an, zeilen

    try:
        FIND.test("Voreingestellt steht cam-safety.nearby-players: false mit nearby-radius: 16.0 da",
                  re.search(r"(?m)^\s*nearby-players:\s*false\s*\n(?:\s*#.*\n)*\s*nearby-radius:\s*16\.0\s*$",
                            ausgeliefert(env)) is not None, "")
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer die Kampfsperre kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        zuschauer_bei(3)
        an, zeilen = startet()
        FIND.test("Voreingestellt haelt ein Spieler drei Bloecke daneben niemanden auf", an, zeige(zeilen))

        set_option(env, "nearby-players", "true")
        an, zeilen = startet()
        FIND.test("nearby-players: true - mit einem Spieler drei Bloecke daneben startet /cam nicht, "
                  "die Meldung sagt, wie weit man weg muss",
                  not an and gesagt(zeilen, rf"too close to {ZUSCHAUER_NAME} to start cam mode\. "
                                            r"Get at least 16 blocks away \(13 more\)"), zeige(zeilen))
        zuschauer_bei(20)
        an, zeilen = startet()
        FIND.test("nearby-players: true - zwanzig Bloecke weg haelt er nicht mehr auf", an, zeige(zeilen))
        set_option(env, "nearby-radius", "25.0")
        an, zeilen = startet()
        FIND.test("nearby-radius: 25 - zwanzig Bloecke weg haelt er auf",
                  not an and gesagt(zeilen, r"Get at least 25 blocks away \(5 more\)"), zeige(zeilen))
        set_option(env, "nearby-radius", "16.0")

        # Die Sperre gilt fuer den zweiten Spieler genauso: Neben dem Bot kommt
        # er nur in den Cam-Modus, solange der Bot selbst Kamera ist.
        zuschauer_bei(3)
        hinstellen(bot, x, y, z)
        zeilen = befehl(zuschauer, "/cam")
        FIND.test("nearby-players: true - auch der zweite Spieler startet nicht neben dem Bot",
                  gesagt(zeilen, rf"too close to {BOT_NAME} to start cam mode"), zeige(zeilen))
        zuschauer_bei(20)
        cam_on(bot)
        if FIND.test("Der zweite Spieler ist fuer die Kampfsperre im Cam-Modus",
                     zuschauer_cam_an(env, zuschauer, x + 3, y, z), ""):
            cam_off(bot)
            an, zeilen = startet()
            FIND.test("nearby-players: true - ein Spieler im Cam-Modus zaehlt nicht", an, zeige(zeilen))
            cam_off(zuschauer)
        cam_off(bot)
        zuschauer_bei(3)
        set_option(env, "enabled", "false", "cam-safety")
        an, zeilen = startet()
        FIND.test("cam-safety.enabled: false - auch die Kampfsperre ist aus", an, zeige(zeilen))
    finally:
        try:
            if zuschauer.proc is not None:
                cam_off(zuschauer)
                zuschauer.stop()
            cam_off(bot)
            set_options(env, [("nearby-players", "false"), ("nearby-radius", "16.0"),
                              ("enabled", "true", "cam-safety")])
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test der Kampfsperre: {exc}")


def anstups_checks(env, bot):
    """nudge: Ein anderer Spieler klickt den Koerper mit rechts an, und der
    Spieler im Cam-Modus erfaehrt, dass jemand an seinem Koerper wartet.

    Voreingestellt ist es an, mit 5 Sekunden - nachgesehen in der
    ausgelieferten Datei; fuer den Test sind es 2. Der zweite Spieler steht
    anderthalb Bloecke neben dem Koerper und klickt ihn an: Beim Bot steht
    "<Name> nudged your body!" in der Action-Bar, mit dem Ton von
    sounds.nudge, beim zweiten Spieler "You nudged <Bot>". Der Cam-Modus
    laeuft weiter. Ein zweiter Klick gleich danach stupst nicht noch einmal
    an - beide gehen deshalb in einem Zug hinaus, sonst laege zwischen ihnen
    schon die Zeit der Proben -, einer nach der Wartezeit schon. Mit
    enabled: false kommt nichts, und
    ein Spieler, der selbst im Cam-Modus ist, stupst auch nicht an.

    Geklickt wird, was dem Koerper am naechsten steht - beim Koerpertyp
    armorstand der Ruestungsstaender oder das Mannequin darin, beide gehoeren
    zum Koerper. Nach der Art wird nicht gesucht: mineflayer nennt die Arten
    mit den Daten von 26.1, und die stimmen fuer 26.2 nicht ueberall. Der
    Abschnitt laeuft mit beiden Koerpertypen: Den Ruestungsstaender erreicht
    der Klick ueber andere Ereignisse als das Mannequin.
    """
    if not FIND.test("Cam-Modus ist vor dem Test des Anstupsens aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    console(env, f"fill {_floor(x) - 2} {y} {_floor(z) - 2} {_floor(x) + 3} {y + 3} {_floor(z) + 2} minecraft:air",
            pause=0.5)
    zuschauer = BotClient(env, name=ZUSCHAUER_NAME)

    def anstupsen(mal=1):
        """Den Koerper so oft gleich hintereinander anklicken und sagen, was
        beim Bot und beim zweiten Spieler ankam: (Zeilen beim Bot, Zeilen beim
        zweiten Spieler)."""
        bei_bot = bot.mark()
        bei_ihm = zuschauer.mark()
        for _ in range(mal):
            klicken(zuschauer, "activate_entity", at=[x, y, z], radius=2)
        time.sleep(1.5)
        return zeilen_seit(bot, bei_bot), zeilen_seit(zuschauer, bei_ihm)

    def angestupst(zeilen):
        return sum(1 for t in zeilen if re.search(rf"{ZUSCHAUER_NAME} nudged your body!", t))

    try:
        FIND.test("Voreingestellt steht nudge.enabled: true mit cooldown: 5 da",
                  re.search(r"(?m)^nudge:\s*\n\s*enabled:\s*true\s*\n(?:\s*#.*\n)*\s*cooldown:\s*5\s*$",
                            ausgeliefert(env)) is not None, "")
        set_option(env, "cooldown", "2", "nudge")
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer das Anstupsen kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        console(env, f"tp {ZUSCHAUER_NAME} {x + 1.5} {y} {z} 90 0", pause=1.0)
        hinstellen(bot, x, y, z)
        if not FIND.test("Cam-Modus fuer das Anstupsen gestartet", cam_on(bot), ""):
            return
        bot.call("fly", wait=30, dy=6, timeout=15000)
        time.sleep(1.0)

        seit = toene_marke(bot)
        beim_bot, beim_zuschauer = anstupsen(mal=2)
        FIND.test("nudge: ein Klick auf den Koerper - \"<Name> nudged your body!\" in der Action-Bar",
                  angestupst(beim_bot) >= 1, zeige(beim_bot))
        FIND.test("nudge: ein zweiter Klick gleich danach stupst nicht noch einmal an",
                  angestupst(beim_bot) == 1, zeige(beim_bot))
        FIND.test("sounds.nudge - der Ton dazu", ton_da(bot, seit, "block.note_block.chime"), "")
        FIND.test("nudge: wer anstupst, liest \"You nudged <Bot>\"",
                  gesagt(beim_zuschauer, rf"You nudged {BOT_NAME}, who is in cam mode"), zeige(beim_zuschauer))
        FIND.test("nudge: der Cam-Modus laeuft weiter", spielmodus_ist(bot, "adventure"), "")

        time.sleep(2.0)
        beim_bot, _ = anstupsen()
        FIND.test("nudge: nach cooldown geht es wieder", angestupst(beim_bot) == 1, zeige(beim_bot))

        # Der reload dabei beendet den Cam-Modus.
        set_option(env, "enabled", "false", "nudge")
        hinstellen(bot, x, y, z)
        cam_on(bot)
        bot.call("fly", wait=30, dy=6, timeout=15000)
        time.sleep(1.0)
        beim_bot, beim_zuschauer = anstupsen()
        FIND.test("nudge.enabled: false - der Klick sagt keinem etwas, der Cam-Modus laeuft weiter",
                  angestupst(beim_bot) == 0 and not gesagt(beim_zuschauer, r"You nudged")
                  and spielmodus_ist(bot, "adventure"), f"{zeige(beim_bot)} / {zeige(beim_zuschauer)}")
        set_option(env, "enabled", "true", "nudge")
        hinstellen(bot, x, y, z)
        cam_on(bot)
        bot.call("fly", wait=30, dy=6, timeout=15000)
        if FIND.test("Der zweite Spieler ist fuer das Anstupsen im Cam-Modus",
                     zuschauer_cam_an(env, zuschauer, x + 1.5, y, z), ""):
            time.sleep(2.0)
            beim_bot, _ = anstupsen()
            FIND.test("nudge: wer selbst im Cam-Modus ist, stupst nicht an", angestupst(beim_bot) == 0,
                      zeige(beim_bot))
    finally:
        try:
            if zuschauer.proc is not None:
                cam_off(zuschauer)
                zuschauer.stop()
            cam_off(bot)
            set_options(env, [("enabled", "true", "nudge"), ("cooldown", "5", "nudge")])
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test des Anstupsens: {exc}")


DUNKELHEIT = '{active_effects:[{id:"minecraft:darkness"}]}'


# actionbar-signal fuer den Test: wie ausgeliefert, und dahinter die Prozent,
# an denen der Test die Staerke misst.
FUNK_ZEILE = '" &7| {icon} &7Signal: {quality} &7{percent}%"'


def funk_werte(zeilen):
    """Die Staerke des Signals aus den Zeilen "... | Signal: good 67%"."""
    return [int(m.group(1)) for t in zeilen for m in [re.search(r"Signal: [a-z ]+? (\d+)%", t)] if m]


def funk_wort(zeilen):
    """Das Wort fuer die Staerke aus der letzten Zeile "... | Signal: good 67%"."""
    worte = [m.group(1) for t in zeilen for m in [re.search(r"Signal: ([a-z ]+?) \d+%", t)] if m]
    return worte[-1] if worte else None


def funk_checks(env, bot):
    """camera-mode.max-distance-mode: signal - die Kamera fliegt auf einem
    Funksignal ihres Koerpers, und Bloecke dazwischen schwaechen es.

    Voreingestellt ist fixed, wie bisher - nachgesehen in der ausgelieferten
    Datei. Fuer den Test reicht das Signal 30 Bloecke (max-distance), jeder
    Block dazwischen kostet 5, und nach dem Abreissen bleiben 2 Sekunden.
    Gestellt wird die Kamera mit /tp auf den Boden, dort steht sie, und der
    Abstand ist genau der, den der Test will.

    Am Koerper zeigt die Action-Bar hinter dem Abstand 100 %, zehn Bloecke
    daneben 67 %. Wie das Spiel eine Verbindung zeigt: mit dem Symbol der
    Tab-Liste, ueber Paper als Bild aus dem Atlas gui (icon/ping_5 am Koerper,
    icon/ping_4 bei 67 %, icon/ping_1 bei 17 %), und einem Wort (excellent,
    good, very weak). Ausgeliefert steht actionbar-signal ohne Prozent da;
    der Test haengt sie an, um daran zu messen. Eine Mauer aus drei Bloecken
    Stein dazwischen nimmt 15 mehr:
    17 % - und das Bild bleibt, wie es ist: Das Signal legt keinen Effekt
    darauf, auch keine Dunkelheit. Ohne Mauer sind es wieder 67 %. Drei
    Bloecke vor dem Koerper kostet ein einziger Block Obsidian 25 (signal.
    block-costs): 7 %; einer aus oxidiertem geschnittenem Kupfer, ueber
    "*copper", 12: 50 %; und durch Grundgestein kommt gar nichts (stop). Fuenf
    Bloecke Stein nehmen das ganze Signal: "Signal lost!" mit dem
    Ton von sounds.signal-lost, und nach den zwei Sekunden steht die Kamera
    wieder am Koerper, der Cam-Modus laeuft weiter - ohne den Ton von
    sounds.signal-back, der nur kommt, wer in der Zeit zurueck ins Signal
    kommt; der bleibt, wo er ist. Mit lost: end endet der Cam-Modus, auch
    ohne ihn.
    Eine Grenze, an die man stoesst, gibt es nicht: In freier Luft fliegt die
    Kamera ueber die 30 Bloecke hinaus, bis das Signal sie zurueckholt.

    signal.levels (voreingestellt 80, 60, 40, 20) legt fest, ab wie viel
    Prozent welche Stufe gilt: Mit 95, 85, 50, 10 sind 90 % "good", 67 %
    "medium" und 17 % "weak". Mit 85, 67, 90, 20 ist medium nicht kleiner
    als good: /cam reload meldet das, und es gelten wieder die voreingestellten.
    """
    if not FIND.test("Cam-Modus ist vor dem Test des Funksignals aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    bx, by, bz = _floor(heim[0]), BODEN_Y + 1, _floor(heim[2])
    x, y, z = bx + 0.5, by, bz + 0.5

    def luft():
        console(env, f"fill {bx - 2} {by} {bz - 3} {bx + 36} {by + 5} {bz + 3} minecraft:air", pause=0.5)

    def mauer(von, bis, block="stone"):
        console(env, f"fill {bx + von} {by - 1} {bz - 2} {bx + bis} {by + 4} {bz + 2} minecraft:{block}", pause=0.3)

    def kamera_bei(dx):
        bot.chat(f"/tp {BOT_NAME} {x + dx} {y} {z}")
        time.sleep(1.0)

    def staerke():
        since = bot.mark()
        time.sleep(1.2)
        werte = funk_werte(zeilen_seit(bot, since))
        return werte[-1] if werte else None

    def anzeige():
        """Das Wort und die Symbole, die in der Zeit in der Action-Bar standen."""
        since = bot.mark()
        seit = bot.call("chat_packets", since=10 ** 9).get("count", 0)
        time.sleep(1.2)
        pakete = " ".join(p["text"] for p in bot.call("chat_packets", since=seit).get("packets", []))
        return funk_wort(zeilen_seit(bot, since)), sorted(set(re.findall(r"icon/ping_\w+", pakete)))

    def dunkel():
        return nbt_frage(bot, "@s", DUNKELHEIT)

    def am_koerper():
        pos = bot.server_pos()
        return pos is not None and math.dist(pos, (x, y, z)) < 1.5, pos

    try:
        FIND.test("Voreingestellt steht camera-mode.max-distance-mode: fixed da",
                  re.search(r"(?m)^\s*max-distance-mode:\s*fixed\s*$", ausgeliefert(env)) is not None, "")
        sprache = (env.server / "plugins" / "CamFly" / SPRACHDATEI).read_text(encoding="utf-8")
        FIND.test("Voreingestellt steht actionbar-signal mit Symbol und Wort da, ohne Prozent",
                  re.search(r'(?m)^  actionbar-signal: " &7\| \{icon\} &7Signal: \{quality\}"$', sprache) is not None,
                  "")
        luft()
        set_options(env, [("max-distance-mode", "signal"), ("max-distance", "30.0"),
                          ("lost-seconds", "2", "signal"), ("actionbar-signal", FUNK_ZEILE, "messages")])
        hinstellen(bot, x, y, z)
        if not FIND.test("Cam-Modus mit Funksignal gestartet", cam_on(bot), ""):
            return
        time.sleep(3.0)
        wert = staerke()
        FIND.test("signal: am Koerper volles Signal hinter dem Abstand",
                  wert is not None and wert >= 99, str(wert))
        wort, symbole = anzeige()
        FIND.test("signal: am Koerper \"excellent\" mit dem Symbol icon/ping_5",
                  wort == "excellent" and symbole == ["icon/ping_5"], f"{wort} {symbole}")

        kamera_bei(10)
        wert = staerke()
        FIND.test("signal: zehn Bloecke daneben in freier Luft 67 % (30 Bloecke Reichweite)",
                  wert is not None and 66 <= wert <= 68, str(wert))
        wort, symbole = anzeige()
        FIND.test("signal: bei 67 % \"good\" mit icon/ping_4", wort == "good" and symbole == ["icon/ping_4"],
                  f"{wort} {symbole}")

        mauer(4, 6)
        wert = staerke()
        FIND.test("signal: drei Bloecke Stein dazwischen kosten 15 mehr - 17 %",
                  wert is not None and 16 <= wert <= 18, str(wert))
        wort, symbole = anzeige()
        FIND.test("signal: bei 17 % \"very weak\" mit icon/ping_1", wort == "very weak" and symbole == ["icon/ping_1"],
                  f"{wort} {symbole}")
        FIND.test("signal: auch bei 17 % kein Effekt auf dem Bild - keine Dunkelheit", not dunkel(), "")
        luft()
        wert = staerke()
        FIND.test("signal: ohne Mauer wieder 67 %", wert is not None and 66 <= wert <= 68, str(wert))

        # --- signal.block-costs: drei Bloecke vor dem Koerper, einer dazwischen ---
        text = ausgeliefert(env)
        FIND.test("Voreingestellt kosten in signal.block-costs bedrock stop, obsidian 25 und \"*copper\" 12",
                  all(re.search(muster, text) for muster in (r"(?m)^    bedrock: stop$", r"(?m)^    obsidian: 25\.0$",
                                                              r'(?m)^    "\*copper": 12\.0$')), "")
        kamera_bei(3)
        mauer(1, 1, "obsidian")
        wert = staerke()
        FIND.test("signal.block-costs: ein Block Obsidian kostet 25 statt 5 - 7 %",
                  wert is not None and 6 <= wert <= 8, str(wert))
        luft()
        mauer(1, 1, "oxidized_cut_copper")
        wert = staerke()
        FIND.test("signal.block-costs: \"*copper\" - ein Block oxidiertes geschnittenes Kupfer kostet 12 - 50 %",
                  wert is not None and 49 <= wert <= 51, str(wert))
        luft()
        since = bot.mark()
        mauer(1, 1, "bedrock")
        weg = bot.expect(r"Signal lost! Fly back within", since, 3000)
        luft()
        FIND.test("signal.block-costs: bedrock: stop - durch einen einzigen Block kommt gar kein Signal", bool(weg),
                  zeige(zeilen_seit(bot, since)[-3:]))
        time.sleep(1.5)
        kamera_bei(10)

        seit = toene_marke(bot)
        since = bot.mark()
        mauer(3, 7)
        weg = bot.expect(r"Signal lost! Fly back within", since, 4000)
        FIND.test("signal: fuenf Bloecke Stein nehmen das ganze Signal - \"Signal lost!\" in der Action-Bar",
                  bool(weg), zeige(zeilen_seit(bot, since)[-3:]))
        FIND.test("sounds.signal-lost - der Ton dazu", ton_da(bot, seit, "block.conduit.deactivate"), "")
        zurueck = bot.expect(r"Signal lost: back at your body", since, 5000)
        da, pos = am_koerper()
        FIND.test("signal.lost: back - nach lost-seconds steht die Kamera wieder am Koerper",
                  bool(zurueck) and da, str(pos))
        time.sleep(1.0)
        FIND.test("signal.lost: back - am Koerper ist das Signal wieder da, aber ohne sounds.signal-back",
                  not ton_da(bot, seit, "block.conduit.activate"), "")
        FIND.test("signal.lost: back - der Cam-Modus laeuft weiter", spielmodus_ist(bot, "adventure"), "")

        set_option(env, "lost-seconds", "4", "signal")
        cam_on(bot)
        since = bot.mark()
        kamera_bei(10)
        bot.expect(r"Signal lost! Fly back within", since, 3000)
        seit = toene_marke(bot)
        kamera_bei(1)
        time.sleep(4.5)
        pos = bot.server_pos()
        z_ = zeilen_seit(bot, since)
        FIND.test("signal: wer rechtzeitig zurueck ins Signal kommt, bleibt, wo er ist",
                  not gesagt(z_, r"back at your body") and pos is not None and abs(pos[0] - (x + 1)) < 0.6,
                  f"{pos} - {zeige(z_[-3:])}")
        FIND.test("sounds.signal-back - der Ton, wenn das Signal rechtzeitig wieder da ist",
                  ton_da(bot, seit, "block.conduit.activate"), "")

        set_option(env, "lost", "end", "signal")
        cam_on(bot)
        seit = toene_marke(bot)
        since = bot.mark()
        kamera_bei(10)
        ende = bot.expect(r"Cam mode ended: the signal was lost", since, 8000)
        FIND.test("signal.lost: end - der Cam-Modus endet",
                  bool(ende) and not spielmodus_ist(bot, "adventure"), zeige(zeilen_seit(bot, since)[-3:]))
        FIND.test("signal.lost: end - mit dem Ton von sounds.end-forced, ohne sounds.signal-back",
                  ton_da(bot, seit, "block.respawn_anchor.deplete") and not ton_da(bot, seit, "block.conduit.activate"),
                  "")

        set_options(env, [("lost", "back", "signal"), ("lost-seconds", "2", "signal")])
        luft()
        hinstellen(bot, x, y, z)
        cam_on(bot)
        kamera_bei(25)
        since = bot.mark()
        bot.call("fly", wait=30, dx=8, timeout=5000)
        draussen = bot.server_pos()
        time.sleep(3.0)
        z_ = zeilen_seit(bot, since)
        FIND.test("signal: keine Grenze - in freier Luft ueber die 30 Bloecke hinaus",
                  draussen is not None and draussen[0] - x > 30.5 and not gesagt(z_, r"cannot move further"),
                  f"{draussen} - {zeige(z_[-3:])}")
        FIND.test("signal: dort holt das Signal die Kamera zurueck", gesagt(z_, r"back at your body")
                  and am_koerper()[0], zeige(z_[-3:]))

        # --- signal.levels: ab wie viel Prozent welche Stufe ---
        FIND.test("Voreingestellt stehen signal.levels mit 80, 60, 40 und 20 da",
                  re.search(r"(?m)^  levels:\n    excellent: 80\n    good: 60\n    medium: 40\n    weak: 20$",
                            ausgeliefert(env)) is not None, "")
        set_options(env, [("excellent", "95", "signal"), ("good", "85", "signal"), ("medium", "50", "signal"),
                          ("weak", "10", "signal")])
        luft()
        hinstellen(bot, x, y, z)
        cam_on(bot)
        kamera_bei(3)
        wort, symbole = anzeige()
        FIND.test("signal.levels 95/85/50/10: bei 90 % \"good\" mit icon/ping_4",
                  wort == "good" and symbole == ["icon/ping_4"], f"{wort} {symbole}")
        kamera_bei(10)
        wort, symbole = anzeige()
        FIND.test("signal.levels 95/85/50/10: bei 67 % \"medium\" mit icon/ping_3",
                  wort == "medium" and symbole == ["icon/ping_3"], f"{wort} {symbole}")
        mauer(4, 6)
        wort, symbole = anzeige()
        FIND.test("signal.levels 95/85/50/10: bei 17 % \"weak\" mit icon/ping_2",
                  wort == "weak" and symbole == ["icon/ping_2"], f"{wort} {symbole}")
        luft()
        vorher = len(server_log(env))
        set_options(env, [("excellent", "85", "signal"), ("good", "67", "signal"), ("medium", "90", "signal"),
                          ("weak", "20", "signal")])
        log = strip_colors(server_log(env)[vorher:])
        FIND.test("signal.levels 85/67/90/20: medium nicht kleiner als good - /cam reload meldet den Fehler",
                  "The values of signal.levels have to go down" in log
                  and "excellent 85, good 67, medium 90, weak 20" in log, log.strip()[-300:])
        hinstellen(bot, x, y, z)
        cam_on(bot)
        kamera_bei(10)
        wort, symbole = anzeige()
        FIND.test("signal.levels 85/67/90/20: es gelten 80, 60, 40, 20 - bei 67 % \"good\"",
                  wort == "good" and symbole == ["icon/ping_4"], f"{wort} {symbole}")
    finally:
        try:
            cam_off(bot)
            luft()
            set_options(env, [("max-distance-mode", "fixed"), ("max-distance", "100.0"),
                              ("lost", "back", "signal"), ("lost-seconds", "3", "signal"),
                              ("actionbar-signal", '" &7| {icon} &7Signal: {quality}"', "messages"),
                              ("excellent", "80", "signal"), ("good", "60", "signal"), ("medium", "40", "signal"),
                              ("weak", "20", "signal")])
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Test des Funksignals: {exc}")


def herzen_checks(env, bot):
    """body.health-visible: die Herzen des Spielers am Namen ueber dem Koerper.

    Voreingestellt ist es aus, und es stuende ueber dem Namen, mit gelben
    Herzen und weiteren Zeilen darueber - nachgesehen in der ausgelieferten
    Datei -, und ueber dem Koerper steht nur der Name. Eingeschaltet stehen
    dort so viele Herzen, wie der Server dem Bot gerade gibt, als Bilder
    seiner Leiste: 11 Leben sind fuenf ganze (hud/heart/full) und ein
    halbes (hud/heart/half). Voreingestellt stehen sie ueber dem Namen, mit
    health-position: below darunter. Damit es weniger als volle sind, nimmt
    /damage ihm vorher neun; cam-safety ist dafuer aus, und die Regeneration
    steht still - aus der Saettigung des Testservers waeren die neun Leben
    sonst in zwei, drei Sekunden wieder da, noch ehe der Cam-Modus laeuft.
    Bekommt er im Cam-Modus Leben dazu - hier mit dem Effekt Direktheilung,
    den das Plugin einem Kamera-Spieler laesst -, stehen gleich darauf mehr
    Herzen da.

    Mit health-show-missing steht jedes Herz wie in der Leiste auf einem
    leeren: hinten ein TextDisplay mit dem Namen, dem Hintergrund und zehn
    leeren Herzen (hud/heart/container), davor ein zweites ohne Hintergrund
    mit den roten und, wo eins fehlt, einem leeren - ohne den Namen, der
    sonst doppelt dastuende, unter wie ueber den Herzen. Beide gehen mit dem
    Cam-Modus wieder weg. Mit Absorption IV - zehn gelbe Herzen - stehen die gelben
    (hud/heart/absorbing_full) hinter den roten, und weil es mehr als zehn
    sind, stehen sie in einer zweiten Zeile: voreingestellt darueber, also
    im Text vorn, mit health-rows-upward: false darunter. Mit
    health-show-absorption: false gibt es keine gelben. Mit
    body.name-visible: false stehen die Herzen allein da.

    Gezaehlt werden die Bilder im Text, den /data ausgibt; wo sie stehen,
    sagt ihre Stelle vor oder hinter dem Namen. Die Bilder gibt es nur auf
    Paper, das Plugin schreibt sie dort als Komponenten.
    """
    vorn = "@e[type=minecraft:text_display,distance=..4,limit=1,sort=nearest,nbt={default_background:0b}]"
    hinten = "@e[type=minecraft:text_display,distance=..4,limit=1,sort=nearest,nbt={default_background:1b}]"

    def halbe():
        text = daten_von(bot, "@s", "Health")
        m = re.search(r"(-?[\d.]+)f?\s*$", text or "")
        return math.ceil(float(m.group(1))) if m else None

    def zeigt(text, halb, leer=0):
        return (bool(text) and halb is not None and text.count("hud/heart/full") == halb // 2
                and text.count("hud/heart/half") == halb % 2 and text.count("hud/heart/container") == leer)

    def oben(text):
        return bool(text) and "'s Body" in text and text.find("hud/heart/") < text.find("'s Body")

    modus = next((m for m in ("survival", "creative") if spielmodus_ist(bot, m)), "survival")
    try:
        cam_off(bot)
        text = ausgeliefert(env)
        FIND.test("Voreingestellt steht body.health-visible: false, health-position: above, health-show-missing: "
                  "false, health-show-absorption: true und health-rows-upward: true da",
                  all(re.search(rf"(?m)^\s*{k}:\s*{v}\s*$", text) is not None
                      for k, v in (("health-visible", "false"), ("health-position", "above"),
                                   ("health-show-missing", "false"), ("health-show-absorption", "true"),
                                   ("health-rows-upward", "true"))), "")
        set_options(env, [("enabled", "false", "cam-safety"), ("name-visible", "false", "camera-mode")])
        console(env, "gamerule natural_health_regeneration false", pause=0.3)
        spielmodus_setzen(bot, "survival")
        bot.chat("/damage @s 9")
        time.sleep(0.5)
        if not FIND.test("Cam-Modus fuer die Herzen gestartet", cam_on(bot), ""):
            return
        vorher = halbe()
        text = daten_von(bot, NAME, "text")
        FIND.test("body.health-visible: false - ueber dem Koerper steht nur der Name",
                  bool(text) and f"{BOT_NAME}'s Body" in text and "hud/heart/" not in text, text or "keine Antwort")

        set_option(env, "health-visible", "true")
        cam_on(bot)
        text = daten_von(bot, NAME, "text")
        FIND.test("body.health-visible: true - so viele ganze und halbe Herzen der Leiste, wie er hat",
                  zeigt(text, vorher), f"{vorher} halbe Herzen - {text or 'keine Antwort'}")
        FIND.test("body.health-position: above - voreingestellt ueber dem Namen", oben(text), text or "")
        bot.chat("/effect give @s minecraft:instant_health 1 0 true")
        time.sleep(1.0)
        danach = halbe()
        text = daten_von(bot, NAME, "text")
        FIND.test("body.health-visible: bekommt er im Cam-Modus Leben dazu, stehen gleich mehr Herzen da",
                  danach != vorher and zeigt(text, danach), f"{vorher} -> {danach} halbe - {text or ''}")

        set_option(env, "health-position", "below")
        cam_on(bot)
        text = daten_von(bot, NAME, "text")
        FIND.test("body.health-position: below - die Herzen stehen unter dem Namen",
                  bool(text) and "'s Body" in text and not oben(text), text or "keine Antwort")

        set_option(env, "health-show-missing", "true")
        cam_on(bot)
        anzahl_namen = anzahl(bot, "@e[type=minecraft:text_display,distance=..4]")
        hinten_text = daten_von(bot, hinten, "text")
        vorn_text = daten_von(bot, vorn, "text")
        fehlen = 10 - (danach + 1) // 2 if danach is not None else 0
        FIND.test("body.health-show-missing: zwei TextDisplays, eins vor dem anderen", anzahl_namen == 2,
                  f"{anzahl_namen} TextDisplays")
        FIND.test("body.health-show-missing: hinten in jedem Platz ein leeres Herz",
                  bool(hinten_text) and hinten_text.count("hud/heart/container") == 10
                  and "hud/heart/full" not in hinten_text, hinten_text or "keine Antwort")
        FIND.test("body.health-show-missing: vorn die roten Herzen und ein leeres fuer jedes fehlende",
                  zeigt(vorn_text, danach, leer=fehlen), vorn_text or "keine Antwort")
        FIND.test("body.health-show-missing: vorn steht der Name nicht noch einmal (Herzen unter dem Namen)",
                  bool(vorn_text) and "'s Body" not in vorn_text and "'s Body" in (hinten_text or ""),
                  vorn_text or "keine Antwort")
        set_option(env, "health-position", "above")
        cam_on(bot)
        hinten_text = daten_von(bot, hinten, "text")
        vorn_text = daten_von(bot, vorn, "text")
        FIND.test("body.health-show-missing: vorn steht der Name nicht noch einmal (Herzen ueber dem Namen)",
                  zeigt(vorn_text, danach, leer=fehlen) and "'s Body" not in vorn_text and oben(hinten_text),
                  vorn_text or "keine Antwort")
        cam_off(bot)
        time.sleep(1.0)
        FIND.test("body.health-show-missing: beide TextDisplays gehen mit dem Cam-Modus", not name_da(bot), "")

        set_option(env, "health-show-missing", "false")
        bot.chat("/effect give @s minecraft:absorption 120 4 true")
        time.sleep(0.5)
        cam_on(bot)
        text = daten_von(bot, NAME, "text")
        FIND.test("body.health-show-absorption: die zehn gelben Herzen der Absorption IV stehen da",
                  bool(text) and text.count("hud/heart/absorbing_full") == 10, text or "keine Antwort")
        FIND.test("body.health-rows-upward: true - die zweite Zeile steht ueber der ersten",
                  bool(text) and -1 < text.find("absorbing") < text.find("hud/heart/full"), text or "")

        set_option(env, "health-rows-upward", "false")
        cam_on(bot)
        text = daten_von(bot, NAME, "text")
        FIND.test("body.health-rows-upward: false - die zweite Zeile steht unter der ersten",
                  bool(text) and -1 < text.find("hud/heart/full") < text.find("absorbing"), text or "")

        set_option(env, "health-show-absorption", "false")
        cam_on(bot)
        text = daten_von(bot, NAME, "text")
        FIND.test("body.health-show-absorption: false - keine gelben Herzen",
                  bool(text) and "absorbing" not in text and zeigt(text, danach), text or "keine Antwort")

        set_option(env, "name-visible", "false", "body")
        cam_on(bot)
        text = daten_von(bot, NAME, "text")
        FIND.test("body.health-visible mit name-visible: false - die Herzen stehen allein da",
                  zeigt(text, danach) and "'s Body" not in (text or ""), text or "keine Antwort")
    finally:
        cam_off(bot)
        set_options(env, [("health-visible", "false"), ("health-position", "above"), ("name-visible", "true", "body"),
                          ("health-show-missing", "false"), ("health-show-absorption", "true"),
                          ("health-rows-upward", "true"),
                          ("enabled", "true", "cam-safety"), ("name-visible", "true", "camera-mode")])
        console(env, "gamerule natural_health_regeneration true", pause=0.3)
        bot.chat(f"/effect clear {BOT_NAME}")
        spielmodus_setzen(bot, modus)


def wartezeit_checks(env, bot):
    """time-limit: die Wartezeit nach dem Cam-Modus - ihre Bossbar, ihre
    Einteilung und wo sie liegt.

    Mit cooldowns-enabled und 30 Sekunden Wartezeit bringt ein Ende durch
    /cam reload keine Wartezeit, der Cam-Modus startet gleich wieder. Nach
    einem /cam zeigt eine Bossbar "Cam mode again in: ...", voreingestellt
    ohne Einteilung, und ein neuer Start wird abgelehnt. /cam stats nennt die
    Wartezeit, und sie steht in der Datei des Bots unter cooldown.started.
    Voreingestellt liegt sie dort (storage: file): Ein /cam reload laesst
    sie weiterlaufen, ebenso das Neuanmelden, und die Bossbar ist danach
    wieder da. Mit bossbar-notches: 12 hat sie zwoelf Abschnitte, mit
    show-bossbar-cooldown: false gibt es keine. Mit storage: memory zaehlt
    die Datei nicht, und ein /cam reload vergisst, was im Speicher liegt. Mit
    3 Sekunden ist sie bald vorbei: "Cam mode available again", und die
    Bossbar verschwindet.
    """
    try:
        cam_off(bot)
        set_options(env, [("cooldowns-enabled", "true"), ("cooldown-seconds", "30", "time-limit")])
        if not FIND.test("Cam-Modus fuer die Wartezeit gestartet", cam_on(bot), ""):
            return
        console(env, "cam reload", pause=2)
        an = cam_on(bot)
        FIND.test("time-limit: ein Ende durch /cam reload bringt keine Wartezeit", an, "")
        cam_off(bot)
        time.sleep(1.5)
        treffer, titel = bossbar_suche(bot, r"Cam mode again in: \d")
        FIND.test("show-bossbar-cooldown: nach dem Ende zeigt eine Bossbar die Wartezeit", bool(treffer), str(titel))
        FIND.test("time-limit.bossbar-notches: voreingestellt 0 - die Bossbar ohne Einteilung",
                  bool(treffer) and treffer[0].get("dividers") == 0, str(treffer))
        z = befehl(bot, "/cam")
        FIND.test("time-limit: waehrend der Wartezeit wird ein Start abgelehnt",
                  gesagt(z, r"can start cam mode again in") and not gesagt(z, r"[Cc]am mode activated"), zeige(z))
        z = befehl(bot, "/cam stats")
        FIND.test("/cam stats nennt die Wartezeit", gesagt(z, r"Cam mode again in: \d"), zeige(z))
        datei = spielerdatei(env)
        FIND.test("time-limit.storage: file - die Wartezeit steht in der Datei unter playerdata/stats",
                  datei is not None and re.search(r"(?m)^cooldown:\s*\n\s*started: \d+",
                                                  datei.read_text(encoding="utf-8")) is not None, str(datei))
        console(env, "cam reload", pause=2)
        z = befehl(bot, "/cam")
        FIND.test("time-limit.storage: file - nach /cam reload laeuft die Wartezeit weiter",
                  gesagt(z, r"can start cam mode again in"), zeige(z))
        if FIND.test("Bot meldet sich waehrend der Wartezeit neu an", neu_anmelden(bot), ""):
            treffer, titel = bossbar_suche(bot, r"Cam mode again in")
            FIND.test("show-bossbar-cooldown: nach dem Betreten ist die Bossbar wieder da", bool(treffer), str(titel))

        set_option(env, "bossbar-notches", "12", "time-limit")
        treffer, titel = bossbar_suche(bot, r"Cam mode again in")
        FIND.test("time-limit.bossbar-notches: 12 - die Bossbar in zwoelf Abschnitten",
                  bool(treffer) and treffer[0].get("dividers") == 3, str(treffer))
        set_option(env, "show-bossbar-cooldown", "false")
        treffer, titel = bossbar_suche(bot, r"Cam mode again in")
        FIND.test("show-bossbar-cooldown: false - keine Bossbar", not treffer, str(titel))

        set_options(env, [("show-bossbar-cooldown", "true"), ("storage", "memory", "time-limit")])
        an = cam_on(bot)
        FIND.test("time-limit.storage: memory - die Wartezeit in der Datei zaehlt nicht", an, "")
        cam_off(bot)
        z = befehl(bot, "/cam")
        FIND.test("time-limit.storage: memory - nach dem Ende wartet er wie sonst",
                  gesagt(z, r"can start cam mode again in"), zeige(z))
        console(env, "cam reload", pause=2)
        an = cam_on(bot)
        FIND.test("time-limit.storage: memory - /cam reload vergisst die Wartezeit", an, "")
        cam_off(bot)

        set_option(env, "cooldown-seconds", "3", "time-limit")
        cam_on(bot)
        since = bot.mark()
        cam_off(bot)
        vorbei = bot.expect(r"Cam mode available again", since, 8000)
        time.sleep(1.0)
        treffer, titel = bossbar_suche(bot, r"Cam mode again in")
        FIND.test("time-limit: ist die Wartezeit vorbei, kommt \"Cam mode available again\" und die Bossbar geht",
                  bool(vorbei) and not treffer, str(titel))
    finally:
        cam_off(bot)
        set_options(env, [("cooldowns-enabled", "false"), ("cooldown-seconds", "120", "time-limit"),
                          ("show-bossbar-cooldown", "true"), ("bossbar-notches", "0", "time-limit"),
                          ("storage", "file", "time-limit")])


def akku_speicher_checks(env, bot):
    """battery.bossbar-notches und battery.storage.

    Voreingestellt hat die Bossbar des Akkus zehn Abschnitte, mit
    bossbar-notches: 6 sechs. Der Akku haelt 5 Sekunden und laedt so gut wie
    nicht: Nach dem Flug ist er leer. Voreingestellt liegt der Ladestand in
    der Datei (storage: file), nach /cam reload ist er also immer noch leer.
    Mit storage: memory zaehlt die Datei nicht, der Akku ist voll; ist er
    wieder leer, macht ihn ein /cam reload wieder voll. Damit er zu Beginn
    voll ist, laedt er vorher 100 Sekunden in der Sekunde. Ein Start braucht
    nur eine Sekunde: Was nach den beiden ersten Fluegen uebrig ist, rechnet
    das Plugin ab dem Ende des zweiten mit dem neuen, langsamen Laden weiter,
    und das sind nur ein paar Sekunden.
    """
    def leer_fliegen():
        since = bot.mark()
        if not cam_on(bot):
            return False
        return bool(bot.expect(r"battery is empty", since, 12000))

    try:
        cam_off(bot)
        set_options(env, [("flight-limit", "battery"), ("capacity", "5", "battery"),
                          ("recharge-per-second", "100", "battery"), ("min-start", "1", "battery")])
        time.sleep(1.0)
        if not FIND.test("Cam-Modus fuer die Einteilung der Akku-Bossbar gestartet", cam_on(bot), ""):
            return
        treffer, titel = bossbar_suche(bot, r"Battery: \d")
        FIND.test("battery.bossbar-notches: voreingestellt zehn Abschnitte",
                  bool(treffer) and treffer[0].get("dividers") == 2, str(treffer))
        cam_off(bot)
        set_option(env, "bossbar-notches", "6", "battery")
        cam_on(bot)
        treffer, titel = bossbar_suche(bot, r"Battery: \d")
        FIND.test("battery.bossbar-notches: 6 - sechs Abschnitte",
                  bool(treffer) and treffer[0].get("dividers") == 1, str(treffer))
        cam_off(bot)

        set_option(env, "recharge-per-second", "0.01", "battery")
        FIND.test("battery: mit 5 Sekunden ist der Akku nach dem Flug leer", leer_fliegen(), "")
        console(env, "cam reload", pause=2)
        z = befehl(bot, "/cam")
        FIND.test("battery.storage: file - nach /cam reload ist der Akku immer noch leer",
                  gesagt(z, r"battery holds 0 seconds") and not gesagt(z, r"[Cc]am mode activated"), zeige(z))

        set_option(env, "storage", "memory", "battery")
        an = cam_on(bot)
        FIND.test("battery.storage: memory - der Ladestand in der Datei zaehlt nicht, der Akku ist voll", an, "")
        cam_off(bot)
        leer_fliegen()
        z = befehl(bot, "/cam")
        FIND.test("battery.storage: memory - leer ist er auch im Speicher leer",
                  gesagt(z, r"battery holds 0 seconds"), zeige(z))
        console(env, "cam reload", pause=2)
        an = cam_on(bot)
        FIND.test("battery.storage: memory - nach /cam reload ist der Akku wieder voll", an, "")
    finally:
        cam_off(bot)
        set_options(env, [("flight-limit", "false"), ("capacity", "300", "battery"),
                          ("recharge-per-second", "0.5", "battery"), ("min-start", "10", "battery"),
                          ("bossbar-notches", "10", "battery"), ("storage", "file", "battery")])


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

        # --- Unbekannte Schluessel in der config.yml ---
        # Ein vertippter Name, ein ganzer alter Abschnitt und mirror-damage.debug,
        # das absichtlich nicht in der mitgelieferten config.yml steht.
        text = re.sub(r"(?m)^(\s*)max-distance:", r"\g<1>max-distanse:", config_vorher, count=1)
        text = re.sub(r"(?m)^mirror-damage:\n", "mirror-damage:\n  debug: false\n", text, count=1)
        config.write_text(text + '\nmessages:\n  camera-on: "alt"\n', encoding="utf-8")
        vorher = len(server_log(env))
        console(env, "cam reload", pause=2)
        neu = strip_colors(server_log(env)[vorher:])
        FIND.test("Ein vertippter Schluessel wird gemeldet",
                  "Unknown setting camera-mode.max-distanse." in neu, "")
        FIND.test("Texte, die noch in der config.yml stehen, werden als unbekannt gemeldet",
                  "Unknown setting messages." in neu, "")
        FIND.test("Ein unbekannter Abschnitt ist eine Meldung, nicht eine je Zeile darin",
                  "messages.camera-on" not in neu, "")
        FIND.test("mirror-damage.debug gilt nicht als unbekannt",
                  "mirror-damage.debug" not in neu, "")
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
        # Erst vom Happy Ghast herunter: Raeumte das Aufraeumen ihn weg,
        # waehrend der Bot noch auf ihm steht, fiele er sechs Bloecke tief,
        # und cam-safety sperrte /cam danach fuenf Sekunden lang - es sei
        # denn, er darf zufaellig gerade fliegen.
        hinstellen(bot, bx + 0.5, by, bz + 0.5)
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
# in Bloecken. Gemessen liegen beide auf ein Zehntausendstel beieinander,
# sobald die Stoesse im selben Takt ankamen (siehe stoss_takt): Die
# Geschwindigkeit kommt in 26.x als lpVec3 und ist damit ein wenig gerundet,
# der Stoss einer Explosion ohne Cam-Modus nicht.
RUECKSTOSS_TOLERANZ = 0.05

# Wie oft eine Probe hoechstens noch einmal laeuft, wenn ihre beiden Laeufe
# die Stoesse in einem anderen Takt bekamen, siehe rueckstoss_im_takt.
# Gemessen kam das in fuenf von 160 Laeufen mit einer Windkugel vor.
RUECKSTOSS_NACHLAEUFE = 4

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

    Gibt zurueck, wo er liegen bleibt, vom Ziel aus gerechnet, die erste
    Geschwindigkeit, die er bekommt, und den Takt, in dem die Stoesse
    ankamen (siehe stoss_takt) - oder None, wenn der Cam-Modus gar nicht
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
            "pakete": len(log["velocity"]),
            "takt": stoss_takt(log)}


def stoss_takt(log):
    """In welchem Physik-Tick des Bots jedes Paket mit einem Stoss ankam, vom
    ersten an gezaehlt: jedes mit einer Geschwindigkeit und jede Explosion,
    die ihn stoesst.

    Der Bot rechnet seine Ticks nach der eigenen Uhr, alle 50 ms, und die
    Pakete kommen nach der des Servers. Zwei Pakete aus zwei Ticks des Servers
    liegen deshalb meist einen Tick des Bots auseinander - aber nicht immer:
    Kommt das erste knapp nach einem seiner Ticks an und das zweite knapp vor
    dem naechsten, liegt keiner dazwischen, und holt er einen verspaeteten
    Tick nach, liegen zwei dazwischen. Er fliegt dann einen Tick weniger oder
    mehr mit dem ersten Stoss allein und landet ganz woanders, ohne Cam-Modus
    genauso wie mit. Eine Windkugel stoesst mit zwei solchen Paketen, erst mit
    ihrer Explosion, einen Tick spaeter mit dem Treffer - gemessen landete der
    Bot nach (0, 1) bei -4,83, nach (0, 0) bei -2,81.
    """
    pakete = sorted([(v["t"], v["tick"]) for v in log["velocity"]]
                    + [(e["t"], e["tick"]) for e in log["explosion"]
                       if e["knockback"] and any(e["knockback"])])
    if not pakete:
        return ()
    return tuple(tick - pakete[0][1] for _, tick in pakete)


def _zahlen(werte):
    return "-" if werte is None else "(" + ", ".join(f"{w:.4f}" for w in werte) + ")"


def gleicher_landeplatz(ohne, mit):
    """Ob der Bot beide Male an derselben Stelle liegen blieb."""
    return max(abs(a - b) for a, b in zip(ohne["weg"], mit["weg"])) <= RUECKSTOSS_TOLERANZ


def rueckstoss_im_takt(ohne, mit, nochmal):
    """Die Paare aus einem Lauf ohne und einem mit Cam-Modus, an denen die
    Probe entschieden wird.

    Das ist das erste Paar - ausser der Bot liegt dort an zwei Stellen, und
    die Stoesse kamen beide Male in gleich vielen Paketen, aber in einem
    anderen Takt an (siehe stoss_takt). Dann hat der Bot sie verschieden
    verrechnet, und der Vergleich sagt nichts ueber das Plugin. Die Probe
    laeuft dann noch einmal, abwechselnd mit und ohne Cam-Modus, bis ein
    Paar im selben Takt dabei ist; entschieden wird an allen Paaren im
    selben Takt. Kam im Cam-Modus ein Paket mehr oder weniger an, laeuft
    nichts nach: Das ist ein Stoss zu viel oder zu wenig.

    `ohne` sind die Laeufe ohne Cam-Modus und bekommen jeden Nachlauf ohne
    ihn dazu, `nochmal(cam)` faehrt einen weiteren Lauf. Gibt die Paare
    zurueck und wie oft nachgefahren wurde.
    """
    erstes = (ohne[0], mit)
    if (nochmal is None or gleicher_landeplatz(*erstes)
            or len(ohne[0]["takt"]) != len(mit["takt"]) or ohne[0]["takt"] == mit["takt"]):
        return [erstes], 0
    mits = [mit]

    def im_takt():
        return [(o, m) for m in mits for o in ohne if o["takt"] == m["takt"]]

    nachgefahren = 0
    while not im_takt() and nachgefahren < RUECKSTOSS_NACHLAEUFE:
        cam = nachgefahren % 2 == 0
        Log.detail(f"Takt ohne Cam-Modus {[o['takt'] for o in ohne]}, mit {[m['takt'] for m in mits]}"
                   f" - noch ein Lauf {'mit' if cam else 'ohne'} Cam-Modus")
        lauf = nochmal(cam)
        nachgefahren += 1
        if lauf is not None and (not cam or lauf["beendet"]):
            (mits if cam else ohne).append(lauf)
    return im_takt() or [erstes], nachgefahren


def rueckstoss_vergleich(name, ohne, mit, wie="auf den Koerper", gegenprobe=True, nochmal=None):
    """Ob der Treffer auf den Koerper den Spieler dorthin stoesst, wo derselbe
    Treffer ihn ohne Cam-Modus hinstoesst.

    Verglichen wird, wo er liegen bleibt: Darin steckt alles - wohin der Stoss
    geht, wie weit er traegt und wie hoch er hebt. Die erste Geschwindigkeit
    steht zum Nachlesen dabei. Die Gegenprobe ohne Cam-Modus steht nur beim
    ersten Vergleich mit ihr als eigene Probe da.

    `ohne` sind die Laeufe ohne Cam-Modus, der erste vorne. Kamen die Stoesse
    in einem anderen Takt an, faehrt `nochmal(cam)` weitere Laeufe, siehe
    rueckstoss_im_takt.
    """
    erster = ohne[0]
    weg_ohne = erster is not None and erster["weg"][0] < -0.3
    if gegenprobe:
        FIND.test(f"{name} ohne Cam-Modus stoesst den Bot weg (Gegenprobe)", weg_ohne,
                  "" if erster is None else f"Weg {_zahlen(erster['weg'])}")
    if not weg_ohne:
        return
    if not FIND.test(f"{name} {wie} beendet den Cam-Modus",
                     mit is not None and mit["beendet"], ""):
        return
    paare, nachgefahren = rueckstoss_im_takt(ohne, mit, nochmal)
    daneben = next((paar for paar in paare if not gleicher_landeplatz(*paar)), None)
    o, m = daneben or paare[0]
    erklaerung = (f"Weg ohne {_zahlen(o['weg'])}, mit {_zahlen(m['weg'])}; "
                  f"erste Geschwindigkeit ohne {_zahlen(o['erste'])}, mit {_zahlen(m['erste'])}")
    if nachgefahren:
        nachlaeufe = f"{nachgefahren} {'Nachlauf' if nachgefahren == 1 else 'Nachlaeufen'}"
        erklaerung += (f"; zuerst kamen die Stoesse im Takt {erster['takt']} ohne und {mit['takt']} mit"
                       f" Cam-Modus an, "
                       + (f"im selben Takt nach {nachlaeufe}" if o["takt"] == m["takt"]
                          else f"auch nach {nachlaeufe} kein Paar im selben Takt"))
    if daneben:
        # Woran es lag: Fing der Flug woanders an, stiess er oben an, kam noch
        # ein Stoss hinterher?
        erklaerung += (f"; Anfang ohne {_zahlen(o['anfang'])}, mit {_zahlen(m['anfang'])}; "
                       f"hoechster Punkt ohne {o['hoehe']:.4f}, mit {m['hoehe']:.4f}; "
                       f"Pakete mit Geschwindigkeit ohne {o['pakete']}, mit {m['pakete']}; "
                       f"Takt ohne {o['takt']}, mit {m['takt']}")
    FIND.test(f"{name} {wie} stoesst den Spieler wie ohne Cam-Modus", daneben is None, erklaerung)


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

    Fallschaden nimmt der Bot fuer die Dauer des Abschnitts keinen. Wer
    hoeher als drei Bloecke fliegt - nach der Windkugel eines Breeze -, nimmt
    ihn sonst beim Aufkommen, und mit ihm kommt ein Paket mit der
    Geschwindigkeit, die der Server gerade von ihm kennt. Welche das ist,
    haengt daran, welche Bewegung des Bots er bis dahin verarbeitet hat:
    gemessen -0,0658, -0,0723 oder -0,0599 zur Seite, und der Bot rutschte
    danach bis zu 0,05 Bloecke weiter oder weniger weit, mit Cam-Modus wie
    ohne.

    Kamen die Stoesse in einem anderen Takt an, laeuft eine Probe noch
    einmal, siehe rueckstoss_im_takt.
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
    bot.chat(f"/attribute {BOT_NAME} minecraft:fall_damage_multiplier base set 0")
    time.sleep(0.5)
    set_option(env, "enabled", "false", "cam-safety")
    damage_mode_umgestellt = False

    def geschoss(kommando):
        """Ein Lauf mit dem Geschoss, das dieses Kommando setzt, mit oder
        ohne Cam-Modus."""
        def lauf(cam):
            return rueckstoss_lauf(env, bot, ziel, cam, lambda: console(env, kommando, pause=0))
        return lauf

    try:
        # Je Probe die Laeufe ohne Cam-Modus, der erste vorne. Ein Nachlauf
        # haengt sich an, und die Proben mit damage-mode: false vergleichen
        # spaeter mit allen.
        ohne_cam = {}
        for name, kommando in rueckstoss_proben(x, y, z):
            lauf = geschoss(kommando)
            ohne_cam[name] = [lauf(False)]
            rueckstoss_vergleich(name, ohne_cam[name], lauf(True), nochmal=lauf)

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

                def schlag(vorbereiten, schlagen):
                    """Ein Lauf mit diesem Schlag, mit oder ohne Cam-Modus."""
                    def lauf(cam):
                        vorbereiten()
                        ergebnis = rueckstoss_lauf(env, bot, ziel, cam, schlagen)
                        schlaeger.call("sprint", on=False)
                        return ergebnis
                    return lauf

                for name, vorbereiten, schlagen in schlaege:
                    lauf = schlag(vorbereiten, schlagen)
                    ohne_cam[name] = [lauf(False)]
                    rueckstoss_vergleich(name, ohne_cam[name], lauf(True), rueckstoss_wie(name),
                                         nochmal=lauf)

                # Ohne Schaden gibt das Plugin den Stoss ganz von Hand weiter -
                # den eines Sprints und den eines Stichs, der mit seinem
                # Schaden gar keinen traegt, eingeschlossen.
                set_option(env, "damage-mode", "false")
                damage_mode_umgestellt = True
                for name, vorbereiten, schlagen in schlaege:
                    if name not in ("Sprintschlag", "Speerstich"):
                        continue
                    lauf = schlag(vorbereiten, schlagen)
                    rueckstoss_vergleich(name, ohne_cam[name], lauf(True), "auf den Koerper mit damage-mode: false",
                                         gegenprobe=False, nochmal=lauf)
        finally:
            schlaeger.stop()

        # Ohne Schaden gibt das Plugin den Stoss ganz von Hand weiter.
        if not damage_mode_umgestellt:
            set_option(env, "damage-mode", "false")
            damage_mode_umgestellt = True
        for name, kommando in rueckstoss_proben(x, y, z):
            if name not in ("Pfeil", "TNT", "Windkugel"):
                continue
            lauf = geschoss(kommando)
            rueckstoss_vergleich(name, ohne_cam[name], lauf(True), "auf den Koerper mit damage-mode: false",
                                 gegenprobe=False, nochmal=lauf)
    finally:
        try:
            if damage_mode_umgestellt:
                set_option(env, "damage-mode", "mirror")
            set_option(env, "enabled", "true", "cam-safety")
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.5)
            bot.chat(f"/effect clear {BOT_NAME}")
            bot.chat(f"/attribute {BOT_NAME} minecraft:fall_damage_multiplier base reset")
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
# Wer den Koerper schieben darf
# ---------------------------------------------------------------------------

# Wie lange das Schwein im Koerper steht, in Sekunden. Es schiebt jeden Tick,
# und schon ein Zwanzigstel Block beendet den Cam-Modus.
SCHIEBEN_WARTEN = 2.0

# Wie weit oestlich der Mitte des Koerpers das Schwein steht, in Bloecken.
# Genau in der Mitte haette sein Stoss keine Richtung, und das Spiel stiesse
# gar nicht.
SCHIEBEN_ABSTAND = 0.3


def koerpertyp_jetzt(env):
    """Der body.type, der gerade in der Konfiguration des Testservers steht."""
    text = (env.server / "plugins" / "CamFly" / "config.yml").read_text(encoding="utf-8")
    treffer = re.search(r"(?m)^\s*type:\s*(\S+)", text)
    return treffer.group(1) if treffer else None


def schieben_checks(env, bot):
    """Ob ein Mob den Koerper schiebt, nach body.movement-sensitivity.

    Auf Stufe 1 schiebt ihn niemand, auf Stufe 2 schieben ihn Spieler und
    Mobs - aber nur, wo das Mannequin selbst der Koerper ist, bei mannequin.
    Beim Ruestungsstaender von armorstand faellt Stufe 2 auf Stufe 1 zurueck.

    Vom Schieben haelt das Plugin das Mannequin mit dem Team cam_body ab und
    nicht mehr damit, dass es nicht kollidiert: Daran liess der Server auch
    Pfeil, Dreizack, Windkugel und Speerstich vorbeigehen. Diese Probe haelt
    fest, dass das Team das Schieben genauso abhaelt. Ihre Gegenprobe ist
    Stufe 2 mit mannequin - schiebt das Schwein dort nicht, sagt "schiebt nicht"
    auch sonst nichts.

    Geschoben wird mit einem Schwein, das sich nicht von der Stelle ruehrt:
    Tempo 0, aber mit KI - ohne schiebt ein Mob gar nichts. Es steht knapp
    neben der Mitte des Koerpers; genau in ihr fehlte dem Stoss die Richtung,
    und das Spiel schoebe gar nicht. Gesetzt wird es erst ein paar Sekunden
    nach dem Start - eine Sekunde lang sieht die Bewegungspruefung des
    Plugins noch gar nicht hin, und was den Koerper bis dahin verschiebt,
    faellt ihr nicht auf.
    """
    if not FIND.test("Cam-Modus ist vor dem Schiebetest aus", cam_off(bot), ""):
        return
    heim = bot.server_pos()
    if heim is None:
        return
    typ = koerpertyp_jetzt(env)
    x, y, z = _floor(heim[0]) + 0.5, BODEN_Y + 1, _floor(heim[2]) + 0.5
    schwein = (f"summon minecraft:pig {x + SCHIEBEN_ABSTAND} {y} {z} {{Silent:1b,"
               f'DeathLootTable:"minecraft:empty",Tags:["{INTERACT_TAG}"],'
               f'attributes:[{{id:"minecraft:movement_speed",base:0.0}}]}}')

    def probe(stufe, geschoben):
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
        hinstellen(bot, x, y, z)
        if not FIND.test(f"/cam startet fuer den Schiebetest (Stufe {stufe})", cam_on(bot), ""):
            return
        # Hoch ueber den Koerper: Das Schwein soll allein ihn finden.
        bot.call("fly", wait=30, dy=RUECKSTOSS_HOEHE, timeout=15000)
        time.sleep(1.0)
        console(env, schwein, pause=0)
        time.sleep(SCHIEBEN_WARTEN)
        laeuft = spielmodus_ist(bot, "adventure")
        console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
        if geschoben:
            FIND.test(f"Auf Stufe {stufe} schiebt ein Mob den Koerper und beendet den Cam-Modus",
                      not laeuft, "" if not laeuft else "der Cam-Modus laeuft weiter")
        else:
            FIND.test(f"Auf Stufe {stufe} schiebt ein Mob den Koerper nicht", laeuft,
                      "" if laeuft else "der Cam-Modus endete")
        if laeuft:
            cam_off(bot)

    try:
        probe(1, False)
        set_option(env, "movement-sensitivity", "2")
        probe(2, typ == "mannequin")
    finally:
        try:
            console(env, f"kill @e[tag={INTERACT_TAG}]", pause=0.3)
            set_option(env, "movement-sensitivity", "1")
            hinstellen(bot, heim[0], heim[1], heim[2])
        except Exception as exc:
            FIND.problem(f"Aufraeumen nach dem Schiebetest: {exc}")


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
    ("type", "armorstand"), ("visible", "true"), ("name-visible", "true", "body"),
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
        # --- Voreingestellt: armorstand, sichtbar ---
        text = (env.server / "plugins" / "CamFly" / "config.yml").read_text(encoding="utf-8")
        FIND.test("Voreingestellt steht body.type: armorstand da",
                  re.search(r"(?m)^\s*type:\s*armorstand\s*$", text) is not None, "")
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
        for typ in KOERPERTYPEN:
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
        set_options(env, [("type", "mannequin"), ("visible", "true")])
        if FIND.test("/cam startet mit type: mannequin", start(), ""):
            FIND.test("Ueber dem sichtbaren Mannequin steht der Name als TextDisplay",
                      name_ueber(bot, "mannequin", NAME_UEBER_MANNEQUIN), "")
            eigener = server_says(bot, "/execute if data entity @e[type=minecraft:mannequin,"
                                       "distance=..3,limit=1,sort=nearest] CustomName "
                                       "run say {marke}")
            FIND.test("Das Mannequin selbst traegt keinen Namen", not eigener,
                      "" if not eigener else "es hat einen CustomName")
            cam_off(bot)
            time.sleep(1.0)

        # --- Eine alte Zahl und ein unbekannter Wert ---
        # body.type hiess frueher 1 und 2. Das wird nicht mehr gelesen: 2 ist
        # wie jeder unbekannte Wert eine Meldung und ein Ruestungsstaender,
        # kein Mannequin.
        for typ in ("2", "kiste"):
            vorher = len(server_log(env))
            set_options(env, [("type", typ), ("visible", "true")])
            gemeldet = "Unknown value for body.type" in strip_colors(server_log(env)[vorher:])
            if not FIND.test(f"/cam startet mit type: {typ}", start(), ""):
                continue
            staender = anzahl(bot, "@e[type=minecraft:armor_stand,distance=..3]")
            puppen = anzahl(bot, "@e[type=minecraft:mannequin,distance=..3]")
            cam_off(bot)
            time.sleep(1.0)
            FIND.test(f"type: {typ} wird gemeldet und ist ein Ruestungsstaender",
                      staender == 1 and puppen == 1 and gemeldet,
                      f"{staender} Ruestungsstaender, {puppen} Mannequins, "
                      f"{'gemeldet' if gemeldet else 'nicht gemeldet'}")

        # --- Die Schalter unter body.name ---
        set_options(env, [("type", "armorstand"), ("color", "red", "body"), ("through-walls", "true", "body"),
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


def kurz_verlauf(folge):
    """Was der Client nacheinander an einem Block bekam, zusammengefasst: G
    fuer blaues Glas, W fuer Wasser, . fuer Luft, je mit der Zahl
    hintereinander - beim Wasser ist jede Stufe, die es sinkt, eine."""
    teile = []
    for name in folge:
        zeichen = {"blue_stained_glass": "G", "water": "W", "air": "."}.get(name, f"<{name}>")
        if teile and teile[-1][0] == zeichen:
            teile[-1][1] += 1
        else:
            teile.append([zeichen, 1])
    return " ".join(f"{zeichen}{n}" for zeichen, n in teile)


def medium_checks(env, bot):
    """Die Schalter allow_lava_flight, allow_water_flight und
    allow_powder_snow_flight unter camera-mode.

    Steht einer auf false, kommt die Kamera nicht hinein: Der Schritt an der
    Kante wird abgebrochen, und der Cam-Modus laeuft weiter. Darin starten
    laesst er sich auch nicht. Alle drei gehen denselben Weg. Mit
    border-mode: barrier, der Voreinstellung, steht an der Kante zudem eine
    Wand - Magma, blaues Glas und Schnee -, an der ein echter Client landet,
    ohne dass etwas abgebrochen werden muss; das prueft der Fall auf die Lava
    am Schluss, samt der Gegenprobe mit push-back. Die Wand steht nur, wo die
    Kamera hinkommt: Am Grund des Beckens grenzt das Wasser nur an Glas, Gras
    und Wasser und bleibt Wasser - bis darunter eine Stufe liegt, in der die
    Kamera stehen koennte.

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
    wasser_unten = (bx + 8, by, bz)
    unter_dem_wasser = (bx + 8, by - 1, bz)
    # Ein Rahmen aus Stein, eine Lage hoch, oestlich vom Becken: Darin laeuft
    # Wasser aus einer Quelle in der Mitte aus und wieder ab.
    rahmen = f"{bx + 12} {by} {bz - 3} {bx + 18} {by} {bz + 3}"
    im_rahmen = f"{bx + 13} {by} {bz - 2} {bx + 17} {by} {bz + 2}"
    quelle = (bx + 15, by, bz)
    abfluss = [(bx + 14, by, bz), (bx + 15, by, bz + 1), (bx + 16, by, bz - 1), (bx + 15, by, bz + 2)]
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
        for bereich in (becken, lavabecken, wuerfel, decke, rahmen):
            bot.chat(f"/fill {bereich} minecraft:air")
            time.sleep(0.5)
        bot.chat("/setblock {} {} {} minecraft:grass_block".format(*unter_dem_wasser))
        time.sleep(0.3)

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
            # Der Bot schwebt ueber dem Becken, der Grund liegt in Reichweite.
            # Neben ihm ist nichts, worin die Kamera sein koennte: Glas, Gras
            # und Wasser. Eine Stufe darunter aendert das - die Wand sieht
            # beim naechsten Auffrischen nach, was um sie herum steht.
            nacheinander = {"Gras": bot.call("block_at", x=wasser_unten[0], y=wasser_unten[1],
                                              z=wasser_unten[2]).get("name")}
            for setzen, name in (("stone_slab", "Stufe"), ("grass_block", "wieder Gras")):
                bot.chat("/setblock {} {} {} minecraft:{}".format(*unter_dem_wasser, setzen))
                time.sleep(2.5)
                nacheinander[name] = bot.call("block_at", x=wasser_unten[0], y=wasser_unten[1],
                                              z=wasser_unten[2]).get("name")
            schnee = wand_sicht(zum_pulverschnee, pulverschnee_vorn)
            # Wasser laeuft aus und wieder ab, der Bot schwebt darueber. Jede
            # Stufe, die es sinkt, schickt der Server dem Client anstelle der
            # Wand. Die Wand wartet 10 Ticks, ob es so bleibt - beim
            # Abfliessen bleibt es nie so, und dazwischen blitzt keine Wand
            # mehr auf, weder Glas noch die Barriere fuer die Luft.
            bot.chat(f"/fill {rahmen} minecraft:stone")
            time.sleep(0.5)
            bot.chat(f"/fill {im_rahmen} minecraft:air")
            time.sleep(0.5)
            # Erst senkrecht hinauf: schraeg vom Pulverschnee aus bliebe
            # der Bot am Rand des Beckens haengen.
            medium_fliegen(bot, (bx + 0.5, by + 6, bz + 4.5), (quelle[0] + 0.5, by + 6, bz + 0.5),
                           (quelle[0] + 0.5, by + 3, bz + 0.5))
            bot.chat("/setblock {} {} {} minecraft:water".format(*quelle))
            time.sleep(2.5)
            vor_dem_abfluss = [bot.call("block_at", x=x, y=y, z=z).get("name") for x, y, z in abfluss]
            bot.call("watch_blocks", cells=[list(zelle) for zelle in abfluss])
            bot.chat("/setblock {} {} {} minecraft:air".format(*quelle))
            time.sleep(3.0)
            mitgeschrieben = bot.call("watch_stop").get("log") or {}
            verlauf = {zelle: mitgeschrieben.get("{},{},{}".format(*zelle), []) for zelle in abfluss}
            am_ende = [bot.call("block_at", x=x, y=y, z=z).get("name") for x, y, z in abfluss]
            # Glas, ehe der Server dort etwas schickt, wechselt nur Glas gegen
            # Glas - so frischt die Wand sich alle paar Sekunden auf. Danach
            # blitzt es auf.
            danach_anders = []
            for zelle, folge in verlauf.items():
                Log.detail(f"Abfluss bei {zelle}: {kurz_verlauf(folge)}")
                erst = next((i for i, g in enumerate(folge) if g != "blue_stained_glass"), len(folge))
                danach_anders += [g for g in folge[erst:] if g not in ("water", "air")]
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
            FIND.test("Abfliessendes Wasser zeigt erst Glas, dann nur noch Wasser und Luft - "
                      "die Wand blitzt dazwischen nicht wieder auf",
                      all(g == "blue_stained_glass" for g in vor_dem_abfluss)
                      and all("water" in folge for folge in verlauf.values()) and not danach_anders
                      and all(g == "air" for g in am_ende),
                      f"vorher {vor_dem_abfluss}, dazwischen {sorted(set(danach_anders)) or 'nichts'}, "
                      f"am Ende {am_ende}")
            FIND.test("Am Grund des Beckens, wo die Kamera nicht hinkommt, bleibt das "
                      "Wasser Wasser; liegt darunter eine Stufe, wird es gleich zur "
                      "Wand, und ist sie weg, wieder Wasser",
                      nacheinander == {"Gras": "water", "Stufe": "blue_stained_glass",
                                       "wieder Gras": "water"},
                      "; ".join(f"{name}: {g}" for name, g in nacheinander.items()))

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
# Koerpertyp armorstand, der Voreinstellung, steht es im Ruestungsstaender.
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

# Der groesste border-radius, den der Testserver annimmt: so weit, wie er bei
# view-distance=2 die Welt schickt, zwei Chunks zu 16 Bloecken.
GROSSER_RADIUS = 32

# Der zweite Spieler, der nachsieht, ob die Wand nur beim Kamera-Spieler
# steht. Hoechstens 16 Zeichen, und nicht der Name des ersten Bots - sonst
# kickt der eine den anderen mit duplicate_login.
ZUSCHAUER_NAME = "CamFlyZuschauer"

# Ein dritter Spieler, fuer das Limit der Gaeste einer Fuehrung.
DRITTER_NAME = "CamFlyDritter"


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
      * ein grosser Radius, so weit wie der Server die Welt schickt: die
        Wand steht vom Koerper aus rundum, wo sie mit 5 noch nicht steht,
        ein geaenderter Block in ihr wird gleich wieder zur Wand, der Bot
        bleibt an ihr stehen, und danach ist sie ganz weg.
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
        set_options(env, [("border-block", "diamond"), ("border-radius", "999")])
        neu = strip_colors(server_log(env)[vorher:])
        FIND.test("Ein unbekannter border-block wird gemeldet",
                  "camera-mode.border-block: 'diamond'" in neu, "")
        FIND.test("Ein zu grosser border-radius wird gemeldet",
                  "camera-mode.border-radius is too large" in neu, "")
        ergebnis = grenz_lauf(bot, heim)
        FIND.test("Mit dem Ersatz fuer beides steht die Wand trotzdem",
                  drin(ergebnis) and ergebnis[1] == 0, grenz_zeige(ergebnis, heim))
        cam_off(bot)

        # --- ein grosser border-radius ---
        # Die Wand auf max-distance liegt vom Koerper aus rundum sechs Bloecke
        # weit, nach oben fuenf: Die Ecke des Blocks, die am weitesten weg
        # liegt, zaehlt. Ein Radius von 5 reicht vom Koerper aus nicht hin,
        # GROSSER_RADIUS schon - in jede Richtung, ohne dass der Bot hinlaeuft.
        bot.chat(f"/fill {bx - 7} {by} {bz - 7} {bx + 7} {by + 6} {bz + 7} minecraft:air")
        time.sleep(0.5)
        rundum = (("Osten", (bx + GRENZ_ABSTAND, by, bz)),
                  ("Westen", (bx - GRENZ_ABSTAND, by, bz)),
                  ("oben", (bx, by + GRENZ_ABSTAND - 1, bz)))
        westen = rundum[1][1]
        set_options(env, [("border-block", "barrier"), ("border-radius", "5")])
        hinstellen(bot, *heim)
        if cam_on(bot):
            time.sleep(1.5)
            gesehen = sieht(westen)
            FIND.test("border-radius: 5 - am Koerper sieht der Bot die Wand auf "
                      "max-distance noch nicht", gesehen != "barrier", f"sieht: {gesehen}")
        cam_off(bot)
        vorher = len(server_log(env))
        set_option(env, "border-radius", str(GROSSER_RADIUS))
        neu = strip_colors(server_log(env)[vorher:])
        FIND.test(f"border-radius: {GROSSER_RADIUS} - so weit, wie der Server die Welt "
                  f"schickt, wird nicht gemeldet", "border-radius" not in neu, "")
        hinstellen(bot, *heim)
        if FIND.test(f"/cam startet mit border-radius: {GROSSER_RADIUS}", cam_on(bot), ""):
            time.sleep(1.5)
            gesehen = {name: sieht(zelle) for name, zelle in rundum}
            FIND.test(f"border-radius: {GROSSER_RADIUS} - die Wand steht rundum auf "
                      f"max-distance, ohne dass der Bot hinlaeuft",
                      all(g == "barrier" for g in gesehen.values()),
                      "; ".join(f"{name}: {g}" for name, g in gesehen.items()))
            # Aendert sich der echte Block, schickt der Server ihn dem Client
            # und ueberschreibt damit die Wand. Das Auffrischen stellt sie
            # wieder hin - ausser auf einem ganzen Block, der bleibt stehen.
            x, y, z = westen
            nacheinander = {}
            for setzen, name in (("stone_slab", "Stufe"), ("stone", "Stein"), ("air", "Luft")):
                bot.chat(f"/setblock {x} {y} {z} minecraft:{setzen}")
                time.sleep(2.5)
                nacheinander[name] = sieht(westen)
            FIND.test("Aendert sich ein Block der Wand in der Welt, steht sie dort gleich "
                      "wieder: auf einer Stufe die Wand, ein ganzer Block bleibt stehen, "
                      "und ist er weg, steht dort wieder die Wand",
                      nacheinander == {"Stufe": "barrier", "Stein": "stone", "Luft": "barrier"},
                      "; ".join(f"{name}: {g}" for name, g in nacheinander.items()))
        cam_off(bot)
        ergebnis = grenz_lauf(bot, heim)
        FIND.test(f"border-radius: {GROSSER_RADIUS} - die Kamera bleibt an max-distance "
                  f"stehen, ohne zurueckgesetzt zu werden",
                  drin(ergebnis) and ergebnis[1] == 0, grenz_zeige(ergebnis, heim))
        cam_off(bot)
        time.sleep(1.0)
        nachher = {name: sieht(zelle) for name, zelle in rundum}
        FIND.test(f"border-radius: {GROSSER_RADIUS} - nach dem Cam-Modus ist die ganze "
                  f"Wand beim Spieler wieder weg", all(g == "air" for g in nachher.values()),
                  "; ".join(f"{name}: {g}" for name, g in nachher.items()))

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


# Wo der Koerper fuer den Test des unbewachten Chunks steht: auf dem Dach des
# Nethers, weit weg von allem, was die anderen Abschnitte dort anfassen. Die
# oberste Lage bei y=127 ist immer Grundgestein, darauf steht man ohne /fill.
# Ein /forceload dort verfaelschte die Probe, es haelte den Chunk ja selbst.
UNBEWACHT_KOERPER = (2000.5, 128, 2000.5)

# Wohin der Bot derweil geht: in die Overworld, weit weg von allem anderen.
UNBEWACHT_WEG = (6000.5, -60, 6000.5)

# Wie lange er dort bleibt, in Sekunden. Paper laesst einen Chunk ohne
# Spieler erst nach einer Weile los, die Entitaeten darin danach; nach 30
# Sekunden war er im Versuch zuverlaessig weg.
UNBEWACHT_WARTEN = 30


def unbewacht_steht(bot, typ, x, y, z):
    """Ob im Nether um diese Stelle herum eine Entitaet dieses Typs steht."""
    return server_says(bot, f"/execute in minecraft:the_nether positioned {x} {y} {z} "
                            f"if entity @e[type=minecraft:{typ},distance=..6] run say {{marke}}")


def unbewacht_geladen(bot, x, y, z):
    """Ob der Chunk an dieser Stelle im Nether gerade geladen ist."""
    return server_says(bot, f"/execute in minecraft:the_nether if loaded "
                            f"{_floor(x)} {y} {_floor(z)} run say {{marke}}")


def unbewacht_weg(bot):
    """Den Bot in die Overworld stellen und warten, bis ein Chunk ohne Spieler
    im Nether entladen waere."""
    bot.chat("/execute in minecraft:overworld run tp @s {} {} {}".format(*UNBEWACHT_WEG))
    time.sleep(UNBEWACHT_WARTEN)


def unbewacht_checks(env, bot):
    """Ein Koerper, bei dem kein Spieler mehr steht.

    Gemeldet war: den Cam-Modus im Nether starten, sich per /tp in die
    Overworld setzen, per /tp zurueck und mit /cam beenden - der
    Ruestungsstaender oder das Mannequin blieb stehen. Ohne Spieler in der
    Naehe fiel der Chunk des Koerpers weg, und was er beim naechsten Laden
    zurueckbrachte, waren neue Entitaeten unter denselben Ids: Das Plugin
    raeumte die alten weg und liess die neuen stehen. Seitdem haelt es den
    Chunk, solange der Cam-Modus laeuft, und raeumt ueber die Id weg.

    Gefragt wird, waehrend der Bot in der Overworld steht, ob der Chunk
    drueben geladen bleibt und Koerper und Name noch dastehen, nach dem Ende,
    ob der Koerper weg ist, und danach, ob der Chunk wieder losgelassen wird.
    Laeuft fuer beide Koerpertypen, siehe fuer_beide_koerpertypen.
    """
    x, y, z = UNBEWACHT_KOERPER
    heim = bot.server_pos()
    try:
        set_option(env, "nether", "true", "cam-area")
        cam_off(bot)
        bot.chat(f"/execute in minecraft:the_nether run tp @s {x} {y} {z}")
        time.sleep(3)
        if not FIND.test("Unbewachter Koerper: Cam-Modus startet im Nether",
                         in_nether(bot) and cam_on(bot), ""):
            return
        time.sleep(2)

        unbewacht_weg(bot)
        FIND.test("Im Cam-Modus bleibt der Chunk des Koerpers geladen, auch ohne "
                  "Spieler in der Naehe", unbewacht_geladen(bot, x, y, z), "")
        koerper = "armor_stand" if koerpertyp_jetzt(env) == "armorstand" else "mannequin"
        FIND.test("Koerper und Name stehen noch, waehrend der Kamera-Spieler in "
                  "einer anderen Welt ist",
                  unbewacht_steht(bot, koerper, x, y, z)
                  and unbewacht_steht(bot, "text_display", x, y, z), "")

        bot.chat(f"/execute in minecraft:the_nether run tp @s {x + 3} {y} {z}")
        time.sleep(4)
        cam_off(bot)
        time.sleep(2)
        rest = [typ for typ in ("armor_stand", "mannequin")
                if unbewacht_steht(bot, typ, x, y, z)]
        FIND.test("Nach /cam ist der Koerper weg, auch nach einem Ausflug per /tp "
                  "in eine andere Welt", not rest,
                  "" if not rest else "es steht noch: " + ", ".join(rest))

        unbewacht_weg(bot)
        FIND.test("Nach dem Cam-Modus laesst das Plugin den Chunk wieder los",
                  not unbewacht_geladen(bot, x, y, z), "")
    finally:
        cam_off(bot)
        # Erst hin: Ist etwas stehen geblieben, liegt es in einem Chunk, den
        # sonst niemand laedt, und /kill kaeme nicht an. Der naechste
        # Koerpertyp zaehlte es dann als seinen eigenen Rest.
        bot.chat(f"/execute in minecraft:the_nether run tp @s {x + 3} {y} {z}")
        time.sleep(4)
        bot.chat(f"/execute in minecraft:the_nether run kill "
                 f"@e[type=!minecraft:player,x={x},y={y},z={z},distance=..10]")
        time.sleep(0.5)
        if heim is not None:
            bot.chat("/execute in minecraft:overworld run tp @s {:.1f} {:.1f} {:.1f}".format(*heim))
            time.sleep(1)
        set_option(env, "nether", "false", "cam-area")


def liegengeblieben_checks(env, bot):
    """Ein Koerper, den ein Absturz in einem entladenen Chunk zurueckgelassen
    hat, verschwindet, sobald der Chunk wieder geladen wird.

    Nachgestellt mit /summon und der Marke, die das Plugin seinen Koerpern
    gibt, in einem Chunk, den /forceload kurz laedt und wieder loslaesst.
    Ein Ruestungsstaender ohne Marke daneben muss stehen bleiben.
    """
    x, y, z = _floor(UNBEWACHT_KOERPER[0]) + 100, UNBEWACHT_KOERPER[1], _floor(UNBEWACHT_KOERPER[2])
    nether = "/execute in minecraft:the_nether run"
    heim = bot.server_pos()
    try:
        bot.chat(f"{nether} forceload add {x} {z}")
        time.sleep(2)
        bot.chat(f'{nether} summon minecraft:armor_stand {x} {y} {z} '
                 f'{{Tags:["camflytest"],BukkitValues:{{"camfly:cam_body":1}}}}')
        bot.chat(f'{nether} summon minecraft:mannequin {x} {y} {z} '
                 f'{{Tags:["camflytest"],BukkitValues:{{"camfly:cam_hitbox":1}}}}')
        bot.chat(f'{nether} summon minecraft:armor_stand {x} {y} {z} '
                 f'{{Tags:["camflytest","fremd"]}}')
        time.sleep(1)
        bot.chat(f"{nether} forceload remove {x} {z}")
        unbewacht_weg(bot)
        if not FIND.test("Liegengebliebener Koerper: der Chunk ist entladen",
                         not unbewacht_geladen(bot, x, y, z), ""):
            return
        bot.chat(f"/execute in minecraft:the_nether run tp @s {x + 2} {y} {z}")
        time.sleep(5)
        markiert = server_says(bot, f"/execute in minecraft:the_nether positioned {x} {y} {z} "
                                    f"if entity @e[tag=camflytest,tag=!fremd,distance=..6] "
                                    f"run say {{marke}}")
        FIND.test("Ein liegengebliebener Koerper verschwindet, sobald sein Chunk "
                  "wieder geladen wird", not markiert, "")
        FIND.test("Ein Ruestungsstaender ohne die Marke des Plugins bleibt dabei stehen",
                  server_says(bot, f"/execute in minecraft:the_nether positioned {x} {y} {z} "
                                   f"if entity @e[tag=fremd,distance=..6] run say {{marke}}"), "")
    finally:
        bot.chat(f"{nether} kill @e[tag=camflytest,x={x},y={y},z={z},distance=..10]")
        time.sleep(0.5)
        if heim is not None:
            bot.chat("/execute in minecraft:overworld run tp @s {:.1f} {:.1f} {:.1f}".format(*heim))
            time.sleep(1)


def tp_koerper_da(bot, x, y, z):
    """Ob an dieser Stelle der Ruestungsstaender eines Koerpers steht."""
    return server_says(bot, f"/execute positioned {x} {y} {z} if entity "
                            f"@e[type=minecraft:armor_stand,distance=..1.5] run say {{marke}}")


def tp_steht_bei(bot, name, x, y, z, weit=1.5):
    """Ob dieser Spieler an dieser Stelle steht, vom Server gefragt."""
    return server_says(bot, f"/execute positioned {x} {y} {z} if entity "
                            f"@a[name={name},distance=..{weit}] run say {{marke}}")


def tp_kamera_hoch(bot, heim):
    """Den Bot an heim in den Cam-Modus setzen und die Kamera sechs Bloecke
    hinauffliegen lassen. Gibt zurueck, ob der Cam-Modus laeuft."""
    bot.chat("/execute in minecraft:overworld run tp @s {} {} {}".format(*heim))
    time.sleep(1.5)
    if not cam_on(bot):
        return False
    time.sleep(1)
    bot.call("fly", wait=30, dy=6, dx=0, timeout=15000)
    time.sleep(1)
    return True


def teleport_checks(env, bot):
    """teleports.command und teleports.plugin: was ein Teleport von aussen mit
    dem Cam-Modus macht.

    Fuer ein tpa-Plugin steht das Hilfsplugin CamFlyTpaHelfer, das ueber
    player.teleport() teleportiert, fuer /tp der Befehl von der Konsole.
    Gefragt wird zweierlei, mit beiden Ursachen:
      * Wer im Cam-Modus teleportiert wird, ist danach heraus, steht am Ziel
        und hat seine Sachen wieder, und sein Koerper ist weg.
      * Wer genau bei einer Kamera landen wuerde - das macht /tpahere aus dem
        Cam-Modus -, landet bei ihrem Koerper und nicht in der Luft, und ihr
        Cam-Modus endet.
    Mit dem Schalter auf false bleibt der Kamera-Spieler im Cam-Modus, und wer
    zu ihm kommt, kommt bei der Kamera an - wie frueher.
    """
    pos = bot.server_pos()
    if not FIND.test("Position fuer den Teleporttest lesbar", pos is not None, str(pos)):
        return
    hx, hy, hz = _floor(pos[0]) + 0.5, _floor(pos[1] + 0.5), _floor(pos[2]) + 0.5
    heim = (hx, hy, hz)
    ziel = (hx + 10, hy, hz)
    helfer = (env.server / "plugins" / f"{TPA_HELFER_NAME}.jar").exists()
    zuschauer = None
    try:
        # Auf dem Testserver steht teleports.command auf false, siehe
        # prepare_server_files. Hier geht es gerade um true, die Voreinstellung.
        set_options(env, [("command", "true", "teleports"), ("plugin", "true", "teleports")])
        cam_off(bot)
        bot.chat("/clear @s")
        bot.chat("/give @s minecraft:stick")
        time.sleep(0.5)

        # --- Der Kamera-Spieler selbst wird teleportiert ---
        for ursache, befehl in (("plugin", f"plugintp {BOT_NAME} {ziel[0]} {ziel[1]} {ziel[2]}"),
                                ("command", f"tp {BOT_NAME} {ziel[0]} {ziel[1]} {ziel[2]}")):
            if ursache == "plugin" and not FIND.test("Hilfsplugin fuer tpa steht bereit", helfer,
                                                     f"{TPA_HELFER_NAME}.jar"):
                continue
            if not tp_kamera_hoch(bot, heim):
                FIND.test(f"teleports.{ursache}: Cam-Modus startet", False, "")
                continue
            since = bot.mark()
            console(env, befehl, pause=2.5)
            FIND.test(f"teleports.{ursache}: wer im Cam-Modus teleportiert wird, ist danach "
                      f"heraus und steht am Ziel",
                      not spielmodus_ist(bot, "adventure")
                      and tp_steht_bei(bot, BOT_NAME, *ziel), "")
            FIND.test(f"teleports.{ursache}: er hat seine Sachen wieder, und sein Koerper "
                      f"bleibt nicht stehen",
                      server_says(bot, "/execute if items entity @s container.* minecraft:stick "
                                       "run say {marke}")
                      and not tp_koerper_da(bot, *heim), "")
            if ursache == "plugin":
                FIND.test("Die Meldung teleport-ended kommt",
                          bool(bot.expect("You were teleported", since, 3000)), "")

        # --- Jemand wird zu einer Kamera teleportiert ---
        zuschauer = BotClient(env, name=ZUSCHAUER_NAME)
        zuschauer.start()
        if not FIND.test("Zweiter Spieler fuer den Teleporttest kommt herein",
                         zuschauer.call("wait_spawn", wait=90, timeout=75000).get("spawned"), ""):
            return
        time.sleep(1.5)
        for ursache, befehl in (("plugin", f"plugintp {ZUSCHAUER_NAME} {BOT_NAME}"),
                                ("command", f"tp {ZUSCHAUER_NAME} {BOT_NAME}")):
            if ursache == "plugin" and not helfer:
                continue
            console(env, f"tp {ZUSCHAUER_NAME} {ziel[0] + 5} {ziel[1]} {ziel[2]}", pause=1.5)
            if not tp_kamera_hoch(bot, heim):
                FIND.test(f"teleports.{ursache}: Cam-Modus startet", False, "")
                continue
            # Langsam fallend: Kaeme er bei der Kamera an, faellt er von dort
            # sonst herunter und stuende bei der Frage ohnehin am Koerper.
            console(env, f"effect give {ZUSCHAUER_NAME} minecraft:slow_falling 10 0 true")
            since = bot.mark()
            console(env, befehl, pause=0.5)
            FIND.test(f"teleports.{ursache}: wer zu einer Kamera teleportiert wird, landet bei "
                      f"ihrem Koerper statt in der Luft",
                      tp_steht_bei(bot, ZUSCHAUER_NAME, *heim), "")
            console(env, f"effect clear {ZUSCHAUER_NAME}", pause=2.0)
            FIND.test(f"teleports.{ursache}: der Cam-Modus der Kamera endet dabei, und sie steht "
                      f"wieder an ihrem Koerper",
                      not spielmodus_ist(bot, "adventure") and tp_steht_bei(bot, BOT_NAME, *heim)
                      and not tp_koerper_da(bot, *heim), "")
            if ursache == "plugin":
                FIND.test("Die Meldung teleport-arrival-ended nennt, wer kam",
                          bool(bot.expect(f"{ZUSCHAUER_NAME} was teleported to you", since, 3000)), "")

        # --- Mit dem Schalter auf false bleibt es wie frueher ---
        set_options(env, [("command", "false", "teleports"), ("plugin", "false", "teleports")])
        for ursache, befehl in (("plugin", f"plugintp {BOT_NAME} {ziel[0]} {ziel[1]} {ziel[2]}"),
                                ("command", f"tp {BOT_NAME} {ziel[0]} {ziel[1]} {ziel[2]}")):
            if ursache == "plugin" and not helfer:
                continue
            if not tp_kamera_hoch(bot, heim):
                FIND.test(f"teleports.{ursache}: false - Cam-Modus startet", False, "")
                continue
            console(env, befehl, pause=2.5)
            FIND.test(f"teleports.{ursache}: false - er bleibt im Cam-Modus, nur die Kamera "
                      f"steht am Ziel",
                      spielmodus_ist(bot, "adventure") and tp_steht_bei(bot, BOT_NAME, *ziel)
                      and tp_koerper_da(bot, *heim), "")
            cam_off(bot)
            time.sleep(1)
        if tp_kamera_hoch(bot, heim):
            luft = bot.server_pos()
            # Langsam fallend, sonst ist er unten, bevor die Frage ankommt.
            console(env, f"effect give {ZUSCHAUER_NAME} minecraft:slow_falling 10 0 true")
            console(env, f"tp {ZUSCHAUER_NAME} {BOT_NAME}", pause=0.5)
            FIND.test("teleports.command: false - wer zur Kamera teleportiert wird, kommt bei "
                      "ihr in der Luft an, und sie bleibt im Cam-Modus",
                      luft is not None and tp_steht_bei(bot, ZUSCHAUER_NAME, *luft, weit=3)
                      and spielmodus_ist(bot, "adventure"), str(luft))
            console(env, f"effect clear {ZUSCHAUER_NAME}")
    finally:
        if zuschauer is not None:
            zuschauer.stop()
        cam_off(bot)
        bot.chat("/clear @s")
        bot.chat("/execute in minecraft:overworld run tp @s {} {} {}".format(*heim))
        time.sleep(1)
        set_options(env, [("command", "false", "teleports"), ("plugin", "true", "teleports")])


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


# ---------------------------------------------------------------------------
# Die Abschnitte der Tests und welche davon laufen
# ---------------------------------------------------------------------------

class Abschnitt:
    """Ein Abschnitt der Tests: sein Name, die Funktion, die ihn faehrt, und
    die Bereiche des Plugins, die er prueft.

    Ein Bereich ist ein Paket unter de.elia.cameraplugin wie "trail" oder
    eine Klasse darin wie "display/CamActionBar". Die Testauswahl
    (tools/testauswahl.py) faehrt den Abschnitt, wenn sich in einem seiner
    Bereiche etwas geaendert hat oder davon betroffen ist, wenn sich ein
    Schluessel geaendert hat, den er mit set_option setzt, oder sein Code im
    Testskript. Mit koerpertypen=True laeuft er einmal mit jedem Koerpertyp,
    siehe fuer_beide_koerpertypen."""

    def __init__(self, name, funktion, bereiche, koerpertypen=False):
        self.name = name
        self.funktion = funktion
        self.bereiche = bereiche.split()
        self.koerpertypen = koerpertypen


# Alle Abschnitte, in der Reihenfolge, in der sie laufen. Ein neuer Abschnitt
# braucht hier einen Eintrag, sonst laeuft er nie. Der Absturztest steht
# zuletzt: Er startet den Server neu.
ABSCHNITTE = [
    Abschnitt("hunger", hunger_checks, "hunger/CamHungerGuard"),
    Abschnitt("heilung", heal_checks, "hunger/CamRegenGuard"),
    Abschnitt("effekte_beim_start", effect_start_checks, "start/StartChecks start/EffectStart"),
    Abschnitt("meldungen", message_switch_checks, "display/CamActionBar config/Messages"),
    Abschnitt("sprache", language_checks,
              "config/Messages config/ConfigFile config/ConfigReader config/ConfigIssue display/CamActionBar"),
    Abschnitt("liste", liste_checks, "list"),
    Abschnitt("aktionsleiste", aktionsleiste_checks, "display/CamActionBar display/ActionBarDisplay"),
    Abschnitt("tempo", tempo_checks, "speed"),
    Abschnitt("nachtsicht", nachtsicht_checks, "nightvision"),
    Abschnitt("toene", toene_checks, "sound session/CameraMode movement/CamMovementGuard"),
    Abschnitt("taste", taste_checks, "session/ExitKey display/CamActionBar"),
    Abschnitt("protokoll", protokoll_checks, "log session/SessionTracker"),
    Abschnitt("akku", akku_checks, "battery timelimit/FlightLimit stats session/SessionListener"),
    Abschnitt("kosten", kosten_checks, "cost"),
    Abschnitt("statistik", statistik_checks, "stats storage session/SessionTracker"),
    Abschnitt("alte_absturzdatei", alte_absturzdatei_checks, "crash storage session/SessionListener"),
    Abschnitt("zurueck", zurueck_checks, "back"),
    Abschnitt("endwarnung", endwarnung_checks, "timelimit battery"),
    Abschnitt("alarm", alarm_checks, "body/BodyAlarm"),
    Abschnitt("knoepfe", knoepfe_checks, "list"),
    Abschnitt("stopp", stopp_checks, "stop list"),
    Abschnitt("bann", bann_checks, "ban"),
    Abschnitt("welten", welten_checks, "area"),
    Abschnitt("angriff", angriff_checks, "start/StartChecks"),
    Abschnitt("afk", afk_checks, "afk"),
    Abschnitt("herzen", herzen_checks,
              "body/BodySpawner body/MannequinLabel display/SpriteText display/NameDisplay"),
    Abschnitt("wartezeit", wartezeit_checks, "timelimit storage session/SessionListener"),
    Abschnitt("akku_speicher", akku_speicher_checks, "battery storage"),
    Abschnitt("startverzoegerung", startverzoegerung_checks, "start/StartDelay start/CamStarter"),
    Abschnitt("flugspur", flugspur_checks, "trail"),
    Abschnitt("vorschlaege", vorschlag_checks, "camfly2/CamTabCompleter camfly2/CamSuggestionFilter"),
    Abschnitt("aussichtspunkte", aussichtspunkt_checks, "spot"),
    Abschnitt("foto", foto_checks, "photo"),
    Abschnitt("zeit_wetter", zeit_wetter_checks, "sky"),
    Abschnitt("nachtsicht_wahl", nachtsicht_wahl_checks, "nightvision preferences session/SessionListener"),
    Abschnitt("naehe", naehe_checks, "start/StartChecks"),
    Abschnitt("einstellungen", einstellungen_checks, "preferences speed sky storage session/SessionListener"),
    Abschnitt("teilen", teilen_checks, "spot visit"),
    Abschnitt("fuehrung", fuehrung_checks, "tour speed"),
    Abschnitt("weiter", weiter_checks, "resume"),
    Abschnitt("ausspaehen", ausspaeh_checks, "scout"),
    Abschnitt("menue", menue_checks, "menu"),
    Abschnitt("spotreiter", spotreiter_checks, "menu spot"),
    Abschnitt("spotmoderation", spotmoderation_checks, "menu spot/SpotModeration spot/SpotBans spot/CamSpots"),
    Abschnitt("anstupsen", anstups_checks, "nudge", koerpertypen=True),
    Abschnitt("funk", funk_checks, "signal"),
    Abschnitt("traenke", potion_checks, "potion", koerpertypen=True),
    Abschnitt("treffergrund", grund_checks, "mirrordamage/DamageMirror body/BodyWatch config/Messages",
              koerpertypen=True),
    Abschnitt("anfassen", interact_checks, "interaction inventory ghast session/CameraMode"),
    Abschnitt("sulfur_cube", sulfur_cube_checks, "sulfurcube"),
    Abschnitt("ruestung", armor_checks, "inventory session/CameraMode"),
    Abschnitt("rueckstoss", rueckstoss_checks, "mirrordamage interaction/CamKnockbackGuard",
              koerpertypen=True),
    Abschnitt("rueckstoss_mob", rueckstoss_mob_checks, "mirrordamage", koerpertypen=True),
    Abschnitt("schieben", schieben_checks,
              "body/MovementSensitivity body/BodyWatch body/BodySpawner scoreboard/NoCollisionTeam",
              koerpertypen=True),
    Abschnitt("waerter_ziel", waerter_ziel_checks, "mob/MobTargeting"),
    Abschnitt("uebergabe", uebergabe_checks, "mob/MobTargeting"),
    Abschnitt("blick", blick_checks, "mob/MobGaze"),
    Abschnitt("name", name_checks,
              "body/BodySpawner body/MannequinLabel body/BodyType display/NameDisplay display/SpriteText "
              "config/NameStyle"),
    Abschnitt("spielername", spielername_checks,
              "display/CamNameTag display/NameMode config/NameStyle visibility scoreboard/NoCollisionTeam"),
    Abschnitt("spielmodus", gamemode_checks, "session/CamGameMode session/CameraMode interaction inventory"),
    Abschnitt("medium", medium_checks,
              "movement/FlightMedium movement/CamMovementGuard movement/CamBorderWall start/StartChecks"),
    Abschnitt("fall", fall_checks, "start/StartChecks"),
    Abschnitt("zustand", zustand_checks, "session/CameraMode body/BodySpawner"),
    Abschnitt("grenze", border_checks, "movement"),
    Abschnitt("portale", portal_checks, "portal movement/CamMovementGuard area"),
    Abschnitt("unbewacht", unbewacht_checks, "body/BodySpawner body/BodyWatch", koerpertypen=True),
    Abschnitt("liegengeblieben", liegengeblieben_checks, "body/BodySpawner crash"),
    Abschnitt("teleport", teleport_checks, "teleport"),
    Abschnitt("absturz", absturz_checks, "crash"),
]

# Welche Abschnitte dieser Lauf faehrt, siehe auswahl_laden. None faehrt alle.
AUSWAHL = None


def markieren(name, ab):
    """Den Proben ab der Stelle ab, die noch keinem Abschnitt gehoeren, den
    Namen geben: So weiss der naechste Lauf, welcher Abschnitt durchgefallen
    ist."""
    for t in FIND.tests[ab:]:
        t.setdefault("abschnitt", name)


def letzte_fehler(env):
    """Die Abschnitte, die beim letzten Lauf in diesem Arbeitsordner
    durchgefallen sind, aus seinem ergebnis.json."""
    try:
        alt = json.loads((env.work / "ergebnis.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return set()
    namen = {a.name for a in ABSCHNITTE}
    return {t.get("abschnitt") for t in alt.get("tests", [])
            if not t.get("ok") and t.get("abschnitt") in namen}


def auswahl_laden(env, alle=False, seit=None, namen=None):
    """Welche Abschnitte laufen.

    Voreingestellt die, die der Vergleich mit master braucht (siehe
    tools/testauswahl.py), und dazu die, die beim letzten Lauf
    durchgefallen sind. Mit namen genau diese, mit alle alle."""
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import testauswahl
    if alle:
        return testauswahl.Auswahl(alle="--alle")
    if namen:
        auswahl = testauswahl.Auswahl()
        for name in namen:
            auswahl.dazu(name, "--abschnitte")
        return auswahl
    try:
        auswahl = testauswahl.auswaehlen(
            env.repo, {a.name: ([a.funktion.__name__], a.bereiche) for a in ABSCHNITTE}, seit)
    except Exception as exc:
        # Lieber alle als eine Auswahl, die an sich selbst gescheitert ist.
        return testauswahl.Auswahl(alle=f"die Testauswahl ging schief ({type(exc).__name__}: {exc})")
    for name in sorted(letzte_fehler(env)):
        auswahl.dazu(name, "beim letzten Lauf durchgefallen")
    return auswahl


def auswahl_zeigen(auswahl):
    Log.step("Testauswahl")
    if auswahl.basis:
        Log.info(f"Verglichen mit: {auswahl.basis_name} ({auswahl.basis[:9]})")
        geaendert = [Path(p).name for p in auswahl.dateien]
        Log.info(f"Geaendert:      {', '.join(geaendert) if geaendert else 'nichts'}")
    if auswahl.alle is not None:
        Log.info(f"Es laufen alle {len(ABSCHNITTE)} Abschnitte: {auswahl.alle}")
        return
    Log.info(f"Es laufen {len(auswahl.abschnitte)} von {len(ABSCHNITTE)} Abschnitten, "
             f"dazu immer die Grundprobe:")
    breite = max((len(a.name) for a in ABSCHNITTE), default=0)
    for a in ABSCHNITTE:
        if a.name in auswahl.abschnitte:
            gruende = auswahl.abschnitte[a.name]
            Log.info(f"  {a.name:<{breite}}  {gruende[0]}")
            for grund in gruende[1:3]:
                Log.detail(f"  {'':<{breite}}  {grund}")
            if len(gruende) > 3:
                Log.detail(f"  {'':<{breite}}  ... und {len(gruende) - 3} weitere Gruende")
    if auswahl.ohne_abschnitt:
        Log.warn("Geaendert, aber von keinem Abschnitt geprueft: " + ", ".join(auswahl.ohne_abschnitt))
    Log.detail("Alle Abschnitte mit --alle, einzelne mit --abschnitte name,name")


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
    # Der Abschnitt, der gerade laeuft: Wirft er, gehoert der Fehler ihm.
    # Die Proben der Schritte davor gehoeren keinem.
    laufend, erste = None, len(FIND.tests)
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

        markieren("grundprobe", erste)

        # --- Die Abschnitte, die die Testauswahl fuer diesen Stand braucht ---
        gewaehlt = [a for a in ABSCHNITTE if AUSWAHL is None or AUSWAHL.gewaehlt(a.name)]
        i = 0
        while i < len(gewaehlt):
            # Was hintereinander mit beiden Koerpertypen laeuft, laeuft in
            # einem Durchgang: Jeder Wechsel des Typs braucht zwei Reloads.
            gruppe = [gewaehlt[i]]
            while gruppe[0].koerpertypen and i + len(gruppe) < len(gewaehlt) \
                    and gewaehlt[i + len(gruppe)].koerpertypen:
                gruppe.append(gewaehlt[i + len(gruppe)])
            i += len(gruppe)
            laufend = gruppe[0]
            if not laufend.koerpertypen:
                laufend.funktion(env, bot)
                markieren(laufend.name, erste)
                continue
            for typ in KOERPERTYPEN:
                with mit_koerpertyp(env, typ):
                    for laufend in gruppe:
                        laufend.funktion(env, bot)
                        markieren(laufend.name, erste)
        laufend = None

    except Exception as exc:
        FIND.test("Testlauf", False, f"{type(exc).__name__}: {exc}")
        if laufend is not None:
            markieren(laufend.name, erste)
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
    auswahl = None
    if AUSWAHL is not None and "tests" in results:
        ausgelassen = [a.name for a in ABSCHNITTE if not AUSWAHL.gewaehlt(a.name)]
        if ausgelassen:
            print(f"\n  {len(ausgelassen)} von {len(ABSCHNITTE)} Abschnitten ausgelassen, "
                  f"siehe Testauswahl oben. Alle mit --alle.")
        auswahl = {"basis": AUSWAHL.basis, "alle": AUSWAHL.alle,
                   "abschnitte": AUSWAHL.abschnitte, "ausgelassen": ausgelassen}
    print(f"\n  Arbeitsordner: {env.work}")
    print(f"  Server-Log:    {env.server / 'server.log'}")
    print(f"  ApiCheck:      {env.apicheck / 'report.json'}")
    report = env.work / "ergebnis.json"
    report.write_text(json.dumps(
        {"tests": FIND.tests, "problems": FIND.problems, "steps": results, "auswahl": auswahl},
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
    parser.add_argument("--alle", action="store_true",
                        help="Alle Abschnitte der Tests fahren, nicht nur die, die die Aenderungen betreffen")
    parser.add_argument("--seit", metavar="REF",
                        help="Womit die Testauswahl vergleicht (voreingestellt: der Abzweig von master)")
    parser.add_argument("--abschnitte", metavar="NAME,NAME",
                        help="Genau diese Abschnitte der Tests fahren")
    parser.add_argument("--nur-auswahl", action="store_true",
                        help="Nur zeigen, welche Abschnitte der Tests laufen wuerden und warum")
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
    namen = [n.strip() for n in (args.abschnitte or "").split(",") if n.strip()]
    unbekannt = [n for n in namen if n not in {a.name for a in ABSCHNITTE}]
    if unbekannt:
        parser.error(f"Unbekannte Abschnitte: {', '.join(unbekannt)}. "
                     f"Es gibt: {', '.join(a.name for a in ABSCHNITTE)}")
    if args.alle and namen:
        parser.error("--alle und --abschnitte schliessen sich aus")

    global AUSWAHL
    if args.nur_auswahl:
        auswahl_zeigen(auswahl_laden(env, args.alle, args.seit, namen))
        return 0

    print(f"{Log.BLUE}CamFly-Testumgebung{Log.OFF}")
    print(f"  Plugin:        {env.repo}")
    print(f"  Arbeitsordner: {env.work}")
    print(f"  Schritte:      {', '.join(plan)}")
    if "tests" in plan:
        AUSWAHL = auswahl_laden(env, args.alle, args.seit, namen)
        auswahl_zeigen(AUSWAHL)

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
