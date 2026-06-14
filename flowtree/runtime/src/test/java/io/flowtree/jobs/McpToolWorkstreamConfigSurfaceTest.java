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

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Exhaustive assertion that the live {@code workstream_register} and
 * {@code workstream_update_config} MCP tool signatures in
 * {@code tools/mcp/manager/server.py} declare every controller-supported
 * workstream config field that is supposed to be settable via MCP.
 *
 * <p>This test is the strengthening of the
 * {@code McpToolDiscoveryTest.managerRegisterAndUpdateConfigHaveCompletionListeners}
 * assertion. The historical failure was that
 * {@code McpToolDiscoveryTest.managerAllExpectedToolsAreRegisteredInServerPy}
 * and {@code managerToolParametersAreProperlyDeclaredInSignatures} used a
 * bare relative path ({@code Path.of("tools/mcp/manager/server.py")}) and
 * an early-return on file-not-found. Maven Surefire runs tests with the
 * working directory set to the module's basedir ({@code flowtree/runtime/}),
 * not the repo root, so the file did not resolve and the tests passed
 * without asserting anything. Every {@code assertTrue} inside those
 * methods was a no-op — a green proxy for broken reality.</p>
 *
 * <p>This test locates the file by walking up ancestor directories from
 * the current working directory (the same pattern as
 * {@code McpConfigBuilderTest.locateManagerServerPy}). When the file is
 * not found, the test fails with a clear message naming the working
 * directory, rather than silently passing. The test then asserts that
 * every name in {@link #REQUIRED_ON_BOTH_REGISTER_AND_UPDATE} and
 * {@link #REQUIRED_ON_REGISTER_ONLY} is declared in the tool signature,
 * with a diff message that names exactly which controller field has no
 * MCP exposure. The set is updated whenever a new controller field is
 * added; a new controller field that is missing from this set is a
 * code-review defect, not a test failure.</p>
 *
 * <p>History: this test was added in response to a recurring failure mode
 * where a controller-side field was added without a corresponding
 * {@code workstream_register} / {@code workstream_update_config} MCP
 * parameter, leaving the field unreachable from MCP clients. The
 * completion-listener feature is the most recent example: the controller
 * engine shipped but the MCP wiring lagged. The
 * {@code completion_listeners} entry in
 * {@link #REQUIRED_ON_BOTH_REGISTER_AND_UPDATE} is the specific
 * regression guard for that case.</p>
 */
public class McpToolWorkstreamConfigSurfaceTest extends TestSuiteBase {

	/**
	 * Controller-supported workstream config fields that must be settable from
	 * BOTH {@code workstream_register} and {@code workstream_update_config} in
	 * the Python tool signature. The set is exhaustive for fields that the
	 * controller's update path actually accepts (see
	 * {@code WorkstreamRegistrationHandler.handleUpdate}, which reads and
	 * forwards each of these to the workstream's setter on update). The
	 * test fails if any name in this set is absent from either tool's
	 * signature.
	 *
	 * <p>Names mirror the controller's {@code Workstream} setters (see
	 * {@code flowtree/runtime/src/main/java/io/flowtree/workstream/Workstream.java})
	 * and the body code that reads them in
	 * {@code flowtree/runtime/src/main/java/io/flowtree/api/WorkstreamRegistrationHandler.java}.</p>
	 */
	private static final Set<String> REQUIRED_ON_BOTH_REGISTER_AND_UPDATE = new HashSet<>(Arrays.asList(
		"default_branch",
		"base_branch",
		"repo_url",
		"planning_document",
		"channel_name",
		"required_labels",
		"dependent_repos",
		"completion_listeners",
		"default_phase_config",
		"phase_configs"
	));

	/**
	 * Controller-supported workstream config fields that must be settable from
	 * {@code workstream_register} but are intentionally absent from
	 * {@code workstream_update_config}. The current entry — {@code workspace_id}
	 * — is set at registration time and is not changeable on update: the
	 * controller's update handler does not read it from the request body and
	 * the routing {@code SlackNotifier} is bound to the workstream at
	 * registration. A test that demands the field appear on the update tool
	 * would either spuriously fail or push an unsupported mutation.
	 *
	 * <p>When a new field is added to the controller that is supposed to be
	 * changeable on update, it belongs in
	 * {@link #REQUIRED_ON_BOTH_REGISTER_AND_UPDATE}, not here. When a new
	 * field is added that is intentionally registration-only, it belongs
	 * here.</p>
	 */
	private static final Set<String> REQUIRED_ON_REGISTER_ONLY = new HashSet<>(Arrays.asList(
		"workspace_id"
	));

	/**
	 * Asserts that {@code workstream_register} declares every entry in
	 * {@link #REQUIRED_ON_BOTH_REGISTER_AND_UPDATE} and
	 * {@link #REQUIRED_ON_REGISTER_ONLY} in its Python function signature.
	 *
	 * <p>When this test fails, the failure message names exactly which
	 * controller field has no MCP exposure — e.g. {@code Missing (controller
	 * field has no MCP exposure): [completion_listeners]}. The fix is to
	 * add the missing parameter to the {@code workstream_register} signature
	 * in {@code tools/mcp/manager/server.py} and the corresponding parse /
	 * payload-forward block in the function body.</p>
	 */
	@Test(timeout = 30000)
	public void workstreamRegisterExposesAllRequiredWorkstreamConfigParams() {
		Path serverFile = locateManagerServerPy();
		assertNotNull(
			"Could not locate tools/mcp/manager/server.py from working directory " +
				Path.of("").toAbsolutePath() +
				". The workstream-register signature check cannot run without it.",
			serverFile);

		List<String> registerParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_register");
		Set<String> required = new HashSet<>(REQUIRED_ON_BOTH_REGISTER_AND_UPDATE);
		required.addAll(REQUIRED_ON_REGISTER_ONLY);
		Set<String> missing = new TreeSet<>(required);
		missing.removeAll(registerParams);
		assertTrue(
			"workstream_register must declare every required workstream config param." +
				" Missing (controller field has no MCP exposure): " + missing,
			missing.isEmpty());
	}

	/**
	 * Asserts that {@code workstream_update_config} declares every entry in
	 * {@link #REQUIRED_ON_BOTH_REGISTER_AND_UPDATE} in its Python function
	 * signature.
	 *
	 * <p>The set excludes registration-only fields
	 * ({@link #REQUIRED_ON_REGISTER_ONLY}) because the controller's update
	 * handler does not accept them — demanding them here would either
	 * spuriously fail or push an unsupported mutation.</p>
	 */
	@Test(timeout = 30000)
	public void workstreamUpdateConfigExposesAllRequiredWorkstreamConfigParams() {
		Path serverFile = locateManagerServerPy();
		assertNotNull(
			"Could not locate tools/mcp/manager/server.py from working directory " +
				Path.of("").toAbsolutePath() +
				". The workstream-update-config signature check cannot run without it.",
			serverFile);

		List<String> updateParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_update_config");
		Set<String> missing = new TreeSet<>(REQUIRED_ON_BOTH_REGISTER_AND_UPDATE);
		missing.removeAll(updateParams);
		assertTrue(
			"workstream_update_config must declare every required workstream config param." +
				" Missing (controller field has no MCP exposure): " + missing,
			missing.isEmpty());
	}

	/**
	 * Asserts that {@code completion_listeners} is declared on both
	 * {@code workstream_register} and {@code workstream_update_config}.
	 *
	 * <p>This is a focused assertion named after the specific
	 * completion-listener feature so that a regression points the operator
	 * directly at the param that needs to be added, rather than hiding the
	 * failure inside a larger set-difference message. The completion-listener
	 * feature shipped on the controller without the corresponding MCP wiring
	 * multiple times across sessions; this test is the tripwire that ensures
	 * the param is in the live signature, not just planned in a design
	 * document.</p>
	 */
	@Test(timeout = 30000)
	public void completionListenersParamIsDeclaredOnBothRegisterAndUpdateTools() {
		Path serverFile = locateManagerServerPy();
		assertNotNull(
			"Could not locate tools/mcp/manager/server.py from working directory " +
				Path.of("").toAbsolutePath() +
				". The completion-listener param check cannot run without it.",
			serverFile);

		List<String> registerParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_register");
		assertTrue(
			"workstream_register must declare the completion_listeners parameter so" +
				" operators can configure a workstream's completion-listener list via MCP.",
			registerParams.contains("completion_listeners"));

		List<String> updateParams =
			McpToolDiscovery.discoverToolParameters(serverFile, "workstream_update_config");
		assertTrue(
			"workstream_update_config must declare the completion_listeners parameter so" +
				" operators can update a workstream's completion-listener list via MCP.",
			updateParams.contains("completion_listeners"));
	}

	/**
	 * Walks up from the current working directory looking for
	 * {@code tools/mcp/manager/server.py}. Maven Surefire defaults the working
	 * directory to the module's basedir ({@code flowtree/runtime/}) so a
	 * single relative path like {@code tools/...} only resolves when the
	 * test is launched from the project root.
	 *
	 * <p>Delegates to
	 * {@link io.flowtree.jobs.McpToolDiscovery#locateManagerServerPy()} so
	 * the resolution path stays in a single place; a copy of this helper
	 * still exists in {@code McpConfigBuilderTest} because that test
	 * file is on the base branch and the agent write-lock prevents
	 * editing it. Once the duplication can be removed the
	 * {@code McpConfigBuilderTest} caller can switch to the same
	 * production helper without changing the resolution semantics.</p>
	 *
	 * @return the resolved path, or {@code null} if not found within the
	 *         helper's search budget
	 */
	private static Path locateManagerServerPy() {
		return McpToolDiscovery.locateManagerServerPy();
	}
}
