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

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Factor;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.Ops;
import org.almostrealism.audio.computations.DefaultEnvelopeComputation;
import org.almostrealism.audio.data.PolymorphicAudioData;
import org.almostrealism.audio.data.WaveData;
import org.almostrealism.audio.filter.AudioPassFilter;
import org.almostrealism.audio.line.OutputLine;
import org.almostrealism.audio.sequence.ValueSequenceCell;
import org.almostrealism.audio.sources.SineWaveCell;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.AdjustableDelayCell;
import org.almostrealism.graph.Cell;
import org.almostrealism.graph.CellAdapter;
import org.almostrealism.graph.FilteredCell;
import org.almostrealism.graph.MultiCell;
import org.almostrealism.graph.Receptor;
import org.almostrealism.graph.ReceptorCell;
import org.almostrealism.graph.SummationCell;
import org.almostrealism.graph.temporal.CollectionTemporalCellAdapter;
import org.almostrealism.graph.temporal.WaveCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.Gene;
import org.almostrealism.heredity.HeredityFeatures;
import org.almostrealism.heredity.IdentityFactor;
import org.almostrealism.heredity.ScaleFactor;
import org.almostrealism.io.Console;
import org.almostrealism.time.Frequency;
import org.almostrealism.time.Temporal;
import org.almostrealism.time.TemporalFeatures;
import org.almostrealism.time.TemporalRunner;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Primary interface for building audio processing chains in the Almost Realism framework.
 *
 * <p>CellFeatures provides a fluent API for creating and composing audio cells, filters,
 * delays, and output destinations. It extends {@link HeredityFeatures} for genetic
 * algorithm support, {@link TemporalFeatures} for time-based operations, and
 * {@link CodeFeatures} for code generation capabilities.</p>
 *
 * <h2>Key Methods</h2>
 * <ul>
 *   <li>{@code w()} - Create wave cells from audio files</li>
 *   <li>{@code d()} - Add delay effects</li>
 *   <li>{@code f()} - Add filters (high-pass, low-pass)</li>
 *   <li>{@code hp()}, {@code lp()} - Create high-pass and low-pass filters</li>
 *   <li>{@code silence()} - Create silent audio source</li>
 *   <li>{@code grid()}, {@code gr()} - Create processing grids for parallel audio</li>
 * </ul>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * public class AudioProcessor implements CellFeatures {
 *     public void process() {
 *         // Load audio, apply high-pass filter, add delay, output to file
 *         CellList chain = w(0, "input.wav")
 *             .f(i -> hp(c(200), scalar(0.1)))  // High-pass at 200Hz
 *             .d(i -> _100ms())                  // 100ms delay
 *             .o(i -> new File("output.wav"));
 *
 *         chain.sec(30).get().run();  // Process 30 seconds
 *     }
 * }
 * }</pre>
 *
 * <h2>Cell Lifecycle</h2>
 * <p>Audio cells follow a setup/tick pattern:</p>
 * <ol>
 *   <li>Setup phase: Initialize cell state (called once)</li>
 *   <li>Tick phase: Process one sample (called per sample)</li>
 * </ol>
 *
 * @see CellList
 * @see SamplingFeatures
 * @see org.almostrealism.audio.filter.AudioPassFilter
 * @see org.almostrealism.graph.Cell
 */
public interface CellFeatures extends HeredityFeatures, TemporalFeatures, CodeFeatures {
	/** Shared console for logging audio cell activity. */
	Console console = Console.root().child();

	/**
	 * Creates a receptor that accumulates audio data into one or more destination producers.
	 *
	 * @param destinations one or more destination producers to receive audio data
	 * @return receptor that pushes audio to all destinations
	 */
	default Receptor<PackedCollection> a(Producer<PackedCollection>... destinations) {
		if (destinations.length == 1) {
			return protein -> a(1, destinations[0], protein);
		} else {
			return protein -> Stream.of(destinations).map(v -> a(1, v, protein)).collect(OperationList.collector());
		}
	}

	/**
	 * Creates a silent audio cell that outputs a constant zero signal.
	 *
	 * @return a temporal cell adapter producing silence
	 */
	default CollectionTemporalCellAdapter silent() {
		return CollectionTemporalCellAdapter.from(c(0.0));
	}

	/**
	 * Creates a CellList containing a single silent cell.
	 *
	 * @return a CellList with one zero-output cell
	 */
	default CellList silence() {
		CellList cells = new CellList();
		cells.addRoot(silent());
		return cells;
	}

	/**
	 * Creates a CellList from an array of pre-built audio cells.
	 *
	 * @param cells the audio cells to add as roots
	 * @return a CellList containing the provided cells
	 */
	default CellList cells(Cell<PackedCollection>... cells) {
		return cells(cells.length, i -> cells[i]);
	}

	/**
	 * Creates a CellList by building cells using a factory function.
	 *
	 * @param count  the number of cells to create
	 * @param cells  factory function mapping index to an audio cell
	 * @return a CellList containing the created cells
	 */
	default CellList cells(int count, IntFunction<Cell<PackedCollection>> cells) {
		CellList c = new CellList();
		IntStream.range(0, count).mapToObj(cells).forEach(c::addRoot);
		return c;
	}

	/**
	 * Creates a CellList by combining multiple existing CellLists.
	 *
	 * @param cells the CellLists to combine
	 * @return a CellList aggregating all provided lists
	 */
	default CellList cells(CellList... cells) {
		return all(cells.length, i -> cells[i]);
	}

	/**
	 * Creates a CellList that aggregates multiple CellLists into a single flat container.
	 *
	 * @param count the number of CellLists to aggregate
	 * @param cells factory function mapping index to a CellList
	 * @return a CellList containing all cells from all sub-lists
	 */
	default CellList all(int count, IntFunction<CellList> cells) {
		CellList[] all = new CellList[count];
		IntStream.range(0, count).forEach(i -> all[i] = cells.apply(i));

		CellList result = new CellList(all);
		IntStream.range(0, all.length).mapToObj(i -> all[i]).flatMap(Collection::stream).forEach(result::add);
		return result;
	}

