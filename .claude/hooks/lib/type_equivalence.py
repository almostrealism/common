#!/usr/bin/env python3
"""Search for existing Java types that a new type may duplicate.

This is the single implementation of the "does this concept already exist?"
question asked about every new top-level Java type, and the single Java
declaration parser the new-type checks use. Two consumers use it:

  * ``.claude/hooks/warn-new-type-placement.py`` imports it, BLOCKS the
    Write/Edit that gives birth to a type that duplicates an existing concept,
    and WARNS, listing the candidates, about a type that only resembles one;
  * the FlowTree harness (``io.flowtree.jobs.NewTypeAudit``) runs it as a
    command-line program (``scan``) to decide which new types the type audit
    must examine and which ones may not be staged until it has.

Keeping both on one implementation means the hook and the harness can never
disagree about what a candidate is. The harness runs the base branch's copy of
this file on its own, from a temporary location, so the module imports nothing
from the working tree: a session must not be able to weaken the search by
editing a file it would load.

How candidates are judged
-------------------------
Every main-source type of the repository (a ``.java`` file under some
``src/main/java``) is indexed once with: its simple name split into stemmed
camel-case words, the words of its javadoc first sentence and of its whole
javadoc description, its public method signatures (name and arity), the types
it extends or implements, and the type names its code refers to.

A new type is compared with each indexed type on three signals of the SAME
CONCEPT:

  * ``doc``: the two javadoc first sentences describe the same thing - each
    sentence's words, weighted by rarity, are looked for in the other type's
    javadoc, in both directions;
  * ``methods``: shared public method signatures;
  * ``name``: shared name words, weighted by rarity (``Index`` is in hundreds
    of names, ``Arithmetic`` in a handful).

A candidate BLOCKS only when at least two of those signals are strong and the
combined concept score reaches ``BLOCK_THRESHOLD``. Shared vocabulary alone, or
a shared package, never blocks: the package is not scored at all. Two
relationships are evidence of DIFFERENT roles, and demote a candidate to a
warning unless the new type is a near-copy of it (``NEAR_COPY``):

  * siblings - both extend or implement the same non-trivial supertype, as
    two cells of one cell interface do;
  * collaborators - the candidate refers to the new type, or one of the two
    extends the other.

A candidate below the block bar but above ``WARN_THRESHOLD`` is reported as a
warning.

A block lifts when the new type genuinely BUILDS ON a candidate: it extends or
implements it and overrides or calls its members, or it calls the candidate's
methods through a field, parameter or local of its type, or constructs it. A
marker interface or an unused field does not lift anything.

The index is cached in the system temporary directory, keyed by repository
root, and refreshed incrementally: a file whose modification time and size are
unchanged is not parsed again.

The justification ledger
------------------------
``.claude/new-type-ledger.json`` (see ``LEDGER_PATH``) is the tracked file in
which a session records why a new type must exist although it blocks. Its
shape::

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

An entry lifts the hook's block only when it covers EVERY blocking candidate
with all three reasons and names a test that exists in the repository's test
sources and refers to the new type. The ``audit`` member is written by the
harness's type-audit session, never by the session that wrote the type; the
harness only trusts it for a type that session actually audited.

Calibration
-----------
``calibrate`` treats every existing type under some directories as if it were
new and reports how many would block; ``test_new_type_placement.py`` holds that
rate down while checking that real duplicates still block.
"""

import bisect
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

TEST_SOURCES = ("/src/test/", "/src/it/")

SKIP_DIRS = {".git", "target", "node_modules", "build", "out", ".idea",
             "results", "__pycache__", ".claude", ".mvn", "venv", ".venv"}

BLOCK_THRESHOLD = 0.5

WARN_THRESHOLD = 0.4

STRONG_NAME = 0.55

STRONG_DOC = 0.5

STRONG_METHODS = 0.6

NEAR_COPY = {"methods": 0.85, "doc": 0.85}

