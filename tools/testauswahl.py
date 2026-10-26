"""
CamFly - welche Abschnitte der Tests ein Stand des Plugins braucht.

Alle Abschnitte zu fahren dauert ueber anderthalb Stunden, die meisten
Aenderungen beruehren aber nur wenige davon. Dieses Modul vergleicht den Stand
im Arbeitsordner mit einer Basis - voreingestellt dem Stand von master, von dem
der Zweig abging - und sucht die Abschnitte heraus, die etwas pruefen, das neu
ist, sich geaendert hat oder davon betroffen sein kann:

  * eine Java-Datei: die Methoden und Felder, die sich geaendert haben, und die
    Klassen, die eins davon direkt benutzen - auch ueber einen Getter wie
    plugin.getTrail(), der CamTrail nicht beim Namen nennt
  * die Sammeldateien unter camfly2 und config (CameraPlugin, CamCommand,
    CamSettings, Messages ...): was in den geaenderten Zeilen steht - Felder
    und Getter, Klassen, Schluessel der config.yml und Texte
  * config.yml und lang/en.yml: die Schluessel der geaenderten Zeilen, ueber
    die Stellen im Code, die sie lesen, und die Abschnitte, die sie setzen
  * das Testskript: die Abschnitte, deren Eintrag, Code oder Helfer sich
    geaendert hat

Reine Kommentare zaehlen nicht. Was sich nicht zuordnen laesst, waehlt alle
Abschnitte - lieber zu viel als zu wenig.

Aufgerufen wird es von camfly_testenv.py, die Bereiche jedes Abschnitts stehen
dort in ABSCHNITTE:

    python3 tools/camfly_testenv.py --nur-auswahl
"""

import ast
import bisect
import difflib
import re
import subprocess
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

# Wo was liegt, relativ zum Repository.
JAVA_WURZEL = "src/main/java/de/elia/cameraplugin/"
CONFIG = "src/main/resources/config.yml"
SPRACHE = "src/main/resources/lang/en.yml"
SKRIPT = "tools/camfly_testenv.py"
PLUGIN_YML = "src/main/resources/plugin.yml"

# Pakete, die selbst nichts koennen, sondern alles zusammenhalten: das Plugin
# mit seinen Befehlen und die Konfiguration. Eine Aenderung darin geht nicht
# an das Paket, sondern an das, was in den geaenderten Zeilen steht.
SAMMEL_PAKETE = {"camfly2", "config"}

# Ab so vielen Zeilen ist eine Methode einer Sammeldatei eine Sammelmethode
# wie CamSettings.load oder CamCommand.onCommand: Sie fasst alles an, und wer
# ihr folgte, landete ueberall. Gefolgt wird dann nur den geaenderten Zeilen.
SAMMEL_ZEILEN = 60

# Die Sammeldateien, deren Mitglieder je einer Sache gehoeren: die Getter von
# CamSettings und CameraPlugin. Steht settings.isTrailEnabled() in einer
# geaenderten Zeile von CamCommand, geht es um die Flugspur. Die anderen -
# ConfigReader, Messages ... - sind Werkzeug fuer alle.
REGISTER = {"config/CamSettings", "camfly2/CameraPlugin"}

# Ein Mitglied einer Sammeldatei, das so viele Klassen benutzen, sagt nichts
# ueber die Zeile, in der es steht: plugin.getMessages() oder settings.
ALLGEMEIN_AB = 8

# Dateien, die an den Tests nichts aendern.
EGAL = (re.compile(r"\.md$"), re.compile(r"^\.idea/"), re.compile(r"^\.vscode/"),
        re.compile(r"(^|/)\.gitignore$"), re.compile(r"\.iml$"),
        re.compile(r"^tools/testauswahl\.py$"))

# Namen im Testskript, die an keinem Abschnitt etwas aendern: die Schritte
# vor dem Server, die Zusammenfassung und die Auswahl selbst. Was sonst
# keinem Abschnitt gehoert - der Bot, der Server und seine Einstellungen,
# step_tests mit der Grundprobe -, waehlt alle.
SKRIPT_EGAL = {
    "STEPS", "MC_VERSION", "PAPER_BUILD", "PAPER_API_VERSION", "JDK_URL", "PAPER_REPO",
    "CENTRAL", "FILL_API", "PAPER_API_DEPS", "EXPECTED_API_CALLS", "maven_url",
    "step_jdk", "step_build", "step_paperapi", "step_crosscheck", "APICHECK_JAVA",
    "step_apicheck", "MOBS_OHNE_ANGRIFF", "SCHADENSARTEN_ORDNER", "BIOME_ORDNER",
    "STRUKTUREN_ORDNER", "spiel_jar", "namen_in", "step_names",
    "summary", "main", "Abschnitt", "ABSCHNITTE", "AUSWAHL",
    "markieren", "auswahl_laden", "auswahl_zeigen", "letzte_fehler",
}

BEZEICHNER = re.compile(r"[A-Za-z_]\w*")
ZUGRIFF = re.compile(r"\.\s*([A-Za-z_]\w*)")
AUFRUF = re.compile(r"([A-Za-z_]\w*)\s*\(|::\s*([A-Za-z_]\w*)")
# Woerter, nach denen name( ein Aufruf ist und keine Deklaration.
VOR_AUFRUF = {"return", "new", "throw", "else", "case", "yield", "await", "assert"}
TYP_WORT = re.compile(r"\b(class|interface|enum|record)\s+([A-Za-z_]\w*)")
ANNOTATION = re.compile(r"@(?!interface\b)[\w.]+(\s*\([^()]*\))?")
ZUWEISUNG = re.compile(r"(?<![=!<>])=(?!=)")
SCHLUESSEL_FORM = re.compile(r"^[a-z0-9_-]+(\.[a-z0-9_-]+)*\.?$")
JAVA_WOERTER = {
    "public", "private", "protected", "static", "final", "abstract", "synchronized",
    "native", "transient", "volatile", "strictfp", "default", "sealed", "non", "permits",
    "void",
}


# ---------------------------------------------------------------------------
# git
# ---------------------------------------------------------------------------

def git(repo, *args, check=True):
    proc = subprocess.run(["git", "-C", str(repo), *args], stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, text=True, errors="replace")
    if proc.returncode != 0:
        if check:
            raise RuntimeError(f"git {' '.join(args)}: {proc.stderr.strip()}")
        return None
    return proc.stdout


def basis_finden(repo, seit=None):
    """Die Basis, gegen die verglichen wird: (Commit, wie sie heisst).

    Ohne seit der Stand von master, von dem der Zweig abging. Auf master
    selbst ist das HEAD - verglichen wird dann nur, was noch nicht
    eingecheckt ist."""
    if seit:
        commit = git(repo, "rev-parse", "--verify", "-q", seit + "^{commit}", check=False)
        if commit is None:
            raise RuntimeError(f"--seit {seit}: kein Commit dieses Namens")
        return commit.strip(), seit
    for ref in ("origin/master", "origin/main", "master", "main"):
        if git(repo, "rev-parse", "--verify", "-q", ref + "^{commit}", check=False) is None:
            continue
        basis = git(repo, "merge-base", "HEAD", ref, check=False)
        if basis:
            return basis.strip(), f"Abzweig von {ref}"
        # Ein flacher Klon, der den Abzweig nicht kennt: dann gegen den Stand
        # von master selbst, das waehlt hoechstens zu viel.
        return git(repo, "rev-parse", ref).strip(), ref
    raise RuntimeError("es gibt weder master noch main zum Vergleichen")


