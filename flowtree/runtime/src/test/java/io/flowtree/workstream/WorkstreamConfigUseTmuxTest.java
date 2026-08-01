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

package io.flowtree.workstream;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@code useTmux} field on
 * {@link WorkstreamConfig.WorkstreamEntry}: YAML round-trip and
 * propagation to the runtime {@link Workstream} via
 * {@link WorkstreamConfig.WorkstreamEntry#toWorkstream()}.
 *
 * <p>The YAML serializes the field as {@code useTmux: true} when the
 * workstream is opted into tmux-backed launches and omits the key when
 * it is not (the inert default). The {@code syncFromWorkstreams} path
 * on {@link WorkstreamConfig} reads the same field back from the live
 * {@link Workstream}, so a runtime flip of the flag persists on the
 * next YAML write.</p>
 */
public class WorkstreamConfigUseTmuxTest extends TestSuiteBase {

	/**
	 * An opt-in workstream ({@code useTmux: true}) round-trips through
	 * YAML save-and-reload, propagates to the runtime {@link Workstream}
	 * on {@code toWorkstream()}, and survives the save-and-reload cycle.
	 */
	@Test(timeout = 10000)
	public void optInWorkstreamRoundTripsThroughYaml() throws IOException {
		String yaml = "workstreams:\n"
				+ "  - workstreamId: \"ws-tmux\"\n"
				+ "    channelId: \"C-TMUX\"\n"
				+ "    channelName: \"#tmux\"\n"
				+ "    defaultBranch: \"feature/tmux\"\n"
				+ "    useTmux: true\n";
		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
		WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
		assertTrue("YAML-loaded entry must have useTmux=true",
			entry.isUseTmux());

		Workstream ws = entry.toWorkstream();
		assertTrue("toWorkstream() must propagate useTmux=true to"
			+ " the runtime Workstream", ws.isUseTmux());

		File tempFile = File.createTempFile("ws-tmux-true", ".yaml");
		tempFile.deleteOnExit();
		config.saveToYaml(tempFile);
		WorkstreamConfig reloaded = WorkstreamConfig.loadFromYaml(tempFile);
		assertTrue("Reloaded entry must still have useTmux=true",
			reloaded.getWorkstreams().get(0).isUseTmux());
	}

	/**
	 * A workstream that does not declare {@code useTmux} in YAML loads
	 * with the field at its default {@code false}. The YAML serialization
	 * also omits the key when the field is false, so a save-and-reload
	 * cycle does not introduce the key for inert workstreams.
	 */
	@Test(timeout = 10000)
	public void defaultIsFalseAndYamlOmitsTheKeyWhenFalse() throws IOException {
		String yaml = "workstreams:\n"
				+ "  - workstreamId: \"ws-inert\"\n"
				+ "    channelId: \"C-I\"\n"
				+ "    channelName: \"#inert\"\n"
				+ "    defaultBranch: \"feature/inert\"\n";
		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
		WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
		assertFalse("YAML-loaded entry must default to useTmux=false",
			entry.isUseTmux());
		Workstream ws = entry.toWorkstream();
		assertFalse("toWorkstream() must propagate the default false to the"
			+ " runtime Workstream", ws.isUseTmux());

		File tempFile = File.createTempFile("ws-tmux-false", ".yaml");
		tempFile.deleteOnExit();
		config.saveToYaml(tempFile);
		String written = new String(Files.readAllBytes(tempFile.toPath()));
		assertFalse("Saved YAML must NOT contain 'useTmux:' when"
			+ " the flag is false (NON_DEFAULT serialisation): " + written,
			written.contains("useTmux:"));
	}

	/**
	 * The {@code syncFromWorkstreams} path (used by the controller when
	 * persisting in-memory state back to YAML) must copy the runtime
	 * flag onto the existing entry. Otherwise a runtime flip on the
	 * live workstream would not survive a controller restart.
	 */
	@Test(timeout = 10000)
	public void syncFromWorkstreamsPersistsRuntimeFlip() throws IOException {
		String yaml = "workstreams:\n"
				+ "  - workstreamId: \"ws-flip\"\n"
				+ "    channelId: \"C-F\"\n"
				+ "    channelName: \"#flip\"\n"
				+ "    defaultBranch: \"feature/flip\"\n";
		WorkstreamConfig config = WorkstreamConfig.loadFromYamlString(yaml);
		WorkstreamConfig.WorkstreamEntry entry = config.getWorkstreams().get(0);
		Workstream live = entry.toWorkstream();
		assertFalse("Pre-condition: default false", live.isUseTmux());

		live.setUseTmux(true);

		config.syncFromWorkstreams(Arrays.asList(live));

		File tempFile = File.createTempFile("ws-tmux-sync", ".yaml");
		tempFile.deleteOnExit();
		config.saveToYaml(tempFile);
		String written = new String(Files.readAllBytes(tempFile.toPath()));
		assertTrue("syncFromWorkstreams must persist useTmux=true after"
			+ " a runtime flip; saved YAML: " + written,
			written.contains("useTmux: true"));
	}
}