NAMED_DOC = 0.35

MAX_CANDIDATES = 5

REASON_FIELDS = ("cannot_extend", "cannot_change_signature",
                 "interface_would_not_work")

MIN_REASON_CHARS = 20

TRIVIAL_SUPERS = {"Object", "Serializable", "Cloneable", "Comparable",
                  "AutoCloseable", "Closeable", "Iterable", "Runnable",
                  "Exception", "RuntimeException", "Enum", "Record",
                  "ConsoleFeatures", "CodeFeatures", "Destroyable"}

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
    r'(?:<[^>{};]*>\s*)?([\w$.]+(?:<[^{};()]*>)?(?:\[\])*)\s+([a-z]\w*)\s*\(')

FIELD = re.compile(
    r'(?:^|[;{}])\s*(?:@\w+(?:\([^)]*\))?\s+)*'
    r'(?:(?:public|protected|private|static|final|transient|volatile)\s+)*'
    r'([A-Z][\w$.]*)(?:<[^;{}()=]*>)?(?:\[\])*\s+[a-z]\w*\s*[;=]')

TYPED_VAR = re.compile(
    r'\b([A-Z]\w*)(?:<[^;{}()=]*?>)?(?:\[\])*\s+([a-z]\w*)\s*(?=[;=,):])')

CALL = re.compile(r'(?:\b([A-Za-z_]\w*)\s*\.\s*|(?<![\w.$]))([a-z]\w*)\s*\(')

CONSTRUCT = re.compile(r'\bnew\s+([A-Z]\w*)')

KEYWORDS = {"if", "for", "while", "switch", "return", "new", "catch",
            "synchronized", "throw", "else", "super", "this", "assert", "try"}

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
                  "isEmpty", "main", "compareTo", "clone", "iterator",
                  "destroy", "describe", "getDescription"}


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
    ``path``, ``module``, ``sentence`` (javadoc first sentence),
    ``sentence_words`` (its stemmed words), ``doc`` (stemmed javadoc words),
    ``words`` (stemmed name words), ``methods`` (public method names),
    ``sigs`` (public ``name/arity`` signatures), ``declared`` (every method
    name declared), ``supers`` (simple names extended or implemented),
    ``fields`` (simple type names of member fields), ``refs`` (type names the
    code refers to), and the use sites ``typed_vars`` (``"Type name"`` for
    each field, parameter and local), ``calls`` (``"receiver.method"``, with
    an empty receiver for an unqualified call) and ``constructs``.
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
        if before and (before.isalnum() or before in "_.$@"):
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
        methods, sigs, declared, fields, api = members(body, kind)
        description = javadoc_before(source, m.start())
        sentence = first_sentence(description)
        text = header + " " + body
        entries.append({
            "name": name,
            "kind": kind,
            "package": package,
            "fqn": (package + "." + name) if package else name,
            "path": path,
            "module": module,
            "sentence": sentence,
            "sentence_words": sorted(text_words(sentence)),
            "doc": sorted(text_words(description))[:240],
            "words": sorted(name_words(name)),
            "methods": sorted(methods)[:60],
            "sigs": sorted(sigs)[:60],
            "declared": sorted(declared)[:120],
            "supers": sorted(set(supers)),
            "fields": sorted(fields)[:60],
            "api": sorted(api - {name})[:60],
            "refs": sorted(set(re.findall(r'\b[A-Z]\w*', text)) - {name}),
            "typed_vars": sorted({a + " " + b for a, b in TYPED_VAR.findall(text)}),
            "calls": sorted({(r or "") + "." + c for r, c in CALL.findall(body)
                             if c not in KEYWORDS}),
            "constructs": sorted(set(CONSTRUCT.findall(body))),
        })
    return entries


USE_SITES = ("typed_vars", "calls", "constructs")