def geaenderte_dateien(repo, basis):
    """Jede Datei, die sich seit der Basis geaendert hat, eingecheckt oder
    nicht, als (Pfad, alter Text, neuer Text). Ein Text ist None, wo es die
    Datei nicht gab."""
    namen = set(git(repo, "diff", "--name-only", "--no-renames", basis).split("\n"))
    namen |= set(git(repo, "ls-files", "--others", "--exclude-standard").split("\n"))
    ergebnis = []
    for pfad in sorted(n.strip() for n in namen if n.strip()):
        alt = git(repo, "show", f"{basis}:{pfad}", check=False)
        datei = Path(repo) / pfad
        neu = datei.read_text(encoding="utf-8", errors="replace") if datei.is_file() else None
        if alt != neu:
            ergebnis.append((pfad, alt, neu))
    return ergebnis


def zeilen_vergleichen(alt, neu):
    """Welche Zeilen sich geaendert haben: (alte Zeilen, neue Zeilen,
    Einschuebe).

    alt und neu sind Listen von (Zeilennummer, Inhalt) nur der Zeilen, die
    zaehlen - ohne Kommentare und Leerzeilen. Was nur wegfiel, steht nur bei
    den alten Zeilen, was nur dazukam, nur bei den neuen. Fuer das, was nur
    dazukam, steht unter den Einschueben, zwischen welchen alten Zeilen es
    steht (None am Anfang oder Ende): Mitten in einer Methode aendert es,
    was sie tut."""
    vergleich = difflib.SequenceMatcher(None, [t for _, t in alt], [t for _, t in neu],
                                        autojunk=False)
    alte, neue, einschuebe = set(), set(), []
    for art, a1, a2, b1, b2 in vergleich.get_opcodes():
        if art == "equal":
            continue
        alte.update(z for z, _ in alt[a1:a2])
        neue.update(z for z, _ in neu[b1:b2])
        if art == "insert":
            einschuebe.append((alt[a1 - 1][0] if a1 > 0 else None, alt[a1][0] if a1 < len(alt) else None))
    return alte, neue, einschuebe


# ---------------------------------------------------------------------------
# Java
# ---------------------------------------------------------------------------

def java_entkernen(text):
    """Kommentare entfernen und Zeichenketten leeren, die Zeilen bleiben, wo
    sie sind. Gibt den Code und die Zeichenketten je Zeile zurueck."""
    raus = []
    literale = defaultdict(list)
    i, n, zeile = 0, len(text), 1
    while i < n:
        c = text[i]
        if c == "\n":
            raus.append(c)
            zeile += 1
            i += 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            umbrueche = text.count("\n", i, j)
            raus.append("\n" * umbrueche)
            zeile += umbrueche
            i = j
        elif text.startswith('"""', i):
            j = i + 3
            while j < n and not text.startswith('"""', j):
                j += 2 if text[j] == "\\" else 1
            inhalt = text[i + 3:j]
            literale[zeile].append(inhalt)
            raus.append('""' + "\n" * inhalt.count("\n"))
            zeile += inhalt.count("\n")
            i = min(n, j + 3)
        elif c in "\"'":
            j = i + 1
            while j < n and text[j] != c and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            if c == '"':
                literale[zeile].append(text[i + 1:j])
            raus.append(c + c)
            i = j + 1
        else:
            raus.append(c)
            i += 1
    return "".join(raus), literale


def aufrufe(zeile):
    """Was eine Zeile aufruft: name(...) und ::name. Eine Deklaration wie
    public void onDisable() { ruft nichts auf - steht vor dem Namen ein
    Typ, ist es eine."""
    ergebnis = set()
    for m in AUFRUF.finditer(zeile):
        if m.group(2):
            ergebnis.add(m.group(2))
            continue
        davor = zeile[:m.start()].rstrip()
        wort = re.search(r"(\w+)$", davor)
        if (wort and wort.group(1) not in VOR_AUFRUF) or davor.endswith((">", "]")):
            continue
        ergebnis.add(m.group(1))
    return ergebnis


def gesetzte_namen(zeile):
    """Die Namen, denen eine Zeile Code etwas zuweist: x = ..., x[i] = ..."""
    return set(re.findall(r"\b(\w+)\s*(?:\[[^]]*\]\s*)?(?:[-+*/|&]?=)(?!=)", zeile))


def klammern_weg(text):
    """Was in runden Klammern steht, entfernen, auch verschachtelt."""
    vorher = None
    while vorher != text:
        vorher, text = text, re.sub(r"\([^()]*\)", "", text)
    return text


@dataclass
class Mitglied:
    """Ein Mitglied einer Klasse: Methode, Getter (eine Methode ohne
    Parameter), Feld, Konstante eines enum, Block oder innere Klasse.
    namen sind alle Bezeichner darin, aufrufe die, die es aufruft."""
    name: str
    art: str
    von: int
    bis: int
    namen: set = field(default_factory=set)
    rueckgabe: set = field(default_factory=set)
    privat: bool = False
    aufrufe: set = field(default_factory=set)

    @property
    def zeilen(self):
        return self.bis - self.von + 1


def benennen(stueck, konstanten):
    """[(Name, Art, Typ, privat)] eines Mitglieds aus seinem Text."""
    s = ANNOTATION.sub(" ", stueck)
    if konstanten:
        namen = (BEZEICHNER.search(t) for t in klammern_weg(s).split(","))
        return [(m.group(), "konstante", set(), False) for m in namen if m]
    kopf = s.split("{", 1)[0]
    m = TYP_WORT.search(kopf)
    if m:
        return [(m.group(2), "typ", set(), "private" in kopf[:m.start()].split())]
    ohne = klammern_weg(kopf)
    if not BEZEICHNER.search(ohne.replace("static", "")):
        return [("<block>", "block", set(), True)]
    gleich = ZUWEISUNG.search(ohne)
    klammer = re.search(r"([A-Za-z_]\w*)\s*\(", kopf)
    if klammer and (gleich is None or kopf.find("(") < kopf.find("=")):
        vorne = BEZEICHNER.findall(kopf[:klammer.start(1)])
        typ = {w for w in vorne if w not in JAVA_WOERTER}
        leer = re.match(r"\s*\)", kopf[klammer.end():]) is not None
        return [(klammer.group(1), "getter" if leer else "methode", typ, "private" in vorne)]
    deklaration = ohne[:gleich.start()] if gleich else ohne.rstrip().rstrip(";")
    namen, tiefe, teil = [], 0, ""
    for c in deklaration + ",":
        tiefe += {"<": 1, ">": -1}.get(c, 0)
        if c == "," and tiefe == 0:
            woerter = BEZEICHNER.findall(teil)
            if woerter:
                namen.append(woerter[-1])
            teil = ""
        else:
            teil += c
    woerter = BEZEICHNER.findall(deklaration)
    typ = {w for w in woerter if w not in JAVA_WOERTER} - set(namen)
    return [(n, "feld", typ, "private" in woerter) for n in namen]


