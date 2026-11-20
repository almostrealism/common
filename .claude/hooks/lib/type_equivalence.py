#!/usr/bin/env python3
"""Search for existing Java types that a new type may duplicate.

This is the single implementation of the "does this concept already exist?"
question asked about every new top-level Java type. Two consumers use it:

  * ``.claude/hooks/warn-new-type-placement.py`` imports it and BLOCKS the
    Write/Edit that gives birth to a type with likely equivalents;
  * the FlowTree harness (``io.flowtree.jobs.NewTypeAudit``) runs it as a
    command-line program (``scan``) to decide which new types the type audit
    must examine and which ones may not be staged until it has.

Keeping both on one implementation means the hook and the harness can never
disagree about what a candidate is.

How candidates are scored
-------------------------
Every main-source type of the repository (a ``.java`` file under some
``src/main/java``) is indexed once with: its simple name split into stemmed
camel-case words, the first sentence of its javadoc, a bag of stemmed words
from its whole javadoc description, its public method names, the types it
extends or implements, its package and its module. A new type is scored
against each indexed type on:

  * shared name words, weighted by rarity (``Index`` is in hundreds of names,
    ``Arithmetic`` in a handful);
  * overlap between the new type's javadoc first sentence and the candidate's
    javadoc;
  * overlap in public method names and in extended/implemented types;
  * the same package, or failing that the same module.

The index is cached in the system temporary directory, keyed by repository
root, and refreshed incrementally: a file whose modification time and size are
unchanged is not parsed again. A warm lookup stats the files and scores; it
does not read them.

The justification ledger
------------------------
``.claude/new-type-ledger.json`` (see ``LEDGER_PATH``) is the tracked file in
which a session records why a new type must exist although candidates were
found. Its shape::

    {"types": [
      {"type": "org.example.NewType",
       "test": "NewTypeTest#distinguishesFromCandidate",
       "candidates": {
         "org.example.Candidate": {
           "cannot_extend": "...",
           "cannot_change_signature": "...",
           "interface_would_not_work": "..."
         }
       },
       "audit": {"verdict": "kept", "reason": "..."}
      }
    ]}

An entry lifts the hook's block only when it covers EVERY listed candidate
with all three reasons and names a test. The ``audit`` member is written by
the harness's type-audit session, never by the session that wrote the type;
the harness only trusts it for a type that session actually audited.
"""

import hashlib
import json
import math
import os
import re
import sys
import tempfile

def _own_digest():
    """Digest of this module's source: a change to the parser invalidates
    every cached index built by the previous version."""
    try:
        with open(os.path.abspath(__file__), "rb") as f:
            return hashlib.sha1(f.read()).hexdigest()
    except OSError:
        return "unknown"


INDEX_VERSION = _own_digest()

LEDGER_PATH = os.path.join(".claude", "new-type-ledger.json")

MAIN_SOURCE = "/src/main/java/"

SKIP_DIRS = {".git", "target", "node_modules", "build", "out", ".idea",
             "results", "__pycache__", ".claude", ".mvn", "venv", ".venv"}

BLOCK_THRESHOLD = 0.5

MAX_CANDIDATES = 5

# A new type whose code refers to a candidate (as a parameter, return type,
# local or call) is more often its collaborator than its duplicate - a
# FileStager that consumes a FileStagingConfig - so such a candidate needs a
# stronger match to be listed. It still can be: a reference proves use, not
# that the new type is not a second copy.
COLLABORATOR_FACTOR = 0.75

REASON_FIELDS = ("cannot_extend", "cannot_change_signature",
                 "interface_would_not_work")

MIN_REASON_CHARS = 20

TYPE = re.compile(
    r'(?:(?:public|private|protected|static|final|abstract|sealed'
    r'|non-sealed|strictfp)\s+)*'
    r'\b(class|interface|enum|record)\s+([A-Za-z_]\w*)')

PACKAGE = re.compile(r'^\s*package\s+([\w.]+)\s*;', re.MULTILINE)

CODE_NOISE = re.compile(
    r'//[^\n]*|/\*.*?\*/|"""(?:.|\n)*?"""|"(?:\\.|[^"\\\n])*"'
    r"|'(?:\\.|[^'\\\n])*'",
    re.DOTALL)

