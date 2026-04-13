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
 * Describes an available audio output device on the system.
 *
 * @param name      the human-readable device name (used for persistence and display)
 * @param description the device description provided by the platform
 * @param mixerInfo the Java Sound API {@link Mixer.Info} for opening lines on this device
 */
public record AudioDeviceInfo(String name, String description, Mixer.Info mixerInfo) {

	@Override
	public String toString() {
		return name;
	}
}
