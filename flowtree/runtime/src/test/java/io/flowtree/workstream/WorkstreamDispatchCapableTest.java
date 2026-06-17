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
 * Tests for the {@code dispatchCapable} field on
 * {@link Workstream}: the runtime default, the getter / setter, and
 * the {@code toSummaryJson} projection that surfaces the flag to
 * {@code /api/workstreams} and (transitively) to the controller-side
 * dispatch check in ar-manager.
 */
public class WorkstreamDispatchCapableTest extends TestSuiteBase {

	/**
	 * The runtime default is {@code false}: a freshly-constructed
	 * workstream must NOT be dispatch-capable. Granting dispatch is
	 * an explicit operator decision; the inert default is the
	 * "deny by construction" half of the safety model.
	 */
	@Test(timeout = 10000)
	public void runtimeDefaultIsFalse() {
		Workstream ws = new Workstream("ws-fresh", "C", "#c");
		assertFalse("Freshly-constructed workstream must default to"
			+ " dispatchCapable=false", ws.isDispatchCapable());
	}

	/**
	 * The setter is a plain boolean store: {@code true} is observed
	 * on the next read, {@code false} is also observed on the next
	 * read. There is no "sticky-once-on" behaviour.
	 */
	@Test(timeout = 10000)
	public void setterTogglesTheFlag() {
		Workstream ws = new Workstream("ws-toggle", "C", "#c");
		ws.setDispatchCapable(true);
		assertTrue(ws.isDispatchCapable());
		ws.setDispatchCapable(false);
		assertFalse(ws.isDispatchCapable());
		ws.setDispatchCapable(true);
		assertTrue(ws.isDispatchCapable());
	}

	/**
	 * {@code toSummaryJson} omits the field when the flag is false
	 * and emits {@code "dispatchCapable": true} when it is set. The
	 * ar-manager side uses the presence of the field in the JSON
	 * projection as the "is this workstream dispatch-capable?"
	 * signal, so a workstream that defaults to false must not emit
	 * the key (false and absent carry the same meaning for the
	 * controller-side check).
	 */
	@Test(timeout = 10000)
	public void toSummaryJsonEmitsTheFlagOnlyWhenTrue() {
		Workstream ws = new Workstream("ws-json", "C", "#c");
		ws.setDefaultBranch("feature/dispatch-test");
		String off = ws.toSummaryJson();
		assertFalse("dispatchCapable=false must NOT appear in toSummaryJson",
			off.contains("dispatchCapable"));

		ws.setDispatchCapable(true);
		String on = ws.toSummaryJson();
		assertTrue("dispatchCapable=true MUST appear in toSummaryJson so the"
			+ " controller-side check can read it from /api/workstreams",
			on.contains("\"dispatchCapable\":true"));
	}
}
