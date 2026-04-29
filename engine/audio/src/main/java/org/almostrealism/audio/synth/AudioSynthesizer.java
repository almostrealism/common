/*
 * Copyright 2025 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.almostrealism.audio.synth;

import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.audio.SamplingFeatures;
import org.almostrealism.audio.filter.ADSREnvelope;
import org.almostrealism.audio.filter.BiquadFilterCell;
import org.almostrealism.audio.sources.BufferDetails;
import org.almostrealism.audio.sources.SawtoothWaveCell;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.audio.sources.SquareWaveCell;
import org.almostrealism.audio.sources.StatelessSource;
import org.almostrealism.audio.sources.TriangleWaveCell;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyNumbering;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.RelativeFrequencySet;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.Temporal;

import io.almostrealism.lifecycle.Setup;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

/**
 * An audio synthesizer that generates sound by combining multiple wave generators
 * at frequencies determined by a {@link RelativeFrequencySet}. Supports multiple
 * oscillator types, dynamic synthesis models for controlling amplitude envelopes,
 * and frequency modulation over time.
 * <p>
 * The synthesizer supports both additive synthesis (multiple oscillators at overtone
 * frequencies) and basic subtractive concepts (different waveform types).
 *
 * @see AudioSynthesisModel
 * @see RelativeFrequencySet
 * @see OvertoneSeries
 */
public class AudioSynthesizer implements Temporal, Setup, StatelessSource, SamplingFeatures {

	/**
	 * Available oscillator waveform types.
	 */
	public enum OscillatorType {
		/** Pure sine wave - smooth, fundamental tone */
		SINE,
		/** Square wave - hollow, clarinet-like with odd harmonics */
		SQUARE,
		/** Sawtooth wave - bright, buzzy with all harmonics */
		SAWTOOTH,
		/** Triangle wave - soft, flute-like with odd harmonics */
		TRIANGLE
	}

	/** The set of relative frequencies used to derive oscillator frequencies from the fundamental. */
	private final RelativeFrequencySet tones;

	/** The waveform type used by all oscillators. */
	private OscillatorType oscillatorType;

	/** The oscillator cells, one per entry in the frequency set. */
	private final List<CollectionTemporalCellAdapter> cells;

	/** Summing cell that accumulates output from all oscillators. */
	private final SummationCell output;

	/** Optional biquad filter applied to the summed oscillator output. */
	private BiquadFilterCell filter;

	/** Optional ADSR envelope for modulating filter cutoff frequency over time. */
	private ADSREnvelope filterEnvelope;

	/** Depth in Hz of the filter envelope modulation applied to the base cutoff. */
	private double filterEnvelopeAmount;

	/** Base filter cutoff frequency in Hz before envelope modulation is applied. */
	private double filterBaseCutoff;

	/** The keyboard tuning system used for MIDI-note-to-frequency conversion. */
	private KeyboardTuning tuning;

	/** The synthesis model controlling per-frequency amplitude levels; null for uniform levels. */
	private AudioSynthesisModel model;

	/** Optional ADSR envelope applied to the amplitude of all oscillators. */
	private ADSREnvelope ampEnvelope;

	/** Current velocity (amplitude scaling factor) in the range 0.0–1.0. */
	private double velocity;

	/** The unmodified fundamental frequency set by {@link #setFrequency(Frequency)}, used as the reference for pitch bend calculations. */
	private Frequency baseFrequency;

	/** Creates an AudioSynthesizer with 2 sub-octaves, 5 overtones, and no synthesis model. */
	public AudioSynthesizer() {
		this((AudioSynthesisModel) null);
	}

	/**
	 * Creates an AudioSynthesizer with the specified sub- and super-octave counts and no synthesis model.
	 *
	 * @param subCount   number of sub-octave partials
	 * @param superCount number of overtone partials
	 */
	public AudioSynthesizer(int subCount, int superCount) {
		this(null, subCount, superCount, 0);
	}

