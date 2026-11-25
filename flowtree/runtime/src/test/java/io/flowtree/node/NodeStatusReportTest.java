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
import io.flowtree.msg.Message;
import io.flowtree.msg.NodeProxy;
import io.flowtree.test.HeaderOnlySocket;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verifies the wire encoding, decoding, and JSON rendering of {@link NodeStatusReport}: the
 * value a worker reports over the {@link io.flowtree.msg.Message#ServerStatus} protocol so the
 * controller can tell a submitter each node's labels and whether it is busy.
 *
 * <p>The decisive behaviour is backward compatibility: a status payload from a node running an
 * older build carries neither the busy nor the labels field, and {@link NodeStatusReport#parse}
 * must report that as unknown (so the controller leaves the node's status unknown) rather than
 * as an idle, unlabelled node.</p>
 *
 * @author Michael Murray
 */
public class NodeStatusReportTest extends TestSuiteBase {

	/** The status fields a worker appends to a {@code ServerStatus} payload round-trip through parse. */
	@Test(timeout = 10000)
	public void encodeThenParseRoundTrips() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("platform", "linux");
		labels.put("hostname", "halo");

		String fields = new NodeStatusReport(true, labels).encode();
		Assert.assertEquals("busy:true;labels:platform=linux,hostname=halo", fields);

		NodeStatusReport parsed = NodeStatusReport.parse("jobtime:0.0;activity:1.0;" + fields);
		Assert.assertEquals(Boolean.TRUE, parsed.busy());
		Assert.assertEquals(labels, parsed.labels());
	}

	/**
	 * A payload carrying only the legacy activity/job-time fields — what a node running an
	 * older build sends — parses to no report, so the caller leaves the peer's status unknown.
	 */
	@Test(timeout = 10000)
	public void parseOfLegacyPayloadReportsUnknown() {
		Assert.assertNull(NodeStatusReport.parse("jobtime:0.0;activity:1.0"));
	}

	/** A node reporting an empty label set is distinguished from one that reported nothing. */
	@Test(timeout = 10000)
	public void parseDistinguishesEmptyLabelsFromUnknown() {
		NodeStatusReport report = NodeStatusReport.parse("busy:false;labels:");
		Assert.assertEquals(Boolean.FALSE, report.busy());
		Assert.assertNotNull("empty labels are known, not unknown", report.labels());
		Assert.assertTrue(report.labels().isEmpty());

		Assert.assertNull("no labels field means unknown", NodeStatusReport.unknown().labels());
	}

	/** The busy and labels fields are recognised as report fields; other fields are not. */
	@Test(timeout = 10000)
	public void isReportFieldIdentifiesOwnFields() {
		Assert.assertTrue(NodeStatusReport.isReportField("busy:true"));
		Assert.assertTrue(NodeStatusReport.isReportField("labels:platform=linux"));
		Assert.assertFalse(NodeStatusReport.isReportField("jobtime:0.0"));
		Assert.assertFalse(NodeStatusReport.isReportField("activity:1.0"));
	}

	/**
	 * A known report renders only its own busy flag and labels; the owning node adds the
	 * address, so the report never renders one.
	 */
	@Test(timeout = 10000)
	public void toJsonRendersKnownStatus() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("platform", "macos");

		ObjectNode json = new NodeStatusReport(false, labels).toJson();
		Assert.assertFalse("a report renders no address field", json.has("address"));
		Assert.assertFalse(json.get("busy").asBoolean());
		Assert.assertFalse(json.get("busy").isNull());
		Assert.assertEquals("macos", json.get("labels").get("platform").asText());
	}

	/** An unknown report renders busy and labels as JSON null, not as idle or empty. */
	@Test(timeout = 10000)
	public void toJsonRendersUnknownAsNull() {
		ObjectNode json = NodeStatusReport.unknown().toJson();
		Assert.assertFalse("a report renders no address field", json.has("address"));
		Assert.assertTrue("unknown busy must be JSON null", json.get("busy").isNull());
		Assert.assertTrue("unknown labels must be JSON null", json.get("labels").isNull());
	}

	/**
	 * A {@code busy} field carrying anything other than {@code true}/{@code false} — a report
	 * garbled by a misbehaving or incompatible peer — parses to unknown rather than idle, so a
	 * malformed value never makes a peer appear available to take work.
	 */
	@Test(timeout = 10000)
	public void parseOfMalformedBusyReportsUnknown() {
		NodeStatusReport report = NodeStatusReport.parse("busy:maybe;labels:platform=linux");
		Assert.assertNull("a malformed busy value is unknown, not idle", report.busy());
		Assert.assertEquals("linux", report.labels().get("platform"));
	}

	/**
	 * A {@code labels} field carrying a malformed pair — one without a non-empty key ahead of its
	 * {@code =} — decodes to unknown labels rather than to a partial or empty set, so a garbled
	 * report never presents inaccurate capabilities as if they were authoritative. The busy flag
	 * alongside it still decodes normally.
	 */
	@Test(timeout = 10000)
	public void parseOfMalformedLabelsReportsUnknown() {
		NodeStatusReport missingAssignment = NodeStatusReport.parse("busy:true;labels:platform");
		Assert.assertEquals(Boolean.TRUE, missingAssignment.busy());
		Assert.assertNull("a labels field with no assignment is unknown", missingAssignment.labels());

		NodeStatusReport partial = NodeStatusReport.parse("busy:false;labels:platform=linux,bad");
		Assert.assertEquals(Boolean.FALSE, partial.busy());
		Assert.assertNull("a partially malformed labels field is unknown, not partial", partial.labels());
	}

	/**
	 * Whitespace within a decoded label key or value is preserved, because the encoder writes no
	 * padding around the {@code =} and {@code ,} separators; any surrounding whitespace is part of
	 * the capability the peer routes on, so trimming it would report a label that differs from the
	 * one in use.
	 */
	@Test(timeout = 10000)
	public void parsePreservesWhitespaceInLabelValues() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("hostname", "build box ");

		String fields = new NodeStatusReport(true, labels).encode();
		Assert.assertEquals("busy:true;labels:hostname=build box ", fields);

		NodeStatusReport parsed = NodeStatusReport.parse(fields);
		Assert.assertEquals("build box ", parsed.labels().get("hostname"));
	}

	/**
	 * A label whose key or value carries one of the payload's structural delimiters cannot be
	 * encoded, so the labels field is left out entirely: it can neither split into spurious pairs
	 * nor forge a status field such as {@code busy}, and the receiver reads the labels as unknown
	 * rather than as an authoritative partial set made of the labels that happened to survive.
	 */
	@Test(timeout = 10000)
	public void encodeReportsLabelsUnknownWhenAnyCarriesDelimiter() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("platform", "linux");
		labels.put("role", "a;busy=true");
		labels.put("re,gion", "us");

		String fields = new NodeStatusReport(false, labels).encode();
		Assert.assertEquals("busy:false", fields);

		NodeStatusReport parsed = NodeStatusReport.parse(fields);
		Assert.assertEquals(Boolean.FALSE, parsed.busy());
		Assert.assertNull("a partially unrepresentable label set is unknown", parsed.labels());
	}

	/**
	 * A label set made only of unrepresentable labels must not encode as an empty {@code labels:}
	 * field, which the receiver would read as "known to have no labels".
	 */
	@Test(timeout = 10000)
	public void encodeOfOnlyUnrepresentableLabelsIsNotEmptySet() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("role", "gpu:metal");

		String fields = new NodeStatusReport(true, labels).encode();
		Assert.assertEquals("busy:true", fields);
		Assert.assertNull(NodeStatusReport.parse(fields).labels());
	}

	/**
	 * A label with an empty key cannot be decoded back — {@link NodeStatusReport#parse} requires a
	 * non-empty key ahead of the {@code =} — so the encoder treats it as unrepresentable and leaves
	 * the whole labels field out, rather than emitting a {@code labels:=value} field the receiver
	 * would reject as unknown anyway. Encode and decode therefore agree on what is representable.
	 */
	@Test(timeout = 10000)
	public void encodeReportsLabelsUnknownWhenKeyIsEmpty() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("platform", "linux");
		labels.put("", "orphan");

		String fields = new NodeStatusReport(true, labels).encode();
		Assert.assertEquals("busy:true", fields);
		Assert.assertNull("an empty-key label set is unknown, not partial",
				NodeStatusReport.parse(fields).labels());
	}

	/**
	 * Encoding distinguishes unknown labels from an empty set: {@code null} labels omit the field,
	 * while an empty map emits an empty field that decodes back to an empty, non-null map.
	 */
	@Test(timeout = 10000)
	public void encodeDistinguishesUnknownLabelsFromEmptySet() {
		String unknown = new NodeStatusReport(false, null).encode();
		Assert.assertEquals("busy:false", unknown);
		Assert.assertNull(NodeStatusReport.parse(unknown).labels());

		String empty = new NodeStatusReport(false, new LinkedHashMap<>()).encode();
		Assert.assertEquals("busy:false;labels:", empty);
		Map<String, String> decoded = NodeStatusReport.parse(empty).labels();
		Assert.assertNotNull(decoded);
		Assert.assertTrue(decoded.isEmpty());
	}

	/**
	 * An unknown busy state omits the busy field rather than emitting {@code busy:false}, so the
	 * encode/parse round trip preserves the unknown state instead of collapsing it into idle. An
	 * all-unknown report encodes to the empty string, which parses back to "nothing reported"
	 * ({@code null}), leaving a peer's existing status untouched.
	 */
	@Test(timeout = 10000)
	public void encodeOmitsBusyFieldWhenUnknown() {
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("platform", "linux");

		String labelsOnly = new NodeStatusReport(null, labels).encode();
		Assert.assertEquals("labels:platform=linux", labelsOnly);

		NodeStatusReport parsed = NodeStatusReport.parse(labelsOnly);
		Assert.assertNull("an omitted busy field decodes as unknown, not idle", parsed.busy());
		Assert.assertEquals(labels, parsed.labels());

		Assert.assertEquals("", NodeStatusReport.unknown().encode());
		Assert.assertNull("an all-unknown report reports nothing",
				NodeStatusReport.parse(NodeStatusReport.unknown().encode()));
	}

	/**
	 * A dangling label separator leaves an empty trailing pair, which is malformed; the whole
	 * labels field decodes to unknown instead of the pairs that preceded it.
	 */
	@Test(timeout = 10000)
	public void parseOfTrailingEmptyLabelPairReportsUnknown() {
		NodeStatusReport trailing = NodeStatusReport.parse("busy:false;labels:platform=linux,");
		Assert.assertEquals(Boolean.FALSE, trailing.busy());
		Assert.assertNull("a trailing empty pair is malformed", trailing.labels());

		NodeStatusReport inner = NodeStatusReport.parse("busy:false;labels:platform=linux,,os=mac");
		Assert.assertNull("an inner empty pair is malformed", inner.labels());
	}

	/**
	 * Drives a full {@code ServerStatus} payload through {@link NodeGroupMessageHandler} and
	 * asserts the parsed busy flag and labels land on the sending {@link NodeProxy}, so
	 * {@link NodeProxy#getStatus()} reflects what the peer reported. The handler's status branch
	 * does not touch its {@link NodeGroup}, so the group is unneeded here.
	 */
	@Test(timeout = 10000)
	public void handlerAppliesReportedStatusToProxy() throws Exception {
		NodeProxy proxy = new NodeProxy(new HeaderOnlySocket());
		Message status = new Message(Message.ServerStatus, -1, proxy);
		status.setString("jobtime:0.0;activity:1.0;busy:true;labels:platform=linux,hostname=halo");

		boolean handled = new NodeGroupMessageHandler(null).recievedMessage(status, -1);

		Assert.assertTrue("a status payload carrying report fields is handled", handled);
		Assert.assertEquals(Boolean.TRUE, proxy.getStatus().busy());
		Assert.assertEquals("halo", proxy.getStatus().labels().get("hostname"));
		Assert.assertEquals("linux", proxy.getStatus().labels().get("platform"));
	}

	/**
	 * A {@code ServerStatus} payload from a peer running an older build — only the legacy
	 * job-time and activity fields, no busy or labels — leaves the proxy's status unknown
	 * through the handler, never overwriting it with an all-unknown report that reads as idle.
	 */
	@Test(timeout = 10000)
	public void handlerLeavesStatusUnknownForLegacyPayload() throws Exception {
		NodeProxy proxy = new NodeProxy(new HeaderOnlySocket());
		Message legacy = new Message(Message.ServerStatus, -1, proxy);
		legacy.setString("jobtime:0.0;activity:1.0");

		new NodeGroupMessageHandler(null).recievedMessage(legacy, -1);

		Assert.assertNull("a legacy payload leaves busy unknown", proxy.getStatus().busy());
		Assert.assertNull("a legacy payload leaves labels unknown", proxy.getStatus().labels());
	}

	/**
	 * A peer renders itself for {@code GET /api/agents} as the handler-assembled {@code nodes}
	 * entry: the {@link NodeProxy} owns the {@code address} and merges in the {@code busy} and
	 * {@code labels} it last reported through its {@link NodeStatusReport status}.
	 */
	@Test(timeout = 10000)
	public void proxyToJsonDescribesAddressAndReportedStatus() throws Exception {
		NodeProxy proxy = new NodeProxy(new HeaderOnlySocket());
		Map<String, String> labels = new LinkedHashMap<>();
		labels.put("platform", "linux");
		proxy.setStatus(new NodeStatusReport(true, labels));

		ObjectNode json = proxy.toJson();
		Assert.assertTrue("the node owns and renders its address", json.has("address"));
		Assert.assertFalse("the address is populated", json.get("address").asText().isEmpty());
		Assert.assertTrue("the reported busy flag is merged in", json.get("busy").asBoolean());
		Assert.assertEquals("linux", json.get("labels").get("platform").asText());
	}
}
