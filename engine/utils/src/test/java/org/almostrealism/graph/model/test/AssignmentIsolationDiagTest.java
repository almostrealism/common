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

package org.almostrealism.graph.model.test;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.profile.OperationInfo;
import io.almostrealism.profile.OperationProfile;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.collect.computations.DynamicCollectionProducer;
import org.almostrealism.hardware.HardwareOperator;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.OperationListRunner;
import org.almostrealism.hardware.ProcessDetailsFactory;
import org.almostrealism.hardware.mem.MemoryDataArgumentMap;
import org.almostrealism.layers.CellularLayer;
import org.almostrealism.layers.DefaultCellularLayer;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.model.SequentialBlock;
import org.almostrealism.util.ModelTestFeatures;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Test;

import java.util.List;

/**
 * Discriminating diagnostics for the assignment-based layer recording divergence:
 * a {@code dense(2, 1)} forward pass returns the bias alone (the weights-input
 * product contributes zero) when {@link DefaultCellularLayer#enableMemoryDataCopy}
 * is {@code false}. Profile comparison shows the isolated forward multiply never
 * executes on that path, while it does execute under copy-based recording.
 *
 * <p>Each test runs the same single forward pass under a different configuration
 * and reports the observed output next to the expected value, so the failing
 * mechanism can be identified by which configuration corrects the result.</p>
 */
public class AssignmentIsolationDiagTest extends TestSuiteBase implements ModelTestFeatures {

	/** Expected forward value: 0.5 * 2.0 - 0.25 * 3.0 + 0.1 */
	private static final double EXPECTED = 0.35;

	/**
	 * Baseline reproduction: assignment-based recording with default settings.
	 */
	@Test(timeout = 120000)
	public void assignmentRecordingBaseline() {
		double result = forwardWithAssignmentRecording();
		log("assignmentRecordingBaseline result=" + result + " expected=" + EXPECTED);
	}

	/**
	 * Hypothesis A: the isolated multiply is treated as a constant argument and
	 * cached (or skipped) by {@link ProcessDetailsFactory}. If disabling the
	 * constant cache corrects the result, the isolation-argument constant
	 * classification is the failing mechanism.
	 */
	@Test(timeout = 120000)
	public void assignmentRecordingWithoutConstantCache() {
		boolean original = ProcessDetailsFactory.enableConstantCache;
		ProcessDetailsFactory.enableConstantCache = false;

		try {
			double result = forwardWithAssignmentRecording();
			log("assignmentRecordingWithoutConstantCache result=" + result + " expected=" + EXPECTED);
		} finally {
			ProcessDetailsFactory.enableConstantCache = original;
		}
	}

	/**
	 * Hypothesis B: the kernel dispatch path silently skips the isolated child.
	 * Verbose kernel logging records every hardware dispatch, so the log shows
	 * whether the isolated multiply is ever dispatched during the forward pass.
	 */
	@Test(timeout = 120000)
	public void assignmentRecordingVerboseDispatch() {
		boolean original = HardwareOperator.enableVerboseLog;
		HardwareOperator.enableVerboseLog = true;

		try {
			double result = forwardWithAssignmentRecording();
			log("assignmentRecordingVerboseDispatch result=" + result + " expected=" + EXPECTED);
		} finally {
			HardwareOperator.enableVerboseLog = original;
		}
	}

	/**
	 * Hypothesis C: the isolated multiply's evaluation is mis-ordered relative to
	 * the input-record member on the first pass only. By the second forward pass
	 * the layer input buffer already holds the values from the first pass, so a
	 * per-run (but mis-ordered) evaluation produces the correct value from pass
	 * two onward, while a never-evaluated (or permanently cached) argument stays
	 * wrong on every pass.
	 */
	@Test(timeout = 120000)
	public void assignmentRecordingRepeatedForward() {
		boolean original = DefaultCellularLayer.enableMemoryDataCopy;
		DefaultCellularLayer.enableMemoryDataCopy = false;

		try {
			double[] results = forwardDense(3);
			for (int i = 0; i < results.length; i++) {
				log("assignmentRecordingRepeatedForward pass=" + (i + 1) +
						" result=" + results[i] + " expected=" + EXPECTED);
			}
		} finally {
			DefaultCellularLayer.enableMemoryDataCopy = original;
		}
	}

