"""Network-capable command checks for the exfiltration guard.

Three things live here:

1. The patterns that mark an inline program or a script file as
   network-capable (``NETWORK_CODE_PATTERNS``, ``SHELL_SCRIPT_PATTERNS``)
   and the helpers that scan for them (``_scan_code``, ``_scan_shell_script``).
2. The URL and curl/wget handling, where the destination has to be an
   allowlisted lab host unless the request is a read.
3. The other tools that reach the network — ssh family, raw sockets, probe
   tools — which are only allowed against allowlisted lab hosts.

Network access is only ever permitted to a host the allowlist recognises.
A request that names a host and cannot be verified is a block; the
guard fails closed.
"""
import os
import re
import sys

if __name__ != "__main__" and not __package__:
    HERE = os.path.dirname(os.path.abspath(__file__))
    if HERE not in sys.path:
        sys.path.insert(0, HERE)

from urllib.parse import urlparse

from exfil_bash_lex import GuardError, normalize_host


# Patterns that mark an inline program (or a script file) as network-capable.
#
# What is matched is the means, not a mention. A bare `https://…` used to
# be on this list, which reads a URL anywhere in the program — including
# one inside a string the program merely writes to a file — as an attempt
# to reach it. That cost more than it bought: quoting a URL in prose is
# ordinary, and every way of actually fetching one still appears here as
# a module, an API, or a shell-out (`urllib`, `requests`, `fetch(`,
# `open("https:`, `subprocess`, `os.system`, …).
#
# The trade is deliberate and it is not free: a program using a network
# API nobody listed, against a URL literal, now passes where the bare URL
# would have caught it. That is the accepted cost of a guard people can
# work with — one that blocks prose teaches its way around itself, which
# buys nothing at all. For the same reason, most entries below match a
# *use* of the module or API it names — an import, a module-qualified
# call, a constructor — never the bare word on its own: a string literal
# that happens to contain "socket" or "subprocess" is prose, not code.
# A handful of entries with no natural qualified form still match a bare
# identifier, on the same accepted-cost basis as everywhere else in this
# list -- see docs/internals/exfiltration-guard.md for which ones.
NETWORK_CODE_PATTERNS = [re.compile(p) for p in (
    r"\brequests\.",
    r"\bimport\s+urllib\b|\bfrom\s+urllib\s+import\b|\burllib\.\w+",
    r"\bhttp\.client\b",
    r"\bimport\s+httplib\b|\bfrom\s+httplib\s+import\b|\bhttplib\.\w+",
    r"\bimport\s+httpx\b|\bfrom\s+httpx\s+import\b|\bhttpx\.\w+",
    r"\bimport\s+aiohttp\b|\bfrom\s+aiohttp\s+import\b|\baiohttp\.\w+",
    r"\bimport\s+socket\b|\bfrom\s+socket\s+import\b|\bsocket\.\w+|\bsocket\(",
    r"\bimport\s+smtplib\b|\bfrom\s+smtplib\s+import\b|\bsmtplib\.\w+",
    r"\bimport\s+ftplib\b|\bfrom\s+ftplib\s+import\b|\bftplib\.\w+",
    r"\bimport\s+telnetlib\b|\bfrom\s+telnetlib\s+import\b|\btelnetlib\.\w+",
    r"\bimport\s+paramiko\b|\bfrom\s+paramiko\s+import\b|\bparamiko\.\w+",
    r"\bimport\s+boto3\b|\bfrom\s+boto3\s+import\b|\bboto3\.\w+",
    r"\bimport\s+botocore\b|\bfrom\s+botocore\s+import\b|\bbotocore\.\w+",
    r"\bimport\s+pycurl\b|\bfrom\s+pycurl\s+import\b|\bpycurl\.\w+",
    r"\bimport\s+websockets?\b|\bfrom\s+websockets?\s+import\b|\bwebsockets?\.\w+|"
    r"\bnew\s+WebSocket\s*\(|require\(\s*['\"]ws['\"]\s*\)|\bfrom\s+['\"]ws['\"]",
    r"\bfetch\s*\(", r"\bXMLHttpRequest\b",
    r"\bhttps?\.request\b", r"\bnet\.connect\b", r"\bnet\.createConnection\b",
    r"require\(\s*['\"]dgram['\"]\s*\)|\bfrom\s+['\"]dgram['\"]|\bdgram\.\w+",
    r"\bgot\s*\(",
    r"require\(\s*['\"]axios['\"]\s*\)|\bfrom\s+['\"]axios['\"]|\baxios\.\w+|\baxios\(",
    r"\bnode-fetch\b", r"\bNet::", r"\bLWP::", r"\bIO::Socket\b",
    r"\bHTTP::Tiny\b", r"\bcurl_", r"\bfsockopen\b", r"\bstream_socket_client\b",
    r"\bInvoke-WebRequest\b", r"\bInvoke-RestMethod\b", r"\bNet\.WebClient\b",
    r"\bSystem\.Net\b", r"\bHttpClient\b", r"\bURLConnection\b", r"\bjava\.net\b",
    r"\bnew\s+URL\s*\(", r"\bSocket\s*\(", r"\bdo shell script\b",
    r"\bsystem\s*\(",
    r"\bimport\s+subprocess\b|\bfrom\s+subprocess\s+import\b|\bsubprocess\.\w+",
    r"\bos\.system\b",
    r"\bchild_process\b", r"\bexec\s*\(", r"\b[Pp]open\s*\(", r"/inet/",
    r"\|\s*getline\b", r"\bopen\s*\(\s*['\"]https?:",
)]


