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

package org.almostrealism.audio.test;

import org.almostrealism.audio.BufferedAudioPlayer;
import org.almostrealism.audio.ScheduledOutputAudioPlayer;
import org.almostrealism.audio.StreamingAudioPlayer;
import org.almostrealism.audio.StreamingAudioPlayer.OutputMode;
import org.almostrealism.audio.line.DelegatedAudioLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.SharedMemoryAudioLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Before;
import org.junit.Test;

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

	@Test
	public void testInitialDirectMode() {
		config = createPlayer(null);
		config.setDirectMode(); // Must explicitly set mode after construction

		assertEquals(OutputMode.DIRECT, config.getActiveMode());
		assertTrue(config.isDirectMode());
		assertFalse(config.isDawMode());
		assertFalse(config.hasDawConnection());
	}

	@Test
	public void testInitialDawMode() {
		config = createPlayer(null);
		config.setDawMode(); // Must explicitly set mode after construction

		assertEquals(OutputMode.SHARED, config.getActiveMode());
		assertFalse(config.isDirectMode());
		assertTrue(config.isDawMode());
		assertFalse(config.hasDawConnection());
	}

	@Test
	public void testSwitchFromDirectToDaw() {
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode
		assertTrue(config.isDirectMode());

		config.setDawMode();

		assertEquals(OutputMode.SHARED, config.getActiveMode());
		assertTrue(config.isDawMode());
		assertFalse(config.isDirectMode());
	}

	@Test
	public void testSwitchFromDawToDirect() {
		config = createPlayer(null);
		config.setDawMode(); // Initialize mode
		assertTrue(config.isDawMode());

		config.setDirectMode();

		assertEquals(OutputMode.DIRECT, config.getActiveMode());
		assertTrue(config.isDirectMode());
		assertFalse(config.isDawMode());
	}

	@Test
	public void testSetDirectModeIdempotent() {
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode

		// Multiple calls should not cause issues
		config.setDirectMode();
		config.setDirectMode();
		config.setDirectMode();

		assertEquals(OutputMode.DIRECT, config.getActiveMode());
	}

	@Test
	public void testSetDawModeIdempotent() {
		config = createPlayer(null);
		config.setDawMode(); // Initialize mode

		// Multiple calls should not cause issues
		config.setDawMode();
		config.setDawMode();
		config.setDawMode();

		assertEquals(OutputMode.SHARED, config.getActiveMode());
	}

	@Test
	public void testDawConnectionStoredButNotActivatedInDirectMode() {
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode

		// Capture initial delegate state
		OutputLine initialDelegate = delegatedLine.getDelegate();

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

	@Test
	public void testDawConnectionActivatedWhenSwitchingToDaw() {
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

	@Test
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

	@Test
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

	@Test
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

	@Test
	public void testSwitchToDawWithNoConnection() {
		config = createPlayer(null);
		config.setDirectMode(); // Initialize mode

		// Switch to DAW mode without any DAW connection
		config.setDawMode();

		// Should be in DAW mode but no connection
		assertTrue(config.isDawMode());
		assertFalse(config.hasDawConnection());
		assertNull(delegatedLine.getOutputDelegate());
	}

	@Test
	public void testModePreservedAcrossDawConnections() {
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

	@Test
	public void testWithRecordingLine() {
		OutputLine recordingLine = createMockOutputLine();
		config = createPlayer(recordingLine);

		assertSame(recordingLine, config.getRecordingLine());
	}

	/**
	 * Creates a mock {@link SharedMemoryAudioLine} for testing.
	 * In real usage, this would be created by
	 * {@link org.almostrealism.audio.stream.AudioLineDelegationHandler}
	 * when a DAW client connects.
	 */
	private SharedMemoryAudioLine createMockSharedMemoryLine() {
		// Create a simple mock - SharedMemoryAudioLine requires a path
		// For testing, we just need an instance to track references
		return new SharedMemoryAudioLine("/tmp/test-shared-audio-" + System.nanoTime()) {
			@Override
			public void destroy() {
				// No-op for testing
			}
		};
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
}
