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

package io.flowtree.submission;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.flowtree.jobs.agent.Phase;
import io.flowtree.jobs.agent.PhaseConfig;
import io.flowtree.jobs.agent.PhaseConfigBundle;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Guard: phase configuration hard-coded in a CI workflow must survive the
 * same validation a live submission gets, and must survive parsing intact.
 *
 * <p>A workflow's {@code PHASE_CONFIGS} is a JSON string inside YAML. Nothing
 * type-checks it, and the controller only sees it once a merge to master has
 * already triggered the job — so a mistake costs a wasted agent run and is
 * discovered from a Slack transcript rather than from CI.</p>
 *
 * <p>That is not hypothetical. The defect-hunt workflow shipped with
 * {@code {"primary": {"model": "opus"}}}, intending to pin the primary phase
 * to opus. The merge against the workspace default is field-by-field, so the
 * runner and provider stayed as the workspace had them and the phase launched
 * as {@code opencode/opus, provider=openrouter}, dying in two seconds against
 * an endpoint with no such model.</p>
 *
 * <p><b>Parsing is checked separately from validation, because the parser is
 * lenient by design.</b> {@link PhaseConfigResolver#bundleFromRequest} never
 * fails: unparseable JSON, an unknown phase name, and a config of entirely
 * unrecognised fields all yield {@link PhaseConfigBundle#EMPTY} or silently
 * drop the entry. An empty bundle then resolves perfectly cleanly. So
 * "the resolver accepted it" says nothing on its own — a config with a
 * misspelled phase name is inert, would send the job back to the workspace
 * defaults exactly as the original bug did, and would sail through a check
 * that only asked whether resolution succeeded.</p>
 */
public class WorkflowPhaseConfigTest extends TestSuiteBase {

	/** Matches {@code PHASE_CONFIGS: '<json>'} in a workflow file. */
	private static final Pattern PHASE_CONFIGS = Pattern.compile(
			"PHASE_CONFIGS:\\s*'(\\{.*?\\})'");

	/** Matches {@code DEFAULT_PHASE_CONFIG: '<json>'} in a workflow file. */
	private static final Pattern DEFAULT_PHASE_CONFIG = Pattern.compile(
			"DEFAULT_PHASE_CONFIG:\\s*'(\\{.*?\\})'");

	/** Parses the workflow JSON strictly, unlike the resolver's lenient reader. */
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * One phase-config declaration found in a workflow file.
	 *
	 * @param file      the workflow file it came from, for failure messages
	 * @param json      the raw JSON as written in the workflow
	 * @param perPhase  {@code true} for {@code PHASE_CONFIGS}, {@code false}
	 *                  for {@code DEFAULT_PHASE_CONFIG}
	 */
	private record Declaration(String file, String json, boolean perPhase) {

		/** Returns the request body the controller would parse this from. */
		String requestBody() {
			return perPhase
					? "{\"phaseConfigs\":" + json + "}"
					: "{\"defaultPhaseConfig\":" + json + "}";
		}

		/** Returns the bundle the controller's lenient reader produces. */
		PhaseConfigBundle bundle() {
			return PhaseConfigResolver.bundleFromRequest(requestBody());
		}

		/** Returns a description of this declaration for a failure message. */
		String describe() {
			return file + ": " + json;
		}
	}

	/**
	 * Returns the repository's workflow directory.
	 *
	 * @return the {@code .github/workflows} directory
	 */
	private File workflowDir() {
		File dir = new File("../../.github/workflows");
		if (!dir.isDirectory()) dir = new File(".github/workflows");
		return dir;
	}

	/**
	 * Collects every phase-config declaration across the workflow files.
	 *
	 * @return the declarations, in file order
	 * @throws IOException if a workflow file cannot be read
	 */
	private List<Declaration> declarations() throws IOException {
		File dir = workflowDir();
		assertTrue("could not locate .github/workflows from "
				+ new File(".").getAbsolutePath(), dir.isDirectory());

		File[] files = dir.listFiles((d, n) -> n.endsWith(".yaml") || n.endsWith(".yml"));
		assertNotNull(files);

		List<Declaration> found = new ArrayList<>();
		for (File file : files) {
			String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			for (Pattern pattern : List.of(PHASE_CONFIGS, DEFAULT_PHASE_CONFIG)) {
				Matcher m = pattern.matcher(text);
				while (m.find()) {
					found.add(new Declaration(
							file.getName(), m.group(1), pattern == PHASE_CONFIGS));
				}
			}
		}
		return found;
	}

	/**
	 * Every declaration survives parsing with its content intact.
	 *
	 * <p>This is the check the resolver cannot provide. Its reader is lenient
	 * on purpose — a request carrying no phase configuration is normal — so it
	 * reports nothing for a declaration that parsed to nothing. Here that is
	 * the failure being hunted: a workflow that declares configuration and
	 * gets none is silently back on the workspace defaults.</p>
	 *
	 * @throws IOException if a workflow file cannot be read
	 */
	@Test(timeout = 60000)
	public void everyDeclarationParsesToTheConfigItDeclares() throws IOException {
		List<Declaration> declarations = declarations();
		assertFalse("no workflow declares a phase config; if that is deliberate,"
				+ " delete this test rather than letting it pass vacuously",
				declarations.isEmpty());

		for (Declaration declaration : declarations) {
			JsonNode declared;
			try {
				declared = MAPPER.readTree(declaration.json());
			} catch (IOException e) {
				throw new AssertionError("workflow JSON does not parse — "
						+ declaration.describe() + " — " + e.getMessage(), e);
			}
			assertTrue("workflow JSON is not an object — " + declaration.describe(),
					declared != null && declared.isObject());

			PhaseConfigBundle bundle = declaration.bundle();

			if (declaration.perPhase()) {
				Iterator<Map.Entry<String, JsonNode>> fields = declared.fields();
				assertTrue("phase config declares no phases — " + declaration.describe(),
						fields.hasNext());
				while (fields.hasNext()) {
					Map.Entry<String, JsonNode> entry = fields.next();
					String wireName = entry.getKey();

					// A phase name the reader does not recognise is dropped
					// without complaint, leaving an inert config.
					Phase phase;
					try {
						phase = Phase.fromWireName(wireName);
					} catch (IllegalArgumentException ex) {
						throw new AssertionError("'" + wireName + "' is not a phase name, so"
								+ " this config is silently ignored and the job falls back to"
								+ " the workspace defaults — " + declaration.describe());
					}

					PhaseConfig config = bundle.phaseConfigs().get(phase);
					assertTrue("phase '" + wireName + "' declares only fields the reader"
							+ " ignores, so it parses to nothing — " + declaration.describe(),
							config != null && !config.isEmpty());
				}
			} else {
				PhaseConfig config = bundle.defaultPhaseConfig();
				assertTrue("defaultPhaseConfig declares only fields the reader ignores,"
						+ " so it parses to nothing — " + declaration.describe(),
						config != null && !config.isEmpty());
			}
		}
	}

	/**
	 * Every declaration resolves without error.
	 *
	 * <p>Resolved against empty workstream and workspace bundles deliberately:
	 * a config that only validates because the workspace happens to supply the
	 * missing half is the failure this exists to catch, and the workspace's
	 * configuration is not in this repository.</p>
	 *
	 * @throws IOException if a workflow file cannot be read
	 */
	@Test(timeout = 60000)
	public void everyDeclarationResolvesCleanly() throws IOException {
		for (Declaration declaration : declarations()) {
			PhaseConfigResolver resolved = PhaseConfigResolver.resolve(
					declaration.bundle(), PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
			Assert.assertNull("workflow phase config is rejected by the resolver — "
					+ declaration.describe() + " — " + resolved.error(), resolved.error());
		}
	}

	/**
	 * A pinned model is accompanied by the runner that accepts it.
	 *
	 * <p>The resolver cannot catch this. A runner with an empty supported-model
	 * set is treated as unconstrained, so pinning a model without a runner
	 * resolves cleanly and then fails at run time against whatever runner the
	 * workspace happened to configure.</p>
	 *
	 * @throws IOException if a workflow file cannot be read
	 */
	@Test(timeout = 60000)
	public void pinnedModelAlsoPinsItsRunner() throws IOException {
		for (Declaration declaration : declarations()) {
			PhaseConfigBundle bundle = declaration.bundle();

			List<PhaseConfig> configs = new ArrayList<>(bundle.phaseConfigs().values());
			if (bundle.defaultPhaseConfig() != null) {
				configs.add(bundle.defaultPhaseConfig());
			}

			for (PhaseConfig config : configs) {
				String model = config.model();
				if (model == null || model.isEmpty()) continue;
				assertTrue(declaration.file() + " pins model '" + model
						+ "' without a runner; the workspace's runner would be used"
						+ " instead, which is how opencode was handed an Anthropic"
						+ " model name: " + declaration.json(),
						config.runner() != null && !config.runner().isEmpty());
			}
		}
	}
}
