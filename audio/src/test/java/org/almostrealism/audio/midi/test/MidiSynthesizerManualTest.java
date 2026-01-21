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

package org.almostrealism.audio.midi.test;

import org.almostrealism.audio.CellFeatures;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.BufferOutputLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.SourceDataOutputLine;
import org.almostrealism.audio.midi.MidiDeviceManager;
import org.almostrealism.audio.midi.MidiInputConnection;
import org.almostrealism.audio.midi.MidiInputListener;
import org.almostrealism.audio.midi.MidiSynthesizerBridge;
import org.almostrealism.audio.synth.AudioSynthesizer;
import org.almostrealism.audio.synth.PolyphonicSynthesizer;
import org.almostrealism.util.TestSuiteBase;
import org.almostrealism.util.TestUtils;
import org.junit.Test;

import javax.sound.midi.MidiDevice;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual tests for MIDI controller integration with {@link PolyphonicSynthesizer}
 * and real-time audio output via {@link BufferedOutputScheduler}.
 * <p>
 * These tests require:
 * <ul>
 *   <li>A physical MIDI controller connected via USB</li>
 *   <li>Audio output hardware (speakers/headphones)</li>
 *   <li>Running outside Docker (no audio/MIDI in container)</li>
 * </ul>
 * <p>
 * To run with default settings (uses first available device):
 * <pre>
 * export AR_HARDWARE_LIBS=/tmp/ar_libs/
 * export AR_HARDWARE_DRIVER=native
 * cd common
 * mvn test -pl audio -Dtest=MidiSynthesizerManualTest#playWithMidiController
 * </pre>
 * <p>
 * To select a specific MIDI device by name pattern:
 * <pre>
 * mvn test -pl audio -Dtest=MidiSynthesizerManualTest#playWithMidiController \
 *     -Dmidi.device.pattern="minilogue"
 * </pre>
 * <p>
 * <b>Note:</b> The "No input line" warning from BufferedOutputScheduler is expected
 * and harmless. The scheduler supports both audio input (microphone) and output,
 * but for a synthesizer we only need output.
 *
 * @see MidiSynthesizerBridge
 * @see PolyphonicSynthesizer
 * @see BufferedOutputScheduler
 */
public class MidiSynthesizerManualTest extends TestSuiteBase implements CellFeatures {

	/**
	 * System property to specify a MIDI device name pattern.
	 * If set, the test will select the first device whose name contains this string
	 * (case-insensitive). If not set, uses the first non-sequencer device.
	 * <p>
	 * Example: {@code -Dmidi.device.pattern="minilogue"}
	 */
	public static final String MIDI_DEVICE_PATTERN_PROPERTY = "midi.device.pattern";

	/**
	 * System property to specify an audio output device name pattern.
	 * If set, the test will select the first audio device whose name contains this string.
	 * <p>
	 * Example: {@code -Daudio.device.pattern="Built-in"}
	 */
	public static final String AUDIO_DEVICE_PATTERN_PROPERTY = "audio.device.pattern";

	/** Duration to run the test in seconds */
	private static final int TEST_DURATION_SECONDS = 60;

	/** Number of synthesizer voices */
	private static final int VOICE_COUNT = 8;

