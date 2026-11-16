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

package io.flowtree.job;

import java.util.regex.Pattern;

/**
 * The parsed view of a serialized job payload, owning the format-marker grammar
 * that lets a receiver pick the right {@link JobCodec} from the payload alone.
 *
 * <h2>Grammar</h2>
 * A payload is either <em>unmarked</em> or carries a leading format marker:
 * <pre>
 *   &#64;wire=&lt;formatId&gt;::&lt;fully.qualified.ClassName&gt;::&lt;payload&gt;
 * </pre>
 * The leading token {@code @wire=} followed by the format id can never collide
 * with a Java binary class name, because {@code @} and {@code =} cannot appear
 * in one. A
 * format id matches {@code [a-z0-9-]+} and never contains {@link JobFactory#ENTRY_SEPARATOR}
 * or {@link JobFactory#KEY_VALUE_SEPARATOR}.
 *
 * <p>The <em>string</em> format is never marked. An unmarked payload therefore
 * parses as format id {@link StringJobCodec#FORMAT_ID}, with its class name
 * taken from the first {@link JobFactory#ENTRY_SEPARATOR}-delimited token, so
 * every payload, persisted blob and peer written before this abstraction stays
 * readable without change.</p>
 *
 * <h2>Payload</h2>
 * {@link #getPayload()} returns the codec-specific body:
 * <ul>
 *   <li>For a <em>marked</em> format it is the text after the second
 *       {@link JobFactory#ENTRY_SEPARATOR} — for example the Base64 of a
 *       serialized protobuf message.</li>
 *   <li>For the <em>unmarked</em> string format it is the whole original
 *       string, beginning with the class name, because that is the native form
 *       the {@link StringJobCodec} decode loop consumes.</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see JobCodec
 * @see StringJobCodec
 */
public final class WireEnvelope {

    /** The leading token of a marked payload. */
    public static final String MARKER_PREFIX = "@wire=";

    /** The grammar every format id must match; it can never contain {@link JobFactory#ENTRY_SEPARATOR}. */
    private static final Pattern FORMAT_ID_PATTERN = Pattern.compile("[a-z0-9-]+");

    /** The codec format id; {@link StringJobCodec#FORMAT_ID} for an unmarked payload. */
    private final String formatId;

    /** The fully-qualified class the receiver must instantiate, or {@code null}. */
    private final String className;

    /** The codec-specific payload body. */
    private final String payload;

    /** Whether the parsed payload carried a format marker. */
    private final boolean marked;

    /**
     * Creates an envelope with an explicit format id, class name and payload.
     * Used by {@link JobFactory#toWire()} and {@link Job#toWire()} to render a
     * marked payload.
     *
     * @param formatId  the codec format id; must match {@code [a-z0-9-]+}
     * @param className the fully-qualified class the receiver must instantiate
     * @param payload   the codec-specific body
     * @throws IllegalArgumentException if {@code formatId} does not match {@code [a-z0-9-]+}
     */
    public WireEnvelope(String formatId, String className, String payload) {
        this(requireValidFormatId(formatId), className, payload, !StringJobCodec.FORMAT_ID.equals(formatId));
    }

    /**
     * Canonical constructor setting every field, including the pre-computed
     * {@code marked} flag.
     *
     * @param formatId  the codec format id
     * @param className the class name, or {@code null}
     * @param payload   the codec-specific body
     * @param marked    whether the payload carried a format marker
     */
    private WireEnvelope(String formatId, String className, String payload, boolean marked) {
        this.formatId = formatId;
        this.className = className;
        this.payload = payload;
        this.marked = marked;
    }

