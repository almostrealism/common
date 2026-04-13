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

package org.almostrealism.studio.stream;

import org.almostrealism.studio.AudioScene;
import org.almostrealism.studio.BufferedAudioPlayer;
import org.almostrealism.studio.Mixer;
import org.almostrealism.studio.SampleMixer;
import org.almostrealism.studio.ScheduledOutputAudioPlayer;
import org.almostrealism.studio.StreamingAudioPlayer;
import org.almostrealism.audio.line.AudioLine;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.DelegatedAudioLine;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.OutputLineGroup;
import org.almostrealism.audio.line.SourceDataOutputLine;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manages audio playback channels and their associated {@link BufferedAudioPlayer} instances.
 * This class provides unified infrastructure for both DAW integration mode (streaming to
 * external applications) and direct hardware playback mode.
 * <p>
 * Each channel is identified by a string key and is associated with:
 * <ul>
 *   <li>A {@link BufferedAudioPlayer} that handles audio mixing and playback control</li>
 *   <li>A {@link BufferedOutputScheduler} that manages timing and buffered writes</li>
 *   <li>An output line - either {@link DelegatedAudioLine} for streaming or
 *       {@link SourceDataOutputLine} for direct hardware playback</li>
 * </ul>
 * <p>
 * Usage example for DAW integration:
 * <pre>{@code
 * AudioStreamManager manager = new AudioStreamManager();
 * manager.start();
 *
 * // Add a player for the "live" channel with 4 player slots
 * BufferedAudioPlayer player = manager.addPlayer("live", 4, recordingLine);
 *
 * // Load audio and control playback
 * player.load(0, "sample.wav");
 * player.play();
 * }</pre>
 *
 * @see BufferedAudioPlayer for the player implementation
 * @see BufferedOutputScheduler for the scheduling mechanism
 * @see AudioServer for the streaming server
 * @see DelegatedAudioLine for streaming/DAW integration output
 * @see SourceDataOutputLine for direct hardware playback output
 */
// TODO  AudioStreamManager really no longer needs to support separate named channels
// TODO  since one will surely be sufficient for all normal playback
public class AudioStreamManager implements ConsoleFeatures {
	/** Default HTTP port used by the {@link AudioServer}. */
	public static final int PORT = 7799;

	/** Default live-stream buffer duration in seconds. */
	public static double defaultLiveDuration = 180.0;

	/** Map from channel name to its active streaming player. */
	private final Map<String, StreamingAudioPlayer> audioStreams;

	/** HTTP audio server used for DAW and streaming client connections. */
	public AudioServer server;

	/**
	 * Creates an audio stream manager and starts an {@link AudioServer} on the default port.
	 *
	 * @throws IOException if the server cannot bind to the port
	 */
	public AudioStreamManager() throws IOException {
		this.audioStreams = new HashMap<>();
		this.server = new AudioServer(PORT);
	}

	/** Starts the underlying {@link AudioServer} to accept client connections. */
	public void start() throws IOException { server.start(); }

	/** Returns the underlying {@link AudioServer}. */
	public AudioServer getServer() { return server; }

	/**
	 * Returns the {@link SampleMixer} for the given named stream, or {@code null}
	 * if the stream does not exist.
	 *
	 * @param stream the stream channel name
	 * @return the sample mixer, or {@code null}
	 */
	public SampleMixer getMixer(String stream) {
		BufferedAudioPlayer player = getPlayer(stream);
		if (player == null) return null;

		return player.getMixer();
	}

	/**
	 * Returns the {@link BufferedAudioPlayer} for the given named channel.
	 *
	 * @param channel the channel name
	 * @return the buffered audio player
	 */
	public BufferedAudioPlayer getPlayer(String channel) {
		return audioStreams.get(channel).getPlayer().getPlayer();
	}

