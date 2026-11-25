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

package io.flowtree.node;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.flowtree.JsonFieldExtractor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A peer node's self-reported routing status: the capability {@link Node#getLabels() labels}
 * it advertises and whether it is currently executing a job.
 *
 * <p>A worker reports this alongside its activity and job-time figures in the
 * {@link io.flowtree.msg.Message#ServerStatus} payload, so the controller can tell a
 * submitter where a job with given {@code requiredLabels} would land and why a job might be
 * waiting. The worker never knew its labels were visible to the controller before this report
 * existed; the controller stores the latest report on the {@link io.flowtree.msg.NodeProxy}
 * for the peer and renders it from {@code GET /api/agents}.</p>
 *
 * <p>Both fields are tri-state to stay backward compatible with a node running an older build
 * that reports neither: {@link #busy()} is {@code null} and {@link #labels()} is {@code null}
 * when the field was absent from the payload, which renders as JSON {@code null} ("unknown")
 * rather than as {@code false} ("idle") or {@code {}} ("no labels"). A node that reports an
 * empty label set is distinguished from one that reported nothing: the former carries an empty,
 * non-null map.</p>
 *
 * @author  Michael Murray
 * @see AutomaticLabel
 * @see io.flowtree.msg.NodeProxy#getStatus()
 */
public final class NodeStatusReport {

	/** Status-payload key carrying the busy flag. */
	public static final String BUSY_KEY = "busy";

	/** Status-payload key carrying the encoded label set. */
	public static final String LABELS_KEY = "labels";

	/** Separator between {@code key:value} status fields within the payload. */
	private static final String FIELD_SEPARATOR = ";";

	/** Separator between a status field's key and its value. */
	private static final String VALUE_SEPARATOR = ":";

	/** Separator between {@code key=value} label pairs within the labels field. */
	private static final String LABEL_SEPARATOR = ",";

	/** Separator between a label's key and its value. */
	private static final String LABEL_ASSIGNMENT = "=";

	/**
	 * The structural delimiter characters of the status payload and the labels field. A label
	 * key or value that contains any of them cannot be represented unambiguously in this flat
	 * wire format (it would split into extra pairs or forge a status field), so a label set
	 * containing one is reported as unknown rather than emitted and corrupting the payload.
	 */
	private static final String RESERVED = FIELD_SEPARATOR + VALUE_SEPARATOR
			+ LABEL_SEPARATOR + LABEL_ASSIGNMENT;

	/** Whether the peer is executing a job; {@code null} when the peer did not report. */
	private final Boolean busy;

	/** The peer's labels; {@code null} when the peer did not report, possibly empty otherwise. */
	private final Map<String, String> labels;

	/**
	 * Constructs a report.
	 *
	 * @param busy    {@code true}/{@code false} when the busy state is known, or {@code null}
	 *                when it is unknown.
	 * @param labels  the reported labels, or {@code null} when they are unknown. A non-null map
	 *                is copied defensively and may be empty to mean "known to have no labels".
	 */
	public NodeStatusReport(Boolean busy, Map<String, String> labels) {
		this.busy = busy;
		this.labels = labels == null
				? null
				: Collections.unmodifiableMap(new LinkedHashMap<>(labels));
	}

	/**
	 * Returns a report in which neither field is known, used as the initial status of a peer
	 * before it has reported and as the status of a peer running a build that reports neither.
	 *
	 * @return  an all-unknown report.
	 */
	public static NodeStatusReport unknown() {
		return new NodeStatusReport(null, null);
	}

	/**
	 * Returns whether the peer is executing a job, or {@code null} when that is unknown.
	 *
	 * @return  the busy flag, or {@code null}.
	 */
	public Boolean busy() { return busy; }

	/**
	 * Returns the peer's labels, or {@code null} when they are unknown.
	 *
	 * @return  an unmodifiable label map (possibly empty), or {@code null}.
	 */
	public Map<String, String> labels() { return labels; }

	/**
	 * Encodes this report's busy and label fields for appending to a
	 * {@link io.flowtree.msg.Message#ServerStatus} payload. The returned text is the busy field,
	 * followed by the labels field when it can be written (see below), joined by
	 * {@value #FIELD_SEPARATOR}, with no leading separator,
	 * so the caller joins it to the rest of the payload with one. A node builds a report of its
	 * own state ({@code new NodeStatusReport(group.isWorking(), group.getLabels())}) to report
	 * it; the busy field is written as {@code false} when {@link #busy()} is {@code null},
	 * because a node reporting its own state always knows it.
	 *
	 * <p>The labels field is written only when the complete label set can be represented (see
	 * {@link #encodeLabels}). When {@link #labels()} is {@code null}, or any label carries a
	 * {@link #RESERVED structural delimiter}, the field is left out entirely, so the receiver
	 * reads the labels as unknown rather than as an authoritative empty or partial set.</p>
	 *
	 * @return  the encoded fields, e.g. {@code "busy:false;labels:platform=linux,hostname=halo"},
	 *          or just {@code "busy:false"} when the labels cannot be reported faithfully.
	 */
	public String encode() {
		String fields = BUSY_KEY + VALUE_SEPARATOR + Boolean.TRUE.equals(busy);
		String encodedLabels = encodeLabels(labels);
		if (encodedLabels == null) return fields;
		return fields + FIELD_SEPARATOR + LABELS_KEY + VALUE_SEPARATOR + encodedLabels;
	}

	/**
	 * Encodes a label map as {@code key=value} pairs separated by {@value #LABEL_SEPARATOR}. This
	 * flat format cannot carry a label whose key or value contains a {@link #RESERVED structural
	 * delimiter}, and silently dropping such a label would present the remaining labels as the
	 * node's complete capability set; so when any label is unrepresentable the whole set is
	 * reported as unknown instead.
	 *
	 * @param labels  the labels to encode; may be {@code null} or empty.
	 * @return  the encoded labels (the empty string for an empty set), or {@code null} when the
	 *          labels are unknown or cannot all be encoded.
	 */
	private static String encodeLabels(Map<String, String> labels) {
		if (labels == null) return null;

		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, String> entry : labels.entrySet()) {
			if (!isEncodable(entry.getKey()) || !isEncodable(entry.getValue())) return null;
			if (out.length() > 0) out.append(LABEL_SEPARATOR);
			out.append(entry.getKey()).append(LABEL_ASSIGNMENT).append(entry.getValue());
		}
		return out.toString();
	}

	/**
	 * Returns whether a label component (a key or a value) can be encoded without corrupting the
	 * payload — that is, it contains none of the {@link #RESERVED structural delimiters}.
	 *
	 * @param component  a label key or value; may be {@code null}.
	 * @return  {@code true} when the component carries no reserved character.
	 */
	private static boolean isEncodable(String component) {
		if (component == null) return false;
		for (int i = 0; i < RESERVED.length(); i++) {
			if (component.indexOf(RESERVED.charAt(i)) >= 0) return false;
		}
		return true;
	}

	/**
	 * Decodes a labels field value into a map. A blank value yields an empty map rather than
	 * {@code null}, since the field was present and simply carried no labels. Whitespace within a
	 * key or value is preserved: {@link #encodeLabels} writes no padding around the {@value
	 * #LABEL_ASSIGNMENT} and {@value #LABEL_SEPARATOR} separators, so any surrounding whitespace is
	 * part of the label the peer actually routes on, and trimming it would report a capability that
	 * differs from the one in use. A malformed pair — one without a non-empty key ahead of its
	 * {@value #LABEL_ASSIGNMENT} — makes the whole field unreliable, so the labels decode to
	 * {@code null} ("unknown") rather than to a partial or empty set that would read as
	 * authoritative; this mirrors the strict {@link #parseBusy} contract. Empty pairs, including a
	 * trailing one left by a dangling {@value #LABEL_SEPARATOR}, are malformed too.
	 *
	 * @param value  the encoded labels, e.g. {@code "platform=linux,hostname=halo"}.
	 * @return  the decoded labels; an empty map when the field was blank, or {@code null} when a
	 *          pair was malformed.
	 */
	private static Map<String, String> decodeLabels(String value) {
		Map<String, String> labels = new LinkedHashMap<>();
		if (value == null || value.isEmpty()) return labels;

		for (String pair : value.split(LABEL_SEPARATOR, -1)) {
			int eq = pair.indexOf(LABEL_ASSIGNMENT);
			if (eq <= 0) return null;

			String key = pair.substring(0, eq);
			String val = pair.substring(eq + LABEL_ASSIGNMENT.length());
			labels.put(key, val);
		}
		return labels;
	}

	/**
	 * Returns whether the given status-payload field is one this report owns (the busy or
	 * labels field), so the message handler can leave it for {@link #parse(String)} rather than
	 * rejecting it as unrecognised.
	 *
	 * @param field  a single {@code key:value} field from a status payload.
	 * @return  {@code true} if the field is a busy or labels field.
	 */
	public static boolean isReportField(String field) {
		return field != null
				&& (field.startsWith(BUSY_KEY + VALUE_SEPARATOR)
						|| field.startsWith(LABELS_KEY + VALUE_SEPARATOR));
	}

	/**
	 * Parses the busy and label fields out of a full status payload.
	 *
	 * @param payload  the {@value #FIELD_SEPARATOR}-delimited status payload.
	 * @return  a report carrying whichever of the two fields were present, or {@code null} when
	 *          neither was — so a caller leaves a peer's existing status untouched rather than
	 *          overwriting it with an all-unknown report.
	 */
	public static NodeStatusReport parse(String payload) {
		if (payload == null) return null;

		Boolean busy = null;
		Map<String, String> labels = null;
		boolean present = false;

		for (String field : payload.split(FIELD_SEPARATOR)) {
			int sep = field.indexOf(VALUE_SEPARATOR);
			if (sep < 0) continue;

			String key = field.substring(0, sep);
			String value = field.substring(sep + VALUE_SEPARATOR.length());

			if (BUSY_KEY.equals(key)) {
				busy = parseBusy(value);
				present = true;
			} else if (LABELS_KEY.equals(key)) {
				labels = decodeLabels(value);
				present = true;
			}
		}

		return present ? new NodeStatusReport(busy, labels) : null;
	}

	/**
	 * Decodes a {@code busy} field value into the tri-state busy flag. Only the exact tokens
	 * {@code true} and {@code false} that {@link #encode()} emits are accepted; any other value —
	 * a malformed report from a misbehaving or incompatible peer — decodes to {@code null}
	 * ("unknown") rather than to {@code false} ("idle"), so a garbled field never makes a peer
	 * appear idle and mislead a submitter about why a matching job is waiting.
	 *
	 * @param value  the raw {@code busy} field value.
	 * @return  {@link Boolean#TRUE} or {@link Boolean#FALSE} for a recognised token, else
	 *          {@code null}.
	 */
	private static Boolean parseBusy(String value) {
		if (Boolean.TRUE.toString().equals(value)) return Boolean.TRUE;
		if (Boolean.FALSE.toString().equals(value)) return Boolean.FALSE;
		return null;
	}

	/**
	 * Renders this report's own fields — {@code busy} and {@code labels} — as a JSON object.
	 * The owning {@link io.flowtree.msg.NodeProxy#toJson() node} adds its {@code address} and
	 * merges these in, so a report renders only the state it owns and never a field (such as the
	 * network address) that belongs to the node it describes.
	 *
	 * @return  an object with {@code busy} (boolean, or {@code null} when unknown) and
	 *          {@code labels} (an object, or {@code null} when unknown).
	 */
	public ObjectNode toJson() {
		ObjectNode json = JsonFieldExtractor.MAPPER.createObjectNode();

		if (busy == null) {
			json.putNull(BUSY_KEY);
		} else {
			json.put(BUSY_KEY, busy.booleanValue());
		}

		if (labels == null) {
			json.putNull(LABELS_KEY);
		} else {
			ObjectNode labelsJson = json.putObject(LABELS_KEY);
			for (Map.Entry<String, String> entry : labels.entrySet()) {
				labelsJson.put(entry.getKey(), entry.getValue());
			}
		}

		return json;
	}
}