	/**
	 * Confirms the lag semantics precisely: each pass supplies a different input,
	 * so a one-pass lag produces the previous pass's expected value while correct
	 * ordering produces the current pass's expected value. Inputs are (2, 3),
	 * (4, 6), and (8, 12); the correct outputs are 0.35, 0.6, and 1.1.
	 */
	@Test(timeout = 120000)
	public void assignmentRecordingVaryingInput() {
		boolean original = DefaultCellularLayer.enableMemoryDataCopy;
		DefaultCellularLayer.enableMemoryDataCopy = false;

		try {
			double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
			double[] expected = { 0.35, 0.6, 1.1 };
			double[] results = forwardDense(inputs);

			for (int i = 0; i < results.length; i++) {
				log("assignmentRecordingVaryingInput pass=" + (i + 1) +
						" result=" + results[i] + " expected=" + expected[i]);
			}
		} finally {
			DefaultCellularLayer.enableMemoryDataCopy = original;
		}
	}

	/**
	 * Hypothesis D: the one-pass lag enters through compile-time argument
	 * aggregation — the aggregate copy-in snapshots the isolated multiply's
	 * destination buffer before the multiply's evaluation for the current pass
	 * has written it. If disabling aggregation corrects the result, the
	 * coupling between aggregation copy-in and asynchronous argument delivery
	 * is the failing mechanism.
	 */
	@Test(timeout = 120000)
	public void assignmentRecordingWithoutAggregation() {
		boolean original = MemoryDataArgumentMap.enableArgumentAggregation;
		MemoryDataArgumentMap.enableArgumentAggregation = false;

		try {
			double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
			double[] expected = { 0.35, 0.6, 1.1 };
			double[] results;

			boolean recording = DefaultCellularLayer.enableMemoryDataCopy;
			DefaultCellularLayer.enableMemoryDataCopy = false;

			try {
				results = forwardDense(inputs);
			} finally {
				DefaultCellularLayer.enableMemoryDataCopy = recording;
			}

			for (int i = 0; i < results.length; i++) {
				log("assignmentRecordingWithoutAggregation pass=" + (i + 1) +
						" result=" + results[i] + " expected=" + expected[i]);
			}
		} finally {
			MemoryDataArgumentMap.enableArgumentAggregation = original;
		}
	}

