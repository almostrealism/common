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

package org.almostrealism.audio;

import org.almostrealism.audio.line.DelegatedAudioLine;
import org.almostrealism.audio.line.LineUtilities;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.SharedMemoryAudioLine;
import org.almostrealism.audio.line.SourceDataOutputLine;
import org.almostrealism.io.ConsoleFeatures;

/**
 * Configuration for a unified audio player that supports switching between
 * {@link OutputMode#DIRECT direct} and {@link OutputMode#DIRECT shared}
 * output modes without recreating the player.
 *
 * <p>This class manages two key concerns:</p>
 * <ul>
 *   <li><b>Active Output Mode</b>: Determines which output delegate is currently active</li>
 *   <li><b>Connection State</b>: Tracks whether a client has connected</li>
 * </ul>
 *
 * <p>The UI widget is the sole authority for which output is active. DAW
 * connections are established in the background and only activated when
 * the user switches to DAW mode.</p>
 *
 * @see BufferedAudioPlayer
 */
// TODO  This class is poorly named
public class StreamingAudioPlayer implements ConsoleFeatures {

	/**
	 * The output mode for the unified player.
	 */
	public enum OutputMode {
		/** Direct hardware playback via SourceDataOutputLine */
		DIRECT,
		/** External streaming via SharedMemoryAudioLine */
		SHARED
	}

	private final ScheduledOutputAudioPlayer player;
	private final DelegatedAudioLine outputLine;
	private final OutputLine recordingLine;

	private SourceDataOutputLine directOutput;
	private SharedMemoryAudioLine dawOutput;
	private OutputMode activeMode;

	/**
	 * Creates a new unified player configuration.
	 * <p>
	 * Note: The initial mode is stored but the output delegate is NOT set here.
	 * The caller must call {@link #setDirectMode()} or {@link #setDawMode()}
	 * after construction to properly initialize the output delegate.
	 *
	 * @param player the scheduled audio player instance (wraps buffered player with scheduler)
	 * @param outputLine      the delegated audio line for output switching
	 * @param recordingLine   optional line for recording (may be null)
	 */
	public StreamingAudioPlayer(ScheduledOutputAudioPlayer player,
								DelegatedAudioLine outputLine,
								OutputLine recordingLine) {
		this.player = player;
		this.outputLine = outputLine;
		this.recordingLine = recordingLine;
		this.activeMode = null;
	}

	/**
	 * Returns the scheduled audio player instance.
	 */
	public ScheduledOutputAudioPlayer getPlayer() {
		return player;
	}

	/**
	 * Returns the delegated audio line used for output switching.
	 */
	public DelegatedAudioLine getOutputLine() {
		return outputLine;
	}

	/**
	 * Returns the recording line, if configured.
	 */
	public OutputLine getRecordingLine() {
		return recordingLine;
	}

	/**
	 * Returns the current active output mode.
	 */
	public OutputMode getActiveMode() {
		return activeMode;
	}

	/**
	 * Returns the {@link SourceDataOutputLine} for direct playback, creating it lazily if needed.
	 *
	 * @return the direct output line
	 * @throws IllegalStateException if no audio output line could be obtained
	 */
	public SourceDataOutputLine getOrCreateDirectOutput() {
		if (directOutput == null) {
			OutputLine line = LineUtilities.getLine();
			if (line == null) {
				throw new IllegalStateException("Could not obtain audio output line");
			}
			if (line instanceof SourceDataOutputLine) {
				directOutput = (SourceDataOutputLine) line;
			} else {
				throw new IllegalStateException("Expected SourceDataOutputLine but got " +
						line.getClass().getSimpleName());
			}
		}
		return directOutput;
	}

	/**
	 * Returns the current DAW connection, or null if no DAW has connected.
	 */
	public SharedMemoryAudioLine getDawConnection() {
		return dawOutput;
	}