	/**
	 * Maps each cell in a CellList to a new destination cell, connecting them as receptors.
	 *
	 * @param cells the source CellList
	 * @param dest  factory function mapping index to a destination cell
	 * @return a new CellList with the destination cells appended
	 */
	default CellList map(CellList cells, IntFunction<Cell<PackedCollection>> dest) {
		CellList c = new CellList(cells);

		IntStream.range(0, cells.size()).forEach(i -> {
			Cell<PackedCollection> d = dest.apply(i);
			cells.get(i).setReceptor(d);
			c.add(d);
		});

		return c;
	}

	/**
	 * Splits each cell in a CellList into multiple parallel branches, one per destination factory.
	 *
	 * @param cells the source CellList to branch
	 * @param dest  destination factory functions, one per branch
	 * @return an array of CellLists, each representing one branch
	 */
	default CellList[] branch(CellList cells, IntFunction<Cell<PackedCollection>>... dest) {
		CellList[] c = IntStream.range(0, dest.length).mapToObj(i -> new CellList(cells)).toArray(CellList[]::new);

		IntStream.range(0, cells.size()).forEach(i -> {
			Cell<PackedCollection>[] d = new Cell[dest.length];

			IntStream.range(0, dest.length).forEach(j -> {
				d[j] = dest[j].apply(i);
				c[j].add(d[j]);
			});

			cells.get(i).setReceptor(Receptor.to(d));
		});

		return c;
	}

	/**
	 * Creates WaveCells from WaveData using the default channel and a fresh PolymorphicAudioData instance.
	 *
	 * @param channel the audio channel index to read from each WaveData
	 * @param waves   the wave data to create cells from
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, WaveData... waves) {
		return w(channel, (Supplier) PolymorphicAudioData::new, waves);
	}

	/**
	 * Creates WaveCells from WaveData using a custom data supplier.
	 *
	 * @param channel the audio channel index to read from each WaveData
	 * @param data    supplier for PolymorphicAudioData instances
	 * @param waves   the wave data to create cells from
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Supplier<PolymorphicAudioData> data, WaveData... waves) {
		return w(channel, data, null, null, waves);
	}

	/**
	 * Creates sine-wave cells for each frequency in the collection.
	 *
	 * @param frequencies the frequencies to synthesize
	 * @return a CellList of SineWaveCells
	 */
	default CellList w(Collection<Frequency> frequencies) {
		return w(PolymorphicAudioData::new, frequencies);
	}

	/**
	 * Creates sine-wave cells for each frequency in the collection using a custom data supplier.
	 *
	 * @param data        supplier for PolymorphicAudioData instances
	 * @param frequencies the frequencies to synthesize
	 * @return a CellList of SineWaveCells
	 */
	default CellList w(Supplier<PolymorphicAudioData> data, Collection<Frequency> frequencies) {
		return w(data, frequencies.stream());
	}

	/**
	 * Creates sine-wave cells for each frequency using the default data supplier.
	 *
	 * @param frequencies the frequencies to synthesize
	 * @return a CellList of SineWaveCells
	 */
	default CellList w(Frequency... frequencies) {
		return w(PolymorphicAudioData::new, frequencies);
	}

	/**
	 * Creates sine-wave cells for each frequency using a custom data supplier.
	 *
	 * @param data        supplier for PolymorphicAudioData instances
	 * @param frequencies the frequencies to synthesize
	 * @return a CellList of SineWaveCells
	 */
	default CellList w(Supplier<PolymorphicAudioData> data, Frequency... frequencies) {
		return w(data, Stream.of(frequencies));
	}

	/**
	 * Creates sine-wave cells for each frequency in the stream using a custom data supplier.
	 *
	 * @param data        supplier for PolymorphicAudioData instances
	 * @param frequencies stream of frequencies to synthesize
	 * @return a CellList of SineWaveCells
	 */
	default CellList w(Supplier<PolymorphicAudioData> data, Stream<Frequency> frequencies) {
		CellList cells = new CellList();
		frequencies.map(f -> {
			SineWaveCell c = new SineWaveCell(data.get());
			c.setFreq(f.asHertz());
			c.setNoteLength(500);
			c.setAmplitude(0.5);
			c.setEnvelope(DefaultEnvelopeComputation::new);
			return c;
		}).forEach(cells::addRoot);
		return cells;
	}

