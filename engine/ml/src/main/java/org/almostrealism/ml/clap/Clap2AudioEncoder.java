/*
 * Copyright 2026 Michael Murray
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

package org.almostrealism.ml.clap;

import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.MeanDirection;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.time.TemporalFeatures;

import java.util.HashMap;
import java.util.Map;

/**
 * The audio tower of a CLAP2 model: a mono 32 kHz waveform becomes one embedding in the space the
 * {@link Clap2TextEncoder text tower} maps captions into.
 *
 * <p>The front end is native: {@link #patches(Producer)} computes the clip's log-mel spectrogram
 * with {@link TemporalFeatures#logMelSpectrogram}, completes its last patch with the clip's quietest
 * value (the reference's {@code floor} padding), and cuts it into {@code 16 x 16} patches ordered
 * frequency row by frequency row, each row in time order. The tower itself is the
 * {@code clap2_audio_tower} layer of {@link #ASSET}; this class supplies its axial rotary angle
 * table and masks, binds the checkpoint and compiles it.</p>
 *
 * <p>The tower attends over every patch of a clip at once, so its sequence length is a function of
 * the clip's duration (50 patches per second). There are two ways to embed a clip:</p>
 * <ul>
 *   <li>{@link #encodeClip(PackedCollection)} compiles the front end and tower <em>once</em>, for the
 *       {@link Clap2Config#getWindowSamples() fixed window} of the configuration, and reads the
 *       clip's length at run time. A clip that fits the window is padded to it, and the patches
 *       of the padding are masked out of every attention and of the pool; its spectrogram is
 *       computed as for the clip alone (reflected at the clip's own end, completed with the
 *       clip's own quietest value), so the padded clip embeds exactly as the unpadded one. A
 *       longer clip is split into windows whose normalized embeddings are averaged. This is the
 *       entry point for clips of arbitrary, varying length.</li>
 *   <li>{@link #encode(PackedCollection)} compiles a tower for the clip's exact sample count and
 *       keeps one per count, released by {@link #destroy()}; it is the reference form, for clips
 *       of a few known lengths.</li>
 * </ul>
 *
 * @see Clap2Config
 */
public class Clap2AudioEncoder implements AttentionFeatures, TemporalFeatures, Destroyable {

	/** The asset defining both CLAP2 towers. */
	private static final String ASSET = "/pdsl/clap2.pdsl";

	/** Every encoder is compiled for one clip at a time. */
	private static final int BATCH = 1;

	/** The architecture. */
	private final Clap2Config config;

	/** The weights, keyed as the extraction script writes them. */
	private final StateDictionary weights;

	/** One compiled tower per clip length, in samples. */
	private final Map<Integer, CompiledModel> compiled;

	/** The number of valid samples of the current window, held on the device; a leaf of the window model. */
	private final PackedCollection clipLength;

	/** The input of the window model, {@link Clap2Config#getWindowSamples()} samples. */
	private PackedCollection windowInput;

	/** The front end and tower compiled for the fixed window, built on the first {@link #encodeClip}. */
	private CompiledModel windowModel;

	/** The mean direction of the window embeddings of the current clip, which {@link #encodeClip} returns. */
	private MeanDirection clipEmbedding;

	/** Which positions of the window every attention may attend to; a leaf of the window model. */
	private PackedCollection windowKeyMask;

	/** Which positions of the window the pool reads; a leaf of the window model. */
	private PackedCollection windowPoolMask;

	/** The compiled assignments that write {@link #windowKeyMask} and {@link #windowPoolMask} from {@link #clipLength}. */
	private Runnable maskUpdate;

	/**
	 * Creates an audio encoder over the given weights.
	 *
	 * @param config  the architecture
	 * @param weights the weights
	 */
	public Clap2AudioEncoder(Clap2Config config, StateDictionary weights) {
		this.config = config;
		this.weights = weights;
		this.compiled = new HashMap<>();
		this.clipLength = new PackedCollection(shape(1));
	}

	/** The architecture of this encoder. */
	public Clap2Config getConfig() { return config; }

