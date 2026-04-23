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

package org.almostrealism.studio.arrange.test;

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;

/**
 * Minimal reproducer for a kernel-cache aliasing bug: calling
 * {@code Producer.get().evaluate()} on a {@link Producer} appears to cache a
 * compiled kernel for that producer's expression; subsequently using the same
 * producer instance as the value of an {@code Assignment} causes the
 * Assignment to write the cached value, regardless of how the producer's
 * underlying input has changed.
 *
 * <p>No {@link org.almostrealism.time.TimeCell}, no
 * {@link org.almostrealism.studio.arrange.AutomationManager}, no Loop, no
 * audio. Just a one-element {@link PackedCollection}, a {@link Producer}
 * over it ({@code cp(buffer).multiply(c(2.0))}), and a single
 * {@code a(p(sink), producer)} assignment.</p>
 *
 * <h2>Sequence</h2>
 * <ol>
 *   <li>Set {@code buffer[0] = 5.0}.</li>
 *   <li>Call {@code producer.get().evaluate()} once from Java &mdash; logs
 *       {@code 10.0} (= 5*2).</li>
 *   <li>Mutate the buffer: {@code buffer[0] = 100.0}.</li>
 *   <li>Run the Assignment that uses the same {@code producer} instance.</li>
 *   <li>Read {@code sink}. If the producer is live, it should be
 *       {@code 200.0} (= 100*2). If the kernel is cached from step 2,
 *       {@code sink} will be {@code 10.0} regardless of the buffer mutation.</li>
 * </ol>
 *
 * <h2>Companion test</h2>
 * <p>{@link #assignmentReadsLiveBufferWithoutPreEval} is the control: same
 * sequence, but skips step 2. If the producer is fundamentally live (as the
 * codebase intends), the control passes. The bug is then specifically that
 * step 2's Java eval contaminates the cached kernel for the assignment.</p>
 */
public class ProducerEvalCachesKernelTest extends TestSuiteBase implements CellFeatures {

	/**
	 * Control: no pre-loop Java eval. Builds a Producer
	 * {@code cp(buffer).multiply(c(2.0))} and uses it as the value of an
	 * Assignment. Verifies that the assignment writes 2x the current buffer
	 * value.
	 */
	@Test(timeout = 30_000)
	public void assignmentReadsLiveBufferWithoutPreEval() {
		PackedCollection buffer = new PackedCollection(1);
		PackedCollection sink = new PackedCollection(1);
		sink.setMem(0, Double.NaN);

		Producer<PackedCollection> producer =
				(Producer) cp(buffer).multiply((Producer) c(2.0));

		buffer.setMem(0, 100.0);
		a(p(sink), producer).get().run();

		double sinkValue = sink.toDouble(0);
		log("control: buffer=100.0, sink=" + sinkValue);
		assertEquals("Assignment should write 2 * buffer = 200.0", 200.0,
				sinkValue, 1e-9);
	}

	/**
	 * The bug: pre-evaluating the producer from Java once before using it as
	 * the value of an Assignment causes the Assignment to write the
	 * pre-eval'd value, ignoring later buffer mutations.
	 *
	 * <p>This test is expected to FAIL while the bug exists. When fixed, the
	 * sink should be 200.0 (= 100 * 2), not 10.0 (= 5 * 2 from the pre-eval).</p>
	 */
	@Test(timeout = 30_000)
	public void preEvalFreezesAssignment() {
		PackedCollection buffer = new PackedCollection(1);
		PackedCollection sink = new PackedCollection(1);
		sink.setMem(0, Double.NaN);

		Producer<PackedCollection> producer =
				(Producer) cp(buffer).multiply((Producer) c(2.0));

		// Step 1: initial buffer value
		buffer.setMem(0, 5.0);

		// Step 2: pre-eval the producer from Java
		double preEval = producer.get().evaluate().toDouble(0);
		log("preEvalFreezesAssignment: pre-eval = " + preEval + " (expected 10.0)");

		// Step 3: mutate the buffer
		buffer.setMem(0, 100.0);

		// Step 4: run the Assignment using the same producer instance
		a(p(sink), producer).get().run();

		// Step 5: check what the assignment wrote
		double sinkValue = sink.toDouble(0);
		log("preEvalFreezesAssignment: buffer=100.0 after mutation, sink=" + sinkValue
				+ " (expected 200.0; 10.0 means kernel is cached from pre-eval)");

		assertEquals("Assignment must read the current buffer value, not a "
						+ "cached value from a previous Java eval. If sink="
						+ preEval + " the producer's kernel was cached by the "
						+ "Java eval and reused for the Assignment with stale "
						+ "data.",
				200.0, sinkValue, 1e-9);
	}

	/**
	 * The Loop variant: same idea, but the assignment runs inside a
	 * compiled Loop that ALSO mutates the buffer per-iteration. Body:
	 * <ol>
	 *   <li>{@code buffer[0] += 1}  (mutates the buffer in-kernel)</li>
	 *   <li>{@code sink = producer}  where {@code producer = cp(buffer) * 2}</li>
	 * </ol>
	 * Pre-eval is done once before the Loop starts. After {@code N} iterations
	 * sink should hold {@code 2 * (initial + N)}; if the Loop reuses a cached
	 * kernel it'll hold {@code 2 * initial} instead.
	 */
	@Test(timeout = 30_000)
	public void preEvalFreezesAssignmentInsideLoop() {
		PackedCollection buffer = new PackedCollection(1);
		PackedCollection sink = new PackedCollection(1);
		sink.setMem(0, Double.NaN);

		Producer<PackedCollection> producer =
				(Producer) cp(buffer).multiply((Producer) c(2.0));

		buffer.setMem(0, 0.0);

		double preEval = producer.get().evaluate().toDouble(0);
		log("preEvalFreezesAssignmentInsideLoop: pre-eval = " + preEval
				+ " (expected 0.0)");

		int iterations = 64;
		OperationList body = new OperationList("preEvalFreezes Loop body");
		// Increment buffer[0] by 1 inside the kernel.
		body.add(a((Producer) cp(buffer),
				(Producer) add((Producer) cp(buffer), (Producer) c(1.0))));
		// Use the SAME producer instance that we pre-evaluated above.
		body.add(a(p(sink), producer));

		new TemporalRunner((Supplier<Runnable>) new OperationList("setup"),
				body, iterations, false).get().run();

		double sinkValue = sink.toDouble(0);
		double bufferValue = buffer.toDouble(0);
		log("preEvalFreezesAssignmentInsideLoop: buffer=" + bufferValue
				+ ", sink=" + sinkValue + " (expected 2*" + iterations + "="
				+ (2.0 * iterations) + "; 0.0 means kernel cached from pre-eval)");

		assertEquals("buffer should have advanced to " + iterations,
				(double) iterations, bufferValue, 1e-9);
		assertEquals("Assignment inside compiled Loop must read the current "
						+ "buffer value, not a cached value from the pre-loop "
						+ "Java eval. If sink=" + preEval + " (the pre-eval "
						+ "value) the producer's kernel was cached by the Java "
						+ "eval and reused inside the Loop with stale data.",
				2.0 * iterations, sinkValue, 1e-9);
	}
}
