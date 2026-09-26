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

package org.almostrealism.hardware.test;

import io.almostrealism.concurrent.CompletionConsumer;
import io.almostrealism.concurrent.DefaultLatchSemaphore;
import io.almostrealism.streams.Semaphore;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

/**
 * Validates {@link CompletionConsumer#compose(java.util.function.Function)}, the primitive
 * {@link org.almostrealism.hardware.computations.HardwareEvaluable} uses to apply a
 * {@link org.almostrealism.hardware.computations.HardwareEvaluable#setResultProcessor(java.util.function.UnaryOperator)
 * result processor} to a value delivered through a streaming request without forcing a host wait
 * on the completion that accompanies it.
 */
public class CompletionConsumerTest extends TestSuiteBase {

	/**
	 * The composed consumer applies the transformation to the accepted value before delivering
	 * it to the original consumer, and passes the completion through unchanged.
	 */
	@Test(timeout = 30000)
	public void composeTransformsValueAndPreservesCompletion() {
		Object[] delivered = new Object[1];
		Semaphore[] receivedCompletion = new Semaphore[1];

		CompletionConsumer<String> target = (value, completion) -> {
			delivered[0] = value;
			receivedCompletion[0] = completion;
		};

		CompletionConsumer<Integer> composed = target.compose(i -> "value=" + i);

		DefaultLatchSemaphore completion = new DefaultLatchSemaphore((Semaphore) null, 1);
		composed.accept(42, completion);

		assertTrue(delivered[0].equals("value=42"));
		assertTrue(receivedCompletion[0] == completion);
	}

	/**
	 * Accepting through the plain {@link java.util.function.Consumer#accept(Object)} contract,
	 * as a caller with no completion to report would, delivers a {@code null} completion to the
	 * underlying consumer even after composition.
	 */
	@Test(timeout = 30000)
	public void composeWithNoCompletionDeliversNull() {
		Object[] delivered = new Object[1];
		Semaphore[] receivedCompletion = new Semaphore[]{ new DefaultLatchSemaphore((Semaphore) null, 1) };

		CompletionConsumer<String> target = (value, completion) -> {
			delivered[0] = value;
			receivedCompletion[0] = completion;
		};

		CompletionConsumer<Integer> composed = target.compose(i -> "value=" + i);
		composed.accept(7);

		assertTrue(delivered[0].equals("value=7"));
		assertTrue(receivedCompletion[0] == null);
	}
}
