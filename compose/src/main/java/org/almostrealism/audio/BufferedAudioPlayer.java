/*
 * Copyright 2025 Michael Murray
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
import org.almostrealism.audio.line.AudioLineInputRecord;
import org.almostrealism.audio.line.AudioLineOperation;
import org.almostrealism.audio.line.BufferDefaults;
import org.almostrealism.audio.line.BufferedAudio;
import org.almostrealism.audio.line.BufferedOutputScheduler;
import org.almostrealism.audio.line.DelegatedAudioLine;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.line.SourceDataOutputLine;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.graph.temporal.WaveCell;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

/**
 * A multi-channel audio player that uses buffered audio processing for real-time playback.
 * This player supports loading audio from files or {@link WaveData} into multiple player
 * channels, mixing them together via {@link SampleMixer}, and delivering the output to
 * an {@link OutputLine} through a {@link BufferedOutputScheduler}.
 * <p>
 * The player is designed to work in two primary configurations:
 * <ul>
 *   <li><b>DAW Integration Mode:</b> Audio is delivered to a {@link DelegatedAudioLine}
 *       for streaming to external DAW software</li>
 *   <li><b>Direct Playback Mode:</b> Audio is delivered to a {@link SourceDataOutputLine}
 *       for direct hardware playback through the Java Sound API</li>
 * </ul>
 * <p>
 * Key features:
 * <ul>
 *   <li>Multi-channel mixing with per-channel mute and volume control</li>
 *   <li>Configurable loop duration per channel</li>
 *   <li>Time tracking via {@link TimeCell} for seek and position reporting</li>
 *   <li>Support for passthrough monitoring when used with bidirectional audio lines</li>
 * </ul>
 * <p>
 * Usage example for direct playback:
 * <pre>{@code
 * // Create player for single channel
 * BufferedAudioPlayer player = new BufferedAudioPlayer(1, 44100, 65536);
 *
 * // Get output line for hardware playback
 * SourceDataOutputLine outputLine = (SourceDataOutputLine) LineUtilities.getLine();
 *
 * // Connect player to output and start playback
 * BufferedOutputScheduler scheduler = player.deliver(outputLine);
 * scheduler.start();
 *
 * // Load and play audio
 * player.load(0, "audio.wav");
 * player.play();
 * }</pre>
 *
 * @see BufferedOutputScheduler for the scheduling mechanism
 * @see SourceDataOutputLine for direct hardware playback
 * @see DelegatedAudioLine for streaming/DAW integration
 */
public class BufferedAudioPlayer extends AudioPlayerBase implements CellFeatures {
	public static boolean enableUnifiedClock = false;

	private final int sampleRate;
	private final int bufferFrames;

	private final PackedCollection raw;
	private final List<DoubleConsumer> timeListeners;

	private final SampleMixer mixer;
	private TimeCell clock;
	private PackedCollection[] level;
	private PackedCollection[] loopDuration;
	private AudioLine outputLine;

	private final boolean[] loaded;
	private final boolean[] muted;
	private final double[] volume;
	private final double[] playbackDuration;
	private final double[] sampleDuration;
	private double passthrough;
	private boolean playing;

	private long waitTime;
	private Thread monitor;
	private boolean stopped;

	public BufferedAudioPlayer(int playerCount, int sampleRate, int bufferFrames) {
		super();
		this.bufferFrames = bufferFrames;
		this.sampleRate = sampleRate;

		this.raw = new PackedCollection(playerCount, bufferFrames);
		this.timeListeners = new ArrayList<>();

		this.loopDuration = IntStream.range(0, playerCount)
				.mapToObj(c -> new PackedCollection(1))
				.toArray(PackedCollection[]::new);
		this.loaded = new boolean[playerCount];
		this.muted = new boolean[playerCount];
		this.volume = new double[playerCount];
		this.playbackDuration = new double[playerCount];
		this.sampleDuration = new double[playerCount];

		this.mixer = new SampleMixer(playerCount);
	}

	protected void initMixer() {
		level = new PackedCollection[mixer.getChannelCount()];

		if (enableUnifiedClock) {
			this.clock = new TimeCell();
		}

		this.mixer.init(c -> {
			WaveCell cell = enableUnifiedClock ? getData(c).toCell(clock)
					: (WaveCell) w(0, p(loopDuration[c]), getData(c)).get(0);
			level[c] = cell.getData().amplitude();
			return cell;
		});

		setVolume(1.0);

		if (!enableUnifiedClock) {
			this.clock = mixer.getSample(0).getClock();
		}
	}

	protected void initMonitor() {
		if (monitor == null) {
			monitor = new Thread(() -> {
				while (!stopped) {
					try {
						for (DoubleConsumer listener : timeListeners) {
							listener.accept(getCurrentTime());
						}

						Thread.sleep(waitTime);
					} catch (Exception e) {
						warn("Error in scheduled job", e);
					}
				}
			}, "BufferedAudioPlayer Monitor");
			monitor.start();
		}
	}

	public SampleMixer getMixer() { return mixer; }

	public TimeCell getClock() { return clock; }

	public WaveData getData(int player) {
		return new WaveData(raw.range(shape(bufferFrames), player * bufferFrames).traverseEach(), sampleRate);
	}

	public synchronized void load(int player, String file) {
		update(player, file);
		updateLevel();
		initMonitor();
	}

	public synchronized void load(int player, WaveData data) {
		update(player, data);
		updateLevel();
		initMonitor();
	}

	private int updateDuration(int player, int frameCount) {
		int frames = Math.min(frameCount, bufferFrames);
		loaded[player] = true;
		playbackDuration[player] = frames / (double) sampleRate;
		sampleDuration[player] = playbackDuration[player];
		return frames;
	}

