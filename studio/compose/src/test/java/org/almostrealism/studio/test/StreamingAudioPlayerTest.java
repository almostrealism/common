/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.studio.test;

import org.almostrealism.studio.BufferedAudioPlayer;
import org.almostrealism.studio.ScheduledOutputAudioPlayer;
import org.almostrealism.studio.StreamingAudioPlayer;
import org.almostrealism.studio.StreamingAudioPlayer.OutputMode;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.DelegatedAudioLine;
import org.almostrealism.audio.line.LineUtilities;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.SharedMemoryAudioLine;
import org.almostrealism.audio.line.SourceDataOutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestProperties;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * Unit tests for {@link StreamingAudioPlayer} mode switching behavior.
 * Verifies that the unified player correctly manages switching between
 * Direct (hardware) and DAW (streaming) modes.
 */
public class StreamingAudioPlayerTest extends TestSuiteBase {
	private static final int PLAYER_COUNT = 4;
	private static final int SAMPLE_RATE = 44100;
	private static final int MAX_FRAMES = 44100 * 180;

	private BufferedAudioPlayer player;
	private DelegatedAudioLine delegatedLine;
	private StreamingAudioPlayer config;

	@Before
	public void setUp() {
		player = new BufferedAudioPlayer(PLAYER_COUNT, SAMPLE_RATE, MAX_FRAMES);
		delegatedLine = new DelegatedAudioLine();
	}

	/**
	 * Helper to create a UnifiedPlayerConfig for testing.
	 * Wraps the player with a ScheduledOutputAudioPlayer.
	 */
	private StreamingAudioPlayer createPlayer(OutputLine recordingLine) {
		ScheduledOutputAudioPlayer scheduledPlayer =
				new ScheduledOutputAudioPlayer(player, delegatedLine, recordingLine);
		return new StreamingAudioPlayer(scheduledPlayer, delegatedLine, recordingLine);
	}

	@Test(timeout = 10_000)
	public void testInitialDirectMode() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDirectMode(); // Must explicitly set mode after construction

