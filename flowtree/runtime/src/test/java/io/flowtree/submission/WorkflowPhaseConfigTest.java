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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * same validation a live submission gets.
 *
 * <p>A workflow's {@code PHASE_CONFIGS} is a JSON string in YAML. Nothing
 * type-checks it, and the controller only sees it once a merge to master has
 * already triggered the job — so a mistake costs a wasted agent run and is
 * discovered from a Slack transcript rather than from CI.</p>
 *
 * <p>That is not hypothetical. The defect-hunt workflow shipped with
 * {@code {"primary": {"model": "opus"}}}, intending to pin the primary phase
 * to opus. The merge against the workspace default is field-by-field, so the
 * runner and provider stayed as the workspace had them and the phase launched
 * as {@code opencode/opus, provider=openrouter}. The opencode runner declares
 * an empty supported-model set, meaning "unconstrained", so
 * {@code validateModelForRunner} skipped it and nothing rejected the
 * submission; the phase then died in two seconds against an endpoint with no
 * such model.</p>
 *
 * <p>This runs the workflow's own JSON through {@link PhaseConfigResolver},
 * so the rules live in one place. Adding a runner constraint anywhere is
 * picked up here without touching this test.</p>
 */
public class WorkflowPhaseConfigTest extends TestSuiteBase {

	/** Matches {@code PHASE_CONFIGS: '<json>'} in a workflow file. */
	private static final Pattern PHASE_CONFIGS = Pattern.compile(
			"PHASE_CONFIGS:\\s*'(\\{.*?\\})'");

	/** Matches {@code DEFAULT_PHASE_CONFIG: '<json>'} in a workflow file. */
	private static final Pattern DEFAULT_PHASE_CONFIG = Pattern.compile(
			"DEFAULT_PHASE_CONFIG:\\s*'(\\{.*?\\})'");

	/**
	 * Returns the repository's workflow directory.
	 *
	 * @return the {@code .github/workflows} directory
	 */
	private static File workflowDir() {
		File dir = new File("../../.github/workflows");
		if (!dir.isDirectory()) dir = new File(".github/workflows");
		return dir;
	}

	/**
	 * Every phase config hard-coded in a workflow resolves without error.
	 *
	 * <p>The workspace bundle is deliberately left empty: a config that only
	 * validates because the workspace happens to supply the missing half is
	 * exactly the failure this exists to catch, and the workspace's
	 * configuration is not in the repository.</p>
	 *
	 * @throws IOException if a workflow file cannot be read
	 */
	@Test(timeout = 60000)
	public void workflowPhaseConfigsResolveCleanly() throws IOException {
		File dir = workflowDir();
		assertTrue("could not locate .github/workflows from "
				+ new File(".").getAbsolutePath(), dir.isDirectory());

		File[] files = dir.listFiles((d, n) -> n.endsWith(".yaml") || n.endsWith(".yml"));
		assertNotNull(files);

		List<String> checked = new ArrayList<>();
		for (File file : files) {
			String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			for (Pattern pattern : List.of(PHASE_CONFIGS, DEFAULT_PHASE_CONFIG)) {
				Matcher m = pattern.matcher(text);
				while (m.find()) {
					String json = m.group(1);
					String label = file.getName() + ": " + json;
					checked.add(label);

					String body = pattern == PHASE_CONFIGS
							? "{\"phaseConfigs\":" + json + "}"
							: "{\"defaultPhaseConfig\":" + json + "}";

					PhaseConfigBundle bundle = PhaseConfigResolver.bundleFromRequest(body);
					assertNotNull("workflow JSON did not parse — " + label, bundle);

					PhaseConfigResolver resolved = PhaseConfigResolver.resolve(
							bundle, PhaseConfigBundle.EMPTY, PhaseConfigBundle.EMPTY);
					Assert.assertNull("workflow phase config is rejected by the resolver — "
							+ label + " — " + resolved.error(), resolved.error());
				}
			}
		}

		assertFalse("no workflow declares a phase config; if that is deliberate,"
				+ " delete this test rather than letting it pass vacuously",
				checked.isEmpty());
	}

	/**
	 * A pinned model is accompanied by the runner that accepts it.
	 *
	 * <p>The resolver cannot catch this on its own. A runner with an empty
	 * supported-model set is treated as unconstrained, so pinning a model
	 * without a runner resolves cleanly and then fails at run time against
	 * whatever runner the workspace happened to configure. Requiring the two
	 * to travel together is what makes the intent explicit.</p>
	 *
	 * @throws IOException if a workflow file cannot be read
	 */
	@Test(timeout = 60000)
	public void pinnedModelAlsoPinsItsRunner() throws IOException {
		File dir = workflowDir();
		assertTrue(dir.isDirectory());
		File[] files = dir.listFiles((d, n) -> n.endsWith(".yaml") || n.endsWith(".yml"));
		assertNotNull(files);

		for (File file : files) {
			String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			for (Pattern pattern : List.of(PHASE_CONFIGS, DEFAULT_PHASE_CONFIG)) {
				Matcher m = pattern.matcher(text);
				while (m.find()) {
					String json = m.group(1);
					String body = pattern == PHASE_CONFIGS
							? "{\"phaseConfigs\":" + json + "}"
							: "{\"defaultPhaseConfig\":" + json + "}";
					PhaseConfigBundle bundle = PhaseConfigResolver.bundleFromRequest(body);

					List<PhaseConfig> configs = new ArrayList<>(bundle.phaseConfigs().values());
					if (bundle.defaultPhaseConfig() != null) {
						configs.add(bundle.defaultPhaseConfig());
					}

					for (PhaseConfig config : configs) {
						String model = config.model();
						if (model == null || model.isEmpty()) continue;
						assertTrue(file.getName() + " pins model '" + model
								+ "' without a runner; the workspace's runner would be"
								+ " used instead, which is how opencode was handed an"
								+ " Anthropic model name: " + json,
								config.runner() != null && !config.runner().isEmpty());
					}
				}
			}
		}
	}
}