def matching_brace(code, open_pos):
    """Position of the brace closing the one at ``open_pos``."""
    depth = 0
    for m in re.finditer(r'[{}]', code[open_pos:]):
        depth += 1 if m.group(0) == "{" else -1
        if depth == 0:
            return open_pos + m.start()
    return len(code)


def parameters(body, open_paren):
    """``(arity, text)`` of the parameter list opening at ``open_paren``."""
    depth = 0
    angle = 0
    commas = 0
    empty = True
    limit = min(len(body), open_paren + 2000)
    for i in range(open_paren, limit):
        ch = body[i]
        if ch == "(":
            depth += 1
            continue
        if ch == ")":
            depth -= 1
            if depth == 0:
                return (0 if empty else commas + 1), body[open_paren + 1:i]
            continue
        if ch == "<":
            angle += 1
        elif ch == ">":
            angle -= 1
        elif ch == "," and depth == 1 and angle <= 0:
            commas += 1
        if not ch.isspace():
            empty = False
    return commas + 1, body[open_paren + 1:limit]


def members(body, kind):
    """The members declared directly in a type body: ``(public method names,
    public "name/arity" signatures, every declared method name, member field
    type names, type names in public method signatures)``. Interface methods
    count as public unless marked private."""
    at = depth_at(body)
    methods = set()
    sigs = set()
    declared = set()
    api = set()
    for m in METHOD.finditer(body):
        if at(m.start(3)) != 0:
            continue
        modifiers = (m.group(1) or "").split()
        name = m.group(3)
        if name in KEYWORDS:
            continue
        declared.add(name)
        if "public" in modifiers or (kind == "interface" and "private" not in modifiers):
            count, params = parameters(body, m.end() - 1)
            methods.add(name)
            sigs.add(name + "/" + str(count))
            api.update(re.findall(r'\b[A-Z]\w*', m.group(2) + " " + params))
    fields = set()
    for m in FIELD.finditer(body):
        if at(m.start(1)) == 0 and m.group(1) not in ("return", "throw", "new"):
            fields.add(m.group(1).split(".")[-1])
    return methods, sigs, declared, fields, api


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


def is_test_source(path):
    """True for a path inside a test tree, whose types are never checked for
    equivalents: a FooTest beside Foo is the point, not a duplicate."""
    normalized = "/" + path.replace(os.sep, "/").lstrip("/")
    return any(t in normalized for t in TEST_SOURCES)


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


def java_sources(root, accept):
    """Yields (relative path, stat) for every Java file whose relative path
    ``accept`` admits."""
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
            elif e.name.endswith(".java") and e.name not in ("package-info.java",
                                                              "module-info.java"):
                rel = os.path.relpath(e.path, root)
                if accept(rel):
                    try:
                        yield rel, e.stat()
                    except OSError:
                        continue


def weighted(words, df, count):
    """Summed rarity of ``words`` given document frequencies ``df``: rare
    words count for more."""
    return sum(math.log((count + 1.0) / (1.0 + df.get(w, 0))) + 0.1 for w in words)


EQUIVALENT_HEADS = [{stem(w) for w in group} for group in (
    ("set", "sequence", "list", "collection", "array", "series", "progression",
     "range", "vector", "values", "run"),
    ("map", "table", "dictionary", "lookup", "registry"),
)]


def name_shape(name):
    """``(head, qualifiers)`` of a type name: its last stemmed camel-case word
    and the set of the others. ``ArithmeticIndexSet`` gives ``("set",
    {"arithmetic", "index"})``."""
    parts = [stem(w) for w in CAMEL.findall(name) if len(w) > 1]
    if not parts:
        return "", set()
    return parts[-1], set(parts[:-1])


