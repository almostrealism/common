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

package org.almostrealism.audio.sources.test;

import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sources.OscillatorCell;
import org.almostrealism.audio.sources.SawtoothWaveCell;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.audio.sources.SineWaveCellData;
import org.almostrealism.audio.sources.SquareWaveCell;
import org.almostrealism.audio.sources.SquareWaveCellData;
import org.almostrealism.audio.sources.TriangleWaveCell;
import org.almostrealism.audio.synth.AudioSynthesizer;
import org.almostrealism.audio.synth.OvertoneSeries;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.time.Frequency;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;

/**
 * Characterization tests for the scaffold {@link OscillatorCell} shares between
 * {@link SineWaveCell}, {@link SawtoothWaveCell}, {@link SquareWaveCell} and
 * {@link TriangleWaveCell}: initial-value setup, the runtime setters, position
 * advancement in {@code tick()}, and the waveform each cell writes on {@code push()}.
 *
 * <p>Every waveform is checked against a plain-Java oracle evaluated at the top of
 * the call stack, so the tests describe the audible contract of each cell rather
 * than the structure of the code that produces it. They were written against the
 * four independent copies before the scaffold was shared and pass unchanged against
 * the shared implementation, with the single exception noted on
 * {@link #defaultAmplitudeIsFullScaleForEveryCell()}.</p>
 */
public class OscillatorCellTest extends TestSuiteBase implements SamplingFeatures {

	/** Frequency giving exactly 100 frames per cycle at 44.1 kHz. */
	private static final double FREQ = OutputLine.sampleRate / 100.0;

	/** Number of frames rendered when comparing waveforms against the oracle. */
	private static final int FRAMES = 300;

	/** Tolerance for single-precision device arithmetic on smooth regions. */
	private static final double TOLERANCE = 1e-3;

	/** Tolerance for values read back from single-precision storage. */
	private static final double STORAGE_TOLERANCE = 1e-6;

	/** Frames whose cycle position lies this close to a discontinuity are not compared. */
	private static final double EDGE_MARGIN = 2e-3;

	/** Square wave data storage that exposes the duty cycle slot for inspection. */
	private static class SquareData extends PolymorphicAudioData implements SquareWaveCellData {
	}

	/** Creates a receptor that accumulates output values. */
	private Receptor<PackedCollection> accumulatingReceptor(List<Double> values) {
		return protein -> () -> () -> values.add(protein.get().evaluate().toDouble(0));
	}

	/** PolyBLEP correction as defined in {@link SamplingFeatures#polyBlep}. */
	private double blep(double t, double dt) {
		if (t < dt) {
			double x = t / dt - 1.0;
			return -(x * x);
		} else if (t > 1.0 - dt) {
			double x = (t - 1.0) / dt + 1.0;
			return x * x;
		}

		return 0.0;
	}

	/** Cycle position of a raw phase value. */
	private double frac(double t) { return t - Math.floor(t); }

	/**
	 * Renders {@code FRAMES} samples from a cell configured with the shared parameters.
	 *
	 * @param cell  the cell under test
	 * @param setup configures the cell before {@code setup()} runs
	 * @return the values delivered to the receptor, one per frame
	 */
	private List<Double> render(OscillatorCell cell, Runnable setup) {
		setup.run();

		List<Double> values = new ArrayList<>();
		cell.setReceptor(accumulatingReceptor(values));

		Runnable init = cell.setup().get();
		Runnable push = cell.push(c(0.0)).get();
		Runnable tick = cell.tick().get();

		init.run();
		for (int i = 0; i < FRAMES; i++) {
			push.run();
			tick.run();
		}

		return values;
	}

