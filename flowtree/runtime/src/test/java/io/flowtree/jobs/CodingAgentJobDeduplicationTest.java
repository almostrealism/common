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
 * Tests for the deduplication helpers on {@link CodingAgentJob}: mode constants,
 * getter/setter round-trip, serialisation, and URL parsing.
 */
public class CodingAgentJobDeduplicationTest extends TestSuiteBase {

	/** Verifies that a freshly constructed job has no deduplication mode set. */
	@Test(timeout = 30000)
	public void dedupModeDefaultIsNull() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		assertNull(job.getDeduplicationMode());
	}

	/** Verifies that the DEDUP_LOCAL constant has the expected string value. */
	@Test(timeout = 30000)
	public void dedupModeLocalConstant() {
		assertEquals("local", CodingAgentJob.DEDUP_LOCAL);
	}

	/** Verifies that the DEDUP_SPAWN constant has the expected string value. */
	@Test(timeout = 30000)
	public void dedupModeSpawnConstant() {
		assertEquals("spawn", CodingAgentJob.DEDUP_SPAWN);
	}

	/** Verifies that setting the deduplication mode to DEDUP_LOCAL is reflected by the getter. */
	@Test(timeout = 30000)
	public void setDedupModeLocal() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		job.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
		assertEquals(CodingAgentJob.DEDUP_LOCAL, job.getDeduplicationMode());
	}

	/** Verifies that setting the deduplication mode to DEDUP_SPAWN is reflected by the getter. */
	@Test(timeout = 30000)
	public void setDedupModeSpawn() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		job.setDeduplicationMode(CodingAgentJob.DEDUP_SPAWN);
		assertEquals(CodingAgentJob.DEDUP_SPAWN, job.getDeduplicationMode());
	}

	/** Verifies that setting the deduplication mode back to null clears the previous value. */
	@Test(timeout = 30000)
	public void setDedupModeNullDisables() {
		CodingAgentJob job = new CodingAgentJob("t1", "do something");
		job.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
		job.setDeduplicationMode(null);
		assertNull(job.getDeduplicationMode());
	}

	/** Verifies that the deduplication mode is included in the encoded wire format. */
	@Test(timeout = 30000)
	public void encodedModeAppearsInWireFormat() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		job.setDeduplicationMode(CodingAgentJob.DEDUP_LOCAL);
		String encoded = job.encode();
		assertNotNull(encoded);
		assert encoded.contains("dedupMode:=local") :
			"Expected dedupMode:=local in: " + encoded;
	}

	/** Verifies that no deduplication mode key appears in the encoded wire format when mode is null. */
	@Test(timeout = 30000)
	public void nullModeOmittedFromWireFormat() {
		CodingAgentJob job = new CodingAgentJob("t1", "hello");
		String encoded = job.encode();
		assertNotNull(encoded);
		assert !encoded.contains("dedupMode") :
			"Expected no dedupMode in: " + encoded;
	}



	/** Verifies that extractControllerBaseUrl returns scheme and host with port from a typical job URL. */
	@Test(timeout = 30000)
	public void extractControllerBaseUrl_typicalUrl() {
		String url = "http://0.0.0.0:7700/api/workstreams/ws-1/jobs/job-abc";
		assertEquals("http://0.0.0.0:7700",
				DeduplicationSpawner.extractControllerBaseUrl(url));
	}

	/** Verifies that extractControllerBaseUrl returns only the scheme and host when no port is present. */
	@Test(timeout = 30000)
	public void extractControllerBaseUrl_hostOnly() {
		String url = "http://controller.local/api/workstreams/mystream/jobs/j1";
		assertEquals("http://controller.local",
				DeduplicationSpawner.extractControllerBaseUrl(url));
	}

	/** Verifies that extractControllerBaseUrl returns null when the URL contains no workstreams segment. */
	@Test(timeout = 30000)
	public void extractControllerBaseUrl_noWorkstreamsSegment_returnsNull() {
		assertNull(DeduplicationSpawner.extractControllerBaseUrl("http://host:7700/api/submit"));
	}

	/** Verifies that extractControllerBaseUrl returns null when given an empty string. */
	@Test(timeout = 30000)
	public void extractControllerBaseUrl_emptyString_returnsNull() {
		assertNull(DeduplicationSpawner.extractControllerBaseUrl(""));
	}

	/** Verifies that extractWorkstreamId returns the workstream identifier from a typical job URL. */
	@Test(timeout = 30000)
	public void extractWorkstreamId_typicalUrl() {
		String url = "http://0.0.0.0:7700/api/workstreams/ws-1/jobs/job-abc";
		assertEquals("ws-1", DeduplicationSpawner.extractWorkstreamId(url));
	}

	/** Verifies that extractWorkstreamId returns the workstream id even when there is no trailing jobs segment. */
	@Test(timeout = 30000)
	public void extractWorkstreamId_noJobsSegment() {
		// URL has workstream but no /jobs/... suffix
		String url = "http://host/api/workstreams/mystream";
		assertEquals("mystream", DeduplicationSpawner.extractWorkstreamId(url));
	}

	/** Verifies that extractWorkstreamId returns null when the URL contains no workstreams segment. */
	@Test(timeout = 30000)
	public void extractWorkstreamId_noWorkstreamsSegment_returnsNull() {
		assertNull(DeduplicationSpawner.extractWorkstreamId("http://host:7700/api/submit"));
	}

	/** Verifies that extractWorkstreamId returns null when given an empty string. */
	@Test(timeout = 30000)
	public void extractWorkstreamId_emptyString_returnsNull() {
		assertNull(DeduplicationSpawner.extractWorkstreamId(""));
	}
}
