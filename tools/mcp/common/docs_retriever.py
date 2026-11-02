"""
Documentation retrieval shared by the AR MCP servers.

Reuses the search logic from the ar-docs MCP server to find relevant
documentation sections. Operates directly on the filesystem rather than
calling ar-docs over MCP, avoiding an extra process hop. There is no
precomputed index: files are globbed and scored per query, so the corpus
is whatever is on disk at the time of the call.

Originally part of ar-consultant; moved to tools/mcp/common/ so ar-manager
can ground its memory retrieval in the same documentation. ar-manager runs
from a container image rather than a checkout, which is what ``AR_DOCS_DIR``
below exists for.
"""

import os
import re
from pathlib import Path
from typing import Optional


# Resolve project root: AR_DOCS_DIR env var takes priority (required when
# running outside a checkout of the common repo — notably the ar-manager
# container, which bakes the corpus in at a fixed path), otherwise fall back
# to the standard path relative to this script.
SCRIPT_DIR = Path(__file__).parent
_env_docs = os.environ.get("AR_DOCS_DIR", "").strip()
if _env_docs:
    DOCS_DIR = Path(_env_docs)
    COMMON_DIR = DOCS_DIR.parent
else:
    COMMON_DIR = SCRIPT_DIR.parent.parent.parent  # tools/mcp/consultant -> common
    DOCS_DIR = COMMON_DIR / "docs"
MODULES_DIR = DOCS_DIR / "modules"
INTERNALS_DIR = DOCS_DIR / "internals"

# Module metadata (mirrors ar-docs server)
# Module short names -> descriptions (for HTML doc lookup and display)
MODULES = {
    "meta": "Naming, identity, lifecycle interfaces",
    "io": "Logging, metrics, file I/O",
    "relation": "Relational computation, Producer/Evaluable, Process optimization",
    "code": "Expression trees, code generation, kernel indexing",
    "collect": "Collection abstractions (currently a dependency waypoint)",
    "hardware": "Hardware acceleration backends, memory management",
    "algebra": "Vector, Scalar, PackedCollection, CollectionProducer",
    "geometry": "3D geometry, ray tracing primitives, cameras",
    "time": "Temporal operations, FFT, filtering, signal processing",
    "stats": "Probability distributions, statistical sampling",
    "graph": "Neural network layers, Cell-Receptor-Transmitter pattern",
    "ml": "Transformer models, attention, StateDictionary, diffusion",
    "audio": "Audio synthesis, signal processing, filters, MIDI",
    "music": "Pattern composition, notes, arrangements",
    "compose": "Audio scene orchestration, generative audio, ML integration",
    "color": "RGB, lighting, shaders, textures",
    "space": "Scene management, meshes, spatial acceleration",
    "physics": "Rigid body dynamics, absorbers, photon fields",
    "heredity": "Genetic algorithms, evolutionary computation",
    "chemistry": "Periodic table (Element enum), atomic structure",
    "biology": "Neurons, synapses, connectomes, spiking neuron dynamics",
    "bioscience": "Connectome loading and simulation, Brian2 reproduction",
    "optimize": "ModelOptimizer, loss functions, Adam, evolutionary algorithms",
    "render": "Ray tracing engine, lighting",
    "utils": "Testing framework, TestSuiteBase, code policy enforcement",
    "utils-http": "HTTP authentication, event delivery",
}

# Map module short names to actual directory paths relative to COMMON_DIR
MODULE_PATHS = {
    "meta": "base/meta",
    "io": "base/io",
    "relation": "base/relation",
    "code": "base/code",
    "collect": "base/collect",
    "hardware": "base/hardware",
    "algebra": "compute/algebra",
    "geometry": "compute/geometry",
    "time": "compute/time",
    "stats": "compute/stats",
    "graph": "domain/graph",
    "ml": "engine/ml",
    "audio": "engine/audio",
    "music": "studio/music",
    "compose": "studio/compose",
    "color": "domain/color",
    "space": "domain/space",
    "physics": "domain/physics",
    "heredity": "domain/heredity",
    "chemistry": "domain/chemistry",
    "biology": "domain/biology",
    "bioscience": "studio/bioscience",
    "optimize": "engine/optimize",
    "render": "engine/render",
    "utils": "engine/utils",
    "utils-http": "engine/utils-http",
}


