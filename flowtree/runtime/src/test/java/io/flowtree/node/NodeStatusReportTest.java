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

	// TODO(review): no test covers the wiring — NodeGroupMessageHandler.handleServerStatus -> NodeProxy.setStatus/getStatus, nor AgentsQueryHandler assembling the nodes array.

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
}