def same_concept_name(a, b):
    """Whether two name shapes say the same concept: equal non-empty
    qualifiers (or one's contained in the other's) on heads that are equal or
    equivalent (``EQUIVALENT_HEADS``) - and the names are not identical in
    their qualifiers' absence."""
    (head_a, quals_a), (head_b, quals_b) = a, b
    if not quals_a or not quals_b:
        return False
    if not (quals_a <= quals_b or quals_b <= quals_a):
        return False
    if head_a == head_b:
        return quals_a == quals_b
    return any(head_a in g and head_b in g for g in EQUIVALENT_HEADS)


class TypeIndex:
    """The cached index of a repository's main-source types."""

    def __init__(self, root, types, df_words, df_doc):
        self.root = root
        self.types = types
        self.df_words = df_words
        self.df_doc = df_doc
        self.count = max(1, len(types))
        self._postings = None
        self._by_name = None
        self._own = {}

    @classmethod
    def load(cls, root):
        """Loads the cached index for ``root``, refreshing what is stale."""
        root = os.path.abspath(root)
        path = cache_path(root)
        cached = {}
        data = {}
        try:
            with open(path) as f:
                data = json.load(f)
            if data.get("version") == INDEX_VERSION and data.get("root") == root:
                cached = data.get("files", {})
        except (OSError, ValueError):
            cached = {}
        files = {}
        changed = False
        for rel, st in java_sources(root, is_main_source):
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
            for t in parsed:
                for key in USE_SITES:
                    t.pop(key, None)
            files[rel] = {"mtime": st.st_mtime, "size": st.st_size, "types": parsed}
            changed = True
        if set(files) != set(cached):
            changed = True
        types = [t for entry in files.values() for t in entry["types"]]
        if changed or data.get("df_words") is None:
            df_words, df_doc = {}, {}
            for t in types:
                for w in t["words"]:
                    df_words[w] = df_words.get(w, 0) + 1
                for w in t["doc"]:
                    df_doc[w] = df_doc.get(w, 0) + 1
        else:
            df_words, df_doc = data["df_words"], data["df_doc"]
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

    def name_weight(self, words):
        """Summed rarity of a set of name words."""
        return weighted(words, self.df_words, self.count)

    def doc_weight(self, words):
        """Summed rarity of a set of javadoc words."""
        return weighted(words, self.df_doc, self.count)

    def query(self, new):
        """The parts of ``new`` every comparison needs, computed once."""
        words = set(new["words"])
        sentence = set(new["sentence_words"])
        return {"new": new, "words": words, "name_weight": self.name_weight(words),
                "shape": name_shape(new["name"]),
                "sentence": sentence, "sentence_weight": self.doc_weight(sentence),
                "doc": set(new["doc"]) | words,
                "sigs": self.own_sigs(new),
                "supers": set(new["supers"]) - TRIVIAL_SUPERS}

    def by_name(self, name):
        """The indexed types with simple name ``name``."""
        if self._by_name is None:
            self._by_name = {}
            for t in self.types:
                self._by_name.setdefault(t["name"], []).append(t)
        return self._by_name.get(name, ())

    def contract(self, t, depth=0):
        """The public signatures ``t`` inherits from the indexed types it
        extends or implements, transitively."""
        out = set()
        if depth > 6:
            return out
        for s in t.get("supers", ()):
            for parent in self.by_name(s):
                out.update(parent.get("sigs", ()))
                out.update(self.contract(parent, depth + 1))
        return out

    def own_sigs(self, t):
        """The public signatures ``t`` declares beyond the contract it inherits
        and beyond the ubiquitous ones (``COMMON_METHODS``): implementing an
        interface's methods says nothing about which concept a type is."""
        key = t["fqn"] + "@" + t["path"]
        cached = self._own.get(key)
        if cached is None:
            inherited = self.contract(t)
            cached = {s for s in t.get("sigs", ())
                      if s not in inherited and s.split("/")[0] not in COMMON_METHODS}
            self._own[key] = cached
        return cached

    def components(self, q, candidate):
        """The separate signals of the same concept between a query and a
        candidate: ``(name, doc, methods)``, each in [0, 1]."""
        cw = set(candidate["words"])
        shared = q["words"] & cw
        name = 0.0
        if shared:
            name = 2 * self.name_weight(shared) / (q["name_weight"] + self.name_weight(cw))
        forward = 0.0
        if q["sentence"]:
            hit = q["sentence"] & (set(candidate["doc"]) | cw)
            forward = self.doc_weight(hit) / q["sentence_weight"]
        cs = set(candidate.get("sentence_words", ()))
        backward = 0.0
        if cs:
            backward = self.doc_weight(cs & q["doc"]) / self.doc_weight(cs)
        doc = (forward + backward) / 2.0
        csigs = self.own_sigs(candidate)
        common = len(q["sigs"] & csigs)
        methods = 0.0
        if common:
            methods = common / math.sqrt(len(q["sigs"]) * len(csigs))
            if common < 2:
                methods *= 0.5
        return name, doc, methods

    @staticmethod
    def concept(name, doc, methods):
        """One score of how likely two types are the same concept."""
        return 0.30 * name + 0.45 * doc + 0.25 * methods

    @staticmethod
    def near_copy(q, candidate, doc):
        """Whether ``new`` is a copy of the candidate: nearly the same public
        signatures, inherited ones included, described in nearly the same
        words."""
        mine = {s for s in q["new"].get("sigs", ()) if s.split("/")[0] not in COMMON_METHODS}
        theirs = {s for s in candidate.get("sigs", ()) if s.split("/")[0] not in COMMON_METHODS}
        shared = len(mine & theirs)
        return shared >= 3 and shared / float(len(mine | theirs)) >= NEAR_COPY["methods"] \
            and doc >= NEAR_COPY["doc"]

    def relation(self, q, candidate, trusted):
        """The relationship that marks a candidate as playing a different role
        from the new type, or ``""``:

          * ``sibling``: both extend or implement one non-trivial supertype;
          * ``role variant``: one name is the other's plus more words
            (``Grain`` and ``GrainParameters``, ``Receptor`` and
            ``CollectionReceptor``) - a part, a context, a specialization;
          * ``collaborator``: the new type's public methods take or return the
            candidate, or the candidate refers to the new type;
          * ``subtype of the new type``: the candidate extends it.

        ``trusted`` is false for a candidate the same change modified: a
        reference it gained may be there only to defeat this check.
        """
        new = q["new"]
        if q["supers"] & (set(candidate["supers"]) - TRIVIAL_SUPERS):
            return "sibling"
        cw = set(candidate["words"])
        if q["words"] != cw and (q["words"] < cw or cw < q["words"]):
            return "role variant"
        if candidate["name"] in new.get("api", ()):
            return "collaborator"
        if trusted and new["name"] in candidate["supers"]:
            return "subtype of the new type"
        if trusted and new["name"] in candidate.get("refs", ()):
            return "collaborator"
        return ""

    def scored(self, q, candidate, trusted=True):
        """The verdict on one candidate: ``None`` when it is not worth
        reporting, else a dict with ``score``, ``blocks`` and ``evidence``.

        A candidate blocks when the new type is a near-copy of it, or, absent
        a relation (see ``relation``), when

          * the own public signatures agree and the name or the javadoc agrees
            too (``behaviour``), or
          * the names say the same concept in another shape - the same
            qualifiers on an equivalent head, ``ArithmeticIndexSet`` beside
            ``ArithmeticIndexSequence`` - and the javadoc agrees (``naming``).

        A shared vocabulary - names and javadoc built from the same domain
        words, with no shared behaviour - is reported but does not block.
        """
        name, doc, methods = self.components(q, candidate)
        total = self.concept(name, doc, methods)
        naming = same_concept_name(q["shape"], name_shape(candidate["name"])) \
            and doc >= NAMED_DOC
        if total < WARN_THRESHOLD and not naming:
            return None
        relation = self.relation(q, candidate, trusted)
        copy = self.near_copy(q, candidate, doc)
        behaviour = methods >= STRONG_METHODS and (name >= STRONG_NAME or doc >= STRONG_DOC) \
            and total >= BLOCK_THRESHOLD
        blocks = copy or (not relation and (behaviour or naming))
        cw = set(candidate["words"])
        evidence = {
            "signals": {"name": round(name, 3), "doc": round(doc, 3),
                        "methods": round(methods, 3)},
            "relation": relation,
            "near_copy": copy,
            "same_concept_name": naming,
            "shared_name_words": sorted(q["words"] & cw),
            "shared_doc_words": sorted(q["sentence"] & set(candidate["doc"]))[:8],
            "shared_methods": sorted(q["sigs"] & self.own_sigs(candidate))[:8],
        }
        return {"score": round(max(total, BLOCK_THRESHOLD if blocks else 0.0), 3),
                "blocks": blocks, "evidence": evidence}

    def postings(self):
        """Inverted indexes from name word, javadoc word and signature to the
        positions of the types using it."""
        if self._postings is None:
            names, docs, sigs = {}, {}, {}
            for i, t in enumerate(self.types):
                for w in t["words"]:
                    names.setdefault(w, []).append(i)
                for w in set(t["doc"]) | set(t["words"]):
                    docs.setdefault(w, []).append(i)
                for s in t.get("sigs", ()):
                    sigs.setdefault(s, []).append(i)
            self._postings = (names, docs, sigs)
        return self._postings

    def pool(self, q):
        """Positions of the types that can reach the warning threshold: those
        sharing a name word, two javadoc words or two signatures."""
        names, docs, sigs = self.postings()
        pool = set()
        for w in q["words"]:
            pool.update(names.get(w, ()))
        for postings, keys in ((docs, q["sentence"]), (sigs, q["sigs"])):
            counts = {}
            for k in keys:
                for i in postings.get(k, ()):
                    counts[i] = counts.get(i, 0) + 1
            pool.update(i for i, n in counts.items() if n >= 2)
        return pool

    def candidates(self, new, exclude=(), untrusted=()):
        """The indexed types ``new`` may duplicate, blocking ones first.

        ``new`` itself is never a candidate, nor is any type whose
        fully-qualified name is in ``exclude`` (the other types the same change
        adds, which are not existing abstractions). ``untrusted`` names the
        paths the same change modified (see ``relation``). Other types declared
        in the same file as ``new`` remain candidates: Java allows several
        top-level types per file, and an equivalent beside the new type is
        still one.
        """
        q = self.query(new)
        found = []
        for i in self.pool(q):
            t = self.types[i]
            if t["fqn"] == new["fqn"] or t["fqn"] in exclude:
                continue
            verdict = self.scored(q, t, trusted=t["path"] not in untrusted)
            if verdict is not None:
                found.append({"name": t["name"], "fqn": t["fqn"], "path": t["path"],
                              "sentence": t["sentence"], "methods": t["methods"],
                              **verdict})
        found.sort(key=lambda c: (not c["blocks"], -c["score"]))
        return found[:MAX_CANDIDATES]


