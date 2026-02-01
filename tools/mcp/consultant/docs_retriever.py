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


# Resolve project root relative to this script
SCRIPT_DIR = Path(__file__).parent
COMMON_DIR = SCRIPT_DIR.parent.parent.parent  # tools/mcp/consultant -> common
DOCS_DIR = COMMON_DIR / "docs"
MODULES_DIR = DOCS_DIR / "modules"
INTERNALS_DIR = DOCS_DIR / "internals"

# Module metadata (mirrors ar-docs server)
MODULES = {
    "uml": "Annotations, lifecycle, metadata",
    "io": "Logging, metrics, file I/O",
    "relation": "Producer/Evaluable pattern",
    "code": "Expression trees, code generation",
    "collect": "PackedCollection, multi-dimensional arrays",
    "hardware": "Hardware acceleration backends",
    "algebra": "Vector, Scalar, linear algebra",
    "geometry": "3D geometry, ray tracing primitives",
    "time": "Temporal operations, FFT, filtering",
    "stats": "Probability distributions",
    "graph": "Neural network layers, Cell pattern",
    "ml": "Transformer models, attention",
    "audio": "Audio synthesis, AudioLibrary, sample management",
    "compose": "Audio persistence, protobuf, PrototypeDiscovery",
    "color": "RGB, lighting, shaders",
    "space": "Scene management, meshes",
    "physics": "Quantum mechanics, atoms",
    "heredity": "Genetic algorithms",
    "chemistry": "Periodic table, elements",
    "optimize": "Loss functions, training",
    "render": "Ray tracing engine",
    "utils": "Testing framework",
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


class DocsRetriever:
    """Searches project documentation and returns relevant chunks."""

    def __init__(self, common_dir: Optional[Path] = None):
        self.common_dir = common_dir or COMMON_DIR
        self.docs_dir = self.common_dir / "docs"
        self.modules_dir = self.docs_dir / "modules"
        self.internals_dir = self.docs_dir / "internals"

    def _all_doc_files(self, module: Optional[str] = None) -> list[Path]:
        """Collect all documentation file paths."""
        files = []

        if module:
            html = self.modules_dir / f"{module}.html"
            readme = self.common_dir / module / "README.md"
            if html.exists():
                files.append(html)
            if readme.exists():
                files.append(readme)
            module_docs = self.common_dir / module / "docs"
            if module_docs.exists():
                files.extend(module_docs.glob("*.md"))
            return files

        # All module HTML pages
        if self.modules_dir.exists():
            files.extend(self.modules_dir.glob("*.html"))

        # All module READMEs and doc subdirectories
        for mod in MODULES:
            readme = self.common_dir / mod / "README.md"
            if readme.exists():
                files.append(readme)
            mod_docs = self.common_dir / mod / "docs"
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

    def read_module(self, module: str) -> str:
        """Read the full documentation for a module.

        Args:
            module: Module name (e.g. 'ml', 'hardware').

        Returns:
            The module documentation text, or an error message.
        """
        if module not in MODULES:
            return f"Unknown module '{module}'. Available: {', '.join(sorted(MODULES))}"

        html_path = self.modules_dir / f"{module}.html"
        readme_path = self.common_dir / module / "README.md"

        content = ""
        if html_path.exists():
            content = _extract_text_from_html(_read_file(html_path))
        elif readme_path.exists():
            content = _read_file(readme_path)

        mod_docs = self.common_dir / module / "docs"
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

    def get_context_for_query(
        self,
        query: str,
        max_chunks: int = 5,
        max_total_chars: int = 8000,
    ) -> str:
        """Get a consolidated documentation context string for a query.

        Handles natural language questions by extracting keywords and
        searching for each independently, then deduplicating and assembling
        the best chunks.

        Args:
            query: The search query (can be a full natural language question).
            max_chunks: Maximum number of chunks to include.
            max_total_chars: Maximum total characters in the context.

        Returns:
            A formatted string of documentation excerpts.
        """
        # First try the raw query (works for short keyword queries)
        results = self.search(query, max_results=max_chunks, context_lines=6)

        # If that didn't find enough, extract keywords and search each one
        if len(results) < max_chunks:
            keywords = _extract_keywords(query)
            seen = {(r["file"], r["line"]) for r in results}
            for kw in keywords:
                if len(results) >= max_chunks:
                    break
                kw_results = self.search(kw, max_results=3, context_lines=6)
                for r in kw_results:
                    key = (r["file"], r["line"])
                    if key not in seen:
                        seen.add(key)
                        results.append(r)
                        if len(results) >= max_chunks:
                            break

        if not results:
            return ""

        parts = []
        total = 0
        for r in results:
            chunk = f"[{r['file']}:{r['line']}]\n{r['context']}"
            if total + len(chunk) > max_total_chars:
                break
            parts.append(chunk)
            total += len(chunk)

        return "\n\n---\n\n".join(parts)
