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

package org.almostrealism.audio.sources.test;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.computations.DefaultEnvelopeComputation;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.SawtoothWaveCell;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.audio.sources.SquareWaveCell;
import org.almostrealism.audio.sources.TriangleWaveCell;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Tests that verify envelope functionality for all WaveCell implementations.
 * Each test confirms that the envelope Factor receives note position as input
 * and can control amplitude based on where we are in the note's lifecycle.
 */
public class WaveCellEnvelopeTest extends TestSuiteBase implements CellFeatures {

	/**
	 * A Factor that uses a custom envelope computation based on note position.
	 * It applies a linear envelope that depends on the note position,
	 * proving that note position is being passed correctly.
	 */
	private static class PositionBasedEnvelope implements Factor<PackedCollection>, CellFeatures {
		@Override
		public Producer<PackedCollection> getResultant(Producer<PackedCollection> notePosition) {
			// Return a DefaultEnvelopeComputation that uses note position
			// If note position is not passed correctly, the envelope won't work properly
			return new DefaultEnvelopeComputation(notePosition);
		}
	}

	/**
	 * A Factor that applies a simple linear decay based on note position.
	 * The envelope value decreases linearly from 1.0 (at position 0) to 0.0 (at position 1).
	 */
	private static class LinearDecayEnvelope implements Factor<PackedCollection>, CellFeatures {
		@Override
		public Producer<PackedCollection> getResultant(Producer<PackedCollection> notePosition) {
			// Linear decay: envelope = 1.0 - notePosition
			// At position 0.0: envelope = 1.0
			// At position 0.5: envelope = 0.5
			// At position 1.0: envelope = 0.0
			return subtract(c(1.0), notePosition);
		}
	}

	/**
	 * Creates a receptor that accumulates output values.
	 */
	protected Receptor<PackedCollection> accumulatingReceptor(List<Double> values) {
		return protein -> () -> () -> values.add(protein.get().evaluate().toDouble(0));
	}