def uses(new, candidate):
    """Whether ``new``'s code actually uses ``candidate``: it constructs it,
    calls one of its static methods, or calls a method on a field, parameter
    or local declared with its type."""
    cname = candidate["name"]
    if cname in new.get("constructs", ()):
        return True
    names = {v.split(" ", 1)[1] for v in new.get("typed_vars", ())
             if v.split(" ", 1)[0] == cname}
    for call in new.get("calls", ()):
        receiver, method = call.split(".", 1)
        if receiver == cname or (receiver in names and method not in COMMON_METHODS):
            return True
    return False


def builds_on(new, candidate):
    """How ``new`` genuinely builds on ``candidate``, or ``None``: it extends
    or implements it AND overrides or calls one of its methods, or it uses
    it (see ``uses``). Naming a supertype it never uses, or holding a field it
    never calls, is not building on it."""
    cm = set(candidate.get("methods", ())) - COMMON_METHODS
    if candidate["name"] in new["supers"]:
        called = {c.split(".", 1)[1] for c in new.get("calls", ())
                  if c.split(".", 1)[0] in ("", "super", "this")}
        if cm & (set(new.get("declared", ())) | called):
            return "extends or implements"
    if uses(new, candidate):
        return "delegates to"
    return None


def structural_lift(new, candidates):
    """The first blocking candidate ``new`` builds on, and how, as
    ``(relation, candidate name)``; ``None`` when it builds on none."""
    for c in candidates:
        if c.get("blocks"):
            how = builds_on(new, c)
            if how:
                return (how, c["name"])
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
    """The ledger entry for a type, matched by fully-qualified name only: a
    justification for one type must not lift the block for a namesake in
    another package."""
    for e in entries:
        if e.get("type") == new["fqn"]:
            return e
    return None


