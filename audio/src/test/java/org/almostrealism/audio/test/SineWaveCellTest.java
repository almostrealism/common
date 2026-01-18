/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.audio.test;

import io.almostrealism.relation.Factor;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.computations.DefaultEnvelopeComputation;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellPair;
import org.almostrealism.graph.MultiCell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.ReceptorCell;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.IdentityFactor;
import org.almostrealism.util.TestFeatures;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

/**
 * Tests for {@link SineWaveCell} covering frequency generation,
 * amplitude control, envelope application, and cell integration.
 */
public class SineWaveCellTest implements CellFeatures, TestFeatures {
	public static final int DURATION_FRAMES = 10 * OutputLine.sampleRate;

	/**
	 * Creates a receptor that captures the output value.
	 */
	protected Receptor<PackedCollection> capturingReceptor(AtomicReference<Double> capture) {
		return protein -> () -> () -> capture.set(protein.get().evaluate().toDouble(0));
	}

	/**
	 * Creates a receptor that accumulates all output values.
	 */
	protected Receptor<PackedCollection> accumulatingReceptor(List<Double> values) {
		return protein -> () -> () -> values.add(protein.get().evaluate().toDouble(0));
	}

	/**
	 * Creates a receptor that logs output values to console.
	 */
	protected Receptor<PackedCollection> loggingReceptor() {
		return protein -> () -> () -> System.out.println(protein.get().evaluate().toDouble(0));
	}

	protected Cell<PackedCollection> loggingCell() {
		return new ReceptorCell<>(protein -> () -> () ->
				System.out.println(protein.get().evaluate().toDouble(0)));
	}

