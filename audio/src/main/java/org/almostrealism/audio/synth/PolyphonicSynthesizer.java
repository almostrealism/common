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

package org.almostrealism.audio.synth;

import org.almostrealism.audio.filter.ADSREnvelope;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.RelativeFrequencySet;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.Temporal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A polyphonic synthesizer that manages multiple {@link AudioSynthesizer} voices.
 * <p>
 * PolyphonicSynthesizer combines {@link VoiceAllocator} for voice management with
 * multiple {@link AudioSynthesizer} instances to provide full polyphonic playback.
 * Each voice has its own oscillators, envelopes, and optionally filter.
 * <p>
 * Features:
 * <ul>
 *   <li>Configurable number of voices</li>
 *   <li>Voice stealing strategies via {@link VoiceAllocator.StealingStrategy}</li>
 *   <li>Shared configuration applied to all voices</li>
 *   <li>Uses {@link KeyboardTuning} for note-to-frequency conversion</li>
 *   <li>Uses {@link RelativeFrequencySet} for additive synthesis</li>
 * </ul>
 * <p>
 * Usage:
 * <pre>{@code
 * PolyphonicSynthesizer synth = new PolyphonicSynthesizer(8);
 * synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
 * synth.setLowPassFilter(2000, 2.0);
 * synth.setAmpEnvelopeParams(0.01, 0.2, 0.5, 0.3);
 *
 * // Play notes
 * synth.noteOn(60, 0.8);  // Middle C
 * synth.noteOn(64, 0.7);  // E
 * synth.noteOn(67, 0.6);  // G
 *
 * // Release notes
 * synth.noteOff(60);
 * }</pre>
 *
 * @see AudioSynthesizer
 * @see VoiceAllocator
 * @see RelativeFrequencySet
 */
public class PolyphonicSynthesizer implements Temporal {

	private final VoiceAllocator allocator;
	private final List<AudioSynthesizer> voices;
	private final SummationCell output;

	// Shared configuration
	private KeyboardTuning tuning;
	private RelativeFrequencySet tones;
	private AudioSynthesizer.OscillatorType oscillatorType;
	private double ampAttack, ampDecay, ampSustain, ampRelease;
	private double filterCutoff, filterResonance;
	private double filterEnvAmount;
	private double filterAttack, filterDecay, filterSustain, filterRelease;
	private boolean filterEnabled;

	// Modulation
	private LFO vibratoLFO;
	private double vibratoDepth;  // In semitones
	private LFO tremoloLFO;
	private double tremoloDepth;  // 0-1 amplitude modulation

	/**
	 * Creates a polyphonic synthesizer with the specified number of voices.
	 *
	 * @param maxVoices the maximum number of simultaneous voices
	 */
	public PolyphonicSynthesizer(int maxVoices) {
		this(maxVoices, new OvertoneSeries(0, 0, 0));  // Single fundamental tone
	}

	/**
	 * Creates a polyphonic synthesizer with the specified voices and tone set.
	 *
	 * @param maxVoices the maximum number of simultaneous voices
	 * @param tones the relative frequency set for each voice
	 */
	public PolyphonicSynthesizer(int maxVoices, RelativeFrequencySet tones) {
		this.allocator = new VoiceAllocator(maxVoices);
		this.voices = new ArrayList<>(maxVoices);
		this.output = new SummationCell();
		this.tuning = new DefaultKeyboardTuning();
		this.tones = tones;
		this.oscillatorType = AudioSynthesizer.OscillatorType.SAWTOOTH;

		// Default envelope settings
		this.ampAttack = 0.01;
		this.ampDecay = 0.1;
		this.ampSustain = 0.7;
		this.ampRelease = 0.3;

		// Default filter settings
		this.filterCutoff = 5000.0;
		this.filterResonance = 0.707;
		this.filterEnvAmount = 0.0;
		this.filterAttack = 0.01;
		this.filterDecay = 0.3;
		this.filterSustain = 0.5;
		this.filterRelease = 0.2;
		this.filterEnabled = false;

		// Create voice synthesizers
		for (int i = 0; i < maxVoices; i++) {
			AudioSynthesizer voice = createVoice();
			voices.add(voice);
		}
	}

	/**
	 * Creates a new voice synthesizer with current shared configuration.
	 */
	private AudioSynthesizer createVoice() {
		AudioSynthesizer voice = new AudioSynthesizer(null, tones, oscillatorType);
		voice.setTuning(tuning);

		// Configure amplitude envelope
		ADSREnvelope ampEnv = new ADSREnvelope(ampAttack, ampDecay, ampSustain, ampRelease);
		voice.setAmpEnvelope(ampEnv);

		// Configure filter if enabled
		if (filterEnabled) {
			voice.setLowPassFilter(filterCutoff, filterResonance);
			ADSREnvelope filterEnv = new ADSREnvelope(filterAttack, filterDecay, filterSustain, filterRelease);
			voice.setFilterEnvelope(filterEnv);
			voice.setFilterEnvelopeAmount(filterEnvAmount);
		}

		// Connect to output
		voice.getOutput().setReceptor(output);

		return voice;
	}