	/**
	 * Standalone reproduction attempt without any model machinery: an
	 * {@link org.almostrealism.hardware.computations.Assignment} whose source is
	 * a computation containing an explicitly isolated multiply, evaluated twice
	 * with different input contents. A one-pass lag here localizes the defect to
	 * the Assignment/isolation compilation itself; correct values localize it to
	 * the model composite context.
	 */
	@Test(timeout = 120000)
	public void standaloneIsolatedSourceAssignment() {
		PackedCollection weights = new PackedCollection(shape(2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection in = new PackedCollection(shape(2));
		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection out = new PackedCollection(shape(1));

		CollectionProducer mul = multiply(
				traverseEach(p(weights)), traverseEach(p(in)));
		Producer<PackedCollection> isolated =
				new CollectionProducerComputation.IsolatedProcess(mul);
		CollectionProducer dense = add(
				sum(traverse(0, isolated)), p(bias));

		Runnable assign = a("standalone isolated", p(out), dense).get();
		log("standaloneIsolatedSourceAssignment runnable=" + assign.getClass().getSimpleName());

		in.setMem(0, 2.0, 3.0);
		assign.run();
		log("standaloneIsolatedSourceAssignment pass=1 result=" + out.toDouble(0) + " expected=0.35");

		in.setMem(0, 4.0, 6.0);
		assign.run();
		log("standaloneIsolatedSourceAssignment pass=2 result=" + out.toDouble(0) + " expected=0.6");

		in.setMem(0, 8.0, 12.0);
		assign.run();
		log("standaloneIsolatedSourceAssignment pass=3 result=" + out.toDouble(0) + " expected=1.1");
	}

	/**
	 * Bisection layer: the same two-member structure the model composite has —
	 * an input-record assignment followed by a dense assignment reading the
	 * recorded buffer — but built directly and passed through the same
	 * {@code flatten().optimize()} the model composite uses. A one-pass lag here
	 * localizes the defect to the OperationList optimization cascade; correct
	 * values localize it to the model/cell wiring.
	 */
	@Test(timeout = 120000)
	public void optimizedListRecordThenDense() {
		PackedCollection external = new PackedCollection(shape(2));
		PackedCollection recorded = new PackedCollection(shape(2));

		PackedCollection weights = new PackedCollection(shape(2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection out = new PackedCollection(shape(1));

		CollectionProducer mul = multiply(
				traverseEach(p(weights)), traverseEach(p(recorded)));
		CollectionProducer dense = add(sum(traverse(0, mul)), p(bias));

		OperationList list = new OperationList("optimizedListRecordThenDense");
		list.add(a("record", traverseEach(p(recorded)), traverseEach(p(external))));
		list.add(a("dense", p(out), dense));

		Runnable run = ((ParallelProcess<?, Runnable>) list.flatten().optimize()).get();

		double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
		double[] expected = { 0.35, 0.6, 1.1 };

		for (int i = 0; i < inputs.length; i++) {
			a(cp(external), c(inputs[i][0], inputs[i][1])).get().run();
			run.run();
			log("optimizedListRecordThenDense pass=" + (i + 1) +
					" result=" + out.toDouble(0) + " expected=" + expected[i]);
		}
	}

	/**
	 * Bisection variant A: the record-then-dense optimized list, with the dense
	 * member using the real dense-layer operator tree
	 * ({@code matmul(weights, input).add(bias)}) instead of the hand-built
	 * multiply/sum. Isolates whether the matmul tree shape is the trigger.
	 */
	@Test(timeout = 120000)
	public void optimizedListRecordThenMatmul() {
		PackedCollection external = new PackedCollection(shape(2));
		PackedCollection recorded = new PackedCollection(shape(2));

		PackedCollection weights = new PackedCollection(shape(1, 2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection out = new PackedCollection(shape(1));

		CollectionProducer dense = matmul(p(weights),
				reshape(shape(1, 2).traverse(1), p(recorded)))
				.add(traverse(1, p(bias)))
				.reshape(shape(1));

		OperationList list = new OperationList("optimizedListRecordThenMatmul");
		list.add(a("record", traverseEach(p(recorded)), traverseEach(p(external))));
		list.add(a("dense", p(out), dense));

		Runnable run = ((ParallelProcess<?, Runnable>) list.flatten().optimize()).get();

		double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
		double[] expected = { 0.35, 0.6, 1.1 };

		for (int i = 0; i < inputs.length; i++) {
			a(cp(external), c(inputs[i][0], inputs[i][1])).get().run();
			run.run();
			log("optimizedListRecordThenMatmul pass=" + (i + 1) +
					" result=" + out.toDouble(0) + " expected=" + expected[i]);
		}
	}

	/**
	 * Bisection variant B: the record-then-dense optimized list, with the record
	 * member's source supplied by a {@link DynamicCollectionProducer} (as the
	 * model's InputManager supplies it) instead of a provider. Isolates whether
	 * the dynamic input source is the trigger.
	 */
	@Test(timeout = 120000)
	public void optimizedListDynamicRecordThenDense() {
		PackedCollection[] external = new PackedCollection[1];
		PackedCollection recorded = new PackedCollection(shape(2));

		PackedCollection weights = new PackedCollection(shape(2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection out = new PackedCollection(shape(1));

		DynamicCollectionProducer dynamicInput =
				new DynamicCollectionProducer(shape(2), args -> external[0]);

		CollectionProducer mul = multiply(
				traverseEach(p(weights)), traverseEach(p(recorded)));
		CollectionProducer dense = add(sum(traverse(0, mul)), p(bias));

		OperationList list = new OperationList("optimizedListDynamicRecordThenDense");
		list.add(a("record", traverseEach(p(recorded)), traverseEach(dynamicInput)));
		list.add(a("dense", p(out), dense));

		Runnable run = ((ParallelProcess<?, Runnable>) list.flatten().optimize()).get();

		double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
		double[] expected = { 0.35, 0.6, 1.1 };

		for (int i = 0; i < inputs.length; i++) {
			external[0] = new PackedCollection(shape(2));
			a(cp(external[0]), c(inputs[i][0], inputs[i][1])).get().run();
			run.run();
			log("optimizedListDynamicRecordThenDense pass=" + (i + 1) +
					" result=" + out.toDouble(0) + " expected=" + expected[i]);
		}
	}

	/**
	 * Bisection variant C: replicates the layer cell wiring — nested operation
	 * lists with {@code into(...)} members exactly as {@link DefaultCellularLayer}
	 * builds them (dynamic-source input record, matmul output record, model
	 * output capture) — without any Model or Cell machinery. A one-pass lag here
	 * gives a minimal reproduction of the model composite defect.
	 */
	@Test(timeout = 120000)
	public void optimizedNestedIntoComposite() {
		PackedCollection[] external = new PackedCollection[1];
		PackedCollection recorded = new PackedCollection(shape(2));

		PackedCollection weights = new PackedCollection(shape(1, 2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection layerOut = new PackedCollection(shape(1));
		PackedCollection modelOut = new PackedCollection(shape(1));

		DynamicCollectionProducer dynamicInput =
				new DynamicCollectionProducer(shape(2), args -> external[0]);

		CollectionProducer dense = matmul(p(weights),
				reshape(shape(1, 2).traverse(1), p(recorded)))
				.add(traverse(1, p(bias)))
				.reshape(shape(1));

		OperationList entry = new OperationList("entry");
		entry.add(into("record", dynamicInput, p(recorded), false));
		entry.add(into("dense out", dense, p(layerOut), false));

		OperationList forward = new OperationList("forward");
		forward.add(entry);
		forward.add(a("model out", p(modelOut), p(layerOut)));

		Runnable run = ((ParallelProcess<?, Runnable>) forward.flatten().optimize()).get();

		double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
		double[] expected = { 0.35, 0.6, 1.1 };

		for (int i = 0; i < inputs.length; i++) {
			external[0] = new PackedCollection(shape(2));
			a(cp(external[0]), c(inputs[i][0], inputs[i][1])).get().run();
			run.run();
			log("optimizedNestedIntoComposite pass=" + (i + 1) +
					" result=" + modelOut.toDouble(0) + " expected=" + expected[i]);
		}
	}

	/**
	 * Runs the minimal composite reproduction with selectable ingredients and
	 * logs per-pass results, so single-ingredient variants can be compared.
	 *
	 * @param label       log label for this variant
	 * @param useInto     build the record/dense members with {@code into(...)} (true) or {@code a(...)} (false)
	 * @param nested      place the record/dense members in a nested entry list (true) or directly in the forward list (false)
	 * @param dynamic     supply the record source from a {@link DynamicCollectionProducer} (true) or a provider (false)
	 * @param thirdMember append the model-output capture assignment (true) or omit it (false)
	 */
	private void runCompositeVariant(String label, boolean useInto, boolean nested,
									 boolean dynamic, boolean thirdMember) {
		PackedCollection[] external = new PackedCollection[1];
		PackedCollection providerExternal = new PackedCollection(shape(2));
		PackedCollection recorded = new PackedCollection(shape(2));

		PackedCollection weights = new PackedCollection(shape(1, 2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection layerOut = new PackedCollection(shape(1));
		PackedCollection modelOut = new PackedCollection(shape(1));

		Producer<PackedCollection> source = dynamic ?
				new DynamicCollectionProducer(shape(2), args -> external[0]) :
				traverseEach(p(providerExternal));

		CollectionProducer dense = matmul(p(weights),
				reshape(shape(1, 2).traverse(1), p(recorded)))
				.add(traverse(1, p(bias)))
				.reshape(shape(1));

		OperationList members = new OperationList("members");
		if (useInto) {
			members.add(into("record", source, p(recorded), false));
			members.add(into("dense out", dense, p(layerOut), false));
		} else {
			members.add(a("record", traverseEach(p(recorded)), source));
			members.add(a("dense out", p(layerOut), dense));
		}

		OperationList forward = new OperationList("forward");
		if (nested) {
			forward.add(members);
		} else {
			forward.add(members.get(0));
			forward.add(members.get(1));
		}

		if (thirdMember) {
			forward.add(a("model out", p(modelOut), p(layerOut)));
		}

		Runnable run = ((ParallelProcess<?, Runnable>) forward.flatten().optimize()).get();

		if (run instanceof OperationListRunner) {
			List<Runnable> ops = ((OperationListRunner) run).getOperations();
			for (int i = 0; i < ops.size(); i++) {
				log(label + " member=" + i + " type=" + ops.get(i).getClass().getSimpleName() +
						" display=" + OperationInfo.display(ops.get(i)));
			}
		} else {
			log(label + " runner=" + run.getClass().getSimpleName());
		}

		PackedCollection result = thirdMember ? modelOut : layerOut;
		double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
		double[] expected = { 0.35, 0.6, 1.1 };

		for (int i = 0; i < inputs.length; i++) {
			if (dynamic) {
				external[0] = new PackedCollection(shape(2));
				a(cp(external[0]), c(inputs[i][0], inputs[i][1])).get().run();
			} else {
				a(cp(providerExternal), c(inputs[i][0], inputs[i][1])).get().run();
			}

			run.run();
			log(label + " pass=" + (i + 1) +
					" result=" + result.toDouble(0) + " expected=" + expected[i] +
					" recorded=" + recorded.toDouble(0) + "," + recorded.toDouble(1));
		}
	}

	/**
	 * Single-ingredient variants of the minimal composite reproduction. The
	 * failing configuration is (into, nested, dynamic, third); each variant
	 * removes exactly one ingredient to identify which is load-bearing.
	 */
	@Test(timeout = 240000)
	public void compositeVariants() {
		runCompositeVariant("variantAll(into,nested,dynamic,third)", true, true, true, true);
		runCompositeVariant("variantNoInto(a,nested,dynamic,third)", false, true, true, true);
		runCompositeVariant("variantFlat(into,flat,dynamic,third)", true, false, true, true);
		runCompositeVariant("variantProvider(into,nested,provider,third)", true, true, false, true);
		runCompositeVariant("variantNoThird(into,nested,dynamic)", true, true, true, false);
	}

	/**
	 * The variant the earlier passing configurations never exercised: a record
	 * member that writes the input buffer, followed by a dense member whose
	 * multiply is explicitly isolated — with a plain provider source, so any
	 * failure is attributable purely to the isolated child's evaluation being
	 * mis-ordered against the preceding member's write.
	 */
	@Test(timeout = 120000)
	public void optimizedListRecordThenIsolatedDense() {
		PackedCollection external = new PackedCollection(shape(2));
		PackedCollection recorded = new PackedCollection(shape(2));

		PackedCollection weights = new PackedCollection(shape(2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection out = new PackedCollection(shape(1));

		CollectionProducer mul = multiply(
				traverseEach(p(weights)), traverseEach(p(recorded)));
		Producer<PackedCollection> isolated =
				new CollectionProducerComputation.IsolatedProcess(mul);
		CollectionProducer dense = add(sum(traverse(0, isolated)), p(bias));

		OperationList list = new OperationList("optimizedListRecordThenIsolatedDense");
		list.add(a("record", traverseEach(p(recorded)), traverseEach(p(external))));
		list.add(a("dense", p(out), dense));

		Runnable run = ((ParallelProcess<?, Runnable>) list.flatten().optimize()).get();

		double[][] inputs = { { 2.0, 3.0 }, { 4.0, 6.0 }, { 8.0, 12.0 } };
		double[] expected = { 0.35, 0.6, 1.1 };

		for (int i = 0; i < inputs.length; i++) {
			a(cp(external), c(inputs[i][0], inputs[i][1])).get().run();
			run.run();
			log("optimizedListRecordThenIsolatedDense pass=" + (i + 1) +
					" result=" + out.toDouble(0) + " expected=" + expected[i]);
		}
	}

	/**
	 * Control for the standalone reproduction: the same computation with no
	 * explicit isolation.
	 */
	@Test(timeout = 120000)
	public void standaloneInlineSourceAssignment() {
		PackedCollection weights = new PackedCollection(shape(2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection in = new PackedCollection(shape(2));
		PackedCollection bias = new PackedCollection(shape(1));
		bias.setMem(0, 0.1);

		PackedCollection out = new PackedCollection(shape(1));

		CollectionProducer mul = multiply(
				traverseEach(p(weights)), traverseEach(p(in)));
		CollectionProducer dense = add(sum(traverse(0, mul)), p(bias));

		Runnable assign = a("standalone inline", p(out), dense).get();
		log("standaloneInlineSourceAssignment runnable=" + assign.getClass().getSimpleName());

		in.setMem(0, 2.0, 3.0);
		assign.run();
		log("standaloneInlineSourceAssignment pass=1 result=" + out.toDouble(0) + " expected=0.35");

		in.setMem(0, 4.0, 6.0);
		assign.run();
		log("standaloneInlineSourceAssignment pass=2 result=" + out.toDouble(0) + " expected=0.6");
	}

	/**
	 * Reproduces the CI failure signature for the norm model (NormTests.normModel)
	 * under default copy-based recording: the norm layer's shapes route both of
	 * its recording copies through the assignment branch of {@code into(...)},
	 * so after the CompiledModel output-capture migration every forward member is
	 * a computation and the composite fuses. With identical input on every pass,
	 * a fused composite suffering the isolated-argument lag produces a wrong
	 * first pass and stable later passes; a correct composite produces identical
	 * output on every pass.
	 */
	@Test(timeout = 120000)
	public void normModelRepeatedForward() {
		int c = 12;
		int v = 10;
		int groups = 4;

		PackedCollection weights = new PackedCollection(shape(c * v)).randnFill();
		PackedCollection biases = new PackedCollection(shape(c * v)).randnFill();
		PackedCollection in = new PackedCollection(shape(c, v)).randnFill();

		Model model = new Model(shape(c, v));
		model.add(norm(groups, weights, biases));

		CompiledModel compiled = model.compile(true, null);

		double[] first = new double[3];
		for (int i = 0; i < 3; i++) {
			PackedCollection out = compiled.forward(in.reshape(compiled.getInputShape()));
			first[i] = out.toDouble(0);
			log("normModelRepeatedForward pass=" + (i + 1) + " out0=" + first[i]);
		}

		log("normModelRepeatedForward lagSignature=" +
				(first[0] != first[1] && first[1] == first[2]));
		compiled.destroy();
	}

	/**
	 * Control: copy-based recording with default settings, which is expected
	 * to produce the correct value.
	 */
	@Test(timeout = 120000)
	public void copyRecordingControl() {
		boolean original = DefaultCellularLayer.enableMemoryDataCopy;
		DefaultCellularLayer.enableMemoryDataCopy = true;

		try {
			double result = forwardDense();
			log("copyRecordingControl result=" + result + " expected=" + EXPECTED);
		} finally {
			DefaultCellularLayer.enableMemoryDataCopy = original;
		}
	}

	/**
	 * Runs the forward pass with assignment-based recording enabled, restoring
	 * the recording mode afterward.
	 *
	 * @return the forward output value
	 */
	private double forwardWithAssignmentRecording() {
		boolean original = DefaultCellularLayer.enableMemoryDataCopy;
		DefaultCellularLayer.enableMemoryDataCopy = false;

		try {
			return forwardDense();
		} finally {
			DefaultCellularLayer.enableMemoryDataCopy = original;
		}
	}

	/**
	 * Builds the fixed {@code dense(2, 1)} model and runs one forward pass.
	 *
	 * @return the forward output value
	 */
	private double forwardDense() {
		return forwardDense(1)[0];
	}

	/**
	 * Builds the fixed {@code dense(2, 1)} model and runs the requested number of
	 * forward passes with identical input, returning the output of each pass.
	 *
	 * @param passes the number of forward passes to run
	 * @return the forward output value of each pass, in order
	 */
	private double[] forwardDense(int passes) {
		double[][] inputs = new double[passes][];
		for (int i = 0; i < passes; i++) {
			inputs[i] = new double[] { 2.0, 3.0 };
		}

		return forwardDense(inputs);
	}

	/**
	 * Builds the fixed {@code dense(2, 1)} model and runs one forward pass per
	 * supplied input, returning the output of each pass.
	 *
	 * @param inputs the input values for each pass (each of length 2)
	 * @return the forward output value of each pass, in order
	 */
	private double[] forwardDense(double[][] inputs) {
		PackedCollection weights = new PackedCollection(shape(1, 2));
		weights.setMem(0, 0.5, -0.25);

		PackedCollection biases = new PackedCollection(shape(1));
		biases.setMem(0, 0.1);

		CellularLayer layer = dense(weights, biases).apply(shape(2));

		SequentialBlock block = new SequentialBlock(shape(2));
		block.add(layer);

		Model model = new Model(shape(2), 0.1);
		model.add(block);

		CompiledModel compiled = model.compile(true,
				new OperationProfile("assignmentIsolationDiag"));

		PackedCollection input = new PackedCollection(shape(2));

		double[] results = new double[inputs.length];
		for (int i = 0; i < inputs.length; i++) {
			a(cp(input), c(inputs[i][0], inputs[i][1])).get().run();
			PackedCollection out = compiled.forward(input);
			results[i] = out.toDouble(0);
		}

		compiled.destroy();
		return results;
	}
}