	/**
	 * Creates a basic SineWaveCell configured for G3 with envelope.
	 */
	protected SineWaveCell cell() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(new DefaultKeyboardTuning().getTone(WesternChromatic.G3).asHertz());
		cell.setNoteLength(600);
		cell.setAmplitude(0.1);
		cell.setEnvelope(DefaultEnvelopeComputation::new);
		return cell;
	}

	/**
	 * Tests that a sine wave cell produces non-zero output when pushed.
	 */
	@Test
	public void sineWaveProducesOutput() {
		SineWaveCell cell = cell();
		AtomicReference<Double> lastValue = new AtomicReference<>(0.0);
		cell.setReceptor(capturingReceptor(lastValue));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();

		// Push multiple times and verify we get output
		boolean foundNonZero = false;
		for (int i = 0; i < 100; i++) {
			push.run();
			tick.run();
			if (Math.abs(lastValue.get()) > 0.0001) {
				foundNonZero = true;
			}
		}

		Assert.assertTrue("Sine wave cell should produce non-zero output", foundNonZero);
	}

	/**
	 * Tests that amplitude parameter controls output level.
	 */
	@Test
	public void amplitudeControlsOutputLevel() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(0); // No envelope
		cell.setAmplitude(0.5);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();
		IntStream.range(0, 1000).forEach(i -> {
			push.run();
			tick.run();
		});

		// Find max absolute value
		double maxValue = values.stream()
				.mapToDouble(Math::abs)
				.max()
				.orElse(0.0);

		// Max should be close to amplitude (0.5) but not exceed it significantly
		Assert.assertTrue("Max output should not exceed amplitude by much",
				maxValue <= 0.6);
		Assert.assertTrue("Max output should reach near the amplitude",
				maxValue >= 0.3);
	}

	/**
	 * Tests that different frequencies produce different output patterns.
	 */
	@Test
	public void frequencyAffectsOutput() {
		// Low frequency cell
		SineWaveCell lowFreqCell = new SineWaveCell();
		lowFreqCell.setFreq(100.0);
		lowFreqCell.setNoteLength(0);
		lowFreqCell.setAmplitude(1.0);

		// High frequency cell
		SineWaveCell highFreqCell = new SineWaveCell();
		highFreqCell.setFreq(1000.0);
		highFreqCell.setNoteLength(0);
		highFreqCell.setAmplitude(1.0);

		List<Double> lowFreqValues = new ArrayList<>();
		List<Double> highFreqValues = new ArrayList<>();

		lowFreqCell.setReceptor(accumulatingReceptor(lowFreqValues));
		highFreqCell.setReceptor(accumulatingReceptor(highFreqValues));

		Runnable lowSetup = lowFreqCell.setup().get();
		Runnable highSetup = highFreqCell.setup().get();
		Runnable lowPush = lowFreqCell.push(c(0.0)).get();
		Runnable highPush = highFreqCell.push(c(0.0)).get();
		Runnable lowTick = lowFreqCell.tick().get();
		Runnable highTick = highFreqCell.tick().get();

		lowSetup.run();
		highSetup.run();

		// Generate samples
		IntStream.range(0, 500).forEach(i -> {
			lowPush.run();
			lowTick.run();
			highPush.run();
			highTick.run();
		});

		// Count zero crossings (higher frequency = more crossings)
		int lowCrossings = countZeroCrossings(lowFreqValues);
		int highCrossings = countZeroCrossings(highFreqValues);

		Assert.assertTrue("Higher frequency should have more zero crossings",
				highCrossings > lowCrossings);
	}

	private int countZeroCrossings(List<Double> values) {
		int crossings = 0;
		for (int i = 1; i < values.size(); i++) {
			if ((values.get(i - 1) >= 0 && values.get(i) < 0) ||
					(values.get(i - 1) < 0 && values.get(i) >= 0)) {
				crossings++;
			}
		}
		return crossings;
	}

	/**
	 * Tests that the cell works with a simple output receptor.
	 */
	@Test
	public void cellProducesOutputThroughReceptor() {
		SineWaveCell cell = cell();
		List<Double> outputValues = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(outputValues));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();

		// Run for a bit
		IntStream.range(0, 200).forEach(i -> {
			push.run();
			tick.run();
		});

		// Verify we got output
		Assert.assertFalse("Should have received values",
				outputValues.isEmpty());

		// Verify some non-zero values came through
		boolean hasNonZero = outputValues.stream()
				.anyMatch(v -> Math.abs(v) > 0.0001);
		Assert.assertTrue("Output should contain non-zero values", hasNonZero);
	}

	/**
	 * Tests cell pair integration.
	 */
	@Test
	public void withCellPairIntegration() {
		SineWaveCell cell = cell();
		List<Double> capturedValues = new ArrayList<>();

		// Create a capturing cell instead of logging
		Cell<PackedCollection> captureCell = new ReceptorCell<>(
				protein -> () -> () -> capturedValues.add(protein.get().evaluate().toDouble(0))
		);

		List<Cell<PackedCollection>> cells = new ArrayList<>();
		cells.add(captureCell);

		MultiCell<PackedCollection> m = new MultiCell<>(cells, identityGene());
		m.setName("CaptureMultiCell");
		new CellPair(cell, m, null, new IdentityFactor<>()).init();

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(null).get();
		Runnable tick = cell.tick().get();

		setup.run();
		IntStream.range(0, 100).forEach(i -> {
			push.run();
			tick.run();
		});

		// Verify the cell pair passed values through
		Assert.assertFalse("Cell pair should pass values through",
				capturedValues.isEmpty());
	}

	/**
	 * Tests that zero amplitude produces no output.
	 */
	@Test
	public void zeroAmplitudeProducesNoOutput() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(0);
		cell.setAmplitude(0.0);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();
		IntStream.range(0, 100).forEach(i -> {
			push.run();
			tick.run();
		});

		// All values should be zero or near-zero
		boolean allNearZero = values.stream()
				.allMatch(v -> Math.abs(v) < 0.0001);
		Assert.assertTrue("Zero amplitude should produce near-zero output", allNearZero);
	}

	/**
	 * Tests standard musical frequencies from keyboard tuning.
	 */
	@Test
	public void keyboardTuningFrequencies() {
		DefaultKeyboardTuning tuning = new DefaultKeyboardTuning();

		// Test A4 = 440 Hz
		double a4Freq = tuning.getTone(WesternChromatic.A4).asHertz();
		Assert.assertEquals("A4 should be 440 Hz", 440.0, a4Freq, 0.01);

		// Create cell with A4
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(a4Freq);
		cell.setNoteLength(0);
		cell.setAmplitude(0.5);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();
		IntStream.range(0, 1000).forEach(i -> {
			push.run();
			tick.run();
		});

		// Verify output was produced
		Assert.assertTrue("Should produce output at A4 frequency",
				values.stream().anyMatch(v -> Math.abs(v) > 0.1));
	}

	protected Gene<PackedCollection> identityGene() {
		return new Gene<>() {
			@Override
			public Factor<PackedCollection> valueAt(int index) {
				return new IdentityFactor<>();
			}

			@Override
			public int length() {
				return 1;
			}
		};
	}
}
