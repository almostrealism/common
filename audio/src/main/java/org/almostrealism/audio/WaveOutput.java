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

public class WaveOutput implements Lifecycle, Destroyable, CodeFeatures {
	public static boolean enableVerbose = false;

	public static int defaultTimelineFrames = OutputLine.sampleRate * 230;

	public static ContextSpecific<PackedCollection> timeline;

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

	private final Supplier<File> file;
	private final int bits;
	private final long sampleRate;

	private WavFile wav;
	private List<CollectionProducer> data;
	private List<Writer> channels;

	public WaveOutput() { this((File) null); }

	public WaveOutput(int maxFrames) {
		this(null, 24, maxFrames, false);
	}

	public WaveOutput(File f) {
		this(f, 24);
	}

	public WaveOutput(File f, int bits) {
		this(() -> f, bits);
	}

	public WaveOutput(Supplier<File> f, int bits) {
		this(f, bits, -1, false);
	}

	public WaveOutput(Supplier<File> f, int bits, boolean stereo) {
		this(f, bits, -1, stereo);
	}

	public WaveOutput(Supplier<File> f, int bits, int maxFrames, boolean stereo) {
		this(f, bits, OutputLine.sampleRate, maxFrames, stereo);
	}

	public WaveOutput(Supplier<File> f, int bits, long sampleRate, int maxFrames, boolean stereo) {
		this(f, bits, new WaveData(
				stereo ? 2 : 1,
				maxFrames <= 0 ? defaultTimelineFrames : maxFrames,
				Math.toIntExact(sampleRate)));
	}

	public WaveOutput(PackedCollection data) {
		this(null, 24, new WaveData(data, OutputLine.sampleRate));
	}

	public WaveOutput(Producer<PackedCollection> data) {
		this(null, 24, OutputLine.sampleRate, List.of(data));
	}

	public WaveOutput(Supplier<File> f, int bits, WaveData data) {
		this(f, bits, data.getSampleRate(),
				data.getChannelCount() > 1 ? List.of(
						CollectionFeatures.getInstance().p(data.getChannelData(0)),
						CollectionFeatures.getInstance().p(data.getChannelData(1))) :
				List.of(CollectionFeatures.getInstance().p(data.getChannelData(0))));
	}

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

	protected void initChannelWriters() {
		this.channels = IntStream.range(0, getChannelCount())
							.mapToObj(Writer::new)
							.collect(Collectors.toList());
	}

	public PackedCollection getCursor(int channel) {
		return channels.get(channel).getCursor();
	}

	public int getChannelCount() { return data.size(); }

	public int getFrameCount() {
		return channels.stream()
				.mapToInt(Writer::getFrameCount)
				.min().orElse(0);
	}

	public Receptor<PackedCollection> getWriter(int channel) {
		return channels.get(channel);
	}

	public ReceptorCell<PackedCollection> getWriterCell(int channel) {
		return new ReceptorCell<>(getWriter(channel));
	}

	public CollectionProducer getChannelData(int channel) {
		return channel < data.size() ? data.get(channel) : null;
	}

	public Supplier<Runnable> export(int channel, PackedCollection destination) {
		TraversalPolicy shape = shape(getChannelData(channel));
		int len = destination.getMemLength();
		if (shape.getTotalSize() > 1 && shape.getTotalSize() > len)
			len = shape.getTotalSize();

		Evaluable<PackedCollection> d = getChannelData(channel).get();
		return new MemoryDataCopy("WaveOutput Export", d::evaluate, () -> destination, len);
	}

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

	protected class Writer implements Receptor<PackedCollection>, Lifecycle, Destroyable {
		private final int channel;
		private PackedCollection cursor;

		public Writer(int channel) {
			this.channel = channel;
			this.cursor = new PackedCollection(1);
		}

		public PackedCollection getCursor() { return cursor; }

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
			push.add(a("WaveOutput Cursor Increment", cp(cursor), cp(cursor).add(1)));
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