METHOD = re.compile(
    r'(?:^|[;{}])\s*((?:@\w+(?:\([^)]*\))?\s+)*'
    r'(?:(?:public|protected|private|static|final|abstract|default'
    r'|synchronized|native|strictfp)\s+)*)'
    r'(?:<[^>{};]*>\s*)?[\w$.]+(?:<[^{};()]*>)?(?:\[\])*\s+([a-z]\w*)\s*\(')

FIELD = re.compile(
    r'(?:^|[;{}])\s*(?:@\w+(?:\([^)]*\))?\s+)*'
    r'(?:(?:public|protected|private|static|final|transient|volatile)\s+)*'
    r'([A-Z][\w$.]*)(?:<[^;{}()=]*>)?(?:\[\])*\s+[a-z]\w*\s*[;=]')

INLINE_TAG = re.compile(r'\{@(?:link|linkplain|code|literal|value)\s+([^}]*)\}')

HTML = re.compile(r'<[^>]+>')

CAMEL = re.compile(r'[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\d+')

WORD = re.compile(r'[A-Za-z][A-Za-z]+')

SUFFIXES = ("ational", "ations", "itions", "ation", "ition", "ions", "ings",
            "ments", "ities", "ness", "ment", "ion", "ing", "ity", "ies",
            "ers", "ors", "er", "or", "es", "ed", "ly", "s", "e")

IRREGULAR = {"indices": "index", "indexes": "index", "matrices": "matrix",
             "vertices": "vertex", "children": "child", "data": "datum"}

STOP = set("""
a an the of for to that this which is are be been being by with in on as and
or from it its all each any used uses use using returns return given provides
provide class interface implementation implements type object instance new
when into one can may not only also such these those their there than then
some more most other via per if so we our you your will would should must
represents represent represented holds hold based simple default abstract
base helper utility generic way like within without both either over under
see note does do how what who where whose about java value
""".split())

COMMON_METHODS = {"equals", "hashCode", "toString", "get", "set", "of",
                  "build", "builder", "close", "run", "apply", "accept",
                  "getName", "valueOf", "values", "create", "init", "size",
                  "isEmpty", "main", "compareTo", "clone", "iterator"}


def stem(word):
    """Reduces an English word to a crude stem; enough to match plurals and
    -ion/-ing/-er variants (``progressions`` and ``progression`` agree)."""
    w = word.lower()
    if w in IRREGULAR:
        return IRREGULAR[w]
    for suffix in SUFFIXES:
        if w.endswith(suffix) and len(w) - len(suffix) >= 3:
            w = w[:-len(suffix)]
            break
    if w.endswith("e") and len(w) > 3:
        w = w[:-1]
    return w


def name_words(name):
    """Stemmed camel-case words of a type name: ``ArithmeticIndexSet`` gives
    ``{"arithmetic", "index", "set"}``."""
    return {stem(w) for w in CAMEL.findall(name) if len(w) > 1}


def text_words(text):
    """Stemmed content words of prose, with stop words removed."""
    out = set()
    for w in WORD.findall(text or ""):
        for part in CAMEL.findall(w) if any(c.isupper() for c in w[1:]) else [w]:
            if len(part) > 2 and part.lower() not in STOP:
                out.add(stem(part))
    return out


def clean_javadoc(comment):
    """Javadoc description text: no comment markers, block tags, HTML or
    inline-tag syntax (``{@link Foo#bar label}`` reads as ``label``)."""
    body = comment
    if body.startswith("/**"):
        body = body[3:]
    if body.endswith("*/"):
        body = body[:-2]
    lines = [re.sub(r'^\s*\*\s?', '', line) for line in body.split("\n")]
    description = []
    for line in lines:
        if re.match(r'\s*@\w+', line):
            break
        description.append(line)
    text = "\n".join(description)

    def inline(m):
        if m.group(0).startswith(("{@code", "{@literal", "{@value")):
            return m.group(1)
        parts = m.group(1).strip().split(None, 1)
        if len(parts) == 2:
            return parts[1]
        return parts[0].split("#")[-1] if parts else ""

    text = INLINE_TAG.sub(inline, text)
    text = HTML.sub(" ", text)
    return re.sub(r'\s+', ' ', text).strip()