	/**
	 * Creates an AudioSynthesizer with the given synthesis model and 2 sub-octaves and 5 overtones.
	 *
	 * @param model synthesis model controlling amplitude levels; null for uniform levels
	 */
	public AudioSynthesizer(AudioSynthesisModel model) {
		this(model, 2, 5, 0);
	}

	/**
	 * Creates an AudioSynthesizer with the given model and an overtone series of specified size.
	 *
	 * @param model          synthesis model controlling amplitude levels; null for uniform levels
	 * @param subCount       number of sub-octave partials
	 * @param superCount     number of overtone partials
	 * @param inharmonicCount number of inharmonic partials
	 */
	public AudioSynthesizer(AudioSynthesisModel model,
							int subCount, int superCount, int inharmonicCount) {
		this(model, new OvertoneSeries(subCount, superCount, inharmonicCount));
	}

	/**
	 * Creates an AudioSynthesizer with the given model and custom frequency set using SINE oscillators.
	 *
	 * @param model  synthesis model controlling amplitude levels; null for uniform levels
	 * @param voices the relative frequency set defining oscillator pitches
	 */
	public AudioSynthesizer(AudioSynthesisModel model, RelativeFrequencySet voices) {
		this(model, voices, OscillatorType.SINE);
	}

	/**
	 * Primary constructor. Creates an AudioSynthesizer with full control over model, voices, and oscillator type.
	 *
	 * @param model          synthesis model controlling amplitude levels; null for uniform levels
	 * @param voices         the relative frequency set defining oscillator pitches
	 * @param oscillatorType waveform type for all oscillators
	 */
	public AudioSynthesizer(AudioSynthesisModel model, RelativeFrequencySet voices, OscillatorType oscillatorType) {
		setModel(model);
		this.tones = voices;
		this.oscillatorType = oscillatorType;
		this.tuning = new DefaultKeyboardTuning();
		this.output = new SummationCell();
		this.cells = new ArrayList<>();
		this.velocity = 1.0;
		this.filterBaseCutoff = 5000.0;
		this.filterEnvelopeAmount = 0.0;

		createOscillators(voices.count());
	}

	/**
	 * Creates the oscillator cells based on the current oscillator type.
	 */
	private void createOscillators(int count) {
		cells.clear();
		for (int i = 0; i < count; i++) {
			CollectionTemporalCellAdapter cell = createOscillator();
			cell.setReceptor(output);
			cells.add(cell);
		}
	}

	/**
	 * Creates a single oscillator of the current type.
	 */
	private CollectionTemporalCellAdapter createOscillator() {
		switch (oscillatorType) {
			case SQUARE:
				SquareWaveCell square = new SquareWaveCell();
				if (ampEnvelope != null) square.setEnvelope(ampEnvelope);
				return square;
			case SAWTOOTH:
				SawtoothWaveCell saw = new SawtoothWaveCell();
				if (ampEnvelope != null) saw.setEnvelope(ampEnvelope);
				return saw;
			case TRIANGLE:
				TriangleWaveCell triangle = new TriangleWaveCell();
				if (ampEnvelope != null) triangle.setEnvelope(ampEnvelope);
				return triangle;
			case SINE:
			default:
				SineWaveCell sine = new SineWaveCell();
				if (ampEnvelope != null) sine.setEnvelope(ampEnvelope);
				return sine;
		}
	}

	/**
	 * Returns the summation cell that accumulates and forwards the mixed oscillator output.
	 *
	 * @return the output summation cell
	 */
	public Cell<PackedCollection> getOutput() {
		return output;
	}

	/**
	 * Returns the keyboard tuning system.
	 */
	public KeyboardTuning getTuning() { return tuning; }

	/**
	 * Sets the keyboard tuning system for MIDI note to frequency conversion.
	 */
	public void setTuning(KeyboardTuning t) { this.tuning = t; }

	/**
	 * Returns the current oscillator type.
	 */
	public OscillatorType getOscillatorType() { return oscillatorType; }

