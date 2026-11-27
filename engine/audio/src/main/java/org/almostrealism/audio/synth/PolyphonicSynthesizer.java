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
import org.almostrealism.audio.tone.KeyNumbering;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.audio.tone.KeyNumbering;
import org.almostrealism.audio.tone.RelativeFrequencySet;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.time.Frequency;

import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.lifecycle.Setup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A polyphonic synthesizer that manages multiple {@link AudioSynthesizer} voices.
 * <p>
 * PolyphonicSynthesizer extends {@link SummationCell} to accumulate audio from all
 * active voices. Each voice pushes its audio to this cell, which sums them together
 * and forwards the result to the downstream receptor on each tick.
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
 * // Connect to audio output
 * synth.setReceptor(audioOutput);
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
 * @see SummationCell
 */
public class PolyphonicSynthesizer extends SummationCell implements Setup, Destroyable {

	/** Voice allocator that manages voice assignment and stealing across the voice pool. */
	private final VoiceAllocator allocator;

	/** Pool of AudioSynthesizer voices; each may be active or idle. */
	private final List<AudioSynthesizer> voices;

	// Shared configuration
	/** Keyboard tuning system for MIDI-note-to-frequency conversion, shared across all voices. */
	private KeyboardTuning tuning;

	/** Relative frequency set defining partials for each oscillator voice. */
	private RelativeFrequencySet tones;

	/** Waveform type applied to all oscillators in every voice. */
	private AudioSynthesizer.OscillatorType oscillatorType;

	/** Amplitude envelope parameters (attack, decay, sustain, release) applied to all voices. */
	private double ampAttack, ampDecay, ampSustain, ampRelease;

	/** Filter cutoff frequency in Hz and resonance Q factor applied to all voices. */
	private double filterCutoff, filterResonance;

	/** Depth of filter envelope modulation in Hz applied to all voices. */
	private double filterEnvAmount;

	/** Filter envelope parameters (attack, decay, sustain, release) applied to all voices. */
	private double filterAttack, filterDecay, filterSustain, filterRelease;

	/** True when the filter is enabled on all voices. */
	private boolean filterEnabled;

	// Modulation
	/** LFO for pitch vibrato modulation; null if vibrato is not active. */
	private LFO vibratoLFO;

	/** Vibrato modulation depth in semitones. */
	private double vibratoDepth;  // In semitones

	/** LFO for amplitude tremolo modulation; null if tremolo is not active. */
	private LFO tremoloLFO;

	/** Tremolo modulation depth as a fraction of amplitude (0–1). */
	private double tremoloDepth;  // 0-1 amplitude modulation

	/**
	 * LFOs detached by {@link #clearVibrato()} / {@link #clearTremolo()} that this synthesizer still
	 * owns and destroys in {@link #destroy()}. An LFO's device buffers are captured by reference into
	 * any {@link #tick()} already compiled while it was active, so destruction is deferred to the end
	 * of this synthesizer's own lifecycle rather than performed from the user-facing clear controls.
	 */
	private final List<LFO> retiredModulators = new ArrayList<>();

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

		// Connect voice output to this synthesizer (which is a SummationCell)
		voice.getOutput().setReceptor(this);