def first_sentence(description):
    """The first sentence of a cleaned javadoc description."""
    m = re.search(r'\.(?:\s|$)', description)
    return description[:m.start() + 1] if m else description


def strip_code(source):
    """Source with comments and string literals blanked out, so structure
    regexes do not match inside them."""
    return CODE_NOISE.sub(lambda m: re.sub(r'[^\n]', ' ', m.group(0)), source)


def depth_at(code):
    """Returns a function giving the brace depth at a position of ``code``."""
    positions = []
    depths = []
    depth = 0
    for m in re.finditer(r'[{}]', code):
        depth += 1 if m.group(0) == "{" else -1
        positions.append(m.start())
        depths.append(depth)

    import bisect

    def at(pos):
        i = bisect.bisect_left(positions, pos) - 1
        return depths[i] if i >= 0 else 0

    return at


def split_types(text):
    """Simple names in an ``extends``/``implements`` clause, without generics."""
    previous = None
    while previous != text:
        previous = text
        text = re.sub(r'<[^<>]*>', '', text)
    return [t.strip().split(".")[-1] for t in text.split(",") if t.strip()]


def parse_types(source, path=""):
    """Parses the top-level types of a Java source into index entries.

    Each entry is a dict with ``name``, ``kind``, ``package``, ``fqn``,
    ``path``, ``module``, ``sentence`` (javadoc first sentence), ``doc``
    (stemmed javadoc words), ``words`` (stemmed name words), ``methods``,
    ``supers`` (simple names extended or implemented) and ``fields`` (simple
    type names of member fields).
    """
    source = source or ""
    code = strip_code(source)
    at = depth_at(code)
    pm = PACKAGE.search(code)
    package = pm.group(1) if pm else ""
    module = module_of(path)
    entries = []
    for m in TYPE.finditer(code):
        if at(m.start()) != 0:
            continue
        before = code[max(0, m.start() - 1):m.start()]
        if before and (before.isalnum() or before in "_.$"):
            continue
        kind, name = m.group(1), m.group(2)
        brace = code.find("{", m.end())
        header = code[m.end():brace if brace >= 0 else len(code)]
        supers = []
        for clause in re.finditer(r'\b(?:extends|implements)\s+(.*?)(?=\bimplements\b|\bpermits\b|$)',
                                  header, re.DOTALL):
            supers.extend(split_types(clause.group(1)))
        end = matching_brace(code, brace) if brace >= 0 else len(code)
        body = code[brace + 1:end] if brace >= 0 else ""
        methods, fields = members(body, kind)
        description = javadoc_before(source, m.start())
        entries.append({
            "name": name,
            "kind": kind,
            "package": package,
            "fqn": (package + "." + name) if package else name,
            "path": path,
            "module": module,
            "sentence": first_sentence(description),
            "doc": sorted(text_words(description))[:80],
            "words": sorted(name_words(name)),
            "methods": sorted(methods)[:60],
            "supers": sorted(set(supers)),
            "fields": sorted(fields)[:60],
            "refs": sorted(set(re.findall(r'\b[A-Z]\w*', header + " " + body)) - {name}),
        })
    return entries


def matching_brace(code, open_pos):
    """Position of the brace closing the one at ``open_pos``."""
    depth = 0
    for m in re.finditer(r'[{}]', code[open_pos:]):
        depth += 1 if m.group(0) == "{" else -1
        if depth == 0:
            return open_pos + m.start()
    return len(code)


def members(body, kind):
    """(public method names, member field type names) declared directly in a
    type body. Interface methods count as public unless marked private."""
    at = depth_at(body)
    methods = set()
    for m in METHOD.finditer(body):
        if at(m.start(2)) != 0:
            continue
        modifiers = m.group(1) or ""
        name = m.group(2)
        if name in ("if", "for", "while", "switch", "return", "new", "catch",
                    "synchronized", "throw", "else", "super", "this"):
            continue
        public = "public" in modifiers.split() or (
            kind == "interface" and "private" not in modifiers.split())
        if public:
            methods.add(name)
    fields = set()
    for m in FIELD.finditer(body):
        if at(m.start(1)) == 0 and m.group(1) not in ("return", "throw", "new"):
            fields.add(m.group(1).split(".")[-1])
    return methods, fields