	/**
	 * Compares rendered samples against an oracle expressed in terms of the raw phase
	 * {@code n * waveLength + phase}, skipping frames adjacent to a discontinuity.
	 *
	 * @param name          label used in assertion messages
	 * @param values        the rendered samples
	 * @param phase         the phase offset the cell was configured with
	 * @param amplitude     the amplitude the cell was configured with
	 * @param oracle        expected waveform as a function of the raw phase
	 * @param discontinuous distance of a cycle position from the nearest discontinuity
	 */
	private void assertWaveform(String name, List<Double> values, double phase, double amplitude,
								DoubleUnaryOperator oracle, DoubleUnaryOperator discontinuous) {
		double waveLength = FREQ / OutputLine.sampleRate;
		Assert.assertEquals(name + " frame count", FRAMES, values.size());

		int compared = 0;
		for (int n = 0; n < FRAMES; n++) {
			double t = n * waveLength + phase;
			if (discontinuous.applyAsDouble(frac(t)) < EDGE_MARGIN) continue;

			double expected = amplitude * oracle.applyAsDouble(t);
			Assert.assertEquals(name + " frame " + n, expected, values.get(n), TOLERANCE);
			compared++;
		}

		Assert.assertTrue(name + " compared " + compared + " frames", compared > FRAMES * 0.9);
	}

	/** Distance of a cycle position from the nearest wrap point (0 or 1). */
	private double wrapDistance(double f) { return Math.min(f, 1.0 - f); }

	/** Configures the shared parameters on a cell before {@code setup()} runs. */
	private void configure(OscillatorCell cell, double phase, double amplitude) {
		cell.setFreq(FREQ);
		cell.setNoteLength(1000);
		cell.setPhase(phase);
		cell.setAmplitude(amplitude);
	}

	/** Each cell paired with the data storage it was constructed around. */
	private List<OscillatorCell> cellsWithData(List<SineWaveCellData> data) {
		SineWaveCellData sine = new PolymorphicAudioData();
		SineWaveCellData saw = new PolymorphicAudioData();
		SquareWaveCellData square = new SquareData();
		SineWaveCellData triangle = new PolymorphicAudioData();
		data.add(sine); data.add(saw); data.add(square); data.add(triangle);

		List<OscillatorCell> cells = new ArrayList<>();
		cells.add(new SineWaveCell(sine));
		cells.add(new SawtoothWaveCell(saw));
		cells.add(new SquareWaveCell(square));
		cells.add(new TriangleWaveCell(triangle));
		return cells;
	}

	/**
	 * {@code setup()} copies every initial value into the cell's storage, and the
	 * positions start at zero, for all four cells.
	 */
	@Test(timeout = 120000)
	public void setupWritesInitialValuesForEveryCell() {
		List<SineWaveCellData> data = new ArrayList<>();
		List<OscillatorCell> cells = cellsWithData(data);

		for (int i = 0; i < cells.size(); i++) {
			OscillatorCell cell = cells.get(i);
			configure(cell, 0.25, 0.7);
			cell.setup().get().run();

			String name = cell.getClass().getSimpleName();
			SineWaveCellData d = data.get(i);
			Assert.assertEquals(name + " depth", CollectionTemporalCellAdapter.depth, d.depth().toDouble(0), STORAGE_TOLERANCE);
			Assert.assertEquals(name + " notePosition", 0.0, d.notePosition().toDouble(0), STORAGE_TOLERANCE);
			Assert.assertEquals(name + " wavePosition", 0.0, d.wavePosition().toDouble(0), STORAGE_TOLERANCE);
			Assert.assertEquals(name + " noteLength", toFramesMilli(1000), d.noteLength().toDouble(0), STORAGE_TOLERANCE);
			Assert.assertEquals(name + " waveLength", FREQ / OutputLine.sampleRate, d.waveLength().toDouble(0), STORAGE_TOLERANCE);
			Assert.assertEquals(name + " phase", 0.25, d.phase().toDouble(0), STORAGE_TOLERANCE);
			Assert.assertEquals(name + " amplitude", 0.7, d.amplitude().toDouble(0), STORAGE_TOLERANCE);
		}

		Assert.assertEquals("duty cycle default", 0.5, ((SquareWaveCellData) data.get(2)).dutyCycle().toDouble(0), STORAGE_TOLERANCE);
	}