class JavaDatei:
    """Eine Java-Datei, zerlegt in Zeilen, Zeichenketten und Mitglieder."""

    def __init__(self, pfad, text):
        self.pfad = pfad
        self.paket = ".".join(pfad[len(JAVA_WURZEL):].split("/")[:-1]) or "-"
        self.klasse = Path(pfad).stem
        self.name = f"{self.paket}/{self.klasse}"
        self.sammel = self.paket in SAMMEL_PAKETE
        code, self.literale = java_entkernen(text)
        self.zeilen = code.split("\n")
        self.zeilen_namen = {i: set(BEZEICHNER.findall(z)) for i, z in enumerate(self.zeilen, 1)}
        # Was hinter einem Punkt steht: settings.isTrailEnabled() greift auf
        # ein Mitglied einer anderen Klasse zu, ein nacktes trail nicht.
        self.zeilen_zugriffe = {i: set(ZUGRIFF.findall(z)) for i, z in enumerate(self.zeilen, 1)}
        self.zeilen_aufrufe = {i: aufrufe(z) for i, z in enumerate(self.zeilen, 1)}
        self.namen = set().union(*self.zeilen_namen.values())
        self.aufrufe = set().union(*self.zeilen_aufrufe.values())
        self.typen = set()
        self.mitglieder = self._zerlegen(code)
        self.mitglied_namen = {m.name for m in self.mitglieder}
        # Methoden werden aufgerufen, alles andere beim Namen genannt. Ein
        # Parameter reason ist nicht die Methode reason(...) einer anderen
        # Klasse.
        self.nur_methoden = {n for n in self.mitglied_namen
                             if all(m.art in ("methode", "getter") for m in self.mitglieder if m.name == n)}
        self.privat = {n for n in self.mitglied_namen
                       if all(m.privat for m in self.mitglieder if m.name == n)}

    def nennt(self, x, name, zeilen=None):
        """Ob x - ein Mitglied, eine Datei oder Zeilen einer Datei - das
        Mitglied name dieser Datei nennt."""
        if zeilen is not None:
            if name in self.nur_methoden:
                return any(name in x.zeilen_aufrufe[z] for z in zeilen)
            return any(name in x.zeilen_namen[z] for z in zeilen)
        return name in (x.aufrufe if name in self.nur_methoden else x.namen)

    def _zerlegen(self, code):
        """Die Mitglieder der Klassen auf oberster Ebene. Eine innere Klasse
        ist ein Mitglied, mit allem, was in ihr steht."""
        umbrueche = [i for i, c in enumerate(code) if c == "\n"]

        def zeile(pos):
            return bisect.bisect_left(umbrueche, pos) + 1

        mitglieder = []
        tiefe, start, kopf_ende, konstanten = 0, 0, None, False

        def fertig(ende):
            nonlocal start, kopf_ende, konstanten
            stueck = code[start:ende + 1]
            if stueck.strip(" \t\n;,{}"):
                von = zeile(start + len(stueck) - len(stueck.lstrip()))
                bis = zeile(ende)
                namen = set().union(*(self.zeilen_namen[z] for z in range(von, bis + 1)))
                aufrufe = set().union(*(self.zeilen_aufrufe[z] for z in range(von, bis + 1)))
                for name, art, typ, privat in benennen(stueck, konstanten):
                    mitglieder.append(Mitglied(name, art, von, bis, namen, typ, privat, aufrufe))
                konstanten = False
            start, kopf_ende = ende + 1, None

        for i, c in enumerate(code):
            if c == "{":
                if tiefe == 0:
                    kopf = ANNOTATION.sub(" ", code[start:i])
                    m = TYP_WORT.search(kopf)
                    if m:
                        self.typen.add(m.group(2))
                        # Im Rumpf eines enum stehen zuerst die Konstanten.
                        konstanten = m.group(1) == "enum"
                        komponenten = re.search(r"\((.*)\)", kopf, re.S)
                        if m.group(1) == "record" and komponenten:
                            for teil in komponenten.group(1).split(","):
                                woerter = BEZEICHNER.findall(teil)
                                if woerter:
                                    mitglieder.append(Mitglied(woerter[-1], "feld", zeile(i), zeile(i),
                                                               set(woerter)))
                    start, kopf_ende = i + 1, None
                elif tiefe == 1 and kopf_ende is None:
                    kopf_ende = i
                tiefe += 1
            elif c == "}":
                tiefe -= 1
                if tiefe == 0:
                    fertig(i - 1)
                elif tiefe == 1:
                    # Ein Feld mit { im Wert - new int[] {..}, eine anonyme
                    # Klasse, ein Lambda - endet erst am Semikolon.
                    kopf = klammern_weg(code[start:kopf_ende] if kopf_ende else "")
                    if not ZUWEISUNG.search(kopf):
                        fertig(i)
            elif c == ";" and tiefe == 1:
                fertig(i)
        return mitglieder

    def mitglied_bei(self, zeile):
        """Das kleinste Mitglied, in dem die Zeile steht."""
        treffer = [m for m in self.mitglieder if m.von <= zeile <= m.bis]
        return min(treffer, key=lambda m: m.zeilen) if treffer else None

    def anweisung(self, zeile):
        """Die Zeilen der ganzen Anweisung um eine Zeile herum, und wo sie eine
        lokale Variable setzt, die Zeilen danach, die sie benutzen:

            String effects = config.getChoice("camera-mode.start-with-effects", ...);
            startWithEffects = switch (effects) { ...

        Was gelesen wird, steht in der ersten Zeile, wohin es geht, erst in
        der zweiten."""
        def ende(z):
            t = self.zeilen[z - 1].strip()
            return not t or t[-1] in ";{}"

        von = zeile
        while von > 1 and not ende(von - 1):
            von -= 1
        bis = zeile
        while bis < len(self.zeilen) and not ende(bis):
            bis += 1
        ergebnis = set(range(von, bis + 1))
        lokale = set()
        for z in ergebnis:
            lokale |= {n for n in re.findall(r"\b([a-z]\w*)\s*=(?!=)", self.zeilen[z - 1])
                       if n not in self.mitglied_namen}
        m = self.mitglied_bei(zeile)
        if lokale and m is not None:
            for z in range(bis + 1, min(m.bis, bis + 30) + 1):
                if lokale & self.zeilen_namen[z]:
                    ergebnis.add(z)
        return ergebnis

    def zaehlende_zeilen(self):
        """Die Zeilen fuer den Vergleich: ohne Kommentare, Leerzeilen,
        Einrueckung, import und package, mit ihren Zeichenketten."""
        ergebnis = []
        for i, z in enumerate(self.zeilen, 1):
            t = " ".join(z.split())
            if self.literale.get(i):
                t += " " + repr(self.literale[i])
            if t and not t.startswith(("import ", "package ")):
                ergebnis.append((i, t))
        return ergebnis


