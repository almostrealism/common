/*
 * Copyright 2025 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.audio;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.lifecycle.Lifecycle;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.ReceptorCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.ctx.ContextSpecific;
import org.almostrealism.hardware.ctx.DefaultContextSpecific;
import org.almostrealism.hardware.mem.MemoryDataCopy;
import org.almostrealism.io.Console;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Captures audio output from processing cells and writes to WAV files.
 *
 * <p>WaveOutput acts as a receptor that accumulates audio samples from the processing
 * pipeline and provides methods to export the captured data to WAV files. It supports
 * mono and stereo output with configurable bit depth and sample rate.</p>
 *
 * <h2>Usage with CellList</h2>
 * <pre>{@code
 * WaveOutput output = new WaveOutput(new File("output.wav"), 24);
 *
 * // Connect to audio processing chain
 * cells.w(output.getWriter(0));  // Left channel
 * cells.w(output.getWriter(1));  // Right channel (if stereo)
 *
 * // Process audio
 * cells.sec(30).get().run();
 *
 * // Write to file
 * output.write().get().run();
 * }</pre>
 *
 * <h2>In-Memory Capture</h2>
 * <pre>{@code
 * // Capture without file output
 * WaveOutput output = new WaveOutput(maxFrames);
 *
 * // Process audio...
 *
 * // Export to PackedCollection
 * PackedCollection captured = new PackedCollection(output.getFrameCount());
 * output.export(0, captured).get().run();
 * }</pre>
 *
 * <h2>Timeline Support</h2>
 * <p>WaveOutput maintains a shared timeline collection that provides time values
 * for each frame, useful for time-based effects and modulation.</p>
 *
 * @see WavFile
 * @see WaveData
 * @see Receptor
 */
public class WaveOutput implements Lifecycle, Destroyable, CodeFeatures {
	/** When true, logs timeline generation progress to the console. */
	public static boolean enableVerbose = false;

	/** Default number of frames in the shared timeline (230 seconds at the default sample rate). */
	public static int defaultTimelineFrames = OutputLine.sampleRate * 230;

	/** Context-specific shared timeline PackedCollection providing time values per frame. */
	public static ContextSpecific<PackedCollection> timeline;

	/** Whether this output writes in circular (wrapping) mode. */
	private boolean circular = false;

	static {
		Supplier<PackedCollection> timelineSupply = () -> {
			if (enableVerbose) {
				CellFeatures.console.features(WaveOutput.class).log("Generating timeline");
			}

			PackedCollection data = new PackedCollection(defaultTimelineFrames).traverseEach();
			Ops.o().integers(0, defaultTimelineFrames).divide(Ops.o().c(OutputLine.sampleRate)).into(data).evaluate();

			if (enableVerbose) {
				CellFeatures.console.features(WaveOutput.class).log("Finished generating timeline");
			}

			return data;
		};

		timeline = new DefaultContextSpecific<>(timelineSupply, PackedCollection::destroy);
		timeline.init();
	}

	/** Supplier for the destination WAV file, or null for in-memory capture. */
	private final Supplier<File> file;

	/** Bit depth for WAV file encoding (typically 16 or 24). */
	private final int bits;

	/** Sample rate in Hz used when writing the WAV file. */
	private final long sampleRate;

	/** The open WAV file being written to during a write operation. */
	private WavFile wav;

	/** Channel audio data buffers, one per channel. */
	private List<CollectionProducer> data;

	/** Per-channel writer receptors that accept push data from the processing pipeline. */
	private List<Writer> channels;

	/** Creates a WaveOutput with no destination file (in-memory capture, default timeline size). */
	public WaveOutput() { this((File) null); }

	/**
	 * Creates an in-memory WaveOutput with the specified buffer size.
	 *
	 * @param maxFrames maximum number of frames the buffer can hold
	 */
	public WaveOutput(int maxFrames) {
		this(null, 24, maxFrames, false);
	}

	/**
	 * Creates a mono WaveOutput targeting the specified file with 24-bit depth.
	 *
	 * @param f destination WAV file
	 */
	public WaveOutput(File f) {
		this(f, 24);
	}