# Shell script FILES are scanned by pattern rather than parsed as command
# lines: array literals, case arms and multi-line strings defeat a
# tokenizer that was built for one command. Comment lines are dropped
# first; anything else that names a network tool, a push, or a raw socket
# counts. Prose in an echo still trips it — that is the accepted cost.
SHELL_SCRIPT_PATTERNS = [re.compile(p) for p in (
    r"(?<![\w./-])(?:curl|wget|scp|sftp|rsync|ssh|nc|ncat|netcat|socat|telnet|ftp|lftp|tftp)"
    r"(?![\w.-])",
    r"/dev/(?:tcp|udp)/",
    r"\bgit\s+push\b",
    r"\bgh\s+(?:gist|release|api|secret|variable|repo\s+create)\b",
    r"(?<![\w./-])(?:aws|gsutil|gcloud|az|rclone|s3cmd|mail|mailx|sendmail|osascript|ngrok|"
    r"cloudflared|docker\s+push|npm\s+publish|twine)(?![\w.-])",
    r"\bopenssl\s+s_client\b",
)]


# Programs that take a destination host and are allowed only to lab hosts.
UPLOAD_TOOLS = frozenset({"curl", "wget"})
SSH_FAMILY = frozenset({"ssh", "scp", "sftp", "rsync"})
RAW_SOCKET_TOOLS = frozenset({"nc", "ncat", "netcat", "socat", "telnet", "ftp",
                              "lftp", "tftp", "openssl"})
PROBE_TOOLS = frozenset({"ping", "ping6", "dig", "nslookup", "host", "traceroute",
                         "mtr", "nmap", "whois"})


_HOSTISH = re.compile(r"^(?:[A-Za-z0-9_.-]+@)?\[?[A-Za-z0-9.:_-]+\]?(?::\d+)?$")
_REMOTE_SPEC = re.compile(r"^(?:[A-Za-z0-9_.-]+@)?(\[[0-9A-Fa-f:.]+\]|[A-Za-z0-9][A-Za-z0-9.-]*)::?(?!//)")

# A GET is allowed to any host, but a URL long enough to carry a payload in
# its query string is not.
MAX_URL_LENGTH = 512


def _scan_code(code, label):
    for pattern in NETWORK_CODE_PATTERNS:
        if pattern.search(code):
            raise _GuardError(f"{label} contains network-capable code ({pattern.pattern}); "
                              f"inline programs that can open a connection are denied")


def _scan_shell_script(text, label):
    """Block when a shell script file names a network tool outside comments."""
    code = "\n".join(line for line in text.split("\n") if not line.lstrip().startswith("#"))
    for pattern in SHELL_SCRIPT_PATTERNS:
        match = pattern.search(code)
        if match:
            raise _GuardError(f"{label} invokes {match.group(0).strip()!r}; a script that can reach "
                              f"the network does not run through the guard")


