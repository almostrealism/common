/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.flowtree.api;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.jobs.BlockReport;
import io.flowtree.jobs.BlockReportRegistry;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

/**
 * Tests for {@link BlockedEndpointHandler}: a POST records a well-formed block
 * for a job and rejects a malformed one, and a GET returns the recorded block
 * exactly once, consuming it as it is read.
 */
public class BlockedEndpointHandlerTest extends TestSuiteBase {

	/** Builds a 400 response whose body carries the message, mirroring the parent endpoint. */
	private static final Function<String, Response> ERROR = message ->
			NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
					"{\"error\":\"" + message + "\"}");

	/**
	 * Builds a handler over the given registry whose body reader returns a fixed
	 * string, so a test drives {@code handlePost} without a live HTTP session.
	 *
	 * @param registry the registry the handler records into and reads from
	 * @param body     the request body the handler will read, or {@code null}
	 * @return a handler bound to the registry
	 */
	private BlockedEndpointHandler handler(BlockReportRegistry registry, String body) {
		Function<IHTTPSession, String> readBody = session -> body;
		return new BlockedEndpointHandler(registry, readBody, ERROR, message -> { });
	}

	/**
	 * Reads a response body fully as UTF-8.
	 *
	 * @param response the response to read
	 * @return the body text
	 * @throws Exception if the body cannot be read
	 */
	private String body(Response response) throws Exception {
		try (InputStream in = response.getData()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** A well-formed POST records the block for its job and answers {@code ok}. */
	@Test(timeout = 30000)
	public void postRecordsAWellFormedBlock() throws Exception {
		BlockReportRegistry registry = new BlockReportRegistry();
		Response response = handler(registry, "{\"category\":\"missing_resource\",\"detail\":\"model absent\"}")
				.handlePost(null, "job-1");

		Assert.assertEquals(Response.Status.OK, response.getStatus());
		Assert.assertEquals("{\"ok\":true}", body(response));
		BlockReport recorded = registry.consume("job-1").orElse(null);
		Assert.assertNotNull("the block is recorded for the job", recorded);
		Assert.assertEquals("missing_resource", recorded.getCategory());
		Assert.assertEquals("model absent", recorded.getDetail());
		Assert.assertFalse("the controller stores the claim unverified", recorded.isVerified());
	}

	/** A POST with no job id is rejected and records nothing. */
	@Test(timeout = 30000)
	public void postWithoutJobIdIsRejected() {
		BlockReportRegistry registry = new BlockReportRegistry();
		Response response = handler(registry, "{\"category\":\"guardrail\"}")
				.handlePost(null, "");

		Assert.assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
		Assert.assertTrue("an unknown job records nothing", registry.consume("").isEmpty());
	}

	/** A POST naming a category outside the fixed set is rejected and records nothing. */
	@Test(timeout = 30000)
	public void postWithUnknownCategoryIsRejected() {
		BlockReportRegistry registry = new BlockReportRegistry();
		Response response = handler(registry, "{\"category\":\"made_up\",\"detail\":\"x\"}")
				.handlePost(null, "job-2");

		Assert.assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
		Assert.assertTrue("a rejected claim is not stored", registry.consume("job-2").isEmpty());
	}

	/** A GET with no recorded block answers {@code blocked:false}. */
	@Test(timeout = 30000)
	public void getWithoutAReportAnswersNotBlocked() throws Exception {
		BlockReportRegistry registry = new BlockReportRegistry();
		Response response = handler(registry, null).handleGet("job-3");

		Assert.assertEquals(Response.Status.OK, response.getStatus());
		Assert.assertEquals("{\"blocked\":false}", body(response));
	}

	/**
	 * A GET returns the recorded block once and consumes it: the category and
	 * detail are rendered and escaped, and a second GET sees nothing.
	 *
	 * @throws Exception if a response body cannot be read
	 */
	@Test(timeout = 30000)
	public void getReturnsThenConsumesTheReport() throws Exception {
		BlockReportRegistry registry = new BlockReportRegistry();
		registry.record("job-4", new BlockReport(BlockReport.AMBIGUOUS_TASK,
				"needs a \"decision\"", true));
		BlockedEndpointHandler handler = handler(registry, null);

		String first = body(handler.handleGet("job-4"));
		Assert.assertTrue("the block is reported", first.contains("\"blocked\":true"));
		Assert.assertTrue("the category is rendered", first.contains("\"category\":\"ambiguous_task\""));
		Assert.assertTrue("the detail's quotes are JSON-escaped",
				first.contains("needs a \\\"decision\\\""));

		Assert.assertEquals("a second read finds nothing — the report was consumed",
				"{\"blocked\":false}", body(handler.handleGet("job-4")));
	}
}