	/**
	 * Sets the oscillator type and recreates all oscillators.
	 *
	 * @param type the new oscillator type
	 */
	public void setOscillatorType(OscillatorType type) {
		if (this.oscillatorType != type) {
			this.oscillatorType = type;
			createOscillators(tones.count());
		}
	}

	/** Returns the synthesis model controlling per-frequency amplitude levels, or null for uniform levels. */
	public AudioSynthesisModel getModel() { return model; }

	/**
	 * Sets the synthesis model.
	 *
	 * @param model the synthesis model, or null for uniform levels
	 */
	public void setModel(AudioSynthesisModel model) { this.model = model; }

	/**
	 * Returns the amplitude envelope.
	 */
	public ADSREnvelope getAmpEnvelope() { return ampEnvelope; }

	/**
	 * Sets the amplitude envelope for all oscillators.
	 *
	 * @param envelope the ADSR envelope to apply
	 */
	public void setAmpEnvelope(ADSREnvelope envelope) {
		this.ampEnvelope = envelope;
		// Apply to existing oscillators
		for (CollectionTemporalCellAdapter cell : cells) {
			if (cell instanceof SineWaveCell) {
				((SineWaveCell) cell).setEnvelope(envelope);
			} else if (cell instanceof SquareWaveCell) {
				((SquareWaveCell) cell).setEnvelope(envelope);
			} else if (cell instanceof SawtoothWaveCell) {
				((SawtoothWaveCell) cell).setEnvelope(envelope);
			} else if (cell instanceof TriangleWaveCell) {
				((TriangleWaveCell) cell).setEnvelope(envelope);
			}
		}
	}

	// ========== Filter Methods ==========

	/**
	 * Returns the filter cell, or null if no filter is set.
	 */
	public BiquadFilterCell getFilter() { return filter; }

	/**
	 * Sets a lowpass filter with the specified cutoff and resonance.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param resonance Q factor (typically 0.5-10)
	 */
	public void setLowPassFilter(double cutoffHz, double resonance) {
		this.filterBaseCutoff = cutoffHz;
		this.filter = BiquadFilterCell.lowPass(cutoffHz, resonance);
	}

	/**
	 * Sets a highpass filter with the specified cutoff and resonance.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param resonance Q factor (typically 0.5-10)
	 */
	public void setHighPassFilter(double cutoffHz, double resonance) {
		this.filterBaseCutoff = cutoffHz;
		this.filter = BiquadFilterCell.highPass(cutoffHz, resonance);
	}

	/**
	 * Sets a bandpass filter with the specified center frequency and Q.
	 *
	 * @param centerHz center frequency in Hz
	 * @param q Q factor (bandwidth control)
	 */
	public void setBandPassFilter(double centerHz, double q) {
		this.filterBaseCutoff = centerHz;
		this.filter = BiquadFilterCell.bandPass(centerHz, q);
	}

	/**
	 * Removes the filter (bypass).
	 */
	public void clearFilter() {
		this.filter = null;
	}

	/**
	 * Returns the filter envelope.
	 */
	public ADSREnvelope getFilterEnvelope() { return filterEnvelope; }

	/**
	 * Sets the filter envelope for modulating filter cutoff.
	 *
	 * @param envelope the ADSR envelope
	 */
	public void setFilterEnvelope(ADSREnvelope envelope) {
		this.filterEnvelope = envelope;
	}

	/**
	 * Returns the filter envelope modulation amount.
	 */
	public double getFilterEnvelopeAmount() { return filterEnvelopeAmount; }

	/**
	 * Sets how much the filter envelope affects the cutoff frequency.
	 * <p>
	 * The effective cutoff is: baseCutoff + (envelopeValue * amount)
	 *
	 * @param amount modulation depth in Hz
	 */
	public void setFilterEnvelopeAmount(double amount) {
		this.filterEnvelopeAmount = amount;
	}

	/**
	 * Sets the base filter cutoff frequency.
	 * Note: This reconfigures the filter as a lowpass filter.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 */
	public void setFilterCutoff(double cutoffHz) {
		setFilterCutoff(cutoffHz, 0.707);
	}

