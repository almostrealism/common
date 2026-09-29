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

package io.flowtree.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.controller.JobStatsStore;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the JSON string escaping performed by the hand-built JSON renderers in
 * {@code flowtree/runtime}: {@link Workstream#toSummaryJson()},
 * {@link JobStatsStore.ActiveJob#toJson(Instant)} and
 * {@link FlowTreeApiEndpoint#escapeJsonValue(String)}.
 *
 * <p>Every renderer must produce output a strict JSON parser accepts and that
 * decodes back to the original text. That includes the C0 control characters,
 * which JSON forbids literally and which terminal output (ANSI colour
 * sequences begin with {@code ESC}) routinely carries.</p>
 */
public class JsonStringEscapingTest extends TestSuiteBase {

	/** Strict JSON parser; rejects unescaped control characters by default. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Text exercising the characters every renderer has always escaped. */
	private static final String BASIC = "say \"hi\" C:\\tmp\nnext\rline";

	/** Text carrying a tab and an ANSI colour sequence. */
	private static final String CONTROL = "col\tumn \u001b[31mred\u001b[0m";

	/**
	 * Quotes, backslashes and line breaks in a workstream summary are escaped
	 * with their short forms.
	 */
	@Test(timeout = 10000)
	public void workstreamSummaryEscapesBasicCharacters() throws IOException {
		Workstream ws = new Workstream("ws-1", "C1", BASIC);
		String json = ws.toSummaryJson();

		assertTrue(json, json.contains(
				"\"channelName\":\"say \\\"hi\\\" C:\\\\tmp\\nnext\\rline\""));
		assertEquals(BASIC, MAPPER.readTree(json).get("channelName").asText());
	}

	/**
	 * A workstream summary carrying a tab or an escape character still parses
	 * under a strict parser and decodes to the original text.
	 */
	@Test(timeout = 10000)
	public void workstreamSummaryEscapesControlCharacters() throws IOException {
		Workstream ws = new Workstream("ws-1", "C1", CONTROL);
		String json = ws.toSummaryJson();

		assertTrue(json, json.contains("col\\tumn \\u001b[31mred\\u001b[0m"));
		assertEquals(CONTROL, MAPPER.readTree(json).get("channelName").asText());
	}

	/**
	 * Quotes, backslashes, line breaks and tabs in an active-job listing are
	 * escaped with their short forms, and a {@code null} identifier renders
	 * as an empty string.
	 */
	@Test(timeout = 10000)
	public void activeJobEscapesBasicCharacters() throws IOException {
		Instant now = Instant.now();
		JobStatsStore.ActiveJob job = new JobStatsStore.ActiveJob(
				"job-1", null, now, null, BASIC + "\tend");
		String json = job.toJson(now);

		assertTrue(json, json.contains("\"workstreamId\":\"\""));
		assertTrue(json, json.contains(
				"\"description\":\"say \\\"hi\\\" C:\\\\tmp\\nnext\\rline\\tend\""));
		assertEquals(BASIC + "\tend", MAPPER.readTree(json).get("description").asText());
	}

	/**
	 * An active-job description carrying an ANSI colour sequence still parses
	 * under a strict parser and decodes to the original text.
	 */
	@Test(timeout = 10000)
	public void activeJobEscapesControlCharacters() throws IOException {
		Instant now = Instant.now();
		JobStatsStore.ActiveJob job = new JobStatsStore.ActiveJob(
				"job-1", "ws-1", now, now, CONTROL);
		String json = job.toJson(now);

		assertTrue(json, json.contains("col\\tumn \\u001b[31mred\\u001b[0m"));
		assertEquals(CONTROL, MAPPER.readTree(json).get("description").asText());
	}

	/**
	 * {@link FlowTreeApiEndpoint#escapeJsonValue(String)} produces a quoted
	 * JSON string, escapes control characters as {@code \\u00xx}, and decodes
	 * back to the original text, including backspace and form feed.
	 */
	@Test(timeout = 10000)
	public void escapeJsonValueQuotesAndEscapes() throws IOException {
		assertEquals("\"say \\\"hi\\\" C:\\\\tmp\\nnext\\rline\"",
				FlowTreeApiEndpoint.escapeJsonValue(BASIC));
		assertEquals("\"col\\tumn \\u001b[31mred\\u001b[0m\"",
				FlowTreeApiEndpoint.escapeJsonValue(CONTROL));
		assertEquals("\"\"", FlowTreeApiEndpoint.escapeJsonValue(""));

		String edges = "a\bb\fc\u0001d\u001fe";
		JsonNode decoded = MAPPER.readTree(FlowTreeApiEndpoint.escapeJsonValue(edges));
		assertEquals(edges, decoded.asText());
	}
}