# Standalone (non-layer) documentation roots: first-class documentation that
# does not live under a Maven layer module. The corpus walk (walk_corpus)
# indexes these along with every other document; the list remains as the set
# of roots that must always resolve to something on disk, which
# test_docs_retriever asserts, so deleting or moving one of these documents
# fails a test instead of silently shrinking what consult can answer from.
#
# Markdown entries are individual files OR directories (in which case
# *.md inside is included); HTML entries are individual files OR
# directories (in which case *.html inside is included).
STANDALONE_MD_ROOTS = [
    # CI pipeline and Master Agent Dispatch documentation. .github/CLAUDE.md is
    # the authoritative description of the dispatch workflow, the QA rounds and
    # the open-PR limit; .github/CI_ARCHITECTURE.md covers the pipeline design;
    # tools/ci/README.md is the only document that describes each CI script
    # (validate-agent-commit.sh among them); docs/COMPUTATION_MODEL.md is a
    # top-level doc not reached by the docs/internals glob. None of these lived
    # under a Maven layer module, so they were invisible to search until listed
    # here.
    ".github/CLAUDE.md",
    ".github/CI_ARCHITECTURE.md",
    "tools/ci/README.md",
    "docs/COMPUTATION_MODEL.md",
    "flowtree/runtime/README.md",
    "flowtree/runtime/docs",
    "tools/mcp/README.md",
    "tools/mcp/CLAUDE.md",
    "tools/mcp/SECRETS.md",
    "tools/mcp/consultant/README.md",
    "tools/mcp/jmx/README.md",
    "tools/mcp/manager/README.md",
    "tools/mcp/memory/README.md",
    "tools/mcp/profile-analyzer/README.md",
    "tools/mcp/test-runner/README.md",
]
STANDALONE_HTML_ROOTS = [
    "docs/tutorials",
]


# What the corpus walk leaves out. Everything else under the corpus root that
# is markdown or HTML is indexed, so a new document is searchable the moment
# it is added — the hand-kept allowlist this replaced let the Dockerfile copy
# documents the index never read. An entry ending in "/" excludes a directory
# (relative to the corpus root); any other entry is one exact file.
# tools/mcp/manager/test_dockerfile_packaging.py checks that every *.md and
# *.html in the repository is either indexed or named here.
INDEX_EXCLUSIONS = (
    # Plans describe intended, not actual, behaviour; indexed as documentation
    # they would plant future-tense claims as present fact.
    "docs/plans/",
    # The root README is not copied into the image (only the root CLAUDE.md
    # is), and the two stray working files beside it are not documentation.
    "README.md",
    "HOST_ARRAY_AUDIT.md",
    "PR_REVIEW_CARRYOVER.md",
    # The consult evaluation's own reports describe consult's past answers,
    # wrong ones included; indexed, they would be retrieved as documentation.
    "tools/mcp/manager/eval/",
)

# Directory names the walk never enters: build output, caches, VCS state,
# and gitignored runtime artifacts (``.flowtree`` holds per-job agent state
# such as oop-review.md).
_SKIP_DIRS = frozenset({
    ".git", "target", "node_modules", "__pycache__", ".pytest_cache",
    ".mypy_cache", "results", "runs", ".idea", ".vscode", ".flowtree",
})

_DOC_SUFFIXES = (".md", ".html")

# Chunks one file may contribute to a plain search().
_MAX_HITS_PER_FILE = 3


def is_indexed(rel_path: str) -> bool:
    """Whether the corpus walk indexes the document at ``rel_path``
    (repository-relative, ``/``-separated)."""
    if not rel_path.endswith(_DOC_SUFFIXES):
        return False
    parts = rel_path.split("/")
    if any(part in _SKIP_DIRS for part in parts[:-1]):
        return False
    for entry in INDEX_EXCLUSIONS:
        if entry.endswith("/") and rel_path.startswith(entry):
            return False
        if rel_path == entry:
            return False
    return True


def walk_corpus(common_dir: Path) -> list[Path]:
    """Every indexed document under ``common_dir``, sorted by path."""
    found: list[Path] = []
    for dirpath, dirnames, filenames in os.walk(common_dir):
        dirnames[:] = sorted(d for d in dirnames if d not in _SKIP_DIRS)
        for name in filenames:
            path = Path(dirpath) / name
            rel = path.relative_to(common_dir).as_posix()
            if is_indexed(rel):
                found.append(path)
    return sorted(found)


def _expand_roots(common_dir: Path, entries: list[str], suffix: str) -> list[Path]:
    """Expand a list of standalone-root entries (files or directories)
    into concrete file paths that exist on disk.

    @param common_dir: project root
    @param entries:    relative paths from {@link STANDALONE_MD_ROOTS} or
                       {@link STANDALONE_HTML_ROOTS}
    @param suffix:     file suffix to glob when an entry is a directory
                       (".md" or ".html")
    @return list of resolved {@link Path} objects, in input order, with
            directory globs sorted alphabetically
    """
    results: list[Path] = []
    for entry in entries:
        p = common_dir / entry
        if p.is_file():
            results.append(p)
        elif p.is_dir():
            results.extend(sorted(p.glob(f"*{suffix}")))
    return results