def test_gap(root, test, new):
    """Why the test a ledger entry names does not demonstrate anything about
    ``new``, or ``None`` when it exists and refers to it.

    ``test`` is ``Class#method`` or ``Class``; the class must be a test source
    of the repository, the method (when named) must be declared in it, and the
    file must refer to ``new`` by its simple name.
    """
    if not isinstance(test, str) or not test.strip():
        return "no test named that demonstrates the distinction (\"test\")"
    cls, _, method = test.strip().partition("#")
    simple = cls.split(".")[-1]
    if not re.match(r'^[A-Za-z_]\w*$', simple):
        return "the named test \"" + test + "\" is not Class#method"
    for rel, _ in java_sources(root, is_test_source):
        if os.path.basename(rel) != simple + ".java":
            continue
        try:
            with open(os.path.join(root, rel), errors="replace") as f:
                code = strip_code(f.read())
        except OSError:
            continue
        if method and not re.search(r'\b' + re.escape(method) + r'\s*\(', code):
            return "the named test class " + rel + " declares no method " + method
        if not re.search(r'(?<![\w$])' + re.escape(new["name"]) + r'(?![\w$])', code):
            return "the named test " + rel + " never refers to " + new["name"]
        return None
    return "the named test class " + simple + " does not exist in the test sources"


