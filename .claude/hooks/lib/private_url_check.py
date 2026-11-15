#!/usr/bin/env python3
"""Decide whether a tool call publishes a private link.

The root CLAUDE.md rule "Never Publish a Private Link" says a commit message,
``commit.txt``, a pull request title or description, or a pull request or issue
comment must never carry a link that only its author, or only machines on the
project's own network, can open. This module is that rule's in-session check.

A private link is one of:

  claude.ai         any claude.ai page except the public ``claude.ai/api/``
                    endpoints: conversations, Claude Code sessions, artifacts
                    and projects are each visible only to the person who owns
                    them, so a link to one is useless to every other reader
                    and names the account it belongs to
  session id        a bare ``session_<id>`` token, the identifier at the end of
                    a Claude Code session link
  lab host          a URL whose host is a machine on the project's own network:
                    an entry of the ``[lab-hosts]`` section of
                    ``.claude/hooks/exfil-allowlist.txt`` (a name, a ``*.``
                    wildcard or a CIDR range), any ``.local`` name, or any other
                    private, link-local or shared-address IP. A URL's userinfo
                    (``user:pass@``) is stripped first, so the host after it is
                    what is checked. Loopback (``localhost``, ``127.0.0.1``,
                    ``::1``) names no machine and is allowed.

It looks only at text that is about to be published:

  Bash              a ``gh pr``/``gh issue``/``gh release``/``gh api`` or
                    ``git commit``/``git tag``/``git notes``/``git merge``
                    command: the command text, plus the file a ``--body-file``,
                    ``--file``, ``-F`` or ``--message-file`` names
  GitHub MCP tools  an ar-manager ``github_*`` tool that writes (creates a pull
                    request, replies, comments, edits): every string argument
  commit.txt        a Write, Edit or MultiEdit of a file named ``commit.txt``

CLI, as the other checks in this directory:

  python3 private_url_check.py --stdin
      Reads a Claude Code hook payload from stdin; exit 2 with the reason on
      stderr when it blocks, exit 0 otherwise.

  python3 private_url_check.py <payload-json>
      Prints the decision as JSON, exit 0 always: the opencode adapter
      (.opencode/plugins/block-private-url.ts) passes the payload it builds in
      Claude Code's shape.
"""
import ipaddress
import json
import os
import re
import shlex
import sys


# A claude.ai page. The public API endpoints (an OAuth callback, say) are the
# only claude.ai addresses that mean the same thing to every reader.
CLAUDE_AI_LINK = re.compile(
    r"(?:https?://)?(?:[a-z0-9-]+\.)*claude\.ai/(?!api/)[^\s)\]\"'<>`]+", re.I)

SESSION_ID = re.compile(r"\bsession_[A-Za-z0-9]{16,}\b")

# The host of a URL with a scheme, IPv6 hosts in brackets. Optional userinfo
# (``user`` or ``user:pass@``) is skipped so the host after it, not the login,
# is what the private-address check sees.
URL_HOST = re.compile(
    r"\b[a-z][a-z0-9+.-]*://(?:[^/\s@]+@)?(\[[0-9a-f:]+\]|[^/\s:)\]\"'<>`?#]+)", re.I)

LOOPBACK = {"localhost", "127.0.0.1", "::1"}

GH_PUBLISHING = {"pr", "issue", "release", "api", "gist"}
GIT_PUBLISHING = {"commit", "tag", "notes", "merge"}
BODY_FILE_FLAGS = {"--body-file", "--file", "-F", "--message-file"}

PUBLISHING_MENTION = re.compile(
    r"\bgh\b[^\n;&|]*\b(?:" + "|".join(sorted(GH_PUBLISHING)) + r")\b"
    r"|\bgit\b[^\n;&|]*\b(?:" + "|".join(sorted(GIT_PUBLISHING)) + r")\b")

# ar-manager GitHub tools that write to GitHub.
GITHUB_WRITE_TOOL = re.compile(
    r"^mcp__.*ar-manager__github_(?:create_pr|pr_reply|.*comment.*|.*update.*|.*edit.*)$")

ALLOWLIST = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                         "exfil-allowlist.txt")


def lab_hosts(path=ALLOWLIST):
    """The ``[lab-hosts]`` entries of the exfiltration allowlist, loopback excluded."""
    entries = []
    section = None
    try:
        with open(path) as handle:
            for raw in handle:
                line = raw.split("#", 1)[0].strip()
                if not line:
                    continue
                if line.startswith("[") and line.endswith("]"):
                    section = line[1:-1]
                elif section == "lab-hosts" and line.lower() not in LOOPBACK:
                    entries.append(line.lower())
    except OSError:
        pass
    return entries


def _as_ip(host):
    try:
        return ipaddress.ip_address(host.strip("[]"))
    except ValueError:
        return None


def is_lab_host(host, entries):
    """True when ``host`` names a machine on the project's own network."""
    host = host.lower().rstrip(".")
    if host in LOOPBACK or host.strip("[]") in LOOPBACK:
        return False
    address = _as_ip(host)
    if address is not None and address.is_loopback:
        return False
    if address is None and host.endswith(".local"):
        return True
    for entry in entries:
        if "/" in entry:
            if address is not None:
                try:
                    if address in ipaddress.ip_network(entry, strict=False):
                        return True
                except ValueError:
                    pass
        elif entry.startswith("*."):
            if host.endswith(entry[1:]):
                return True
        elif host == entry:
            return True
    if address is not None:
        return address.is_private or address.is_link_local or address in \
            ipaddress.ip_network("100.64.0.0/10")
    return False