def _extract_hosts_from_url(token):
    if "://" not in token:
        return None
    parsed = urlparse(token)
    if parsed.scheme in ("file", ""):
        return ""
    return parsed.hostname or ""


_CURL_VALUE_CLUSTER = "oHAbceuUxmwKTdFXryYzCEQtPD"
_WGET_VALUE_CLUSTER = "OoaPUTtwQBeiFl"


def _url_positionals(argv, value_flags, cluster_chars):
    """Tokens that name a destination for curl/wget-style programs."""
    urls = []
    i = 1
    while i < len(argv):
        tok = argv[i]
        if tok == "--url" and i + 1 < len(argv):
            urls.append(argv[i + 1])
            i += 2
            continue
        if tok.startswith("--url="):
            urls.append(tok.split("=", 1)[1])
        elif tok.startswith("-") and tok != "-":
            if tok in value_flags or (len(tok) == 2 and tok in value_flags):
                i += 1
            elif not tok.startswith("--") and len(tok) > 2 and tok[-1] in cluster_chars:
                i += 1
        else:
            urls.append(tok)
        i += 1
    return urls


_CURL_VALUE_FLAGS = frozenset({
    "-o", "-H", "-A", "-b", "-c", "-e", "-u", "-U", "-x", "-m", "-w", "-K", "-T",
    "-d", "-F", "-X", "-r", "-y", "-Y", "-z", "-C", "-E", "-Q", "-t", "--output",
    "--header", "--user-agent", "--cookie", "--cookie-jar", "--referer", "--user",
    "--proxy-user", "--proxy", "--max-time", "--write-out", "--config",
    "--upload-file", "--data", "--data-ascii", "--data-binary", "--data-raw",
    "--data-urlencode", "--form", "--form-string", "--request", "--connect-timeout",
    "--retry", "--cacert", "--cert", "--key", "--resolve", "--interface",
    "--dns-servers", "--unix-socket", "--abstract-unix-socket", "--range",
    "--json", "--max-filesize", "--limit-rate", "--speed-limit", "--speed-time",
    "--stderr", "--trace", "--trace-ascii", "--dump-header", "--output-dir",
    "--create-file-mode", "--aws-sigv4", "--oauth2-bearer", "--proto",
    "--proto-redir", "--tls-max", "--tlsuser", "--tlspassword", "--variable",
    "--expect100-timeout", "--happy-eyeballs-timeout-ms", "--keepalive-time",
    "--local-port", "--max-redirs", "--noproxy", "--pinnedpubkey", "--retry-delay",
    "--retry-max-time", "--socks5", "--socks4", "--socks4a", "--socks5-hostname",
    "--time-cond", "--tcp-fastopen", "--url-query", "--service-name", "--sasl-authzid",
})
_CURL_UPLOAD_LONG = frozenset({
    "--data", "--data-ascii", "--data-binary", "--data-raw", "--data-urlencode",
    "--form", "--form-string", "--upload-file", "--json", "--url-query",
})
_CURL_REDIRECTING = frozenset({"--proxy", "-x", "--resolve", "--dns-servers", "--unix-socket",
                               "--abstract-unix-socket", "--socks5", "--socks4", "--socks4a",
                               "--socks5-hostname", "--connect-to", "--interface", "-K", "--config",
                               "--aws-sigv4"})


def _curl_uploads(argv):
    """Whether this curl invocation sends a body, and whether it redirects its destination."""
    uploads, redirects = False, False
    i = 1
    while i < len(argv):
        tok = argv[i]
        base = tok.split("=", 1)[0]
        if base in _CURL_UPLOAD_LONG:
            uploads = True
        if base in _CURL_REDIRECTING:
            redirects = True
        if base in ("-X", "--request"):
            value = tok.split("=", 1)[1] if "=" in tok else (argv[i + 1] if i + 1 < len(argv) else "")
            if value.upper() not in ("GET", "HEAD"):
                uploads = True
        if tok.startswith("-") and not tok.startswith("--") and len(tok) > 1:
            for ch in tok[1:]:
                if ch in "dFTKX":
                    if ch == "X":
                        idx = tok.index("X") + 1
                        value = tok[idx:] or (argv[i + 1] if i + 1 < len(argv) else "")
                        if value.upper() not in ("GET", "HEAD"):
                            uploads = True
                    elif ch == "K":
                        redirects = True
                    else:
                        uploads = True
                    break
        i += 1
    return uploads, redirects


