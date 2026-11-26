/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.almostrealism.compute.test;

import io.almostrealism.compute.Process;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Pins how {@link Process#isolationPermitted(Supplier)} answers while explicit isolation
 * targets are configured. The targets are predicates over processes: a process is permitted to
 * be isolated when one of them matches it, and a supplier that is not a process matches none
 * of them. Each test removes the target it adds, since the targets are shared by the whole
 * JVM. None of this compiles anything, so the tests hold on any backend.
 */
public class ExplicitIsolationTargetTests extends TestSuiteBase {

	/** A process with no children, which does no work. */
	private static final class Stage implements Process<Process<?, ?>, Runnable> {
		@Override
		public Collection<Process<?, ?>> getChildren() { return List.of(); }

		@Override
		public Runnable get() { return () -> { }; }
	}

	/** A supplier that is not a process is not permitted, since no target can match it. */
	@Test(timeout = 10000)
	public void supplierThatIsNotAProcessMatchesNoTarget() {
		Supplier<String> supplier = () -> "value";
		permitWhile(process -> true, () -> Assert.assertFalse(Process.isolationPermitted(supplier)));
	}

	/** A process that a target matches is permitted. */
	@Test(timeout = 10000)
	public void matchedProcessIsPermitted() {
		Stage stage = new Stage();
		permitWhile(process -> process == stage, () -> Assert.assertTrue(Process.isolationPermitted(stage)));
	}

	/** A process that no target matches is not permitted. */
	@Test(timeout = 10000)
	public void unmatchedProcessIsNotPermitted() {
		Stage stage = new Stage();
		permitWhile(process -> false, () -> Assert.assertFalse(Process.isolationPermitted(stage)));
	}

	/**
	 * Runs a check while the given predicate is configured as an explicit isolation target, and
	 * removes the target afterwards.
	 *
	 * @param target the explicit isolation target to configure
	 * @param check  the check to run while it is configured
	 */
	private static void permitWhile(Predicate<Process> target, Runnable check) {
		Process.explicitIsolationTargets.add(target);

		try {
			Assert.assertTrue(Process.isExplicitIsolation());
			check.run();
		} finally {
			Process.explicitIsolationTargets.remove(target);
		}
	}
}