def javadoc_before(source, pos):
    """The cleaned javadoc immediately preceding a declaration, or ``""``.

    ``pos`` is the position of the declaration keyword; annotations and
    modifiers may sit between the comment and it, nothing else.
    """
    start = source.rfind("/**", 0, pos)
    if start < 0:
        return ""
    end = source.find("*/", start)
    if end < 0 or end > pos:
        return ""
    between = source[end + 2:pos]
    between = re.sub(r'@\w+(?:\([^)]*\))?', ' ', between)
    if re.search(r'[;{}=]', between):
        return ""
    return clean_javadoc(source[start:end + 2])


def module_of(path):
    """The module directory of a source path (the part before src/main/java)."""
    p = "/" + path.replace(os.sep, "/").lstrip("/")
    i = p.find("/src/")
    return p[1:i] if i > 0 else ""


def is_main_source(path):
    """True for a path inside some ``src/main/java`` tree."""
    return MAIN_SOURCE in "/" + path.replace(os.sep, "/").lstrip("/")


def find_root(path):
    """Repository root for ``path``: ``AR_TYPE_INDEX_ROOT`` when set, else the
    nearest ancestor holding a ``.git`` entry, else ``CLAUDE_PROJECT_DIR``,
    else the current directory."""
    forced = os.environ.get("AR_TYPE_INDEX_ROOT")
    if forced:
        return os.path.abspath(forced)
    d = os.path.dirname(os.path.abspath(path)) if path else os.getcwd()
    while True:
        if os.path.exists(os.path.join(d, ".git")):
            return d
        parent = os.path.dirname(d)
        if parent == d:
            break
        d = parent
    return os.path.abspath(os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd())


def cache_path(root):
    """Where the index for ``root`` is cached (``AR_TYPE_INDEX_CACHE`` wins)."""
    forced = os.environ.get("AR_TYPE_INDEX_CACHE")
    if forced:
        return forced
    user = os.environ.get("USER") or "developer"
    key = hashlib.sha1(os.path.abspath(root).encode()).hexdigest()[:16]
    return os.path.join(tempfile.gettempdir(), "ar-type-index-" + user + "-" + key + ".json")


def main_sources(root):
    """Yields (relative path, stat) for every main-source Java file."""
    stack = [root]
    while stack:
        d = stack.pop()
        try:
            entries = list(os.scandir(d))
        except OSError:
            continue
        for e in entries:
            if e.is_dir(follow_symlinks=False):
                if e.name not in SKIP_DIRS and not e.name.startswith("."):
                    stack.append(e.path)
            elif e.name.endswith(".java"):
                rel = os.path.relpath(e.path, root)
                if is_main_source(rel) and e.name != "package-info.java" \
                        and e.name != "module-info.java":
                    try:
                        yield rel, e.stat()
                    except OSError:
                        continue