	/**
	 * Embeds a clip. The embedding is not normalized; its direction is what the model compares.
	 * The result is the compiled encoder's output and is overwritten by the next clip of the
	 * same length, so a caller that keeps several embeddings copies each one.
	 *
	 * @param waveform the mono waveform at {@link Clap2Config#SAMPLE_RATE}, of any length of at
	 *                 least one analysis window
	 * @return the embedding, shape {@code [1, embedDim]}
	 */
	public PackedCollection encode(PackedCollection waveform) {
		int samples = waveform.getShape().getTotalSize();
		return compiled.computeIfAbsent(samples, n -> {
			int timePatches = Clap2Config.timePatches(n);
			return compile(n, c((double) n), keyMask(timePatches, c((double) timePatches)),
					poolMask(timePatches, c((double) timePatches)));
		}).forward(waveform.reshape(shape(samples)));
	}

	/**
	 * Embeds a clip of any length of at least {@link Clap2Config#MIN_CLIP_SAMPLES} through the
	 * tower compiled once for the {@link Clap2Config#getWindowSamples() fixed window}.
	 *
	 * <p>The clip is cut into consecutive windows; the last holds the remainder, and a remainder
	 * shorter than {@link Clap2Config#MIN_CLIP_SAMPLES} is dropped. Each window is copied into the
	 * model's input with its length, embedded with the patches beyond its length masked (so a
	 * window shorter than the fixed one embeds exactly as it would at its own length), and its
	 * embedding is normalized and added to a running sum on the device; the clip's embedding is the
	 * normalized sum, the direction of the mean of the window directions. A clip that fits one
	 * window therefore has the direction {@link #encode(PackedCollection)} gives it.</p>
	 *
	 * <p>The result is a buffer this encoder owns and overwrites on the next call, so a caller that
	 * keeps several embeddings copies each one.</p>
	 *
	 * @param waveform the mono waveform at {@link Clap2Config#SAMPLE_RATE}
	 * @return the unit-length embedding, shape {@code [embedDim]}
	 * @throws IllegalArgumentException if the clip is shorter than {@link Clap2Config#MIN_CLIP_SAMPLES}
	 */
	public PackedCollection encodeClip(PackedCollection waveform) {
		int samples = waveform.getShape().getTotalSize();
		if (samples < Clap2Config.MIN_CLIP_SAMPLES) {
			throw new IllegalArgumentException("A clip of " + samples + " samples is shorter than the "
					+ Clap2Config.MIN_CLIP_SAMPLES + " samples of one analysis window");
		}

		int window = config.getWindowSamples();
		int windows = (samples + window - 1) / window;
		if (windows > 1 && samples - (windows - 1) * window < Clap2Config.MIN_CLIP_SAMPLES) {
			windows--;
		}

		if (windowModel == null) {
			int timePatches = Clap2Config.timePatches(window);
			TraversalPolicy maskShape = shape(BATCH, Clap2Config.audioSequenceLength(timePatches));
			windowInput = new PackedCollection(shape(window));
			windowKeyMask = new PackedCollection(maskShape);
			windowPoolMask = new PackedCollection(maskShape);
			OperationList masks = new OperationList("clap2WindowMasks");
			masks.add(a("clap2WindowKeyMask", p(windowKeyMask),
					keyMask(timePatches, validTimePatches(cp(clipLength)))));
			masks.add(a("clap2WindowPoolMask", p(windowPoolMask),
					poolMask(timePatches, validTimePatches(cp(clipLength)))));
			maskUpdate = masks.get();

			windowModel = compile(window, cp(clipLength), cp(windowKeyMask), cp(windowPoolMask));
			clipEmbedding = new MeanDirection(config.getEmbedDim());
		}

		clipEmbedding.clear();
		for (int k = 0; k < windows; k++) {
			int length = Math.min(window, samples - k * window);
			windowInput.setFrom(0, waveform, k * window, length);
			clipLength.fill((double) length);
			maskUpdate.run();
			clipEmbedding.add(windowModel.forward(windowInput));
		}

		return clipEmbedding.direction();
	}

	/**
	 * The tower's input for a clip: the register row followed by one row per patch,
	 * {@code [1, 1 + patches, patchSize + 1]}. The register row is zero except for a one in the
	 * last column, where every patch row is zero, so that the patch embedding (whose last input
	 * column holds the register token) yields the register token for the first row and the
	 * embedded patch for every other.
	 *
	 * <p>The patches are cut from the log-mel spectrogram {@code [frames, 128]} after its frames
	 * are completed to a whole number of patches with the clip's minimum value. Patch
	 * {@code (f, t)} holds mel bands {@code 16f .. 16f + 15} of frames {@code 16t .. 16t + 15},
	 * flattened band-major, and the patches are ordered by frequency row, then time.</p>
	 *
	 * @param waveform the mono waveform at {@link Clap2Config#SAMPLE_RATE}
	 * @return the input rows
	 */
	public CollectionProducer patches(Producer<PackedCollection> waveform) {
		return patches(waveform, c((double) shape(waveform).getTotalSize()));
	}

