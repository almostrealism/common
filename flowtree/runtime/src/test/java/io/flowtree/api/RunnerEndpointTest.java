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
import com.sun.net.httpserver.HttpServer;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.CookieHandler;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import io.flowtree.github.GitHubProxyHandler;
import io.flowtree.github.RunnerMonitor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests {@link RunnerQueryHandler}'s HTTP surface: {@code GET /api/runners/select}
 * and {@code GET /api/runners}, the required-parameter and method guards, and the
 * JSON shape each produces — the serialisation the controller's CI selector
 * actually reads, which no other test exercises.
 */
public class RunnerEndpointTest extends TestSuiteBase {

	/** JSON parser for the responses under assertion. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** A listing with one idle CUDA runner and one idle macOS runner, both carrying ar-build. */
	private static final String LISTING = "{\"total_count\":2,\"runners\":["
			+ "{\"name\":\"cuda-1\",\"status\":\"online\",\"busy\":false,\"labels\":["
			+ "{\"name\":\"self-hosted\"},{\"name\":\"linux\"},{\"name\":\"ar-ci-cuda\"},{\"name\":\"ar-build\"}]},"
			+ "{\"name\":\"macos-1\",\"status\":\"online\",\"busy\":false,\"labels\":["
			+ "{\"name\":\"self-hosted\"},{\"name\":\"macos\"},{\"name\":\"ar-ci\"},{\"name\":\"ar-build\"}]}"
			+ "]}";