# Common synonyms for fuzzy matching (from ar-docs)
SYNONYMS = {
    "assignment": {"assign", "destination", "target", "write"},
    "destination": {"assignment", "target", "output", "dest"},
    "provider": {"producer", "supplier", "source", "wavedataprovider"},
    "producer": {"provider", "supplier", "computation"},
    "index": {"indices", "indexing", "offset", "position"},
    "traverse": {"traversal", "walk", "iterate", "iteration"},
    "dynamic": {"computed", "runtime", "variable"},
    "collection": {"array", "buffer", "memory", "data"},
    "expression": {"expr", "computation", "calculation"},
    "scope": {"context", "block", "statement"},
    "evaluate": {"eval", "compute", "execute", "run"},
    "compile": {"compilation", "generate", "codegen"},
    "sin": {"sine", "trigonometry", "trig", "sinusoidal"},
    "cos": {"cosine", "trigonometry", "trig"},
    "tan": {"tangent", "trigonometry", "trig"},
    "tanh": {"hyperbolic", "trigonometry", "trig"},
    "trigonometry": {"trig", "sin", "cos", "tan", "sine", "cosine", "tangent", "geometry"},
    "math": {"mathematical", "arithmetic", "calculation", "trigonometry"},
    "identifier": {"id", "hash", "md5", "content-hash", "getidentifier"},
    "key": {"path", "filepath", "file-path", "location", "getkey"},
    "path": {"filepath", "file-path", "location", "key", "directory"},
    "file": {"filepath", "path", "location", "wav", "audio"},
    "resolve": {"find", "lookup", "locate", "get"},
    "library": {"audiolibrary", "collection", "samples"},
    "persistence": {"save", "load", "protobuf", "serialize"},
    "protobuf": {"persistence", "proto", "serialize", "binary"},
    "similarity": {"similar", "matching", "compare", "distance"},
    "wave": {"audio", "wavedata", "sample", "wav"},
    "sample": {"audio", "wave", "sound", "clip"},
}


def _get_word_variants(word: str) -> set[str]:
    """Get word variants including stems and synonyms."""
    word_lower = word.lower()
    variants = {word_lower}

    base = word_lower
    for suffix in ["ing", "tion", "ation", "ed", "er", "s", "es", "able", "ible"]:
        if base.endswith(suffix) and len(base) > len(suffix) + 2:
            stem = base[:-len(suffix)]
            variants.add(stem)
            for new_suffix in ["", "e", "ing", "tion", "ed", "s"]:
                variants.add(stem + new_suffix)

    for key, syns in SYNONYMS.items():
        if word_lower == key or word_lower in syns:
            variants.add(key)
            variants.update(syns)

    return variants


def _fuzzy_match(query: str, text: str) -> bool:
    """Check if query fuzzy-matches text using stemming and synonyms."""
    text_lower = text.lower()
    query_lower = query.lower()

    if query_lower in text_lower:
        return True

    query_words = re.split(r"\s+", query_lower)
    for qword in query_words:
        if len(qword) < 3:
            continue
        variants = _get_word_variants(qword)
        if not any(v in text_lower for v in variants):
            return False

    return len(query_words) > 0


def _extract_text_from_html(html: str) -> str:
    """Extract readable text from HTML, preserving code blocks."""
    html = re.sub(r"<script[^>]*>.*?</script>", "", html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r"<style[^>]*>.*?</style>", "", html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r"<pre[^>]*>(.*?)</pre>", r"\n```\n\1\n```\n", html, flags=re.DOTALL)
    html = re.sub(r"<code[^>]*>(.*?)</code>", r"`\1`", html, flags=re.DOTALL)
    html = re.sub(r"<h1[^>]*>(.*?)</h1>", r"\n# \1\n", html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r"<h2[^>]*>(.*?)</h2>", r"\n## \1\n", html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r"<h3[^>]*>(.*?)</h3>", r"\n### \1\n", html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r"<li[^>]*>(.*?)</li>", r"- \1\n", html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r"<p[^>]*>(.*?)</p>", r"\1\n\n", html, flags=re.DOTALL | re.IGNORECASE)
    html = re.sub(r"<br\s*/?>", "\n", html, flags=re.IGNORECASE)
    html = re.sub(r"<[^>]+>", "", html)
    html = re.sub(r"\n{3,}", "\n\n", html)
    html = re.sub(r" +", " ", html)
    html = html.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
    html = html.replace("&quot;", '"').replace("&#39;", "'").replace("&nbsp;", " ")
    return html.strip()


# Per-file read cap. Raised from the original 50 000 because the authoritative
# CI documents are long: .github/CLAUDE.md is ~70 000 characters, and the facts
# consult must answer from it (the QA-round branch prefixes, the MAX_OPEN_PRS
# open-PR limit) sit past the 54 000th character — a 50 000 cap silently cut
# them off, so copying the file into the corpus was not enough on its own. The
# cap stays finite as a guard against a pathologically large file dominating a
# search; 200 000 covers every document in the corpus today with headroom.
_MAX_DOC_CHARS = 200000


def _read_file(path: Path, max_chars: int = _MAX_DOC_CHARS) -> str:
    """Read a file with size limit, returning empty string on failure."""
    try:
        if not path.exists():
            return ""
        content = path.read_text(encoding="utf-8")
        if len(content) > max_chars:
            content = content[:max_chars]
        return content
    except Exception:
        return ""