class TypeIndex:
    """The cached index of a repository's main-source types."""

    def __init__(self, root, types, df_words, df_doc):
        self.root = root
        self.types = types
        self.df_words = df_words
        self.df_doc = df_doc
        self.count = max(1, len(types))

    @classmethod
    def load(cls, root, keep_refs=False):
        """Loads the cached index for ``root``, refreshing what is stale.

        ``keep_refs`` keeps the names each type refers to, which only the
        whole-repository ``survey`` needs (a new type's own references are
        read from its source); that index is cached separately.
        """
        root = os.path.abspath(root)
        path = cache_path(root) + (".refs" if keep_refs else "")
        cached = {}
        try:
            with open(path) as f:
                data = json.load(f)
            if data.get("version") == INDEX_VERSION and data.get("root") == root:
                cached = data.get("files", {})
        except (OSError, ValueError):
            cached = {}
        files = {}
        changed = False
        for rel, st in main_sources(root):
            entry = cached.get(rel)
            if entry and entry.get("mtime") == st.st_mtime and entry.get("size") == st.st_size:
                files[rel] = entry
                continue
            try:
                with open(os.path.join(root, rel), errors="replace") as f:
                    text = f.read()
            except OSError:
                continue
            parsed = parse_types(text, rel)
            if not keep_refs:
                for t in parsed:
                    t.pop("refs", None)
            files[rel] = {"mtime": st.st_mtime, "size": st.st_size, "types": parsed}
            changed = True
        if set(files) != set(cached):
            changed = True
        df = None if changed else (data.get("df_words"), data.get("df_doc"))
        types = [t for entry in files.values() for t in entry["types"]]
        if df is None or df[0] is None:
            df_words, df_doc = {}, {}
            for t in types:
                for w in t["words"]:
                    df_words[w] = df_words.get(w, 0) + 1
                for w in t["doc"]:
                    df_doc[w] = df_doc.get(w, 0) + 1
        else:
            df_words, df_doc = df
        if changed:
            try:
                tmp = path + "." + str(os.getpid())
                with open(tmp, "w") as f:
                    json.dump({"version": INDEX_VERSION, "root": root, "files": files,
                               "df_words": df_words, "df_doc": df_doc}, f)
                os.replace(tmp, path)
            except OSError:
                pass
        return cls(root, types, df_words, df_doc)

    def idf(self, word, df):
        """Rarity weight of a word: rare words count for more."""
        return math.log((self.count + 1.0) / (1.0 + df.get(word, 0))) + 0.1

    def name_weight(self, words):
        """Summed rarity of a set of name words."""
        return sum(self.idf(w, self.df_words) for w in words)

    def doc_weight(self, words):
        """Summed rarity of a set of javadoc words."""
        return sum(self.idf(w, self.df_doc) for w in words)

    def query(self, new):
        """The parts of ``new`` every comparison needs, computed once."""
        words = set(new["words"])
        sentence = text_words(new["sentence"]) | words
        return {"new": new, "words": words, "name_weight": self.name_weight(words),
                "sentence": sentence, "sentence_weight": self.doc_weight(sentence),
                "methods": set(new["methods"]) - COMMON_METHODS,
                "supers": set(new["supers"]), "refs": set(new.get("refs", ())),
                "builds_on": set(new["supers"]) | set(new["fields"])}

    def components(self, q, candidate):
        """The separate similarity signals between a query and a candidate:
        ``(name, doc, methods, supers, locality)``, each in [0, 1]."""
        new = q["new"]
        cw = set(candidate["words"])
        shared = q["words"] & cw
        name = 0.0
        if shared:
            name = 2 * self.name_weight(shared) / (q["name_weight"] + self.name_weight(cw))
        doc_shared = q["sentence"] & (set(candidate["doc"]) | cw)
        doc = self.doc_weight(doc_shared) / q["sentence_weight"] if doc_shared else 0.0
        cm = set(candidate["methods"]) - COMMON_METHODS
        nm = q["methods"]
        methods = len(nm & cm) / len(nm | cm) if nm and cm else 0.0
        cs = set(candidate["supers"])
        ns = q["supers"]
        supers = len(ns & cs) / len(ns | cs) if ns and cs else 0.0
        if new["package"] and new["package"] == candidate["package"]:
            locality = 1.0
        elif new["module"] and new["module"] == candidate["module"]:
            locality = 0.5
        else:
            locality = 0.0
        return name, doc, methods, supers, locality

    @staticmethod
    def combine(name, doc, methods, supers, locality):
        """One score from the separate signals.

        Name and javadoc carry the score; methods and supertypes add to it;
        locality amplifies a match that already exists but never makes one
        (two unrelated types in one package are not equivalent).
        """
        if name < 0.2 and doc < 0.35 and methods < 0.3:
            return 0.0
        base = 0.50 * name + 0.35 * doc + 0.10 * methods + 0.05 * supers
        return min(1.0, base * (1.0 + 0.3 * locality))

    def score(self, new, candidate):
        """Similarity of ``new`` to ``candidate`` in [0, 1], with the evidence."""
        q = self.query(new)
        return self.scored(q, candidate)

    def scored(self, q, candidate):
        """Score and evidence of a candidate against a prepared query."""
        name, doc, methods, supers, locality = self.components(q, candidate)
        total = self.combine(name, doc, methods, supers, locality)
        if total <= 0.0:
            return 0.0, {}
        collaborator = candidate["name"] in q["refs"] - q["builds_on"]
        if collaborator:
            total *= COLLABORATOR_FACTOR
        cw = set(candidate["words"])
        evidence = {
            "collaborator": collaborator,
            "shared_name_words": sorted(q["words"] & cw),
            "shared_doc_words": sorted((q["sentence"] & set(candidate["doc"])) - q["words"])[:8],
            "shared_methods": sorted(q["methods"] & set(candidate["methods"]))[:8],
            "shared_supertypes": sorted(q["supers"] & set(candidate["supers"])),
            "locality": "same package" if locality == 1.0
            else "same module" if locality else "",
        }
        return round(total, 3), evidence

    def postings(self):
        """Inverted indexes from name word, and from javadoc word, to the
        positions of the types using it."""
        if getattr(self, "_postings", None) is None:
            names, docs = {}, {}
            for i, t in enumerate(self.types):
                for w in t["words"]:
                    names.setdefault(w, []).append(i)
                for w in set(t["doc"]) | set(t["words"]):
                    docs.setdefault(w, []).append(i)
            self._postings = (names, docs)
        return self._postings

    def pool(self, q):
        """Positions of the types that can reach the threshold at all: those
        sharing a name word, or at least two javadoc words, with the query."""
        names, docs = self.postings()
        pool = set()
        for w in q["words"]:
            pool.update(names.get(w, ()))
        counts = {}
        for w in q["sentence"]:
            for i in docs.get(w, ()):
                counts[i] = counts.get(i, 0) + 1
        pool.update(i for i, n in counts.items() if n >= 2)
        return pool

    def candidates(self, new, exclude_path=None):
        """The indexed types ``new`` likely duplicates, best first."""
        q = self.query(new)
        found = []
        for i in self.pool(q):
            t = self.types[i]
            if t["path"] == exclude_path or t["fqn"] == new["fqn"]:
                continue
            s, evidence = self.scored(q, t)
            if s >= BLOCK_THRESHOLD:
                found.append({"name": t["name"], "fqn": t["fqn"], "path": t["path"],
                              "sentence": t["sentence"], "score": s,
                              "evidence": evidence})
        found.sort(key=lambda c: -c["score"])
        return found[:MAX_CANDIDATES]


