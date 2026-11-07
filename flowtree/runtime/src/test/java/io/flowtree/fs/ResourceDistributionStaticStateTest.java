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

package io.flowtree.fs;

import org.almostrealism.util.TestSuiteBase;
import org.junit.After;
import org.junit.Before;

import java.util.List;

/**
 * Base class for tests that unavoidably mutate the JVM-wide static state of
 * {@link ResourceDistributionTask} — the current-task singleton and the
 * resource-type parser registry, both of which construction touches. The state
 * is snapshotted before each test and restored afterwards so subclasses neither
 * depend on nor leak that shared state into other tests running in the same JVM.
 *
 * <p>The nested {@link InMemoryTask} is the common fixture for these tests: a
 * task whose database-backed file load is a no-op, so its inventory can be
 * populated directly without a running {@link OutputServer}.
 */
public abstract class ResourceDistributionStaticStateTest extends TestSuiteBase {

	/** The current-task singleton captured before each test. */
	private ResourceDistributionTask savedCurrent;

	/** The parser registry contents captured before each test. */
	private List savedResourceTypes;

	/**
	 * A {@link ResourceDistributionTask} whose database-backed file-list load is
	 * replaced with a no-op, allowing the in-memory inventory logic to be tested
	 * without a running {@link OutputServer}. Each test populates the inventory
	 * directly instead of loading it from a database.
	 */
	protected static class InMemoryTask extends ResourceDistributionTask {
		/** Constructs a task with no server, no jobs, and no inter-job sleep. */
		protected InMemoryTask() {
			super(null, 0, 0);
		}

		@Override
		protected void initFiles(OutputServer server) {
		}
	}

	/**
	 * Captures the current-task singleton and parser registry so both can be
	 * restored after the test, keeping the JVM-wide static state unchanged across
	 * test boundaries.
	 */
	@Before
	public void snapshotStaticState() {
		savedCurrent = ResourceDistributionTask.getCurrentTask();
		savedResourceTypes = ResourceDistributionTaskState.snapshotRegistry();
	}

	/**
	 * Restores the current-task singleton and parser registry captured in
	 * {@link #snapshotStaticState()}.
	 */
	@After
	public void restoreStaticState() {
		ResourceDistributionTaskState.setCurrentTask(savedCurrent);
		ResourceDistributionTaskState.restoreRegistry(savedResourceTypes);
	}
}