# Stop words to strip when extracting keywords from natural language queries
_STOP_WORDS = frozenset({
    "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "do", "does", "did", "will", "would", "could",
    "should", "may", "might", "shall", "can", "need", "dare", "ought",
    "used", "to", "of", "in", "for", "on", "with", "at", "by", "from",
    "as", "into", "through", "during", "before", "after", "above", "below",
    "between", "out", "off", "over", "under", "again", "further", "then",
    "once", "here", "there", "when", "where", "why", "how", "what", "which",
    "who", "whom", "this", "that", "these", "those", "am", "it", "its",
    "he", "she", "we", "they", "i", "me", "my", "you", "your", "and",
    "but", "or", "nor", "not", "so", "if", "about", "up", "all", "each",
    "every", "both", "few", "more", "most", "other", "some", "such", "no",
    "only", "own", "same", "than", "too", "very", "just", "because",
    "one", "also", "use", "using", "get", "set", "like",
})


def _extract_keywords(query: str) -> list[str]:
    """Extract meaningful keywords from a natural language query.

    Strips stop words and short tokens, returning terms likely to match
    documentation content.
    """
    words = re.split(r"\s+", query.strip().lower())
    keywords = []
    for w in words:
        # Strip punctuation from edges
        w = w.strip("?.,!;:'\"()")
        if len(w) < 3:
            continue
        if w in _STOP_WORDS:
            continue
        keywords.append(w)
    return keywords


# A markdown ATX heading. HTML documents reach the matcher with their <h1>-<h3>
# already rewritten to this form by _extract_text_from_html, so one pattern
# serves both.
_HEADING = re.compile(r"^(#{1,6})\s+(.+?)\s*#*\s*$")

# A subsection at most this many lines long is returned whole, rather than as
# the fixed window around the matching line: the whole subsection is what the
# heading trail names, and a short one fits the chunk budget comfortably.
SECTION_GROW_LINES = 30

# Separator between the headings of a chunk's trail.
SECTION_SEPARATOR = " › "


def _headings(lines: list[str]) -> list[tuple[int, int, str]]:
    """The headings of a document as ``(line_index, level, title)``.

    Lines inside fenced code blocks are skipped, so a shell comment such as
    ``# install`` in an example is not mistaken for a heading.
    """
    found: list[tuple[int, int, str]] = []
    in_fence = False
    for i, line in enumerate(lines):
        stripped = line.lstrip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        match = _HEADING.match(line)
        if match:
            found.append((i, len(match.group(1)), match.group(2).strip()))
    return found


def _enclosing_section(headings: list[tuple[int, int, str]], line_count: int,
                       index: int) -> tuple[list[str], int, int]:
    """The heading trail of the line at ``index`` and its section's bounds.

    Returns ``(trail, start, end)``: the titles from the outermost heading
    down to the deepest one at or above the line, and the zero-based line
    span of that deepest heading's section (up to, not including, the next
    heading of the same or a higher level). A line before any heading has an
    empty trail and the whole document as its span.
    """
    stack: list[tuple[int, int, str]] = []
    for line_index, level, title in headings:
        if line_index > index:
            break
        while stack and stack[-1][1] >= level:
            stack.pop()
        stack.append((line_index, level, title))
    if not stack:
        return [], 0, line_count - 1
    start, level, _ = stack[-1]
    end = line_count - 1
    for line_index, other_level, _ in headings:
        if line_index > start and other_level <= level:
            end = line_index - 1
            break
    return [title for _, _, title in stack], start, end


def make_chunk(rel_path: str, lines: list[str], index: int, context_lines: int,
               headings: list[tuple[int, int, str]]) -> dict:
    """Build one retrieval result for a match on the line at ``index``.

    The chunk is the enclosing subsection when that is at most
    :data:`SECTION_GROW_LINES` long, and otherwise the ``context_lines``
    window around the match clipped to the subsection, so a chunk never
    straddles two subjects. It records the 1-based inclusive line range it
    actually covers and the heading trail above it, which is what lets a
    citation state honestly what the reader saw instead of naming only the
    line that matched.

    Returns:
        A dict with ``file``, ``line`` (the matching line), ``start``,
        ``end``, ``section`` (the heading trail) and ``context``.
    """
    trail, sec_start, sec_end = _enclosing_section(headings, len(lines), index)
    if trail and sec_end - sec_start + 1 <= SECTION_GROW_LINES:
        start, end = sec_start, sec_end
    else:
        # Keep the window's full width, sliding it inside the subsection: a
        # match on the heading line itself then shows the text under it.
        width = 2 * context_lines + 1
        start = max(sec_start, index - context_lines)
        end = min(sec_end, start + width - 1)
        start = max(sec_start, end - width + 1)
    return {
        "file": rel_path,
        "line": index + 1,
        "start": start + 1,
        "end": end + 1,
        "section": SECTION_SEPARATOR.join(trail),
        "context": "\n".join(lines[start:end + 1]),
    }


