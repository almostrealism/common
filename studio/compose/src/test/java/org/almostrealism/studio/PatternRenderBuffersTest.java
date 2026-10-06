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

package org.almostrealism.studio;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests that {@link PatternRenderBuffers} halts the render-ahead producer threads it
 * tracks, which is what lets {@link AudioScene#destroy()} release pattern state without
 * racing a producer that is still rendering.
 */
public class PatternRenderBuffersTest extends TestSuiteBase {

	/**
	 * Starts a render-ahead stream whose render operation records the producer thread,
	 * then verifies that {@link PatternRenderBuffers#stopStreams()} leaves that thread
	 * terminated, and that a subsequent {@link PatternRenderBuffers#destroy()} is safe.
	 */
	@Test(timeout = 60000)
	public void stopStreamsHaltsProducerThread() {
		AtomicReference<Thread> producer = new AtomicReference<>();
		PackedCollection workingInput = new PackedCollection(4);
		PatternRenderStream stream = new PatternRenderStream(
				() -> producer.set(Thread.currentThread()),
				new long[] {0}, workingInput, 2, 1, 4);

		PatternRenderBuffers buffers = new PatternRenderBuffers();
		buffers.addStream(stream);
		stream.start(1);

		Thread thread = producer.get();
		assertNotNull("Producer should have rendered during prefill", thread);
		assertTrue("Producer should be running after start", thread.isAlive());

		buffers.stopStreams();
		assertFalse("Producer should be stopped by stopStreams", thread.isAlive());

		buffers.destroy();
		workingInput.destroy();
	}
}
