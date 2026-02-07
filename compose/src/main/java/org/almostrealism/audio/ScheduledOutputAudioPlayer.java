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

import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.AudioLine;
import org.almostrealism.audio.line.BufferedAudio;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.graph.TimeCell;

import java.util.function.DoubleConsumer;

/**
 * An {@link AudioPlayer} implementation that wraps a {@link BufferedAudioPlayer} and
 * manages a single {@link BufferedOutputScheduler} for delivering audio to an output line.
 * <p>
 * This class provides the complete player lifecycle including:
 * <ul>
 *   <li>Scheduler creation during construction</li>
 *   <li>Automatic suspend/unsuspend on stop/play for proper pause behavior</li>
 *   <li>Delegation of all {@link AudioPlayer} methods to the underlying player</li>
 * </ul>
 * <p>
 * When {@link #stop()} is called, the scheduler is suspended, which stops the output
 * line and prevents continuous writing of silence to hardware. When {@link #play()} is
 * called, the scheduler is resumed before audio generation restarts.
 * <p>
 * <b>Single Output Destination:</b> This class is designed for a single output destination.
 * For multiple output destinations from the same audio source, use {@link BufferedAudioPlayer}
 * directly and manage schedulers manually.
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create the underlying player
 * BufferedAudioPlayer player = new BufferedAudioPlayer(1, 44100, 65536);
 *
 * // Wrap with scheduled output
 * ScheduledOutputAudioPlayer scheduledPlayer =
 *     new ScheduledOutputAudioPlayer(player, outputLine, null);
 *
 * // Start the scheduler
 * scheduledPlayer.start();
 *
 * // Load and play
 * scheduledPlayer.load(0, "audio.wav");
 * scheduledPlayer.play();
 *
 * // Pause - automatically suspends scheduler
 * scheduledPlayer.stop();
 *
 * // Resume - automatically unsuspends scheduler
 * scheduledPlayer.play();
 * }</pre>
 *
 * @see BufferedAudioPlayer for the underlying player
 * @see BufferedOutputScheduler for the scheduling mechanism
 */
public class ScheduledOutputAudioPlayer extends AudioPlayerBase {
	private final BufferedAudioPlayer player;
	private final BufferedOutputScheduler scheduler;

	/**
	 * Creates a new scheduled output player wrapping the specified player and
	 * delivering audio to the specified output line.
	 *
	 * @param player       the underlying audio player
	 * @param outputLine   the output line for audio delivery
	 * @param recordingLine optional line for recording (may be null)
	 */
	public ScheduledOutputAudioPlayer(BufferedAudioPlayer player,
									  BufferedAudio outputLine,
									  OutputLine recordingLine) {
		this.player = player;
		if (outputLine instanceof AudioLine) {
			this.scheduler = player.deliver((AudioLine) outputLine, recordingLine);
		} else {
			this.scheduler = player.deliver((OutputLine) outputLine);
		}
	}

	/**
	 * Starts the scheduler. This must be called before audio can be played.
	 */
	public void start() {
		scheduler.start();
	}

	/**
	 * Returns the underlying {@link BufferedAudioPlayer}.
	 *
	 * @return the wrapped player
	 */
	public BufferedAudioPlayer getPlayer() {
		return player;
	}

	/**
	 * Returns the {@link BufferedOutputScheduler} managed by this player.
	 *
	 * @return the scheduler
	 */
	public BufferedOutputScheduler getScheduler() {
		return scheduler;
	}

	// ========== AudioPlayer Implementation ==========

	/**
	 * Starts or resumes playback. The scheduler is unsuspended before the
	 * underlying player's play method is called.
	 *
	 * @return true if playback started successfully
	 */
	@Override
	public boolean play() {
		scheduler.unsuspend();
		return player.play();
	}