		assertEquals(OutputMode.DIRECT, config.getActiveMode());
		assertTrue(config.isDirectMode());
		assertFalse(config.isDawMode());
		assertFalse(config.hasDawConnection());
	}

	@Test(timeout = 10_000)
	public void testInitialDawMode() {
		config = createPlayer(null);
		config.setDawMode(); // Must explicitly set mode after construction

		assertEquals(OutputMode.SHARED, config.getActiveMode());
		assertFalse(config.isDirectMode());
		assertTrue(config.isDawMode());
		assertFalse(config.hasDawConnection());
	}

	@Test(timeout = 10_000)
	public void testSwitchFromDirectToDaw() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode
		assertTrue(config.isDirectMode());

		config.setDawMode();

		assertEquals(OutputMode.SHARED, config.getActiveMode());
		assertTrue(config.isDawMode());
		assertFalse(config.isDirectMode());
	}

	@Test(timeout = 10_000)
	public void testSwitchFromDawToDirect() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDawMode(); // Initialize mode
		assertTrue(config.isDawMode());

		config.setDirectMode();

		assertEquals(OutputMode.DIRECT, config.getActiveMode());
		assertTrue(config.isDirectMode());
		assertFalse(config.isDawMode());
	}

	@Test(timeout = 10_000)
	public void testSetDirectModeIdempotent() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode

		// Multiple calls should not cause issues
		config.setDirectMode();
		config.setDirectMode();
		config.setDirectMode();

		assertEquals(OutputMode.DIRECT, config.getActiveMode());
	}

	@Test(timeout = 10_000)
	public void testSetDawModeIdempotent() {
		config = createPlayer(null);
		config.setDawMode(); // Initialize mode

		// Multiple calls should not cause issues
		config.setDawMode();
		config.setDawMode();
		config.setDawMode();

		assertEquals(OutputMode.SHARED, config.getActiveMode());
	}

	@Test(timeout = 10_000)
	public void testDawConnectionStoredButNotActivatedInDirectMode() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode

		// Capture initial delegate state
		delegatedLine.getDelegate();

		// Simulate DAW connection while in Direct mode
		SharedMemoryAudioLine dawLine = createMockSharedMemoryLine();
		config.setDawConnection(dawLine);

		// DAW connection should be stored
		assertTrue(config.hasDawConnection());
		assertEquals(dawLine, config.getDawConnection());

		// But mode should still be Direct
		assertTrue(config.isDirectMode());

		// And the output delegate should NOT have changed (still Direct output, not DAW)
		// Note: In a real scenario, Direct mode would set a SourceDataOutputLine,
		// but since we haven't called setDirectMode() with getOrCreateDirectOutput(),
		// the delegate remains as it was (could be null initially)
	}

	@Test(timeout = 10_000)
	public void testDawConnectionActivatedWhenSwitchingToDaw() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode

		// Set DAW connection while in Direct mode
		SharedMemoryAudioLine dawLine = createMockSharedMemoryLine();
		config.setDawConnection(dawLine);

		// Now switch to DAW mode
		config.setDawMode();

		// DAW connection should now be active
		assertTrue(config.isDawMode());
		assertEquals(dawLine, delegatedLine.getOutputDelegate());
	}

	@Test(timeout = 10_000)
	public void testDawConnectionImmediatelyActiveInDawMode() {
		config = createPlayer(null);
		config.setDawMode(); // Initialize mode

		// Set DAW connection while already in DAW mode
		SharedMemoryAudioLine dawLine = createMockSharedMemoryLine();
		config.setDawConnection(dawLine);

		// DAW connection should be immediately active
		assertTrue(config.hasDawConnection());
		assertEquals(dawLine, delegatedLine.getOutputDelegate());
	}

	@Test(timeout = 10_000)
	public void testDawConnectionReplacedProperly() {
		config = createPlayer(null);
		config.setDawMode(); // Initialize mode

		// First DAW connection
		SharedMemoryAudioLine firstDaw = createMockSharedMemoryLine();
		config.setDawConnection(firstDaw);
		assertEquals(firstDaw, config.getDawConnection());

		// Second DAW connection (replaces first)
		SharedMemoryAudioLine secondDaw = createMockSharedMemoryLine();
		config.setDawConnection(secondDaw);

		assertEquals(secondDaw, config.getDawConnection());
		assertEquals(secondDaw, delegatedLine.getOutputDelegate());
	}

	@Test(timeout = 10_000)
	public void testNullDawConnectionHandled() {
		config = createPlayer(null);
		config.setDawMode(); // Initialize mode

		// Set a DAW connection
		SharedMemoryAudioLine dawLine = createMockSharedMemoryLine();
		config.setDawConnection(dawLine);
		assertTrue(config.hasDawConnection());

		// Clear the DAW connection
		config.setDawConnection(null);

		assertFalse(config.hasDawConnection());
		assertNull(config.getDawConnection());
	}

	@Test(timeout = 10_000)
	public void testSwitchToDawWithNoConnection() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode

		// Switch to DAW mode without any DAW connection
		config.setDawMode();

		// Should be in DAW mode but no connection
		assertTrue(config.isDawMode());
		assertFalse(config.hasDawConnection());
		assertNull(delegatedLine.getOutputDelegate());
	}

	@Test(timeout = 10_000)
	public void testModePreservedAcrossDawConnections() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode

		// First DAW connection (stored, not activated)
		SharedMemoryAudioLine firstDaw = createMockSharedMemoryLine();
		config.setDawConnection(firstDaw);
		assertTrue(config.isDirectMode()); // Still Direct

		// Second DAW connection (stored, not activated)
		SharedMemoryAudioLine secondDaw = createMockSharedMemoryLine();
		config.setDawConnection(secondDaw);
		assertTrue(config.isDirectMode()); // Still Direct

		// UI switches to DAW mode
		config.setDawMode();
		assertTrue(config.isDawMode());
		assertEquals(secondDaw, delegatedLine.getOutputDelegate()); // Second connection is now active

		// Third DAW connection (immediately activated since in DAW mode)
		SharedMemoryAudioLine thirdDaw = createMockSharedMemoryLine();
		config.setDawConnection(thirdDaw);
		assertTrue(config.isDawMode()); // Still DAW
		assertEquals(thirdDaw, delegatedLine.getOutputDelegate()); // Third is now active

		// UI switches back to Direct mode
		config.setDirectMode();
		assertTrue(config.isDirectMode()); // Now Direct
		assertTrue(config.hasDawConnection()); // DAW connection still stored
	}

	@Test(timeout = 10_000)
	@TestProperties(knownIssue = true)
	public void testWithRecordingLine() {
		OutputLine recordingLine = createMockOutputLine();
		config = createPlayer(recordingLine);

		assertSame(recordingLine, config.getRecordingLine());
	}

	/**
	 * Assumes audio hardware is available, skipping the test if not.
	 */
	private void assumeAudioHardware() {
		try {
			AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
			Assume.assumeTrue("Audio hardware required", AudioSystem.isLineSupported(info));
		} catch (Exception e) {
			Assume.assumeTrue("Audio hardware required", false);
		}
	}

	/**
	 * Creates a mock {@link SharedMemoryAudioLine} for testing.
	 *
	 * <p>Uses the three-argument constructor with regular {@link PackedCollection}
	 * instances to avoid shared memory allocation, which may not be available
	 * in CI environments.</p>
	 */
	private SharedMemoryAudioLine createMockSharedMemoryLine() {
		return new SharedMemoryAudioLine(
				new PackedCollection(SharedMemoryAudioLine.controlSize),
				new PackedCollection(BufferDefaults.defaultBufferSize),
				new PackedCollection(BufferDefaults.defaultBufferSize)) {
			@Override
			public void destroy() {
				// No-op for testing
			}
		};
	}

	/**
	 * Verifies that setDirectOutput() updates the delegate when already
	 * in direct mode, so audio routes through the new line immediately.
	 */
	@Test(timeout = 10_000)
	public void testSetDirectOutputUpdatesDelegateInDirectMode() {
		assumeAudioHardware();
		config = createPlayer(null);
		config.setDirectMode();

		// The delegate should be the initial direct output
		OutputLine initialDelegate = delegatedLine.getOutputDelegate();
		assertNotNull("Delegate should be set after setDirectMode", initialDelegate);

		// Create a new SourceDataOutputLine (via hardware)
		SourceDataOutputLine newLine = createHardwareOutputLine();
		Assume.assumeNotNull("Need hardware line for this test", newLine);

		config.setDirectOutput(newLine);

		// The delegate should now point to the new line, not the old one
		assertSame("Delegate must be updated to new line",
				newLine, delegatedLine.getOutputDelegate());
	}

	/**
	 * Verifies that setDirectOutput() does not activate the line when
	 * in DAW mode — the new line is stored for later use.
	 */
	@Test(timeout = 10_000)
	public void testSetDirectOutputDoesNotActivateInDawMode() {
		config = createPlayer(null);
		config.setDawMode();

		SourceDataOutputLine newLine = createHardwareOutputLine();
		Assume.assumeNotNull("Need hardware line for this test", newLine);

		config.setDirectOutput(newLine);

		// Delegate should still be null (DAW mode with no connection)
		assertNull("Delegate should remain null in DAW mode",
				delegatedLine.getOutputDelegate());

		// But switching to direct should use the pre-set line
		config.setDirectMode();
		assertSame("Should use pre-set direct output",
				newLine, delegatedLine.getOutputDelegate());
	}

	/**
	 * Verifies that the full reinitialize flow works: destroy existing
	 * stream, create new one, set direct output — audio should route
	 * through the new device.
	 */
	@Test(timeout = 30_000)
	public void testReinitializeWithDeviceOverride() {
		assumeAudioHardware();

		// Simulate initial setup
		config = createPlayer(null);
		config.setDirectMode();
		OutputLine original = delegatedLine.getOutputDelegate();
		assertNotNull(original);

		// Simulate reinitialize: destroy and rebuild
		config.destroy();

		// Create fresh player and config
		player = new BufferedAudioPlayer(PLAYER_COUNT, SAMPLE_RATE, MAX_FRAMES);
		delegatedLine = new DelegatedAudioLine();
		config = createPlayer(null);
		config.setDirectMode();

		// Now override with a specific device line
		SourceDataOutputLine deviceLine = createHardwareOutputLine();
		Assume.assumeNotNull("Need hardware line for this test", deviceLine);

		config.setDirectOutput(deviceLine);

		// Delegate must point to the device line
		assertSame("After reinit + setDirectOutput, delegate must be device line",
				deviceLine, delegatedLine.getOutputDelegate());
	}

	/**
	 * Creates a mock OutputLine for testing.
	 */
	private OutputLine createMockOutputLine() {
		return new OutputLine() {
			@Override
			public void write(PackedCollection sample) {}

			@Override
			public void destroy() {}

			@Override
			public int getBufferSize() { return 4096; }

			@Override
			public int getSampleRate() { return SAMPLE_RATE; }
		};
	}

	/**
	 * Creates a real hardware SourceDataOutputLine for testing device
	 * switching. Returns null if hardware is unavailable.
	 */
	private SourceDataOutputLine createHardwareOutputLine() {
		try {
			AudioFormat format = new AudioFormat(
					AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE,
					16, 2, 4, SAMPLE_RATE, false);
			OutputLine line = LineUtilities.getLine(
					format, BufferDefaults.defaultBufferSize);
			return line instanceof SourceDataOutputLine
					? (SourceDataOutputLine) line : null;
		} catch (Exception e) {
			return null;
		}
	}
}