def structural_lift(new, candidates):
    """The candidate ``new`` builds on, and how: it extends or implements the
    candidate, or holds a member field of the candidate's type (delegation).
    Returns ``(relation, candidate name)`` or ``None``."""
    supers = set(new["supers"])
    fields = set(new["fields"])
    for c in candidates:
        if c["name"] in supers:
            return ("extends or implements", c["name"])
        if c["name"] in fields:
            return ("delegates to", c["name"])
    return None


def read_ledger(root):
    """The ledger's entries, or an empty list when it is absent or invalid."""
    try:
        with open(os.path.join(root, LEDGER_PATH)) as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    entries = data.get("types") if isinstance(data, dict) else None
    return [e for e in entries or [] if isinstance(e, dict)]


def ledger_entry(entries, new):
    """The ledger entry for a type, matched by fully-qualified or simple name."""
    for e in entries:
        if e.get("type") in (new["fqn"], new["name"]):
            return e
    return None


def justification_gaps(entry, candidates):
    """What a ledger entry fails to justify; empty when it lifts the block."""
    if entry is None:
        return ["no ledger entry for this type in " + LEDGER_PATH]
    gaps = []
    test = entry.get("test")
    if not isinstance(test, str) or not test.strip():
        gaps.append("no test named that demonstrates the distinction (\"test\")")
    justified = entry.get("candidates")
    justified = justified if isinstance(justified, dict) else {}
    for c in candidates:
        reasons = justified.get(c["fqn"]) or justified.get(c["name"])
        if not isinstance(reasons, dict):
            gaps.append("candidate " + c["fqn"] + " is not justified")
            continue
        for field in REASON_FIELDS:
            value = reasons.get(field)
            if not isinstance(value, str) or len(value.strip()) < MIN_REASON_CHARS:
                gaps.append("candidate " + c["fqn"] + ": \"" + field + "\" is missing or too short")
    return gaps


