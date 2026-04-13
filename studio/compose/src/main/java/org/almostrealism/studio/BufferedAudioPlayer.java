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

package org.almostrealism.studio;
import org.almostrealism.audio.CellList;
import org.almostrealism.audio.WavFile;
import org.almostrealism.audio.CellFeatures;

import org.almostrealism.audio.data.DynamicWaveDataProvider;
import org.almostrealism.audio.data.FileWaveDataProvider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <h2>Multiple Output Destinations</h2>
 * <p>
 * <b>IMPORTANT:</b> The {@link #deliver(OutputLine)} method may be called multiple times
 * with different output lines. Each call creates and returns a new {@link BufferedOutputScheduler}
 * that independently consumes audio from this player's shared processing pipeline.
 * </p>
 * <p>
 * This player does NOT track or manage the schedulers it creates. The caller is responsible for:
 * <ul>
 *   <li>Storing references to each scheduler returned by {@code deliver()}</li>
 *   <li>Starting each scheduler with {@link BufferedOutputScheduler#start()}</li>
 *   <li>Suspending/resuming each scheduler individually with
 *       {@link BufferedOutputScheduler#suspend()} and {@link BufferedOutputScheduler#unsuspend()}</li>
 *   <li>Stopping each scheduler with {@link BufferedOutputScheduler#stop()}</li>
 * </ul>
 * <p>
 * The player's {@link #play()} and {@link #stop()} methods control the logical playback state
 * (affecting audio generation via level controls), but do NOT control individual schedulers.
 * To pause audio output without continuously writing silence to hardware, use the scheduler's
 * suspend mechanism directly.
 * </p>
 *
 * <h2>Usage Example</h2>
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
 *
 * // To pause without writing silence to hardware:
 * player.stop();           // Stop audio generation
 * scheduler.suspend();     // Suspend the scheduler and stop the hardware line
 *
 * // To resume:
 * scheduler.unsuspend();   // Resume the scheduler and restart the hardware line
 * player.play();           // Resume audio generation
 * }</pre>
 *
 * @see BufferedOutputScheduler for the scheduling mechanism
 * @see SourceDataOutputLine for direct hardware playback
 * @see DelegatedAudioLine for streaming/DAW integration
 */
public class BufferedAudioPlayer implements AudioPlayer, CellFeatures {
	/**
	 * When {@code true}, a single shared {@link TimeCell} drives all sample channels;
	 * when {@code false}, each channel tracks time independently via its own clock.
	 */
	public static boolean enableUnifiedClock = false;

	/** Audio sample rate in Hz shared across all channels. */
	private final int sampleRate;

	/** Number of frames in the shared audio buffer per channel. */
	private final int bufferFrames;

	/** Flat packed buffer holding raw PCM data for all player channels. */
	private final PackedCollection raw;

	/** Listeners notified of the current playback time at regular monitoring intervals. */
	private final List<DoubleConsumer> timeListeners;

	/** Multi-channel sample mixer that combines individual channel {@link WaveCell} outputs. */
	private final SampleMixer mixer;

	/** Master clock providing the current sample frame position for all channels. */
	private TimeCell clock;

	/** Per-channel amplitude control collections; one scalar per channel. */
	private PackedCollection[] level;

	/** Per-channel loop duration in seconds; controls when each channel wraps. */
	private PackedCollection[] loopDuration;

	/** The active audio output line for passthrough level control; may be {@code null}. */
	private AudioLine outputLine;

	/** Per-channel flag indicating whether audio data has been loaded. */
	private final boolean[] loaded;

	/** Per-channel mute state; a muted channel outputs silence regardless of volume. */
	private final boolean[] muted;

	/** Per-channel volume levels applied when computing output amplitude. */
	private final double[] volume;

	/** Per-channel playback durations in seconds, used for loop and seek bounds. */
	private final double[] playbackDuration;

	/** Per-channel original sample durations in seconds as loaded from source data. */
	private final double[] sampleDuration;

	/** Passthrough level for monitoring input through the output line. */
	private double passthrough;

	/** {@code true} while the player is in the playing state. */
	private boolean playing;

	/** Interval in milliseconds between time-listener notifications. */
	private long waitTime;

	/** Background thread that periodically notifies time listeners. */
	private Thread monitor;

	/** Set to {@code true} to terminate the monitor thread on {@link #destroy()}. */
	private boolean stopped;

	/**
	 * Creates a new {@code BufferedAudioPlayer} with the specified number of player channels,
	 * sample rate, and buffer size.
	 *
	 * @param playerCount  the number of independent audio channels to mix
	 * @param sampleRate   the audio sample rate in Hz (e.g., 44100)
	 * @param bufferFrames the number of frames in the shared PCM buffer per channel
	 */
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

	/**
	 * Initializes the {@link SampleMixer} by constructing a {@link WaveCell} for each channel
	 * and setting up per-channel amplitude controls. Also initializes the master clock
	 * and sets the initial volume to 1.0. Must be called before audio delivery begins.
	 */
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

	/**
	 * Ensures the mixer is initialized. This can be called before
	 * {@link #deliver(OutputLine)} to allow configuration of output groups
	 * on the underlying {@link Mixer} before the pipeline is built.
	 *
	 * @param bufferSize the output buffer size in frames, used to compute timing
	 */
	public void ensureMixerInitialized(int bufferSize) {
		if (clock == null) {
			initMixer();
			long bufferDuration = bufferSize * 1000L / sampleRate;
			int updates = BufferDefaults.groups * 2;
			waitTime = bufferDuration / updates;
		}
	}

	/**
	 * Starts the background monitor thread that periodically notifies registered time
	 * listeners of the current playback position. Has no effect if the monitor thread
	 * has already been started.
	 */
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

	/**
	 * Returns the {@link SampleMixer} used to combine all channel outputs.
	 *
	 * @return the sample mixer
	 */
	public SampleMixer getMixer() { return mixer; }

	/**
	 * Returns the master {@link TimeCell} tracking the current sample frame position.
	 *
	 * @return the master clock, or {@code null} if the player has not yet been delivered
	 *         to an output line
	 */
	public TimeCell getClock() { return clock; }

	/**
	 * Returns a {@link WaveData} view into the raw PCM buffer for the specified player channel.
	 *
	 * @param player the zero-based channel index
	 * @return a {@link WaveData} wrapping this channel's slice of the shared buffer
	 */
	public WaveData getData(int player) {
		return new WaveData(raw.range(shape(bufferFrames), player * bufferFrames).traverseEach(), sampleRate);
	}

	/**
	 * Loads audio data from the specified file path into the given player channel.
	 * Resets the channel buffer and updates level controls.
	 *
	 * @param player the zero-based channel index to load into
	 * @param file   the path to the WAV file to load
	 */
	public synchronized void load(int player, String file) {
		update(player, file);
		updateLevel();
		initMonitor();
	}

	/**
	 * Loads audio data from the provided {@link WaveData} into the given player channel.
	 * Resets the channel buffer and updates level controls.
	 *
	 * @param player the zero-based channel index to load into
	 * @param data   the {@link WaveData} source; if {@code null} the channel is cleared
	 */
	public synchronized void load(int player, WaveData data) {
		update(player, data);
		updateLevel();
		initMonitor();
	}

	/**
	 * Records the playback duration for a channel based on the number of frames loaded,
	 * clamped to the buffer size, and marks the channel as loaded.
	 *
	 * @param player     the zero-based channel index
	 * @param frameCount the number of frames available in the source data
	 * @return the number of frames that will actually be used (clamped to buffer size)
	 */
	private int updateDuration(int player, int frameCount) {
		int frames = Math.min(frameCount, bufferFrames);
		loaded[player] = true;
		playbackDuration[player] = frames / (double) sampleRate;
		sampleDuration[player] = playbackDuration[player];
		return frames;
	}

	/**
	 * Clears the channel buffer and updates the duration for the specified player channel.
	 *
	 * @param player     the zero-based channel index
	 * @param frameCount the number of source frames to accommodate
	 * @return the number of frames that will be used after clamping
	 */
	private int resetPlayer(int player, int frameCount) {
		int frames = updateDuration(player, frameCount);
		getData(player).getData().clear();
		return frames;
	}

	/**
	 * Copies audio data from the given {@link WaveData} source into the specified channel buffer.
	 * If {@code source} is {@code null} the channel is cleared.
	 *
	 * @param player the zero-based channel index
	 * @param source the source wave data, or {@code null} to clear the channel
	 */
	protected void update(int player, WaveData source) {
		if (source == null) {
			clear(player);
			return;
		}

		if (source.getSampleRate() != sampleRate) {
			source = new DynamicWaveDataProvider(
					"resample", source).get(sampleRate);
		}

		int frames = resetPlayer(player, source.getFrameCount());

		for (int c = 0; c < source.getChannelCount(); c++) {
			getData(player).getChannelData(c).setMem(source.getChannelData(c), 0, frames);
		}
	}

	/**
	 * Reads a WAV file and copies its PCM data into the specified channel buffer.
	 * If {@code file} is {@code null} the channel is cleared.
	 *
	 * @param player the zero-based channel index
	 * @param file   the path to the WAV file to load, or {@code null} to clear
	 */
	protected void update(int player, String file) {
		if (file == null) {
			clear(player);
			return;
		}

		WaveData data = new FileWaveDataProvider(file).get(sampleRate);
		if (data == null) {
			warn("Could not load " + file + " to player");
			return;
		}

		int frames = resetPlayer(player, data.getFrameCount());

		for (int c = 0; c < data.getChannelCount(); c++) {
			getData(player).getChannelData(c).setMem(data.getChannelData(c), 0, frames);
		}
	}

	/**
	 * Marks the specified channel as unloaded and resets its playback duration to zero.
	 *
	 * @param player the zero-based channel index to clear
	 */
	protected void clear(int player) {
		loaded[player] = false;
		playbackDuration[player] = 0.0;
	}

	/**
	 * Recalculates and applies amplitude and loop-duration values for all channels based
	 * on the current playing state, mute flags, and per-channel volume settings. Also
	 * updates the passthrough level on the output line if one is configured.
	 */
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

	/**
	 * Directly writes an amplitude value into the per-channel level buffer.
	 *
	 * @param c the zero-based channel index
	 * @param v the amplitude value to set
	 */
	protected void setLevel(int c, double v) {
		level[c].setMem(0, v);
	}

	/**
	 * Sets the loop duration for the specified channel. When the unified clock is enabled,
	 * the master clock reset point is updated accordingly.
	 *
	 * @param c        the zero-based channel index
	 * @param duration the loop duration in seconds
	 */
	protected void setLoopDuration(int c, double duration) {
		loopDuration[c].setMem(duration);

		if (enableUnifiedClock) {
			clock.setReset(0, (int) (duration * sampleRate));
		}
	}

	/**
	 * Delivers audio output to the specified {@link OutputLine}, creating and returning a
	 * new {@link BufferedOutputScheduler}. The caller is responsible for starting and
	 * managing the scheduler lifecycle.
	 *
	 * @param out the output line to receive the mixed audio
	 * @return a new {@link BufferedOutputScheduler} connected to this player's pipeline
	 */
	public BufferedOutputScheduler deliver(OutputLine out) {
		return deliver(out, null);
	}


	/**
	 * Delivers audio output to the specified {@link AudioLine}, optionally recording from
	 * an additional input record line. Returns a new {@link BufferedOutputScheduler}.
	 *
	 * @param main        the primary audio output line
	 * @param inputRecord an optional line for input recording; may be {@code null}
	 * @return a new {@link BufferedOutputScheduler} connected to this player's pipeline
	 */
	public BufferedOutputScheduler deliver(AudioLine main, OutputLine inputRecord) {
		return deliver((BufferedAudio) main, inputRecord);
	}

	/**
	 * Internal delivery implementation. Initializes the mixer on first call, constructs the
	 * audio processing pipeline, and wraps it in a {@link BufferedOutputScheduler}.
	 *
	 * @param out    the buffered audio output
	 * @param record an optional recording output line; may be {@code null}
	 * @return a new {@link BufferedOutputScheduler}
	 */
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

	/**
	 * Delivers audio to multiple output lines, one per output group configured
	 * on the underlying {@link Mixer}. Each group gets its own
	 * {@link BufferedOutputScheduler} that ticks only that group's cells.
	 *
	 * <p>Since each channel belongs to exactly one output group, the cell
	 * pipelines are non-overlapping and can run on separate threads without
	 * double-ticking any cell. Channel rendering happens exactly once.</p>
	 *
	 * <p>The mixer must have output groups configured via
	 * {@link Mixer#addOutputGroup(String, int...)} and
	 * {@link Mixer#applyOutputGroups()} before calling this method.
	 * Call {@link #ensureMixerInitialized(int)} first if the mixer has
	 * not yet been initialized.</p>
	 *
	 * @param groupOutputLines map from group name to the output line for that group's device
	 * @return map from group name to the scheduler for that group
	 */
	public Map<String, BufferedOutputScheduler> deliverGroups(
			Map<String, OutputLine> groupOutputLines) {
		ensureMixerInitialized(groupOutputLines.values().iterator().next().getBufferSize());

		Map<String, BufferedOutputScheduler> schedulers = new LinkedHashMap<>();

		for (Map.Entry<String, OutputLine> entry : groupOutputLines.entrySet()) {
			String groupName = entry.getKey();
			OutputLine out = entry.getValue();

			CellList groupCells = mixer.getGroupCellList(groupName);
			if (enableUnifiedClock) {
				groupCells = groupCells.addRequirement(clock);
			}

			AudioLineOperation operation = groupCells.toLineOperation();
			BufferedOutputScheduler scheduler = operation.buffer(out);
			schedulers.put(groupName, scheduler);
		}

		return schedulers;
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

	/**
	 * Sets the mute state for the specified player channel. A muted channel produces silence
	 * regardless of its volume setting.
	 *
	 * @param player the zero-based channel index
	 * @param muted  {@code true} to mute the channel, {@code false} to unmute
	 */
	public void setMuted(int player, boolean muted) {
		this.muted[player] = muted;
		updateLevel();
	}

	/**
	 * Overrides the playback duration for the specified channel. This affects loop
	 * boundaries and seek clamping.
	 *
	 * @param player   the zero-based channel index
	 * @param duration the new playback duration in seconds
	 */
	public void setPlaybackDuration(int player, double duration) {
		this.playbackDuration[player] = duration;
		updateLevel();
	}

	/**
	 * Returns the current playback duration for the specified channel in seconds.
	 *
	 * @param player the zero-based channel index
	 * @return the playback duration in seconds
	 */
	public double getPlaybackDuration(int player) {
		return playbackDuration[player];
	}

	/**
	 * Returns the original duration of the loaded sample for the specified channel in seconds,
	 * as determined at load time before any override via {@link #setPlaybackDuration}.
	 *
	 * @param player the zero-based channel index
	 * @return the sample duration in seconds
	 */
	public double getSampleDuration(int player) {
		return sampleDuration[player];
	}

	/**
	 * Returns the current sample frame position from the master clock.
	 *
	 * @return the current frame as a double
	 */
	protected double getFrame() { return clock.getFrame(); }

	/**
	 * Seeks all channels to the specified sample frame position. When the unified clock
	 * is enabled, the master clock is updated directly; otherwise each channel's clock
	 * is updated through the mixer.
	 *
	 * @param frame the target sample frame position
	 */
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