		return voice;
	}

	/**
	 * Returns the voice allocator for advanced voice management.
	 */
	public VoiceAllocator getAllocator() {
		return allocator;
	}

	/**
	 * Returns the voice at {@code index} in the voice pool, the one the {@link #getAllocator()
	 * allocator} assigns that index to.
	 *
	 * @param index the voice index, from zero to {@link #getMaxVoices()} exclusive
	 * @return the voice
	 */
	public AudioSynthesizer getVoice(int index) {
		return voices.get(index);
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
	 * <p>
	 * The LFO's device buffers may already be referenced by a previously compiled {@link #tick()},
	 * so the LFO is retained for destruction in {@link #destroy()} rather than freed here; see
	 * {@link #retiredModulators}.
	 */
	public void clearVibrato() {
		retireModulator(vibratoLFO);
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
	 * <p>
	 * The LFO's device buffers may already be referenced by a previously compiled {@link #tick()},
	 * so the LFO is retained for destruction in {@link #destroy()} rather than freed here; see
	 * {@link #retiredModulators}.
	 */
	public void clearTremolo() {
		retireModulator(tremoloLFO);
		this.tremoloLFO = null;
		this.tremoloDepth = 0;
	}

	/**
	 * Retains {@code lfo} for destruction in {@link #destroy()}, if it is non-null. A detached LFO is
	 * not destroyed immediately because a {@link #tick()} compiled while it was active still
	 * references its device buffers by reference; destroying it would leave that tick reading freed
	 * native memory.
	 *
	 * @param lfo the LFO being detached from active modulation, or null
	 */
	private void retireModulator(LFO lfo) {
		if (lfo != null) {
			retiredModulators.add(lfo);
		}
	}

	/**
	 * Returns the vibrato depth a tick that captured {@code captured} applies: the current depth
	 * while {@code captured} is still the active vibrato LFO, and zero once it has been cleared or
	 * replaced, so a tick compiled before the change does not modulate from a retired LFO.
	 *
	 * @param captured the vibrato LFO a compiled tick was built with
	 * @return the depth in semitones to apply
	 */
	private double vibratoDepthFor(LFO captured) {
		return vibratoLFO == captured ? vibratoDepth : 0;
	}

	/**
	 * Returns the tremolo depth a tick that captured {@code captured} applies: the current depth
	 * while {@code captured} is still the active tremolo LFO, and zero once it has been cleared or
	 * replaced, so a tick compiled before the change does not modulate from a retired LFO.
	 *
	 * @param captured the tremolo LFO a compiled tick was built with
	 * @return the depth, as a fraction of amplitude, to apply
	 */
	private double tremoloDepthFor(LFO captured) {
		return tremoloLFO == captured ? tremoloDepth : 0;
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

	/**
	 * Releases the device resources of every LFO this synthesizer created &mdash; the active vibrato
	 * and tremolo LFOs together with any {@link #retiredModulators retired} by {@link #clearVibrato()}
	 * or {@link #clearTremolo()}. Because an LFO's buffers are captured by reference into any
	 * {@link #tick()} compiled while it was active, destruction is owned by this lifecycle method
	 * rather than the clear controls: callers must stop running ticks built from this synthesizer
	 * before destroying it. Every release is attempted even if an earlier one fails.
	 * <p>
	 * Only the LFOs are released here; the voices and the inherited cell state are not yet
	 * destroyable and are reclaimed with the synthesizer itself.
	 */
	@Override
	public void destroy() {
		retireModulator(vibratoLFO);
		retireModulator(tremoloLFO);
		vibratoLFO = null;
		tremoloLFO = null;
		vibratoDepth = 0;
		tremoloDepth = 0;

		List<Runnable> releases = new ArrayList<>();
		retiredModulators.forEach(lfo -> releases.add(lfo::destroy));
		retiredModulators.clear();

		Destroyable.releaseAll(releases);
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
	 * Applies pitch bend to all active voices.
	 *
	 * @param semitones pitch bend amount in semitones
	 */
	public void setPitchBend(double semitones) {
		for (int i = 0; i < voices.size(); i++) {
			VoiceState state = allocator.getVoice(i);
			if (state.isActive()) {
				voices.get(i).setPitchBend(semitones);
			}
		}
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

	/**
	 * Initializes the synthesizer and all voices.
	 * <p>
	 * This must be called before generating audio. It initializes:
	 * <ul>
	 *   <li>All voice synthesizers</li>
	 *   <li>The summation cell (via super.setup())</li>
	 * </ul>
	 *
	 * @return operation that initializes all components
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("PolyphonicSynthesizer Setup");

		// Setup all voice synthesizers
		for (AudioSynthesizer voice : voices) {
			setup.add(voice.setup());
		}

		// Setup the summation cell (inherited)
		setup.add(super.setup());

		return setup;
	}

	/**
	 * Advances the synthesizer state and forwards accumulated audio.
	 * <p>
	 * This method:
	 * <ol>
	 *   <li>Ticks LFOs for modulation</li>
	 *   <li>Applies modulation to active voices</li>
	 *   <li>Ticks all voices (generating audio that accumulates in this cell)</li>
	 *   <li>Forwards the accumulated audio to the receptor (via super.tick())</li>
	 * </ol>
	 *
	 * <p>
	 * The vibrato and tremolo LFOs active when this method is called are captured by the returned
	 * operation, while the modulation depths are read each time it runs, and only while the LFO
	 * the operation captured is still the active one. A tick compiled before
	 * {@link #clearVibrato()} or {@link #clearTremolo()} therefore keeps using the retired LFO
	 * (which stays alive until {@link #destroy()}) with a depth of zero, so it applies no
	 * modulation rather than dereferencing the cleared field &mdash; even after modulation is
	 * enabled again with a new LFO, which that older tick does not advance.
	 *
	 * @return operation that processes one sample frame
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("PolyphonicSynthesizer Tick");
		final LFO vibrato = vibratoLFO;
		final LFO tremolo = tremoloLFO;

		// Tick LFOs
		if (vibrato != null) {
			tick.add(vibrato.tick());
		}
		if (tremolo != null) {
			tick.add(tremolo.tick());
		}

		// Process all voices
		for (int i = 0; i < voices.size(); i++) {
			final int voiceIndex = i;
			final AudioSynthesizer voice = voices.get(i);

			// Apply modulation at runtime (only if voice is active)
			if (vibrato != null && vibratoDepth > 0) {
				tick.add(() -> () -> {
					VoiceState state = allocator.getVoice(voiceIndex);
					if (state.isActive()) {
						double lfoValue = vibrato.getValue();
						double semitoneOffset = lfoValue * vibratoDepthFor(vibrato);
						double pitchMultiplier = Math.pow(2.0, semitoneOffset / 12.0);
						Frequency baseFreq = tuning.getTone(state.getMidiNote(),
							KeyNumbering.MIDI);
						voice.setFrequency(new Frequency(baseFreq.asHertz() * pitchMultiplier));
					}
				});
			}

			if (tremolo != null && tremoloDepth > 0) {
				tick.add(() -> () -> {
					VoiceState state = allocator.getVoice(voiceIndex);
					if (state.isActive()) {
						double lfoValue = tremolo.getValue();
						double modulation = 1.0 - (tremoloDepthFor(tremolo) * (1.0 - (lfoValue + 1.0) / 2.0));
						voice.setVelocity(state.getVelocity() * modulation);
					}
				});
			}

			// Tick the voice - this generates audio that gets pushed to this cell
			tick.add(voice.tick());
		}

		// Forward accumulated audio to receptor (inherited from SummationCell)
		tick.add(super.tick());

		return tick;
	}

	/**
	 * Resets all voices to initial state.
	 */
	@Override
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