	/**
	 * Creates a player for the given named channel, registers it with the server,
	 * and returns the underlying {@link BufferedAudioPlayer}.
	 *
	 * @param channel     the stream channel name
	 * @param playerCount the number of audio sources the player can mix
	 * @param inputRecord optional output line for recording the mixed audio
	 * @return the buffered audio player
	 */
	public BufferedAudioPlayer addPlayer(String channel, int playerCount,
										 OutputLine inputRecord) {
		DelegatedAudioLine line = new DelegatedAudioLine();
		server.addStream(channel, new AudioLineDelegationHandler(line));
		return addPlayer(playerCount, line, inputRecord);
	}

	/**
	 * Creates a player delivering audio to the given output line and returns the
	 * underlying {@link BufferedAudioPlayer}.
	 *
	 * @param playerCount the number of audio sources the player can mix
	 * @param out         the audio output line
	 * @param inputRecord optional output line for recording the mixed audio
	 * @return the buffered audio player
	 */
	public BufferedAudioPlayer addPlayer(int playerCount,
										 AudioLine out, OutputLine inputRecord) {
		int maxFrames = (int) (out.getSampleRate() * defaultLiveDuration);

		// Ensure that the player buffer size is a multiple
		// of the size of the OutputLine buffer
		maxFrames = maxFrames / out.getBufferSize();
		maxFrames *= out.getBufferSize();

		if (maxFrames % out.getBufferSize() != 0) {
			throw new IllegalArgumentException();
		}

		BufferedAudioPlayer player = new BufferedAudioPlayer(playerCount, out.getSampleRate(), maxFrames);
		BufferedOutputScheduler scheduler = player.deliver(out, inputRecord);
		scheduler.start();
		return player;
	}

	/**
	 * Creates a unified player that supports switching between Direct and DAW modes
	 * without recreating the player. The player uses a single {@link DelegatedAudioLine}
	 * whose output delegate can be switched based on the active mode.
	 *
	 * <p>The created player starts in the specified initial mode. Use
	 * {@link StreamingAudioPlayer#setDirectMode()} and {@link StreamingAudioPlayer#setDawMode()}
	 * to switch modes at runtime.</p>
	 *
	 * <p>For DAW mode, this also registers the channel with the {@link AudioServer} so that
	 * DAW clients can connect via {@link org.almostrealism.studio.stream.AudioLineDelegationHandler}.</p>
	 *
	 * @param channel The channel name for DAW registration
	 * @param playerCount Number of audio sources this player can mix
	 * @param inputRecord Optional output line for recording the mixed output
	 * @param initialMode The initial output mode (DIRECT or DAW)
	 * @return The unified player configuration
	 */
	public StreamingAudioPlayer createStream(String channel, int playerCount,
											 OutputLine inputRecord,
											 StreamingAudioPlayer.OutputMode initialMode) {
		// Create a DelegatedAudioLine that will switch between outputs
		DelegatedAudioLine delegatedLine = new DelegatedAudioLine();

		// Calculate buffer parameters based on default settings
		int bufferSize = delegatedLine.getBufferSize();
		int maxFrames = (int) (OutputLine.sampleRate * defaultLiveDuration);
		maxFrames = (maxFrames / bufferSize) * bufferSize;

		// Create the player and wrap with scheduled output
		BufferedAudioPlayer player = new BufferedAudioPlayer(playerCount,
				OutputLine.sampleRate, maxFrames);
		ScheduledOutputAudioPlayer scheduledPlayer =
				new ScheduledOutputAudioPlayer(player, delegatedLine, inputRecord);

		// Create the unified config
		StreamingAudioPlayer config =
				new StreamingAudioPlayer(scheduledPlayer, delegatedLine, inputRecord);

		// Register with AudioServer for DAW connections
		server.addStream(channel,
				new AudioLineDelegationHandler(delegatedLine, config));

		// Store references
		audioStreams.put(channel, config);

		// Set initial mode (this will set the appropriate output delegate)
		if (initialMode == StreamingAudioPlayer.OutputMode.DIRECT) {
			config.setDirectMode();
		} else {
			config.setDawMode();
		}

		// Start the scheduler
		scheduledPlayer.start();

		return config;
	}

