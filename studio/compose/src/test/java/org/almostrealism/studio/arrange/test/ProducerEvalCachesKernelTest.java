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

import io.almostrealism.code.ComputableBase;
import io.almostrealism.code.Memory;
import io.almostrealism.code.ScopeLifecycle;
import io.almostrealism.scope.ScopeSettings;
import io.almostrealism.profile.OperationProfileNode;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.hardware.Hardware;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ProcessDetailsFactory;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.time.TemporalRunner;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
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
	public void preEvalFreezesAssignmentInsideLoop() throws IOException {
		runLoopAssignmentScenario("preEvalFreezesAssignmentInsideLoop",
				/* doPreEval */ true);
	}

	/**
	 * Apples-to-apples control for {@link #preEvalFreezesAssignmentInsideLoop}:
	 * same Loop body, same producer instance, same iteration count &mdash;
	 * just no pre-loop {@code producer.get().evaluate()} call. Expected to
	 * pass. The two profile XMLs (with and without the pre-eval) are the
	 * primary diagnostic: comparing them tells us whether the bug is
	 * (a) different generated code, (b) different argument layout, or
	 * (c) same code &amp; args but different runtime behaviour of those args.
	 */
	@Test(timeout = 30_000)
	public void assignmentReadsLiveBufferInsideLoopWithoutPreEval() throws IOException {
		runLoopAssignmentScenario("assignmentReadsLiveBufferInsideLoopWithoutPreEval",
				/* doPreEval */ false);
	}

	/**
	 * Builds the scenario shared between
	 * {@link #preEvalFreezesAssignmentInsideLoop} and
	 * {@link #assignmentReadsLiveBufferInsideLoopWithoutPreEval}; saves a
	 * profile XML for the run so the two compiled kernels can be compared
	 * via the {@code ar-profile-analyzer} MCP tool.
	 */
	private void runLoopAssignmentScenario(String name, boolean doPreEval)
			throws IOException {
		PackedCollection buffer = new PackedCollection(1);
		PackedCollection sink = new PackedCollection(1);
		sink.setMem(0, Double.NaN);

		Producer<PackedCollection> producer =
				(Producer) cp(buffer).multiply((Producer) c(2.0));

		buffer.setMem(0, 0.0);

		log(name + ": [phase=after-construct] " + describe("buffer", buffer)
				+ ", " + describe("sink", sink));

		if (doPreEval) {
			double preEval = producer.get().evaluate().toDouble(0);
			log(name + ": pre-eval = " + preEval + " (expected 0.0)");
			log(name + ": [phase=after-pre-eval] " + describe("buffer", buffer)
					+ ", " + describe("sink", sink));
		}

		int iterations = 64;
		OperationList body = new OperationList(name + " body");
		body.add(a((Producer) cp(buffer),
				(Producer) add((Producer) cp(buffer), (Producer) c(1.0))));
		body.add(a(p(sink), producer));

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);
		try {
			new TemporalRunner((Supplier<Runnable>) new OperationList("setup"),
					body, iterations, false).get().run();
		} finally {
			File profileFile = new File("results/" + name + ".profile.xml");
			profileFile.getParentFile().mkdirs();
			profile.save(profileFile.getPath());
			Hardware.getLocalHardware().assignProfile(null);
			log(name + ": profile saved to " + profileFile.getPath());
		}

		log(name + ": [phase=after-loop] " + describe("buffer", buffer)
				+ ", " + describe("sink", sink));

		double sinkValue = sink.toDouble(0);
		double bufferValue = buffer.toDouble(0);
		log(name + ": buffer=" + bufferValue + ", sink=" + sinkValue
				+ " (expected 2*" + iterations + "=" + (2.0 * iterations) + ")");

		assertEquals("buffer should have advanced to " + iterations,
				(double) iterations, bufferValue, 1e-9);
		assertEquals("Assignment inside compiled Loop must read the current "
						+ "buffer value (expected 2 * " + iterations + ")",
				2.0 * iterations, sinkValue, 1e-9);
	}

	/** Logs identity / memory-provider details for a {@link PackedCollection}. */
	private static String describe(String label, PackedCollection coll) {
		Memory mem = coll.getMem();
		String providerName = mem == null ? "<null mem>"
				: mem.getProvider() == null ? "<null provider>"
				: mem.getProvider().getClass().getSimpleName();
		return label + "{id=" + System.identityHashCode(coll)
				+ ", mem=" + System.identityHashCode(mem)
				+ ", provider=" + providerName
				+ ", offset=" + coll.getOffset()
				+ ", len=" + coll.getMemLength() + "}";
	}

	/**
	 * Same as {@link #preEvalFreezesAssignmentInsideLoop} but runs with
	 * {@link MemoryDataArgumentMap#enableArgumentAggregation} set to
	 * {@code false}. If aggregation is what's allowing the read-side and
	 * write-side of {@code buffer} to be merged into a single kernel arg in
	 * the no-pre-eval case (and what's split apart by the pre-eval),
	 * disabling it should make BOTH variants behave the same way: either
	 * both pass (each gets its own arg, kernel reads live) or both fail
	 * (each gets its own arg but the read side still reads a stale snapshot).
	 */
	@Test(timeout = 30_000)
	public void preEvalWithAggregationDisabled() throws IOException {
		boolean previous = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;
		try {
			runLoopAssignmentScenario("preEvalWithAggregationDisabled",
					/* doPreEval */ true);
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = previous;
		}
	}

	/**
	 * Same as {@link #assignmentReadsLiveBufferInsideLoopWithoutPreEval} but
	 * runs with aggregation disabled. Companion to
	 * {@link #preEvalWithAggregationDisabled}.
	 */
	@Test(timeout = 30_000)
	public void noPreEvalWithAggregationDisabled() throws IOException {
		boolean previous = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;
		try {
			runLoopAssignmentScenario("noPreEvalWithAggregationDisabled",
					/* doPreEval */ false);
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = previous;
		}
	}

	/**
	 * The same scenario as {@link #preEvalFreezesAssignmentInsideLoop} but
	 * with {@link ProcessDetailsFactory#enableConstantCache} turned off. If
	 * the constant-cache path is what allocates the snapshot collection that
	 * the aggregation later picks up, this should pass.
	 */
	@Test(timeout = 30_000)
	public void preEvalWithConstantCacheDisabled() throws IOException {
		boolean previous = ProcessDetailsFactory.enableConstantCache;
		ProcessDetailsFactory.enableConstantCache = false;
		try {
			runLoopAssignmentScenario("preEvalWithConstantCacheDisabled",
					/* doPreEval */ true);
		} finally {
			ProcessDetailsFactory.enableConstantCache = previous;
		}
	}

	/**
	 * Tests whether {@link ScopeSettings#enableInstructionSetReuse} is the
	 * mechanism that lets the pre-eval's compiled-kernel state leak into the
	 * Loop body's compilation. With reuse on, the pre-eval registers an
	 * instruction set under the producer's signature; the Loop body's
	 * compilation finds the same signature and reuses the cached instruction
	 * set (which was built with a destination collection from the pre-eval).
	 */
	@Test(timeout = 30_000)
	public void preEvalWithInstructionSetReuseDisabled() throws IOException {
		boolean previous = ScopeSettings.enableInstructionSetReuse;
		ScopeSettings.enableInstructionSetReuse = false;
		try {
			runLoopAssignmentScenario("preEvalWithInstructionSetReuseDisabled",
					/* doPreEval */ true);
		} finally {
			ScopeSettings.enableInstructionSetReuse = previous;
		}
	}

	/**
	 * Confirmation test for the cached-{@code ArrayVariable} hypothesis. Same
	 * sequence as {@link #preEvalFreezesAssignmentInsideLoop} (pre-eval + loop
	 * body that uses the same producer instance), but calls
	 * {@link ScopeLifecycle#resetArguments()} on the producer in between, which
	 * walks the input tree clearing each {@code ComputationBase}'s cached
	 * {@code ArrayVariable} list. If the leak channel is the producer's
	 * cached argument variables (populated by pre-eval, pinned to MDAM #1's
	 * aggregate, and reused for MDAM #2's compilation because
	 * {@code prepareArguments}/{@code prepareScope} early-return when
	 * {@code getArgumentVariables() != null}), then this test should PASS
	 * (sink == 2 * iterations) where {@link #preEvalFreezesAssignmentInsideLoop}
	 * fails (sink == 0). If both still fail, the leak channel is somewhere
	 * else.
	 */
	@Test(timeout = 30_000)
	public void preEvalFollowedByResetArguments() throws IOException {
		String name = "preEvalFollowedByResetArguments";

		PackedCollection buffer = new PackedCollection(1);
		PackedCollection sink = new PackedCollection(1);
		sink.setMem(0, Double.NaN);

		Producer<PackedCollection> producer =
				(Producer) cp(buffer).multiply((Producer) c(2.0));

		buffer.setMem(0, 0.0);

		double preEval = producer.get().evaluate().toDouble(0);
		log(name + ": pre-eval = " + preEval + " (expected 0.0)");

		// Clear the producer tree's cached ArrayVariables. This is the only
		// difference vs. preEvalFreezesAssignmentInsideLoop.
		((ScopeLifecycle) producer).resetArguments();
		log(name + ": resetArguments() called on producer tree");

		int iterations = 64;
		OperationList body = new OperationList(name + " body");
		body.add(a((Producer) cp(buffer),
				(Producer) add((Producer) cp(buffer), (Producer) c(1.0))));
		body.add(a(p(sink), producer));

		OperationProfileNode profile = new OperationProfileNode(name);
		Hardware.getLocalHardware().assignProfile(profile);
		try {
			new TemporalRunner((Supplier<Runnable>) new OperationList("setup"),
					body, iterations, false).get().run();
		} finally {
			File profileFile = new File("results/" + name + ".profile.xml");
			profileFile.getParentFile().mkdirs();
			profile.save(profileFile.getPath());
			Hardware.getLocalHardware().assignProfile(null);
			log(name + ": profile saved to " + profileFile.getPath());
		}

		double sinkValue = sink.toDouble(0);
		double bufferValue = buffer.toDouble(0);
		log(name + ": buffer=" + bufferValue + ", sink=" + sinkValue
				+ " (expected 2*" + iterations + "=" + (2.0 * iterations) + ")");

		assertEquals("buffer should have advanced to " + iterations,
				(double) iterations, bufferValue, 1e-9);
		assertEquals("If resetArguments() between pre-eval and loop "
						+ "compilation makes sink correct, the leak channel "
						+ "is the cached ArrayVariable list on ComputationBase.",
				2.0 * iterations, sinkValue, 1e-9);
	}

	/**
	 * Walks the input tree of {@code cp(buffer).multiply(c(2.0))} and dumps
	 * each node's class plus, for any {@link Provider}-shaped leaf, the
	 * identity of the {@link MemoryData} it wraps. Snapshots the tree
	 * before, after, and again after pre-eval. If the leaf MemoryData
	 * identity changes, that's the divergence the aggregation later picks up.
	 */
	@Test(timeout = 30_000)
	public void leafMemoryDataIdentityBeforeAndAfterPreEval() {
		PackedCollection buffer = new PackedCollection(1);
		buffer.setMem(0, 5.0);

		Producer<PackedCollection> producer =
				(Producer) cp(buffer).multiply((Producer) c(2.0));

		log("buffer initial identity: " + System.identityHashCode(buffer));

		log("--- BEFORE pre-eval ---");
		dumpProducerLeaves(producer, 0);

		double v = producer.get().evaluate().toDouble(0);
		log("--- pre-eval returned: " + v + " ---");

		log("--- AFTER pre-eval ---");
		dumpProducerLeaves(producer, 0);

		// Mutate buffer; producer.get().evaluate() should now reflect the
		// new value if the leaf is still 'buffer'. If pre-eval cached an
		// independent destination collection somewhere, evaluating again
		// might return the stale value.
		buffer.setMem(0, 100.0);
		double v2 = producer.get().evaluate().toDouble(0);
		log("--- after buffer.setMem(0,100), eval returned: " + v2
				+ " (expected 200.0; if 10.0 the producer's leaf points at "
				+ "a snapshot rather than buffer) ---");

		log("--- AFTER second eval ---");
		dumpProducerLeaves(producer, 0);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private void dumpProducerLeaves(Object node, int depth) {
		String indent = "  ".repeat(depth);
		String cls = node == null ? "null" : node.getClass().getName();
		log(indent + "node: " + cls + " #" + System.identityHashCode(node));
		if (node instanceof ComputableBase) {
			List<?> inputs = ((ComputableBase<?, ?>) node).getInputs();
			log(indent + "  ComputableBase has " + (inputs == null ? "null" : inputs.size()) + " inputs");
			if (inputs != null) {
				for (int i = 0; i < inputs.size(); i++) {
					log(indent + "  input[" + i + "]:");
					dumpProducerLeaves(inputs.get(i), depth + 2);
				}
			}
		} else if (node instanceof Producer) {
			Evaluable<?> ev = ((Producer<?>) node).get();
			log(indent + "  -> get() returned " + (ev == null ? "null"
					: ev.getClass().getName() + " #"
							+ System.identityHashCode(ev)));
			if (ev instanceof Provider) {
				Object value = ((Provider<?>) ev).get();
				if (value instanceof MemoryData) {
					MemoryData md = (MemoryData) value;
					log(indent + "  -> Provider wraps MemoryData "
							+ md.getClass().getSimpleName()
							+ " #" + System.identityHashCode(md)
							+ " mem#" + System.identityHashCode(md.getMem())
							+ " offset=" + md.getOffset()
							+ " len=" + md.getMemLength());
				} else {
					log(indent + "  -> Provider wraps " + value);
				}
			}
		}
	}
}
