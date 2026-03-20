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

package io.flowtree.test;

import io.flowtree.job.Job;
import io.flowtree.job.JobFactory;
import io.flowtree.node.NodeGroup;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests for {@link NodeGroup} task list management, specifically
 * verifying that completed tasks are safely removed during the
 * run loop without triggering {@link IndexOutOfBoundsException}.
 *
 * @author Michael Murray
 */
public class NodeGroupTaskRemovalTest extends TestSuiteBase {

	/**
	 * Verifies that the NodeGroup run loop can process a mix of
	 * completed and active tasks without throwing an exception.
	 * This covers the fix where index-based removal during iteration
	 * was replaced with a collect-then-removeAll pattern.
	 */
	@Test(timeout = 30000)
	public void completedTasksRemovedSafely() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		Properties p = new Properties();
		p.setProperty("server.port", "0");
		p.setProperty("nodes.initial", "1");
		p.setProperty("nodes.jobs.max", "1");
		p.setProperty("servers.total", "0");
		p.setProperty("group.thread.sleep", "100");

		CompletableJobFactory defaultFactory = new CompletableJobFactory("default");
		NodeGroup group = new NodeGroup(p, defaultFactory);

		// Add several tasks, some of which will be pre-completed
		List<CompletableJobFactory> factories = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			CompletableJobFactory factory = new CompletableJobFactory("task-" + i);
			if (i % 2 == 0) {
				factory.markComplete();
			}
			factories.add(factory);
			group.addTask(factory);
		}

		// Start the NodeGroup thread — if the old index-based removal
		// code were still present, this would throw IOOB
		AtomicBoolean failed = new AtomicBoolean(false);
		Thread groupThread = new Thread(() -> {
			try {
				group.run();
			} catch (IndexOutOfBoundsException e) {
				System.err.println("NodeGroup threw IOOB: " + e.getMessage());
				failed.set(true);
			}
		});
		groupThread.setDaemon(true);
		groupThread.start();

		// Let it run for a few iterations
		Thread.sleep(500);

		// Now mark remaining tasks as complete too
		for (CompletableJobFactory f : factories) {
			f.markComplete();
		}
		defaultFactory.markComplete();

		// Let it process the completions
		Thread.sleep(500);

		group.stop();
		groupThread.join(5000);

		Assert.assertFalse("NodeGroup task removal threw IndexOutOfBoundsException", failed.get());
	}

	/**
	 * A {@link JobFactory} whose completion state can be controlled
	 * externally for testing purposes.
	 */
	static class CompletableJobFactory implements JobFactory {
		private final String id;
		private final AtomicBoolean complete = new AtomicBoolean(false);
		private int jobCount = 0;

		CompletableJobFactory(String id) {
			this.id = id;
		}

		void markComplete() {
			complete.set(true);
		}

		@Override
		public Job nextJob() {
			return new Job() {
				private final CompletableFuture<Void> future = new CompletableFuture<>();

				@Override
				public void run() {
					future.complete(null);
				}

				@Override
				public String encode() { return "test-job"; }

				@Override
				public void set(String key, String value) { }

				@Override
				public String getTaskId() { return id; }

				@Override
				public String getTaskString() { return id; }

				@Override
				public CompletableFuture<Void> getCompletableFuture() { return future; }
			};
		}

		@Override
		public Job createJob(String data) { return null; }

		@Override
		public String encode() { return getClass().getName() + ":id=" + id; }

		@Override
		public void set(String key, String value) { }

		@Override
		public boolean isComplete() { return complete.get(); }

		@Override
		public String getTaskId() { return id; }

		@Override
		public String getName() { return "CompletableTest (" + id + ")"; }

		@Override
		public double getCompleteness() { return complete.get() ? 1.0 : 0.0; }

		@Override
		public void setPriority(double p) { }

		@Override
		public double getPriority() { return 1.0; }

		@Override
		public CompletableFuture<Void> getCompletableFuture() {
			return new CompletableFuture<>();
		}
	}
}
