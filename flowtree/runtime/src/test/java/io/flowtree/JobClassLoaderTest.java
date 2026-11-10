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

package io.flowtree;

import io.flowtree.job.Job;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Verifies that {@link JobClassLoader#instantiateJobClass(String)} reconstructs a
 * {@link Job} from the encoded {@code classname::key:=value::key:=value} wire format
 * used across FlowTree, applying every key-value pair to the instance, and that it
 * fails softly (returning {@code null}) when the class cannot be instantiated.
 *
 * @author Michael Murray
 */
public class JobClassLoaderTest extends TestSuiteBase {

	/** The fully-qualified binary name of the reflectively-instantiated stub job. */
	private static final String STUB = StubJob.class.getName();

	/** A single property is decoded and applied to the reconstructed job. */
	@Test(timeout = 60000)
	public void instantiatesAndAppliesSingleProperty() {
		Job job = JobClassLoader.instantiateJobClass(STUB + "::alpha:=one");

		Assert.assertTrue(job instanceof StubJob);
		Assert.assertEquals("one", ((StubJob) job).value("alpha"));
	}

	/** Multiple properties are each decoded and applied in order. */
	@Test(timeout = 60000)
	public void instantiatesAndAppliesMultipleProperties() {
		Job job = JobClassLoader.instantiateJobClass(STUB + "::alpha:=one::beta:=two");

		StubJob stub = (StubJob) job;
		Assert.assertEquals("one", stub.value("alpha"));
		Assert.assertEquals("two", stub.value("beta"));
		Assert.assertEquals(2, stub.assignmentCount());
	}

	/** An unknown class name is reported as a null job rather than an exception. */
	@Test(timeout = 60000)
	public void unknownClassNameYieldsNull() {
		Assert.assertNull(JobClassLoader.instantiateJobClass("io.flowtree.NotARealClass::k:=v"));
	}

	/**
	 * A minimal {@link Job} whose {@link #set(String, String)} records every key-value
	 * assignment so a test can confirm the decoder applied them. It must be public with a
	 * public no-argument constructor so {@link JobClassLoader} can instantiate it by name.
	 */
	public static class StubJob implements Job {

		/** Records each {@code key -> value} assignment made during decoding. */
		private final Map<String, String> assigned = new HashMap<>();

		/** Completed, but never awaited, by {@link #run()}. */
		private final CompletableFuture<Void> future = new CompletableFuture<>();

		/** Public no-argument constructor required for reflective instantiation. */
		public StubJob() { }

		/**
		 * Returns the value recorded for the given key during decoding.
		 *
		 * @param key the property name
		 * @return the assigned value, or {@code null} if the key was never set
		 */
		public String value(String key) { return assigned.get(key); }

		/**
		 * Returns the number of key-value assignments the decoder applied.
		 *
		 * @return the assignment count
		 */
		public int assignmentCount() { return assigned.size(); }

		@Override
		public void run() { future.complete(null); }

		@Override
		public void set(String key, String value) { assigned.put(key, value); }

		@Override
		public String encode() { return STUB; }

		@Override
		public String getTaskId() { return assigned.get("id"); }

		@Override
		public String getTaskString() { return "StubJob"; }

		@Override
		public CompletableFuture<Void> getCompletableFuture() { return future; }
	}
}