	private int resetPlayer(int player, int frameCount) {
		int frames = updateDuration(player, frameCount);
		getData(player).getData().clear();
		return frames;
	}

	protected void update(int player, WaveData source) {
		if (source == null) {
			clear(player);
			return;
		} else if (source.getSampleRate() != sampleRate) {
			warn("Sample rate " + source.getSampleRate() + " != " + sampleRate);
			return;
		}

		int frames = resetPlayer(player, source.getFrameCount());

		for (int c = 0; c < source.getChannelCount(); c++) {
			getData(player).getChannelData(c).setMem(source.getChannelData(c), 0, frames);
		}
	}

	protected void update(int player, String file) {
		if (file == null) {
			clear(player);
			return;
		}

		try (WavFile in = WavFile.openWavFile(new File(file))) {
			long inRate = in.getSampleRate();

			if (inRate != sampleRate) {
				warn("Sample rate " + inRate + " != " + sampleRate);
				return;
			}

			double[][] result = new double[in.getNumChannels()][(int) in.getFramesRemaining()];
			in.readFrames(result, (int) in.getFramesRemaining());

			int frames = resetPlayer(player, result[0].length);

			for (int c = 0; c < getData(player).getChannelCount(); c++) {
				getData(player).getChannelData(c).setMem(result[c], 0, frames);
			}
		} catch (IOException e) {
			warn("Could not load " + getFileString() + " to player", e);
		}
	}

	protected void clear(int player) {
		loaded[player] = false;
		playbackDuration[player] = 0.0;
	}

	protected void updateLevel() {
		if (clock == null) return;

		for (int c = 0; c < mixer.getChannelCount(); c++) {
			boolean audible = loaded[c] && !muted[c] && playing;
			setLevel(c, audible ? volume[c] : 0.0);
			setLoopDuration(c, playing ? this.playbackDuration[c] : 0.0);
		}

		if (outputLine != null) {
			outputLine.setPassthroughLevel(passthrough);
		}
	}

	protected void setLevel(int c, double v) {
		level[c].setMem(0, v);
	}

	protected void setLoopDuration(int c, double duration) {
		loopDuration[c].setMem(duration);

		if (enableUnifiedClock) {
			clock.setReset(0, (int) (duration * sampleRate));
		}
	}

	public BufferedOutputScheduler deliver(OutputLine out) {
		return deliver(out, null);
	}


	public BufferedOutputScheduler deliver(AudioLine main, OutputLine inputRecord) {
		return deliver((BufferedAudio) main, inputRecord);
	}

	private BufferedOutputScheduler deliver(BufferedAudio out, OutputLine record) {
		if (out.getSampleRate() != sampleRate) {
			throw new UnsupportedOperationException();
		}

		if (clock == null) {
			initMixer();

			long bufferDuration = out.getBufferSize() * 1000L / sampleRate;
			int updates = BufferDefaults.groups * 2;
			waitTime = bufferDuration / updates;
		} else {
			warn("Attempting to deliver to an already active player");
		}

		if (out instanceof AudioLine) {
			this.outputLine = (AudioLine) out;
		}

		CellList cells = mixer.toCellList();
		if (enableUnifiedClock) {
			cells = cells.addRequirement(clock);
		}

		AudioLineOperation operation = cells.toLineOperation();

		if (record == null) {
			return operation.buffer(out);
		} else {
			return new AudioLineInputRecord(operation, record).buffer(out);
		}
	}

	@Override
	public boolean play() {
		if (!playing) {
			// Align all the samples from the start
			// if play is resuming
			setFrame(0.0);
		}

		playing = true;
		updateLevel();
		return true;
	}

	@Override
	public boolean stop() {
		playing = false;
		updateLevel();
		return true;
	}

	@Override
	public boolean isPlaying() { return playing; }

	@Override
	public boolean isReady() { return clock != null; }

	@Override
	public void setVolume(double volume) {
		for (int c = 0; c < mixer.getChannelCount(); c++) {
			this.volume[c] = volume;
		}

		this.passthrough = 1.0 - volume;
		updateLevel();
	}

	@Override
	public double getVolume() { return volume[0]; }

	public void setMuted(int player, boolean muted) {
		this.muted[player] = muted;
		updateLevel();
	}

	public void setPlaybackDuration(int player, double duration) {
		this.playbackDuration[player] = duration;
		updateLevel();
	}

	public double getPlaybackDuration(int player) {
		return playbackDuration[player];
	}

	public double getSampleDuration(int player) {
		return sampleDuration[player];
	}

	protected double getFrame() { return clock.getFrame(); }

	protected void setFrame(double frame) {
		if (enableUnifiedClock) {
			clock.setFrame(frame);
		} else {
			mixer.setFrame(frame);
		}
	}

	@Override
	public void seek(double time) {
		if (clock == null) {
			return;
		}

		if (time < 0.0) time = 0.0;
		if (time > getTotalDuration()) time = getTotalDuration();
		setFrame(time * sampleRate);
	}

	@Override
	public double getCurrentTime() {
		if (clock == null || !playing) {
			return 0;
		}

		return getFrame() / (double) sampleRate;
	}

	@Override
	public double getTotalDuration() {
		return DoubleStream.of(playbackDuration).max().orElse(0.0);
	}

	@Override
	public void addTimeListener(DoubleConsumer listener) {
		timeListeners.add(listener);
	}

	@Override
	public void destroy() {
		this.stopped = true;
		this.monitor = null;

		if (this.raw != null) {
			this.raw.destroy();
			this.loopDuration = null;
		}

		if (this.loopDuration != null) {
			for (PackedCollection packedCollection : loopDuration) {
				packedCollection.destroy();
			}

			this.loopDuration = null;
		}
	}
}
