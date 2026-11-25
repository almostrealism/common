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
	 * {@link io.flowtree.msg.Message#ServerStatus} payload. The returned text is the two
	 * {@code key:value} fields joined by {@value #FIELD_SEPARATOR}, with no leading separator,
	 * so the caller joins it to the rest of the payload with one. A node builds a report of its
	 * own state ({@code new NodeStatusReport(group.isWorking(), group.getLabels())}) to report
	 * it; the busy field is written as {@code false} when {@link #busy()} is {@code null},
	 * because a node reporting its own state always knows it.
	 *
	 * @return  the encoded fields, e.g. {@code "busy:false;labels:platform=linux,hostname=halo"}.
	 */
	public String encode() {
		return BUSY_KEY + VALUE_SEPARATOR + Boolean.TRUE.equals(busy)
				+ FIELD_SEPARATOR + LABELS_KEY + VALUE_SEPARATOR + encodeLabels(labels);
	}

	/**
	 * Encodes a label map as {@code key=value} pairs separated by {@value #LABEL_SEPARATOR}.
	 *
	 * @param labels  the labels to encode; may be {@code null} or empty.
	 * @return  the encoded labels, or the empty string when there are none.
	 */
	private static String encodeLabels(Map<String, String> labels) {
		if (labels == null || labels.isEmpty()) return "";

		StringBuilder out = new StringBuilder();
		for (Map.Entry<String, String> entry : labels.entrySet()) {
			if (out.length() > 0) out.append(LABEL_SEPARATOR);
			out.append(entry.getKey()).append(LABEL_ASSIGNMENT).append(entry.getValue());
		}
		return out.toString();
	}

	/**
	 * Decodes a labels field value into a map. A blank value yields an empty map rather than
	 * {@code null}, since the field was present and simply carried no labels.
	 *
	 * @param value  the encoded labels, e.g. {@code "platform=linux,hostname=halo"}.
	 * @return  the decoded labels; never {@code null}.
	 */
	private static Map<String, String> decodeLabels(String value) {
		Map<String, String> labels = new LinkedHashMap<>();
		if (value == null || value.isEmpty()) return labels;

		for (String pair : value.split(LABEL_SEPARATOR)) {
			int eq = pair.indexOf(LABEL_ASSIGNMENT);
			if (eq <= 0) continue;

			String key = pair.substring(0, eq).trim();
			String val = pair.substring(eq + LABEL_ASSIGNMENT.length()).trim();
			if (!key.isEmpty()) labels.put(key, val);
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
				busy = Boolean.valueOf(value);
				present = true;
			} else if (LABELS_KEY.equals(key)) {
				labels = decodeLabels(value);
				present = true;
			}
		}

		return present ? new NodeStatusReport(busy, labels) : null;
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