	/**
	 * Test that SineWaveCell passes note position to the envelope Factor.
	 * Verifies by checking that output goes to zero after note ends.
	 */
	@Test
	public void sineWaveCellEnvelopeReceivesNotePosition() {
		PositionBasedEnvelope envelope = new PositionBasedEnvelope();

		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(100); // 100ms note
		cell.setAmplitude(1.0);
		cell.setEnvelope(envelope);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();
		// Run for 200ms to go past the 100ms note end
		int frames = (int) (OutputLine.sampleRate * 0.2);
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		Assert.assertFalse("Should have produced output values", values.isEmpty());

		// If note position is being passed correctly, the envelope will
		// silence the output after position > 1.0 (after 100ms)
		int afterNoteEnd = (int) (OutputLine.sampleRate * 0.15); // 150ms in
		double afterEndMax = values.subList(afterNoteEnd, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		Assert.assertTrue("Output should be near zero after note ends (proves note position is used)",
				afterEndMax < 0.1);
	}

	/**
	 * Test that SawtoothWaveCell passes note position to the envelope Factor.
	 * Verifies by checking that output goes to zero after note ends.
	 */
	@Test
	public void sawtoothWaveCellEnvelopeReceivesNotePosition() {
		PositionBasedEnvelope envelope = new PositionBasedEnvelope();

		SawtoothWaveCell cell = new SawtoothWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(100); // 100ms note
		cell.setAmplitude(1.0);
		cell.setEnvelope(envelope);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();
		int frames = (int) (OutputLine.sampleRate * 0.2);
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		Assert.assertFalse("Should have produced output values", values.isEmpty());

		int afterNoteEnd = (int) (OutputLine.sampleRate * 0.15);
		double afterEndMax = values.subList(afterNoteEnd, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		Assert.assertTrue("Output should be near zero after note ends (proves note position is used)",
				afterEndMax < 0.1);
	}

	/**
	 * Test that SquareWaveCell passes note position to the envelope Factor.
	 * Verifies by checking that output goes to zero after note ends.
	 */
	@Test
	public void squareWaveCellEnvelopeReceivesNotePosition() {
		PositionBasedEnvelope envelope = new PositionBasedEnvelope();

		SquareWaveCell cell = new SquareWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(100); // 100ms note
		cell.setAmplitude(1.0);
		cell.setEnvelope(envelope);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();
		int frames = (int) (OutputLine.sampleRate * 0.2);
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		Assert.assertFalse("Should have produced output values", values.isEmpty());

		int afterNoteEnd = (int) (OutputLine.sampleRate * 0.15);
		double afterEndMax = values.subList(afterNoteEnd, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		Assert.assertTrue("Output should be near zero after note ends (proves note position is used)",
				afterEndMax < 0.1);
	}

	/**
	 * Test that TriangleWaveCell passes note position to the envelope Factor.
	 * Verifies by checking that output goes to zero after note ends.
	 */
	@Test
	public void triangleWaveCellEnvelopeReceivesNotePosition() {
		PositionBasedEnvelope envelope = new PositionBasedEnvelope();

		TriangleWaveCell cell = new TriangleWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(100); // 100ms note
		cell.setAmplitude(1.0);
		cell.setEnvelope(envelope);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();
		int frames = (int) (OutputLine.sampleRate * 0.2);
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		Assert.assertFalse("Should have produced output values", values.isEmpty());

		int afterNoteEnd = (int) (OutputLine.sampleRate * 0.15);
		double afterEndMax = values.subList(afterNoteEnd, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		Assert.assertTrue("Output should be near zero after note ends (proves note position is used)",
				afterEndMax < 0.1);
	}

	/**
	 * Test that SineWaveCell envelope controls amplitude over the note's lifecycle.
	 * Uses a linear decay envelope to verify amplitude decreases as note position advances.
	 */
	@Test
	public void sineWaveCellEnvelopeControlsAmplitude() {
		LinearDecayEnvelope envelope = new LinearDecayEnvelope();

		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(100); // 100ms note
		cell.setAmplitude(1.0);
		cell.setEnvelope(envelope);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();

		// Run through the entire note and beyond
		int frames = (int) (OutputLine.sampleRate * 0.15); // 150ms to go past note end
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		// Split into early (first 10%), middle (40-60%), and late (90-100%) segments
		int earlyEnd = frames / 10;
		int middleStart = frames * 4 / 10;
		int middleEnd = frames * 6 / 10;
		int lateStart = frames * 9 / 10;

		double earlyMax = values.subList(0, earlyEnd).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);
		double middleMax = values.subList(middleStart, middleEnd).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);
		double lateMax = values.subList(lateStart, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		// With linear decay, amplitude should decrease over time
		Assert.assertTrue("Early amplitude should be higher than late amplitude",
				earlyMax > lateMax);
	}

	/**
	 * Test that SawtoothWaveCell envelope controls amplitude over the note's lifecycle.
	 */
	@Test
	public void sawtoothWaveCellEnvelopeControlsAmplitude() {
		LinearDecayEnvelope envelope = new LinearDecayEnvelope();

		SawtoothWaveCell cell = new SawtoothWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(100);
		cell.setAmplitude(1.0);
		cell.setEnvelope(envelope);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();

		int frames = (int) (OutputLine.sampleRate * 0.15);
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		int earlyEnd = frames / 10;
		int lateStart = frames * 9 / 10;

		double earlyMax = values.subList(0, earlyEnd).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);
		double lateMax = values.subList(lateStart, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		Assert.assertTrue("Early amplitude should be higher than late amplitude",
				earlyMax > lateMax);
	}

	/**
	 * Test that SquareWaveCell envelope controls amplitude over the note's lifecycle.
	 */
	@Test
	public void squareWaveCellEnvelopeControlsAmplitude() {
		LinearDecayEnvelope envelope = new LinearDecayEnvelope();

		SquareWaveCell cell = new SquareWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(100);
		cell.setAmplitude(1.0);
		cell.setEnvelope(envelope);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();

		int frames = (int) (OutputLine.sampleRate * 0.15);
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		int earlyEnd = frames / 10;
		int lateStart = frames * 9 / 10;

		double earlyMax = values.subList(0, earlyEnd).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);
		double lateMax = values.subList(lateStart, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		Assert.assertTrue("Early amplitude should be higher than late amplitude",
				earlyMax > lateMax);
	}

	/**
	 * Test that TriangleWaveCell envelope controls amplitude over the note's lifecycle.
	 */
	@Test
	public void triangleWaveCellEnvelopeControlsAmplitude() {
		LinearDecayEnvelope envelope = new LinearDecayEnvelope();

		TriangleWaveCell cell = new TriangleWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(100);
		cell.setAmplitude(1.0);
		cell.setEnvelope(envelope);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();

		int frames = (int) (OutputLine.sampleRate * 0.15);
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		int earlyEnd = frames / 10;
		int lateStart = frames * 9 / 10;

		double earlyMax = values.subList(0, earlyEnd).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);
		double lateMax = values.subList(lateStart, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		Assert.assertTrue("Early amplitude should be higher than late amplitude",
				earlyMax > lateMax);
	}

	/**
	 * Test that without an envelope, SineWaveCell produces constant amplitude output.
	 */
	@Test
	public void sineWaveCellWithoutEnvelopeHasConstantAmplitude() {
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(0); // No note length means no natural decay
		cell.setAmplitude(0.5);
		// No envelope set

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();

		int frames = (int) (OutputLine.sampleRate * 0.1); // 100ms
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		// Find the max amplitude in early and late portions
		int earlyEnd = frames / 3;
		int lateStart = frames * 2 / 3;

		double earlyMax = values.subList(0, earlyEnd).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);
		double lateMax = values.subList(lateStart, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		// Without envelope, amplitude should be roughly constant
		Assert.assertEquals("Without envelope, amplitude should be constant",
				earlyMax, lateMax, 0.1);
	}

	/**
	 * Test that DefaultEnvelopeComputation properly uses note position.
	 * This test verifies the envelope at specific note positions.
	 */
	@Test
	public void defaultEnvelopeComputationUsesNotePosition() {
		// Create cells with DefaultEnvelopeComputation
		SineWaveCell cell = new SineWaveCell();
		cell.setFreq(440.0);
		cell.setNoteLength(1000); // 1 second note
		cell.setAmplitude(1.0);
		cell.setEnvelope(DefaultEnvelopeComputation::new);

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable setup = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		setup.run();

		// Run for 1.5 seconds to go past note end
		int frames = (int) (OutputLine.sampleRate * 1.5);
		IntStream.range(0, frames).forEach(i -> {
			push.run();
			tick.run();
		});

		// After note position > 1.0 (after 1 second), envelope should be 0
		// So output should be near zero in the last portion
		int afterNoteEnd = (int) (OutputLine.sampleRate * 1.1); // 1.1 seconds in
		double afterEndMax = values.subList(afterNoteEnd, values.size()).stream()
				.mapToDouble(Math::abs).max().orElse(0.0);

		Assert.assertTrue("After note ends (position > 1.0), output should be near zero",
				afterEndMax < 0.1);
	}
}