	/**
	 * Creates WaveCells by loading audio files at the given paths.
	 *
	 * @param channel the audio channel to read from each file
	 * @param path    file paths to load
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, String... path) {
		return w(channel, Stream.of(path).map(File::new).toArray(File[]::new));
	}

	/**
	 * Creates looping WaveCells by loading audio files at the given paths.
	 *
	 * @param channel the audio channel to read from each file
	 * @param repeat  producer that controls looping behavior
	 * @param path    file paths to load
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Producer<PackedCollection> repeat, String... path) {
		return w(channel, null, repeat, path);
	}

	/**
	 * Creates WaveCells with offset and repeat control by loading audio files at the given paths.
	 *
	 * @param channel the audio channel to read from each file
	 * @param offset  producer specifying playback start offset
	 * @param repeat  producer that controls looping behavior
	 * @param path    file paths to load
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Producer<PackedCollection> offset, Producer<PackedCollection> repeat, String... path) {
		return w(channel, offset, repeat, Stream.of(path).map(File::new).toArray(File[]::new));
	}

	/**
	 * Creates WaveCells by loading audio files.
	 *
	 * @param channel the audio channel to read from each file
	 * @param files   the audio files to load
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, File... files) {
		return w(channel, (Supplier<PolymorphicAudioData>) PolymorphicAudioData::new, files);
	}

	/**
	 * Creates looping WaveCells by loading audio files.
	 *
	 * @param channel the audio channel to read from each file
	 * @param repeat  producer that controls looping behavior
	 * @param files   the audio files to load
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Producer<PackedCollection> repeat, File... files) {
		return w(channel, null, repeat, files);
	}

	/**
	 * Creates WaveCells with offset and repeat control by loading audio files.
	 *
	 * @param channel the audio channel to read from each file
	 * @param offset  producer specifying playback start offset
	 * @param repeat  producer that controls looping behavior
	 * @param files   the audio files to load
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Producer<PackedCollection> offset, Producer<PackedCollection> repeat, File... files) {
		return w(channel, PolymorphicAudioData::new, offset, repeat, files);
	}

	/**
	 * Creates looping WaveCells from WaveData.
	 *
	 * @param channel the audio channel index
	 * @param repeat  producer that controls looping behavior
	 * @param data    the WaveData sources
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Producer<PackedCollection> repeat, WaveData... data) {
		return w(channel, null, repeat, data);
	}

	/**
	 * Creates WaveCells with offset and repeat control from WaveData.
	 *
	 * @param channel the audio channel index
	 * @param offset  producer specifying playback start offset
	 * @param repeat  producer that controls looping behavior
	 * @param data    the WaveData sources
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Producer<PackedCollection> offset, Producer<PackedCollection> repeat, WaveData... data) {
		return w(channel, PolymorphicAudioData::new, offset, repeat, data);
	}

	/**
	 * Creates WaveCells from audio files using a custom data supplier.
	 *
	 * @param channel the audio channel to read from each file
	 * @param data    supplier for PolymorphicAudioData instances
	 * @param files   the audio files to load
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Supplier<PolymorphicAudioData> data, File... files) {
		return w(channel, data, null, null, files);
	}

	/**
	 * Creates WaveCells with full playback control from audio files.
	 *
	 * @param channel the audio channel to read from each file
	 * @param data    supplier for PolymorphicAudioData instances
	 * @param offset  producer specifying playback start offset, or null for none
	 * @param repeat  producer that controls looping behavior, or null for none
	 * @param files   the audio files to load
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Supplier<PolymorphicAudioData> data, Producer<PackedCollection> offset, Producer<PackedCollection> repeat, File... files) {
		CellList cells = new CellList();
		Stream.of(files).map(f -> {
			try {
				return WaveData.load(f).toCell(channel, 1.0, offset, repeat).apply(data.get());
			} catch (IOException e) {
				e.printStackTrace();
				return silence().get(0);
			}
		}).forEach(cells::addRoot);
		return cells;
	}

	/**
	 * Creates WaveCells from raw wave producers with explicit sample rate and frame count.
	 *
	 * @param data       supplier for PolymorphicAudioData instances
	 * @param sampleRate the sample rate in Hz
	 * @param frames     total number of frames in the wave data
	 * @param offset     producer specifying playback start offset, or null for none
	 * @param repeat     producer that controls looping behavior, or null for none
	 * @param waves      raw wave data producers
	 * @return a CellList of WaveCells
	 */
	default CellList w(Supplier<PolymorphicAudioData> data, int sampleRate, int frames,
					   Producer<PackedCollection> offset,
					   Producer<PackedCollection> repeat,
					   Producer<PackedCollection>... waves) {
		CellList cells = new CellList();
		Stream.of(waves)
				.map(w ->
						new WaveCell(data.get(), w, sampleRate, 1.0,
									offset == null ? null : Ops.o().c(offset),
									repeat == null ? null : Ops.o().c(repeat),
									Ops.o().c(0.0), Ops.o().c(frames))).forEach(cells::addRoot);
		return cells;
	}

	/**
	 * Creates WaveCells with external frame control for real-time streaming.
	 *
	 * <p>Unlike the other {@code w()} methods which create WaveCells with internal
	 * clocks, this method creates WaveCells that use an external frame producer.
	 * This is essential for real-time rendering where the frame position must be
	 * controlled per-buffer rather than globally.</p>
	 *
	 * @param data   supplier for polymorphic audio data storage
	 * @param frames number of frames in the wave buffer
	 * @param frame  external frame position producer (values 0 to frames-1)
	 * @param waves  wave data producers to create cells for
	 * @return CellList containing WaveCells with external frame control
	 */
	default CellList w(Supplier<PolymorphicAudioData> data, int frames,
					   Producer<PackedCollection> frame,
					   Producer<PackedCollection>... waves) {
		CellList cells = new CellList();
		Stream.of(waves)
				.map(wav -> new WaveCell(data.get(), wav, 1.0, frame,
						Ops.o().c(0.0), Ops.o().c(frames)))
				.forEach(cells::addRoot);
		return cells;
	}

	/**
	 * Creates WaveCells from WaveData with full playback control.
	 *
	 * @param channel the audio channel index
	 * @param data    supplier for PolymorphicAudioData instances
	 * @param offset  producer specifying playback start offset, or null for none
	 * @param repeat  producer that controls looping behavior, or null for none
	 * @param waves   the WaveData sources
	 * @return a CellList of WaveCells
	 */
	default CellList w(int channel, Supplier<PolymorphicAudioData> data, Producer<PackedCollection> offset, Producer<PackedCollection> repeat, WaveData... waves) {
		CellList cells = new CellList();
		Stream.of(waves)
				.map(w -> w.toCell(channel, 1.0, offset, repeat).apply(data.get()))
				.forEach(cells::addRoot);
		return cells;
	}

	/**
	 * Creates a polyphonic CellList with frequency-based choices using a shared data supplier.
	 *
	 * @param count    the number of polyphonic voices
	 * @param data     supplier for PolymorphicAudioData shared across all voices
	 * @param decision function mapping voice index to the decision producer
	 * @param choices  frequencies available as synthesis choices
	 * @return a CellList of PolymorphicAudioCells
	 */
	default CellList poly(int count, Supplier<PolymorphicAudioData> data, IntFunction<CollectionProducer> decision, Frequency... choices) {
		return poly(count, data, decision, Stream.of(choices)
				.map(f -> (Function<PolymorphicAudioData, CollectionTemporalCellAdapter>) d -> (CollectionTemporalCellAdapter) w(data, f).get(0)).
				toArray(Function[]::new));
	}

	/**
	 * Creates a polyphonic CellList with custom cell factories and a shared data supplier.
	 *
	 * @param count    the number of polyphonic voices
	 * @param data     supplier for PolymorphicAudioData shared across all voices
	 * @param decision function mapping voice index to the decision producer
	 * @param choices  factory functions that build cell adapters from PolymorphicAudioData
	 * @return a CellList of PolymorphicAudioCells
	 */
	default CellList poly(int count, Supplier<PolymorphicAudioData> data, IntFunction<CollectionProducer> decision,
						  Function<PolymorphicAudioData, CollectionTemporalCellAdapter>... choices) {
		return poly(count, i -> data.get(), decision, choices);
	}