	/**
	 * The tower's input rows for a clip held in the first {@code clipLength} samples of a longer
	 * buffer, laid out on the patch grid of the whole buffer: {@code [1, 1 + patches, patchSize + 1]}
	 * for the buffer's {@link Clap2Config#timePatches(int) time patches}.
	 *
	 * <p>The spectrogram is the clip's own: the buffer is {@link #reflectPad(Producer, int, Producer)
	 * reflect-padded} at the clip's end rather than the buffer's, so the frames of the clip are the
	 * frames of the clip alone, and every frame from the clip's last onward is the clip's minimum,
	 * which completes the clip's last patch as {@link #patches(Producer)} completes it. The patches
	 * of the clip's {@link #validTimePatches(Producer) time patches} therefore equal the patches of
	 * the clip alone, each at the same grid coordinates; the patches beyond hold the minimum and are
	 * for the tower to mask.</p>
	 *
	 * @param waveform   the buffer, the clip followed by samples that are not read
	 * @param clipLength the number of samples of the clip, shape {@code [1]}, at least
	 *                   {@link Clap2Config#MIN_CLIP_SAMPLES}
	 * @return the input rows
	 */
	public CollectionProducer patches(Producer<PackedCollection> waveform, Producer<PackedCollection> clipLength) {
		int samples = shape(waveform).getTotalSize();
		int frames = Clap2Config.frames(samples);
		int timePatches = Clap2Config.timePatches(samples);
		int completion = timePatches * Clap2Config.PATCH_TIME - frames;
		int patchCount = Clap2Config.FREQUENCY_PATCHES * timePatches;
		int values = frames * Clap2Config.MEL_BANDS;

		CollectionProducer padded = reflectPad(waveform, Clap2Config.FFT_SIZE / 2, clipLength);
		CollectionProducer mel = logMelFrames(Clap2Config.FFT_SIZE, Clap2Config.HOP_SIZE,
				Clap2Config.SAMPLE_RATE, Clap2Config.MEL_BANDS,
				Clap2Config.MEL_MIN_HZ, Clap2Config.MEL_MAX_HZ, padded, frames)
				.reshape(shape(values));

		CollectionProducer frame = floor(integers(0, values).divide(c((double) Clap2Config.MEL_BANDS)));
		CollectionProducer limit = validFrames(clipLength).repeat(values).reshape(shape(values));
		CollectionProducer floor = greaterThan(limit, frame, mel, c(Double.MAX_VALUE))
				.reshape(shape(1, values)).min(1).reshape(shape(1));
		mel = greaterThan(limit, frame, mel, floor.repeat(values).reshape(shape(values)))
				.reshape(shape(frames, Clap2Config.MEL_BANDS));
		if (completion > 0) {
			mel = concat(0, mel, floor.repeat(completion * Clap2Config.MEL_BANDS)
					.reshape(shape(completion, Clap2Config.MEL_BANDS)));
		}

		CollectionProducer grid = mel
				.reshape(shape(timePatches, Clap2Config.PATCH_TIME,
						Clap2Config.FREQUENCY_PATCHES, Clap2Config.PATCH_FREQ))
				.permute(2, 0, 3, 1)
				.reshape(shape(patchCount, Clap2Config.PATCH_SIZE));
		CollectionProducer rows = concat(1, grid, zeros(shape(patchCount, 1)));

		double[] register = new double[Clap2Config.PATCH_SIZE + 1];
		register[Clap2Config.PATCH_SIZE] = 1.0;
		return concat(0, c(shape(1, Clap2Config.PATCH_SIZE + 1), register), rows)
				.reshape(shape(BATCH, 1 + patchCount, Clap2Config.PATCH_SIZE + 1));
	}