def justification_gaps(root, entry, new, candidates):
    """What a ledger entry fails to justify about the blocking candidates;
    empty when it lifts the block."""
    if entry is None:
        return ["no ledger entry for this type in " + LEDGER_PATH]
    gaps = []
    gap = test_gap(root, entry.get("test"), new)
    if gap:
        gaps.append(gap)
    justified = entry.get("candidates")
    justified = justified if isinstance(justified, dict) else {}
    for c in candidates:
        if not c.get("blocks"):
            continue
        reasons = justified.get(c["fqn"])
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


def assess(index, source, path, name, ledger, exclude=(), untrusted=()):
    """Everything known about a new type: its candidates (each marked as
    blocking or only resembling), whether it builds on a blocking candidate,
    the ledger entry and its gaps, and whether it ``blocks``. ``exclude``
    names the other types the same change adds; ``untrusted`` the paths it
    modified."""
    rel = os.path.relpath(os.path.abspath(path), index.root) \
        if os.path.isabs(path) else path
    new = profile(source, rel, name)
    if new is None:
        return None
    candidates = index.candidates(new, exclude=exclude, untrusted=untrusted)
    blocking = [c for c in candidates if c["blocks"]]
    entry = ledger_entry(ledger, new)
    lift = structural_lift(new, blocking) if blocking else None
    gaps = justification_gaps(index.root, entry, new, blocking) if blocking else []
    audit = entry.get("audit") if entry else None
    for c in candidates:
        c.pop("methods", None)
    return {
        "name": new["name"],
        "fqn": new["fqn"],
        "path": rel,
        "candidates": candidates,
        "lift": ("%s %s" % lift) if lift else None,
        "justified": bool(blocking) and not gaps,
        "blocks": bool(blocking) and lift is None and bool(gaps),
        "gaps": gaps,
        "ledger": entry,
        "audit_verdict": audit.get("verdict") if isinstance(audit, dict) else None,
    }