	/**
	 * Creates a polyphonic CellList with custom cell factories and per-voice data allocation.
	 *
	 * @param count    the number of polyphonic voices
	 * @param data     function mapping voice index to its PolymorphicAudioData
	 * @param decision function mapping voice index to the decision producer
	 * @param choices  factory functions that build cell adapters from PolymorphicAudioData
	 * @return a CellList of PolymorphicAudioCells
	 */
	default CellList poly(int count, IntFunction<PolymorphicAudioData> data, IntFunction<CollectionProducer> decision,
						  Function<PolymorphicAudioData, CollectionTemporalCellAdapter>... choices) {
		CellList cells = new CellList();
		IntStream.range(0, count).mapToObj(i -> new PolymorphicAudioCell(data.apply(i), decision.apply(i), choices)).forEach(cells::addRoot);
		return cells;
	}

	/**
	 * Sums all cells in a CellList into a single summation cell.
	 *
	 * @param cells the CellList to sum
	 * @return a CellList whose final cell accumulates the sum of all inputs
	 */
	default CellList sum(CellList cells) {
		SummationCell sum = new SummationCell();
		cells.forEach(c -> c.setReceptor(sum));

		CellList result = new CellList(cells);
		result.add(sum);
		return result;
	}

	/**
	 * Creates a CellList of WaveOutput writer cells that write audio to files.
	 *
	 * @param count the number of output files to create
	 * @param f     function mapping index to the output file
	 * @return a CellList of writer cells with finalization operations
	 */
	default CellList o(int count, IntFunction<File> f) {
		CellList result = new CellList();

		for (int i = 0; i < count; i++) {
			WaveOutput out = new WaveOutput(f.apply(i));
			result.add(out.getWriterCell(0));
			result.getFinals().add(out.write().get());
		}

		return result;
	}

	/**
	 * Writes each cell in a CellList to a CSV file for debugging or analysis.
	 *
	 * @param cells the source CellList to capture
	 * @param f     function mapping index to the CSV output file
	 * @return a new CellList with finalization operations that write CSV files
	 */
	default CellList csv(CellList cells, IntFunction<File> f) {
		CellList result = new CellList(cells);

		for (int i = 0; i < cells.size(); i++) {
			WaveOutput out = new WaveOutput(f.apply(i));
			cells.get(i).setReceptor(out.getWriter(0));
			result.getFinals().add(out.writeCsv(0, f.apply(i)).get());
		}

		return result;
	}

	/**
	 * Connects each cell in a CellList to a WaveOutput writer and returns the extended list.
	 *
	 * @param cells the source CellList
	 * @param f     function mapping index to the output file
	 * @return a new CellList including the writer cells and finalization operations
	 */
	default CellList o(CellList cells, IntFunction<File> f) {
		CellList result = new CellList(cells);

		for (int i = 0; i < cells.size(); i++) {
			WaveOutput out = new WaveOutput(f.apply(i));
			ReceptorCell c = out.getWriterCell(0);
			cells.get(i).setReceptor(c);
			result.add(c);
			result.getFinals().add(out.write().get());
		}

		return result;
	}

	/**
	 * Attaches a WaveOutput meter to each cell in a CellList for monitoring without replacing the receptor.
	 *
	 * <p>If the cell is a {@link CellAdapter}, the output is set as a meter. Otherwise, it is
	 * set as the receptor. This allows monitoring intermediate signal levels during processing.</p>
	 *
	 * @param cells the source CellList to monitor
	 * @param f     function mapping index to the output file
	 * @return a new CellList with finalization operations that write the monitored audio
	 */
	default CellList om(CellList cells, IntFunction<File> f) {
		CellList result = new CellList(cells);

		for (int i = 0; i < cells.size(); i++) {
			WaveOutput out = new WaveOutput(f.apply(i));

			if (cells.get(i) instanceof CellAdapter) {
				((CellAdapter) cells.get(i)).setMeter(out.getWriter(0));
			} else {
				cells.get(i).setReceptor(out.getWriter(0));
			}

			result.getFinals().add(out.write().get());
		}

		return result;
	}

	/**
	 * Creates a factory function that produces identity-filtered cells (pass-through).
	 *
	 * @return an IntFunction that creates FilteredCell instances with identity factors
	 */
	default IntFunction<Cell<PackedCollection>> fi() {
		return i -> new FilteredCell<>(i().apply(i));
	}

	/**
	 * Creates a factory function that produces identity factors (pass-through, no modification).
	 *
	 * @return an IntFunction that creates IdentityFactor instances
	 */
	default IntFunction<Factor<PackedCollection>> i() {
		return i -> new IdentityFactor<>();
	}

	/**
	 * Creates a CellList of identity-filtered cells.
	 *
	 * @param count the number of cells to create
	 * @return a CellList of FilteredCells with identity factors
	 */
	default CellList fi(int count) {
		return f(count, i());
	}

	/**
	 * Creates a factory function that produces FilteredCells using a given filter factory.
	 *
	 * @param filter factory function mapping index to a processing factor
	 * @return an IntFunction that creates FilteredCell instances
	 */
	default IntFunction<Cell<PackedCollection>> fc(IntFunction<Factor<PackedCollection>> filter) {
		return i -> new FilteredCell<>(filter.apply(i));
	}

	/**
	 * Creates a CellList of FilteredCells using a given filter factory.
	 *
	 * @param count  the number of cells to create
	 * @param filter factory function mapping index to a processing factor
	 * @return a CellList of FilteredCells
	 */
	default CellList f(int count, IntFunction<Factor<PackedCollection>> filter) {
		CellList layer = new CellList();

		for (int i = 0; i < count; i++) {
			layer.add(new FilteredCell<>(filter.apply(i)));
		}

		return layer;
	}

	/**
	 * Applies a filter to each cell in a CellList, inserting FilteredCells as downstream processors.
	 *
	 * @param cells  the source CellList
	 * @param filter factory function mapping index to a processing factor
	 * @return a new CellList with FilteredCells connected to each source cell
	 */
	default CellList f(CellList cells, IntFunction<Factor<PackedCollection>> filter) {
		CellList layer = new CellList(cells);
		Iterator<Cell<PackedCollection>> itr = cells.iterator();

		for (int i = 0; itr.hasNext(); i++) {
			FilteredCell<PackedCollection> f = new FilteredCell<>(filter.apply(i));
			Cell<PackedCollection> c = itr.next();

			c.setReceptor(f);
			layer.add(f);
		}

		return layer;
	}