class JavaIndex:
    """Alle Java-Dateien des Plugins und wer wen kennt."""

    def __init__(self, dateien):
        self.dateien = dateien
        self.pfade = {d.pfad: d for d in dateien}
        self.klassen = {}
        for d in dateien:
            for typ in sorted(d.typen | {d.klasse}):
                self.klassen.setdefault(typ, d)
        for d in dateien:
            for m in d.mitglieder:
                if m.art == "typ":
                    self.klassen.setdefault(m.name, d)
        # Welcher Getter einen Typ des Plugins zurueckgibt: plugin.getTrail()
        # benutzt CamTrail, ohne den Namen hinzuschreiben. Nur Methoden ohne
        # Parameter - HitPush.of(...) ist eine Fabrik, und of steht auch in
        # jedem List.of(...).
        self.getter = defaultdict(set)
        for d in dateien:
            for m in d.mitglieder:
                if m.art == "getter":
                    for typ in m.rueckgabe:
                        if typ in self.klassen:
                            self.getter[self.klassen[typ].name].add(m.name)
        self.literal_stellen = defaultdict(list)
        for d in dateien:
            for z, liste in d.literale.items():
                for text in liste:
                    self.literal_stellen[text].append((d, z))

    def kennt(self, g, d):
        """Ob Datei g die Klasse in d kennt: beim Namen oder ueber einen Getter."""
        return bool((d.typen | {d.klasse}) & g.namen or self.getter[d.name] & g.namen)

    def benutzer(self, d, name):
        """Die anderen Dateien, die das Mitglied name von d benutzen."""
        if name in d.privat:
            return []
        return [g for g in self.dateien if g is not d and d.nennt(g, name) and self.kennt(g, d)]


@dataclass
class Betroffen:
    """Was eine Aenderung beruehrt. Klassen als paket/Klasse oder, wo nur das
    Paket bekannt ist, paket/*; Schluessel als config:pfad oder
    lang:schluessel. direkt sind die Klassen, die sich selbst geaendert
    haben."""
    klassen: set = field(default_factory=set)
    schluessel: set = field(default_factory=set)
    direkt: set = field(default_factory=set)
    unklar: list = field(default_factory=list)

    def dazu(self, anderes):
        self.klassen |= anderes.klassen
        self.schluessel |= anderes.schluessel
        self.direkt |= anderes.direkt
        self.unklar += anderes.unklar