	/**
	 * Returns the combined output cell.
	 */
	public Cell<PackedCollection> getOutput() {
		return output;
	}

	/**
	 * Returns the voice allocator for advanced voice management.
	 */
	public VoiceAllocator getAllocator() {
		return allocator;
	}

	/**
	 * Returns the number of maximum voices.
	 */
	public int getMaxVoices() {
		return allocator.getMaxVoices();
	}

	/**
	 * Returns the number of currently active voices.
	 */
	public int getActiveVoiceCount() {
		return allocator.getActiveVoiceCount();
	}

	// ========== Configuration Methods ==========

	/**
	 * Sets the keyboard tuning system.
	 */
	public void setTuning(KeyboardTuning tuning) {
		this.tuning = tuning;
		for (AudioSynthesizer voice : voices) {
			voice.setTuning(tuning);
		}
	}

	/**
	 * Sets the oscillator type for all voices.
	 */
	public void setOscillatorType(AudioSynthesizer.OscillatorType type) {
		this.oscillatorType = type;
		for (AudioSynthesizer voice : voices) {
			voice.setOscillatorType(type);
		}
	}

	/**
	 * Sets the voice stealing strategy.
	 */
	public void setStealingStrategy(VoiceAllocator.StealingStrategy strategy) {
		allocator.setStealingStrategy(strategy);
	}

	/**
	 * Sets the amplitude envelope parameters for all voices.
	 *
	 * @param attack attack time in seconds
	 * @param decay decay time in seconds
	 * @param sustain sustain level (0-1)
	 * @param release release time in seconds
	 */
	public void setAmpEnvelopeParams(double attack, double decay, double sustain, double release) {
		this.ampAttack = attack;
		this.ampDecay = decay;
		this.ampSustain = sustain;
		this.ampRelease = release;

		for (AudioSynthesizer voice : voices) {
			ADSREnvelope env = voice.getAmpEnvelope();
			if (env != null) {
				env.setAttack(attack);
				env.setDecay(decay);
				env.setSustain(sustain);
				env.setRelease(release);
			}
		}
	}

	/**
	 * Enables a lowpass filter on all voices.
	 *
	 * @param cutoffHz cutoff frequency in Hz
	 * @param resonance Q factor
	 */
	public void setLowPassFilter(double cutoffHz, double resonance) {
		this.filterEnabled = true;
		this.filterCutoff = cutoffHz;
		this.filterResonance = resonance;

		for (AudioSynthesizer voice : voices) {
			voice.setLowPassFilter(cutoffHz, resonance);
		}
	}

	/**
	 * Sets the filter envelope parameters for all voices.
	 *
	 * @param attack attack time in seconds
	 * @param decay decay time in seconds
	 * @param sustain sustain level (0-1)
	 * @param release release time in seconds
	 */
	public void setFilterEnvelopeParams(double attack, double decay, double sustain, double release) {
		this.filterAttack = attack;
		this.filterDecay = decay;
		this.filterSustain = sustain;
		this.filterRelease = release;

		for (AudioSynthesizer voice : voices) {
			ADSREnvelope env = voice.getFilterEnvelope();
			if (env != null) {
				env.setAttack(attack);
				env.setDecay(decay);
				env.setSustain(sustain);
				env.setRelease(release);
			}
		}
	}

	/**
	 * Sets the filter envelope modulation amount.
	 *
	 * @param amount modulation depth in Hz
	 */
	public void setFilterEnvelopeAmount(double amount) {
		this.filterEnvAmount = amount;

		for (AudioSynthesizer voice : voices) {
			voice.setFilterEnvelopeAmount(amount);
		}
	}

	/**
	 * Disables the filter on all voices.
	 */
	public void clearFilter() {
		this.filterEnabled = false;

		for (AudioSynthesizer voice : voices) {
			voice.clearFilter();
		}
	}

	// ========== Modulation Methods ==========

	/**
	 * Enables vibrato (pitch modulation) with the specified rate and depth.
	 *
	 * @param rate LFO frequency in Hz (typically 4-7 Hz)
	 * @param depthSemitones modulation depth in semitones
	 */
	public void setVibrato(double rate, double depthSemitones) {
		if (vibratoLFO == null) {
			vibratoLFO = LFO.vibrato(rate);
		} else {
			vibratoLFO.setFrequency(rate);
		}
		this.vibratoDepth = depthSemitones;
	}