def chunk_label(result: dict) -> str:
    """The ``file:start-end § section`` label of a retrieval result.

    Falls back to the matching line alone for a result that carries no range
    (one built by an older caller), so the label never claims more than the
    result records.
    """
    start, end = result.get("start"), result.get("end")
    loc = (f"{result['file']}:{start}-{end}" if start and end
           else f"{result['file']}:{result.get('line', '')}")
    section = result.get("section")
    return f"{loc} § {section}" if section else loc


def _format_context(md_results: list[dict], max_total_chars: int) -> str:
    """Render markdown results as labelled chunks within a character budget."""
    parts = []
    total = 0
    for r in md_results:
        chunk = f"[{chunk_label(r)}]\n{r['context']}"
        if total + len(chunk) > max_total_chars:
            break
        parts.append(chunk)
        total += len(chunk)
    return "\n\n---\n\n".join(parts)


def _result_key(result: dict) -> tuple:
    """Identity of a result for de-duplication: the span it covers, so two
    matches inside one short subsection collapse into one chunk."""
    return (result["file"], result.get("start", result["line"]),
            result.get("end", result["line"]))


def _overlaps(result: dict, others: list[dict]) -> bool:
    """Whether ``result`` covers lines already covered by one of ``others``
    in the same file. Overlapping chunks would show the reader the same
    text twice and spend the chunk budget on it."""
    _, start, end = _result_key(result)
    for other in others:
        o_file, o_start, o_end = _result_key(other)
        if o_file == result["file"] and start <= o_end and end >= o_start:
            return True
    return False


def _path_depth(path: Path, base: Path) -> int:
    """Calculate the depth of a path relative to a base directory.

    Deeper paths (more subdirectories) are considered more specific.
    """
    try:
        rel = path.relative_to(base)
        return len(rel.parts)
    except ValueError:
        return 0