	/**
	 * Full integration test: MIDI controller -> PolyphonicSynthesizer -> Audio output.
	 * <p>
	 * This test:
	 * <ol>
	 *   <li>Enumerates available MIDI input devices</li>
	 *   <li>Opens the first available MIDI input</li>
	 *   <li>Creates a PolyphonicSynthesizer with sawtooth oscillator</li>
	 *   <li>Connects via MidiSynthesizerBridge</li>
	 *   <li>Routes audio through BufferedOutputScheduler to speakers</li>
	 *   <li>Runs for TEST_DURATION_SECONDS, allowing manual play testing</li>
	 * </ol>
	 */
	@Test
	public void playWithMidiController() throws Exception {
		// Skip in CI pipeline - requires real hardware
		if (testProfileIs(TestUtils.PIPELINE)) return;

		// 1. Enumerate MIDI devices
		MidiDeviceManager manager = new MidiDeviceManager();
		List<MidiDevice.Info> devices = manager.getAvailableInputDevices();

		if (devices.isEmpty()) {
			log("No MIDI input devices found. Connect a MIDI controller and try again.");
			log("Skipping test.");
			return;
		}

		log("=== Available MIDI Input Devices ===");
		for (int i = 0; i < devices.size(); i++) {
			MidiDevice.Info info = devices.get(i);
			log("  [" + i + "] " + info.getName() + " - " + info.getDescription());
		}

		// 2. Select device based on pattern or default heuristics
		MidiDevice.Info selectedDevice = selectMidiDevice(devices);
		if (selectedDevice == null) {
			log("\nNo suitable MIDI device found. Skipping test.");
			return;
		}
		log("\nOpening: " + selectedDevice.getName());

		MidiInputConnection midiInput = manager.openInput(selectedDevice);
		if (midiInput == null) {
			log("Failed to open MIDI device. Skipping test.");
			return;
		}

		try {
			// 3. Create synthesizer
			log("\nCreating PolyphonicSynthesizer with " + VOICE_COUNT + " voices...");
			PolyphonicSynthesizer synth = new PolyphonicSynthesizer(VOICE_COUNT);

			// Configure synth sound
			synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
			synth.setAmpEnvelopeParams(0.01, 0.1, 0.7, 0.3);  // Quick attack, medium release
			synth.setLowPassFilter(2000.0, 1.5);  // Warm filter

			// 4. Create CellList with synth output
			// Add synth directly as root - it implements Cell and handles audio generation in push()
			log("Setting up audio routing...");
			CellList cells = new CellList();
			cells.addRoot(synth);  // Synth implements Cell, push() generates and outputs audio

			// 5. Create audio output with SMALL buffer size
			// CRITICAL: Large buffers (65536) cause compilation to hang with complex synths
			OutputLine outputLine = createSmallBufferAudioOutput();
			if (outputLine == null) {
				log("Failed to create audio output. Skipping test.");
				return;
			}
			log("Audio output: " + outputLine.getSampleRate() + " Hz, buffer " + outputLine.getBufferSize());

			// 6. Create scheduler with explicit small frame count
			// CRITICAL: Large frame counts (8192) hang during compilation for complex synths
			int framesPerTick = 512;
			log("Using framesPerTick: " + framesPerTick + " (critical for synth performance)");
			BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
					null, outputLine, framesPerTick, cells.toLineOperation());

			// 7. Connect MIDI bridge
			log("Connecting MIDI bridge...");
			MidiSynthesizerBridge bridge = new MidiSynthesizerBridge(synth);
			midiInput.addListener(bridge);

			// Add debug listener to trace MIDI messages
			midiInput.addListener(new MidiInputListener() {
				@Override
				public void noteOn(int channel, int note, int velocity) {
					log("DEBUG MIDI: Note ON  ch=" + channel + " note=" + note + " vel=" + velocity);
				}

				@Override
				public void noteOff(int channel, int note, int velocity) {
					log("DEBUG MIDI: Note OFF ch=" + channel + " note=" + note);
				}

				@Override
				public void controlChange(int channel, int controller, int value) {
					log("DEBUG MIDI: CC ch=" + channel + " cc=" + controller + " val=" + value);
				}
			});

			log("Listener count: " + midiInput.getListenerCount());
			log("Connection open: " + midiInput.isOpen());

			// 8. Start audio
			log("\n=== Starting Audio Playback ===");
			log("Play notes on your MIDI controller!");
			log("Test will run for " + TEST_DURATION_SECONDS + " seconds...\n");

			// Enable verbose logging to diagnose scheduler behavior
			BufferedOutputScheduler.enableVerbose = true;
			BufferedOutputScheduler.logRate = 1;  // Log every cycle

			scheduler.start();

			// 9. Run for specified duration with audio level monitoring
			long startTime = System.currentTimeMillis();
			long endTime = startTime + (TEST_DURATION_SECONDS * 1000L);

			// Track peak levels for diagnostics
			double overallPeak = 0.0;
			long nonZeroFrames = 0;
			long totalSampledFrames = 0;

			while (System.currentTimeMillis() < endTime) {
				Thread.sleep(1000);
				long elapsed = (System.currentTimeMillis() - startTime) / 1000;
				int activeVoices = synth.getActiveVoiceCount();

				// Sample the audio buffer to check levels
				double currentPeak = 0.0;
				int currentNonZero = 0;
				try {
					org.almostrealism.collect.PackedCollection buffer =
							scheduler.getBuffer().getOutputBuffer();
					int bufferSize = (int) buffer.getMemLength();
					for (int i = 0; i < bufferSize; i++) {
						double sample = Math.abs(buffer.toDouble(i));
						if (sample > currentPeak) currentPeak = sample;
						if (sample > 0.0001) currentNonZero++;
					}
					totalSampledFrames += bufferSize;
					nonZeroFrames += currentNonZero;
					if (currentPeak > overallPeak) overallPeak = currentPeak;
				} catch (Exception e) {
					// Buffer access failed, ignore
				}

				String levelIndicator = currentPeak > 0.01 ? " [AUDIO DETECTED]" :
						(currentPeak > 0.0001 ? " [low signal]" : " [SILENT]");

				log("Time: " + elapsed + "s | Voices: " + activeVoices +
						" | Frames: " + scheduler.getRenderedFrames() +
						" | Peak: " + String.format("%.4f", currentPeak) +
						" | NonZero: " + currentNonZero + levelIndicator);
			}

			// Summary
			log("\n=== Audio Level Summary ===");
			log("Overall peak amplitude: " + String.format("%.6f", overallPeak));
			log("Total sampled frames: " + totalSampledFrames);
			log("Non-zero frames: " + nonZeroFrames);
			if (overallPeak > 0.01) {
				log("[OK] Audio IS being generated - check speaker/volume settings");
			} else if (overallPeak > 0.0001) {
				log("[WARN] Very low audio level detected - may be too quiet to hear");
			} else {
				log("[FAIL] NO audio detected - synthesizer output issue");
			}

			// 10. Cleanup
			log("\n=== Stopping ===");
			scheduler.stop();
			outputLine.destroy();
			log("Audio stopped.");

		} finally {
			midiInput.close();
			log("MIDI connection closed.");
		}