	/**
	 * Creates a CellList of adjustable delay cells with unit scale.
	 *
	 * @param count the number of delay cells to create
	 * @param d     function mapping index to a delay time producer
	 * @return a CellList of AdjustableDelayCells
	 */
	default CellList d(int count, IntFunction<Producer<PackedCollection>> d) {
		return d(count, d, i -> c(1.0));
	}

	/**
	 * Creates a CellList of adjustable delay cells with custom scale factors.
	 *
	 * @param count the number of delay cells to create
	 * @param d     function mapping index to a delay time producer
	 * @param s     function mapping index to a scale factor producer
	 * @return a CellList of AdjustableDelayCells
	 */
	default CellList d(int count,
					   IntFunction<Producer<PackedCollection>> d,
					   IntFunction<Producer<PackedCollection>> s) {
		CellList result = new CellList();

		for (int i = 0; i < count; i++) {
			result.add(new AdjustableDelayCell(OutputLine.sampleRate, d.apply(i), s.apply(i)));
		}

		return result;
	}

	/**
	 * Applies adjustable delay with unit scale to each cell in a CellList.
	 *
	 * @param cells the source CellList
	 * @param delay function mapping index to a delay time producer
	 * @return a new CellList with delay cells connected to each source cell
	 */
	default CellList d(CellList cells, IntFunction<Producer<PackedCollection>> delay) {
		return d(cells, delay, i -> c(1.0));
	}

	/**
	 * Applies adjustable delay with custom scale to each cell in a CellList.
	 *
	 * @param cells the source CellList
	 * @param delay function mapping index to a delay time producer
	 * @param scale function mapping index to a scale factor producer
	 * @return a new CellList with delay cells connected to each source cell
	 */
	default CellList d(CellList cells,
					   IntFunction<Producer<PackedCollection>> delay,
					   IntFunction<Producer<PackedCollection>> scale) {
		return map(cells, i -> new AdjustableDelayCell(OutputLine.sampleRate, delay.apply(i), scale.apply(i)));
	}

	/**
	 * Routes each cell through an adapter back into the same CellList (self-routing without transmission).
	 *
	 * @param cells   the source CellList
	 * @param adapter factory function mapping index to a routing adapter cell
	 * @return a new CellList with routing applied back into itself
	 */
	default CellList m(CellList cells, IntFunction<Cell<PackedCollection>> adapter) {
		return m(cells, adapter, cells::get, cells.size());
	}

	/**
	 * Routes each cell through an adapter back into the same CellList using a transmission gene.
	 *
	 * @param cells        the source CellList
	 * @param adapter      factory function mapping index to a routing adapter cell
	 * @param transmission function mapping index to the routing gene
	 * @return a new CellList with gene-controlled routing
	 */
	default CellList m(CellList cells, IntFunction<Cell<PackedCollection>> adapter, IntFunction<Gene<PackedCollection>> transmission) {
		return m(cells, adapter, cells::get, transmission);
	}

	/**
	 * Routes each cell through a list of adapter cells back into the same CellList with a transmission gene.
	 *
	 * @param cells        the source CellList
	 * @param adapter      list of adapter cells (one per source cell)
	 * @param transmission function mapping index to the routing gene
	 * @return a new CellList with gene-controlled self-routing
	 */
	default CellList mself(CellList cells, List<Cell<PackedCollection>> adapter, IntFunction<Gene<PackedCollection>> transmission) {
		return m(cells, adapter, cells, transmission);
	}

	/**
	 * Routes each cell through a list of adapter cells to a list of destination cells.
	 *
	 * @param cells        the source CellList
	 * @param adapter      list of adapter cells (one per source cell)
	 * @param destinations list of destination cells to route to
	 * @return a new CellList with the routed signal
	 */
	default CellList m(CellList cells, List<Cell<PackedCollection>> adapter, List<Cell<PackedCollection>> destinations) {
		return m(cells, adapter, destinations, null);
	}

	/**
	 * Routes each cell through a list of adapter cells to a list of destination cells with a transmission gene.
	 *
	 * @param cells        the source CellList
	 * @param adapter      list of adapter cells (one per source cell)
	 * @param destinations list of destination cells to route to
	 * @param transmission function mapping index to the routing gene, or null for full connectivity
	 * @return a new CellList with gene-controlled routing
	 */
	default CellList m(CellList cells, List<Cell<PackedCollection>> adapter, List<Cell<PackedCollection>> destinations, IntFunction<Gene<PackedCollection>> transmission) {
		CellList result = m(cells, adapter::get, destinations, transmission);

		if (adapter instanceof CellList) {
			result.getFinals().addAll(((CellList) adapter).getFinals());
			((CellList) adapter).getRequirements().forEach(result::addRequirement);
		}

		return result;
	}

	/**
	 * Routes each cell through an adapter back into the same CellList using a transmission gene,
	 * with identity pass-through cells for clean signal separation.
	 *
	 * @param cells        the source CellList
	 * @param adapter      factory function mapping index to a routing adapter cell
	 * @param transmission function mapping index to the routing gene
	 * @return a new CellList with gene-controlled self-routing and identity pass-through
	 */
	default CellList mself(CellList cells, IntFunction<Cell<PackedCollection>> adapter, IntFunction<Gene<PackedCollection>> transmission) {
		return m(cells, adapter, cells, transmission, fi());
	}

	/**
	 * Routes each cell through an adapter back into the same CellList using a transmission gene
	 * and a custom pass-through factory.
	 *
	 * @param cells        the source CellList
	 * @param adapter      factory function mapping index to a routing adapter cell
	 * @param transmission function mapping index to the routing gene
	 * @param passthrough  factory function mapping index to the pass-through cell
	 * @return a new CellList with gene-controlled self-routing and custom pass-through
	 */
	default CellList mself(CellList cells, IntFunction<Cell<PackedCollection>> adapter, IntFunction<Gene<PackedCollection>> transmission, IntFunction<Cell<PackedCollection>> passthrough) {
		return m(cells, adapter, cells, transmission, passthrough);
	}