class DocsRetriever:
    """Searches project documentation and returns relevant chunks."""

    def __init__(self, common_dir: Optional[Path] = None):
        self.common_dir = common_dir or COMMON_DIR
        self.docs_dir = self.common_dir / "docs"
        self.modules_dir = self.docs_dir / "modules"
        self.internals_dir = self.docs_dir / "internals"
        self._corpus_files: Optional[list[Path]] = None

    def _markdown_files(self, module: Optional[str] = None) -> tuple[list[Path], list[Path]]:
        """Collect markdown documentation files, split into READMEs and others.

        Returns:
            Tuple of (readme_files, other_files) where:
            - readme_files: Module README.md files (searched first to guarantee coverage)
            - other_files: All other markdown files, sorted by depth (deepest first)
        """
        readmes = []
        others = []

        if module:
            mod_path = MODULE_PATHS.get(module, module)
            readme = self.common_dir / mod_path / "README.md"
            if readme.exists():
                readmes.append(readme)
            module_docs = self.common_dir / mod_path / "docs"
            if module_docs.exists():
                others.extend(module_docs.glob("*.md"))
        else:
            # The whole corpus. READMEs go in the readmes bucket so they're
            # guaranteed to be searched; everything else goes in others.
            for path in self._corpus():
                if path.suffix != ".md":
                    continue
                (readmes if path.name == "README.md" else others).append(path)

        # Sort others by depth (deepest/most specific first), then alphabetically
        others.sort(key=lambda p: (-_path_depth(p, self.common_dir), str(p)))
        return readmes, others

    def _html_files(self, module: Optional[str] = None) -> list[Path]:
        """Collect HTML documentation files."""
        files = []

        if module:
            html = self.modules_dir / f"{module}.html"
            if html.exists():
                files.append(html)
        else:
            files.extend(p for p in self._corpus() if p.suffix == ".html")

        return files

    def _all_doc_files(self, module: Optional[str] = None) -> list[Path]:
        """Collect all documentation file paths."""
        files = []

        if module:
            mod_path = MODULE_PATHS.get(module, module)
            html = self.modules_dir / f"{module}.html"
            readme = self.common_dir / mod_path / "README.md"
            if html.exists():
                files.append(html)
            if readme.exists():
                files.append(readme)
            module_docs = self.common_dir / mod_path / "docs"
            if module_docs.exists():
                files.extend(module_docs.glob("*.md"))
            return files

        return list(self._corpus())

    def _corpus(self) -> list[Path]:
        """Every indexed document (see :func:`walk_corpus`), walked once per
        retriever: the corpus is fixed for the life of the server."""
        if self._corpus_files is None:
            self._corpus_files = walk_corpus(self.common_dir)
        return self._corpus_files

    def search(
        self,
        query: str,
        module: Optional[str] = None,
        max_results: int = 10,
        context_lines: int = 4,
    ) -> list[dict]:
        """Search documentation for a query.

        Args:
            query: The search term or phrase.
            module: Optional module name to restrict search.
            max_results: Maximum number of result chunks to return.
            context_lines: Lines of context around each match.

        Returns:
            List of dicts with keys: file, line, context.
        """
        results = []

        # Markdown before HTML (the HTML pages are reference renderings), and
        # at most _MAX_HITS_PER_FILE chunks from one file, so a single long
        # page cannot fill every result slot.
        files = sorted(self._all_doc_files(module),
                       key=lambda p: (p.suffix == ".html", str(p)))
        for file_path in files:
            lines = self._lines(file_path)
            headings = None
            file_results: list[dict] = []
            for i, line in enumerate(lines):
                if _fuzzy_match(query, line):
                    if headings is None:
                        headings = _headings(lines)
                    chunk = make_chunk(
                        self._rel(file_path), lines, i, context_lines, headings)
                    if _overlaps(chunk, file_results):
                        continue
                    file_results.append(chunk)
                    results.append(chunk)
                    if len(results) >= max_results:
                        return results
                    if len(file_results) >= _MAX_HITS_PER_FILE:
                        break

        return results

    def _rel(self, file_path: Path) -> str:
        """A file's path relative to the corpus root, as results report it."""
        try:
            return str(file_path.relative_to(self.common_dir))
        except ValueError:
            return str(file_path)

    @staticmethod
    def _lines(file_path: Path) -> list[str]:
        """A document's lines, with HTML reduced to its readable text; empty
        when the file is missing or unreadable."""
        content = _read_file(file_path)
        if not content:
            return []
        if file_path.suffix == ".html":
            content = _extract_text_from_html(content)
        return content.split("\n")

    def search_by_type(
        self,
        query: str,
        module: Optional[str] = None,
        max_md_results: int = 8,
        max_html_results: int = 5,
        context_lines: int = 6,
    ) -> dict:
        """Search documentation separately by file type.

        Markdown files are searched with prioritization:
        - READMEs are searched first to guarantee at least one README result
        - Then deeper/more specific files are searched
        - Results are deduplicated by file to ensure diversity

        HTML files are searched separately and returned for reference.

        Args:
            query: The search term or phrase.
            module: Optional module name to restrict search.
            max_md_results: Maximum markdown result chunks.
            max_html_results: Maximum HTML result chunks.
            context_lines: Lines of context around each match.

        Returns:
            Dict with 'markdown' and 'html' keys, each containing a list of
            result dicts with keys: file, line, context.
        """
        readme_files, other_md_files = self._markdown_files(module)

        readme_results = []
        other_results = []
        html_results = []

        def _search_file(file_path: Path) -> list[dict]:
            """Search a single file and return matching results."""
            results = []
            lines = self._lines(file_path)
            headings = None
            for i, line in enumerate(lines):
                if _fuzzy_match(query, line):
                    if headings is None:
                        headings = _headings(lines)
                    chunk = make_chunk(
                        self._rel(file_path), lines, i, context_lines, headings)
                    if _overlaps(chunk, results):
                        continue
                    results.append(chunk)

                    # Limit results per file to avoid one file dominating
                    if len(results) >= 3:
                        break

            return results

        # Step 1: Search all READMEs and track match counts for prioritization
        readme_match_counts: dict[str, int] = {}  # file -> match count

        # Extract individual words from query for multi-keyword matching
        query_words = [w.lower() for w in re.split(r'\s+', query) if len(w) >= 3]

        for file_path in readme_files:
            content = _read_file(file_path)
            if not content:
                continue
            lines = content.split("\n")

            # Find all matching lines and score by number of query words matched
            scored_matches = []
            for i, line in enumerate(lines):
                if _fuzzy_match(query, line):
                    # Score: count how many query words appear in this line
                    line_lower = line.lower()
                    word_matches = sum(1 for w in query_words if w in line_lower)
                    scored_matches.append((i, word_matches))

            if not scored_matches:
                continue

            total_matches = len(scored_matches)
            readme_match_counts[str(file_path.relative_to(self.common_dir))] = total_matches

            # Sort by score (most keywords matched) then by position
            scored_matches.sort(key=lambda x: (-x[1], x[0]))

            # Take top results, prioritizing lines that match multiple keywords
            file_results = []
            seen_line_ranges = set()  # Avoid overlapping context
            headings = _headings(lines)
            for line_idx, score in scored_matches[:8]:  # Consider up to 8 candidates
                # Skip if this line's context would overlap with already-added result
                if any(abs(line_idx - prev) < context_lines * 2 for prev in seen_line_ranges):
                    continue

                chunk = make_chunk(
                    self._rel(file_path), lines, line_idx, context_lines, headings)
                if _overlaps(chunk, file_results):
                    continue
                file_results.append(chunk)
                seen_line_ranges.add(line_idx)

                if len(file_results) >= 3:  # Max 3 non-overlapping results per file
                    break

            readme_results.extend(file_results)

        # Sort README results by match count (most relevant first)
        readme_results.sort(
            key=lambda r: -readme_match_counts.get(r["file"], 0)
        )

        # Step 2: Search other markdown files (sorted by depth, deepest first)
        for file_path in other_md_files:
            file_results = _search_file(file_path)
            other_results.extend(file_results)

        # Step 3: Combine results with README priority and file diversity
        final_md = []
        seen_files: set[str] = set()
        file_result_count: dict[str, int] = {}  # Track results per file

        # Allow up to 2 results from high-relevance READMEs (those with many matches)
        HIGH_MATCH_THRESHOLD = 10

        # First, add results from the best README (up to 2 if high-relevance)
        if readme_results:
            best_readme = readme_results[0]["file"]
            best_match_count = readme_match_counts.get(best_readme, 0)
            max_from_best = 2 if best_match_count >= HIGH_MATCH_THRESHOLD else 1

            for r in readme_results:
                if r["file"] != best_readme:
                    continue
                if file_result_count.get(best_readme, 0) >= max_from_best:
                    break
                if len(final_md) >= max_md_results:
                    break
                final_md.append(r)
                file_result_count[best_readme] = file_result_count.get(best_readme, 0) + 1

            seen_files.add(best_readme)

        # Then add specific (deeper) results, one per file for diversity
        for r in other_results:
            if len(final_md) >= max_md_results:
                break
            if r["file"] not in seen_files:
                final_md.append(r)
                seen_files.add(r["file"])
                file_result_count[r["file"]] = 1

        # If we have slots left, add more README results from different files
        for r in readme_results:
            if len(final_md) >= max_md_results:
                break
            if r["file"] not in seen_files:
                final_md.append(r)
                seen_files.add(r["file"])
                file_result_count[r["file"]] = 1

        # If still have slots, add additional results from already-seen files
        all_md_results = readme_results + other_results
        for r in all_md_results:
            if len(final_md) >= max_md_results:
                break
            # Check if this exact result is already included
            if not _overlaps(r, final_md):
                final_md.append(r)

        # Step 4: Search HTML files
        for file_path in self._html_files(module):
            if len(html_results) >= max_html_results:
                break
            file_results = _search_file(file_path)
            for r in file_results:
                if len(html_results) >= max_html_results:
                    break
                html_results.append(r)

        return {
            "markdown": final_md,
            "html": html_results,
        }

    def read_module(self, module: str) -> str:
        """Read the full documentation for a module.

        Args:
            module: Module name (e.g. 'ml', 'hardware').

        Returns:
            The module documentation text, or an error message.
        """
        if module not in MODULES:
            return f"Unknown module '{module}'. Available: {', '.join(sorted(MODULES))}"

        mod_path = MODULE_PATHS.get(module, module)
        html_path = self.modules_dir / f"{module}.html"
        readme_path = self.common_dir / mod_path / "README.md"

        content = ""
        if html_path.exists():
            content = _extract_text_from_html(_read_file(html_path))
        elif readme_path.exists():
            content = _read_file(readme_path)

        mod_docs = self.common_dir / mod_path / "docs"
        if mod_docs.exists():
            for md_file in sorted(mod_docs.glob("*.md")):
                doc = _read_file(md_file, max_chars=20000)
                if doc:
                    sep = f"\n\n---\n\n# {md_file.stem}\n\n"
                    content = content + sep + doc if content else doc

        return content or f"No documentation found for module '{module}'"

    def read_quick_reference(self) -> str:
        """Read the condensed API quick reference."""
        return _read_file(self.docs_dir / "QUICK_REFERENCE.md")

    def get_context_for_keywords(
        self,
        keywords: list[str],
        max_chunks: int = 5,
        max_total_chars: int = 8000,
    ) -> dict:
        """Get documentation context for a list of agent-provided keywords.

        Searches markdown and HTML documentation separately. Markdown results
        are formatted into a context string for the LLM. HTML results are
        returned as references for the coding agent to explore if needed.

        Markdown prioritization:
        - At least one README.md result is guaranteed if available
        - Deeper/more specific files are prioritized over shallow ones

        Args:
            keywords: List of search terms, ordered by importance.
            max_chunks: Maximum number of markdown chunks to include in context.
            max_total_chars: Maximum total characters in the markdown context.

        Returns:
            Dict with:
            - 'context': Formatted markdown context string for LLM
            - 'html_refs': List of HTML file references for agent exploration
            - 'markdown_results': Raw markdown results list
        """
        md_results = []
        html_results = []
        seen_html: set[tuple] = set()

        # Search each keyword, then take results round-robin: every keyword's
        # best chunk before any keyword's second. Filling the budget keyword
        # by keyword let the first keyword or two (often the broadest, such
        # as a workflow name) take every slot, so the chunk a later, more
        # specific keyword matched — the one holding the asked-for value —
        # never reached the reader.
        per_keyword: list[list[dict]] = []
        for kw in keywords:
            typed = self.search_by_type(
                kw,
                max_md_results=max_chunks,
                max_html_results=3,
                context_lines=6,
            )
            per_keyword.append(typed["markdown"])

            # Merge HTML results (for reference only)
            for r in typed["html"]:
                if len(html_results) >= 5:
                    break
                key = _result_key(r)
                if key not in seen_html:
                    seen_html.add(key)
                    html_results.append(r)

        # Each pass gives every keyword one *new* chunk: a result another
        # keyword already contributed is skipped in favour of that keyword's
        # next one, so a keyword is not starved because its best chunk was
        # shared.
        cursors = [0] * len(per_keyword)
        while len(md_results) < max_chunks and any(
                cursors[i] < len(r) for i, r in enumerate(per_keyword)):
            for i, results in enumerate(per_keyword):
                while cursors[i] < len(results) and _overlaps(
                        results[cursors[i]], md_results):
                    cursors[i] += 1
                if len(md_results) >= max_chunks:
                    break
                if cursors[i] < len(results):
                    md_results.append(results[cursors[i]])
                    cursors[i] += 1

        context = _format_context(md_results, max_total_chars)

        # Extract unique HTML file references
        html_refs = list({r["file"] for r in html_results})

        return {
            "context": context,
            "html_refs": html_refs,
            "markdown_results": md_results,
        }

    def get_context_for_query(
        self,
        query: str,
        max_chunks: int = 5,
        max_total_chars: int = 8000,
    ) -> dict:
        """Get documentation context for a natural language query.

        Searches markdown and HTML documentation separately. Markdown results
        are formatted into a context string for the LLM. HTML results are
        returned as references for the coding agent to explore if needed.

        Handles natural language questions by:
        1. First searching for the full query
        2. Then extracting keywords and searching each independently
        3. Deduplicating and assembling the best chunks

        Markdown prioritization:
        - At least one README.md result is guaranteed if available
        - Deeper/more specific files are prioritized over shallow ones

        Args:
            query: The search query (can be a full natural language question).
            max_chunks: Maximum number of markdown chunks to include in context.
            max_total_chars: Maximum total characters in the markdown context.

        Returns:
            Dict with:
            - 'context': Formatted markdown context string for LLM
            - 'html_refs': List of HTML file references for agent exploration
            - 'markdown_results': Raw markdown results list
        """
        md_results = []
        html_results = []
        seen_html: set[tuple] = set()

        # First try the full query
        typed = self.search_by_type(
            query,
            max_md_results=max_chunks,
            max_html_results=3,
            context_lines=6,
        )

        for r in typed["markdown"]:
            md_results.append(r)

        for r in typed["html"]:
            key = _result_key(r)
            seen_html.add(key)
            html_results.append(r)

        # If we didn't find enough markdown results, extract keywords and search
        if len(md_results) < max_chunks:
            keywords = _extract_keywords(query)
            for kw in keywords:
                if len(md_results) >= max_chunks:
                    break

                kw_typed = self.search_by_type(
                    kw,
                    max_md_results=3,
                    max_html_results=2,
                    context_lines=6,
                )

                for r in kw_typed["markdown"]:
                    if len(md_results) >= max_chunks:
                        break
                    if not _overlaps(r, md_results):
                        md_results.append(r)

                for r in kw_typed["html"]:
                    if len(html_results) >= 5:
                        break
                    key = _result_key(r)
                    if key not in seen_html:
                        seen_html.add(key)
                        html_results.append(r)

        context = _format_context(md_results, max_total_chars)

        # Extract unique HTML file references
        html_refs = list({r["file"] for r in html_results})

        return {
            "context": context,
            "html_refs": html_refs,
            "markdown_results": md_results,
        }