	/**
	 * Creates a mono WaveOutput targeting the specified file.
	 *
	 * @param f    destination WAV file
	 * @param bits bit depth for encoding
	 */
	public WaveOutput(File f, int bits) {
		this(() -> f, bits);
	}

	/**
	 * Creates a mono WaveOutput with a file supplier and the default timeline size.
	 *
	 * @param f    supplier producing the destination WAV file
	 * @param bits bit depth for encoding
	 */
	public WaveOutput(Supplier<File> f, int bits) {
		this(f, bits, -1, false);
	}

	/**
	 * Creates a WaveOutput with a file supplier, configurable channel count, and default timeline size.
	 *
	 * @param f      supplier producing the destination WAV file
	 * @param bits   bit depth for encoding
	 * @param stereo true for stereo (2-channel) output; false for mono
	 */
	public WaveOutput(Supplier<File> f, int bits, boolean stereo) {
		this(f, bits, -1, stereo);
	}

	/**
	 * Creates a WaveOutput with configurable channel count and buffer size using the default sample rate.
	 *
	 * @param f         supplier producing the destination WAV file
	 * @param bits      bit depth for encoding
	 * @param maxFrames maximum number of frames; use -1 for the default timeline size
	 * @param stereo    true for stereo (2-channel) output; false for mono
	 */
	public WaveOutput(Supplier<File> f, int bits, int maxFrames, boolean stereo) {
		this(f, bits, OutputLine.sampleRate, maxFrames, stereo);
	}

	/**
	 * Creates a WaveOutput with full control over channel count, buffer size, and sample rate.
	 *
	 * @param f          supplier producing the destination WAV file
	 * @param bits       bit depth for encoding
	 * @param sampleRate sample rate in Hz
	 * @param maxFrames  maximum number of frames; use -1 for the default timeline size
	 * @param stereo     true for stereo (2-channel) output; false for mono
	 */
	public WaveOutput(Supplier<File> f, int bits, long sampleRate, int maxFrames, boolean stereo) {
		this(f, bits, new WaveData(
				stereo ? 2 : 1,
				maxFrames <= 0 ? defaultTimelineFrames : maxFrames,
				Math.toIntExact(sampleRate)));
	}

	/**
	 * Creates a WaveOutput backed by an existing PackedCollection (mono, default sample rate).
	 *
	 * @param data pre-allocated collection to use as the audio buffer
	 */
	public WaveOutput(PackedCollection data) {
		this(null, 24, new WaveData(data, OutputLine.sampleRate));
	}

	/**
	 * Creates a WaveOutput backed by a producer of audio data (mono, default sample rate).
	 *
	 * @param data producer that supplies the audio data
	 */
	public WaveOutput(Producer<PackedCollection> data) {
		this(null, 24, OutputLine.sampleRate, List.of(data));
	}

	/**
	 * Creates a WaveOutput backed by a WaveData object, inheriting its channel layout and sample rate.
	 *
	 * @param f    supplier producing the destination WAV file, or null for in-memory capture
	 * @param bits bit depth for encoding
	 * @param data WaveData providing the underlying channel buffers and sample rate
	 */
	public WaveOutput(Supplier<File> f, int bits, WaveData data) {
		this(f, bits, data.getSampleRate(),
				data.getChannelCount() > 1 ? List.of(
						CollectionFeatures.getInstance().p(data.getChannelData(0)),
						CollectionFeatures.getInstance().p(data.getChannelData(1))) :
				List.of(CollectionFeatures.getInstance().p(data.getChannelData(0))));
	}

	/**
	 * Primary constructor used by all other constructors.
	 *
	 * @param f          supplier producing the destination WAV file, or null for in-memory capture
	 * @param bits       bit depth for encoding
	 * @param sampleRate sample rate in Hz
	 * @param data       list of per-channel audio data producers
	 */
	public WaveOutput(Supplier<File> f, int bits, long sampleRate, List<Producer<PackedCollection>> data) {
		this.file = f;
		this.bits = bits;
		this.sampleRate = sampleRate;
		this.data = data.stream()
				.map(this::c)
				.map(CollectionProducer::traverseEach)
				.toList();
		initChannelWriters();
	}

