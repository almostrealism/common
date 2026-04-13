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

package org.almostrealism.audio.line.test;

import org.almostrealism.audio.line.AudioDeviceInfo;
import org.almostrealism.audio.line.AudioDeviceManager;
import org.junit.Test;

import java.util.List;

/**
 * Tests for {@link AudioDeviceManager} device enumeration.
 * These tests verify the device enumeration works on the current
 * hardware without requiring specific devices to be present.
 */
public class AudioDeviceEnumerationTest {

	@Test
	public void enumerateOutputDevices() {
		List<AudioDeviceInfo> devices = AudioDeviceManager.getOutputDevices();

		System.out.println("Available output devices (" + devices.size() + "):");
		for (AudioDeviceInfo device : devices) {
			System.out.println("  - " + device.name()
					+ " (" + device.description() + ")");
		}

		// On any system with audio, we should have at least one device
		// (but we don't assert this since CI may be headless)
	}

	@Test
	public void findByNameReturnsNullForMissing() {
		AudioDeviceInfo result = AudioDeviceManager.findByName(
				"Nonexistent Device That Does Not Exist");
		assert result == null : "Should return null for unknown device";
	}

	@Test
	public void findByNameHandlesNull() {
		AudioDeviceInfo result = AudioDeviceManager.findByName(null);
		assert result == null : "Should return null for null name";
	}
}