	/** A 400 factory that tags its body so a guarded path is distinguishable from a served one. */
	private static final Function<String, Response> ERROR =
			message -> NanoHTTPD.newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json",
					"{\"ok\":false,\"error\":\"" + message + "\"}");

	/** Builds a handler whose monitor reads the fixed {@link #LISTING} from a stub server. */
	private static RunnerQueryHandler handlerOver(HttpServer server) {
		server.createContext("/", exchange -> {
			byte[] bytes = LISTING.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		server.start();
		GitHubProxyHandler github = new GitHubProxyHandler(Map.of("almostrealism", "tok"),
				"http://127.0.0.1:" + server.getAddress().getPort());
		return new RunnerQueryHandler(new RunnerMonitor(github, 60_000L,
				RunnerMonitor.DEFAULT_FLEETS, RunnerMonitor.DEFAULT_PREFERENCE));
	}

	/** A selection for an idle fleet emits its runs-on list, the fleet, and listed=true. */
	@Test(timeout = 30000)
	public void selectEmitsRunsOnForTheIdleFleet() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			Response response = handlerOver(server).handle(
					new FakeSession(Map.of("required", List.of("ar-build"),
							"org", List.of("almostrealism"))),
					Method.GET, "/api/runners/select", ERROR);

			assertEquals(Response.Status.OK, response.getStatus());
			JsonNode body = body(response);
			assertTrue(body.path("ok").asBoolean());
			assertFalse("an idle fleet was found, so not hosted", body.path("hosted").asBoolean());
			assertTrue(body.path("listed").asBoolean());
			assertEquals("ar-ci-cuda", body.path("fleet").asText());
			assertEquals(List.of("self-hosted", "linux", "ar-ci-cuda", "ar-build"),
					toList(body.path("runs_on")));
			assertFalse("the reason is populated", body.path("reason").asText().isEmpty());
		} finally {
			server.stop(0);
		}
	}

	/** An explicit fleets preference is honoured by the endpoint. */
	@Test(timeout = 30000)
	public void selectHonoursTheFleetsPreference() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			Response response = handlerOver(server).handle(
					new FakeSession(Map.of("required", List.of("ar-build"),
							"fleets", List.of("macos,ar-ci-cuda"))),
					Method.GET, "/api/runners/select", ERROR);

			JsonNode body = body(response);
			assertEquals("macos", body.path("fleet").asText());
			assertEquals(List.of("self-hosted", "macos", "ar-build"), toList(body.path("runs_on")));
		} finally {
			server.stop(0);
		}
	}

	/** When no idle runner carries the required label, the endpoint reports a hosted fallback with a null fleet. */
	@Test(timeout = 30000)
	public void selectFallsBackToHostedWithNullFleet() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			Response response = handlerOver(server).handle(
					new FakeSession(Map.of("required", List.of("ar-deploy"))),
					Method.GET, "/api/runners/select", ERROR);

			JsonNode body = body(response);
			assertTrue("no runner carries ar-deploy, so hosted", body.path("hosted").asBoolean());
			assertTrue("the list was read, so this is a busy fallback", body.path("listed").asBoolean());
			assertTrue("the fleet is null when hosted", body.path("fleet").isNull());
			assertEquals(List.of("ubuntu-latest"), toList(body.path("runs_on")));
		} finally {
			server.stop(0);
		}
	}

	/** A select without the required label is a 400, and no runner list is read. */
	@Test(timeout = 10000)
	public void selectWithoutRequiredIsRejected() {
		RunnerQueryHandler handler = new RunnerQueryHandler(
				new RunnerMonitor(new GitHubProxyHandler(Map.of()), 60_000L,
						RunnerMonitor.DEFAULT_FLEETS, RunnerMonitor.DEFAULT_PREFERENCE));

		Response response = handler.handle(new FakeSession(Map.of()),
				Method.GET, "/api/runners/select", ERROR);

		assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
	}

	/** A non-GET method is rejected before any routing. */
	@Test(timeout = 10000)
	public void nonGetMethodIsRejected() {
		RunnerQueryHandler handler = new RunnerQueryHandler(
				new RunnerMonitor(new GitHubProxyHandler(Map.of()), 60_000L,
						RunnerMonitor.DEFAULT_FLEETS, RunnerMonitor.DEFAULT_PREFERENCE));

		Response response = handler.handle(new FakeSession(Map.of("required", List.of("ar-build"))),
				Method.POST, "/api/runners/select", ERROR);

		assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
	}

	/** An unknown path under /api/runners is a 400 rather than a silent 200. */
	@Test(timeout = 10000)
	public void unknownRunnerPathIsRejected() {
		RunnerQueryHandler handler = new RunnerQueryHandler(
				new RunnerMonitor(new GitHubProxyHandler(Map.of()), 60_000L,
						RunnerMonitor.DEFAULT_FLEETS, RunnerMonitor.DEFAULT_PREFERENCE));

		Response response = handler.handle(new FakeSession(Map.of()),
				Method.GET, "/api/runners/bogus", ERROR);

		assertEquals(Response.Status.BAD_REQUEST, response.getStatus());
	}

	/** The diagnostics listing serialises each runner's name, status, busy, idle and labels. */
	@Test(timeout = 30000)
	public void listSerialisesEachRunner() throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		try {
			Response response = handlerOver(server).handle(new FakeSession(Map.of()),
					Method.GET, "/api/runners", ERROR);

			assertEquals(Response.Status.OK, response.getStatus());
			JsonNode body = body(response);
			assertTrue(body.path("ok").asBoolean());
			assertTrue(body.path("listed").asBoolean());
			JsonNode runners = body.path("runners");
			assertEquals(2, runners.size());
			JsonNode cuda = runners.get(0);
			assertEquals("cuda-1", cuda.path("name").asText());
			assertEquals("online", cuda.path("status").asText());
			assertFalse(cuda.path("busy").asBoolean());
			assertTrue(cuda.path("idle").asBoolean());
			assertEquals(List.of("self-hosted", "linux", "ar-ci-cuda", "ar-build"),
					toList(cuda.path("labels")));
		} finally {
			server.stop(0);
		}
	}

	/** Reads a {@link Response} body as JSON. */
	private static JsonNode body(Response response) throws IOException {
		try (InputStream data = response.getData()) {
			return MAPPER.readTree(new String(data.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	/** Collects a JSON string array into a {@link List}. */
	private static List<String> toList(JsonNode array) {
		List<String> values = new ArrayList<>();
		array.forEach(element -> values.add(element.asText()));
		return values;
	}

	/**
	 * A minimal {@link IHTTPSession} that serves only the query parameters and
	 * method the handler reads; every other member answers with an inert default.
	 */
	private static final class FakeSession implements IHTTPSession {

		/** The decoded multi-valued query parameters. */
		private final Map<String, List<String>> parameters;

		/**
		 * @param parameters the query parameters, by name
		 */
		private FakeSession(Map<String, List<String>> parameters) {
			this.parameters = parameters;
		}

		@Override
		public Map<String, List<String>> getParameters() {
			return parameters;
		}

		@Override
		public Method getMethod() {
			return Method.GET;
		}

		@Override
		public Map<String, String> getParms() {
			return Map.of();
		}

		@Override
		public Map<String, String> getHeaders() {
			return Map.of();
		}

		@Override
		public InputStream getInputStream() {
			return null;
		}

		@Override
		public void parseBody(Map<String, String> files) {
		}

		@Override
		public void execute() {
		}

		@Override
		public CookieHandler getCookies() {
			return null;
		}

		@Override
		public String getQueryParameterString() {
			return null;
		}

		@Override
		public String getUri() {
			return "/api/runners";
		}

		@Override
		public String getRemoteIpAddress() {
			return "127.0.0.1";
		}

		@Override
		public String getRemoteHostName() {
			return "localhost";
		}
	}
}
