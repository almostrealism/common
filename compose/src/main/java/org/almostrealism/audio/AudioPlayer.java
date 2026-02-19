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

package org.almostrealism.audio;

import java.util.function.DoubleConsumer;

/** The AudioPlayer interface. */
public interface AudioPlayer {
	/** Performs the play operation. */
	boolean play();
	/** Performs the stop operation. */
	boolean stop();

	/** Performs the isPlaying operation. */
	boolean isPlaying();
	/** Performs the isReady operation. */
	boolean isReady();

	/** Performs the setVolume operation. */
	void setVolume(double volume);
	/** Performs the getVolume operation. */
	double getVolume();

	/** Performs the seek operation. */
	void seek(double time);
	/** Performs the getCurrentTime operation. */
	double getCurrentTime();
	/** Performs the getTotalDuration operation. */
	double getTotalDuration();
	/** Performs the addTimeListener operation. */
	void addTimeListener(DoubleConsumer listener);

	/** Performs the destroy operation. */
	void destroy();
}