	/**
	 * Routes each cell through an adapter to a list of destinations without a transmission gene.
	 *
	 * @param cells        the source CellList
	 * @param adapter      factory function mapping index to a routing adapter cell
	 * @param destinations list of destination cells to route to
	 * @return a new CellList with all-to-all routing
	 */
	default CellList m(CellList cells, IntFunction<Cell<PackedCollection>> adapter,
					   List<Cell<PackedCollection>> destinations) {
		return m(cells, adapter, destinations::get, null, null, destinations.size());
	}

	/**
	 * Routes each cell through an adapter to a list of destinations with a transmission gene.
	 *
	 * @param cells        the source CellList
	 * @param adapter      factory function mapping index to a routing adapter cell
	 * @param destinations list of destination cells to route to
	 * @param transmission function mapping index to the routing gene, or null for full connectivity
	 * @return a new CellList with gene-controlled routing
	 */
	default CellList m(CellList cells, IntFunction<Cell<PackedCollection>> adapter,
					   List<Cell<PackedCollection>> destinations,
					   IntFunction<Gene<PackedCollection>> transmission) {
		return m(cells, adapter, destinations, transmission, null);
	}

	/**
	 * Routes each cell through an adapter to a list of destinations with a transmission gene
	 * and pass-through cells.
	 *
	 * @param cells        the source CellList
	 * @param adapter      factory function mapping index to a routing adapter cell
	 * @param destinations list of destination cells to route to
	 * @param transmission function mapping index to the routing gene, or null for full connectivity
	 * @param passthrough  factory function mapping index to pass-through cells, or null for none
	 * @return a new CellList with gene-controlled routing and pass-through
	 */
	default CellList m(CellList cells, IntFunction<Cell<PackedCollection>> adapter,
					   List<Cell<PackedCollection>> destinations,
					   IntFunction<Gene<PackedCollection>> transmission,
					   IntFunction<Cell<PackedCollection>> passthrough) {
		CellList result = m(cells, adapter, destinations::get, transmission, passthrough, -1);

		if (destinations instanceof CellList) {
			result.getFinals().addAll(((CellList) destinations).getFinals());
			((CellList) destinations).getRequirements().forEach(result::addRequirement);
		}

		return result;
	}

	/**
	 * Routes each cell through an adapter to indexed destinations with an explicit destination count.
	 *
	 * @param cells            the source CellList
	 * @param adapter          factory function mapping index to a routing adapter cell
	 * @param destinations     function mapping index to a destination cell
	 * @param destinationCount the total number of destinations
	 * @return a new CellList with routing applied
	 */
	default CellList m(CellList cells, IntFunction<Cell<PackedCollection>> adapter,
					   IntFunction<Cell<PackedCollection>> destinations, int destinationCount) {
		return m(cells, adapter, destinations, null, null, destinationCount);
	}

	/**
	 * Routes each cell through an adapter to indexed destinations using a transmission gene.
	 *
	 * @param cells        the source CellList
	 * @param adapter      factory function mapping index to a routing adapter cell
	 * @param destinations function mapping index to a destination cell
	 * @param transmission function mapping index to the routing gene
	 * @return a new CellList with gene-controlled routing
	 */
	default CellList m(CellList cells, IntFunction<Cell<PackedCollection>> adapter,
					   IntFunction<Cell<PackedCollection>> destinations,
					   IntFunction<Gene<PackedCollection>> transmission) {
		return m(cells, adapter, destinations, transmission, null, -1);
	}

	/**
	 * NOTE: Destination count is only required if transmissions is not provided, as otherwise the
	 * number of destinations is determined by the length of the transmission gene.
	 */
	default CellList m(CellList cells, IntFunction<Cell<PackedCollection>> adapter,
					   IntFunction<Cell<PackedCollection>> destinations,
					   IntFunction<Gene<PackedCollection>> transmission,
					   IntFunction<Cell<PackedCollection>> passthrough,
					   int destinationCount) {
		CellList layer = new CellList(cells);
		CellList cleanLayer = passthrough == null ? null : new CellList(layer);
		Iterator<Cell<PackedCollection>> itr = cells.iterator();

		for (AtomicInteger i = new AtomicInteger(); itr.hasNext(); i.incrementAndGet()) {
			Gene g = transmission == null ? null : transmission.apply(i.get());
			if (g == null && destinationCount < 0) {
				throw new IllegalArgumentException("A transmission gene or a destination count must be provided");
			}

			Cell<PackedCollection> source = itr.next();
			Cell<PackedCollection> clean = Optional.ofNullable(passthrough).map(p -> p.apply(i.get())).orElse(null);

			List<Cell<PackedCollection>> dest = new ArrayList<>();
			IntStream.range(0, g == null ? destinationCount : g.length()).mapToObj(destinations).forEach(dest::add);

			layer.addRequirement(MultiCell.split(source, adapter.apply(i.get()), dest, g, clean));
			dest.forEach(c -> append(layer, c));
			if (cleanLayer != null) cleanLayer.add(clean);
		}

		return cleanLayer == null ? layer : cleanLayer;
	}

	/**
	 * Appends a value to a list only if it is not already present (identity equality check).
	 *
	 * @param <T>  the element type
	 * @param dest the list to append to
	 * @param v    the value to append if not already present
	 */
	default <T> void append(List<T> dest, T v) {
		for (T c : dest) {
			if (c == v) return;
		}

		dest.add(v);
	}

	/**
	 * Creates a CellList containing a single ValueSequenceCell that steps through values over time.
	 *
	 * @param values   function mapping step index to the value producer for that step
	 * @param duration producer specifying the duration of each step
	 * @param steps    the total number of steps in the sequence
	 * @return a CellList containing the sequence cell
	 */
	default CellList seq(IntFunction<Producer<PackedCollection>> values, Producer<PackedCollection> duration, int steps) {
		CellList cells = new CellList();
		cells.addRoot(new ValueSequenceCell(values, duration, steps));
		return cells;
	}

	/**
	 * Creates a grid routing that cycles through cells using integer choice indices.
	 *
	 * <p>Converts integer choices to normalized double positions within the cell list,
	 * then delegates to {@link #grid(CellList, double, int, IntToDoubleFunction)}.</p>
	 *
	 * @param cells    the CellList of possible audio sources
	 * @param duration the duration of each segment in seconds
	 * @param segments the total number of segments
	 * @param choices  function mapping segment index to a cell index
	 * @return a CellList with dynamic routing through the cell choices
	 */
	default CellList gr(CellList cells, double duration, int segments, IntUnaryOperator choices) {
		return grid(cells, duration, segments, (IntToDoubleFunction) i -> (2.0 * choices.applyAsInt(i) + 1) / (2.0 * cells.size()));
	}