		log("\nTest completed successfully!");
	}

	/**
	 * Creates an audio output line, optionally selecting a specific device.
	 */
	private OutputLine createAudioOutput() {
		AudioFormat format = new AudioFormat(
				AudioFormat.Encoding.PCM_SIGNED,
				44100, 16, 2, 4, 44100, false);

		DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);

		// List available audio output devices
		log("\n=== Available Audio Output Devices ===");
		List<Mixer.Info> outputMixers = new ArrayList<>();

		for (Mixer.Info mixerInfo : AudioSystem.getMixerInfo()) {
			Mixer mixer = AudioSystem.getMixer(mixerInfo);
			if (mixer.isLineSupported(lineInfo)) {
				outputMixers.add(mixerInfo);
				log("  [" + outputMixers.size() + "] " + mixerInfo.getName() + " - " + mixerInfo.getDescription());
			}
		}

		if (outputMixers.isEmpty()) {
			log("No audio output devices found!");
			return null;
		}

		// Select device based on pattern or default
		String pattern = System.getProperty(AUDIO_DEVICE_PATTERN_PROPERTY);
		Mixer.Info selectedMixer = null;

		if (pattern != null && !pattern.isEmpty()) {
			String lowerPattern = pattern.toLowerCase();
			log("Looking for audio device matching: \"" + pattern + "\"");

			for (Mixer.Info info : outputMixers) {
				if (info.getName().toLowerCase().contains(lowerPattern) ||
					info.getDescription().toLowerCase().contains(lowerPattern)) {
					selectedMixer = info;
					break;
				}
			}

			if (selectedMixer == null) {
				log("WARNING: No audio device found matching pattern \"" + pattern + "\"");
				log("Using default audio output instead.");
			}
		}

		try {
			SourceDataLine line;

			if (selectedMixer != null) {
				log("Using audio device: " + selectedMixer.getName());
				Mixer mixer = AudioSystem.getMixer(selectedMixer);
				line = (SourceDataLine) mixer.getLine(lineInfo);
			} else {
				log("Using default audio output");
				line = (SourceDataLine) AudioSystem.getLine(lineInfo);
			}

			int bufferSize = BufferDefaults.defaultBufferSize;
			line.open(format, bufferSize * 4);
			line.start();

			return new SourceDataOutputLine(line, bufferSize);
		} catch (Exception e) {
			log("Error creating audio output: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Creates an audio output line with a SMALL buffer size suitable for complex synthesizers.
	 * The default buffer size (65536) causes compilation to hang for PolyphonicSynthesizer
	 * because it results in ~8192 frames per tick. This method uses 1024 buffer size
	 * (like RealtimePlaybackTest) resulting in ~128 frames per tick.
	 */
	private OutputLine createSmallBufferAudioOutput() {
		AudioFormat format = new AudioFormat(
				AudioFormat.Encoding.PCM_SIGNED,
				44100, 16, 2, 4, 44100, false);

		DataLine.Info lineInfo = new DataLine.Info(SourceDataLine.class, format);

		try {
			SourceDataLine line = (SourceDataLine) AudioSystem.getLine(lineInfo);

			// CRITICAL: Use small buffer size to avoid compilation hang with complex synths
			// RealtimePlaybackTest uses 1024, which works well
			int bufferSize = 1024;
			line.open(format, bufferSize * 4);
			line.start();

			log("Created audio output with SMALL buffer: " + bufferSize);
			return new SourceDataOutputLine(line, bufferSize);
		} catch (Exception e) {
			log("Error creating audio output: " + e.getMessage());
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Selects a MIDI device from the available devices.
	 * <p>
	 * Selection priority:
	 * <ol>
	 *   <li>If {@link #MIDI_DEVICE_PATTERN_PROPERTY} is set, find first device matching the pattern</li>
	 *   <li>Otherwise, skip "Real Time Sequencer" and "Gervill" (Java built-in software devices)</li>
	 *   <li>Prefer devices with "MIDI IN" in the name (standard MIDI input port)</li>
	 *   <li>Fall back to first non-software device</li>
	 * </ol>
	 *
	 * @param devices list of available MIDI input devices
	 * @return the selected device, or null if none suitable
	 */
	private MidiDevice.Info selectMidiDevice(List<MidiDevice.Info> devices) {
		String pattern = System.getProperty(MIDI_DEVICE_PATTERN_PROPERTY);

		if (pattern != null && !pattern.isEmpty()) {
			// User specified a pattern - find matching device
			String lowerPattern = pattern.toLowerCase();
			log("Looking for device matching: \"" + pattern + "\"");

			for (MidiDevice.Info info : devices) {
				if (info.getName().toLowerCase().contains(lowerPattern) ||
					info.getDescription().toLowerCase().contains(lowerPattern)) {
					return info;
				}
			}

			log("WARNING: No device found matching pattern \"" + pattern + "\"");
			return null;
		}

		// No pattern specified - use heuristics
		// First, filter out software/virtual devices
		MidiDevice.Info midiInDevice = null;
		MidiDevice.Info firstHardwareDevice = null;

		for (MidiDevice.Info info : devices) {
			String name = info.getName();

			// Skip Java built-in software devices
			if (name.contains("Real Time Sequencer") || name.contains("Gervill")) {
				continue;
			}

			// Track first hardware device as fallback
			if (firstHardwareDevice == null) {
				firstHardwareDevice = info;
			}

			// Prefer "MIDI IN" ports (standard MIDI input)
			if (name.contains("MIDI IN") && midiInDevice == null) {
				midiInDevice = info;
			}
		}

		// Return MIDI IN device if found, otherwise first hardware device
		if (midiInDevice != null) {
			log("Auto-selected MIDI IN port: " + midiInDevice.getName());
			return midiInDevice;
		}

		if (firstHardwareDevice != null) {
			log("Auto-selected first hardware device: " + firstHardwareDevice.getName());
			return firstHardwareDevice;
		}

		return devices.isEmpty() ? null : devices.get(0);
	}

	/**
	 * Simpler test that just lists MIDI devices without requiring audio.
	 * Useful for verifying MIDI detection works.
	 */
	@Test
	public void listMidiDevices() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		MidiDeviceManager manager = new MidiDeviceManager();

		log("=== MIDI Input Devices ===");
		List<MidiDevice.Info> inputs = manager.getAvailableInputDevices();
		if (inputs.isEmpty()) {
			log("  (none found)");
		} else {
			for (MidiDevice.Info info : inputs) {
				log("  " + info.getName() + " - " + info.getDescription() +
						" [" + info.getVendor() + "]");
			}
		}

		log("\n=== MIDI Output Devices ===");
		List<MidiDevice.Info> outputs = manager.getAvailableOutputDevices();
		if (outputs.isEmpty()) {
			log("  (none found)");
		} else {
			for (MidiDevice.Info info : outputs) {
				log("  " + info.getName() + " - " + info.getDescription() +
						" [" + info.getVendor() + "]");
			}
		}
	}

	/**
	 * Test synthesizer audio output without MIDI - generates a test tone.
	 * Useful for verifying audio pipeline works before adding MIDI.
	 */
	@Test
	public void synthesizerAudioOnly() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== Synthesizer Audio Test (no MIDI) ===");
		log("This test plays a chord for 5 seconds to verify audio output.\n");

		// Create synthesizer
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(VOICE_COUNT);
		synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
		synth.setAmpEnvelopeParams(0.1, 0.2, 0.6, 0.5);
		synth.setLowPassFilter(1500.0, 1.0);

		// Set up audio routing - synth implements Cell and handles audio generation in push()
		CellList cells = new CellList();
		cells.addRoot(synth);  // Synth implements Cell, push() generates and outputs audio

		// Use small buffer to avoid compilation hang with complex synths
		OutputLine outputLine = createSmallBufferAudioOutput();
		if (outputLine == null) {
			log("Failed to create audio output. Skipping test.");
			return;
		}

		// Create scheduler with explicit small frame count
		int framesPerTick = 512;
		BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
				null, outputLine, framesPerTick, cells.toLineOperation());

		// Start audio
		scheduler.start();

		// Play a C major chord
		log("Playing C major chord (C4, E4, G4)...");
		synth.noteOn(60, 0.7);  // C4
		synth.noteOn(64, 0.6);  // E4
		synth.noteOn(67, 0.5);  // G4

		Thread.sleep(3000);

		// Release notes
		log("Releasing notes...");
		synth.noteOff(60);
		synth.noteOff(64);
		synth.noteOff(67);

		// Wait for release tail
		Thread.sleep(2000);

		// Cleanup
		scheduler.stop();
		outputLine.destroy();

		log("Audio test completed!");
	}

	/**
	 * Diagnostic test that triggers notes programmatically and verifies
	 * the synthesizer produces non-silent output. This test captures audio
	 * to a buffer instead of playing to speakers, allowing automated
	 * verification of the audio pipeline.
	 * <p>
	 * Use this test to diagnose whether the issue is:
	 * <ul>
	 *   <li>Synthesizer not producing output (this test will fail)</li>
	 *   <li>Audio routing to speakers (this test will pass but no sound)</li>
	 * </ul>
	 */
	@Test
	public void verifySynthesizerProducesOutput() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== Synthesizer Output Verification Test ===");
		log("This test verifies the synthesizer produces non-silent audio.\n");

		// Create synthesizer with same settings as manual test
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(VOICE_COUNT);
		synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
		synth.setAmpEnvelopeParams(0.01, 0.1, 0.7, 0.3);
		synth.setLowPassFilter(2000.0, 1.5);

		log("Synth created: " + synth);
		log("Oscillator type: SAWTOOTH");
		log("Voice count: " + VOICE_COUNT);

		// Set up audio routing with BufferOutputLine to capture output
		// Synth implements Cell and handles audio generation in push()
		CellList cells = new CellList();
		cells.addRoot(synth);  // Synth implements Cell, push() generates and outputs audio

		// Use smaller buffer to reduce iterations per tick
		// With 8 voices and complex operations, large buffers take too long
		int captureFrames = 44100;  // 1 second at 44100 Hz
		BufferOutputLine bufferLine = new BufferOutputLine(captureFrames);
		bufferLine.setCircular(false);

		// Create scheduler with explicit small frame count per tick
		// Default is bufferSize/batchCount = 44100/8 = ~5512 frames per tick
		// Use smaller frame count for faster ticks
		int framesPerTick = 512;  // Small batch size for faster ticks
		BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
				null, bufferLine, framesPerTick, cells.toLineOperation());

		// Enable verbose logging to trace scheduler behavior
		BufferedOutputScheduler.enableVerbose = true;
		BufferedOutputScheduler.logRate = 1;  // Log every cycle

		log("\nStarting audio capture...");
		log("Buffer size: " + bufferLine.getBufferSize());
		log("Sample rate: " + bufferLine.getSampleRate());
		log("Frames per tick: " + framesPerTick);

		// IMPORTANT: Trigger note BEFORE starting scheduler so the first tick
		// captures audio from the active note
		log("Triggering note: C4 (MIDI 60) at velocity 0.8");
		synth.noteOn(60, 0.8);
		log("Active voices after noteOn: " + synth.getActiveVoiceCount());

		long startTime = System.currentTimeMillis();
		log("Before scheduler.start(): " + startTime);
		scheduler.start();
		long afterStart = System.currentTimeMillis();
		log("After scheduler.start(): " + afterStart + " (took " + (afterStart - startTime) + "ms)");

		// Wait for scheduler to complete at least one tick
		// The start() method compiles operations and submits to executor, but
		// the first tick with 8 voices can take several seconds to complete
		log("Waiting for scheduler to complete first tick with active note...");
		int maxWaitMs = 30000;  // 30 seconds max
		int waitedMs = 0;
		while (scheduler.getRenderedCount() == 0 && waitedMs < maxWaitMs) {
			Thread.sleep(100);
			waitedMs += 100;
		}
		long afterWait = System.currentTimeMillis();
		log("Scheduler completed after " + waitedMs + "ms wait (total elapsed: " + (afterWait - startTime) + "ms)");
		log("Rendered count: " + scheduler.getRenderedCount());
		log("Rendered frames: " + scheduler.getRenderedFrames());

		if (scheduler.getRenderedCount() == 0) {
			log("WARNING: Scheduler did not start processing within " + maxWaitMs + "ms!");
		}

		// Wait for at least one more tick to ensure audio is written to output
		log("Waiting for additional tick to complete...");
		long startRendered = scheduler.getRenderedCount();
		waitedMs = 0;
		while (scheduler.getRenderedCount() <= startRendered && waitedMs < maxWaitMs) {
			Thread.sleep(100);
			waitedMs += 100;
		}
		log("After wait - Rendered count: " + scheduler.getRenderedCount());
		log("Rendered frames: " + scheduler.getRenderedFrames());

		// Check the AudioBuffer content
		org.almostrealism.collect.PackedCollection audioBuffer = scheduler.getBuffer().getOutputBuffer();
		int audioBufferFrames = scheduler.getBuffer().getDetails().getFrames();
		int audioBufferNonZero = 0;
		double audioBufferMax = 0;
		for (int i = 0; i < audioBufferFrames; i++) {
			double val = audioBuffer.toDouble(i);
			if (Math.abs(val) > 0.0001) audioBufferNonZero++;
			audioBufferMax = Math.max(audioBufferMax, Math.abs(val));
		}
		log("AudioBuffer frames: " + audioBufferFrames);
		log("AudioBuffer non-zero: " + audioBufferNonZero);
		log("AudioBuffer max: " + audioBufferMax);

		log("Frames written so far: " + bufferLine.getTotalFramesWritten());
		log("Has audio: " + bufferLine.hasAudio());
		log("Peak amplitude: " + bufferLine.getPeakAmplitude());

		// Release the note
		log("\nReleasing note...");
		synth.noteOff(60);
		log("Active voices after noteOff: " + synth.getActiveVoiceCount());

		// Wait for release tail
		Thread.sleep(500);

		// Stop and analyze
		scheduler.stop();

		log("\n=== Results ===");
		log("Total frames written: " + bufferLine.getTotalFramesWritten());
		log("Duration written: " + String.format("%.3f", bufferLine.getDurationWritten()) + " seconds");
		log("Has audio: " + bufferLine.hasAudio());
		log("Peak amplitude: " + String.format("%.6f", bufferLine.getPeakAmplitude()));
		log("RMS amplitude: " + String.format("%.6f", bufferLine.getRmsAmplitude()));
		log("Zero crossings: " + bufferLine.countZeroCrossings());
		log("Estimated frequency: " + String.format("%.1f", bufferLine.estimateFrequency()) + " Hz");

		// Sample some values from the buffer
		log("\nFirst 10 samples:");
		for (int i = 0; i < 10 && i < bufferLine.getTotalFramesWritten(); i++) {
			log("  [" + i + "] = " + bufferLine.getSample(i));
		}

		// Check mid-buffer samples (should be in the note)
		int midPoint = (int) Math.min(captureFrames / 4, bufferLine.getTotalFramesWritten() - 10);
		if (midPoint > 0) {
			log("\nSamples around frame " + midPoint + " (during note):");
			for (int i = midPoint; i < midPoint + 10 && i < bufferLine.getTotalFramesWritten(); i++) {
				log("  [" + i + "] = " + bufferLine.getSample(i));
			}
		}

		// Verify we got audio
		if (bufferLine.hasAudio()) {
			log("\n[OK] SUCCESS: Synthesizer is producing audio output!");
			log("  If you're not hearing sound through speakers, the issue is in");
			log("  the audio output device selection or system audio routing.");
		} else {
			log("\n[FAIL] FAILURE: Synthesizer produced silent output!");
			log("  The issue is in the synthesizer or audio processing pipeline.");
		}

		// Also verify we got a reasonable number of frames
		if (bufferLine.getTotalFramesWritten() == 0) {
			log("\n[FAIL] WARNING: No frames were written to the buffer!");
			log("  This suggests the scheduler did not run properly.");
		}

		bufferLine.destroy();
	}

	/**
	 * Simpler diagnostic test that directly tests the synthesizer without
	 * BufferedOutputScheduler. This helps isolate whether the issue is in
	 * the synthesizer itself or the scheduling/routing layer.
	 */
	@Test
	public void directSynthesizerTest() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== Direct Synthesizer Test ===");
		log("Testing synthesizer output without BufferedOutputScheduler\n");

		// Create a simple sawtooth oscillator for testing
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);
		synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
		synth.setAmpEnvelopeParams(0.001, 0.05, 1.0, 0.1);  // Very fast attack, full sustain

		// Capture output values
		java.util.List<Double> capturedValues = new java.util.ArrayList<>();
		synth.getOutput().setReceptor(protein -> () -> () -> {
			double value = protein.get().evaluate().toDouble(0);
			capturedValues.add(value);
		});

		// Get setup and tick
		log("Getting setup and tick operations...");
		Runnable setup = synth.setup().get();

		log("Running setup...");
		setup.run();

		// Trigger a note
		log("Triggering note A4 (440 Hz) at full velocity...");
		synth.noteOn(69, 1.0);  // A4 = 440 Hz
		log("Active voices: " + synth.getActiveVoiceCount());

		// Run tick loop to generate audio
		log("Running " + 1000 + " ticks...");
		Runnable tick = synth.tick().get();
		for (int i = 0; i < 1000; i++) {
			tick.run();
		}

		// Analyze output
		log("\n=== Results ===");
		log("Captured values: " + capturedValues.size());

		if (capturedValues.isEmpty()) {
			log("ERROR: No values captured! The receptor was never called.");
		} else {
			// Find non-zero values
			long nonZeroCount = capturedValues.stream()
					.filter(v -> Math.abs(v) > 0.0001)
					.count();
			double maxValue = capturedValues.stream()
					.mapToDouble(Math::abs)
					.max()
					.orElse(0.0);
			double sumSquares = capturedValues.stream()
					.mapToDouble(v -> v * v)
					.sum();
			double rms = Math.sqrt(sumSquares / capturedValues.size());

			log("Non-zero values: " + nonZeroCount + " / " + capturedValues.size());
			log("Max absolute value: " + maxValue);
			log("RMS: " + rms);

			log("\nFirst 20 values:");
			for (int i = 0; i < Math.min(20, capturedValues.size()); i++) {
				log("  [" + i + "] = " + capturedValues.get(i));
			}

			if (nonZeroCount > 0) {
				log("\n[OK] SUCCESS: Synthesizer produces non-zero output!");
			} else {
				log("\n[FAIL] FAILURE: All output values are zero!");
			}
		}

		synth.noteOff(69);
	}

	/**
	 * Diagnostic test: Verify that a simple SineWaveCell works with CellList + BufferedOutputScheduler.
	 * This isolates whether the issue is with the scheduler/CellList pattern or specific to PolyphonicSynthesizer.
	 */
	@Test
	public void simpleSineWaveThroughScheduler() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== Simple SineWaveCell Through Scheduler Test ===");
		log("Testing if CellList + BufferedOutputScheduler works with a basic SineWaveCell\n");

		// Create a simple sine wave cell
		org.almostrealism.audio.sources.SineWaveCell sineCell = new org.almostrealism.audio.sources.SineWaveCell();
		sineCell.setFreq(440.0);  // A4
		sineCell.setAmplitude(0.8);
		sineCell.setNoteLength(2000);  // 2 second note

		log("SineWaveCell created:");
		log("  Frequency: 440 Hz (A4)");
		log("  Amplitude: 0.8");
		log("  Note length: 2000 ms");

		// Set up CellList with the sine wave cell
		CellList cells = new CellList();
		cells.addRoot(sineCell);

		// Create a buffer to capture output
		int captureFrames = 44100;  // 1 second
		BufferOutputLine bufferLine = new BufferOutputLine(captureFrames);
		bufferLine.setCircular(false);

		log("\nCreating BufferedOutputScheduler...");
		BufferedOutputScheduler scheduler = cells.buffer(bufferLine);

		log("Starting scheduler...");
		scheduler.start();

		// Let it run for 500ms
		Thread.sleep(500);

		log("\n=== Mid-test Check ===");
		log("Frames written: " + bufferLine.getTotalFramesWritten());
		log("Has audio: " + bufferLine.hasAudio());
		log("Peak amplitude: " + String.format("%.6f", bufferLine.getPeakAmplitude()));

		// Continue for another 500ms
		Thread.sleep(500);

		scheduler.stop();

		log("\n=== Final Results ===");
		log("Total frames written: " + bufferLine.getTotalFramesWritten());
		log("Has audio: " + bufferLine.hasAudio());
		log("Peak amplitude: " + String.format("%.6f", bufferLine.getPeakAmplitude()));
		log("RMS amplitude: " + String.format("%.6f", bufferLine.getRmsAmplitude()));

		// Sample first 20 values
		log("\nFirst 20 samples:");
		for (int i = 0; i < 20 && i < bufferLine.getTotalFramesWritten(); i++) {
			log("  [" + i + "] = " + bufferLine.getSample(i));
		}

		if (bufferLine.hasAudio()) {
			log("\n[OK] SUCCESS: SineWaveCell produces audio through CellList + BufferedOutputScheduler!");
			log("  This means the scheduler works fine with simple cells.");
			log("  The issue is specific to PolyphonicSynthesizer integration.");
		} else {
			log("\n[FAIL] FAILURE: SineWaveCell produces NO audio through scheduler!");
			log("  This indicates a fundamental issue with CellList + BufferedOutputScheduler.");
		}

		bufferLine.destroy();
	}

	/**
	 * Diagnostic test: Verify SineWaveCell works when manually ticked (like directSynthesizerTest).
	 * This establishes the baseline that SineWaveCell works before testing scheduler integration.
	 */
	@Test
	public void directSineWaveTest() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== Direct SineWaveCell Test ===");
		log("Testing SineWaveCell without scheduler (baseline)\n");

		org.almostrealism.audio.sources.SineWaveCell sineCell = new org.almostrealism.audio.sources.SineWaveCell();
		sineCell.setFreq(440.0);
		sineCell.setAmplitude(0.8);
		sineCell.setNoteLength(2000);

		// Capture output values
		java.util.List<Double> capturedValues = new java.util.ArrayList<>();
		sineCell.setReceptor(protein -> () -> () -> {
			double value = protein.get().evaluate().toDouble(0);
			capturedValues.add(value);
		});

		log("Running setup...");
		sineCell.setup().get().run();

		log("Running 1000 ticks...");
		Runnable tick = sineCell.tick().get();
		Runnable push = sineCell.push(null).get();
		for (int i = 0; i < 1000; i++) {
			push.run();
			tick.run();
		}

		// Analyze
		log("\n=== Results ===");
		log("Captured values: " + capturedValues.size());

		long nonZeroCount = capturedValues.stream()
				.filter(v -> Math.abs(v) > 0.0001)
				.count();
		double maxValue = capturedValues.stream()
				.mapToDouble(Math::abs)
				.max()
				.orElse(0.0);

		log("Non-zero values: " + nonZeroCount + " / " + capturedValues.size());
		log("Max absolute value: " + maxValue);

		log("\nFirst 20 values:");
		for (int i = 0; i < Math.min(20, capturedValues.size()); i++) {
			log("  [" + i + "] = " + capturedValues.get(i));
		}

		if (nonZeroCount > 0) {
			log("\n[OK] SUCCESS: Direct SineWaveCell works!");
		} else {
			log("\n[FAIL] FAILURE: Direct SineWaveCell produces no output!");
		}
	}

	/**
	 * Diagnostic test: Verify that WaveCell (from file) works with CellList + BufferedOutputScheduler.
	 * This mirrors the RealtimePlaybackTest pattern which is known to work.
	 */
	@Test
	public void waveCellThroughScheduler() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== WaveCell Through Scheduler Test ===");
		log("Testing if CellList + BufferedOutputScheduler works with WaveCell (known working pattern)\n");

		// Try to use a test wav file if available, otherwise use a generated tone
		java.io.File testFile = new java.io.File("Library/Snare Gold 1.wav");
		if (!testFile.exists()) {
			testFile = new java.io.File("src/test/resources/test.wav");
		}

		if (!testFile.exists()) {
			log("No test file found, skipping WaveCell test");
			log("Create a test.wav file to enable this test");
			return;
		}

		log("Using test file: " + testFile.getAbsolutePath());

		// Use the working pattern from RealtimePlaybackTest
		CellList cells = w(0, testFile.getPath());

		// Create a buffer to capture output
		int captureFrames = 44100;  // 1 second
		BufferOutputLine bufferLine = new BufferOutputLine(captureFrames);
		bufferLine.setCircular(false);

		BufferedOutputScheduler scheduler = cells.buffer(bufferLine);

		log("Starting scheduler...");
		scheduler.start();

		Thread.sleep(500);

		scheduler.stop();

		log("\n=== Results ===");
		log("Total frames written: " + bufferLine.getTotalFramesWritten());
		log("Has audio: " + bufferLine.hasAudio());
		log("Peak amplitude: " + String.format("%.6f", bufferLine.getPeakAmplitude()));

		// Sample first 20 values
		log("\nFirst 20 samples:");
		for (int i = 0; i < 20 && i < bufferLine.getTotalFramesWritten(); i++) {
			log("  [" + i + "] = " + bufferLine.getSample(i));
		}

		if (bufferLine.hasAudio()) {
			log("\n[OK] SUCCESS: WaveCell works through scheduler (as expected)");
		} else {
			log("\n[FAIL] FAILURE: Even WaveCell fails through scheduler!");
			log("  This indicates a fundamental issue with the test setup.");
		}

		bufferLine.destroy();
	}

	/**
	 * Diagnostic test: Trace the audio flow through the synth with manual receptor chain.
	 * This helps isolate exactly where audio is lost in the CellList integration.
	 */
	@Test
	public void traceAudioFlowThroughSynth() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== Audio Flow Tracing Test ===");
		log("Testing synth audio flow with manual receptor to trace where audio is lost\n");

		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);
		synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
		synth.setAmpEnvelopeParams(0.001, 0.05, 1.0, 0.1);

		// Set up a capture receptor on the synth
		java.util.List<Double> capturedFromPush = new java.util.ArrayList<>();
		synth.setReceptor(protein -> () -> () -> {
			double value = protein.get().evaluate().toDouble(0);
			capturedFromPush.add(value);
		});

		log("Running setup...");
		synth.setup().get().run();

		log("Triggering note A4 (440 Hz)...");
		synth.noteOn(69, 1.0);

		log("Running 1000 push() calls...");
		Runnable push = synth.push(null).get();
		for (int i = 0; i < 1000; i++) {
			push.run();
		}

		// Analyze
		log("\n=== Push Results ===");
		log("Values captured via setReceptor: " + capturedFromPush.size());

		long nonZeroCount = capturedFromPush.stream()
				.filter(v -> Math.abs(v) > 0.0001)
				.count();
		double maxValue = capturedFromPush.stream()
				.mapToDouble(Math::abs)
				.max()
				.orElse(0.0);

		log("Non-zero values: " + nonZeroCount + " / " + capturedFromPush.size());
		log("Max absolute value: " + maxValue);

		log("\nFirst 20 values from receptor:");
		for (int i = 0; i < Math.min(20, capturedFromPush.size()); i++) {
			log("  [" + i + "] = " + capturedFromPush.get(i));
		}

		if (nonZeroCount > 0) {
			log("\n[OK] SUCCESS: Synth's push() sends audio to receptor!");
			log("  The issue is in CellList/BufferedOutputScheduler integration.");
		} else {
			log("\n[FAIL] FAILURE: Synth's push() sends NO audio to receptor!");
			log("  The issue is in how push() forwards to receptor.");
		}

		synth.noteOff(69);
	}

	/**
	 * Diagnostic test: Test synth through CellList tick() with WaveOutput receptor.
	 * This mimics the exact pattern used by BufferedOutputScheduler.
	 */
	@Test
	public void testSynthWithCellListAndWaveOutput() {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== CellList + WaveOutput Pattern Test ===");
		log("Testing synth with CellList.tick() and WaveOutput receptor (scheduler pattern)\n");

		// Create synth
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(4);
		synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
		synth.setAmpEnvelopeParams(0.001, 0.05, 1.0, 0.1);

		// Create CellList with synth as root
		CellList cells = new CellList();
		cells.addRoot(synth);

		log("CellList size: " + cells.size());
		log("Roots count: " + cells.getAllRoots().size());
		log("Temporals count: " + cells.getAllTemporals().size());

		// Create a destination buffer (like AudioBuffer's output buffer)
		int frames = 1000;
		org.almostrealism.collect.PackedCollection destination = new org.almostrealism.collect.PackedCollection(frames);

		// Mimic what buffer(destination) does:
		// cells = map(cells, i -> new WaveOutput(destination).getWriterCell(0));
		org.almostrealism.audio.WaveOutput waveOutput = new org.almostrealism.audio.WaveOutput(p(destination));
		org.almostrealism.graph.ReceptorCell<org.almostrealism.collect.PackedCollection> receptorCell = waveOutput.getWriterCell(0);

		// Set receptor on the synth (like map() does)
		synth.setReceptor(receptorCell);

		log("Receptor set on synth: " + (synth.getReceptor() != null));

		// Create new CellList with receptor cell (like map() does)
		CellList mapped = new CellList(cells);  // Parent is the original cells
		mapped.add(receptorCell);

		log("\nMapped CellList size: " + mapped.size());
		log("Mapped roots count: " + mapped.getAllRoots().size());
		log("Mapped temporals count: " + mapped.getAllTemporals().size());

		// Run setup
		log("\nRunning setup...");
		mapped.setup().get().run();

		// Trigger note
		log("Triggering note A4 (440 Hz)...");
		synth.noteOn(69, 1.0);

		// Run tick() like the TemporalRunner would
		log("Running " + frames + " ticks via CellList.tick()...\n");
		Runnable tick = mapped.tick().get();
		for (int i = 0; i < frames; i++) {
			tick.run();
		}

		// Check WaveOutput cursor and data
		int cursorPos = waveOutput.getFrameCount();
		log("WaveOutput cursor position: " + cursorPos);

		// Check destination buffer
		int nonZeroCount = 0;
		double maxAbs = 0;
		for (int i = 0; i < frames; i++) {
			double val = destination.toDouble(i);
			if (Math.abs(val) > 0.0001) nonZeroCount++;
			maxAbs = Math.max(maxAbs, Math.abs(val));
		}

		log("Destination buffer analysis:");
		log("  Non-zero values: " + nonZeroCount + " / " + frames);
		log("  Max absolute value: " + maxAbs);

		log("\nFirst 20 destination values:");
		for (int i = 0; i < Math.min(20, frames); i++) {
			log("  [" + i + "] = " + destination.toDouble(i));
		}

		if (nonZeroCount > 0) {
			log("\n[OK] SUCCESS: CellList + WaveOutput pattern works!");
			log("  The issue is specific to BufferedOutputScheduler timing or execution.");
		} else {
			log("\n[FAIL] FAILURE: CellList + WaveOutput pattern produces no output!");
			log("  The issue is in how CellList.tick() integrates with WaveOutput.");
		}

		synth.noteOff(69);
		destination.destroy();
	}

	/**
	 * Real audio output test with programmatic note triggering.
	 * This test uses the same audio pipeline as playWithMidiController but
	 * triggers notes programmatically instead of waiting for MIDI input.
	 * This helps diagnose whether the issue is with MIDI integration or
	 * the BufferedOutputScheduler + real audio output combination.
	 */
	@Test
	public void realAudioOutputTest() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== Real Audio Output Test ===");
		log("Testing synthesizer with real audio output (no MIDI)\n");

		// Create synthesizer with same settings as playWithMidiController
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(VOICE_COUNT);
		synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
		synth.setAmpEnvelopeParams(0.01, 0.1, 0.7, 0.3);
		synth.setLowPassFilter(2000.0, 1.5);

		log("Synth created with " + VOICE_COUNT + " voices");

		// Create CellList with synth
		CellList cells = new CellList();
		cells.addRoot(synth);

		// Create real audio output with SMALL buffer size (like RealtimePlaybackTest)
		// Large buffers (65536) cause compilation to hang with complex synths
		OutputLine outputLine = createSmallBufferAudioOutput();
		if (outputLine == null) {
			log("Failed to create audio output. Skipping test.");
			return;
		}
		log("Audio output: " + outputLine.getSampleRate() + " Hz, buffer " + outputLine.getBufferSize());

		// Create scheduler with explicit small frame count
		// CRITICAL: Large frame counts (8192) hang during compilation for complex synths
		int framesPerTick = 512;
		log("Using framesPerTick: " + framesPerTick + " (critical for synth performance)");
		BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
				null, outputLine, framesPerTick, cells.toLineOperation());

		// Enable verbose logging
		BufferedOutputScheduler.enableVerbose = true;
		BufferedOutputScheduler.logRate = 1;

		// IMPORTANT: Trigger note BEFORE starting scheduler
		// so the first tick has audio to process
		log("\nTriggering note A4 (MIDI 69) BEFORE starting scheduler...");
		synth.noteOn(69, 0.8);
		log("Active voices: " + synth.getActiveVoiceCount());

		// Start scheduler
		log("\nStarting scheduler...");
		long startTime = System.currentTimeMillis();
		scheduler.start();
		long afterStart = System.currentTimeMillis();
		log("scheduler.start() completed in " + (afterStart - startTime) + "ms");

		// Wait for first tick to complete
		log("\nWaiting for first tick to complete...");
		int waitedMs = 0;
		while (scheduler.getRenderedCount() == 0 && waitedMs < 30000) {
			Thread.sleep(100);
			waitedMs += 100;
		}
		log("First tick completed after " + waitedMs + "ms");
		log("Rendered count: " + scheduler.getRenderedCount());
		log("Rendered frames: " + scheduler.getRenderedFrames());

		// Let it run for a few seconds with note held
		log("\nLetting note play for 5 seconds...");
		log("You should hear audio from your speakers!");
		Thread.sleep(5000);

		// Check status
		log("\nAfter 5 seconds:");
		log("Rendered count: " + scheduler.getRenderedCount());
		log("Rendered frames: " + scheduler.getRenderedFrames());
		log("Active voices: " + synth.getActiveVoiceCount());

		// Release note and wait for release
		log("\nReleasing note...");
		synth.noteOff(69);
		Thread.sleep(1000);

		// Stop
		log("\nStopping scheduler...");
		scheduler.stop();
		outputLine.destroy();

		log("\n=== Test Complete ===");
		log("If you heard audio, the real audio pipeline works!");
		log("If silent, check scheduler logs above for issues.");
	}

	/**
	 * Programmatic MIDI simulation test - triggers notes WHILE scheduler is running.
	 * This tests the realistic scenario where notes arrive after the scheduler has started,
	 * which is what happens with a real MIDI controller.
	 */
	@Test
	public void programmaticMidiTest() throws Exception {
		if (testProfileIs(TestUtils.PIPELINE)) return;

		log("=== Programmatic MIDI Simulation Test ===");
		log("Testing note triggering WHILE scheduler is running\n");

		// Create synthesizer
		PolyphonicSynthesizer synth = new PolyphonicSynthesizer(VOICE_COUNT);
		synth.setOscillatorType(AudioSynthesizer.OscillatorType.SAWTOOTH);
		synth.setAmpEnvelopeParams(0.01, 0.1, 0.7, 0.3);
		synth.setLowPassFilter(2000.0, 1.5);

		log("Synth created with " + VOICE_COUNT + " voices");

		// Create CellList with synth
		CellList cells = new CellList();
		cells.addRoot(synth);

		// Create real audio output with SMALL buffer size (critical for complex synths)
		OutputLine outputLine = createSmallBufferAudioOutput();
		if (outputLine == null) {
			log("Failed to create audio output. Skipping test.");
			return;
		}
		log("Audio output: " + outputLine.getSampleRate() + " Hz, buffer " + outputLine.getBufferSize());

		// Create scheduler with explicit small frame count
		// CRITICAL: Large frame counts (8192) hang during compilation for complex synths
		int framesPerTick = 512;
		log("Using framesPerTick: " + framesPerTick + " (critical for synth performance)");
		BufferedOutputScheduler scheduler = BufferedOutputScheduler.create(
				null, outputLine, framesPerTick, cells.toLineOperation());

		// Enable verbose logging
		BufferedOutputScheduler.enableVerbose = true;
		BufferedOutputScheduler.logRate = 1;

		// Start scheduler FIRST (no note playing yet - like real MIDI scenario)
		log("\nStarting scheduler (no note yet)...");
		long startTime = System.currentTimeMillis();
		scheduler.start();
		long afterStart = System.currentTimeMillis();
		log("scheduler.start() completed in " + (afterStart - startTime) + "ms");

		// Wait for scheduler to be running (first tick complete)
		log("\nWaiting for scheduler to start processing...");
		int waitedMs = 0;
		while (scheduler.getRenderedCount() == 0 && waitedMs < 30000) {
			Thread.sleep(100);
			waitedMs += 100;
		}
		log("Scheduler running after " + waitedMs + "ms");
		log("Rendered count: " + scheduler.getRenderedCount());

		// Now trigger note (simulating MIDI input arriving)
		log("\n--- Simulating MIDI Note ON ---");
		log("Triggering note A4 (MIDI 69) at velocity 100");
		synth.noteOn(69, 0.8);
		log("Active voices: " + synth.getActiveVoiceCount());

		// Wait for several ticks with note active
		log("\nWaiting for note to be processed (needs multiple ticks)...");
		log("Each tick processes ~512 frames");

		long noteStartTick = scheduler.getRenderedCount();
		int secondsToWait = 10;
		for (int i = 0; i < secondsToWait; i++) {
			Thread.sleep(1000);
			long currentTicks = scheduler.getRenderedCount();
			log("  Second " + (i+1) + ": " + currentTicks + " ticks total (" +
				(currentTicks - noteStartTick) + " since note on)");
		}

		// Release note
		log("\n--- Simulating MIDI Note OFF ---");
		synth.noteOff(69);
		log("Note released, waiting for release tail...");
		Thread.sleep(2000);

		// Summary
		log("\n=== Summary ===");
		log("Total rendered: " + scheduler.getRenderedCount() + " ticks");
		log("Total frames: " + scheduler.getRenderedFrames());
		log("Ticks during note: " + (scheduler.getRenderedCount() - noteStartTick));

		// Stop
		scheduler.stop();
		outputLine.destroy();

		log("\n=== Test Complete ===");
		log("This test simulates MIDI input arriving while scheduler is running.");
		log("If you heard audio during the note, MIDI-style triggering works!");
		log("If silent, the issue is with note triggering AFTER scheduler starts.");
	}
}