def added_types(before, after, path=""):
    """The top-level types declared in ``after`` but not in ``before``."""
    old = {t["name"] for t in parse_types(before)}
    return [t for t in parse_types(after, path) if t["name"] not in old]


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

    Only main-source files are assessed; every file, test sources included,
    is listed with the types it declares, so that the harness can withhold
    every type of a file it refuses.

    Returns ``(assessed, added, declared)``: the assessments of the added
    types that resemble existing ones, the fully-qualified names of every
    added type, and, for every file given, the fully-qualified names of every
    top-level type it declares. The added types are excluded from one
    another's candidates, since the index is built from the working tree and
    they are not existing types; and every examined file is untrusted as a
    collaborator, since the change modified it.
    """
    index = TypeIndex.load(root)
    ledger = read_ledger(root)
    additions = []
    declared = {}
    for spec in files:
        rel, _, before_copy = spec.partition("::")
        after = read_text(os.path.join(root, rel))
        declared[rel] = [t["fqn"] for t in parse_types(after, rel)]
        if not is_main_source(rel):
            continue
        before = read_text(before_copy) if before_copy else ""
        additions.append((rel, after, added_types(before, after, rel)))
    added = [t["fqn"] for _, _, types in additions for t in types]
    exclude = set(added)
    untrusted = {rel for rel in declared if is_main_source(rel)}
    out = []
    for rel, after, types in additions:
        for t in types:
            result = assess(index, after, rel, t["name"], ledger,
                            exclude=exclude, untrusted=untrusted)
            if result is not None and result["candidates"]:
                out.append(result)
    return out, added, declared


def calibrate(root, prefixes=()):
    """How existing types fare when each is treated as if it were new.

    Every indexed type whose path starts with one of ``prefixes`` (every type
    when none is given) is assessed against all the others, from its own
    source, with an empty ledger. Returns the number of types, how many would
    only warn, how many would block, and the blocking ones with their top
    candidate.
    """
    index = TypeIndex.load(root)
    sample = [t for t in index.types
              if not prefixes or t["path"].startswith(tuple(prefixes))]
    sources = {}
    warned = 0
    blocking = []
    for t in sample:
        if t["path"] not in sources:
            sources[t["path"]] = read_text(os.path.join(index.root, t["path"]))
        result = assess(index, sources[t["path"]], t["path"], t["name"], [])
        if result is None or not result["candidates"]:
            continue
        if result["blocks"]:
            top = result["candidates"][0]
            blocking.append({"fqn": t["fqn"], "top": top["fqn"], "score": top["score"],
                             "evidence": top["evidence"]})
        else:
            warned += 1
    blocking.sort(key=lambda b: -b["score"])
    return {"types": len(sample), "warned": warned, "blocked": len(blocking),
            "block_rate": round(len(blocking) / float(max(1, len(sample))), 4),
            "blocking": blocking}


def main(argv):
    """``scan --root DIR FILE[::BEFORE]...`` or ``calibrate --root DIR
    [PREFIX...]``; prints JSON."""
    if len(argv) < 3 or argv[1] != "--root":
        print("usage: type_equivalence.py (scan|calibrate) --root DIR [ARG...]", file=sys.stderr)
        return 2
    command, root, rest = argv[0], os.path.abspath(argv[2]), argv[3:]
    if command == "scan":
        assessed, added, declared = scan(root, rest)
        print(json.dumps({"types": assessed, "added": added, "declared": declared}))
        return 0
    if command == "calibrate":
        print(json.dumps(calibrate(root, rest), indent=1))
        return 0
    print("unknown command: " + command, file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