	/**
	 * The Producer-based setters write frequency, note length and amplitude into the
	 * cell's storage when executed, for all four cells.
	 */
	@Test(timeout = 120000)
	public void runtimeSettersUpdateStorageForEveryCell() {
		List<SineWaveCellData> data = new ArrayList<>();
		List<OscillatorCell> cells = cellsWithData(data);

		for (int i = 0; i < cells.size(); i++) {
			OscillatorCell cell = cells.get(i);
			configure(cell, 0.0, 1.0);
			cell.setup().get().run();

			cell.setFreq(c(882.0)).get().run();
			cell.setNoteLength(c(200.0)).get().run();
			cell.setAmplitude(c(0.25)).get().run();

			String name = cell.getClass().getSimpleName();
			SineWaveCellData d = data.get(i);
			Assert.assertEquals(name + " waveLength", 882.0 / OutputLine.sampleRate, d.waveLength().toDouble(0), STORAGE_TOLERANCE);
			Assert.assertEquals(name + " noteLength", OutputLine.sampleRate / 1000d * 200, d.noteLength().toDouble(0), 1e-3);
			Assert.assertEquals(name + " amplitude", 0.25, d.amplitude().toDouble(0), STORAGE_TOLERANCE);
		}

		SquareWaveCell square = (SquareWaveCell) cells.get(2);
		square.setDutyCycle(c(0.2)).get().run();
		Assert.assertEquals("duty cycle", 0.2, ((SquareWaveCellData) data.get(2)).dutyCycle().toDouble(0), STORAGE_TOLERANCE);
	}

	/**
	 * {@code tick()} advances the wave position by one wave length and the note
	 * position by the reciprocal of the note length, and {@code strike()} resets the
	 * note position, for all four cells.
	 */
	@Test(timeout = 120000)
	public void tickAdvancesPositionsAndStrikeResetsNoteForEveryCell() {
		List<SineWaveCellData> data = new ArrayList<>();
		List<OscillatorCell> cells = cellsWithData(data);
		int ticks = 50;

		for (int i = 0; i < cells.size(); i++) {
			OscillatorCell cell = cells.get(i);
			configure(cell, 0.0, 1.0);
			cell.setup().get().run();

			Runnable tick = cell.tick().get();
			for (int n = 0; n < ticks; n++) tick.run();

			String name = cell.getClass().getSimpleName();
			SineWaveCellData d = data.get(i);
			Assert.assertEquals(name + " wavePosition", ticks * FREQ / OutputLine.sampleRate,
					d.wavePosition().toDouble(0), 1e-4);
			Assert.assertEquals(name + " notePosition", ticks / (double) toFramesMilli(1000),
					d.notePosition().toDouble(0), 1e-4);

			cell.strike();
			Assert.assertEquals(name + " notePosition after strike", 0.0, d.notePosition().toDouble(0), STORAGE_TOLERANCE);
		}
	}

	/** The sine cell produces {@code amplitude * sin(2 * pi * (wavePosition + phase))}. */
	@Test(timeout = 120000)
	public void sineWaveformMatchesOracle() {
		SineWaveCell cell = new SineWaveCell();
		List<Double> values = render(cell, () -> configure(cell, 0.1, 0.6));
		assertWaveform("sine", values, 0.1, 0.6,
				t -> Math.sin(2 * Math.PI * t), f -> 1.0);
	}

	/** The triangle cell ramps from -1 to +1 over the first half cycle and back over the second. */
	@Test(timeout = 120000)
	public void triangleWaveformMatchesOracle() {
		TriangleWaveCell cell = new TriangleWaveCell();
		List<Double> values = render(cell, () -> configure(cell, 0.3, 0.8));
		assertWaveform("triangle", values, 0.3, 0.8,
				t -> frac(t) < 0.5 ? frac(t) * 4 - 1 : 3 - frac(t) * 4, f -> 1.0);
	}

	/** The ascending sawtooth cell ramps from -1 to +1 with the PolyBLEP step subtracted. */
	@Test(timeout = 120000)
	public void ascendingSawtoothWaveformMatchesOracle() {
		double dt = FREQ / OutputLine.sampleRate;
		SawtoothWaveCell cell = new SawtoothWaveCell();
		List<Double> values = render(cell, () -> configure(cell, 0.0, 0.9));
		Assert.assertTrue(cell.isAscending());
		assertWaveform("sawtooth ascending", values, 0.0, 0.9,
				t -> (frac(t) * 2 - 1) - blep(frac(t), dt), this::wrapDistance);
	}