	/** Initializes per-channel Writer receptors for the current channel count. */
	protected void initChannelWriters() {
		this.channels = IntStream.range(0, getChannelCount())
							.mapToObj(Writer::new)
							.collect(Collectors.toList());
	}

	/**
	 * Returns the write cursor for the specified channel, indicating how many frames have been written.
	 *
	 * @param channel channel index
	 * @return single-element PackedCollection holding the current frame cursor value
	 */
	public PackedCollection getCursor(int channel) {
		return channels.get(channel).getCursor();
	}

	/** Returns the number of channels (1 for mono, 2 for stereo). */
	public int getChannelCount() { return data.size(); }

	/**
	 * Enables circular buffer mode where the cursor wraps at buffer size.
	 * Use this for continuous audio processing with BufferedOutputScheduler.
	 */
	public void setCircular(boolean circular) { this.circular = circular; }

	/** Returns true if this output is in circular buffer mode. */
	public boolean isCircular() { return circular; }

	/**
	 * Returns the number of frames written across all channels (minimum across channels).
	 *
	 * @return number of frames currently captured
	 */
	public int getFrameCount() {
		return channels.stream()
				.mapToInt(Writer::getFrameCount)
				.min().orElse(0);
	}

	/**
	 * Returns the Receptor for the specified channel, suitable for use with {@code cells.w()}.
	 *
	 * @param channel channel index
	 * @return the Writer receptor for the channel
	 */
	public Receptor<PackedCollection> getWriter(int channel) {
		return channels.get(channel);
	}

	/**
	 * Returns a ReceptorCell wrapping the writer for the specified channel.
	 *
	 * @param channel channel index
	 * @return ReceptorCell that routes pushed data to this output channel
	 */
	public ReceptorCell<PackedCollection> getWriterCell(int channel) {
		return new ReceptorCell<>(getWriter(channel));
	}

	/**
	 * Returns the CollectionProducer for the specified channel's audio buffer.
	 *
	 * @param channel channel index
	 * @return the channel data producer, or null if the channel index is out of range
	 */
	public CollectionProducer getChannelData(int channel) {
		return channel < data.size() ? data.get(channel) : null;
	}

	/**
	 * Creates a runnable that copies captured audio from a channel into the destination collection.
	 *
	 * @param channel     channel index to export
	 * @param destination target PackedCollection to receive the audio data
	 * @return supplier of a Runnable that performs the copy when executed
	 */
	public Supplier<Runnable> export(int channel, PackedCollection destination) {
		TraversalPolicy shape = shape(getChannelData(channel));
		int len = destination.getMemLength();
		if (shape.getTotalSize() > 1 && shape.getTotalSize() > len)
			len = shape.getTotalSize();

		Evaluable<PackedCollection> d = getChannelData(channel).get();
		return new MemoryDataCopy("WaveOutput Export", d::evaluate, () -> destination, len);
	}

	/**
	 * Creates a runnable that writes all captured audio to the destination WAV file.
	 *
	 * <p>The write is performed in a lazy, two-step manner: the outer supplier compiles
	 * the evaluables, and the inner Runnable performs the actual file I/O when executed.</p>
	 *
	 * @return supplier of a Runnable that writes WAV data to the configured file
	 */
	public Supplier<Runnable> write() {
		// TODO  Write frames in larger batches than 1
		return () -> {
			Evaluable<PackedCollection> left = getChannelData(0).get();
			Evaluable<PackedCollection> right = getChannelData(1) == null ? null : getChannelData(1).get();

			return () -> {
				PackedCollection l = left.evaluate();
				PackedCollection r = right == null ? null : right.evaluate();
				int frames = getFrameCount();

				if (frames > 0) {
					// log("Writing " + frames + " frames");
				} else {
					log("No frames to write");
					return;
				}

				long start = System.currentTimeMillis();

				try {
					File f = file.get();
					if (f == null) {
						warn("No destination file provided");
						return;
					}

					this.wav = WavFile.newWavFile(f, 2, frames, bits, sampleRate);
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}

				double[] framesLeft = l.toArray(0, frames);
				double[] framesRight = r == null ? framesLeft : r.toArray(0, frames);

				for (int i = 0; i < frames; i++) {
					try {
						wav.writeFrames(new double[][]
								{{framesLeft[i]}, {framesRight[i]}}, 1);
					} catch (IOException e) {
						e.printStackTrace();
					}
				}

				try {
					wav.close();
				} catch (IOException e) {
					e.printStackTrace();
					return;
				}

				if (enableVerbose)
					log(" Wrote " + frames + " frames in " + (System.currentTimeMillis() - start) + " msec");
			};
		};
	}