	/**
	 * The number of spectrogram frames of a clip, {@link Clap2Config#frames(int)}, for a length
	 * known only at run time.
	 *
	 * @param clipLength the number of samples of the clip, shape {@code [1]}
	 * @return the number of frames, shape {@code [1]}
	 */
	protected CollectionProducer validFrames(Producer<PackedCollection> clipLength) {
		return floor(c(clipLength).reshape(shape(1)).divide(c((double) Clap2Config.HOP_SIZE))).add(c(1.0));
	}

	/**
	 * The number of time patches of a clip, {@link Clap2Config#timePatches(int)}, for a length
	 * known only at run time.
	 *
	 * @param clipLength the number of samples of the clip, shape {@code [1]}
	 * @return the number of time patches, shape {@code [1]}
	 */
	protected CollectionProducer validTimePatches(Producer<PackedCollection> clipLength) {
		return floor(validFrames(clipLength).add(c(Clap2Config.PATCH_TIME - 1.0))
				.divide(c((double) Clap2Config.PATCH_TIME)));
	}

	/**
	 * The axial rotary angles of a clip's sequence, {@code [1 + patches, headDim / 2]} in the
	 * split-half pairing {@code sequence_rope} rotates. The register row is all zero (the register
	 * token is not rotated). For patch {@code (f, t)}, with {@code q = headDim / 4} bands
	 * {@code band[j] = theta^(-j / q)}, pair {@code i < q} turns by {@code f * band[i]} and pair
	 * {@code i >= q} by {@code t * band[i - q]}: the first half of every head encodes the
	 * frequency row, the second the time column.
	 *
	 * @param timePatches the number of patches along the time axis
	 * @return the angle table
	 */
	public CollectionProducer axialAngles(int timePatches) {
		int pairs = config.getAudioHeadDim() / 2;
		int quarter = pairs / 2;
		int rows = Clap2Config.audioSequenceLength(timePatches);

		CollectionProducer index = integers(0, rows * pairs);
		CollectionProducer position = floor(index.divide(c((double) pairs)));
		CollectionProducer pair = mod(index, c((double) pairs));
		CollectionProducer patch = position.subtract(c(1.0));
		CollectionProducer row = floor(patch.divide(c((double) timePatches)));
		CollectionProducer column = patch.subtract(row.multiply(c((double) timePatches)));

		CollectionProducer coordinate = greaterThan(c((double) quarter), pair, row, column);
		CollectionProducer band = exp(mod(pair, c((double) quarter))
				.multiply(c(-Math.log(Clap2Config.ROPE_THETA) / quarter)));
		CollectionProducer rotated = greaterThan(position, c(0.5), c(1.0), c(0.0));
		return coordinate.multiply(band).multiply(rotated).reshape(shape(rows, pairs));
	}

	/**
	 * The native front end for clips of the given length, as a block from the waveform
	 * {@code [samples]} to the tower's input rows, {@link #patches(Producer)}.
	 *
	 * @param samples the number of samples in a clip
	 * @return the front end
	 */
	public Block frontEnd(int samples) {
		return frontEnd(samples, c((double) samples));
	}

	/**
	 * The native front end for buffers of the given length holding a clip of a length read at run
	 * time, as a block from the buffer {@code [samples]} to the tower's input rows,
	 * {@link #patches(Producer, Producer)}.
	 *
	 * @param samples    the number of samples in a buffer
	 * @param clipLength the number of those samples that hold the clip, shape {@code [1]}
	 * @return the front end
	 */
	public Block frontEnd(int samples, Producer<PackedCollection> clipLength) {
		int length = Clap2Config.audioSequenceLength(Clap2Config.timePatches(samples));
		return layer("clap2FrontEnd", shape(samples),
				shape(BATCH, length, Clap2Config.PATCH_SIZE + 1), input -> patches(input, clipLength));
	}

	/**
	 * The tower for clips of the given number of time patches, as a block from the input rows
	 * {@link #patches(Producer)} produces to the embedding {@code [1, embedDim]}.
	 *
	 * @param timePatches the number of patches along the time axis
	 * @return the tower
	 */
	public Block block(int timePatches) {
		return block(timePatches, keyMask(timePatches, c((double) timePatches)),
				poolMask(timePatches, c((double) timePatches)));
	}