class Folger:
    """Folgt Aenderungen durch den Code des Plugins."""

    def __init__(self, index, config_pfade, sprach_schluessel):
        self.index = index
        self.config_pfade = config_pfade
        self.sprach_schluessel = sprach_schluessel
        self.pakete = {d.paket for d in index.dateien}
        self.gefolgt = {}
        self._allgemein = {}

    def schluessel_von(self, text):
        """Der Schluessel der config.yml oder der Sprachdatei, den eine
        Zeichenkette im Code nennt, oder None."""
        if text in self.sprach_schluessel:
            return "lang:" + text
        if SCHLUESSEL_FORM.match(text) and "." in text:
            pfad = text.rstrip(".")
            if pfad.split(".")[0] in self.config_pfade:
                return "config:" + pfad
        return None

    def allgemein(self, d, name):
        """Ob so viele Klassen das Mitglied name von d benutzen, dass es
        nichts ueber die Zeile sagt, in der es steht."""
        schluessel = (d.pfad, name)
        if schluessel not in self._allgemein:
            nutzer = {g.name for g in self.index.benutzer(d, name) if not g.sammel}
            self._allgemein[schluessel] = len(nutzer) >= ALLGEMEIN_AB
        return self._allgemein[schluessel]

    def mitglieder(self, d, namen):
        """Geaenderten Mitgliedern von d folgen: in d zu allen Mitgliedern,
        die sie benutzen, und von dort zu den Klassen, die eins davon direkt
        benutzen. Durch eine Sammeldatei geht es weiter, ausser durch ihre
        Sammelmethoden."""
        b = Betroffen({d.name})
        for name in namen:
            schluessel = (d.pfad, name)
            if schluessel not in self.gefolgt:
                self.gefolgt[schluessel] = Betroffen()     # gegen Kreise
                self.gefolgt[schluessel] = self._mitglied(d, name)
            b.dazu(self.gefolgt[schluessel])
        return b

    def _mitglied(self, d, name):
        b = Betroffen({d.name})
        alle, offen = set(), [name]
        while offen:
            n = offen.pop()
            if n in alle:
                continue
            alle.add(n)
            offen += [m.name for m in d.mitglieder if d.nennt(m, n) and m.name not in alle
                      and not (d.sammel and m.zeilen > SAMMEL_ZEILEN)]
        felder = {m.name for m in d.mitglieder if m.art == "feld"}
        for m in d.mitglieder:
            if m.name in alle and m.zeilen <= SAMMEL_ZEILEN:
                for f in (m.namen | {m.name}) & felder:
                    b.schluessel |= self.feld_schluessel(d, f)
        for n in alle:
            for g in self.index.benutzer(d, n):
                b.klassen.add(g.name)
                if g.sammel:
                    weiter = {m.name for m in g.mitglieder if d.nennt(m, n) and m.zeilen <= SAMMEL_ZEILEN}
                    b.dazu(self.mitglieder(g, weiter))
        return b

    def feld_schluessel(self, d, feld):
        """Die Schluessel der config.yml, aus denen ein Feld gelesen wird:
        die Zeichenketten der Anweisungen, in denen es steht."""
        ergebnis = set()
        for z, namen in d.zeilen_namen.items():
            if feld in namen and d.literale.get(z):
                for text in d.literale[z]:
                    s = self.schluessel_von(text)
                    if s and s.startswith("config:"):
                        ergebnis.add(s)
        return ergebnis

    def sammel_zeile(self, d, z, ziel=None):
        """Einer geaenderten Zeile einer Sammeldatei folgen: Klassen und
        Getter darin, eigene Felder und Methoden, die von CamSettings und
        CameraPlugin, Paketnamen und Schluessel in Zeichenketten.

        d ist der Stand, in dem die Zeile steht, ziel der im Index - fuer
        eine weggefallene Zeile der neue Stand derselben Datei."""
        ziel = ziel or d
        b = Betroffen({d.name})
        zeilen = d.anweisung(z)
        namen = set().union(*(d.zeilen_namen[x] for x in zeilen))
        zugriffe = set().union(*(d.zeilen_zugriffe[x] for x in zeilen))
        # Eigene Felder, die die Zeilen setzen - auch ueber eine eigene
        # Methode, die sie aufrufen: readSignalLevels(config) in load setzt
        # signalLevels. Was sie nur lesen oder aufrufen, hat sich nicht
        # geaendert: warnings.add(...) aendert warnings nicht, und wer allem
        # folgte, was warnings liest, landete ueberall.
        gesetzt = set().union(*(gesetzte_namen(d.zeilen[x - 1]) for x in zeilen))
        for m in ziel.mitglieder:
            if m.name in ziel.nur_methoden and ziel.nennt(d, m.name, zeilen):
                gesetzt |= set().union(*(gesetzte_namen(ziel.zeilen[x - 1]) for x in range(m.von, m.bis + 1)))
        eigene = set()
        for n in namen | gesetzt:
            k = self.index.klassen.get(n)
            if k is not None and k.pfad != d.pfad and not k.sammel:
                b.klassen.add(k.name)
            if n in gesetzt and n in ziel.mitglied_namen and n not in ziel.nur_methoden \
                    and not self.allgemein(ziel, n):
                eigene.add(n)
        for n in zugriffe:
            for klasse, getter in self.index.getter.items():
                if n in getter and klasse.split("/")[0] not in SAMMEL_PAKETE:
                    b.klassen.add(klasse)
            for g in self.index.dateien:
                if g.pfad != d.pfad and g.name in REGISTER and n in g.mitglied_namen \
                        and n not in g.privat and g.nennt(d, n, zeilen) and not self.allgemein(g, n):
                    b.dazu(self.mitglieder(g, {n}))
        m = d.mitglied_bei(z)
        if m is not None and m.zeilen <= SAMMEL_ZEILEN and m.name in ziel.mitglied_namen:
            eigene.add(m.name)
        b.dazu(self.mitglieder(ziel, eigene))
        for text in (t for x in sorted(zeilen) for t in d.literale.get(x, ())):
            if text in self.pakete:
                b.klassen.add(text + "/*")
            s = self.schluessel_von(text)
            if s:
                b.schluessel.add(s)
                b.dazu(self.literal(text, ausser=d))
        return b

    def literal(self, text, ausser=None):
        """Die Klassen ausserhalb der Sammeldateien, in denen die Zeichenkette
        steht."""
        b = Betroffen()
        for g, _ in self.index.literal_stellen.get(text, ()):
            if not g.sammel and (ausser is None or g.pfad != ausser.pfad):
                b.klassen.add(g.name)
        return b

    def java(self, pfad, alt, neu):
        """Einer geaenderten Java-Datei folgen."""
        b = Betroffen()
        staende = [(JavaDatei(pfad, t) if t is not None else None) for t in (alt, neu)]
        alte, neue, einschuebe = zeilen_vergleichen(*(d.zaehlende_zeilen() if d else [] for d in staende))
        if staende[0] is not None:
            for vorher, nachher in einschuebe:
                m = staende[0].mitglied_bei(vorher) if vorher else None
                if m is not None and nachher is not None and m is staende[0].mitglied_bei(nachher):
                    alte.add(vorher)
        # Gefolgt wird im neuen Stand, den der Index kennt. Eine weggefallene
        # Zeile sagt mit ihrem Mitglied, was sich geaendert hat. Gibt es die
        # Datei nicht mehr, hat sich mitgeaendert, wer sie benutzte.
        ziel = self.index.pfade.get(pfad)
        for d, zeilen in zip(staende, (alte, neue)):
            if d is None or not zeilen:
                continue
            b.klassen.add(d.name)
            b.direkt.add(d.name)
            if ziel is None:
                continue
            geaendert = set()
            for z in sorted(zeilen):
                for text in d.literale.get(z, ()):
                    s = self.schluessel_von(text)
                    if s:
                        b.schluessel.add(s)
                if d.sammel:
                    b.dazu(self.sammel_zeile(d, z, ziel))
                else:
                    m = d.mitglied_bei(z)
                    # Ausserhalb jedes Mitglieds steht der Kopf der Klasse.
                    geaendert.add(m.name if m is not None else d.klasse)
            b.dazu(self.mitglieder(ziel, geaendert & (ziel.mitglied_namen | {ziel.klasse})))
        return b

    def yaml(self, sprache, pfad, vorhanden=True):
        """Einem geaenderten Schluessel der config.yml oder der Sprachdatei
        folgen: zu den Stellen im Code, die ihn lesen. vorhanden sagt, ob es
        den Schluessel im neuen Stand noch gibt."""
        b = Betroffen()
        teile = pfad.split(".")
        if sprache and teile[0] in ("messages", "message-settings"):
            if len(teile) < 2:
                return b
            # Die Texte nennt der Code ohne den Abschnitt: "trail-off". Wo
            # er etwas anhaengt - button("trail-button-all") liest auch
            # trail-button-all-hover -, steht dort ein Anfang des Namens.
            text = ".".join(teile[1:])
            b.schluessel.add("lang:" + text)
            stuecke = text.split("-")
            kandidaten = [text] + ["-".join(stuecke[:i]) for i in range(len(stuecke) - 1, 1, -1)]
            kinder = False
        else:
            # Ein Schluessel, sonst der Abschnitt darueber, den der Code als
            # Ganzes liest, sonst - fuer den Kopf eines Abschnitts - alles
            # darunter.
            kandidaten = [".".join(teile[:i]) for i in range(len(teile), 0, -1)]
            kinder = True
            if not sprache:
                b.schluessel.add("config:" + pfad)
        stellen = self.index.literal_stellen
        treffer = []
        for k in kandidaten:
            treffer = stellen.get(k, []) + stellen.get(k + ".", [])
            if treffer:
                break
        if not treffer and kinder:
            treffer = [s for text, liste in stellen.items() if text.startswith(pfad + ".") for s in liste]
        if not treffer:
            treffer = self.zusammengesetzt(kandidaten[0])
        b.dazu(self.stellen_folgen(treffer))
        if not treffer and vorhanden:
            b.unklar.append(f"{pfad} steht nirgends im Code")
        # Weggefallen und auch im Code nicht mehr da: Was ihn las, ist mit ihm
        # gegangen und steht unter den geaenderten Java-Dateien.
        return b

    def zusammengesetzt(self, schluessel):
        """Die Stellen, an denen der Code einen Schluessel zusammensetzt: der
        laengste Anfang - "banned-changed-" + was, "cost-point" + "s" - und
        jedes Ende - prefix + "-area"."""
        stellen = self.index.literal_stellen
        anfaenge = [t for t in stellen if len(t) >= 4 and "-" in t and SCHLUESSEL_FORM.match(t)
                    and schluessel.startswith(t)]
        treffer = list(stellen[max(anfaenge, key=len)]) if anfaenge else []
        return treffer + [s for t, liste in stellen.items()
                          if len(t) >= 3 and t[0] == "-" and SCHLUESSEL_FORM.match(t[1:])
                          and schluessel.endswith(t) for s in liste]

    def stellen_folgen(self, stellen):
        """Den Stellen im Code folgen, an denen eine Zeichenkette steht."""
        b = Betroffen()
        for d, z in stellen:
            b.dazu(self.sammel_zeile(d, z) if d.sammel else Betroffen({d.name}))
        return b


