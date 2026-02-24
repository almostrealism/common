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

package org.almostrealism.audio.stream;

import org.almostrealism.audio.AudioScene;
import org.almostrealism.audio.BufferedAudioPlayer;
import org.almostrealism.audio.SampleMixer;
import org.almostrealism.audio.ScheduledOutputAudioPlayer;
import org.almostrealism.audio.StreamingAudioPlayer;
import org.almostrealism.audio.line.AudioLine;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.DelegatedAudioLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.SourceDataOutputLine;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;

import java.io.IOException;
import java.util.HashMap;
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
	public static final int PORT = 7799;

	public static double defaultLiveDuration = 180.0;

	private final Map<String, StreamingAudioPlayer> audioStreams;
	public AudioServer server;

	public AudioStreamManager() throws IOException {
		this.audioStreams = new HashMap<>();
		this.server = new AudioServer(PORT);
	}

	public void start() throws IOException { server.start(); }

	public AudioServer getServer() { return server; }

	public SampleMixer getMixer(String stream) {
		BufferedAudioPlayer player = getPlayer(stream);
		if (player == null) return null;

		return player.getMixer();
	}

	public BufferedAudioPlayer getPlayer(String channel) {
		return audioStreams.get(channel).getPlayer().getPlayer();
	}

	public BufferedAudioPlayer addPlayer(String channel, int playerCount,
										 OutputLine inputRecord) {
		DelegatedAudioLine line = new DelegatedAudioLine();
		server.addStream(channel, new AudioLineDelegationHandler(line));
		return addPlayer(playerCount, line, inputRecord);
	}

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
	 * DAW clients can connect via {@link org.almostrealism.audio.stream.AudioLineDelegationHandler}.</p>
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

	@Override
	public Console console() { return AudioScene.console; }
}
