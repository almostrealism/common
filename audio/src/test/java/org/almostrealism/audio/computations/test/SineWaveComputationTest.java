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

package org.almostrealism.audio.computations.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Tests for sine wave computation through {@link SineWaveCell}.
 * Verifies mathematical correctness of sine wave generation including
 * frequency, phase, and amplitude behavior.
 */
public class SineWaveComputationTest extends TestSuiteBase implements CellFeatures {

	private static final int SAMPLE_RATE = OutputLine.sampleRate;
	private static final double EPSILON = 0.0001;

	/**
	 * Creates a receptor that accumulates all output values.
	 */
	private Receptor<PackedCollection> accumulatingReceptor(List<Double> values) {
		return protein -> () -> () -> values.add(protein.get().evaluate().toDouble(0));
	}

	/**
	 * Helper to setup and run a SineWaveCell, returning collected values.
	 */
	private List<Double> runCell(SineWaveCell cell, int iterations) {
		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();
		IntStream.range(0, iterations).forEach(i -> {
			push.run();
			tick.run();
		});

		return values;
	}

	/**
	 * Tests that a basic sine wave produces the expected sinusoidal pattern.
	 */
	@Test
	public void basicSineWavePattern() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0); // Standard A4 frequency
		cell.setAmplitude(1.0);
		cell.setNoteLength(0); // No envelope

		List<Double> values = runCell(cell, 1000);

		// Verify the signal oscillates between -1 and 1
		double min = values.stream().mapToDouble(d -> d).min().orElse(0);
		double max = values.stream().mapToDouble(d -> d).max().orElse(0);

		Assert.assertTrue("Min should be near -1 or above", min >= -1.1);
		Assert.assertTrue("Max should be near 1 or below", max <= 1.1);

		// Verify there are both positive and negative values (oscillation)
		boolean hasPositive = values.stream().anyMatch(v -> v > 0.1);
		boolean hasNegative = values.stream().anyMatch(v -> v < -0.1);

		Assert.assertTrue("Should have positive values", hasPositive);
		Assert.assertTrue("Should have negative values", hasNegative);
	}

	/**
	 * Tests that higher frequency produces more oscillations.
	 */
	@Test
	public void higherFrequencyMoreOscillations() {
		// Low frequency cell
		SineWaveCell lowCell = new SineWaveCell();
		lowCell.setFreq(100.0);
		lowCell.setAmplitude(1.0);
		lowCell.setNoteLength(0);

		// High frequency cell
		SineWaveCell highCell = new SineWaveCell();
		highCell.setFreq(1000.0);
		highCell.setAmplitude(1.0);
		highCell.setNoteLength(0);

		List<Double> lowValues = runCell(lowCell, 1000);
		List<Double> highValues = runCell(highCell, 1000);

		// Count zero crossings (higher frequency = more crossings)
		int lowCrossings = countZeroCrossings(lowValues);
		int highCrossings = countZeroCrossings(highValues);

		// High frequency (1000 Hz) should have ~10x more crossings than low (100 Hz)
		Assert.assertTrue("High frequency should have significantly more zero crossings: " +
						"low=" + lowCrossings + ", high=" + highCrossings,
				highCrossings > lowCrossings * 5);
	}

	/**
	 * Tests that amplitude scales the output correctly.
	 */
	@Test
	public void amplitudeScalesOutput() {
		// Full amplitude
		SineWaveCell fullCell = new SineWaveCell();
		fullCell.setFreq(440.0);
		fullCell.setAmplitude(1.0);
		fullCell.setNoteLength(0);

		// Half amplitude
		SineWaveCell halfCell = new SineWaveCell();
		halfCell.setFreq(440.0);
		halfCell.setAmplitude(0.5);
		halfCell.setNoteLength(0);

		// Quarter amplitude
		SineWaveCell quarterCell = new SineWaveCell();
		quarterCell.setFreq(440.0);
		quarterCell.setAmplitude(0.25);
		quarterCell.setNoteLength(0);

		List<Double> fullValues = runCell(fullCell, 500);
		List<Double> halfValues = runCell(halfCell, 500);
		List<Double> quarterValues = runCell(quarterCell, 500);

		double fullMax = fullValues.stream().mapToDouble(Math::abs).max().orElse(0);
		double halfMax = halfValues.stream().mapToDouble(Math::abs).max().orElse(0);
		double quarterMax = quarterValues.stream().mapToDouble(Math::abs).max().orElse(0);

		// Half amplitude should produce ~half the max value
		Assert.assertEquals("Half amplitude should produce ~half output",
				fullMax * 0.5, halfMax, fullMax * 0.15);

		// Quarter amplitude should produce ~quarter the max value
		Assert.assertEquals("Quarter amplitude should produce ~quarter output",
				fullMax * 0.25, quarterMax, fullMax * 0.1);
	}

	/**
	 * Tests that zero amplitude produces zero output.
	 */
	@Test
	public void zeroAmplitudeProducesZeroOutput() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setAmplitude(0.0);
		cell.setNoteLength(0);

		List<Double> values = runCell(cell, 100);

		// All values should be zero
		boolean allZero = values.stream().allMatch(v -> Math.abs(v) < EPSILON);
		Assert.assertTrue("Zero amplitude should produce zero output", allZero);
	}

	/**
	 * Tests output stability over many iterations.
	 */
	@Test
	public void outputStabilityOverTime() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setAmplitude(0.5);
		cell.setNoteLength(0);

		List<Double> values = runCell(cell, 10000);

		// Verify no values exceed amplitude bounds
		boolean withinBounds = values.stream()
				.allMatch(v -> Math.abs(v) <= 0.6); // Slight tolerance

		Assert.assertTrue("All values should stay within amplitude bounds", withinBounds);

		// Verify we still see oscillation at the end (not degrading to zero)
		int startIndex = Math.max(0, values.size() - 100);
		List<Double> lastValues = values.subList(startIndex, values.size());
		double lastMax = lastValues.stream().mapToDouble(Math::abs).max().orElse(0);

		Assert.assertTrue("Signal should still have amplitude at end", lastMax > 0.3);
	}

	/**
	 * Tests that different frequencies produce proportionally different periods.
	 */
	@Test
	public void frequencyProducesPredictablePeriod() {
		// 440 Hz should complete one cycle in ~100 samples at 44100 Hz sample rate
		// Period = sample_rate / frequency = 44100 / 440 = ~100.2 samples

		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setAmplitude(1.0);
		cell.setNoteLength(0);

		List<Double> values = runCell(cell, 500);

		// Count zero crossings
		int crossings = countZeroCrossings(values);

		// Each cycle has 2 zero crossings
		// 500 samples at 44100 Hz with 440 Hz signal
		// Expected cycles = 500 / (44100/440) = ~4.99 cycles
		// Expected crossings = ~10 (2 per cycle)
		int expectedCrossings = (int) (2 * 500.0 / (SAMPLE_RATE / 440.0));

		Assert.assertEquals("Zero crossings should match expected frequency",
				expectedCrossings, crossings, 3); // Wider tolerance for edge effects
	}

	/**
	 * Tests 440 Hz (A4) produces expected output.
	 */
	@Test
	public void a440Frequency() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setAmplitude(1.0);
		cell.setNoteLength(0);

		List<Double> values = runCell(cell, 1000);

		// Should produce output
		double max = values.stream().mapToDouble(Math::abs).max().orElse(0);
		Assert.assertTrue("440 Hz signal should produce output", max > 0.5);

		// Should have reasonable number of zero crossings for 440 Hz
		int crossings = countZeroCrossings(values);
		// 1000 samples at 44100 Hz with 440 Hz = ~10 cycles = ~20 crossings
		Assert.assertTrue("Should have reasonable crossings for 440 Hz", crossings > 10 && crossings < 30);
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
}