def profile(source, path, name):
    """The index entry for type ``name`` as declared in ``source``."""
    for t in parse_types(source, path):
        if t["name"] == name:
            return t
    return None


def assess(index, source, path, name, ledger):
    """Everything known about a new type: its candidates, whether a candidate
    is built upon structurally, the ledger entry and its gaps."""
    rel = os.path.relpath(os.path.abspath(path), index.root) \
        if os.path.isabs(path) else path
    new = profile(source, rel, name)
    if new is None:
        return None
    candidates = index.candidates(new, exclude_path=rel)
    entry = ledger_entry(ledger, new)
    lift = structural_lift(new, candidates) if candidates else None
    gaps = justification_gaps(entry, candidates) if candidates else []
    audit = entry.get("audit") if entry else None
    return {
        "name": new["name"],
        "fqn": new["fqn"],
        "path": rel,
        "candidates": candidates,
        "lift": ("%s %s" % lift) if lift else None,
        "justified": bool(candidates) and not gaps,
        "gaps": gaps,
        "ledger": entry,
        "audit_verdict": audit.get("verdict") if isinstance(audit, dict) else None,
    }


def added_type_names(before, after):
    """Names of top-level types declared in ``after`` but not in ``before``."""
    old = {t["name"] for t in parse_types(before)}
    return [t["name"] for t in parse_types(after) if t["name"] not in old]


def read_text(path):
    """The text of a file, or ``""`` when it cannot be read."""
    try:
        with open(path, errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def scan(root, files):
    """Assesses every top-level type that ``files`` add.

    Each element of ``files`` is a repository-relative path, optionally
    followed by ``::`` and the path of a copy of the file as it was before the
    change (for the harness, its HEAD version). Without a copy the file is
    treated as new, so every type it declares is assessed.
    """
    index = TypeIndex.load(root)
    ledger = read_ledger(root)
    out = []
    for spec in files:
        rel, _, before_copy = spec.partition("::")
        if not is_main_source(rel):
            continue
        after = read_text(os.path.join(root, rel))
        before = read_text(before_copy) if before_copy else ""
        for name in added_type_names(before, after):
            result = assess(index, after, rel, name, ledger)
            if result is not None and result["candidates"]:
                out.append(result)
    return out


def survey(root):
    """How many existing types would block if each were added today: every
    indexed type is scored against all the others."""
    index = TypeIndex.load(root, keep_refs=True)
    blocking = []
    for t in index.types:
        found = index.candidates(t, exclude_path=t["path"])
        if found and structural_lift(t, found) is None:
            blocking.append({"fqn": t["fqn"], "top": found[0]["fqn"],
                             "score": found[0]["score"]})
    histogram = {}
    for b in blocking:
        bucket = "%.1f" % (int(b["score"] * 10) / 10.0)
        histogram[bucket] = histogram.get(bucket, 0) + 1
    ranked = sorted(blocking, key=lambda b: -b["score"])
    return {"types": len(index.types), "blocking": len(blocking),
            "top_score_histogram": dict(sorted(histogram.items())),
            "strongest": ranked[:20], "weakest": ranked[-20:]}


def main(argv):
    """``scan --root DIR FILE[::BEFORE]...`` or ``survey --root DIR``; prints JSON."""
    if len(argv) < 3 or argv[1] != "--root":
        print("usage: type_equivalence.py (scan|survey) --root DIR [FILE[::BEFORE]...]", file=sys.stderr)
        return 2
    command, root, files = argv[0], os.path.abspath(argv[2]), argv[3:]
    if command == "scan":
        print(json.dumps({"types": scan(root, files)}))
        return 0
    if command == "survey":
        print(json.dumps(survey(root), indent=1))
        return 0
    print("unknown command: " + command, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