	/**
	 * Disables vibrato.
	 */
	public void clearVibrato() {
		this.vibratoLFO = null;
		this.vibratoDepth = 0;
	}

	/**
	 * Enables tremolo (amplitude modulation) with the specified rate and depth.
	 *
	 * @param rate LFO frequency in Hz (typically 4-10 Hz)
	 * @param depth modulation depth (0-1)
	 */
	public void setTremolo(double rate, double depth) {
		if (tremoloLFO == null) {
			tremoloLFO = LFO.tremolo(rate);
		} else {
			tremoloLFO.setFrequency(rate);
		}
		this.tremoloDepth = depth;
	}

	/**
	 * Disables tremolo.
	 */
	public void clearTremolo() {
		this.tremoloLFO = null;
		this.tremoloDepth = 0;
	}

	/**
	 * Returns the vibrato LFO, or null if not enabled.
	 */
	public LFO getVibratoLFO() {
		return vibratoLFO;
	}

	/**
	 * Returns the tremolo LFO, or null if not enabled.
	 */
	public LFO getTremoloLFO() {
		return tremoloLFO;
	}

	// ========== Note Control Methods ==========

	/**
	 * Triggers a note on event.
	 *
	 * @param midiNote MIDI note number (0-127)
	 * @param velocity velocity (0.0-1.0)
	 * @return true if a voice was allocated
	 */
	public boolean noteOn(int midiNote, double velocity) {
		VoiceState state = allocator.allocate(midiNote, velocity);
		if (state != null) {
			AudioSynthesizer voice = voices.get(state.getVoiceIndex());
			voice.noteOn(midiNote, velocity);
			return true;
		}
		return false;
	}

	/**
	 * Triggers a note off event.
	 *
	 * @param midiNote MIDI note number
	 * @return true if a voice was released
	 */
	public boolean noteOff(int midiNote) {
		VoiceState state = allocator.release(midiNote);
		if (state != null) {
			AudioSynthesizer voice = voices.get(state.getVoiceIndex());
			voice.noteOff();
			return true;
		}
		return false;
	}

	/**
	 * Releases all notes (panic/all notes off).
	 */
	public void allNotesOff() {
		allocator.allNotesOff();
		for (AudioSynthesizer voice : voices) {
			voice.noteOff();
		}
	}

	// ========== Temporal Implementation ==========

	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("PolyphonicSynthesizer Tick");

		// Tick LFOs
		if (vibratoLFO != null) {
			tick.add(vibratoLFO.tick());
		}
		if (tremoloLFO != null) {
			tick.add(tremoloLFO.tick());
		}

		// Apply modulation and tick all voices
		for (int i = 0; i < voices.size(); i++) {
			VoiceState state = allocator.getVoice(i);
			AudioSynthesizer voice = voices.get(i);

			if (state.isActive()) {
				// Apply vibrato (pitch modulation)
				if (vibratoLFO != null && vibratoDepth > 0) {
					final int midiNote = state.getMidiNote();
					final AudioSynthesizer v = voice;
					tick.add(() -> () -> {
						double lfoValue = vibratoLFO.getValue();
						double semitoneOffset = lfoValue * vibratoDepth;
						double pitchMultiplier = Math.pow(2.0, semitoneOffset / 12.0);
						Frequency baseFreq = tuning.getTone(midiNote,
							org.almostrealism.audio.tone.KeyNumbering.MIDI);
						v.setFrequency(new Frequency(baseFreq.asHertz() * pitchMultiplier));
					});
				}

				// Apply tremolo (amplitude modulation)
				if (tremoloLFO != null && tremoloDepth > 0) {
					final AudioSynthesizer v = voice;
					final double baseVelocity = state.getVelocity();
					tick.add(() -> () -> {
						double lfoValue = tremoloLFO.getValue();
						// Convert bipolar LFO (-1 to 1) to amplitude modulation
						double modulation = 1.0 - (tremoloDepth * (1.0 - (lfoValue + 1.0) / 2.0));
						v.setVelocity(baseVelocity * modulation);
					});
				}

				tick.add(voice.tick());

				// Check if release has completed
				if (state.isReleasing() && !voice.isActive()) {
					final int voiceIndex = i;
					tick.add(() -> () -> allocator.deactivate(voiceIndex));
				}
			}
		}

		return tick;
	}

	/**
	 * Resets all voices to initial state.
	 */
	public void reset() {
		allNotesOff();
		for (AudioSynthesizer voice : voices) {
			if (voice.getAmpEnvelope() != null) {
				voice.getAmpEnvelope().reset();
			}
			if (voice.getFilterEnvelope() != null) {
				voice.getFilterEnvelope().reset();
			}
		}
	}
}