    /**
     * Parses a serialized payload into its format id, class name and body.
     *
     * <p>An unmarked payload is the string format: its class name is the first
     * {@link JobFactory#ENTRY_SEPARATOR}-delimited token (or {@code null} when
     * the payload carries no separator), and its payload is the whole string.
     * A payload beginning with {@link #MARKER_PREFIX} is parsed as a marked
     * format; a marker missing either {@link JobFactory#ENTRY_SEPARATOR}, whose
     * format id does not match {@code [a-z0-9-]+}, or whose format id is the
     * {@link StringJobCodec#FORMAT_ID string format} (which is never marked, so
     * a marked payload claiming it would have its class name stripped and the
     * remainder misparsed), is rejected.</p>
     *
     * @param data the serialized payload; must not be {@code null}
     * @return the parsed envelope
     * @throws IllegalArgumentException if {@code data} is {@code null} or carries
     *                                  a structurally malformed format marker
     */
    public static WireEnvelope parse(String data) {
        if (data == null) {
            throw new IllegalArgumentException("wire payload is null");
        }

        if (!data.startsWith(MARKER_PREFIX)) {
            int sep = data.indexOf(JobFactory.ENTRY_SEPARATOR);
            String className = sep < 0 ? null : data.substring(0, sep);
            return new WireEnvelope(StringJobCodec.FORMAT_ID, className, data, false);
        }

        int firstSep = data.indexOf(JobFactory.ENTRY_SEPARATOR);
        if (firstSep < 0) {
            throw new IllegalArgumentException("malformed wire marker, missing class separator: " + data);
        }
        String formatId = data.substring(MARKER_PREFIX.length(), firstSep);
        if (!isValidFormatId(formatId)) {
            throw new IllegalArgumentException("malformed wire marker, invalid format id '"
                    + formatId + "': " + data);
        }
        if (StringJobCodec.FORMAT_ID.equals(formatId)) {
            // The string format is never marked, so a marked one is malformed.
            throw new IllegalArgumentException("malformed wire marker, the string format is never marked: " + data);
        }

        String rest = data.substring(firstSep + JobFactory.ENTRY_SEPARATOR.length());
        int secondSep = rest.indexOf(JobFactory.ENTRY_SEPARATOR);
        if (secondSep < 0) {
            throw new IllegalArgumentException("malformed wire marker, missing payload separator: " + data);
        }
        String className = rest.substring(0, secondSep);
        String payload = rest.substring(secondSep + JobFactory.ENTRY_SEPARATOR.length());
        return new WireEnvelope(formatId, className, payload, true);
    }

    /**
     * Returns whether the given value is a well-formed format id: a non-empty
     * string matching {@code [a-z0-9-]+}. Such an id can never contain
     * {@link JobFactory#ENTRY_SEPARATOR}, so a rendered marker always parses
     * back to the same format id, class name and payload.
     *
     * @param formatId the candidate format id, possibly {@code null}
     * @return {@code true} if {@code formatId} is well formed
     */
    public static boolean isValidFormatId(String formatId) {
        return formatId != null && FORMAT_ID_PATTERN.matcher(formatId).matches();
    }

    /**
     * Returns the given format id unchanged if it is
     * {@link #isValidFormatId(String) well formed}, and rejects it otherwise.
     *
     * @param formatId the candidate format id
     * @return {@code formatId}
     * @throws IllegalArgumentException if {@code formatId} is not well formed
     */
    public static String requireValidFormatId(String formatId) {
        if (!isValidFormatId(formatId)) {
            throw new IllegalArgumentException("invalid wire format id '" + formatId
                    + "'; a format id must match [a-z0-9-]+");
        }
        return formatId;
    }

    /**
     * Renders this envelope back to its serialized form. An unmarked (string)
     * envelope renders as its payload unchanged, keeping the legacy format
     * byte-identical; a marked envelope renders as
     * {@code @wire=<formatId>::<className>::<payload>}.
     *
     * @return the serialized payload
     */
    public String render() {
        if (!marked) {
            return payload;
        }
        return MARKER_PREFIX + formatId + JobFactory.ENTRY_SEPARATOR
                + className + JobFactory.ENTRY_SEPARATOR + payload;
    }

    /**
     * Returns the format id, used to select a {@link JobCodec} from the
     * {@link JobCodecRegistry}.
     *
     * @return the format id; {@link StringJobCodec#FORMAT_ID} for an unmarked payload
     */
    public String getFormatId() {
        return formatId;
    }

    /**
     * Returns the fully-qualified name of the class the receiver must
     * instantiate, or {@code null} when an unmarked payload carries no
     * {@link JobFactory#ENTRY_SEPARATOR}.
     *
     * @return the class name, or {@code null}
     */
    public String getClassName() {
        return className;
    }

    /**
     * Returns the codec-specific payload body (see the class javadoc).
     *
     * @return the payload body
     */
    public String getPayload() {
        return payload;
    }

    /**
     * Returns whether this payload carried a format marker. An unmarked payload
     * is the string format.
     *
     * @return {@code true} if the payload was marked; {@code false} otherwise
     */
    public boolean isMarked() {
        return marked;
    }
}