	/**
	 * Which positions of a grid of the given number of time patches hold the clip, of which only
	 * the first {@code validTimePatches} columns do: one for the register token and every patch of
	 * those columns, zero for every other patch. Every attention of the tower attends only to these.
	 *
	 * @param timePatches      the number of patches along the time axis of the grid
	 * @param validTimePatches the number of those that hold the clip, shape {@code [1]}
	 * @return the mask, shape {@code [1, length]}
	 */
	public CollectionProducer keyMask(int timePatches, Producer<PackedCollection> validTimePatches) {
		int length = Clap2Config.audioSequenceLength(timePatches);
		CollectionProducer column = mod(max(integers(0, length).subtract(c(1.0)), c(0.0)), c((double) timePatches));
		return lessThan(column, c(validTimePatches).reshape(shape(1)).repeat(length).reshape(shape(length)))
				.reshape(shape(BATCH, length));
	}

	/**
	 * Which positions the pool reads: the {@link #keyMask patches that hold the clip}, without the
	 * register token.
	 *
	 * @param timePatches      the number of patches along the time axis of the grid
	 * @param validTimePatches the number of those that hold the clip, shape {@code [1]}
	 * @return the mask, shape {@code [1, length]}
	 */
	public CollectionProducer poolMask(int timePatches, Producer<PackedCollection> validTimePatches) {
		int length = Clap2Config.audioSequenceLength(timePatches);
		return greaterThan(integers(0, length), c(0.5),
				keyMask(timePatches, validTimePatches).reshape(shape(length)), c(0.0))
				.reshape(shape(BATCH, length));
	}

	/**
	 * The tower over the patch grid of the given number of time patches, as a block from the
	 * input rows {@link #patches(Producer, Producer)} produces to the embedding {@code [1, embedDim]},
	 * with the patches the masks exclude kept out of every attention as keys and out of the pool,
	 * so that they reach neither a valid patch nor the embedding.
	 *
	 * <p>The tower reads the masks on every run. A mask that changes between runs (the window's,
	 * which follows the clip length) must therefore be a collection the caller rewrites, not a
	 * computation over one: a compiled model is free to fold a computation that does not depend
	 * on its input into a constant.</p>
	 *
	 * @param timePatches the number of patches along the time axis
	 * @param keyMask     which positions every attention may attend to, shape {@code [1, length]}
	 * @param poolMask    which positions the pool reads, shape {@code [1, length]}
	 * @return the tower
	 */
	public Block block(int timePatches, Producer<PackedCollection> keyMask, Producer<PackedCollection> poolMask) {
		int length = Clap2Config.audioSequenceLength(timePatches);
		Map<String, Object> arguments = config.toAudioPdslSettings();
		arguments.put("batch", BATCH);
		arguments.put("seq_len", length);
		arguments.put("weights", weights);
		arguments.put("angles", axialAngles(timePatches));
		arguments.put("padding_mask", everyPositionValid(BATCH, length));
		arguments.put("key_mask", keyMask);
		arguments.put("pool_mask", poolMask);

		TraversalPolicy inputShape = shape(BATCH, length, Clap2Config.PATCH_SIZE + 1);
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(ASSET), "clap2_audio_tower", inputShape, arguments);
	}

	/**
	 * Compiles the front end and the masked tower, as one model, for buffers of the given length
	 * holding a clip of the given length: the waveform's own length for
	 * {@link #encode(PackedCollection)}, the window's valid length in {@link #clipLength} for
	 * {@link #encodeClip(PackedCollection)}.
	 *
	 * @param samples    the number of samples in a buffer
	 * @param clipLength the number of those samples that hold the clip, shape {@code [1]}
	 * @param keyMask    the tower's key mask
	 * @param poolMask   the tower's pool mask
	 * @return the compiled encoder, from the buffer {@code [samples]} to the embedding
	 */
	private CompiledModel compile(int samples, Producer<PackedCollection> clipLength,
								  Producer<PackedCollection> keyMask, Producer<PackedCollection> poolMask) {
		Model model = new Model(shape(samples));
		model.add(frontEnd(samples, clipLength));
		model.add(block(Clap2Config.timePatches(samples), keyMask, poolMask));
		return model.compile(false);
	}

	@Override
	public void destroy() {
		compiled.values().forEach(CompiledModel::destroy);
		compiled.clear();

		if (windowModel != null) {
			windowModel.destroy();
			windowInput.destroy();
			clipEmbedding.destroy();
			windowKeyMask.destroy();
			windowPoolMask.destroy();
			windowModel = null;
		}

		clipLength.destroy();
	}
}
