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

import io.almostrealism.relation.Producer;
import org.almostrealism.audio.filter.ADSREnvelope;
import org.almostrealism.audio.tone.DefaultKeyboardTuning;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.RelativeFrequencySet;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.Temporal;

import io.almostrealism.cycle.Setup;

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
public class PolyphonicSynthesizer implements Temporal, Setup, Cell<PackedCollection> {

	private final VoiceAllocator allocator;
	private final List<AudioSynthesizer> voices;
	private final SummationCell output;
	private Receptor<PackedCollection> receptor;

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
		// Check for completed releases to free up voices before allocation.
		// This was moved here from push() to allow push() to compile to a kernel.
		checkReleases();
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

	/**
	 * Checks all voices for completed releases and deactivates them.
	 * <p>
	 * This method is called from {@link #noteOn(int, double)} to free up voices
	 * whose release phase has completed. Moving this check out of {@link #push(Producer)}
	 * allows push() to return a fully compilable OperationList without runtime lambdas.
	 */
	private void checkReleases() {
		for (int i = 0; i < voices.size(); i++) {
			VoiceState state = allocator.getVoice(i);
			if (state.isActive() && state.isReleasing() && !voices.get(i).isActive()) {
				allocator.deactivate(i);
			}
		}
	}

	// ========== Setup and Temporal Implementation ==========

	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("PolyphonicSynthesizer Setup");

		// Setup all voice synthesizers
		for (AudioSynthesizer voice : voices) {
			setup.add(voice.setup());
		}

		return setup;
	}

	/**
	 * Performs per-sample state updates.
	 * <p>
	 * Behavior depends on context:
	 * <ul>
	 *   <li><b>CellList mode</b> (receptor set via {@link #setReceptor}): Returns no-op because
	 *       CellList calls both push() (from roots) and tick() (from temporals), and push()
	 *       already handles everything.</li>
	 *   <li><b>Standalone mode</b> (receptor set on output directly): Delegates to
	 *       {@link #push(Producer)} for audio generation.</li>
	 * </ul>
	 *
	 * @return operation for audio generation (standalone) or no-op (CellList mode)
	 */
	@Override
	public Supplier<Runnable> tick() {
		// Detect context: if receptor was set via setReceptor(), we're in CellList mode
		// and push() already handles audio generation. If receptor is null, we're in
		// standalone mode and tick() should generate audio.
		if (receptor != null) {
			// CellList mode: no-op to avoid double-processing
			return new OperationList("PolyphonicSynthesizer Tick (CellList mode - no-op)");
		} else {
			// Standalone mode: delegate to push() for audio generation
			return push(null);
		}
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

	// ========== Cell Implementation ==========

	/**
	 * Sets the receptor that will receive the synthesizer's audio output.
	 * <p>
	 * This configures the internal output cell to forward audio to the specified receptor.
	 * When using the synthesizer with {@link org.almostrealism.audio.CellList}, add this
	 * synthesizer directly using {@code addRoot(synth)} rather than adding the output cell.
	 *
	 * @param r the receptor to receive audio output
	 */
	@Override
	public void setReceptor(Receptor<PackedCollection> r) {
		this.receptor = r;
		// Also set on the internal output cell so audio flows through
		output.setReceptor(r);
	}

	/**
	 * Returns the receptor that receives this synthesizer's audio output.
	 *
	 * @return the receptor, or null if not set
	 */
	@Override
	public Receptor<PackedCollection> getReceptor() {
		return receptor;
	}

	/**
	 * Generates audio samples and pushes them to the receptor.
	 * <p>
	 * This method is called by {@link org.almostrealism.audio.CellList} to generate
	 * audio for each sample frame. It triggers all active voices to generate audio
	 * which is accumulated in the internal output cell and then forwarded to the receptor.
	 * <p>
	 * Note: The input protein is ignored - the synthesizer generates audio from its
	 * internal state (active notes, oscillators, envelopes).
	 *
	 * @param protein ignored input (synthesizer generates from internal state)
	 * @return operation that generates and pushes audio
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection> protein) {
		OperationList push = new OperationList("PolyphonicSynthesizer Push");

		// Tick LFOs (these are compile-time optional, which is fine)
		if (vibratoLFO != null) {
			push.add(vibratoLFO.tick());
		}
		if (tremoloLFO != null) {
			push.add(tremoloLFO.tick());
		}

		// CRITICAL: Always tick ALL voices unconditionally.
		// The voice activity check must happen at RUNTIME, not compile time.
		// Operations are compiled once when the scheduler starts, so compile-time
		// checks like "if (state.isActive())" would freeze the active state.
		// AudioSynthesizer handles inactive state internally (envelope at 0).
		for (int i = 0; i < voices.size(); i++) {
			final int voiceIndex = i;
			final AudioSynthesizer voice = voices.get(i);

			// Apply modulation at runtime (only if voice is active)
			if (vibratoLFO != null && vibratoDepth > 0) {
				push.add(() -> () -> {
					VoiceState state = allocator.getVoice(voiceIndex);
					if (state.isActive()) {
						double lfoValue = vibratoLFO.getValue();
						double semitoneOffset = lfoValue * vibratoDepth;
						double pitchMultiplier = Math.pow(2.0, semitoneOffset / 12.0);
						Frequency baseFreq = tuning.getTone(state.getMidiNote(),
							org.almostrealism.audio.tone.KeyNumbering.MIDI);
						voice.setFrequency(new Frequency(baseFreq.asHertz() * pitchMultiplier));
					}
				});
			}

			if (tremoloLFO != null && tremoloDepth > 0) {
				push.add(() -> () -> {
					VoiceState state = allocator.getVoice(voiceIndex);
					if (state.isActive()) {
						double lfoValue = tremoloLFO.getValue();
						double modulation = 1.0 - (tremoloDepth * (1.0 - (lfoValue + 1.0) / 2.0));
						voice.setVelocity(state.getVelocity() * modulation);
					}
				});
			}

			// Always tick the voice - inactive voices produce silence via envelope
			push.add(voice.tick());

			// NOTE: Release checking was moved to noteOn() to allow this OperationList
			// to compile to a kernel. See checkReleases() method.
		}

		// Forward accumulated audio to receptor
		push.add(output.tick());

		return push;
	}
}