	/**
	 * Sets the base filter cutoff frequency with specified resonance.
	 * Note: This reconfigures the filter as a lowpass filter.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param resonance Q factor
	 */
	public void setFilterCutoff(double cutoffHz, double resonance) {
		this.filterBaseCutoff = cutoffHz;
		if (filter != null) {
			filter.configureLowPass(cutoffHz, resonance);
		}
	}

	// ========== Velocity Methods ==========

	/**
	 * Returns the current velocity (amplitude scaling).
	 */
	public double getVelocity() { return velocity; }

	/**
	 * Sets the velocity (amplitude scaling) for MIDI-style dynamics.
	 *
	 * @param velocity velocity value (0.0-1.0)
	 */
	public void setVelocity(double velocity) {
		this.velocity = velocity;
		for (CollectionTemporalCellAdapter cell : cells) {
			setOscillatorAmplitude(cell, velocity);
		}
	}

	/**
	 * Sets the note using MIDI note number.
	 *
	 * @param key MIDI note number (0-127)
	 */
	public void setNoteMidi(int key) {
		setFrequency(tuning.getTone(key, KeyNumbering.MIDI));
	}

	/**
	 * Sets the fundamental frequency and updates all oscillators according
	 * to the {@link RelativeFrequencySet}.
	 *
	 * @param f the fundamental frequency
	 */
	public void setFrequency(Frequency f) {
		this.baseFrequency = f;
		applyFrequency(f);
	}

	/**
	 * Applies pitch bend by shifting the current base frequency by the
	 * specified number of semitones. Fractional semitones are supported.
	 *
	 * @param semitones pitch bend amount in semitones (e.g., 2.0 = up two semitones)
	 */
	public void setPitchBend(double semitones) {
		if (baseFrequency == null) return;
		double bentHz = baseFrequency.asHertz() * Math.pow(2.0, semitones / 12.0);
		applyFrequency(new Frequency(bentHz));
	}

	/**
	 * Updates all oscillators to match the given frequency according to the
	 * {@link RelativeFrequencySet}.
	 */
	private void applyFrequency(Frequency f) {
		Iterator<CollectionTemporalCellAdapter> itr = cells.iterator();
		for (Frequency r : tones.getFrequencies(f)) {
			if (itr.hasNext()) {
				setOscillatorFreq(itr.next(), r.asHertz());
			}
		}
	}

	/**
	 * Triggers note on - resets phase on all oscillators and starts envelopes.
	 */
	public void noteOn() {
		strike();
		if (ampEnvelope != null) {
			ampEnvelope.noteOn();
		}
		if (filterEnvelope != null) {
			filterEnvelope.noteOn();
		}
	}

	/**
	 * Triggers note on with MIDI parameters.
	 *
	 * @param midiNote MIDI note number (0-127)
	 * @param velocity velocity (0.0-1.0)
	 */
	public void noteOn(int midiNote, double velocity) {
		setNoteMidi(midiNote);
		setVelocity(velocity);
		noteOn();
	}

	/**
	 * Triggers note off - begins envelope release phase.
	 */
	public void noteOff() {
		if (ampEnvelope != null) {
			ampEnvelope.noteOff();
		}
		if (filterEnvelope != null) {
			filterEnvelope.noteOff();
		}
	}

	/**
	 * Returns true if the synthesizer is still producing sound
	 * (envelope has not completed release).
	 */
	public boolean isActive() {
		return ampEnvelope == null || ampEnvelope.isActive();
	}

	/**
	 * Strikes all oscillators (resets phase to beginning of wave).
	 */
	public void strike() {
		for (CollectionTemporalCellAdapter cell : cells) {
			strikeOscillator(cell);
		}
	}

