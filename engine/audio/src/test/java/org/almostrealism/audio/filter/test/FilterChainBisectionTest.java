/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 */

package org.almostrealism.audio.filter.test;

import io.almostrealism.lifecycle.Setup;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.function.Supplier;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.test.support.TestAudioData;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.audio.data.AudioFilterData;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.graph.CellAdapter;
import org.almostrealism.graph.FilteredCell;
import org.almostrealism.heredity.CellularTemporalFactor;
import org.almostrealism.heredity.TemporalFactor;
import org.almostrealism.hardware.HardwareFeatures;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Temporal;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.function.IntFunction;

/**
 * Layered bisection tests for the audio filter cell graph. The layers let a
 * regression be localized by which tests fail: if only a lower layer fails, the
 * upper layers' regressions are symptoms of the same root cause and can be
 * ignored. Every test asserts a concrete non-zero / equality condition so
 * silent output is never acceptable.
 *
 * <h2>Layer A — AudioPassFilter primitives ({@code apf01-08})</h2>
 * The biquad filter itself, in isolation. Covers coefficient precomputation
 * ({@code apf01}), per-sample tick in both Java and compiled loops
 * ({@code apf02}, {@code apf03}), state independence between instances
 * ({@code apf04}), PackedCollection memory persistence inside a compiled loop
 * ({@code apf05}, {@code apf06}), IIR-style self-reference in a compiled loop
 * ({@code apf07}), and sequential-assignment semantics in a compiled body
 * ({@code apf08}). A failure here indicates a regression in the filter kernel
 * or in the underlying Assignment/OperationList machinery.
 *
 * <h2>Layer B — FilteredCell wiring ({@code fc01-02})</h2>
 * {@link org.almostrealism.graph.FilteredCell} delegation: setup forwarding
 * ({@code fc01}) and tick updating the wrapped filter's output
 * ({@code fc02}). A failure here indicates a regression in FilteredCell's
 * setup/tick forwarding, not in the filter itself.
 *
 * <h2>Layer C — Chain shapes with a controllable source ({@code chain01-21})</h2>
 * {@link CellList} composition, {@link org.almostrealism.heredity.CombinedFactor}
 * sequencing, {@link org.almostrealism.graph.SummationCell} aggregation, and
 * receptor wiring, using a deterministic {@link ProbeCell} source so that
 * failures here can only come from the cell-graph layer. Covers single and
 * multi-source chains, identity and MixdownManager-shaped composite factors,
 * hp-first and volume-first orderings, producer-valued cutoffs, compiled
 * {@link org.almostrealism.hardware.HardwareFeatures#loop} execution, and
 * varying source signals. A failure here but not in A/B indicates a regression
 * in the cell graph or factor composition.
 *
 * <h2>Layer D — The narrow reproducer ({@code chain22-24})</h2>
 * {@link org.almostrealism.graph.temporal.WaveCell} with an external frame
 * producer feeding a {@link org.almostrealism.graph.FilteredCell} wrapping an
 * {@link AudioPassFilter}, compiled inside a
 * {@link org.almostrealism.hardware.HardwareFeatures#loop}. This is the minimal
 * shape that triggered the {@code CachedStateCell.tick()} deferred-evaluation
 * bug, where the cached value was pushed to the receptor and then reset to
 * zero in the same tick — leaving the filter's stored input reference
 * evaluating to zero every sample. {@code chain22} uses high-pass,
 * {@code chain23} uses low-pass, {@code chain24} chains hp→lp. A failure in
 * {@code chain22} with Layer A/B/C green indicates the deferred-evaluation
 * bug has returned.
 *
 * <h2>Layer E — Generalization ({@code chain25-26})</h2>
 * {@code chain25} runs multiple WaveCell sources with independent filters in
 * parallel, verifying per-source filter state isolation. {@code chain26} uses
 * the {@link org.almostrealism.graph.SummationCell} fast path (no filter
 * between source and sum) to verify the fast path was not disturbed by the
 * Layer-D fix. A {@code chain26} failure without a {@code chain22} failure
 * indicates the fast path regressed.
 *
 * <h2>Integration coverage</h2>
 * The end-to-end contract is asserted in
 * {@code OptimizerSceneDiagnosticTest.optimizerSetFeatureLevel7ProducesAudio}
 * (full AudioScene render with all features enabled) and the
 * {@code renderBufferPopulation*} family (MixdownManager-level bisection of
 * individual effect flags). Those tests catch regressions that escape every
 * layer above.
 */
public class FilterChainBisectionTest extends TestSuiteBase implements CellFeatures {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;

	/** A1: AudioPassFilter setup() pre-computes a non-zero a1 coefficient. */
	/**
	 * A1: tick() computes a non-zero a1 coefficient on the first invocation.
	 * The biquad coefficients ({@code c}, {@code a1}, {@code a2}, {@code a3},
	 * {@code b1}, {@code b2}) must be written to the filter's data storage
	 * before the IIR math reads them; verifying a1 != 0 after one tick confirms
	 * the coefficient-computation ops fired.
	 */
	@Test(timeout = 30000)
	public void apf01_tickComputesA1() {
		PackedCollection input = new PackedCollection(1);
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
		filter.getResultant(p(input)).get();

		input.setMem(0, 0.7);
		filter.tick().get().run();

		double a1 = readA1(filter);
		Assert.assertTrue("tick() should set a1 to non-zero (got " + a1 + ")",
				Math.abs(a1) > 0.0);
	}

	/** A2: tick() writes data.output non-zero for a non-zero input (Java loop). */
	@Test(timeout = 30000)
	public void apf02_javaLoopWritesOutput() {
		PackedCollection input = new PackedCollection(1);
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);

		Evaluable<PackedCollection> ev = filter.getResultant(p(input)).get();
		Runnable tick = filter.tick().get();

		input.setMem(0, 0.7);
		tick.run();
		double afterFirstTick = ev.evaluate().toDouble(0);