	/**
	 * Creates a runnable that writes the captured audio for a single channel to a CSV file.
	 *
	 * <p>Each line in the CSV file contains a frame index and the corresponding sample value,
	 * separated by a comma.</p>
	 *
	 * @param channel channel index to export
	 * @param file    destination CSV file
	 * @return supplier of a Runnable that writes the CSV data when executed
	 */
	public Supplier<Runnable> writeCsv(int channel, File file) {
		return () -> {
			Evaluable<PackedCollection> d = getChannelData(channel).get();

			return () -> {
				PackedCollection o = d.evaluate();
				StringBuffer buf = new StringBuffer();

				int frames = getFrameCount();

				for (int i = 0; i < frames; i++) {
					double value = o.toDouble(i);
					buf.append(i + "," + value + "\n");
				}

				try (PrintWriter out = new PrintWriter(new OutputStreamWriter(new FileOutputStream(file)))) {
					out.println(buf);
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			};
		};
	}

	@Override
	public void reset() {
		channels.forEach(Writer::reset);
	}

	@Override
	public void destroy() {
		if (channels != null) {
			channels.forEach(Writer::destroy);
			channels = null;
		}

		if (data != null) {
			data.forEach(CollectionProducer::destroy);
			data = null;
		}
	}

	@Override
	public Console console() { return CellFeatures.console; }

	/**
	 * A Receptor for a single channel of a WaveOutput that appends incoming audio frames
	 * to the channel's buffer and advances the write cursor.
	 */
	protected class Writer implements Receptor<PackedCollection>, Lifecycle, Destroyable {
		/** Index of the channel this writer services. */
		private final int channel;

		/** Single-element collection holding the current write cursor (frame index). */
		private PackedCollection cursor;

		/**
		 * Creates a Writer for the specified channel index.
		 *
		 * @param channel channel index into the parent WaveOutput's data list
		 */
		public Writer(int channel) {
			this.channel = channel;
			this.cursor = new PackedCollection(1);
		}

		/** Returns the write cursor collection holding the current frame index. */
		public PackedCollection getCursor() { return cursor; }

		/**
		 * Returns the number of complete frames written to this channel.
		 *
		 * @return frame count (cursor value minus 1)
		 */
		public int getFrameCount() {
			return (int) cursor.toDouble(0) - 1;
		}

		@Override
		public Supplier<Runnable> push(Producer<PackedCollection> protein) {
			String description = "WaveOutput Push";
			if (file != null) description += " (to file)";
			OperationList push = new OperationList(description);

			if (shape(protein).getSize() == 2) {
				protein = c(protein, 0);
			}

			Producer slot = c(shape(1), getChannelData(channel), p(cursor));

			push.add(a("WaveOutput Insert", slot, protein));

			if (circular) {
				int bufferSize = shape(getChannelData(channel)).getTotalSize();
				push.add(a("WaveOutput Cursor Increment (circular)",
						cp(cursor), mod(cp(cursor).add(1), c(bufferSize))));
			} else {
				push.add(a("WaveOutput Cursor Increment", cp(cursor), cp(cursor).add(1)));
			}
			return push;
		}

		@Override
		public void reset() {
			Lifecycle.super.reset();
			cursor.setMem(0.0);
		}

		@Override
		public void destroy() {
			if (cursor != null) {
				cursor.destroy();
				cursor = null;
			}
		}
	}
}