	/**
	 * Creates a grid routing that cycles through cells using double-valued choice positions.
	 *
	 * @param cells    the CellList of possible audio sources
	 * @param duration the duration of each segment in seconds
	 * @param segments the total number of segments
	 * @param choices  function mapping segment index to a normalized choice position (0.0 to 1.0)
	 * @return a CellList with dynamic routing through the cell choices
	 */
	default CellList grid(CellList cells, double duration, int segments, IntToDoubleFunction choices) {
		return grid(cells, duration, segments, (IntFunction<Producer<PackedCollection>>) i -> c(choices.applyAsDouble(i)));
	}

	/**
	 * Creates a grid routing that cycles through cells using producer-based choice values.
	 *
	 * <p>Uses a ValueSequenceCell to step through choices and a DynamicAudioCell to route
	 * audio to the selected cell during each segment.</p>
	 *
	 * @param cells    the CellList of possible audio sources
	 * @param duration the duration of each segment in seconds
	 * @param segments the total number of segments
	 * @param choices  function mapping segment index to a choice producer
	 * @return a CellList with dynamic routing through the cell choices
	 */
	default CellList grid(CellList cells, double duration, int segments, IntFunction<Producer<PackedCollection>> choices) {
		PackedCollection out = new PackedCollection(1);
		List<Function<PolymorphicAudioData, ? extends CollectionTemporalCellAdapter>> cellChoices =
				cells.stream()
						.map(c -> (Function<PolymorphicAudioData, ? extends CollectionTemporalCellAdapter>) data -> (CollectionTemporalCellAdapter) c).collect(Collectors.toList());
		DynamicAudioCell cell = new DynamicAudioCell(c(1).multiply(p(out)), cellChoices);
		ValueSequenceCell c = (ValueSequenceCell) seq(choices, c(duration), segments).get(0);
		c.setReceptor(a(p(out)));

		// WaveOutput csv = new WaveOutput(new File("value-sequence-debug.wav"));
		// c.setMeter(csv);

		// TODO  By dropping the parent, we may be losing necessary dependencies
		// TODO  However, if it is included, operations will be invoked multiple times
		// TODO  Since the new dynamic cell delegates to the operations of the
		// TODO  original cells in this current CellList
		CellList result = new CellList();
		result.addRoot(c);

		result = new CellList(result);
		result.addRoot(cell);
		// result.getFinals().add(csv.writeCsv(new File("value-sequence-debug.csv")).get());

		return result;
	}

	/**
	 * Creates a circular-buffer TemporalRunner that writes cell output into a destination collection.
	 *
	 * <p>Currently only supports single-cell CellLists. The output is written in circular
	 * fashion, overwriting the oldest data when the buffer is full.</p>
	 *
	 * @param cells       the source CellList (must contain exactly one cell)
	 * @param destination the destination producer for the circular buffer
	 * @return a TemporalRunner that drives the cell and fills the buffer
	 * @throws UnsupportedOperationException if cells contains more than one cell
	 */
	default TemporalRunner buffer(CellList cells, Producer<PackedCollection> destination) {
		TraversalPolicy shape = shape(destination);

		if (cells.size() > 1) {
			// TODO  This should be supported, but it requires a more complicated
			// TODO  operation on the destination involving subset
			throw new UnsupportedOperationException();
		}

		cells = map(cells, i -> {
			WaveOutput output = new WaveOutput(destination);
			output.setCircular(true);
			return output.getWriterCell(0);
		});
		return cells.buffer(shape.getTotalSize());
	}

	/**
	 * Exports cell audio output into pre-allocated wave data slots within a PackedCollection.
	 *
	 * <p>Each cell in the list writes into a corresponding slice of the destination collection.
	 * The number of cells must equal the number of collection slots ({@code wavs.getCount()}).</p>
	 *
	 * @param cells the source CellList to export
	 * @param wavs  the destination PackedCollection with one slot per cell
	 * @return a Supplier that runs the export operation
	 * @throws IllegalArgumentException if the cell count does not match the slot count
	 */
	default Supplier<Runnable> export(CellList cells, PackedCollection wavs) {
		if (wavs.getCount() != cells.size()) throw new IllegalArgumentException("Destination count must match cell count");

		cells = map(cells, i -> new WaveOutput(
					wavs.range(shape(wavs.getAtomicMemLength()), i * wavs.getAtomicMemLength()))
				.getWriterCell(0));

		OperationList export = new OperationList("Export " + wavs.getAtomicMemLength() + " frames");
		export.add(iter(cells, wavs.getAtomicMemLength()));

//		for (int i = 0; i < cells.size(); i++) {
//			export.add(((WaveOutput) ((ReceptorCell) cells.get(i)).getReceptor()).export(wavs.get(i).traverseEach()));
//		}

		return export;
	}

	/**
	 * Renders each cell in a CellList to a fixed-duration wave buffer, then returns a new
	 * CellList of WaveCells backed by those buffers for playback.
	 *
	 * <p>The mixdown is run synchronously during setup: each cell is iterated for the specified
	 * duration, the audio is captured into a PackedCollection timeline, and then WaveCells
	 * are created to play back the rendered audio.</p>
	 *
	 * @param cells   the source CellList to render
	 * @param seconds the duration to render in seconds
	 * @return a new CellList of WaveCells backed by the rendered audio
	 */
	default CellList mixdown(CellList cells, double seconds) {
		List<WaveOutput> outputs = IntStream.range(0, cells.size())
				.mapToObj(i -> new WaveOutput()).toList();

		cells = map(cells, i -> outputs.get(i).getWriterCell(0));

		OperationList export = new OperationList("Mixdown Export");

		PackedCollection wavs = new PackedCollection(shape(cells.size(), WaveOutput.defaultTimelineFrames)).traverse(1);
		for (int i = 0; i < cells.size(); i++) {
			export.add(outputs.get(i).export(0, wavs.get(i)));
		}

		OperationList setup = new OperationList(seconds + " second mixdown");
		setup.add(iter(cells, (int) (seconds * OutputLine.sampleRate), false));
		setup.add(export);

		CellList newCells = new CellList();
		newCells.addSetup(() -> setup);

		IntStream.range(0, wavs.getCount())
				.mapToObj(wavs::get)
				.map(wav -> new WaveCell(wav, OutputLine.sampleRate))
				.forEach(newCells::addRoot);
		CellList fc = cells;
		newCells.getFinals().add(() -> fc.reset());

		return newCells;
	}