	/**
	 * Called by AudioLineDelegationHandler when a DAW client connects.
	 * This stores the connection but does NOT activate it unless we're in DAW mode.
	 *
	 * <p>This ensures the UI widget remains the sole authority for which output
	 * is active. A DAW connection made while in Direct mode will be stored and
	 * ready for use when the user switches to DAW mode.</p>
	 *
	 * @param connection the new SharedMemoryAudioLine connection
	 */
	public synchronized void setDawConnection(SharedMemoryAudioLine connection) {
		SharedMemoryAudioLine old = this.dawOutput;
		this.dawOutput = connection;

		// Only update the active delegate if we're currently in DAW mode
		if (activeMode == OutputMode.SHARED) {
			outputLine.setOutputDelegate(dawOutput);
			log("DAW connection activated: " + (dawOutput != null ? "connected" : "disconnected"));
		} else {
			log("DAW connection stored (not active, currently in " + activeMode + " mode)");
		}

		// Clean up the old connection
		if (old != null && old != connection) {
			old.destroy();
		}
	}

	/**
	 * Switches to direct hardware playback mode.
	 * The DAW connection (if any) is preserved but not active.
	 */
	public synchronized void setDirectMode() {
		if (activeMode == OutputMode.DIRECT) {
			return; // Already in direct mode
		}

		activeMode = OutputMode.DIRECT;
		SourceDataOutputLine direct = getOrCreateDirectOutput();
		outputLine.setOutputDelegate(direct);

		// Start the hardware line if it was stopped
		direct.start();
	}

	/**
	 * Switches to DAW streaming mode.
	 * Uses the existing DAW connection if available, otherwise output is null
	 * (silent) until a DAW client connects.
	 */
	public synchronized void setDawMode() {
		if (activeMode == OutputMode.SHARED) {
			return; // Already in DAW mode
		}

		// Stop the direct output to prevent frame position from advancing
		// while we're not using it (makes switching back cleaner)
		if (directOutput != null) {
			directOutput.stop();
		}

		activeMode = OutputMode.SHARED;
		outputLine.setOutputDelegate(dawOutput); // May be null, that's OK
		log("Switched to DAW mode" + (dawOutput != null ? " (connection available)" : " (awaiting connection)"));
	}

	/**
	 * Checks if direct mode is currently active.
	 */
	public boolean isDirectMode() {
		return activeMode == OutputMode.DIRECT;
	}

	/**
	 * Checks if DAW mode is currently active.
	 */
	public boolean isDawMode() {
		return activeMode == OutputMode.SHARED;
	}

	/**
	 * Checks if a DAW connection is available (regardless of active mode).
	 */
	public boolean hasDawConnection() {
		return dawOutput != null;
	}

	/**
	 * Returns the buffer gap in frames between write and read positions.
	 * <p>
	 * Only meaningful in direct mode when the player is actively running.
	 *
	 * @return the buffer gap
	 */
	public int getBufferGap() {
		return player.getBufferGap();
	}

	/**
	 * Returns the buffer gap as a percentage of total buffer size.
	 *
	 * @return the buffer gap percentage (0.0-100.0)
	 */
	public double getBufferGapPercent() {
		return player.getBufferGapPercent();
	}

	/**
	 * Returns whether the player is in degraded mode (unable to keep up
	 * with real-time audio generation).
	 *
	 * @return true if in degraded mode
	 */
	public boolean isDegradedMode() {
		return player.isDegradedMode();
	}

	/**
	 * Resets the direct output line to recover from audio issues.
	 * <p>
	 * This method is useful when switching audio destinations (e.g., Bluetooth
	 * devices) causes the SourceDataLine to enter a corrupted state. It closes
	 * the current line and creates a new one with the same configuration.
	 * <p>
	 * This only affects direct mode. In DAW mode, this method has no effect
	 * since the DAW manages its own audio output.
	 * <p>
	 * If the scheduler is currently suspended, the new line
	 * will be stopped immediately after creation to maintain
	 * the suspended state.
	 *
	 * @return true if the reset was performed, false if not in direct mode
	 *         or no direct output exists
	 */
	public synchronized boolean resetOutputLine() {
		if (activeMode != OutputMode.DIRECT || directOutput == null) {
			log("Cannot reset output line: " +
					(activeMode != OutputMode.DIRECT ? "not in direct mode" : "no direct output"));
			return false;
		}

		directOutput.reset();
		return true;
	}

	/**
	 * Destroys all resources associated with this configuration.
	 */
	public void destroy() {
		if (player != null) {
			player.destroy();
		}
		if (directOutput != null) {
			directOutput.destroy();
			directOutput = null;
		}
		if (dawOutput != null) {
			dawOutput.destroy();
			dawOutput = null;
		}
	}
}
