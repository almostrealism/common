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

package io.flowtree.jobs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

/**
 * Tests for the deduplication helpers on {@link ClaudeCodeJob}: mode constants,
 * getter/setter round-trip, serialisation, and URL parsing.
 */
public class ClaudeCodeJobDeduplicationTest extends TestSuiteBase {

	// ── Mode constants and setter ────────────────────────────────────────────

	@Test(timeout = 30000)
	public void dedupModeDefaultIsNull() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		assertNull(job.getDeduplicationMode());
	}

	@Test(timeout = 30000)
	public void dedupModeLocalConstant() {
		assertEquals("local", ClaudeCodeJob.DEDUP_LOCAL);
	}

	@Test(timeout = 30000)
	public void dedupModeSpawnConstant() {
		assertEquals("spawn", ClaudeCodeJob.DEDUP_SPAWN);
	}

	@Test(timeout = 30000)
	public void setDedupModeLocal() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		job.setDeduplicationMode(ClaudeCodeJob.DEDUP_LOCAL);
		assertEquals(ClaudeCodeJob.DEDUP_LOCAL, job.getDeduplicationMode());
	}

	@Test(timeout = 30000)
	public void setDedupModeSpawn() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		job.setDeduplicationMode(ClaudeCodeJob.DEDUP_SPAWN);
		assertEquals(ClaudeCodeJob.DEDUP_SPAWN, job.getDeduplicationMode());
	}

	@Test(timeout = 30000)
	public void setDedupModeNullDisables() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "do something");
		job.setDeduplicationMode(ClaudeCodeJob.DEDUP_LOCAL);
		job.setDeduplicationMode(null);
		assertNull(job.getDeduplicationMode());
	}

	// ── Serialisation round-trip ─────────────────────────────────────────────

	@Test(timeout = 30000)
	public void encodedModeAppearsInWireFormat() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		job.setDeduplicationMode(ClaudeCodeJob.DEDUP_LOCAL);
		String encoded = job.encode();
		assertNotNull(encoded);
		assert encoded.contains("dedupMode:=local") :
			"Expected dedupMode:=local in: " + encoded;
	}

	@Test(timeout = 30000)
	public void nullModeOmittedFromWireFormat() {
		ClaudeCodeJob job = new ClaudeCodeJob("t1", "hello");
		String encoded = job.encode();
		assertNotNull(encoded);
		assert !encoded.contains("dedupMode") :
			"Expected no dedupMode in: " + encoded;
	}



	// ── extractControllerBaseUrl ─────────────────────────────────────────────

	@Test(timeout = 30000)
	public void extractControllerBaseUrl_typicalUrl() {
		String url = "http://0.0.0.0:7700/api/workstreams/ws-1/jobs/job-abc";
		assertEquals("http://0.0.0.0:7700",
				ClaudeCodeJob.extractControllerBaseUrl(url));
	}

	@Test(timeout = 30000)
	public void extractControllerBaseUrl_hostOnly() {
		String url = "http://controller.local/api/workstreams/mystream/jobs/j1";
		assertEquals("http://controller.local",
				ClaudeCodeJob.extractControllerBaseUrl(url));
	}

	@Test(timeout = 30000)
	public void extractControllerBaseUrl_noWorkstreamsSegment_returnsNull() {
		assertNull(ClaudeCodeJob.extractControllerBaseUrl("http://host:7700/api/submit"));
	}

	@Test(timeout = 30000)
	public void extractControllerBaseUrl_emptyString_returnsNull() {
		assertNull(ClaudeCodeJob.extractControllerBaseUrl(""));
	}

	// ── extractWorkstreamId ──────────────────────────────────────────────────

	@Test(timeout = 30000)
	public void extractWorkstreamId_typicalUrl() {
		String url = "http://0.0.0.0:7700/api/workstreams/ws-1/jobs/job-abc";
		assertEquals("ws-1", ClaudeCodeJob.extractWorkstreamId(url));
	}

	@Test(timeout = 30000)
	public void extractWorkstreamId_noJobsSegment() {
		// URL has workstream but no /jobs/... suffix
		String url = "http://host/api/workstreams/mystream";
		assertEquals("mystream", ClaudeCodeJob.extractWorkstreamId(url));
	}

	@Test(timeout = 30000)
	public void extractWorkstreamId_noWorkstreamsSegment_returnsNull() {
		assertNull(ClaudeCodeJob.extractWorkstreamId("http://host:7700/api/submit"));
	}

	@Test(timeout = 30000)
	public void extractWorkstreamId_emptyString_returnsNull() {
		assertNull(ClaudeCodeJob.extractWorkstreamId(""));
	}
}