_WGET_UPLOAD = ("--post-data", "--post-file", "--body-data", "--body-file")
_WGET_VALUE_FLAGS = frozenset({
    "-O", "-o", "-a", "-P", "-U", "-T", "-t", "-w", "-Q", "-B", "-e", "-i", "-F",
    "--output-document", "--output-file", "--append-output", "--directory-prefix",
    "--user-agent", "--timeout", "--tries", "--wait", "--quota", "--base", "--execute",
    "--input-file", "--header", "--user", "--password", "--http-user", "--http-password",
    "--referer", "--method", "--limit-rate", "--bind-address", "--ca-certificate",
    "--certificate", "--private-key", "--load-cookies", "--save-cookies", "--level", "-l",
})


def _wget_uploads(argv):
    uploads, redirects = False, False
    for i, tok in enumerate(argv[1:], 1):
        base = tok.split("=", 1)[0]
        if base in _WGET_UPLOAD:
            uploads = True
        if base in ("-e", "--execute", "--bind-address"):
            redirects = True
        if base == "--method":
            value = tok.split("=", 1)[1] if "=" in tok else (argv[i + 1] if i + 1 < len(argv) else "")
            if value.upper() not in ("GET", "HEAD"):
                uploads = True
    return uploads, redirects


def _normalize_host(host):
    """Wrap the shared ``normalize_host`` so callers in this module can
    keep the leading underscore that signals "module-private"."""
    return normalize_host(host)


def _check_upload_tool(prog, argv, piped, ctx):
    uploads, redirects = (_curl_uploads if prog == "curl" else _wget_uploads)(argv)
    if piped and prog == "curl":
        uploads = True
    if redirects:
        raise _GuardError(f"{prog} is given a proxy/resolve/config option that changes where "
                          f"the request actually goes; the destination cannot be verified")
    if prog == "curl":
        urls = _url_positionals(argv, _CURL_VALUE_FLAGS, _CURL_VALUE_CLUSTER)
    else:
        urls = _url_positionals(argv, _WGET_VALUE_FLAGS, _WGET_VALUE_CLUSTER)
    if not urls:
        raise _GuardError(f"{prog} destination could not be determined from the command")
    for url in urls:
        if url.startswith("$") or url.startswith("@"):
            raise _GuardError(f"{prog} destination {url!r} is not a literal; cannot be verified")
        host = _extract_hosts_from_url(url)
        if host is None:
            host = _normalize_host(url.split("/", 1)[0])
        if host == "":
            continue
        if ctx.allowlist.is_lab_host(host):
            continue
        if uploads:
            raise _GuardError(f"{prog} would send data to {host!r}, which is not an allowlisted "
                              f"lab host (upload flag, mutating method, or piped stdin present)")
        if len(url) > MAX_URL_LENGTH:
            raise _GuardError(f"{prog} URL to {host!r} is {len(url)} characters long; a URL that "
                              f"long is a data channel, not a fetch")
    return f"{prog}:" + ",".join(urls)


_SSH_VALUE_FLAGS = frozenset({"-p", "-l", "-i", "-o", "-F", "-J", "-L", "-R", "-D", "-W",
                              "-b", "-c", "-e", "-m", "-O", "-Q", "-S", "-E", "-B", "-I", "-P"})


def _ssh_positionals(argv, value_flags):
    """Positional arguments and the option values that matter for routing."""
    positionals, options = [], []
    i = 1
    while i < len(argv):
        tok = argv[i]
        if tok in value_flags and i + 1 < len(argv):
            options.append((tok, argv[i + 1]))
            i += 2
            continue
        if tok.startswith("-") and len(tok) > 2 and tok[:2] in value_flags:
            options.append((tok[:2], tok[2:]))
        elif not tok.startswith("-"):
            positionals.append(tok)
        i += 1
    return positionals, options


def _require_lab(host, prog, ctx):
    if not host or host.startswith("$"):
        raise _GuardError(f"{prog} destination could not be determined ({host!r})")
    if not ctx.allowlist.is_lab_host(host):
        raise _GuardError(f"{prog} destination {_normalize_host(host)!r} is not an allowlisted "
                          f"lab host (see .claude/hooks/exfil-allowlist.txt)")