	/**
	 * Helper to set frequency on any oscillator type.
	 */
	private void setOscillatorFreq(CollectionTemporalCellAdapter cell, double hertz) {
		if (cell instanceof SineWaveCell) {
			((SineWaveCell) cell).setFreq(hertz);
		} else if (cell instanceof SquareWaveCell) {
			((SquareWaveCell) cell).setFreq(hertz);
		} else if (cell instanceof SawtoothWaveCell) {
			((SawtoothWaveCell) cell).setFreq(hertz);
		} else if (cell instanceof TriangleWaveCell) {
			((TriangleWaveCell) cell).setFreq(hertz);
		}
	}

	/**
	 * Helper to set amplitude on any oscillator type.
	 */
	private void setOscillatorAmplitude(CollectionTemporalCellAdapter cell, double amplitude) {
		if (cell instanceof SineWaveCell) {
			((SineWaveCell) cell).setAmplitude(amplitude);
		} else if (cell instanceof SquareWaveCell) {
			((SquareWaveCell) cell).setAmplitude(amplitude);
		} else if (cell instanceof SawtoothWaveCell) {
			((SawtoothWaveCell) cell).setAmplitude(amplitude);
		} else if (cell instanceof TriangleWaveCell) {
			((TriangleWaveCell) cell).setAmplitude(amplitude);
		}
	}

	/**
	 * Helper to strike any oscillator type.
	 */
	private void strikeOscillator(CollectionTemporalCellAdapter cell) {
		if (cell instanceof SineWaveCell) {
			((SineWaveCell) cell).strike();
		} else if (cell instanceof SquareWaveCell) {
			((SquareWaveCell) cell).strike();
		} else if (cell instanceof SawtoothWaveCell) {
			((SawtoothWaveCell) cell).strike();
		} else if (cell instanceof TriangleWaveCell) {
			((TriangleWaveCell) cell).strike();
		}
	}

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("AudioSynthesizer Setup");

		// Setup all oscillator cells
		for (CollectionTemporalCellAdapter cell : cells) {
			setup.add(cell.setup());
		}

		// Setup filter if present
		if (filter != null) {
			setup.add(filter.setup());
		}

		return setup;
	}

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("AudioSynthesizer Tick");

		// Tick the amplitude envelope if present
		if (ampEnvelope != null) {
			tick.add(ampEnvelope.tick());
		}

		// Tick the filter envelope and apply modulation
		if (filterEnvelope != null) {
			tick.add(filterEnvelope.tick());

			// Update filter cutoff based on envelope
			if (filter != null && filterEnvelopeAmount != 0) {
				tick.add(() -> () -> {
					double envValue = filterEnvelope.getCurrentLevel();
					double modulatedCutoff = filterBaseCutoff + (envValue * filterEnvelopeAmount);
					double clampedCutoff = Math.max(20.0, Math.min(20000.0, modulatedCutoff));
					filter.configureLowPass(clampedCutoff, 0.707);
				});
			}
		}

		// Push audio from all oscillators and tick to advance wave position
		for (CollectionTemporalCellAdapter cell : cells) {
			tick.add(cell.push(null));
			tick.add(cell.tick());
		}

		// Tick the output cell to forward accumulated audio to receptor
		tick.add(output.tick());

		// Apply filter if present
		if (filter != null) {
			tick.add(filter.tick());
		}

		return tick;
	}

	@Override
	public Producer<PackedCollection> generate(BufferDetails buffer,
												  Producer<PackedCollection> params,
												  Factor<PackedCollection> frequency) {
		double amp = 0.75;
		return sampling(buffer.getSampleRate(), () -> {
			double scale = amp / tones.count();

			List<Producer<?>> series = new ArrayList<>();

			for (Frequency f : tones) {
				CollectionProducer t =
						integers(0, buffer.getFrames()).divide(buffer.getSampleRate());
				Producer<PackedCollection> ft = frequency.getResultant(t);
				CollectionProducer signal =
						sin(t.multiply(2 * Math.PI).multiply(f.asHertz()).multiply(ft));

				if (model != null) {
					Producer<PackedCollection> levels = model.getLevels(f.asHertz(), t);
					signal = signal.multiply(levels);
				}

				series.add(signal);
			}

			return model == null ? add(series).multiply(scale) : add(series);
		});
	}
}