	/**
	 * Creates a multi-device stream where different channels are routed to
	 * different output devices. Each entry in the {@code deviceGroups} map
	 * assigns a set of channel indices to an {@link OutputLine} for a
	 * specific device.
	 *
	 * <p>The underlying {@link Mixer} is configured with output groups so
	 * that channel rendering happens exactly once. Each group's summed
	 * output is delivered to its device via a separate
	 * {@link BufferedOutputScheduler}. An {@link OutputLineGroup} synchronizes
	 * read positions across all devices.</p>
	 *
	 * @param channel      the channel name for DAW registration
	 * @param playerCount  number of audio sources this player can mix
	 * @param inputRecord  optional output line for recording the mixed output
	 * @param initialMode  the initial output mode (DIRECT or DAW)
	 * @param deviceGroups map from device/group name to (output line, channel indices)
	 * @return the unified player configuration
	 */
	public StreamingAudioPlayer createMultiDeviceStream(
			String channel, int playerCount,
			OutputLine inputRecord,
			StreamingAudioPlayer.OutputMode initialMode,
			Map<String, DeviceGroup> deviceGroups) {

		int bufferSize = BufferDefaults.defaultBufferSize;
		int maxFrames = (int) (OutputLine.sampleRate * defaultLiveDuration);
		maxFrames = (maxFrames / bufferSize) * bufferSize;

		BufferedAudioPlayer player = new BufferedAudioPlayer(playerCount,
				OutputLine.sampleRate, maxFrames);

		// Configure output groups on the Mixer before building the pipeline
		Mixer mixer = player.getMixer().getChannelMixer();
		for (Map.Entry<String, DeviceGroup> entry : deviceGroups.entrySet()) {
			mixer.addOutputGroup(entry.getKey(), entry.getValue().channelIndices());
		}
		mixer.applyOutputGroups();

		// Build per-group output lines map for deliverGroups()
		Map<String, OutputLine> groupLines = new LinkedHashMap<>();
		for (Map.Entry<String, DeviceGroup> entry : deviceGroups.entrySet()) {
			groupLines.put(entry.getKey(), entry.getValue().outputLine());
		}

		// Create per-group schedulers (each ticks only its group's cells)
		Map<String, BufferedOutputScheduler> schedulers =
				player.deliverGroups(groupLines);

		// Wrap in ScheduledOutputAudioPlayer with multi-scheduler support
		ScheduledOutputAudioPlayer scheduledPlayer =
				new ScheduledOutputAudioPlayer(player, schedulers);

		// Create an OutputLineGroup for DAW/streaming mode fallback
		OutputLineGroup lineGroup = new OutputLineGroup();
		for (DeviceGroup group : deviceGroups.values()) {
			lineGroup.addMember(group.outputLine());
		}

		DelegatedAudioLine delegatedLine = new DelegatedAudioLine();

		StreamingAudioPlayer config =
				new StreamingAudioPlayer(scheduledPlayer, delegatedLine, inputRecord);

		server.addStream(channel,
				new AudioLineDelegationHandler(delegatedLine, config));
		audioStreams.put(channel, config);

		// In direct mode, the per-group schedulers handle output.
		// The DelegatedAudioLine is used for DAW mode fallback.
		if (initialMode == StreamingAudioPlayer.OutputMode.DIRECT) {
			config.setDirectMode();
		} else {
			config.setDawMode();
		}

		scheduledPlayer.start();

		return config;
	}

	/**
	 * Describes a device output group: an output line for a specific device
	 * and the channel indices that should be routed to it.
	 *
	 * @param outputLine     the output line for this device
	 * @param channelIndices the channel indices (0-based) assigned to this device
	 */
	public record DeviceGroup(OutputLine outputLine, int[] channelIndices) {
	}

	@Override
	public Console console() { return AudioScene.console; }
}