def _check_ssh_family(prog, argv, ctx):
    positionals, options = _ssh_positionals(argv, _SSH_VALUE_FLAGS)
    for flag, value in options:
        if flag == "-o" and re.match(r"(?i)^\s*(ProxyCommand|ProxyJump|LocalCommand|PermitLocalCommand)",
                                    value):
            raise _GuardError(f"{prog} -o {value!r} routes the session through another command; denied")
        if flag == "-J":
            for hop in value.split(","):
                _require_lab(hop, f"{prog} jump host", ctx)
        if flag == "-S" and prog == "scp":
            raise _GuardError("scp -S substitutes the transport program; denied")
        if flag in ("-e", "--rsh") and prog == "rsync" and re.search(r"-J|ProxyCommand|ProxyJump", value):
            raise _GuardError("rsync -e with a jump/proxy option; denied")
    hosts = []
    if prog == "ssh":
        if not positionals:
            raise _GuardError("ssh has no host argument")
        hosts.append(positionals[0])
    else:
        for tok in positionals:
            if "://" in tok:
                hosts.append(_extract_hosts_from_url(tok) or "")
            elif _REMOTE_SPEC.match(tok) and not re.match(r"^[A-Za-z]:[\\/]", tok):
                hosts.append(_REMOTE_SPEC.match(tok).group(1))
            elif prog == "sftp":
                hosts.append(tok)
        if prog == "sftp" and not hosts:
            raise _GuardError("sftp has no host argument")
    for host in hosts:
        _require_lab(host, prog, ctx)
    return f"{prog}:" + ",".join(_normalize_host(h) for h in hosts)


def _check_raw_socket_tool(prog, argv, ctx):
    args = argv[1:]
    if prog in ("nc", "ncat", "netcat") and any(a.startswith("-") and "l" in a[1:] for a in args):
        raise _GuardError(f"{prog} in listen mode exposes this machine to inbound connections; denied")
    if prog == "openssl":
        if "s_client" not in args:
            return "openssl:local"
        if "-connect" in args:
            _require_lab(args[args.index("-connect") + 1], "openssl s_client", ctx)
            return "openssl:" + args[args.index("-connect") + 1]
        raise _GuardError("openssl s_client without -connect; destination undetermined")
    hosts = []
    if prog == "socat":
        for a in args:
            if re.match(r"(?i)^(tcp|udp|sctp|openssl|ssl|socks4|socks4a|socks5|proxy)[46]?-listen:", a):
                raise _GuardError("socat listen address exposes this machine; denied")
            m = re.match(r"(?i)^(?:tcp|udp|sctp|openssl|ssl)[46]?:([^:,]+)", a)
            if m:
                hosts.append(m.group(1))
            elif re.match(r"(?i)^(exec|system|shell):", a):
                raise _GuardError("socat exec/system address runs an arbitrary command; denied")
            elif re.match(r"(?i)^(socks|proxy)", a):
                raise _GuardError("socat via a proxy; destination undetermined")
    else:
        for a in args:
            if a.startswith("-"):
                continue
            if "://" in a:
                hosts.append(_extract_hosts_from_url(a) or "")
            elif _HOSTISH.match(a) and not a.isdigit():
                hosts.append(a)
                if prog != "lftp":
                    break
    if not hosts:
        raise _GuardError(f"{prog} destination could not be determined")
    for host in hosts:
        _require_lab(host, prog, ctx)
    return f"{prog}:" + ",".join(_normalize_host(h) for h in hosts)


def _check_probe_tool(prog, argv, ctx):
    hosts = [a.lstrip("@") for a in argv[1:] if not a.startswith("-") and not a.isdigit()]
    if not hosts:
        raise _GuardError(f"{prog} has no host argument")
    for host in hosts:
        _require_lab(host, prog, ctx)
    return f"{prog}:" + ",".join(hosts)


# Backward-compatible alias: earlier callers raised ``_GuardError``
# because each module had its own stub. They now share ``GuardError``
# from ``exfil_bash_lex``; this alias keeps any code that named the
# local stub still working.
_GuardError = GuardError