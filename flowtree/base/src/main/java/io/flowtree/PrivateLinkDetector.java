/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects private Claude session data in text bound for a public surface.
 *
 * <p>This is the Java half of a defense-in-depth that keeps private Claude
 * session links out of public repositories. It guards the controller's GitHub
 * proxy ({@code GitHubProxyHandler}) — the choke point every GitHub write body
 * passes through — and the FlowTree harness commit and auto-PR egress, so a
 * link is refused even if the ar-manager tool layer is bypassed. The ar-manager
 * {@code github_*} tools carry the same guard in Python
 * ({@code tools/mcp/manager/private_link_detector.py}).</p>
 *
 * <p>Both engines read the same canonical pattern list and block message from
 * {@code /io/flowtree/private-link-patterns.json} on the classpath, so there is
 * one configurable source of truth. The patterns are written in the common
 * subset of {@link Pattern} and Python {@code re}.</p>
 *
 * <p>Detection {@linkplain #normalise(String) normalises} the text first
 * (Unicode NFKC, zero-width removal, URL and HTML-entity decoding) and scans
 * both the normalised form and a whitespace-collapsed variant, so an
 * obfuscated or line-wrapped link is caught the same as a plain one.
 * Everything {@link #redact(String) redacted} for logging hides the match —
 * the link is never reproduced.</p>
 *
 * @author Michael Murray
 */
public final class PrivateLinkDetector {

    /** Marker substituted for a matched link anywhere text is redacted for logging. */
    public static final String REDACTION = "<REDACTED-PRIVATE-LINK>";

    /** The number of characters of a file {@link #scanFileContent(Path)} holds at once. */
    static final int SCAN_WINDOW_CHARS = 1 << 20;

    /**
     * The characters of one window carried into the next by
     * {@link #scanFileContent(Path)}; far longer than any link, so a link that
     * straddles a window boundary is whole in the second window.
     */
    static final int SCAN_OVERLAP_CHARS = 4096;

    /** Classpath location of the canonical pattern list and block message. */
    private static final String RESOURCE = "/io/flowtree/private-link-patterns.json";

    /**
     * One compiled detection rule: the name it is reported as, the pattern,
     * whether it is also matched against the whitespace-collapsed variant, and
     * the pattern {@link #redact(String)} replaces.
     *
     * <p>A collapse-scanned rule describes a URL, and its pattern may match only
     * a prefix of that URL (the host and application segment). Its redaction
     * pattern therefore extends the match to the end of the non-whitespace run,
     * so the session or share suffix that follows is redacted with it rather
     * than left in the output.</p>
     *
     * @param name      the pattern name reported by {@link #scan(String)}
     * @param pattern   the compiled regular expression
     * @param collapse  whether to also match against the whitespace-collapsed text
     * @param redaction the pattern whose matches {@link #redact(String)} replaces
     */
    private record Rule(String name, Pattern pattern, boolean collapse, Pattern redaction) {

        /**
         * Compiles a rule from its specification.
         *
         * @param name     the pattern name
         * @param regex    the regular expression source
         * @param collapse whether the rule describes a URL that is also
         *                 matched against the whitespace-collapsed text
         * @return the compiled rule
         */
        static Rule compile(String name, String regex, boolean collapse) {
            Pattern pattern = Pattern.compile(regex);
            return new Rule(name, pattern, collapse,
                    collapse ? Pattern.compile(regex + "\\S*") : pattern);
        }
    }

    /** The compiled detection rules, in declaration order. */
    private static final List<Rule> PATTERNS = new ArrayList<>();

    /** The owner's block message, loaded from the resource. */
    private static final String BLOCK_MESSAGE;

    /**
     * Patterns that describe a commit-message trailer or attribution line rather
     * than a link embedded in content. {@link #scanFileContent(String)} omits
     * them: source, docs, and this detector's own pattern resource legitimately
     * quote the words "Co-Authored-By" or "Claude-Session" while discussing the
     * policy, and flagging a staged file for that would be a false positive. An
     * actual leaked link, session id, or Anthropic identity in a file is still
     * caught.
     */
    private static final Set<String> MESSAGE_ONLY_PATTERNS =
            Set.of("claude_session_trailer", "claude_attribution_credit");

    /**
     * Patterns for an actual private session link or id — the content that must
     * never be committed and that an attribution sanitiser cannot gracefully
     * remove. {@link #scanLinks(String)} restricts to these so a commit-message
     * check does not pre-empt {@code AuthorAttribution}, which removes ordinary
     * attribution trailers rather than failing the job over them.
     */
    private static final Set<String> LINK_PATTERNS = Set.of(
            "claude_ai_session_or_app_url", "claude_code_session_path", "bare_session_id");

    /** Zero-width characters stripped during normalisation. */
    private static final Pattern ZERO_WIDTH =
            Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");

    /** A URL percent-escape, used to decide whether percent-decoding is needed. */
    private static final Pattern PERCENT = Pattern.compile("%[0-9A-Fa-f]{2}");

    /** Runs of whitespace, removed to build the line-wrap-resistant scan variant. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** A numeric or named HTML entity decoded during normalisation. */
    private static final Pattern HTML_ENTITY =
            Pattern.compile("&(#x?[0-9A-Fa-f]+|[a-zA-Z]+);");

    /**
     * A JSON string escape for a solidus ({@code \/}) or a {@code \\uXXXX}
     * code-point escape. GitHub decodes both when it stores a PR/issue body, so
     * a proxy caller can hide a link in the serialised JSON it forwards by
     * escaping the slashes of its path or a letter of its host; decoding them
     * during normalisation means the scan sees what GitHub will. (No intact
     * example appears here: this file is itself scanned when it is staged.)
     */
    private static final Pattern JSON_ESCAPE =
            Pattern.compile("\\\\u([0-9A-Fa-f]{4})|\\\\(/)");

    /** The named HTML entities relevant to URLs, mapped to their characters. */
    private static final Map<String, String> NAMED_ENTITIES = new LinkedHashMap<>();

    static {
        NAMED_ENTITIES.put("amp", "&");
        NAMED_ENTITIES.put("lt", "<");
        NAMED_ENTITIES.put("gt", ">");
        NAMED_ENTITIES.put("quot", "\"");
        NAMED_ENTITIES.put("apos", "'");
        NAMED_ENTITIES.put("sol", "/");
        NAMED_ENTITIES.put("colon", ":");
        NAMED_ENTITIES.put("lpar", "(");
        NAMED_ENTITIES.put("rpar", ")");
        NAMED_ENTITIES.put("nbsp", " ");

        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = PrivateLinkDetector.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "Missing private-link pattern resource " + RESOURCE);
            }
            JsonNode root = mapper.readTree(in);
            for (JsonNode entry : root.get("patterns")) {
                PATTERNS.add(Rule.compile(entry.get("name").asText(),
                        entry.get("regex").asText(),
                        entry.path("collapse").asBoolean(false)));
            }
            BLOCK_MESSAGE = root.get("block_message").asText();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Could not load private-link pattern resource " + RESOURCE, e);
        }
        if (PATTERNS.isEmpty() || BLOCK_MESSAGE.isEmpty()) {
            throw new IllegalStateException("Empty private-link pattern specification");
        }
    }

    /** Not instantiable; all behaviour is static. */
    private PrivateLinkDetector() {
    }

    /**
     * Returns the owner's block message explaining why a private-link write was
     * refused and what the caller must do about it.
     *
     * @return the block message; never {@code null}
     */
    public static String blockMessage() {
        return BLOCK_MESSAGE;
    }

    /**
     * Returns whether {@code text} carries any private Claude session data.
     *
     * @param text the text to scan, possibly {@code null}
     * @return {@code true} when at least one pattern matches
     */
    public static boolean containsPrivateLink(String text) {
        return !scan(text).isEmpty();
    }

    /**
     * Returns the names of every pattern that matches {@code text}, deduplicated
     * and in declaration order.
     *
     * <p>The text is normalised first, and both the normalised form and a
     * whitespace-collapsed variant are scanned, so a link wrapped across lines
     * or broken up by markup is caught.</p>
     *
     * @param text the text to scan, possibly {@code null}
     * @return the matched pattern names; empty, never {@code null}, when none match
     */
    public static List<String> scan(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }
        String normalised = normalise(text);
        String collapsed = WHITESPACE.matcher(normalised).replaceAll("");
        for (Rule rule : PATTERNS) {
            boolean matched = rule.pattern().matcher(normalised).find()
                    || (rule.collapse() && rule.pattern().matcher(collapsed).find());
            if (matched && !hits.contains(rule.name())) {
                hits.add(rule.name());
            }
        }
        return hits;
    }

    /**
     * Returns the matched pattern names in {@code text} that indicate a link,
     * session id, or Anthropic identity embedded in content, excluding the
     * {@linkplain #MESSAGE_ONLY_PATTERNS message-only} trailer and credit
     * patterns. Use this when scanning the contents of a staged file, where
     * quoting an attribution word is not itself a leak but an actual link is.
     *
     * @param text the file content to scan, possibly {@code null}
     * @return the matched content pattern names; empty, never {@code null}
     */
    public static List<String> scanFileContent(String text) {
        List<String> hits = scan(text);
        hits.removeIf(MESSAGE_ONLY_PATTERNS::contains);
        return hits;
    }

    /**
     * Returns the {@linkplain #scanFileContent(String) content patterns} that
     * match the file at {@code path}, read in bounded windows so a large file is
     * never loaded into memory whole.
     *
     * <p>A link is ASCII, so the content is decoded strictly as UTF-8 and, only
     * when that fails, leniently as ISO-8859-1 — a file that is not valid UTF-8
     * is still scanned rather than slipping through. Each window of
     * {@link #SCAN_WINDOW_CHARS} characters is scanned with the last
     * {@link #SCAN_OVERLAP_CHARS} characters of the previous one prepended, so a
     * link that straddles a window boundary is still seen.</p>
     *
     * @param path the file to scan
     * @return the matched content pattern names, deduplicated; empty when clean
     * @throws IOException if the file cannot be read
     */
    public static List<String> scanFileContent(Path path) throws IOException {
        try {
            return scanFileContent(path, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT));
        } catch (CharacterCodingException notUtf8) {
            return scanFileContent(path, StandardCharsets.ISO_8859_1.newDecoder());
        }
    }

    /**
     * Scans the file at {@code path}, decoded with {@code decoder}, window by
     * window as described in {@link #scanFileContent(Path)}.
     *
     * @param path    the file to scan
     * @param decoder the decoder for the file's bytes
     * @return the matched content pattern names, deduplicated
     * @throws IOException if the file cannot be read or, for a strict decoder,
     *                     is not validly encoded
     */
    private static List<String> scanFileContent(Path path, CharsetDecoder decoder)
            throws IOException {
        List<String> hits = new ArrayList<>();
        char[] buffer = new char[SCAN_WINDOW_CHARS];
        String carry = "";
        try (Reader reader = new InputStreamReader(Files.newInputStream(path), decoder)) {
            int filled;
            while ((filled = fill(reader, buffer)) > 0) {
                String window = carry + new String(buffer, 0, filled);
                for (String hit : scanFileContent(window)) {
                    if (!hits.contains(hit)) {
                        hits.add(hit);
                    }
                }
                carry = window.substring(Math.max(0, window.length() - SCAN_OVERLAP_CHARS));
            }
        }
        return hits;
    }

    /**
     * Reads from {@code reader} until {@code buffer} is full or the stream ends.
     *
     * @param reader the reader to drain
     * @param buffer the buffer to fill
     * @return the number of characters read; {@code 0} at end of stream
     * @throws IOException if reading fails
     */
    private static int fill(Reader reader, char[] buffer) throws IOException {
        int filled = 0;
        while (filled < buffer.length) {
            int read = reader.read(buffer, filled, buffer.length - filled);
            if (read < 0) {
                break;
            }
            filled += read;
        }
        return filled;
    }

    /**
     * Returns the matched pattern names in {@code text} that indicate an actual
     * private session link or id, restricted to {@link #LINK_PATTERNS}. Use this
     * where ordinary attribution trailers are handled elsewhere (a commit
     * message, which {@code AuthorAttribution} sanitises) and only a hard,
     * un-removable session leak should fail the operation.
     *
     * @param text the text to scan, possibly {@code null}
     * @return the matched link pattern names; empty, never {@code null}
     */
    public static List<String> scanLinks(String text) {
        List<String> hits = scan(text);
        hits.removeIf(name -> !LINK_PATTERNS.contains(name));
        return hits;
    }

    /**
     * Returns {@code text} with every private-link match replaced by
     * {@link #REDACTION}. The returned value is safe to log: the matched link,
     * session id, or trailer is never reproduced. A URL match is redacted
     * through to the end of its non-whitespace run, so the session or share
     * suffix after the matched prefix goes with it. A link obfuscated by
     * whitespace — detected only on the collapsed variant and not removable by
     * a per-line substitution — causes the whole string to be redacted, so a
     * fragment can never survive.
     *
     * @param text the text to redact, possibly {@code null}
     * @return the redacted text; {@code ""} when {@code text} is {@code null}
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String redacted = normalise(text);
        for (Rule rule : PATTERNS) {
            redacted = rule.redaction().matcher(redacted).replaceAll(REDACTION);
        }
        // A whitespace-split URL survives per-line substitution but is still
        // detected on the collapsed form; if any collapse-scanned pattern still
        // matches there, redact wholesale (see redact's javadoc).
        String collapsed = WHITESPACE.matcher(redacted).replaceAll("");
        for (Rule rule : PATTERNS) {
            if (rule.collapse() && rule.pattern().matcher(collapsed).find()) {
                return REDACTION;
            }
        }
        return redacted;
    }

    /**
     * Canonicalises {@code text} so an obfuscated form matches the same as a
     * plain one: Unicode NFKC folding, zero-width removal, and URL
     * percent-decoding plus HTML-entity and JSON-escape decoding applied up to
     * twice to defeat double-encoding. Zero-width characters are re-stripped
     * after each decode pass, because a decode can reveal a freshly encoded
     * zero-width character that would otherwise keep a link split.
     *
     * @param text the text to normalise
     * @return the normalised text
     */
    static String normalise(String text) {
        String result = ZERO_WIDTH.matcher(
                Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll("");
        for (int i = 0; i < 2; i++) {
            String decoded = ZERO_WIDTH.matcher(
                    decodeJsonEscapes(decodeHtmlEntities(percentDecode(result)))).replaceAll("");
            if (decoded.equals(result)) {
                break;
            }
            result = decoded;
        }
        return result;
    }

    /**
     * Decodes the {@code \\uXXXX} and {@code \/} JSON string escapes the way
     * GitHub does when it stores a body, so a link escaped in the serialised
     * JSON a proxy caller forwards is scanned as the text GitHub will render.
     * Other backslash escapes are left untouched.
     *
     * @param text the possibly JSON-escaped text
     * @return the decoded text
     */
    private static String decodeJsonEscapes(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        Matcher m = JSON_ESCAPE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(1) != null
                    ? codePoint(Integer.parseInt(m.group(1), 16)) : "/";
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    replacement != null ? replacement : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Decodes {@code %XX} percent-escapes to their bytes, interpreting the
     * assembled byte sequence as UTF-8. Characters that are not part of a valid
     * escape are passed through unchanged; a literal {@code +} is left alone
     * (it is not a space in a session path).
     *
     * @param text the possibly percent-encoded text
     * @return the decoded text
     */
    private static String percentDecode(String text) {
        if (text.indexOf('%') < 0 || !PERCENT.matcher(text).find()) {
            return text;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '%' && i + 2 < text.length()
                    && isHex(text.charAt(i + 1)) && isHex(text.charAt(i + 2))) {
                out.write(Integer.parseInt(text.substring(i + 1, i + 3), 16));
                i += 3;
            } else {
                for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    out.write(b);
                }
                i++;
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /**
     * Decodes decimal and hexadecimal numeric HTML entities and a fixed set of
     * named ones relevant to URLs. Unknown entities are passed through.
     *
     * @param text the possibly entity-encoded text
     * @return the decoded text
     */
    private static String decodeHtmlEntities(String text) {
        if (text.indexOf('&') < 0) {
            return text;
        }
        Matcher m = HTML_ENTITY.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String body = m.group(1);
            String replacement = null;
            try {
                if (body.startsWith("#x") || body.startsWith("#X")) {
                    replacement = codePoint(Integer.parseInt(body.substring(2), 16));
                } else if (body.startsWith("#")) {
                    replacement = codePoint(Integer.parseInt(body.substring(1)));
                } else {
                    replacement = NAMED_ENTITIES.get(body.toLowerCase(Locale.ROOT));
                }
            } catch (NumberFormatException ignored) {
                replacement = null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    replacement != null ? replacement : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Returns the string for a Unicode code point, or {@code null} when it is
     * not a valid code point.
     *
     * @param value the code point value
     * @return the single-character string, or {@code null}
     */
    private static String codePoint(int value) {
        if (value < 0 || value > Character.MAX_CODE_POINT) {
            return null;
        }
        return new String(Character.toChars(value));
    }

    /**
     * Returns whether {@code c} is a hexadecimal digit.
     *
     * @param c the character to test
     * @return {@code true} when {@code c} is {@code 0-9}, {@code a-f}, or {@code A-F}
     */
    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
