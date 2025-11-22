/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.graph.temporal;

import io.almostrealism.collect.Shape;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.Provider;
import org.almostrealism.Ops;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.graph.TimeCell;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.hardware.computations.Assignment;
import io.almostrealism.relation.Factor;
import org.almostrealism.io.Console;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A temporal cell that processes audio waveform data sample by sample.
 *
 * <p>{@code WaveCell} extends {@link CollectionTemporalCellAdapter} to provide
 * audio sample playback functionality within the computation graph. It reads
 * samples from a {@link PackedCollection} representing audio waveform data,
 * applying amplitude scaling and supporting features like offset timing and
 * looping through the {@link TimeCell} clock mechanism.</p>
 *
 * <p>The cell operates by:</p>
 * <ul>
 *   <li>Maintaining a reference to audio waveform data as a {@link PackedCollection}</li>
 *   <li>Using a {@link TimeCell} for frame-based timing and optional looping</li>
 *   <li>Reading samples based on the current frame position</li>
 *   <li>Applying amplitude scaling to output values</li>
 *   <li>Supporting frame windowing through frameIndex and frameCount parameters</li>
 * </ul>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * PackedCollection<?> audioData = loadAudioFile("sample.wav");
 * WaveCell cell = new WaveCell(audioData, 44100, 0.8);
 * cell.setup().get().run();
 *
 * // Process audio samples
 * Runnable tick = cell.tick().get();
 * for (int i = 0; i < audioData.getCountLong(); i++) {
 *     tick.run();
 * }
 * }</pre>
 *
 * @author Michael Murray
 * @see CollectionTemporalCellAdapter
 * @see TimeCell
 * @see WaveCellData
 * @see WaveCellPush
 */
public class WaveCell extends CollectionTemporalCellAdapter {
	private final WaveCellData data;
	private final Producer<PackedCollection<?>> wave;

	private final TimeCell clock;
	private final Producer<PackedCollection<?>> frameIndex, frameCount;
	private final Producer<PackedCollection<?>> frame;

	private double amplitude;
	private double waveLength;

	/**
	 * Creates a new WaveCell with the specified waveform data and sample rate,
	 * using default amplitude of 1.0.
	 *
	 * @param wav        the audio waveform data as a packed collection of samples
	 * @param sampleRate the sample rate in Hz (e.g., 44100 for CD quality)
	 */
	public WaveCell(PackedCollection<?> wav, int sampleRate) {
		this(wav, sampleRate, 1.0);
	}

