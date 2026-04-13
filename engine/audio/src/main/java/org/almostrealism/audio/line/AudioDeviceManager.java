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

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates available audio output devices on the system and provides
 * methods for opening {@link OutputLine} instances on specific devices.
 *
 * @see AudioDeviceInfo
 * @see LineUtilities
 */
public class AudioDeviceManager {

	/**
	 * Returns a list of all audio output devices that support {@link SourceDataLine}
	 * playback at the current sample rate.
	 *
	 * @return list of available output devices (never null, may be empty)
	 */
	public static List<AudioDeviceInfo> getOutputDevices() {
		AudioFormat format = getDefaultFormat();
		List<AudioDeviceInfo> devices = new ArrayList<>();

		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			Mixer mixer = AudioSystem.getMixer(info);

			for (Line.Info supported : mixer.getSourceLineInfo()) {
				if (supported instanceof DataLine.Info
						&& ((DataLine.Info) supported).isFormatSupported(format)) {
					devices.add(new AudioDeviceInfo(
							info.getName(), info.getDescription(), info));
					break;
				}
			}
		}

		return devices;
	}

	/**
	 * Opens a {@link SourceDataOutputLine} on the specified device.
	 *
	 * @param device the target output device
	 * @return an open output line, or null if the device is unavailable
	 */
	public static OutputLine getLine(AudioDeviceInfo device) {
		return getLine(device, BufferDefaults.defaultBufferSize);
	}

	/**
	 * Opens a {@link SourceDataOutputLine} on the specified device with the
	 * given buffer size.
	 *
	 * @param device      the target output device
	 * @param bufferFrames the buffer size in frames
	 * @return an open output line, or null if the device is unavailable
	 */
	public static OutputLine getLine(AudioDeviceInfo device, int bufferFrames) {
		return LineUtilities.getLine(device.mixerInfo(), getDefaultFormat(), bufferFrames);
	}

	/**
	 * Finds an {@link AudioDeviceInfo} by name from the list of available devices.
	 *
	 * @param name the device name to match
	 * @return the matching device info, or null if not found
	 */
	public static AudioDeviceInfo findByName(String name) {
		if (name == null) return null;

		for (AudioDeviceInfo device : getOutputDevices()) {
			if (name.equals(device.name())) {
				return device;
			}
		}

		return null;
	}

	/** Returns the default PCM audio format used for device capability checks. */
	private static AudioFormat getDefaultFormat() {
		return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
				OutputLine.sampleRate, 16, 2, 4,
				OutputLine.sampleRate, false);
	}
}