	/**
	 * Creates a Supplier that iterates a Temporal object for the specified duration in minutes.
	 *
	 * @param t       the temporal object to iterate
	 * @param minutes the duration to run in minutes
	 * @return a Supplier that runs the temporal object for the specified duration
	 */
	default Supplier<Runnable> min(Temporal t, double minutes) {
		return sec(t, minutes * 60);
	}

	/**
	 * Creates a Supplier that iterates a Temporal object for the specified duration in seconds.
	 *
	 * @param t       the temporal object to iterate
	 * @param seconds the duration to run in seconds
	 * @return a Supplier that runs the temporal object for the specified duration
	 */
	default Supplier<Runnable> sec(Temporal t, double seconds) {
		return iter(t, (int) (seconds * OutputLine.sampleRate));
	}

	/**
	 * Creates a Supplier that iterates a Temporal object for the specified duration in seconds,
	 * with optional reset after completion.
	 *
	 * @param t          the temporal object to iterate
	 * @param seconds    the duration to run in seconds
	 * @param resetAfter if true, resets the temporal object after iteration completes
	 * @return a Supplier that runs the temporal object for the specified duration
	 */
	default Supplier<Runnable> sec(Temporal t, double seconds, boolean resetAfter) {
		return iter(t, (int) (seconds * OutputLine.sampleRate), resetAfter);
	}

	/**
	 * Creates a ScaleFactor that multiplies audio signals by the given scale value.
	 *
	 * @param scale the scaling multiplier
	 * @return a ScaleFactor for the given value
	 */
	default ScaleFactor sf(double scale) {
		return new ScaleFactor(scale);
	}

	/**
	 * Creates a high-pass filter at the default sample rate.
	 *
	 * @param frequency the cutoff frequency in Hz
	 * @param resonance the resonance (Q) factor
	 * @return an AudioPassFilter configured as a high-pass filter
	 */
	default AudioPassFilter hp(double frequency, double resonance) {
		return hp(OutputLine.sampleRate, frequency, resonance);
	}

	/**
	 * Creates a high-pass filter at the specified sample rate.
	 *
	 * @param sampleRate the sample rate in Hz
	 * @param frequency  the cutoff frequency in Hz
	 * @param resonance  the resonance (Q) factor
	 * @return an AudioPassFilter configured as a high-pass filter
	 */
	default AudioPassFilter hp(int sampleRate, double frequency, double resonance) {
		return hp(sampleRate, c(frequency), scalar(resonance));
	}

	/**
	 * Creates a high-pass filter using producer-based parameters at the default sample rate.
	 *
	 * @param <T>       the PackedCollection type
	 * @param frequency producer for the cutoff frequency in Hz
	 * @param resonance producer for the resonance (Q) factor
	 * @return an AudioPassFilter configured as a high-pass filter
	 */
	default <T extends PackedCollection> AudioPassFilter hp(Producer<T> frequency, Producer<T> resonance) {
		return hp(OutputLine.sampleRate, frequency, resonance);
	}

	/**
	 * Creates a high-pass filter using producer-based parameters at the specified sample rate.
	 *
	 * @param <T>        the PackedCollection type
	 * @param sampleRate the sample rate in Hz
	 * @param frequency  producer for the cutoff frequency in Hz
	 * @param resonance  producer for the resonance (Q) factor
	 * @return an AudioPassFilter configured as a high-pass filter
	 */
	default <T extends PackedCollection> AudioPassFilter hp(int sampleRate, Producer<T> frequency, Producer<T> resonance) {
		return new AudioPassFilter(sampleRate, (Producer) frequency, (Producer) resonance, true);
	}

	/**
	 * Creates a low-pass filter at the default sample rate.
	 *
	 * @param frequency the cutoff frequency in Hz
	 * @param resonance the resonance (Q) factor
	 * @return an AudioPassFilter configured as a low-pass filter
	 */
	default AudioPassFilter lp(double frequency, double resonance) {
		return lp(OutputLine.sampleRate, frequency, resonance);
	}

	/**
	 * Creates a low-pass filter at the specified sample rate.
	 *
	 * @param sampleRate the sample rate in Hz
	 * @param frequency  the cutoff frequency in Hz
	 * @param resonance  the resonance (Q) factor
	 * @return an AudioPassFilter configured as a low-pass filter
	 */
	default AudioPassFilter lp(int sampleRate, double frequency, double resonance) {
		return lp(sampleRate, c(frequency), scalar(resonance));
	}

	/**
	 * Creates a low-pass filter using producer-based parameters at the default sample rate.
	 *
	 * @param <T>       the PackedCollection type
	 * @param frequency producer for the cutoff frequency in Hz
	 * @param resonance producer for the resonance (Q) factor
	 * @return an AudioPassFilter configured as a low-pass filter
	 */
	default <T extends PackedCollection> AudioPassFilter lp(Producer<T> frequency, Producer<T> resonance) {
		return lp(OutputLine.sampleRate, frequency, resonance);
	}

	/**
	 * Creates a low-pass filter using producer-based parameters at the specified sample rate.
	 *
	 * @param <T>        the PackedCollection type
	 * @param sampleRate the sample rate in Hz
	 * @param frequency  producer for the cutoff frequency in Hz
	 * @param resonance  producer for the resonance (Q) factor
	 * @return an AudioPassFilter configured as a low-pass filter
	 */
	default <T extends PackedCollection> AudioPassFilter lp(int sampleRate, Producer<T> frequency, Producer<T> resonance) {
		return new AudioPassFilter(sampleRate, (Producer) frequency, (Producer) resonance, false);
	}

	/**
	 * Returns a default CellFeatures instance with no additional state.
	 *
	 * @return a stateless CellFeatures implementation
	 */
	static CellFeatures getInstance() {
		return new CellFeatures() { };
	}
}
