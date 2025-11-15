/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.time;

/**
 * Represents a frequency value with conversions between Hertz (Hz) and Beats Per Minute (BPM),
 * and provides utilities for calculating wavelengths and time durations.
 *
 * <p>{@link Frequency} is an immutable value class that stores frequency in Hertz internally
 * and provides convenient methods for working with musical tempo (BPM), wavelengths, and
 * time calculations commonly needed in audio synthesis and signal processing.</p>
 *
 * <h2>Core Conversions</h2>
 * <ul>
 *   <li><strong>Hertz (Hz):</strong> Cycles per second (standard SI unit)</li>
 *   <li><strong>BPM:</strong> Beats per minute (musical tempo)</li>
 *   <li><strong>Wavelength:</strong> Time period of one complete cycle</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>Creating Frequencies</h3>
 * <pre>{@code
 * // From Hertz
 * Frequency f1 = new Frequency(440.0);  // A4 note
 * System.out.println(f1.asHertz());     // 440.0
 *
 * // From BPM (musical tempo)
 * Frequency tempo = Frequency.forBPM(120.0);  // 120 BPM
 * System.out.println(tempo.asHertz());        // 2.0 Hz
 * System.out.println(tempo.asBPM());          // 120.0
 * }</pre>
 *
 * <h3>Wavelength Calculations</h3>
 * <pre>{@code
 * Frequency f = new Frequency(2.0);  // 2 Hz
 * double wavelength = f.getWaveLength();  // 0.5 seconds
 *
 * // Calculate time for multiple cycles
 * double time4Beats = f.l(4);  // 2.0 seconds (4 cycles at 2 Hz)
 * }</pre>
 *
 * <h3>Musical Applications</h3>
 * <pre>{@code
 * // Tempo-based timing
 * Frequency bpm = Frequency.forBPM(120.0);
 *
 * double quarterNote = bpm.getWaveLength();     // 0.5 seconds
 * double wholeNote = bpm.l(4);                  // 2.0 seconds (4 beats)
 * double sixteenthNote = bpm.l(0.25);           // 0.125 seconds
 *
 * // Use in synthesis
 * Temporal oscillator = createOscillator(new Frequency(440.0));
 * }</pre>
 *
 * <h3>Integration with TemporalFeatures</h3>
 * <pre>{@code
 * // Using the convenience method from TemporalFeatures
 * Frequency tempo = bpm(128.0);  // 128 BPM
 *
 * // Calculate buffer sizes based on tempo
 * int sampleRate = 44100;
 * int samplesPerBeat = (int) (tempo.getWaveLength() * sampleRate);
 * }</pre>
 *
 * <h2>Common Use Cases</h2>
 * <ul>
 *   <li><strong>Audio Synthesis:</strong> Oscillator frequencies for musical notes</li>
 *   <li><strong>Tempo Synchronization:</strong> Converting BPM to time durations</li>
 *   <li><strong>LFO Rates:</strong> Low-frequency oscillator modulation speeds</li>
 *   <li><strong>Sequencing:</strong> Calculating note durations and timing</li>
 *   <li><strong>Filter Design:</strong> Cutoff frequencies in Hz</li>
 * </ul>
 *
 * <h2>Relationship Formulas</h2>
 * <pre>
 * BPM = Hz × 60
 * Hz = BPM / 60
 * Wavelength = 1 / Hz (in seconds)
 * Time for n cycles = n × Wavelength
 * </pre>
 *
 * <h2>Musical Note Frequencies</h2>
 * <p>Common musical note frequencies (A440 tuning):</p>
 * <pre>{@code
 * new Frequency(261.63);  // Middle C (C4)
 * new Frequency(440.00);  // A4 (concert pitch)
 * new Frequency(523.25);  // C5
 * }</pre>
 *
 * <h2>Immutability</h2>
 * <p>{@link Frequency} instances are immutable. All values are final and set during construction.</p>
 *
 * @see TemporalFeatures#bpm(double)
 *
 * @author Michael Murray
 */
public class Frequency {
	private final double hertz;

	/**
	 * Constructs a frequency with the specified value in Hertz.
	 *
	 * @param hertz The frequency in cycles per second (Hz)
	 */
	public Frequency(double hertz) {
		this.hertz = hertz;
	}

	/**
	 * Returns the frequency in Hertz (cycles per second).
	 *
	 * @return The frequency in Hz
	 */
	public double asHertz() { return hertz; }

	/**
	 * Returns the frequency converted to Beats Per Minute (BPM).
	 *
	 * <p>Conversion formula: BPM = Hz × 60</p>
	 *
	 * @return The frequency in beats per minute
	 */
	public double asBPM() { return asHertz() * 60; }

	/**
	 * Returns the wavelength (period) of one complete cycle in seconds.
	 *
	 * <p>Wavelength is the reciprocal of frequency: wavelength = 1 / Hz</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Frequency f = new Frequency(2.0);  // 2 Hz
	 * double period = f.getWaveLength();  // 0.5 seconds
	 * }</pre>
	 *
	 * @return The time duration of one cycle in seconds
	 */
	public double getWaveLength() { return 1.0 / asHertz(); }

	/**
	 * Calculates the total time duration for a specified number of complete cycles.
	 *
	 * <p>This method is useful for calculating note durations in musical applications
	 * where count represents the number of beats or subdivisions.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Frequency tempo = Frequency.forBPM(120.0);
	 * double fourBeats = tempo.l(4);  // Time for 4 beats
	 * }</pre>
	 *
	 * @param count The number of cycles
	 * @return The total time duration in seconds
	 */
	public double l(int count) { return count * getWaveLength(); }

	/**
	 * Calculates the total time duration for a specified fractional number of cycles.
	 *
	 * <p>This overload accepts fractional cycle counts, useful for subdivisions like
	 * half-notes, eighth-notes, etc.</p>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Frequency tempo = Frequency.forBPM(120.0);
	 * double halfBeat = tempo.l(0.5);      // Half note
	 * double quarterBeat = tempo.l(0.25);  // Sixteenth note
	 * }</pre>
	 *
	 * @param count The fractional number of cycles
	 * @return The total time duration in seconds
	 */
	public double l(double count) { return count * getWaveLength(); }

	/**
	 * Creates a {@link Frequency} from a tempo specified in Beats Per Minute (BPM).
	 *
	 * <p>This factory method converts musical tempo to frequency. Common tempos:</p>
	 * <ul>
	 *   <li>60 BPM = 1 Hz (one beat per second)</li>
	 *   <li>120 BPM = 2 Hz</li>
	 *   <li>240 BPM = 4 Hz</li>
	 * </ul>
	 *
	 * <h3>Example</h3>
	 * <pre>{@code
	 * Frequency moderate = Frequency.forBPM(120.0);  // Moderate tempo
	 * Frequency fast = Frequency.forBPM(180.0);      // Fast tempo
	 * }</pre>
	 *
	 * @param bpm The tempo in beats per minute
	 * @return A new {@link Frequency} instance representing the specified BPM
	 */
	public static Frequency forBPM(double bpm) {
		return new Frequency(bpm / 60);
	}

	/**
	 * Returns a string representation of this frequency in Hertz.
	 *
	 * @return A string like "440.0Hz"
	 */
	public String toString() { return asHertz() + "Hz"; }
}