	/**
	 * Creates a new WaveCell with the specified waveform data, sample rate, and amplitude.
	 *
	 * @param wav        the audio waveform data as a packed collection of samples
	 * @param sampleRate the sample rate in Hz (e.g., 44100 for CD quality)
	 * @param amplitude  the amplitude multiplier for output samples (1.0 = original level)
	 */
	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude) {
		this(wav, sampleRate, amplitude, null, null);
	}

	/**
	 * Creates a new WaveCell with timing offset and repeat duration control.
	 *
	 * @param wav        the audio waveform data as a packed collection of samples
	 * @param sampleRate the sample rate in Hz (e.g., 44100 for CD quality)
	 * @param amplitude  the amplitude multiplier for output samples
	 * @param offset     producer for the time offset in seconds before playback starts, or null for no offset
	 * @param repeat     producer for the repeat duration in seconds for looping, or null for no looping
	 */
	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<PackedCollection<?>> offset, Producer<PackedCollection<?>> repeat) {
		this(wav, sampleRate, amplitude, offset, repeat, Ops.o().c(0.0), Ops.o().c(wav.getCountLong()));
	}

	/**
	 * Creates a new WaveCell with full timing and frame windowing control.
	 *
	 * @param wav        the audio waveform data as a packed collection of samples
	 * @param sampleRate the sample rate in Hz (e.g., 44100 for CD quality)
	 * @param amplitude  the amplitude multiplier for output samples
	 * @param offset     producer for the time offset before playback starts, or null
	 * @param repeat     producer for the repeat duration for looping, or null
	 * @param frameIndex producer for the starting frame index within the wave data
	 * @param frameCount producer for the number of frames to process
	 */
	public WaveCell(PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<PackedCollection<?>> offset, Producer<PackedCollection<?>> repeat,
					Producer<PackedCollection<?>> frameIndex, Producer<PackedCollection<?>> frameCount) {
		this(new DefaultWaveCellData(), wav, sampleRate, amplitude, offset, repeat, frameIndex, frameCount);
	}

	/**
	 * Creates a new WaveCell with custom data storage and full control.
	 *
	 * @param data       the wave cell data storage for internal state
	 * @param wav        the audio waveform data as a packed collection of samples
	 * @param sampleRate the sample rate in Hz (e.g., 44100 for CD quality)
	 * @param amplitude  the amplitude multiplier for output samples
	 * @param offset     producer for the time offset before playback starts, or null
	 * @param repeat     producer for the repeat duration for looping, or null
	 * @param frameIndex producer for the starting frame index within the wave data
	 * @param frameCount producer for the number of frames to process
	 */
	public WaveCell(WaveCellData data, PackedCollection<?> wav, int sampleRate, double amplitude,
					Producer<PackedCollection<?>> offset, Producer<PackedCollection<?>> repeat,
					Producer<PackedCollection<?>> frameIndex, Producer<PackedCollection<?>> frameCount) {
		this(data, () -> new Provider<>(wav), sampleRate, amplitude,
				offset, repeat, frameIndex, frameCount);
	}

	public WaveCell(WaveCellData data, Producer<PackedCollection<?>> wav,
					int sampleRate, double amplitude,
					Producer<PackedCollection<?>> offset, Producer<PackedCollection<?>> repeat,
					Producer<PackedCollection<?>> frameIndex, Producer<PackedCollection<?>> frameCount) {
		this.data = data;
		this.amplitude = amplitude;
		this.wave = validate(wav);

		this.waveLength = 1;

		Producer<PackedCollection<?>> initial;

		if (offset != null) {
			initial = multiply(offset, c(-sampleRate));
		} else {
			initial = null;
		}

		Producer<PackedCollection<?>> duration;

		if (repeat != null) {
			duration = multiply(repeat, c(sampleRate));
		} else {
			duration = null;
		}

		this.clock = new TimeCell(initial, duration);
		this.frame = clock.frame();

		this.frameIndex = frameIndex;
		this.frameCount = frameCount;
	}

	/**
	 * Creates a new WaveCell using an existing TimeCell as the clock source.
	 *
	 * @param wav   the audio waveform data as a packed collection of samples
	 * @param clock the TimeCell to use for frame timing
	 */
	public WaveCell(PackedCollection<?> wav, TimeCell clock) {
		this(wav, clock.frame());
	}

	/**
	 * Creates a new WaveCell with an externally controlled frame position.
	 *
	 * @param wav   the audio waveform data as a packed collection of samples
	 * @param frame producer for the current frame position
	 */
	public WaveCell(PackedCollection<?> wav, Producer<PackedCollection<?>> frame) {
		this(wav, 1.0, frame);
	}

	/**
	 * Creates a new WaveCell with external frame control and custom amplitude.
	 *
	 * @param wav       the audio waveform data as a packed collection of samples
	 * @param amplitude the amplitude multiplier for output samples
	 * @param frame     producer for the current frame position
	 */
	public WaveCell(PackedCollection<?> wav, double amplitude, Producer<PackedCollection<?>> frame) {
		this(new DefaultWaveCellData(), wav, amplitude, frame);
	}

	/**
	 * Creates a new WaveCell with custom data storage and external frame control.
	 *
	 * @param data      the wave cell data storage for internal state
	 * @param wav       the audio waveform data as a packed collection of samples
	 * @param amplitude the amplitude multiplier for output samples
	 * @param frame     producer for the current frame position
	 */
	public WaveCell(WaveCellData data, PackedCollection<?> wav, double amplitude, Producer<PackedCollection<?>> frame) {
		this(data, wav, amplitude, frame, Ops.o().c(0.0), Ops.o().c(wav.getCountLong()));
	}

	/**
	 * Creates a new WaveCell with custom data storage, frame control, and windowing.
	 *
	 * @param data       the wave cell data storage for internal state
	 * @param wav        the audio waveform data as a packed collection of samples
	 * @param amplitude  the amplitude multiplier for output samples
	 * @param frame      producer for the current frame position
	 * @param frameIndex producer for the starting frame index within the wave data
	 * @param frameCount producer for the number of frames to process
	 */
	public WaveCell(WaveCellData data, PackedCollection<?> wav, double amplitude,
					Producer<PackedCollection<?>> frame,
					Producer<PackedCollection<?>> frameIndex,
					Producer<PackedCollection<?>> frameCount) {
		this(data, () -> new Provider<>(wav), amplitude, frame, frameIndex, frameCount);
	}

	/**
	 * Creates a new WaveCell with producer-based wave data and full control.
	 *
	 * @param data       the wave cell data storage for internal state
	 * @param wav        producer for the audio waveform data
	 * @param amplitude  the amplitude multiplier for output samples
	 * @param frame      producer for the current frame position
	 * @param frameIndex producer for the starting frame index within the wave data
	 * @param frameCount producer for the number of frames to process
	 */
	public WaveCell(WaveCellData data, Producer<PackedCollection<?>> wav, double amplitude,
					Producer<PackedCollection<?>> frame,
					Producer<PackedCollection<?>> frameIndex,
					Producer<PackedCollection<?>> frameCount) {
		this.data = data;
		this.amplitude = amplitude;
		this.wave = validate(wav);
		this.waveLength = 1;

		this.clock = null;
		this.frame = Objects.requireNonNull(frame);

		this.frameIndex = frameIndex;
		this.frameCount = frameCount;
	}

	/**
	 * Sets the amplitude multiplier for output samples.
	 *
	 * @param amp the new amplitude value (1.0 = original level)
	 */
	public void setAmplitude(double amp) { amplitude = amp; }

	/**
	 * Returns the wave cell data storage containing internal state.
	 *
	 * @return the wave cell data instance
	 */
	public WaveCellData getData() { return data; }

	/**
	 * Returns the TimeCell used for frame timing, if any.
	 *
	 * @return the clock TimeCell, or null if using external frame control
	 */
	public TimeCell getClock() { return clock; }

	/**
	 * Returns the producer for the current frame position.
	 *
	 * @return the frame position producer
	 */
	public Producer<PackedCollection<?>> getFrame() { return frame; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Initializes the wave cell by setting up the clock (if present),
	 * configuring wave parameters (length, index, count, amplitude),
	 * and calling the parent setup.</p>
	 *
	 * @return a supplier that provides the setup operation
	 */
	@Override
	public Supplier<Runnable> setup() {
		OperationList setup = new OperationList("WaveCell Setup");
		if (clock != null) setup.add(clock.setup());
		setup.add(a(data.getWaveLength(), c(waveLength)));
		setup.add(a(data.getWaveIndex(), frameIndex));
		setup.add(a(data.getWaveCount(), frameCount));
		setup.add(a(data.getAmplitude(), c(amplitude)));
		setup.add(super.setup());
		return setup;
	}

	/**
	 * Pushes the current sample value through the cell without any input protein.
	 *
	 * @return a supplier that provides the push operation
	 */
	public Supplier<Runnable> push() { return push(null); }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Reads the current sample from the wave data based on the frame position,
	 * applies amplitude scaling, and pushes the result to connected receptors.</p>
	 *
	 * @param protein the input value (ignored in this implementation)
	 * @return a supplier that provides the push operation
	 */
	@Override
	public Supplier<Runnable> push(Producer<PackedCollection<?>> protein) {
		OperationList push = new OperationList("WavCell Push");
		push.add(new WaveCellPush(data, wave, frame, data.value()));
		push.add(super.push(p(data.value())));
		return push;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Advances the frame counter by ticking the clock (if present)
	 * and calling the parent tick implementation.</p>
	 *
	 * @return a supplier that provides the tick operation
	 */
	@Override
	public Supplier<Runnable> tick() {
		OperationList tick = new OperationList("WavCell Tick");
		if (clock != null) tick.add(clock.tick());
		tick.add(super.tick());
		return tick;
	}

	/**
	 * Converts this WaveCell into a Factor that can be used in factor-based processing.
	 *
	 * @return a Factor that wraps this cell's functionality
	 */
	public Factor<PackedCollection<?>> toFactor() {
		return toFactor(() -> new PackedCollection<>(shape(1)), p -> protein -> new Assignment<>(1, p, protein));
	}

	private static Producer<PackedCollection<?>> validate(Producer<PackedCollection<?>> wav) {
		if (!(wav instanceof Shape)) return wav;

		TraversalPolicy shape = ((Shape) wav).getShape();

		if (shape.getCountLong() == 0) {
			throw new IllegalArgumentException("Wave must have at least one sample");
		} else if (shape.getDimensions() > 1) {
			throw new IllegalArgumentException("WaveCell cannot handle more than one audio channel");
		} else if (shape.getTotalSizeLong() == 1) {
			throw new IllegalArgumentException("Wave has only one sample");
		} else if (shape.getCountLong() == 1) {
			Console.root().features(WaveCell.class).warn("Wave traversal axis is likely incorrect");
		}

		return wav;
	}
}

