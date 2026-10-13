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

import io.flowtree.JsonFieldExtractor;
import io.flowtree.workstream.Workstream;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the per-job {@code use_tmux} flag / workstream
 * {@code defaultUseTmux} precedence rule, exercised through
 * {@link Workstream#newJobFactory(String)}, which seeds the factory with the
 * workstream default, followed by the per-job override that
 * {@code FlowTreeApiEndpoint.handleSubmit} applies on top of it.
 *
 * <p>Precedence, in order:</p>
 * <ol>
 *   <li>Per-job {@code useTmux} field on the request body, when present,
 *       always wins (true or false).</li>
 *   <li>Otherwise the workstream's {@link Workstream#isUseTmux() useTmux}
 *       default applies.</li>
 *   <li>Otherwise (per-job absent, workstream absent, or both) the
 *       factory's {@code useTmux} stays at its default (false).</li>
 * </ol>
 *
 * <p>Body parsing is exercised via {@link JsonFieldExtractor} so the
 * test stays in the same package family the production caller lives in
 * and matches its actual JSON parsing rules.</p>
 */
public class WorkstreamDefaultUseTmuxFallbackTest extends TestSuiteBase {

	/**
	 * Builds a factory the way the submit endpoint does: the workstream's
	 * defaults from {@link Workstream#newJobFactory(String)}, then the
	 * per-job {@code useTmux} override when the body carries one.
	 */
	private static CodingAgentJobFactory resolve(String body, Workstream workstream) {
		CodingAgentJobFactory factory = workstream != null
				? workstream.newJobFactory("prompt") : new CodingAgentJobFactory("prompt");
		if (body != null && JsonFieldExtractor.hasField(body, "useTmux"))
			factory.setUseTmux(JsonFieldExtractor.extractBoolean(body, "useTmux"));
		return factory;
	}

	/**
	 * When the request body sets {@code useTmux=true} explicitly and the
	 * workstream default is also true, the per-job value (true) wins.
	 */
	@Test(timeout = 10000)
	public void perJobTrueWinsOverWorkstreamTrue() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		workstream.setUseTmux(true);
		CodingAgentJobFactory factory = resolve("{\"useTmux\":true}", workstream);
		assertTrue("per-job useTmux=true must win over workstream default",
			factory.isUseTmux());
	}

	/**
	 * When the request body sets {@code useTmux=false} explicitly and the
	 * workstream default is true, the per-job value (false) wins — an
	 * individual job submission can opt out of tmux even when the
	 * workstream is opted in.
	 */
	@Test(timeout = 10000)
	public void perJobFalseWinsOverWorkstreamTrue() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		workstream.setUseTmux(true);
		CodingAgentJobFactory factory = resolve("{\"useTmux\":false}", workstream);
		assertFalse("per-job useTmux=false must win over workstream default",
			factory.isUseTmux());
	}

	/**
	 * When the request body sets {@code useTmux=true} explicitly and the
	 * workstream default is false, the per-job value (true) wins — an
	 * individual job submission can opt in to tmux even when the
	 * workstream is opted out.
	 */
	@Test(timeout = 10000)
	public void perJobTrueWinsOverWorkstreamFalse() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		workstream.setUseTmux(false);
		CodingAgentJobFactory factory = resolve("{\"useTmux\":true}", workstream);
		assertTrue("per-job useTmux=true must win over workstream default",
			factory.isUseTmux());
	}

	/**
	 * When the request body omits {@code useTmux} and the workstream
	 * default is true, the factory gets {@code useTmux=true} via the
	 * workstream default.
	 */
	@Test(timeout = 10000)
	public void workstreamTrueAppliesWhenPerJobAbsent() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		workstream.setUseTmux(true);
		CodingAgentJobFactory factory = resolve("{\"prompt\":\"x\"}", workstream);
		assertTrue("workstream default must apply when per-job flag absent",
			factory.isUseTmux());
	}

	/**
	 * When the request body omits {@code useTmux} and the workstream
	 * default is false, the factory's {@code useTmux} stays at its
	 * default (false).
	 */
	@Test(timeout = 10000)
	public void workstreamFalseKeepsFactoryAtFalseWhenPerJobAbsent() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		workstream.setUseTmux(false);
		CodingAgentJobFactory factory = resolve("{\"prompt\":\"x\"}", workstream);
		assertFalse("workstream default false must keep factory at false"
			+ " when per-job flag absent", factory.isUseTmux());
	}

	/**
	 * When the request body is {@code null} and the workstream default
	 * is true, the factory's {@code useTmux} is set via the workstream
	 * default. A {@code null} body is treated the same as a body that
	 * omits the field.
	 */
	@Test(timeout = 10000)
	public void nullBodyFallsBackToWorkstreamDefault() {
		Workstream workstream = new Workstream("ws", "C", "#c");
		workstream.setUseTmux(true);
		CodingAgentJobFactory factory = resolve(null, workstream);
		assertTrue("null body must fall back to workstream default",
			factory.isUseTmux());
	}

	/**
	 * A {@code null} workstream is treated as having no default (false).
	 * The factory stays at its default unless the body sets the field.
	 */
	@Test(timeout = 10000)
	public void nullWorkstreamTreatedAsDefaultFalse() {
		CodingAgentJobFactory factory = resolve("{\"prompt\":\"x\"}", null);
		assertFalse("null workstream with no per-job flag must keep"
			+ " factory at default false", factory.isUseTmux());

		factory = resolve("{\"useTmux\":true}", null);
		assertTrue("per-job useTmux=true must still apply when workstream"
			+ " is null", factory.isUseTmux());
	}
}
