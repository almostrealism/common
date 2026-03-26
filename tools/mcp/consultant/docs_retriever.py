"""
Documentation retrieval for the AR Consultant.

Reuses the search logic from the ar-docs MCP server to find relevant
documentation sections. Operates directly on the filesystem rather than
calling ar-docs over MCP, avoiding an extra process hop.
"""

import os
import re
from pathlib import Path
from typing import Optional


# Resolve project root: AR_DOCS_DIR env var takes priority (required
# when running as a pushed tool outside the common repo), otherwise
# fall back to the standard path relative to this script.
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
    "optimize": "engine/optimize",
    "render": "engine/render",
    "utils": "engine/utils",
    "utils-http": "engine/utils-http",
}

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


def _read_file(path: Path, max_chars: int = 50000) -> str:
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
            # All module READMEs
            for mod, mod_path in MODULE_PATHS.items():
                readme = self.common_dir / mod_path / "README.md"
                if readme.exists():
                    readmes.append(readme)
                mod_docs = self.common_dir / mod_path / "docs"
                if mod_docs.exists():
                    others.extend(mod_docs.glob("*.md"))

            # Quick reference, CLAUDE.md, internals (not READMEs)
            qr = self.docs_dir / "QUICK_REFERENCE.md"
            if qr.exists():
                others.append(qr)
            claude_md = self.common_dir / "CLAUDE.md"
            if claude_md.exists():
                others.append(claude_md)
            if self.internals_dir.exists():
                others.extend(self.internals_dir.glob("*.md"))

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
            if self.modules_dir.exists():
                files.extend(sorted(self.modules_dir.glob("*.html")))

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

        # All module HTML pages
        if self.modules_dir.exists():
            files.extend(self.modules_dir.glob("*.html"))

        # All module READMEs and doc subdirectories
        for mod, mod_path in MODULE_PATHS.items():
            readme = self.common_dir / mod_path / "README.md"
            if readme.exists():
                files.append(readme)
            mod_docs = self.common_dir / mod_path / "docs"
            if mod_docs.exists():
                files.extend(mod_docs.glob("*.md"))

        # Quick reference, CLAUDE.md, internals
        qr = self.docs_dir / "QUICK_REFERENCE.md"
        if qr.exists():
            files.append(qr)
        claude_md = self.common_dir / "CLAUDE.md"
        if claude_md.exists():
            files.append(claude_md)
        if self.internals_dir.exists():
            files.extend(self.internals_dir.glob("*.md"))

        return files

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

        for file_path in self._all_doc_files(module):
            content = _read_file(file_path)
            if not content:
                continue

            if file_path.suffix == ".html":
                content = _extract_text_from_html(content)

            lines = content.split("\n")
            for i, line in enumerate(lines):
                if _fuzzy_match(query, line):
                    start = max(0, i - context_lines)
                    end = min(len(lines), i + context_lines + 1)
                    chunk = "\n".join(lines[start:end])

                    try:
                        rel_path = str(file_path.relative_to(self.common_dir))
                    except ValueError:
                        rel_path = str(file_path)

                    results.append({
                        "file": rel_path,
                        "line": i + 1,
                        "context": chunk,
                    })

                    if len(results) >= max_results:
                        return results

        return results

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
            content = _read_file(file_path)
            if not content:
                return results

            if file_path.suffix == ".html":
                content = _extract_text_from_html(content)

            lines = content.split("\n")
            for i, line in enumerate(lines):
                if _fuzzy_match(query, line):
                    start = max(0, i - context_lines)
                    end = min(len(lines), i + context_lines + 1)
                    chunk = "\n".join(lines[start:end])

                    try:
                        rel_path = str(file_path.relative_to(self.common_dir))
                    except ValueError:
                        rel_path = str(file_path)

                    results.append({
                        "file": rel_path,
                        "line": i + 1,
                        "context": chunk,
                    })

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
            for line_idx, score in scored_matches[:8]:  # Consider up to 8 candidates
                # Skip if this line's context would overlap with already-added result
                if any(abs(line_idx - prev) < context_lines * 2 for prev in seen_line_ranges):
                    continue

                start = max(0, line_idx - context_lines)
                end = min(len(lines), line_idx + context_lines + 1)
                chunk = "\n".join(lines[start:end])

                try:
                    rel_path = str(file_path.relative_to(self.common_dir))
                except ValueError:
                    rel_path = str(file_path)

                file_results.append({
                    "file": rel_path,
                    "line": line_idx + 1,
                    "context": chunk,
                })
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
            if not any(r["file"] == m["file"] and r["line"] == m["line"] for m in final_md):
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
        seen_md: set[tuple[str, int]] = set()
        seen_html: set[tuple[str, int]] = set()

        # Search each keyword and merge results
        for kw in keywords:
            if len(md_results) >= max_chunks and len(html_results) >= 3:
                break

            typed = self.search_by_type(
                kw,
                max_md_results=max_chunks,
                max_html_results=3,
                context_lines=6,
            )

            # Merge markdown results
            for r in typed["markdown"]:
                if len(md_results) >= max_chunks:
                    break
                key = (r["file"], r["line"])
                if key not in seen_md:
                    seen_md.add(key)
                    md_results.append(r)

            # Merge HTML results (for reference only)
            for r in typed["html"]:
                if len(html_results) >= 5:
                    break
                key = (r["file"], r["line"])
                if key not in seen_html:
                    seen_html.add(key)
                    html_results.append(r)

        # Format markdown context
        context = ""
        if md_results:
            parts = []
            total = 0
            for r in md_results:
                chunk = f"[{r['file']}:{r['line']}]\n{r['context']}"
                if total + len(chunk) > max_total_chars:
                    break
                parts.append(chunk)
                total += len(chunk)
            context = "\n\n---\n\n".join(parts)

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
        seen_md: set[tuple[str, int]] = set()
        seen_html: set[tuple[str, int]] = set()

        # First try the full query
        typed = self.search_by_type(
            query,
            max_md_results=max_chunks,
            max_html_results=3,
            context_lines=6,
        )

        for r in typed["markdown"]:
            key = (r["file"], r["line"])
            seen_md.add(key)
            md_results.append(r)

        for r in typed["html"]:
            key = (r["file"], r["line"])
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
                    key = (r["file"], r["line"])
                    if key not in seen_md:
                        seen_md.add(key)
                        md_results.append(r)

                for r in kw_typed["html"]:
                    if len(html_results) >= 5:
                        break
                    key = (r["file"], r["line"])
                    if key not in seen_html:
                        seen_html.add(key)
                        html_results.append(r)

        # Format markdown context
        context = ""
        if md_results:
            parts = []
            total = 0
            for r in md_results:
                chunk = f"[{r['file']}:{r['line']}]\n{r['context']}"
                if total + len(chunk) > max_total_chars:
                    break
                parts.append(chunk)
                total += len(chunk)
            context = "\n\n---\n\n".join(parts)

        # Extract unique HTML file references
        html_refs = list({r["file"] for r in html_results})

        return {
            "context": context,
            "html_refs": html_refs,
            "markdown_results": md_results,
        }
