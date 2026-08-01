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

package org.almostrealism.audio.line;

import javax.sound.sampled.Mixer;

/**
 * Describes an available audio output device on the system, including
 * its channel capabilities for multi-channel output pair routing.
 *
 * @param name             the human-readable device name (used for persistence and display)
 * @param description      the device description provided by the platform
 * @param mixerInfo        the Java Sound API {@link Mixer.Info} for opening lines on this device
 * @param maxOutputChannels the maximum number of output channels supported (e.g., 8 for 4 stereo pairs)
 */
public record AudioDeviceInfo(String name, String description,
							  Mixer.Info mixerInfo, int maxOutputChannels) {

	/**
	 * Returns the number of stereo output pairs available on this device.
	 *
	 * @return the stereo pair count (maxOutputChannels / 2)
	 */
	public int getOutputPairCount() {
		return maxOutputChannels / 2;
	}

	/**
	 * Returns true if this device supports multi-channel output
	 * (more than one stereo pair).
	 *
	 * @return true if the device has more than 2 output channels
	 */
	public boolean isMultiChannel() {
		return maxOutputChannels > 2;
	}

	@Override
	public String toString() {
		return name;
	}
}