		Assert.assertTrue("data.output non-zero after one Java tick (got " + afterFirstTick + ")",
				Math.abs(afterFirstTick) > 0.0);
	}

	/** A3: tick() writes data.output non-zero when run inside a compiled Loop body. */
	@Test(timeout = 60000)
	public void apf03_compiledLoopWritesOutput() {
		PackedCollection input = new PackedCollection(1);
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);

		Evaluable<PackedCollection> ev = filter.getResultant(p(input)).get();

		OperationList body = new OperationList("apf03 body");
		body.add(filter.tick());
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 100).get();

		input.setMem(0, 0.7);
		compiled.run();

		double afterRun = ev.evaluate().toDouble(0);
		Assert.assertTrue("data.output non-zero after compiled loop run (got " + afterRun + ")",
				Math.abs(afterRun) > 0.0);
	}

	/** A4: Two separate filters in same compiled body have independent state. */
	@Test(timeout = 60000)
	public void apf04_twoFiltersIndependentState() {
		PackedCollection input1 = new PackedCollection(1);
		PackedCollection input2 = new PackedCollection(1);
		AudioPassFilter f1 = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
		AudioPassFilter f2 = new AudioPassFilter(SAMPLE_RATE, c(500), scalar(0.1), true);

		Evaluable<PackedCollection> ev1 = f1.getResultant(p(input1)).get();
		Evaluable<PackedCollection> ev2 = f2.getResultant(p(input2)).get();

		OperationList body = new OperationList("apf04 body");
		body.add(f1.tick());
		body.add(f2.tick());
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 100).get();

		input1.setMem(0, 0.7);
		input2.setMem(0, 0.3);
		compiled.run();

		double o1 = ev1.evaluate().toDouble(0);
		double o2 = ev2.evaluate().toDouble(0);
		Assert.assertNotEquals("Independent filters should produce different outputs",
				o1, o2, 1e-9);
	}

	/**
	 * B1: FilteredCell.setup() is forwarded from the wrapped filter when the
	 * filter implements {@link io.almostrealism.lifecycle.Setup}. Verifies
	 * that running the wrapper's setup populates the underlying filter's a1
	 * coefficient.
	 */
	@Test(timeout = 30000)
	public void fc01_setupForwarded() {
		PackedCollection input = new PackedCollection(1);
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
		filter.getResultant(p(input)).get();

		FilteredCell<PackedCollection> cell = new FilteredCell<>(filter);
		cell.setup().get().run();

		double a1 = readA1(filter);
		Assert.assertTrue("FilteredCell.setup should run filter.setup (a1=" + a1 + ")",
				Math.abs(a1) > 0.0);
	}

	/**
	 * B2: FilteredCell.tick() updates the wrapped filter's data.output.
	 * Verifies the FilteredCell→Temporal delegation: a single tick on the
	 * wrapper must advance the filter's state and write a non-zero sample
	 * to the filter's output memory.
	 */
	@Test(timeout = 30000)
	public void fc02_tickUpdatesOutput() {
		PackedCollection input = new PackedCollection(1);
		AudioPassFilter filter = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
		Evaluable<PackedCollection> ev = filter.getResultant(p(input)).get();

		FilteredCell<PackedCollection> cell = new FilteredCell<>(filter);

		input.setMem(0, 0.7);
		cell.tick().get().run();
		double after = ev.evaluate().toDouble(0);
		Assert.assertTrue("FilteredCell.tick should update output (got " + after + ")",
				Math.abs(after) > 0.0);
	}

	/**
	 * D1: cells.map(fc(hp)) chain — feed a fixed input into each source cell,
	 * tick the chain, verify the FilteredCell output is non-zero.
	 *
	 * <p>This is the closest narrow reproducer of MixdownManager's pattern.</p>
	 */
	@Test(timeout = 60000)
	public void chain01_mapFcHpProducesOutput() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		// A trivial "source" cell that just holds a value.
		ProbeCell src = new ProbeCell(p(sourceVal));

		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			AudioPassFilter f = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = f;
			return f;
		};

		CellList mapped = sources.map(fc(hpFactory));

		// Run the chain like CellList.tick() would: root pushes then temporals.
		OperationList all = new OperationList("chain01");
		mapped.getAllSetup().forEach(s -> all.add(s.setup()));
		mapped.getAllRoots().forEach(r -> all.add(r.push(c(0.0))));
		all.add(mapped.getAllTemporals().tick());
		all.get().run();

		double output = readOutput(filterCapture[0]);
		Assert.assertTrue("cells.map(fc(hp)) chain should produce non-zero filter output (got "
				+ output + ")", Math.abs(output) > 0.0);
	}

	private static double readOutput(AudioPassFilter filter) {
		try {
			Field f = AudioPassFilter.class.getDeclaredField("data");
			f.setAccessible(true);
			AudioFilterData d = (AudioFilterData) f.get(filter);
			return d.output().toDouble(0);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * A5: Verify that PackedCollection writes inside a compiled Loop persist
	 * across iterations (filter behavior depends on this).
	 */
	@Test(timeout = 30000)
	public void apf05_packedCollectionWritesPersistInCompiledLoop() {
		PackedCollection state = new PackedCollection(1);
		state.setMem(0, 0.0);

		// Body: state = state + 1
		OperationList body = new OperationList("apf05 body");
		body.add(a(p(state), c(1.0).add(p(state))));
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 100).get();
		compiled.run();

		double finalState = state.toDouble(0);
		Assert.assertEquals("Compiled loop should accumulate to 100", 100.0, finalState, 1e-6);
	}

	/**
	 * A6: Verify two PackedCollections both update inside a single compiled
	 * loop body (no aliasing or one being elided as dead).
	 */
	@Test(timeout = 30000)
	public void apf06_twoStatesBothPersist() {
		PackedCollection a = new PackedCollection(1);
		PackedCollection b = new PackedCollection(1);
		a.setMem(0, 0.0);
		b.setMem(0, 0.0);

		OperationList body = new OperationList("apf06 body");
		body.add(a(p(a), c(1.0).add(p(a))));
		body.add(a(p(b), c(2.0).add(p(b))));
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 50).get();
		compiled.run();

		Assert.assertEquals("a should be 50", 50.0, a.toDouble(0), 1e-6);
		Assert.assertEquals("b should be 100", 100.0, b.toDouble(0), 1e-6);
	}

	/**
	 * A7: Self-referential update — y = a*x + (-b)*y_prev — does the
	 * IIR-style assignment work in a compiled loop? (Mirrors the biquad
	 * output formula y[n] = a1*x[n] - b1*y[n-1].)
	 */
	@Test(timeout = 30000)
	public void apf07_iirStyleSelfReferenceInCompiledLoop() {
		PackedCollection x = new PackedCollection(1);
		PackedCollection y = new PackedCollection(1);
		x.setMem(0, 1.0);
		y.setMem(0, 0.0);

		double a1 = 0.5;
		double b1 = -0.3;

		OperationList body = new OperationList("apf07 body");
		body.add(a(p(y), subtract(multiply(c(a1), cp(x)), multiply(c(b1), cp(y)))));
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 10).get();
		compiled.run();

		double finalY = y.toDouble(0);
		Assert.assertNotEquals("IIR self-update should produce non-zero accumulated value",
				0.0, finalY, 1e-9);
	}

	/**
	 * A8: Two SEPARATE assignments to the same target — does the second
	 * one override the first within the same body? Bisects whether the
	 * coefficient ops in tick (which assign c, then a1 reading c, then
	 * ...) sequence correctly.
	 */
	@Test(timeout = 30000)
	public void apf08_sequentialAssignmentReadsLatest() {
		PackedCollection target = new PackedCollection(1);
		PackedCollection out = new PackedCollection(1);
		target.setMem(0, 0.0);
		out.setMem(0, 0.0);

		OperationList body = new OperationList("apf08 body");
		// First: target = 5
		body.add(a(p(target), c(5.0)));
		// Second: out = target * 2 (should be 10 if target was just set to 5)
		body.add(a(p(out), multiply(cp(target), c(2.0))));
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 1).get();
		compiled.run();

		double targetVal = target.toDouble(0);
		double outVal = out.toDouble(0);
		Assert.assertEquals("target should be 5", 5.0, targetVal, 1e-9);
		Assert.assertEquals("out should be 10 (target * 2 with sequential semantics)",
				10.0, outVal, 1e-9);
	}

	/**
	 * D2: cells.map(fc(hp)).sum() — verify the SummationCell receives non-zero
	 * data from the filtered cell. Captures sum's output via a tap receptor.
	 */
	@Test(timeout = 60000)
	public void chain02_mapFcHpSumProducesOutput() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			AudioPassFilter f = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = f;
			return f;
		};

		PackedCollection sumOutput = new PackedCollection(1);
		CellList chain = sources.map(fc(hpFactory)).sum().map(i -> new TapCell(sumOutput));

		OperationList setup = new OperationList("chain02 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		// Many iterations — SummationCell + filter has 1-sample lag, so sum
		// receives 0 on iteration 0 then non-zero from iteration 1.
		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double sumVal = sumOutput.toDouble(0);
		double filterOut = readOutput(filterCapture[0]);
		Assert.assertTrue("sum should receive non-zero after 100 ticks (filterOut="
				+ filterOut + ", sumVal=" + sumVal + ")", Math.abs(sumVal) > 0.0);
	}

	/**
	 * D3: Multi-source — three sources through three hp filters, sum'd. All
	 * filters should produce non-zero independent state, and sum should receive
	 * non-zero accumulated value.
	 */
	@Test(timeout = 60000)
	public void chain03_multiSourceMapFcHp() {
		PackedCollection s1 = new PackedCollection(1); s1.setMem(0, 0.2);
		PackedCollection s2 = new PackedCollection(1); s2.setMem(0, 0.4);
		PackedCollection s3 = new PackedCollection(1); s3.setMem(0, 0.6);

		ProbeCell src1 = new ProbeCell(p(s1));
		ProbeCell src2 = new ProbeCell(p(s2));
		ProbeCell src3 = new ProbeCell(p(s3));
		CellList sources = new CellList();
		sources.addRoot(src1);
		sources.addRoot(src2);
		sources.addRoot(src3);

		AudioPassFilter[] filters = new AudioPassFilter[3];
		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			AudioPassFilter f = new AudioPassFilter(SAMPLE_RATE, c(100 + i * 50), scalar(0.1), true);
			filters[i] = f;
			return f;
		};

		CellList mapped = sources.map(fc(hpFactory));

		OperationList all = new OperationList("chain03");
		mapped.getAllSetup().forEach(s -> all.add(s.setup()));
		mapped.getAllRoots().forEach(r -> all.add(r.push(c(0.0))));
		all.add(mapped.getAllTemporals().tick());
		all.get().run();

		double[] outputs = new double[3];
		for (int i = 0; i < 3; i++) outputs[i] = readOutput(filters[i]);

		Assert.assertTrue("filter 0 non-zero (got " + outputs[0] + ")", Math.abs(outputs[0]) > 0.0);
		Assert.assertTrue("filter 1 non-zero (got " + outputs[1] + ")", Math.abs(outputs[1]) > 0.0);
		Assert.assertTrue("filter 2 non-zero (got " + outputs[2] + ")", Math.abs(outputs[2]) > 0.0);
		Assert.assertNotEquals("filters should be independent", outputs[0], outputs[1], 1e-9);
	}

	/**
	 * D4: cells.map(fc(hp)) where the SAME hp Factor expression mirrors what
	 * MixdownManager produces — cutoff = scalar(20000) * gene_value, where
	 * gene_value comes from a held PackedCollection. Tests the realistic
	 * cutoff-via-multiply pattern.
	 */
	@Test(timeout = 60000)
	public void chain04_mapFcHpWithMultipliedCutoff() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);
		PackedCollection geneVal = new PackedCollection(1);
		geneVal.setMem(0, 0.005);  // gene * 20000 = 100 Hz cutoff

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			Producer<PackedCollection> cutoff = multiply(c(20000.0), cp(geneVal));
			AudioPassFilter f = new AudioPassFilter(SAMPLE_RATE, cutoff, scalar(0.1), true);
			filterCapture[0] = f;
			return f;
		};

		CellList mapped = sources.map(fc(hpFactory));

		OperationList all = new OperationList("chain04");
		mapped.getAllSetup().forEach(s -> all.add(s.setup()));
		mapped.getAllRoots().forEach(r -> all.add(r.push(c(0.0))));
		all.add(mapped.getAllTemporals().tick());
		all.get().run();

		double output = readOutput(filterCapture[0]);
		Assert.assertTrue("multiplied-cutoff hp should produce non-zero (got " + output + ")",
				Math.abs(output) > 0.0);
	}

	/** A minimal cell that captures the value pushed to it into an output collection. */
	private class TapCell extends CellAdapter<PackedCollection> {
		private final PackedCollection capture;
		TapCell(PackedCollection capture) { this.capture = capture; }

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> protein) {
			OperationList ops = new OperationList("TapCell");
			ops.add(a(p(capture), protein));
			return ops;
		}
	}

	/**
	 * D5: cells.map(fc(hp)).sum() then map ReceptorCell that taps to PackedCollection.
	 * Mirrors MixdownManager's "deliver to master" final stage.
	 */
	@Test(timeout = 60000)
	public void chain05_mapHpSumReceptor() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			AudioPassFilter f = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = f;
			return f;
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(hpFactory)).sum().map(i -> new TapCell(captured));

		OperationList setup = new OperationList("chain05 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		// 100 iterations to clear the SummationCell + filter 1-sample lag.
		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double filterOut = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("captured non-zero after 100 ticks (filterOut=" + filterOut
				+ ", capture=" + captureVal + ")", Math.abs(captureVal) > 0.0);
	}

	/**
	 * D6: Compose volume.andThen(hp) in a single fc — same pattern as
	 * MixdownManager when enableMainFilterUp=true. Tests if composition
	 * with TemporalFactor lambdas (volume) chains correctly with hp state.
	 */
	@Test(timeout = 60000)
	public void chain06_composedVolumeAndHp() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		// Mimic MixdownManager exactly: volume = TemporalFactor (so andThen returns CombinedFactor)
		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			TemporalFactor<PackedCollection> volume =
					in -> multiply(in, c(1.0));
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = hp;
			return volume.andThen(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain06 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double output = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("composed volume+hp should produce non-zero "
				+ "(output=" + output + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D7: Run the chain MANY iterations via cell graph wrapper. Each call to
	 * cells.tick() runs the push+tick cycle once. Verify state accumulates.
	 */
	@Test(timeout = 60000)
	public void chain07_manyIterationsViaCellListTick() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			AudioPassFilter f = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = f;
			return f;
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(hpFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setupOps = new OperationList("chain07 setup");
		chain.getAllSetup().forEach(s -> setupOps.add(s.setup()));
		setupOps.get().run();

		Runnable tickRun = chain.tick().get();
		for (int i = 0; i < 100; i++) tickRun.run();

		double output = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("hp should produce non-zero after 100 ticks "
				+ "(output=" + output + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D8: cells.tick() compiled inside a HardwareFeatures.loop kernel — exact
	 * pattern AudioScene.runnerRealTime uses.
	 */
	@Test(timeout = 60000)
	public void chain08_cellListTickCompiledInLoop() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			AudioPassFilter f = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = f;
			return f;
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(hpFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setupOps = new OperationList("chain08 setup");
		chain.getAllSetup().forEach(s -> setupOps.add(s.setup()));
		setupOps.get().run();

		OperationList body = new OperationList("chain08 body");
		body.add(chain.tick());
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 100).get();
		compiled.run();

		double output = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("hp should produce non-zero after 100 compiled ticks "
				+ "(output=" + output + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	private static double readA1(AudioPassFilter filter) {
		try {
			Field f = AudioPassFilter.class.getDeclaredField("data");
			f.setAccessible(true);
			AudioFilterData d = (AudioFilterData) f.get(filter);
			return d.a1().toDouble(0);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * D9: CombinedFactor(identity, identity) with NO AudioPassFilter —
	 * bisects whether ANY CombinedFactor causes the compilation failure, or
	 * whether the issue is specifically with wrapping AudioPassFilter.
	 */
	@Test(timeout = 60000)
	public void chain09_combinedFactorIdentityNoFilter() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		PackedCollection captured = new PackedCollection(1);

		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			TemporalFactor<PackedCollection> a = in -> multiply(in, c(1.0));
			TemporalFactor<PackedCollection> b = in -> multiply(in, c(2.0));
			return a.andThen(b);
		};

		CellList chain = sources.map(fc(composedFactory)).map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain09 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 10; i++) tick.run();

		double captureVal = captured.toDouble(0);
		Assert.assertTrue("CombinedFactor(identity, identity) should produce non-zero (got "
				+ captureVal + ")", Math.abs(captureVal) > 0.0);
	}

	/**
	 * D10: CombinedFactor(identityTemporal, AudioPassFilter) where identity
	 * is a TemporalFactor lambda. Isolates the TemporalFactor-as-first +
	 * AudioPassFilter-as-second specific combination, without multiplication.
	 */
	@Test(timeout = 60000)
	public void chain10_combinedTemporalIdentityThenHp() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			TemporalFactor<PackedCollection> identity = in -> in;
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = hp;
			return identity.andThen(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain10 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double output = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("CombinedFactor(identityTemporal, hp) should produce non-zero "
				+ "(output=" + output + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D11: CombinedFactor isolated — no FilteredCell, no CellList. Just build
	 * the combined factor, call getResultant, compile and run. Bisects whether
	 * the issue is in the CombinedFactor.getResultant compilation itself.
	 */
	@Test(timeout = 60000)
	public void chain11_combinedFactorIsolatedCompile() {
		PackedCollection input = new PackedCollection(1);
		input.setMem(0, 0.7);
		PackedCollection out = new PackedCollection(1);

		TemporalFactor<PackedCollection> volume = in -> multiply(in, c(1.0));
		AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
		Factor<PackedCollection> combined = volume.andThen(hp);

		Producer<PackedCollection> chained = combined.getResultant(p(input));

		OperationList setupOps = new OperationList("chain11 setup");
		if (combined instanceof Setup) {
			setupOps.add(((Setup) combined).setup());
		}
		setupOps.get().run();

		OperationList body = new OperationList("chain11 body");
		body.add(a(p(out), chained));
		body.add(((Temporal) combined).tick());
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 100).get();
		compiled.run();

		double outVal = out.toDouble(0);
		double hpOut = readOutput(hp);
		Assert.assertTrue("isolated CombinedFactor.getResultant compile+run should produce "
				+ "non-zero (out=" + outVal + ", hpOut=" + hpOut + ")",
				Math.abs(outVal) > 0.0 || Math.abs(hpOut) > 0.0);
	}

	/**
	 * D12: Manually compose instead of CombinedFactor — wrap the filter in a
	 * lambda that does the same work as volume.andThen(hp). If this passes
	 * and chain06 fails, the bug is in CombinedFactor's lifecycle wiring
	 * (setup/tick forwarding) specifically.
	 */
	@Test(timeout = 60000)
	public void chain12_manualCompositionInsteadOfCombinedFactor() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> manualFactory = i -> {
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = hp;
			// Equivalent to volume(v=>v*1).andThen(hp) manually:
			return new InlineComposed(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(manualFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain12 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double output = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("manual composition (no CombinedFactor) should produce non-zero "
				+ "(output=" + output + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * A TemporalFactor that manually composes: applies multiply(in, 1.0) to input,
	 * then passes to hp.getResultant. Propagates setup/tick like CombinedFactor but
	 * in a single class (no andThen invocation).
	 */
	private static class InlineComposed implements CellularTemporalFactor<PackedCollection>, CellFeatures {
		private final AudioPassFilter hp;
		InlineComposed(AudioPassFilter hp) { this.hp = hp; }

		@Override
		public Producer<PackedCollection> getResultant(Producer<PackedCollection> value) {
			Producer<PackedCollection> scaled = multiply(value, c(1.0));
			return hp.getResultant(scaled);
		}

		@Override
		public Supplier<Runnable> setup() {
			return hp.setup();
		}

		@Override
		public Supplier<Runnable> tick() {
			return hp.tick();
		}
	}

	/**
	 * D13: CombinedFactor(hp, identity) — hp as FIRST factor, identity second.
	 * Reverses the order vs MixdownManager. Bisects whether the issue is
	 * specifically AudioPassFilter-as-second-factor.
	 */
	@Test(timeout = 60000)
	public void chain13_combinedHpThenIdentity() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = hp;
			Factor<PackedCollection> identity = in -> in;
			return hp.andThen(identity);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain13 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double output = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("CombinedFactor(hp, identity) should produce non-zero "
				+ "(output=" + output + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D14: Exact MixdownManager pattern — volumeOnly returns
	 * {@code v -> f.getResultant(v)} wrapping an underlying scale factor
	 * (mimicking {@code MixdownManager.factor(Factor)}). This is the closest
	 * narrow reproducer of how MixdownManager constructs the composed factor.
	 */
	@Test(timeout = 60000)
	public void chain14_exactMixdownComposedFactor() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			// Mimic MixdownManager.factor(Factor): wraps an ordinary Factor in a TemporalFactor lambda.
			Factor<PackedCollection> inner = v -> multiply(v, c(0.5));
			TemporalFactor<PackedCollection> volumeTemporal = v -> inner.getResultant(v);

			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = hp;
			return volumeTemporal.andThen(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain14 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double output = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("exact MixdownManager composed factor should produce non-zero "
				+ "(output=" + output + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D15: chain09 pattern (with TapCell terminal) but using AudioPassFilter
	 * as the second factor. If this passes, the chain10 failure was caused by
	 * the missing terminal consumer, not by AudioPassFilter+CombinedFactor.
	 */
	@Test(timeout = 60000)
	public void chain15_combinedIdentityThenHpWithTerminal() {
		PackedCollection sourceVal = new PackedCollection(1);
		sourceVal.setMem(0, 0.7);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		PackedCollection captured = new PackedCollection(1);
		AudioPassFilter[] filterCapture = new AudioPassFilter[1];

		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			TemporalFactor<PackedCollection> identity = in -> in;
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = hp;
			return identity.andThen(hp);
		};

		CellList chain = sources.map(fc(composedFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain15 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double filterOut = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("chain15 with terminal should compile and produce non-zero "
				+ "(filterOut=" + filterOut + ", capture=" + captureVal + ")",
				Math.abs(filterOut) > 0.0 || Math.abs(captureVal) > 0.0);
	}

	/**
	 * D16: Exact MixdownManager shape — multi-source, CombinedFactor(volume, hp),
	 * summed to a single SummationCell, terminal is a TapCell.
	 */
	@Test(timeout = 60000)
	public void chain16_exactMixdownShape() {
		PackedCollection s1 = new PackedCollection(1); s1.setMem(0, 0.3);
		PackedCollection s2 = new PackedCollection(1); s2.setMem(0, 0.5);

		ProbeCell src1 = new ProbeCell(p(s1));
		ProbeCell src2 = new ProbeCell(p(s2));
		CellList sources = new CellList();
		sources.addRoot(src1);
		sources.addRoot(src2);

		AudioPassFilter[] filters = new AudioPassFilter[2];

		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			// Mimic MixdownManager exactly:
			Factor<PackedCollection> inner = v -> multiply(v, c(0.5));
			TemporalFactor<PackedCollection> volumeTemporal = v -> inner.getResultant(v);
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100 + i * 50), scalar(0.1), true);
			filters[i] = hp;
			return volumeTemporal.andThen(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum().map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain16 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double f0 = readOutput(filters[0]);
		double f1 = readOutput(filters[1]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("exact MixdownManager shape should compile and produce non-zero "
				+ "(f0=" + f0 + ", f1=" + f1 + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D17: chain16 pattern wrapped in HardwareFeatures.loop — matches
	 * AudioScene.runnerRealTime compilation path.
	 */
	@Test(timeout = 60000)
	public void chain17_exactMixdownShapeInCompiledLoop() {
		PackedCollection s1 = new PackedCollection(1); s1.setMem(0, 0.3);
		PackedCollection s2 = new PackedCollection(1); s2.setMem(0, 0.5);

		ProbeCell src1 = new ProbeCell(p(s1));
		ProbeCell src2 = new ProbeCell(p(s2));
		CellList sources = new CellList();
		sources.addRoot(src1);
		sources.addRoot(src2);

		AudioPassFilter[] filters = new AudioPassFilter[2];

		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			Factor<PackedCollection> inner = v -> multiply(v, c(0.5));
			TemporalFactor<PackedCollection> volumeTemporal = v -> inner.getResultant(v);
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100 + i * 50), scalar(0.1), true);
			filters[i] = hp;
			return volumeTemporal.andThen(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum().map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain17 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		OperationList body = new OperationList("chain17 body");
		body.add(chain.tick());
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 100).get();
		compiled.run();

		double f0 = readOutput(filters[0]);
		double f1 = readOutput(filters[1]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("compiled loop over MixdownManager shape should produce non-zero "
				+ "(f0=" + f0 + ", f1=" + f1 + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D18: Reproduce production's pattern: hp with PRODUCER-valued cutoff
	 * ({@code scalar(20000).multiply(geneProducer)}) composed via andThen.
	 * Same shape as {@code chain17} but cutoff is dynamic.
	 */
	@Test(timeout = 60000)
	public void chain18_hpWithProducerCutoff() {
		PackedCollection s1 = new PackedCollection(1); s1.setMem(0, 0.3);
		PackedCollection s2 = new PackedCollection(1); s2.setMem(0, 0.5);

		ProbeCell src1 = new ProbeCell(p(s1));
		ProbeCell src2 = new ProbeCell(p(s2));
		CellList sources = new CellList();
		sources.addRoot(src1);
		sources.addRoot(src2);

		AudioPassFilter[] filters = new AudioPassFilter[2];
		// Producer-valued gene (constant 0.001 → cutoff 20 Hz, HP passes most audio)
		PackedCollection geneVal = new PackedCollection(1);
		geneVal.setMem(0, 0.001);

		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			Factor<PackedCollection> inner = v -> multiply(v, c(0.5));
			TemporalFactor<PackedCollection> volumeTemporal = v -> inner.getResultant(v);
			// Exact production shape: cutoff = scalar(20000) * geneProducer
			Producer<PackedCollection> cutoff = scalar(20000).multiply(cp(geneVal));
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, cutoff, scalar(0.1), true);
			filters[i] = hp;
			return volumeTemporal.andThen(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum().map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain18 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double f0 = readOutput(filters[0]);
		double f1 = readOutput(filters[1]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("producer-cutoff hp in chain should produce non-zero "
				+ "(f0=" + f0 + ", f1=" + f1 + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D19: chain18 in compiled HardwareFeatures.loop.
	 */
	@Test(timeout = 60000)
	public void chain19_hpWithProducerCutoffCompiledLoop() {
		PackedCollection s1 = new PackedCollection(1); s1.setMem(0, 0.3);
		PackedCollection s2 = new PackedCollection(1); s2.setMem(0, 0.5);

		ProbeCell src1 = new ProbeCell(p(s1));
		ProbeCell src2 = new ProbeCell(p(s2));
		CellList sources = new CellList();
		sources.addRoot(src1);
		sources.addRoot(src2);

		AudioPassFilter[] filters = new AudioPassFilter[2];
		PackedCollection geneVal = new PackedCollection(1);
		geneVal.setMem(0, 0.001);

		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			Factor<PackedCollection> inner = v -> multiply(v, c(0.5));
			TemporalFactor<PackedCollection> volumeTemporal = v -> inner.getResultant(v);
			Producer<PackedCollection> cutoff = scalar(20000).multiply(cp(geneVal));
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, cutoff, scalar(0.1), true);
			filters[i] = hp;
			return volumeTemporal.andThen(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum().map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain19 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		OperationList body = new OperationList("chain19 body");
		body.add(chain.tick());
		Runnable compiled = HardwareFeatures.getInstance().loop(body, 100).get();
		compiled.run();

		double captureVal = captured.toDouble(0);
		Assert.assertTrue("compiled loop producer-cutoff hp should produce non-zero "
				+ "(capture=" + captureVal + ")", Math.abs(captureVal) > 0.0);
	}

	/**
	 * D20: Multi-step chain: source pushes a VARYING signal (not constant).
	 * Tests whether the chain drops signal when source provides oscillating input.
	 */
	@Test(timeout = 60000)
	public void chain20_varyingSourceSignal() {
		// Source is a PackedCollection that we'll mutate between iterations.
		PackedCollection sourceVal = new PackedCollection(1);

		ProbeCell src = new ProbeCell(p(sourceVal));
		CellList sources = new CellList();
		sources.addRoot(src);

		AudioPassFilter[] filters = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> composedFactory = i -> {
			TemporalFactor<PackedCollection> volumeTemporal = v -> multiply(v, c(0.5));
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filters[i] = hp;
			return volumeTemporal.andThen(hp);
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(composedFactory)).sum().map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain20 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		double maxCapture = 0;
		for (int i = 0; i < 100; i++) {
			// Vary the source signal each iteration (simulated oscillation)
			sourceVal.setMem(0, Math.sin(i * 0.1) * 0.5);
			tick.run();
			double v = Math.abs(captured.toDouble(0));
			if (v > maxCapture) maxCapture = v;
		}

		Assert.assertTrue("varying-source chain should produce non-zero "
				+ "(maxCapture=" + maxCapture + ")", maxCapture > 0.0);
	}

	/**
	 * D21: Compare the chained-maps pattern (sources.map(fc(hp)).map(fc(v)))
	 * — the pre-refactor pattern — against the CombinedFactor pattern (chain18).
	 * Both should produce non-zero audio. If chained-maps works in real
	 * integration but CombinedFactor doesn't, the bug is in the factor-embedding
	 * cell graph structure specifically.
	 */
	@Test(timeout = 60000)
	public void chain21_chainedMapsHpThenVolume() {
		PackedCollection s1 = new PackedCollection(1); s1.setMem(0, 0.3);
		PackedCollection s2 = new PackedCollection(1); s2.setMem(0, 0.5);

		ProbeCell src1 = new ProbeCell(p(s1));
		ProbeCell src2 = new ProbeCell(p(s2));
		CellList sources = new CellList();
		sources.addRoot(src1);
		sources.addRoot(src2);

		AudioPassFilter[] filters = new AudioPassFilter[2];
		PackedCollection geneVal = new PackedCollection(1);
		geneVal.setMem(0, 0.001);

		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			Producer<PackedCollection> cutoff = scalar(20000).multiply(cp(geneVal));
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, cutoff, scalar(0.1), true);
			filters[i] = hp;
			return hp;
		};

		IntFunction<Factor<PackedCollection>> volumeFactory = i -> {
			Factor<PackedCollection> inner = v -> multiply(v, c(0.5));
			return (TemporalFactor<PackedCollection>) (v -> inner.getResultant(v));
		};

		PackedCollection captured = new PackedCollection(1);
		// Chained maps: sources → FC(hp) → FC(volume) → sum → TapCell
		CellList chain = sources.map(fc(hpFactory)).map(fc(volumeFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain21 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		Runnable tick = chain.tick().get();
		for (int i = 0; i < 100; i++) tick.run();

		double f0 = readOutput(filters[0]);
		double f1 = readOutput(filters[1]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("chained-maps sources.map(fc(hp)).map(fc(v)) should produce non-zero "
				+ "(f0=" + f0 + ", f1=" + f1 + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D22: Full production-like chain with WaveCell sources driven by external
	 * frame producer (matches runnerRealTime structure). Tests whether the
	 * external-frame WaveCell + FilteredCell(hp) + SummationCell + ReceptorCell
	 * chain produces non-zero output.
	 */
	@Test(timeout = 60000)
	public void chain22_externalFrameWaveCellWithHpFilter() {
		// Pre-populated wave audio source (512 samples of a simple ramp)
		int bufSize = 512;
		PackedCollection audioBuffer = new PackedCollection(bufSize);
		for (int i = 0; i < bufSize; i++) {
			audioBuffer.setMem(i, 0.5 * Math.sin(i * 2 * Math.PI / 64.0));
		}

		// External frame index (incremented in the outer loop)
		PackedCollection frameIndex = new PackedCollection(1);
		frameIndex.setMem(0, 0.0);

		// Build a WaveCell with external frame control using the same API
		// that EfxManager.createCells uses for real-time mode.
		CellList waveCells = w(
				PolymorphicAudioData.supply(PackedCollection.factory()),
				bufSize, cp(frameIndex), cp(audioBuffer));

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
			filterCapture[0] = hp;
			return hp;
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = waveCells.map(fc(hpFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain22 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		// Mimic runnerRealTime: wrap tick + frame increment in HardwareFeatures.loop
		OperationList loopBody = new OperationList("chain22 body");
		loopBody.add(chain.tick());
		loopBody.add(a(1, cp(frameIndex), c(1.0).add(cp(frameIndex))));
		Runnable compiled = HardwareFeatures.getInstance().loop(loopBody, bufSize).get();
		compiled.run();

		double filterOut = readOutput(filterCapture[0]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("external-frame wave cell + hp chain should produce non-zero "
				+ "(filterOut=" + filterOut + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D23: Low-pass filter variant of chain22 — confirms the CachedStateCell
	 * fix works for both high-pass and low-pass AudioPassFilter.
	 */
	@Test(timeout = 60000)
	public void chain23_externalFrameWaveCellWithLpFilter() {
		int bufSize = 512;
		PackedCollection audioBuffer = new PackedCollection(bufSize);
		for (int i = 0; i < bufSize; i++) {
			audioBuffer.setMem(i, 0.5 * Math.sin(i * 2 * Math.PI / 64.0));
		}

		PackedCollection frameIndex = new PackedCollection(1);
		frameIndex.setMem(0, 0.0);

		CellList waveCells = w(
				PolymorphicAudioData.supply(PackedCollection.factory()),
				bufSize, cp(frameIndex), cp(audioBuffer));

		AudioPassFilter[] filterCapture = new AudioPassFilter[1];
		IntFunction<Factor<PackedCollection>> lpFactory = i -> {
			AudioPassFilter lp = new AudioPassFilter(SAMPLE_RATE, c(5000), scalar(0.1), false);
			filterCapture[0] = lp;
			return lp;
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = waveCells.map(fc(lpFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain23 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		OperationList loopBody = new OperationList("chain23 body");
		loopBody.add(chain.tick());
		loopBody.add(a(1, cp(frameIndex), c(1.0).add(cp(frameIndex))));
		Runnable compiled = HardwareFeatures.getInstance().loop(loopBody, bufSize).get();
		compiled.run();

		double captureVal = captured.toDouble(0);
		Assert.assertTrue("external-frame wave cell + lp chain should produce non-zero "
				+ "(filterOut=" + readOutput(filterCapture[0]) + ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D24: Chained filters (hp then lp) with WaveCell source. Verifies the
	 * fix propagates through a multi-filter chain — each FilteredCell must
	 * read its upstream's outValue rather than reset cachedValue.
	 */
	@Test(timeout = 60000)
	public void chain24_waveCellHpThenLp() {
		int bufSize = 512;
		PackedCollection audioBuffer = new PackedCollection(bufSize);
		for (int i = 0; i < bufSize; i++) {
			audioBuffer.setMem(i, 0.5 * Math.sin(i * 2 * Math.PI / 64.0));
		}

		PackedCollection frameIndex = new PackedCollection(1);

		CellList waveCells = w(
				PolymorphicAudioData.supply(PackedCollection.factory()),
				bufSize, cp(frameIndex), cp(audioBuffer));

		AudioPassFilter[] hpCap = new AudioPassFilter[1];
		AudioPassFilter[] lpCap = new AudioPassFilter[1];

		PackedCollection captured = new PackedCollection(1);
		CellList chain = waveCells
				.map(fc(i -> {
					AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100), scalar(0.1), true);
					hpCap[0] = hp;
					return hp;
				}))
				.map(fc(i -> {
					AudioPassFilter lp = new AudioPassFilter(SAMPLE_RATE, c(5000), scalar(0.1), false);
					lpCap[0] = lp;
					return lp;
				}))
				.sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain24 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		OperationList loopBody = new OperationList("chain24 body");
		loopBody.add(chain.tick());
		loopBody.add(a(1, cp(frameIndex), c(1.0).add(cp(frameIndex))));
		Runnable compiled = HardwareFeatures.getInstance().loop(loopBody, bufSize).get();
		compiled.run();

		double captureVal = captured.toDouble(0);
		Assert.assertTrue("wave -> hp -> lp -> sum -> tap chain should produce non-zero "
				+ "(hpOut=" + readOutput(hpCap[0])
				+ ", lpOut=" + readOutput(lpCap[0])
				+ ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D25: Multiple parallel WaveCell sources, each with its own hp filter,
	 * then summed together. Verifies per-source filter state independence
	 * and proper accumulation at the sum.
	 */
	@Test(timeout = 60000)
	public void chain25_multiSourceParallelHpSum() {
		int bufSize = 256;
		int numSources = 3;

		PackedCollection[] buffers = new PackedCollection[numSources];
		for (int s = 0; s < numSources; s++) {
			buffers[s] = new PackedCollection(bufSize);
			double freq = 64.0 / (s + 1);
			for (int i = 0; i < bufSize; i++) {
				buffers[s].setMem(i, 0.3 * Math.sin(i * 2 * Math.PI / freq));
			}
		}

		PackedCollection frameIndex = new PackedCollection(1);

		ArrayList<Producer<PackedCollection>> allWavesList = new ArrayList<>(numSources);
		for (int s = 0; s < numSources; s++) {
			allWavesList.add(cp(buffers[s]));
		}
		CellList sources = w(
				PolymorphicAudioData.supply(PackedCollection.factory()),
				bufSize, cp(frameIndex), allWavesList.toArray(new Producer[0]));

		AudioPassFilter[] filters = new AudioPassFilter[numSources];

		IntFunction<Factor<PackedCollection>> hpFactory = i -> {
			AudioPassFilter hp = new AudioPassFilter(SAMPLE_RATE, c(100 + i * 50), scalar(0.1), true);
			filters[i] = hp;
			return hp;
		};

		PackedCollection captured = new PackedCollection(1);
		CellList chain = sources.map(fc(hpFactory)).sum()
				.map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain25 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		OperationList body = new OperationList("chain25 body");
		body.add(chain.tick());
		body.add(a(1, cp(frameIndex), c(1.0).add(cp(frameIndex))));
		Runnable compiled = HardwareFeatures.getInstance().loop(body, bufSize).get();
		compiled.run();

		double f0 = readOutput(filters[0]);
		double f1 = readOutput(filters[1]);
		double f2 = readOutput(filters[2]);
		double captureVal = captured.toDouble(0);
		Assert.assertTrue("multi-source parallel hp should produce non-zero "
				+ "(f0=" + f0 + ", f1=" + f1 + ", f2=" + f2
				+ ", capture=" + captureVal + ")",
				Math.abs(captureVal) > 0.0);
	}

	/**
	 * D26: Verify the SummationCell fast path — when a CachedStateCell cell
	 * feeds directly into a SummationCell receptor, the optimized path (no
	 * outValue copy) must still work correctly. Tests cell → sum → tap with
	 * NO filter in between. If the CachedStateCell fix accidentally broke
	 * the fast path, this would fail.
	 */
	@Test(timeout = 60000)
	public void chain26_summationCellFastPath() {
		int bufSize = 256;
		PackedCollection audioBuffer = new PackedCollection(bufSize);
		for (int i = 0; i < bufSize; i++) {
			audioBuffer.setMem(i, 0.1 + i * 0.001);
		}

		PackedCollection frameIndex = new PackedCollection(1);

		CellList waveCells = w(
				PolymorphicAudioData.supply(PackedCollection.factory()),
				bufSize, cp(frameIndex), cp(audioBuffer));

		PackedCollection captured = new PackedCollection(1);
		// sources → sum (fast path: source.receptor is SummationCell) → TapCell
		CellList chain = waveCells.sum().map(idx -> new TapCell(captured));

		OperationList setup = new OperationList("chain26 setup");
		chain.getAllSetup().forEach(s -> setup.add(s.setup()));
		setup.get().run();

		OperationList body = new OperationList("chain26 body");
		body.add(chain.tick());
		body.add(a(1, cp(frameIndex), c(1.0).add(cp(frameIndex))));
		Runnable compiled = HardwareFeatures.getInstance().loop(body, bufSize).get();
		compiled.run();

		double captureVal = captured.toDouble(0);
		Assert.assertTrue("SummationCell fast path should produce non-zero "
				+ "(capture=" + captureVal + ")", Math.abs(captureVal) > 0.0);
	}

	/**
	 * A minimal cell that pushes a constant producer when push() is called.
	 * Used as a controllable source for chain tests.
	 */
	private static class ProbeCell extends CellAdapter<PackedCollection> {
		private final Producer<PackedCollection> value;
		ProbeCell(Producer<PackedCollection> value) { this.value = value; }

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> protein) {
			return super.push(value);
		}
	}
}