# Words that match too many documents to narrow a search usefully.
_GENERIC_KEYWORDS = {
    "default", "interface", "class", "method", "pattern",
    "type", "module", "use", "create", "build", "make",
}


def keyword_guidance(keywords: Optional[list[str]] = None) -> str:
    """Advice to append when a search returned little of use.

    Poor keywords are the usual cause of an empty documentation result, and
    the two failure modes are consistent enough to name: no keywords at all,
    and a list of individual common words where a phrase was needed.

    Args:
        keywords: The keywords a caller supplied, if any. Each is compared
            case-insensitively and tested for embedded spaces, so they must
            be strings.

    Returns:
        A leading-space-prefixed sentence to append to a note, or the empty
        string when the keywords look reasonable.
    """
    hints = []

    if not keywords:
        hints.append(
            "Tip: Provide explicit keywords for better results. "
            "Example: keywords=[\"StateDictionary\", \"weight loading\"]"
        )
    else:
        all_single = all(" " not in kw for kw in keywords)
        has_generic = any(kw.lower() in _GENERIC_KEYWORDS for kw in keywords)
        if all_single and len(keywords) > 2:
            hints.append(
                "Tip: Use multi-word phrases as keywords instead of "
                "individual words. For example, [\"Features mixin\", "
                "\"CollectionFeatures\"] works better than "
                "[\"Features\", \"mixin\", \"CollectionFeatures\", "
                "\"default\", \"interface\"] because single common words "
                "match too many documents."
            )
        elif has_generic:
            hints.append(
                "Tip: Avoid generic keywords like 'default', 'interface', "
                "'pattern'. Use domain-specific terms or multi-word phrases "
                "that match documentation headings."
            )

    return (" " + " ".join(hints)) if hints else ""
