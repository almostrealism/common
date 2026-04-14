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
import org.almostrealism.io.Console;

import java.util.ArrayList;
import java.util.List;

/**
 * Enumerates available audio output devices on the system and provides
 * methods for opening {@link OutputLine} instances on specific devices,
 * including multi-channel devices with per-pair output routing.
 *
 * @see AudioDeviceInfo
 * @see MultiChannelOutputLine
 * @see LineUtilities
 */
public class AudioDeviceManager {

	/**
	 * Returns a list of all audio output devices that support {@link SourceDataLine}
	 * playback at the current sample rate. Each device includes its maximum
	 * output channel count for multi-channel routing support.
	 *
	 * @return list of available output devices (never null, may be empty)
	 */
	public static List<AudioDeviceInfo> getOutputDevices() {
		AudioFormat format = getDefaultFormat(2);
		List<AudioDeviceInfo> devices = new ArrayList<>();

		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			Mixer mixer = AudioSystem.getMixer(info);
			int maxChannels = 0;

			for (Line.Info supported : mixer.getSourceLineInfo()) {
				if (supported instanceof DataLine.Info dli) {
					if (dli.isFormatSupported(format)) {
						for (AudioFormat f : dli.getFormats()) {
							if (f.getChannels() > maxChannels) {
								maxChannels = f.getChannels();
							}
						}
					}
				}
			}

			if (maxChannels > 0) {
				devices.add(new AudioDeviceInfo(
						info.getName(), info.getDescription(),
						info, maxChannels));
			}
		}

		return devices;
	}

	/**
	 * Opens a stereo {@link SourceDataOutputLine} on the specified device.
	 *
	 * @param device the target output device
	 * @return an open output line, or null if the device is unavailable
	 */
	public static OutputLine getLine(AudioDeviceInfo device) {
		return getLine(device, BufferDefaults.defaultBufferSize);
	}

	/**
	 * Opens a stereo {@link SourceDataOutputLine} on the specified device
	 * with the given buffer size.
	 *
	 * @param device       the target output device
	 * @param bufferFrames the buffer size in frames
	 * @return an open output line, or null if the device is unavailable
	 */
	public static OutputLine getLine(AudioDeviceInfo device, int bufferFrames) {
		return LineUtilities.getLine(device.mixerInfo(),
				getDefaultFormat(2), bufferFrames);
	}

	/**
	 * Opens a multi-channel {@link MultiChannelOutputLine} on the specified
	 * device. The line is opened with the device's maximum channel count.
	 *
	 * @param device       the target multi-channel output device
	 * @param bufferFrames the buffer size in frames
	 * @return a multi-channel output line, or null if unavailable
	 */
	public static MultiChannelOutputLine getMultiChannelLine(
			AudioDeviceInfo device, int bufferFrames) {
		int channels = device.maxOutputChannels();
		AudioFormat format = getDefaultFormat(channels);

		try {
			Mixer mixer = AudioSystem.getMixer(device.mixerInfo());
			DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
			SourceDataLine line = (SourceDataLine) mixer.getLine(info);
			line.open(format, Math.max(1024, bufferFrames * channels * 2));
			line.start();
			return new MultiChannelOutputLine(line, channels, bufferFrames);
		} catch (LineUnavailableException ex) {
			Console.root().features(AudioDeviceManager.class)
					.warn("Multi-channel line unavailable on "
					+ device.name() + " (" + ex.getMessage() + ")");
			return null;
		}
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

	/**
	 * Returns a PCM audio format with the specified channel count.
	 *
	 * @param channels the number of audio channels
	 * @return the audio format
	 */
	private static AudioFormat getDefaultFormat(int channels) {
		int frameSize = channels * 2; // 16-bit = 2 bytes per sample per channel
		return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
				OutputLine.sampleRate, 16, channels, frameSize,
				OutputLine.sampleRate, false);
	}
}