# ---------------------------------------------------------------------------
# config.yml und lang/en.yml
# ---------------------------------------------------------------------------

YAML_SCHLUESSEL = re.compile(r"^(\s*)([^\s#:'\"-][^:#]*?|'[^']*'|\"[^\"]*\"):(\s|$)")


def yaml_schluessel(text):
    """Die Schluessel jeder Zeile als Tupel von aussen nach innen, oder None
    bei Kommentaren und Leerzeilen. Listen und Fortsetzungen gehoeren zum
    Schluessel darueber. Index 0 bleibt leer, Zeilen zaehlen ab 1."""
    ergebnis, stapel = [None], []
    for zeile in text.split("\n"):
        inhalt = zeile.strip()
        if not inhalt or inhalt.startswith("#"):
            ergebnis.append(None)
            continue
        m = YAML_SCHLUESSEL.match(zeile)
        if m:
            tief = len(m.group(1))
            while stapel and stapel[-1][0] >= tief:
                stapel.pop()
            stapel.append((tief, m.group(2).strip("'\"")))
        ergebnis.append(tuple(s for _, s in stapel) or None)
    return ergebnis


def yaml_pfade(text):
    """Wie yaml_schluessel, mit Punkten zum ganzen Pfad verbunden."""
    return [".".join(t) if t else None for t in yaml_schluessel(text)]


def yaml_aenderungen(alt, neu):
    """Die Schluessel der geaenderten Zeilen, alt wie neu, als Tupel."""
    staende = []
    for text in (alt, neu):
        schluessel = yaml_schluessel(text or "")
        zeilen = [(i, " ".join(z.split())) for i, z in enumerate((text or "").split("\n"), 1)
                  if schluessel[i]]
        staende.append((schluessel, zeilen if text else []))
    alte, neue, _ = zeilen_vergleichen(staende[0][1], staende[1][1])
    return {staende[0][0][z] for z in alte} | {staende[1][0][z] for z in neue}


def plugin_yml(folger, alt, neu):
    """Einer geaenderten plugin.yml folgen. Ein Recht fuehrt zu den Klassen,
    die es abfragen - camplugin.trail zu CamTrail -, der Befehl selbst steht
    in jedem Test. Alles andere - main, api-version, depend - betrifft alle."""
    b = Betroffen()
    vorhanden = set(yaml_schluessel(neu or ""))
    for teile in sorted(yaml_aenderungen(alt, neu)):
        if teile[0] == "commands":
            continue
        if teile[0] == "permissions" and len(teile) >= 2:
            stellen = folger.index.literal_stellen.get(teile[1], [])
            b.dazu(folger.stellen_folgen(stellen))
            if stellen or teile[:2] not in {t[:2] for t in vorhanden if t}:
                continue
        b.unklar.append(f"{'.'.join(teile)} betrifft alle")
        break
    return b


# ---------------------------------------------------------------------------
# Das Testskript
# ---------------------------------------------------------------------------

def ohne_docstrings(knoten):
    """Der Knoten als Text zum Vergleichen, ohne Docstrings und Kommentare:
    Ein geaenderter Text darin aendert keinen Test."""
    knoten = ast.parse(ast.unparse(knoten))
    for k in ast.walk(knoten):
        if isinstance(k, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)) and k.body \
                and isinstance(k.body[0], ast.Expr) and isinstance(k.body[0].value, ast.Constant) \
                and isinstance(k.body[0].value.value, str):
            k.body = k.body[1:] or [ast.Pass()]
    return ast.dump(knoten)