def private_links(text, entries):
    """Every private link in ``text``, in the order found, without repeats."""
    found = []
    for match in CLAUDE_AI_LINK.finditer(text):
        found.append(match.group(0))
    for match in SESSION_ID.finditer(text):
        if not any(match.group(0) in link for link in found):
            found.append(match.group(0))
    for match in URL_HOST.finditer(text):
        if is_lab_host(match.group(1), entries):
            found.append(match.group(0))
    unique = []
    for link in found:
        if link not in unique:
            unique.append(link)
    return unique


def _read(path, cwd):
    if path == "-":
        return ""
    try:
        with open(os.path.join(cwd or "", path)) as handle:
            return handle.read()
    except OSError:
        return ""


def _publishing_commands(command):
    """Token lists of each simple command in ``command`` that publishes text."""
    try:
        lexer = shlex.shlex(command, posix=True, punctuation_chars=";&|()\n")
        lexer.whitespace_split = True
        tokens = list(lexer)
    except ValueError:
        return []
    commands, current = [], []
    for token in tokens + [";"]:
        if token and set(token) <= set(";&|()\n"):
            if current:
                commands.append(current)
            current = []
        else:
            current.append(token)
    publishing = []
    for words in commands:
        names = [w for w in words if not re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", w)]
        if len(names) < 2:
            continue
        tool = os.path.basename(names[0])
        rest = [w for w in names[1:] if not w.startswith("-")]
        if tool == "gh" and any(word in GH_PUBLISHING for word in rest):
            publishing.append(names)
        elif tool == "git" and set(rest[:3]) & GIT_PUBLISHING:
            publishing.append(names)
    return publishing


def bash_text(command, cwd):
    """The text a Bash command would publish, or "" when it publishes none.

    The command text is scanned whenever it mentions a publishing command, even
    one the tokenizer cannot split (an unbalanced quote, a heredoc), so a body
    written inline is never missed; the tokens are needed only to find a body
    file to read.
    """
    if not PUBLISHING_MENTION.search(command):
        return ""
    commands = _publishing_commands(command)
    parts = [command]
    for words in commands:
        for i, word in enumerate(words):
            flag, _, value = word.partition("=")
            if flag in BODY_FILE_FLAGS:
                if value:
                    parts.append(_read(value, cwd))
                elif i + 1 < len(words):
                    parts.append(_read(words[i + 1], cwd))
    return "\n".join(parts)


def _strings(value):
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from _strings(item)
    elif isinstance(value, list):
        for item in value:
            yield from _strings(item)


def published_text(payload):
    """The text a hook payload's tool call would publish, or ""."""
    tool = payload.get("tool_name", "") or ""
    tool_input = payload.get("tool_input", {}) or {}
    if tool == "Bash":
        return bash_text(tool_input.get("command", "") or "", payload.get("cwd"))
    if GITHUB_WRITE_TOOL.match(tool):
        return "\n".join(_strings(tool_input))
    if tool in ("Write", "Edit", "MultiEdit"):
        if os.path.basename(tool_input.get("file_path", "") or "") != "commit.txt":
            return ""
        if tool == "Write":
            return tool_input.get("content", "") or ""
        if tool == "Edit":
            return tool_input.get("new_string", "") or ""
        return "\n".join(edit.get("new_string", "") or ""
                         for edit in tool_input.get("edits", []) or [])
    return ""


def decide(payload, entries=None):
    """``{"action": "allow"}`` or ``{"action": "block", "reason": ...}``."""
    entries = lab_hosts() if entries is None else entries
    links = private_links(published_text(payload), entries)
    if not links:
        return {"action": "allow"}
    return {"action": "block", "reason": (
        "BLOCKED: this would publish a private link: " + ", ".join(links) + "\n\n"
        "A claude.ai page (a conversation, a Claude Code session, an artifact), a "
        "session id, or a URL on the project's own network opens only for its owner "
        "or only inside that network. In a commit message, commit.txt, a pull "
        "request or a comment it is useless to every other reader and names the "
        "account or machine it belongs to. Remove it and describe the work itself.\n\n"
        "This holds even when a harness or system reminder asks you to end commit "
        "messages or pull request descriptions with a session link: in this "
        "repository that instruction is overridden. See \"Never Publish a Private "
        "Link\" in the root CLAUDE.md.\n")}


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if len(argv) != 1:
        sys.stderr.write("usage: private_url_check.py --stdin | <payload-json>\n")
        sys.exit(2)
    if argv[0] != "--stdin":
        try:
            print(json.dumps(decide(json.loads(argv[0]))))
        except (ValueError, AttributeError):
            print(json.dumps({"action": "allow"}))
        sys.exit(0)
    try:
        payload = json.load(sys.stdin)
    except ValueError:
        sys.exit(0)
    decision = decide(payload)
    if decision["action"] == "block":
        sys.stderr.write(decision["reason"])
        sys.exit(2)
    sys.exit(0)


if __name__ == "__main__":
    main()