	/** The descending sawtooth cell ramps from +1 to -1 with the PolyBLEP step added. */
	@Test(timeout = 120000)
	public void descendingSawtoothWaveformMatchesOracle() {
		double dt = FREQ / OutputLine.sampleRate;
		SawtoothWaveCell cell = new SawtoothWaveCell();
		cell.setAscending(false);
		List<Double> values = render(cell, () -> configure(cell, 0.0, 0.9));
		Assert.assertFalse(cell.isAscending());
		assertWaveform("sawtooth descending", values, 0.0, 0.9,
				t -> (1 - frac(t) * 2) + blep(frac(t), dt), this::wrapDistance);
	}

	/**
	 * The square cell is +1 below the duty cycle and -1 above it, with PolyBLEP
	 * corrections at the rising edge and at the falling edge.
	 */
	@Test(timeout = 120000)
	public void squareWaveformMatchesOracle() {
		double dt = FREQ / OutputLine.sampleRate;
		double duty = 0.3;
		SquareWaveCell cell = new SquareWaveCell();
		cell.setDutyCycle(duty);
		List<Double> values = render(cell, () -> configure(cell, 0.0, 0.5));
		assertWaveform("square", values, 0.0, 0.5,
				t -> {
					double f = frac(t);
					double falling = f - duty + (f < duty ? 1.0 : 0.0);
					return (f < duty ? 1.0 : -1.0) + blep(f, dt) - blep(falling, dt);
				},
				f -> Math.min(wrapDistance(f), Math.abs(f - duty)));
	}

	/**
	 * A cell that never had its amplitude set produces full-scale output. Before the
	 * scaffold was shared, the sine cell alone defaulted to an amplitude of zero and was
	 * silent until {@code setAmplitude} was called, while the other three defaulted to
	 * full scale; the shared default resolves that divergence in favour of full scale.
	 */
	@Test(timeout = 120000)
	public void defaultAmplitudeIsFullScaleForEveryCell() {
		Assert.assertEquals("sine", 1.0, peak(new SineWaveCell()), 0.05);
		Assert.assertEquals("sawtooth", 1.0, peak(new SawtoothWaveCell()), 0.05);
		Assert.assertEquals("square", 1.0, peak(new SquareWaveCell()), 0.05);
		Assert.assertEquals("triangle", 1.0, peak(new TriangleWaveCell()), 0.05);
	}

	/** Peak absolute output of a cell whose amplitude was never set. */
	private double peak(OscillatorCell cell) {
		List<Double> values = render(cell, () -> {
			cell.setFreq(FREQ);
			cell.setNoteLength(1000);
		});
		return values.stream().mapToDouble(Math::abs).max().orElse(0.0);
	}

	/**
	 * {@link AudioSynthesizer} reaches every oscillator type through its frequency and
	 * velocity controls: each type produces audible output at the configured level.
	 */
	@Test(timeout = 180000)
	public void synthesizerDrivesEveryOscillatorType() {
		for (AudioSynthesizer.OscillatorType type : AudioSynthesizer.OscillatorType.values()) {
			AudioSynthesizer synth = new AudioSynthesizer(null, new OvertoneSeries(0, 0, 0), type);
			synth.setFrequency(new Frequency(FREQ));
			synth.setVelocity(0.5);

			List<Double> values = new ArrayList<>();
			synth.getOutput().setReceptor(accumulatingReceptor(values));

			Runnable setup = synth.setup().get();
			Runnable tick = synth.tick().get();
			setup.run();
			for (int i = 0; i < FRAMES; i++) tick.run();

			double peak = values.stream().mapToDouble(Math::abs).max().orElse(0.0);
			Assert.assertEquals(type + " peak", 0.5, peak, 0.05);
		}
	}
}