class Skript:
    """Das Testskript: was auf oberster Ebene steht, wer welchen Helfer
    benutzt und welche Werte setzt."""

    def __init__(self, text, funktionen):
        """funktionen: Abschnitt -> die Namen seiner Funktionen."""
        self.defs = {}
        for knoten in ast.parse(text).body:
            if isinstance(knoten, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                self.defs[knoten.name] = knoten
            elif isinstance(knoten, (ast.Assign, ast.AnnAssign)):
                for ziel in (knoten.targets if isinstance(knoten, ast.Assign) else [knoten.target]):
                    if isinstance(ziel, ast.Name):
                        self.defs[ziel.id] = knoten
        self.funktionen = funktionen
        self._huelle = {}

    def eintraege(self):
        """Die Eintraege der Tabelle ABSCHNITTE: Name -> Text des Eintrags."""
        ergebnis = {}
        for k in ast.walk(self.defs.get("ABSCHNITTE", ast.Pass())):
            if isinstance(k, ast.Call) and isinstance(k.func, ast.Name) and k.func.id == "Abschnitt" \
                    and k.args and isinstance(k.args[0], ast.Constant):
                ergebnis[k.args[0].value] = ast.dump(k)
        return ergebnis

    def huelle(self, name):
        """Was auf oberster Ebene name benutzt, auch ueber Helfer, mit name."""
        if name not in self._huelle:
            ergebnis, offen = set(), [name]
            while offen:
                n = offen.pop()
                if n in ergebnis or n not in self.defs or n == "ABSCHNITTE":
                    continue
                ergebnis.add(n)
                offen += [k.id for k in ast.walk(self.defs[n]) if isinstance(k, ast.Name)]
            self._huelle[name] = ergebnis
        return self._huelle[name]

    def abschnitt_huelle(self, abschnitt):
        return set().union(set(), *(self.huelle(f) for f in self.funktionen.get(abschnitt, ())))

    def knoten(self, namen, art):
        for n in namen:
            for k in ast.walk(self.defs[n]):
                if isinstance(k, art):
                    yield k

    def attribute(self, namen):
        """Die Attribute (bot.chat, bot.server_pos ...), die diese Definitionen benutzen."""
        return {k.attr for k in self.knoten(namen, ast.Attribute)}

    def zeichenketten(self, namen):
        return {k.value for k in self.knoten(namen, ast.Constant) if isinstance(k.value, str)}

    def methoden(self, klasse):
        k = self.defs.get(klasse)
        return {m.name: m for m in k.body if isinstance(m, ast.FunctionDef)} \
            if isinstance(k, ast.ClassDef) else {}

    def gesetzte_werte(self, namen, config_pfade):
        """Die Schluessel, die set_option und set_options in diesen
        Definitionen setzen, als config:pfad oder lang:schluessel."""
        ergebnis = set()
        for k in self.knoten(namen, ast.Call):
            if not isinstance(k.func, ast.Name):
                continue
            aenderungen = []
            if k.func.id == "set_option" and len(k.args) >= 3:
                aenderungen.append(k.args[1:2] + k.args[3:4])
            elif k.func.id == "set_options" and len(k.args) >= 2 \
                    and isinstance(k.args[1], (ast.List, ast.Tuple)):
                aenderungen += [e.elts[0:1] + e.elts[2:3] for e in k.args[1].elts
                                if isinstance(e, ast.Tuple) and len(e.elts) >= 2]
            for teile in aenderungen:
                werte = [t.value if isinstance(t, ast.Constant) else None for t in teile]
                schluessel, abschnitt = werte[0], (werte[1] if len(werte) > 1 else None)
                if not isinstance(schluessel, str):
                    continue
                if abschnitt in ("messages", "message-settings"):
                    ergebnis.add("lang:" + schluessel)
                    continue
                ergebnis |= {"config:" + p for p in config_pfade
                             if p.split(".")[-1] == schluessel
                             and (abschnitt is None or p.split(".")[0] == abschnitt)}
        return ergebnis


BOT_FALL = re.compile(r"^ {4}case '(\w+)'")
JS_NAME = re.compile(r"[A-Za-z_$][\w$]*")


class BotSkript:
    """BOT_JS, zerlegt in die Aktionen des switch (case 'fly' ...) und die
    Bloecke auf oberster Ebene davor: Listener, die Pakete mitschreiben,
    Helfer und die Verbindung selbst."""

    def __init__(self, text):
        self.zeilen = text.split("\n")
        self.faelle = {}       # Aktion -> (von, bis)
        aktuell = None
        for i, z in enumerate(self.zeilen, 1):
            m = BOT_FALL.match(z)
            if m:
                aktuell = m.group(1)
                self.faelle[aktuell] = (i, i)
            elif aktuell and z.strip() and len(z) - len(z.lstrip()) <= 4:
                aktuell = None
            elif aktuell:
                self.faelle[aktuell] = (self.faelle[aktuell][0], i)
        anfaenge = [i for i, z in enumerate(self.zeilen, 1)
                    if z.strip() and not z[0].isspace() and z[0] not in "})]"]
        self.bloecke = [(a, b - 1) for a, b in zip(anfaenge, anfaenge[1:] + [len(self.zeilen) + 1])]
        self.oben = {m.group(2) for z in self.zeilen
                     for m in [re.match(r"(?:async\s+)?(let|const|var|function)\s+([\w$]+)", z)] if m}

    def text(self, von, bis):
        return "\n".join(self.zeilen[von - 1:bis])

    def fall(self, z):
        return next((a for a, (von, bis) in self.faelle.items() if von <= z <= bis), None)

    def block(self, z):
        return next(((von, bis) for von, bis in self.bloecke if von <= z <= bis), None)

    def geschrieben(self, von, bis):
        """Was ein Block auf oberster Ebene anlegt oder beschreibt: let x,
        function f, x = ..., x[i].y = ..., x.push(...)."""
        text = self.text(von, bis)
        namen = {m.group(1) for m in re.finditer(r"([\w$]+)(?:\[[^\]]*\])*(?:\.[\w$]+)*\s*(?:=(?![=>])|\.push\()",
                                                 text)}
        kopf = re.match(r"(?:async\s+)?(?:let|const|var|function)\s+([\w$]+)", self.zeilen[von - 1])
        return (namen & self.oben) | ({kopf.group(1)} if kopf else set())

    def aktionen_fuer(self, namen):
        """Die Aktionen, die einen dieser Namen lesen, auch ueber einen
        anderen Block - einen Helfer, der ihn liest."""
        namen = set(namen)
        for von, bis in self.bloecke:
            if JS_NAME.findall(self.text(von, bis)) and namen & set(JS_NAME.findall(self.text(von, bis))) \
                    and not any(von <= a <= bis for a, _ in self.faelle.values()):
                namen |= self.geschrieben(von, bis)
        return {a for a, (von, bis) in self.faelle.items() if namen & set(JS_NAME.findall(self.text(von, bis)))}


def bot_aktionen(alt, neu):
    """Die Bot-Aktionen (case 'fly' ...) in BOT_JS, die eine Aenderung
    betrifft: die geaenderten, und fuer eine Aenderung davor die, die lesen,
    was der geaenderte Block anlegt oder beschreibt - ein Listener, der die
    Bossbars mitschreibt, betrifft die Aktion bossbars."""
    def zaehlend(text):
        return [(i, " ".join(z.split())) for i, z in enumerate(text.split("\n"), 1)
                if z.strip() and not z.strip().startswith("//")]

    alte, neue, _ = zeilen_vergleichen(zaehlend(alt), zaehlend(neu))
    aktionen = set()
    for skript, zeilen in ((BotSkript(alt), alte), (BotSkript(neu), neue)):
        for z in zeilen:
            if not JS_NAME.search(skript.zeilen[z - 1]):
                continue    # nur Klammern: sie gehoeren zu den Zeilen daneben
            fall = skript.fall(z)
            if fall:
                aktionen.add(fall)
                continue
            block = skript.block(z)
            if block:
                aktionen |= skript.aktionen_fuer(skript.geschrieben(*block))
    return aktionen


def methoden_vergleichen(alt, neu):
    """Die Methoden einer Klasse, die sich geaendert haben, oder None, wenn
    sich ausserhalb ihrer Methoden etwas geaendert hat."""
    def teile(k):
        methoden = {m.name: ohne_docstrings(m) for m in k.body if isinstance(m, ast.FunctionDef)}
        rest = [ast.dump(m) for m in k.body if not isinstance(m, ast.FunctionDef)
                and not (isinstance(m, ast.Expr) and isinstance(m.value, ast.Constant))]
        return methoden, rest + [ast.dump(b) for b in k.bases]
    (am, ar), (nm, nr) = teile(alt), teile(neu)
    if ar != nr:
        return None
    return {n for n in set(am) | set(nm) if am.get(n) != nm.get(n)}


def skript_aenderungen(alt_text, neu_text, funktionen):
    """Was eine Aenderung am Testskript betrifft: (Abschnitt -> Gruende,
    Grund fuer alle oder None).

    Ein Abschnitt ist betroffen, wenn sich sein Eintrag in ABSCHNITTE, seine
    Funktion oder ein Helfer geaendert hat, den er benutzt - auch eine
    Methode von BotClient oder eine Aktion des Bots. Alles andere, was die
    Tests im Spiel benutzen, betrifft alle."""
    neu = Skript(neu_text, funktionen)
    gruende = defaultdict(list)
    try:
        alt = Skript(alt_text, funktionen) if alt_text else None
    except SyntaxError:
        alt = None
    if alt is None:
        return {}, "das Testskript ist neu"

    alt_eintraege = alt.eintraege()
    for name, eintrag in neu.eintraege().items():
        if alt_eintraege.get(name) != eintrag:
            gruende[name].append("neu im Testskript" if name not in alt_eintraege
                                 else "Eintrag in ABSCHNITTE geaendert")

    huellen = {a: neu.abschnitt_huelle(a) for a in funktionen}
    # Was den Server und den Bot einrichtet, gilt fuer jeden Abschnitt, auch
    # wenn einer es selbst noch einmal aufruft - der Absturztest richtet den
    # Server nach dem Neustart neu ein.
    einrichtung = (neu.huelle("step_server") | neu.huelle("step_bot")) - {"BOT_JS"}
    # Die Schritte ruft main ueber ihren Namen auf, nicht beim Namen.
    benutzt = {k.id for d in neu.defs.values() for k in ast.walk(d) if isinstance(k, ast.Name)} \
        | {n for n in neu.defs if n.startswith("step_")}
    for name in sorted(set(alt.defs) | set(neu.defs)):
        a, n = alt.defs.get(name), neu.defs.get(name)
        if name in SKRIPT_EGAL or (a is not None and n is not None
                                   and ohne_docstrings(a) == ohne_docstrings(n)):
            continue
        if name == "BOT_JS" and a is not None and n is not None:
            aktionen = bot_aktionen(ast.literal_eval(a.value), ast.literal_eval(n.value))
            # bot.chat(...) schickt die Aktion chat, ohne sie hinzuschreiben.
            methoden = {m for m, k in neu.methoden("BotClient").items()
                        if aktionen & {c.value for c in ast.walk(k) if isinstance(c, ast.Constant)}}
            for x, h in huellen.items():
                if aktionen & neu.zeichenketten(h) or methoden & neu.attribute(h):
                    gruende[x].append("Bot-Aktion " + kurz(aktionen) + " geaendert")
            continue
        if name in einrichtung:
            return gruende, f"{name} richtet den Server oder den Bot ein"
        treffer = [x for x, h in huellen.items() if name in h]
        if treffer:
            for x in treffer:
                gruende[x].append(f"{name} im Testskript geaendert")
            continue
        if a is None or n is None or name not in benutzt:
            # Neu und von keinem Abschnitt benutzt, weg, oder von nichts im
            # Skript benutzt: aendert nichts.
            continue
        if isinstance(a, ast.ClassDef) and isinstance(n, ast.ClassDef):
            methoden = methoden_vergleichen(a, n)
            if methoden is not None:
                for x, h in huellen.items():
                    if methoden & neu.attribute(h):
                        gruende[x].append(f"{name}.{kurz(methoden)} geaendert")
                continue
        return gruende, f"{name} im Testskript geaendert"
    return gruende, None


# ---------------------------------------------------------------------------
# Die Auswahl
# ---------------------------------------------------------------------------

@dataclass
class Auswahl:
    """Welche Abschnitte laufen: alle, mit dem Grund dafuer, oder die in
    abschnitte, jeder mit seinen Gruenden."""
    basis: str = ""
    basis_name: str = ""
    alle: str = None
    abschnitte: dict = field(default_factory=dict)
    dateien: list = field(default_factory=list)
    ohne_abschnitt: list = field(default_factory=list)

    def gewaehlt(self, name):
        return self.alle is not None or name in self.abschnitte

    def dazu(self, name, grund):
        liste = self.abschnitte.setdefault(name, [])
        if grund not in liste:
            liste.append(grund)


def bereich_passt(bereich, klasse):
    """Ob ein Bereich eines Abschnitts - paket oder paket/Klasse - zu einer
    betroffenen Klasse passt."""
    if "/" in bereich:
        return klasse in (bereich, bereich.split("/")[0] + "/*")
    return klasse.split("/")[0] == bereich


def schluessel_passt(a, b):
    """config:camera-mode.name passt zu config:camera-mode.name.color und umgekehrt."""
    return a == b or a.startswith(b + ".") or b.startswith(a + ".")


def kurz(liste, n=3):
    liste = sorted(liste)
    return ", ".join(liste[:n]) + (" ..." if len(liste) > n else "")


def auswaehlen(repo, abschnitte, seit=None):
    """Welche Abschnitte der Stand im Arbeitsordner braucht.

    abschnitte: Name -> (die Namen seiner Funktionen im Testskript, die
    Bereiche, die er prueft). Ein Bereich ist ein Paket wie "trail" oder eine
    Klasse wie "display/CamActionBar"."""
    repo = Path(repo)
    a = Auswahl()
    try:
        a.basis, a.basis_name = basis_finden(repo, seit)
        dateien = geaenderte_dateien(repo, a.basis)
    except (RuntimeError, OSError) as exc:
        a.alle = f"kein Vergleich moeglich: {exc}"
        return a
    a.dateien = [p for p, _, _ in dateien]

    def lesen(pfad):
        datei = repo / pfad
        return datei.read_text(encoding="utf-8", errors="replace") if datei.is_file() else ""

    config_pfade = {p for p in yaml_pfade(lesen(CONFIG)) if p}
    sprach_schluessel = {p.split(".", 1)[1] for p in yaml_pfade(lesen(SPRACHE))
                         if p and p.startswith(("messages.", "message-settings."))}
    java = [JavaDatei(str(p.relative_to(repo)), p.read_text(encoding="utf-8", errors="replace"))
            for p in sorted((repo / JAVA_WURZEL).rglob("*.java"))]
    folger = Folger(JavaIndex(java), {p.split(".")[0] for p in config_pfade}, sprach_schluessel)
    funktionen = {name: f for name, (f, _) in abschnitte.items()}

    herkunft = []   # (Dateiname, Betroffen)
    for pfad, alt, neu in dateien:
        if any(m.search(pfad) for m in EGAL):
            continue
        if pfad.startswith(JAVA_WURZEL) and pfad.endswith(".java"):
            b = folger.java(pfad, alt, neu)
        elif pfad in (CONFIG, SPRACHE):
            b = Betroffen()
            vorhanden = {p for p in yaml_pfade(neu or "") if p}
            for teile in sorted(yaml_aenderungen(alt, neu)):
                p = ".".join(teile)
                b.dazu(folger.yaml(pfad == SPRACHE, p, p in vorhanden))
        elif pfad == PLUGIN_YML:
            b = plugin_yml(folger, alt, neu)
        elif pfad == SKRIPT:
            gruende, alle = skript_aenderungen(alt, neu, funktionen)
            for name, liste in gruende.items():
                for grund in liste:
                    a.dazu(name, grund)
            if alle:
                a.alle = alle
                return a
            continue
        else:
            a.alle = f"{pfad} geaendert"
            return a
        if b.unklar:
            a.alle = f"{Path(pfad).name}: {b.unklar[0]}"
            return a
        herkunft.append((Path(pfad).name, b))

    skript = Skript(lesen(SKRIPT), funktionen)
    for name, (_, bereiche) in abschnitte.items():
        gesetzt = skript.gesetzte_werte(skript.abschnitt_huelle(name), config_pfade)
        for datei, b in herkunft:
            klassen = {k for k in b.klassen for x in bereiche if bereich_passt(x, k)}
            werte = {s for s in b.schluessel for g in gesetzt if schluessel_passt(s, g)}
            if klassen:
                a.dazu(name, f"{datei}: {kurz(klassen)}")
            elif werte:
                a.dazu(name, f"{datei}: {kurz(w.split(':', 1)[1] for w in werte)}")
    for _, b in herkunft:
        for klasse in sorted(b.direkt):
            if not any(bereich_passt(x, klasse) for _, bereiche in abschnitte.values() for x in bereiche) \
                    and klasse.split("/")[0] not in SAMMEL_PAKETE and klasse not in a.ohne_abschnitt:
                a.ohne_abschnitt.append(klasse)
    return a
