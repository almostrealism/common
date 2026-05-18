/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.studio;

import java.util.function.DoubleConsumer;

/**
 * Interface for controlling audio playback in the Almost Realism studio layer.
 * Implementations provide transport controls (play, stop, seek), volume management,
 * and time reporting for audio content.
 */
public interface AudioPlayer {
	/**
	 * Starts or resumes audio playback.
	 *
	 * @return {@code true} if playback was successfully started
	 */
	boolean play();

	/**
	 * Stops audio playback.
	 *
	 * @return {@code true} if playback was successfully stopped
	 */
	boolean stop();

	/**
	 * Returns whether audio is currently playing.
	 *
	 * @return {@code true} if the player is actively playing audio
	 */
	boolean isPlaying();

	/**
	 * Returns whether the player is ready for playback (e.g., initialized and loaded).
	 *
	 * @return {@code true} if the player is ready to begin playback
	 */
	boolean isReady();

	/**
	 * Sets the output volume level.
	 *
	 * @param volume the volume level, typically in the range {@code [0.0, 1.0]}
	 */
	void setVolume(double volume);

	/**
	 * Returns the current output volume level.
	 *
	 * @return the current volume
	 */
	double getVolume();

	/**
	 * Seeks to the specified playback position.
	 *
	 * @param time the target time in seconds
	 */
	void seek(double time);

	/**
	 * Returns the current playback position in seconds.
	 *
	 * @return the current time in seconds, or {@code 0.0} if not playing
	 */
	double getCurrentTime();

	/**
	 * Returns the total duration of the loaded audio content in seconds.
	 *
	 * @return the total duration in seconds
	 */
	double getTotalDuration();

	/**
	 * Registers a listener that receives the current playback time at regular intervals.
	 *
	 * @param listener a consumer that accepts the current playback time in seconds
	 */
	void addTimeListener(DoubleConsumer listener);

	/**
	 * Releases all resources held by this player.
	 */
	void destroy();
}
