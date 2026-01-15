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

package org.almostrealism.audio.sources;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;

import java.util.function.Supplier;

/**
 * A temporal cell that generates square wave audio with configurable frequency,
 * amplitude, phase, duty cycle, and envelope. Supports pulse-width modulation (PWM)
 * through dynamic duty cycle control. Includes PolyBLEP anti-aliasing to reduce
 * aliasing artifacts.
 * <p>
 * The duty cycle controls the ratio of high to low in each wave period:
 * <ul>
 *   <li>0.5 = standard 50% square wave</li>
 *   <li>0.1 = narrow pulse</li>
 *   <li>0.9 = wide pulse</li>
 * </ul>
 *
 * @see CollectionTemporalCellAdapter
 * @see SquareWaveCellData
 * @see SquareWavePush
 * @see SquareWaveTick
 */
public class SquareWaveCell extends CollectionTemporalCellAdapter implements SamplingFeatures {
	private Factor<PackedCollection> env;
	private final SquareWaveCellData data;

	private double noteLength;
	private double waveLength;
	private double phase;
	private double amplitude;
	private double dutyCycle;

	public SquareWaveCell() {
		this(new SquareWavePolymorphicData());
	}

	public SquareWaveCell(SquareWaveCellData data) {
		this.data = data;
		this.dutyCycle = 0.5;
		this.amplitude = 1.0;
	}

	public void setEnvelope(Factor<PackedCollection> e) { this.env = e; }

	public void strike() { data.setNotePosition(0); }

	public void setFreq(double hertz) {
		this.waveLength = hertz / (double) OutputLine.sampleRate;
	}

	public Supplier<Runnable> setFreq(Producer<PackedCollection> hertz) {
		return a(data.getWaveLength(), divide(hertz, c(OutputLine.sampleRate)));
	}

	public void setNoteLength(int msec) {
		this.noteLength = toFramesMilli(msec);
	}

	public Supplier<Runnable> setNoteLength(Producer<PackedCollection> noteLength) {
		return a(data.getNoteLength(), toFramesMilli(noteLength));
	}

	public void setPhase(double phase) { this.phase = phase; }

	public void setAmplitude(double amp) { amplitude = amp; }

	public Supplier<Runnable> setAmplitude(Producer<PackedCollection> amp) {
		return a(data.getAmplitude(), amp);
	}

	/**
	 * Sets the duty cycle (pulse width).
	 *
	 * @param dutyCycle Ratio of high to low (0.0 to 1.0, default 0.5)
	 */
	public void setDutyCycle(double dutyCycle) {
		this.dutyCycle = dutyCycle;
	}

	/**
	 * Sets the duty cycle dynamically for PWM effects.
	 *
	 * @param dutyCycle Producer providing duty cycle value
	 * @return Supplier that updates the duty cycle
	 */
	public Supplier<Runnable> setDutyCycle(Producer<PackedCollection> dutyCycle) {
		return a(data.getDutyCycle(), dutyCycle);
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList defaults = new OperationList("SquareWaveCell Default Value Assignment");
		defaults.add(a(data.getDepth(), c(CollectionTemporalCellAdapter.depth)));
		defaults.add(a(data.getNotePosition(), c(0)));
		defaults.add(a(data.getWavePosition(), c(0)));
		defaults.add(a(data.getNoteLength(), c(noteLength)));
		defaults.add(a(data.getWaveLength(), c(waveLength)));
		defaults.add(a(data.getPhase(), c(phase)));
		defaults.add(a(data.getAmplitude(), c(amplitude)));
		defaults.add(a(data.getDutyCycle(), c(dutyCycle)));

		Supplier<Runnable> customization = super.setup();

		OperationList setup = new OperationList("SquareWaveCell Setup");
		setup.add(defaults);
		setup.add(customization);
		return setup;
	}

	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		PackedCollection value = new PackedCollection(1);
		OperationList push = new OperationList("SquareWaveCell Push");
		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(cp(data.notePosition()));
		push.add(new SquareWavePush(data, envelope, value));
		push.add(super.push(p(value)));
		return push;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("SquareWaveCell Tick");
		Producer<PackedCollection> envelope = env == null ? scalar(1.0) :
				env.getResultant(cp(data.notePosition()));
		tick.add(new SquareWaveTick(data, envelope));
		tick.add(super.tick());
		return tick;
	}

	/**
	 * Internal data class that implements SquareWaveCellData using PolymorphicAudioData storage.
	 */
	private static class SquareWavePolymorphicData extends PolymorphicAudioData implements SquareWaveCellData {
		public SquareWavePolymorphicData() {
			super();
		}
	}
}
