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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Generic schema-divergence test that fails whenever the set of
 * operator-settable workstream config fields diverges between the Python MCP
 * tool layer and the Java controller handler — in either direction.
 *
 * <h2>How it works (no hard-coded field names)</h2>
 * <p>Both sides are derived dynamically at test time:</p>
 * <ul>
 *   <li><b>MCP side — Python payload keys</b>: {@code payload["X"] = ...}
 *       assignments found in the body of {@code workstream_register} /
 *       {@code workstream_update_config} in
 *       {@code tools/mcp/manager/server.py}. These are the camelCase field
 *       names the Python tool actually forwards to the controller REST
 *       endpoint.</li>
 *   <li><b>MCP side — typed parameters</b>: the Python function signature
 *       parameters, discovered by
 *       {@link McpToolDiscovery#discoverToolParameters}. These are the
 *       snake_case names exposed to MCP clients in the tool schema.</li>
 *   <li><b>Controller side — Java body reads</b>: camelCase field names
 *       found in {@code JsonFieldExtractor.extract*(body, "X")} and
 *       {@code JsonFieldExtractor.hasField(body, "X")} calls anywhere in
 *       {@code WorkstreamRegistrationHandler.java}, plus field names found
 *       in {@code root.has("X")} calls inside
 *       {@code PhaseConfigResolver.applyToWorkstream} (which reads
 *       {@code defaultPhaseConfig} and {@code phaseConfigs} via Jackson
 *       rather than {@code JsonFieldExtractor}).</li>
 * </ul>
 *
 * <h2>Three checks per tool</h2>
 * <dl>
 *   <dt>Check A — payload → typed params</dt>
 *   <dd>Every camelCase payload key must have a corresponding snake_case
 *       typed MCP parameter. This is the primary check for the recurring
 *       failure mode where a field is added to the controller and forwarded
 *       in the Python payload but the MCP parameter declaration is omitted,
 *       leaving the feature unreachable by operators.</dd>
 *
 *   <dt>Check B — typed params (known to controller) → payload</dt>
 *   <dd>For every typed MCP parameter whose camelCase form appears in the
 *       Java handler body reads (a field the controller actively consumes),
 *       that camelCase form must also appear in the Python payload dict.
 *       This catches a parameter that is declared in the MCP schema and
 *       consumed by the controller but is never forwarded from MCP to the
 *       controller — an MCP client can set it but the controller never
 *       receives the value.
 *       MCP-layer-only parameters ({@code plan_content}, {@code plan_path},
 *       etc.) are automatically excluded because the Java handler never reads
 *       them, so they do not appear in the Java reads set and fall outside
 *       the scope of this check without any hard-coded exclusion list.</dd>
 *
 *   <dt>Check C — payload → Java reads</dt>
 *   <dd>Every camelCase key in the Python payload dict must be consumed by
 *       the Java handler (via a {@code JsonFieldExtractor} call or via
 *       {@code PhaseConfigResolver}). Catches fields forwarded by the MCP
 *       layer that the controller silently ignores.</dd>
 * </dl>
 *
 * <h2>Why the existing {@code McpToolWorkstreamConfigSurfaceTest} could not
 * catch {@code dispatch_capable} being missing</h2>
 * <p>That test maintains a hard-coded set
 * {@code REQUIRED_ON_BOTH_REGISTER_AND_UPDATE}. Adding a new controller
 * field requires a human to also add its name to that set. When that step
 * is skipped (as happened five times with {@code phase_configs},
 * {@code workspace_update_config}, {@code retrospective_enabled},
 * {@code completion_listeners}, and {@code dispatch_capable}), the test
 * passes while the feature is unreachable. This test eliminates the
 * hard-coded set: both sides are derived from the live source, so no
 * manual list update is ever needed.</p>
 *
 * <h2>Known limitation</h2>
 * <p>This test cannot detect a controller field added with no corresponding
 * Python payload forwarding AND no MCP parameter — all three sides would
 * be missing simultaneously. A shared-schema source of truth (e.g. a
 * constant listing all operator-settable fields shared between Java and
 * Python) would close this gap and is the recommended v2 enhancement. The
 * test does detect the vastly more common failure: field added to controller
 * + forwarded in Python payload, but MCP parameter omitted.</p>
 */
public class WorkstreamConfigSchemaAlignmentTest extends TestSuiteBase {

	/** Matches {@code payload["X"] = ...} subscript-assignment in Python function bodies. */
	private static final Pattern PY_PAYLOAD_KEY =
			Pattern.compile("payload\\[\"([^\"]+)\"\\]\\s*=");

	/**
	 * Matches the start of a dict-literal payload initialisation, e.g.
	 * {@code payload = \{"defaultBranch": ...}} (workstream_register uses this
	 * pattern for the first field rather than a subscript assignment).
	 */
	private static final Pattern PY_PAYLOAD_LITERAL_START =
			Pattern.compile("\\bpayload\\s*=\\s*\\{");

	/**
	 * Matches a quoted key in a Python dict literal: {@code "key":}.
	 * Used to extract field names from dict-literal payload initialisations.
	 */
	private static final Pattern PY_DICT_KEY =
			Pattern.compile("\"([^\"]+)\"\\s*:");

	/** Matches {@code JsonFieldExtractor.extractXxx(body, "X")} in Java source. */
	private static final Pattern JAVA_EXTRACT =
			Pattern.compile("JsonFieldExtractor\\.extract\\w+\\s*\\(body,\\s*\"([^\"]+)\"\\)");

	/** Matches {@code JsonFieldExtractor.hasField(body, "X")} in Java source. */
	private static final Pattern JAVA_HAS_FIELD =
			Pattern.compile("JsonFieldExtractor\\.hasField\\s*\\(body,\\s*\"([^\"]+)\"\\)");

	/** Matches {@code root.has("X")} in PhaseConfigResolver (Jackson JsonNode API). */
	private static final Pattern RESOLVER_HAS =
			Pattern.compile("root\\.has\\(\"([^\"]+)\"\\)");

	// -------------------------------------------------------------------------
	// Tests
	// -------------------------------------------------------------------------

	/**
	 * Asserts that {@code workstream_register}'s Python payload keys,
	 * typed MCP parameters, and Java handler reads are all mutually
	 * consistent.
	 */
	@Test(timeout = 30000)
	public void registerPayloadParamsAndHandlerAreAligned() {
		Path serverPy = McpToolDiscovery.locateManagerServerPy();
		Path handlerJava = locateRegistrationHandler();
		Path resolverJava = locatePhaseConfigResolver();

		assertNotNull(
				"Cannot locate tools/mcp/manager/server.py from working directory "
						+ Path.of("").toAbsolutePath() + ". Skipping schema-alignment check.",
				serverPy);
		assertNotNull(
				"Cannot locate WorkstreamRegistrationHandler.java from working directory "
						+ Path.of("").toAbsolutePath() + ". Skipping schema-alignment check.",
				handlerJava);

		Set<String> payloadKeys = pythonPayloadKeys(serverPy, "workstream_register");
		Set<String> mcpParams = new HashSet<>(
				McpToolDiscovery.discoverToolParameters(serverPy, "workstream_register"));
		Set<String> javaReads = javaHandlerReads(handlerJava);
		if (resolverJava != null) {
			javaReads.addAll(phaseResolverFields(resolverJava));
		}

		assertFalse(
				"workstream_register: Python payload dict is empty — could not parse "
						+ "payload[\"X\"] = ... assignments; the function body may have moved "
						+ "out of server.py or the payload pattern has changed.",
				payloadKeys.isEmpty());
		assertFalse(
				"workstream_register: MCP typed parameters are empty — the function "
						+ "signature could not be parsed from server.py.",
				mcpParams.isEmpty());

		checkPayloadToParams(payloadKeys, mcpParams, "workstream_register");
		checkParamsToPayload(mcpParams, payloadKeys, javaReads, "workstream_register");
		checkPayloadToJavaReads(payloadKeys, javaReads, "workstream_register");
	}

	/**
	 * Asserts that {@code workstream_update_config}'s Python payload keys,
	 * typed MCP parameters, and Java handler reads are all mutually
	 * consistent.
	 */
	@Test(timeout = 30000)
	public void updateConfigPayloadParamsAndHandlerAreAligned() {
		Path serverPy = McpToolDiscovery.locateManagerServerPy();
		Path handlerJava = locateRegistrationHandler();
		Path resolverJava = locatePhaseConfigResolver();

		assertNotNull(
				"Cannot locate tools/mcp/manager/server.py from working directory "
						+ Path.of("").toAbsolutePath() + ". Skipping schema-alignment check.",
				serverPy);
		assertNotNull(
				"Cannot locate WorkstreamRegistrationHandler.java from working directory "
						+ Path.of("").toAbsolutePath() + ". Skipping schema-alignment check.",
				handlerJava);

		Set<String> payloadKeys = pythonPayloadKeys(serverPy, "workstream_update_config");
		Set<String> mcpParams = new HashSet<>(
				McpToolDiscovery.discoverToolParameters(serverPy, "workstream_update_config"));
		Set<String> javaReads = javaHandlerReads(handlerJava);
		if (resolverJava != null) {
			javaReads.addAll(phaseResolverFields(resolverJava));
		}

		assertFalse(
				"workstream_update_config: Python payload dict is empty — could not parse "
						+ "payload[\"X\"] = ... assignments.",
				payloadKeys.isEmpty());
		assertFalse(
				"workstream_update_config: MCP typed parameters are empty.",
				mcpParams.isEmpty());

		checkPayloadToParams(payloadKeys, mcpParams, "workstream_update_config");
		checkParamsToPayload(mcpParams, payloadKeys, javaReads, "workstream_update_config");
		checkPayloadToJavaReads(payloadKeys, javaReads, "workstream_update_config");
	}

	// -------------------------------------------------------------------------
	// Checks
	// -------------------------------------------------------------------------

	/**
	 * Check A: every camelCase payload key's snake_case form must exist as a
	 * typed MCP parameter.
	 *
	 * <p>This is the primary check for the recurring failure mode: a field is
	 * added to the Java handler and forwarded in the Python payload dict, but
	 * the corresponding MCP parameter declaration is omitted, leaving the
	 * feature unreachable from MCP clients. Any item in the failure set is a
	 * field the controller will read but that no MCP caller can supply.</p>
	 */
	private void checkPayloadToParams(Set<String> payloadKeys,
	                                  Set<String> mcpParams,
	                                  String toolName) {
		Set<String> missing = payloadKeys.stream()
				.map(WorkstreamConfigSchemaAlignmentTest::camelToSnake)
				.filter(snake -> !mcpParams.contains(snake))
				.collect(Collectors.toCollection(TreeSet::new));

		assertTrue(
				toolName + " — payload fields forwarded to the controller with no"
						+ " corresponding typed MCP parameter (the feature is unreachable"
						+ " from MCP clients). Fix: add each missing name as a typed"
						+ " parameter in the Python function signature and confirm it is"
						+ " documented in the docstring's Args section. Missing: " + missing,
				missing.isEmpty());
	}

	/**
	 * Check B: for every typed MCP parameter whose camelCase form is consumed
	 * by the Java handler, that camelCase form must also appear in the Python
	 * payload dict.
	 *
	 * <p>Only parameters that the Java handler actively reads are checked
	 * (those whose camelCase form appears in the {@code javaReads} set). This
	 * naturally excludes MCP-layer-only parameters such as {@code plan_content}
	 * and {@code plan_instructions}, which are consumed before the payload is
	 * assembled and never appear in a {@code JsonFieldExtractor} call — they
	 * fall outside this check without any hard-coded exclusion list. It also
	 * naturally excludes URL-path parameters such as {@code workstream_id},
	 * which flow into the request URL rather than the body and therefore have
	 * no Java body read.</p>
	 *
	 * <p>A failure here indicates an MCP parameter that operators can set and
	 * the controller knows about, but the Python layer never forwards — the
	 * operator's value is silently dropped.</p>
	 */
	private void checkParamsToPayload(Set<String> mcpParams,
	                                  Set<String> payloadKeys,
	                                  Set<String> javaReads,
	                                  String toolName) {
		if (javaReads.isEmpty()) return;

		// Only check params the controller actually reads; MCP-layer-only and
		// URL-path params are automatically excluded because they have no
		// corresponding Java body-read entry.
		Set<String> missing = new TreeSet<>();
		for (String param : mcpParams) {
			String camel = snakeToCamel(param);
			if (javaReads.contains(camel) && !payloadKeys.contains(camel)) {
				missing.add(param);
			}
		}

		assertTrue(
				toolName + " — typed MCP parameters that the Java handler reads but the"
						+ " Python payload does not forward. The operator can set these"
						+ " values via MCP but the controller will never receive them."
						+ " Fix: add payload[\"<camelCase>\"] = <param> inside the Python"
						+ " function body. Missing from payload: " + missing,
				missing.isEmpty());
	}

	/**
	 * Check C: every Python payload key must be consumed by the Java handler
	 * or by {@code PhaseConfigResolver}.
	 *
	 * <p>A failure here indicates a field the MCP layer forwards to the
	 * controller endpoint that the controller does not read — either dead
	 * code in the Python layer or a Java handler regression that removed the
	 * corresponding read.</p>
	 */
	private void checkPayloadToJavaReads(Set<String> payloadKeys,
	                                     Set<String> javaReads,
	                                     String toolName) {
		if (javaReads.isEmpty()) return;

		Set<String> notConsumed = new TreeSet<>(payloadKeys);
		notConsumed.removeAll(javaReads);

		assertTrue(
				toolName + " — Python payload keys forwarded to the controller but not"
						+ " read by the Java handler (wasted forwarding or a handler regression"
						+ " that removed the corresponding extract call). Fix: either add"
						+ " the missing JsonFieldExtractor call in the Java handler or remove"
						+ " the payload key from the Python body. Not consumed: " + notConsumed,
				notConsumed.isEmpty());
	}

	// -------------------------------------------------------------------------
	// Source parsers
	// -------------------------------------------------------------------------

	/**
	 * Extracts the camelCase keys from all payload assignments in the named
	 * Python function's body. Scanning begins at the {@code def <funcName>(}
	 * line and ends at the next top-level decorator ({@code @mcp.tool()}) or
	 * {@code def } / {@code class } at column 0.
	 *
	 * <p>Two payload shapes are handled:</p>
	 * <ul>
	 *   <li>{@code payload["X"] = ...} — subscript assignment (most fields)</li>
	 *   <li>{@code payload = \{"X": ...\}} — dict-literal initialisation
	 *       (e.g. {@code workstream_register} initialises with
	 *       {@code \{"defaultBranch": default_branch\}})</li>
	 * </ul>
	 */
	private static Set<String> pythonPayloadKeys(Path serverPy, String funcName) {
		List<String> lines = readLines(serverPy);
		if (lines.isEmpty()) return Collections.emptySet();

		Pattern funcStart =
				Pattern.compile("^def\\s+" + Pattern.quote(funcName) + "\\s*\\(");
		Pattern topLevel =
				Pattern.compile("^(@mcp\\.tool\\(\\)|def\\s+\\w+|class\\s+\\w+)");

		Set<String> keys = new LinkedHashSet<>();
		boolean inFunc = false;
		boolean inDictLiteral = false;

		for (String line : lines) {
			if (!inFunc) {
				if (funcStart.matcher(line).find()) {
					inFunc = true;
				}
				continue;
			}
			// Stop at the next top-level definition (unindented)
			if (!line.isEmpty() && !Character.isWhitespace(line.charAt(0))
					&& topLevel.matcher(line).find()) {
				break;
			}
			// Subscript assignment: payload["X"] = ...
			Matcher m = PY_PAYLOAD_KEY.matcher(line);
			if (m.find()) {
				keys.add(m.group(1));
			}
			// Dict-literal initialisation: payload = {"X": ..., "Y": ...}
			// workstream_register uses this for the first field.
			if (!inDictLiteral && PY_PAYLOAD_LITERAL_START.matcher(line).find()) {
				inDictLiteral = true;
			}
			if (inDictLiteral) {
				Matcher dk = PY_DICT_KEY.matcher(line);
				while (dk.find()) {
					keys.add(dk.group(1));
				}
				// TODO(review): line.contains("}") closes the literal on the first "}" seen, even if
				// the dict value is itself a nested dict. Track brace depth instead to be safe.
				// If the opening brace is closed on this line, the literal ends.
				if (line.contains("}")) {
					inDictLiteral = false;
				}
			}
		}
		return keys;
	}

	/**
	 * Scans the entire {@code WorkstreamRegistrationHandler.java} file for
	 * {@code JsonFieldExtractor.extract*(body, "X")} and
	 * {@code JsonFieldExtractor.hasField(body, "X")} calls (including those
	 * in private helper methods such as {@code extractCompletionListeners})
	 * and returns the set of camelCase field names found.
	 */
	private static Set<String> javaHandlerReads(Path handlerJava) {
		List<String> lines = readLines(handlerJava);
		if (lines.isEmpty()) return Collections.emptySet();

		Set<String> fields = new LinkedHashSet<>();
		for (String line : lines) {
			Matcher m1 = JAVA_EXTRACT.matcher(line);
			while (m1.find()) fields.add(m1.group(1));

			Matcher m2 = JAVA_HAS_FIELD.matcher(line);
			while (m2.find()) fields.add(m2.group(1));
		}
		return fields;
	}

	/**
	 * Scans {@code PhaseConfigResolver.java} for {@code root.has("X")} calls
	 * inside {@code applyToWorkstream} and returns the camelCase field names.
	 * These fields ({@code defaultPhaseConfig}, {@code phaseConfigs}) are read
	 * via Jackson's {@code JsonNode} API rather than via
	 * {@code JsonFieldExtractor}, so they do not appear in
	 * {@link #javaHandlerReads}.
	 */
	private static Set<String> phaseResolverFields(Path resolverJava) {
		List<String> lines = readLines(resolverJava);
		if (lines.isEmpty()) return Collections.emptySet();

		Pattern methodStart = Pattern.compile("\\bapplyToWorkstream\\s*\\(");
		Set<String> fields = new LinkedHashSet<>();
		boolean inMethod = false;
		int braceDepth = 0;
		boolean bodyStarted = false;

		for (String line : lines) {
			if (!inMethod) {
				if (methodStart.matcher(line).find()) {
					inMethod = true;
					braceDepth = 0;
					bodyStarted = false;
				}
				continue;
			}
			for (char c : line.toCharArray()) {
				if (c == '{') { braceDepth++; bodyStarted = true; }
				else if (c == '}') braceDepth--;
			}
			if (bodyStarted && braceDepth == 0) break;

			Matcher m = RESOLVER_HAS.matcher(line);
			while (m.find()) fields.add(m.group(1));
		}
		return fields;
	}

	// -------------------------------------------------------------------------
	// camelCase / snake_case conversions
	// -------------------------------------------------------------------------

	/**
	 * Converts a camelCase identifier to snake_case.
	 * {@code defaultBranch} → {@code default_branch}.
	 */
	static String camelToSnake(String camel) {
		return camel.replaceAll("([A-Z])", "_$1").toLowerCase();
	}

	/**
	 * Converts a snake_case identifier to camelCase.
	 * {@code default_branch} → {@code defaultBranch}.
	 */
	static String snakeToCamel(String snake) {
		StringBuilder sb = new StringBuilder();
		boolean nextUpper = false;
		for (char c : snake.toCharArray()) {
			if (c == '_') {
				nextUpper = true;
			} else if (nextUpper) {
				sb.append(Character.toUpperCase(c));
				nextUpper = false;
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	// -------------------------------------------------------------------------
	// File locators
	// -------------------------------------------------------------------------

	/** Reads all UTF-8 lines from a file, returning empty list on any error. */
	private static List<String> readLines(Path path) {
		if (path == null || !Files.isRegularFile(path)) return Collections.emptyList();
		try {
			return Files.readAllLines(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return Collections.emptyList();
		}
	}

	/**
	 * Walks up from the current working directory looking for
	 * {@code WorkstreamRegistrationHandler.java}.
	 */
	private static Path locateRegistrationHandler() {
		Path cwd = Path.of("").toAbsolutePath();
		String rel = "flowtree/runtime/src/main/java/io/flowtree/api/"
				+ "WorkstreamRegistrationHandler.java";
		for (int i = 0; i < 5 && cwd != null; i++) {
			Path candidate = cwd.resolve(rel);
			if (Files.isRegularFile(candidate)) return candidate;
			cwd = cwd.getParent();
		}
		return null;
	}

	/**
	 * Walks up from the current working directory looking for
	 * {@code PhaseConfigResolver.java}.
	 */
	private static Path locatePhaseConfigResolver() {
		Path cwd = Path.of("").toAbsolutePath();
		String rel = "flowtree/runtime/src/main/java/io/flowtree/submission/"
				+ "PhaseConfigResolver.java";
		for (int i = 0; i < 5 && cwd != null; i++) {
			Path candidate = cwd.resolve(rel);
			if (Files.isRegularFile(candidate)) return candidate;
			cwd = cwd.getParent();
		}
		return null;
	}
}
