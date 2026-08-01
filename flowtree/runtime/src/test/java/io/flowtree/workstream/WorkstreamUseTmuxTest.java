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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@code useTmux} field on {@link Workstream}: the runtime
 * default, the getter / setter, and the {@code toSummaryJson} projection
 * that surfaces the flag to {@code /api/workstreams}.
 */
public class WorkstreamUseTmuxTest extends TestSuiteBase {

	/**
	 * The runtime default is {@code false}: a freshly-constructed workstream
	 * must NOT have tmux-backed launches enabled. Opting the workstream
	 * into tmux is an explicit operator decision; the inert default is
	 * the "deny by construction" half of the safety model.
	 */
	@Test(timeout = 10000)
	public void runtimeDefaultIsFalse() {
		Workstream ws = new Workstream("ws-fresh", "C", "#c");
		assertFalse("Freshly-constructed workstream must default to"
			+ " useTmux=false", ws.isUseTmux());
	}

	/**
	 * The setter is a plain boolean store: {@code true} is observed on
	 * the next read, {@code false} is also observed on the next read.
	 * There is no "sticky-once-on" behaviour.
	 */
	@Test(timeout = 10000)
	public void setterTogglesTheFlag() {
		Workstream ws = new Workstream("ws-toggle", "C", "#c");
		ws.setUseTmux(true);
		assertTrue(ws.isUseTmux());
		ws.setUseTmux(false);
		assertFalse(ws.isUseTmux());
		ws.setUseTmux(true);
		assertTrue(ws.isUseTmux());
	}

	/**
	 * {@code toSummaryJson} omits the field when the flag is false and
	 * emits {@code "useTmux": true} when it is set. The default-on
	 * path in {@code FlowTreeApiEndpoint.handleSubmit} consults
	 * {@code workstream.isUseTmux()}, so the JSON projection is a
	 * diagnostic-only signal for operators — its shape is preserved
	 * for symmetry with {@code dispatchCapable}.
	 */
	@Test(timeout = 10000)
	public void toSummaryJsonEmitsTheFlagOnlyWhenTrue() {
		Workstream ws = new Workstream("ws-json", "C", "#c");
		ws.setDefaultBranch("feature/tmux-test");
		String off = ws.toSummaryJson();
		assertFalse("useTmux=false must NOT appear in toSummaryJson",
			off.contains("useTmux"));

		ws.setUseTmux(true);
		String on = ws.toSummaryJson();
		assertTrue("useTmux=true MUST appear in toSummaryJson so operators"
			+ " can see the workstream-level tmux opt-in from"
			+ " /api/workstreams", on.contains("\"useTmux\":true"));
	}
}