	/**
	 * Stops playback. The underlying player's stop method is called first
	 * (to zero levels), then the scheduler is suspended to stop the output line.
	 *
	 * @return true if playback stopped successfully
	 */
	@Override
	public boolean stop() {
		boolean result = player.stop();
		scheduler.suspend();
		return result;
	}

	@Override
	public boolean isPlaying() {
		return player.isPlaying();
	}

	@Override
	public boolean isReady() {
		return player.isReady();
	}

	@Override
	public void setVolume(double volume) {
		player.setVolume(volume);
	}

	@Override
	public double getVolume() {
		return player.getVolume();
	}

	@Override
	public void seek(double time) {
		player.seek(time);
	}

	@Override
	public double getCurrentTime() {
		return player.getCurrentTime();
	}

	@Override
	public double getTotalDuration() {
		return player.getTotalDuration();
	}

	@Override
	public void addTimeListener(DoubleConsumer listener) {
		player.addTimeListener(listener);
	}

	@Override
	public void destroy() {
		scheduler.stop();
		player.destroy();
	}

	// ========== BufferedAudioPlayer Delegation ==========

	/**
	 * Loads audio from a file into the specified player channel.
	 *
	 * @param playerIndex the player channel index
	 * @param file        the audio file path
	 */
	public void load(int playerIndex, String file) {
		player.load(playerIndex, file);
	}

	/**
	 * Loads audio data into the specified player channel.
	 *
	 * @param playerIndex the player channel index
	 * @param data        the audio data
	 */
	public void load(int playerIndex, WaveData data) {
		player.load(playerIndex, data);
	}

	/**
	 * Returns the sample mixer for this player.
	 *
	 * @return the mixer
	 */
	public SampleMixer getMixer() {
		return player.getMixer();
	}

	/**
	 * Returns the time cell (clock) for this player.
	 *
	 * @return the clock
	 */
	public TimeCell getClock() {
		return player.getClock();
	}

	/**
	 * Sets whether a specific player channel is muted.
	 *
	 * @param playerIndex the player channel index
	 * @param muted       true to mute, false to unmute
	 */
	public void setMuted(int playerIndex, boolean muted) {
		player.setMuted(playerIndex, muted);
	}

	/**
	 * Sets the playback duration for a specific player channel.
	 *
	 * @param playerIndex the player channel index
	 * @param duration    the duration in seconds
	 */
	public void setPlaybackDuration(int playerIndex, double duration) {
		player.setPlaybackDuration(playerIndex, duration);
	}

	/**
	 * Returns the playback duration for a specific player channel.
	 *
	 * @param playerIndex the player channel index
	 * @return the duration in seconds
	 */
	public double getPlaybackDuration(int playerIndex) {
		return player.getPlaybackDuration(playerIndex);
	}

	/**
	 * Returns the sample duration for a specific player channel.
	 *
	 * @param playerIndex the player channel index
	 * @return the sample duration in seconds
	 */
	public double getSampleDuration(int playerIndex) {
		return player.getSampleDuration(playerIndex);
	}

	// ========== Scheduler Monitoring ==========

	/**
	 * Returns the buffer gap in frames between write and read positions.
	 *
	 * @return the buffer gap in frames
	 */
	public int getBufferGap() {
		return scheduler.getBufferGap();
	}

	/**
	 * Returns the buffer gap as a percentage of total buffer size.
	 *
	 * @return the buffer gap percentage (0.0-100.0)
	 */
	public double getBufferGapPercent() {
		return scheduler.getBufferGapPercent();
	}

	/**
	 * Returns whether the scheduler is in degraded mode (unable to keep up
	 * with real-time audio generation).
	 *
	 * @return true if in degraded mode
	 */
	public boolean isDegradedMode() {
		return scheduler.isDegradedMode();
	}

	/**
	 * Returns whether the scheduler is currently suspended.
	 *
	 * @return true if suspended
	 */
	public boolean isSuspended() {
		return scheduler.isSuspended();
	}
}
