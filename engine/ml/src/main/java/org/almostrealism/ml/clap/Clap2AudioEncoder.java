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
import org.almostrealism.ml.AttentionFeatures;
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
 * the clip's duration (50 patches per second). One compiled tower is kept per patch count, built on
 * the first clip of that length.</p>
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

	/** One compiled tower per number of time patches. */
	private final Map<Integer, CompiledModel> compiled;

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
		return compiled.computeIfAbsent(samples, this::compile).forward(waveform.reshape(shape(samples)));
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
		int samples = shape(waveform).getTotalSize();
		int frames = Clap2Config.frames(samples);
		int timePatches = Clap2Config.timePatches(samples);
		int completion = timePatches * Clap2Config.PATCH_TIME - frames;
		int patchCount = Clap2Config.FREQUENCY_PATCHES * timePatches;

		CollectionProducer mel = logMelSpectrogram(Clap2Config.FFT_SIZE, Clap2Config.HOP_SIZE,
				Clap2Config.SAMPLE_RATE, Clap2Config.MEL_BANDS,
				Clap2Config.MEL_MIN_HZ, Clap2Config.MEL_MAX_HZ, waveform);
		if (completion > 0) {
			CollectionProducer floor = mel.reshape(shape(1, frames * Clap2Config.MEL_BANDS)).min(1)
					.reshape(shape(1)).repeat(completion * Clap2Config.MEL_BANDS)
					.reshape(shape(completion, Clap2Config.MEL_BANDS));
			mel = concat(0, mel, floor);
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
		int length = Clap2Config.audioSequenceLength(Clap2Config.timePatches(samples));
		return layer("clap2FrontEnd", shape(samples),
				shape(BATCH, length, Clap2Config.PATCH_SIZE + 1), this::patches);
	}

	/**
	 * The tower for clips of the given number of time patches, as a block from the input rows
	 * {@link #patches(Producer)} produces to the embedding {@code [1, embedDim]}.
	 *
	 * @param timePatches the number of patches along the time axis
	 * @return the tower
	 */
	public Block block(int timePatches) {
		int length = Clap2Config.audioSequenceLength(timePatches);
		CollectionProducer poolMask = greaterThan(integers(0, length), c(0.5), c(1.0), c(0.0))
				.reshape(shape(BATCH, length));

		Map<String, Object> arguments = config.toAudioPdslSettings();
		arguments.put("batch", BATCH);
		arguments.put("seq_len", length);
		arguments.put("weights", weights);
		arguments.put("angles", axialAngles(timePatches));
		arguments.put("padding_mask", everyPositionValid(BATCH, length));
		arguments.put("key_mask", everyPositionValid(BATCH, length));
		arguments.put("pool_mask", poolMask);

		TraversalPolicy inputShape = shape(BATCH, length, Clap2Config.PATCH_SIZE + 1);
		PdslLoader loader = new PdslLoader();
		return loader.buildLayer(loader.parseResource(ASSET), "clap2_audio_tower", inputShape, arguments);
	}

	/**
	 * Compiles the front end and the tower, as one model, for clips of the given length.
	 *
	 * @param samples the number of samples in a clip
	 * @return the compiled encoder, from the waveform {@code [samples]} to the embedding
	 */
	private CompiledModel compile(int samples) {
		Model model = new Model(shape(samples));
		model.add(frontEnd(samples));
		model.add(block(Clap2Config.timePatches(samples)));
		return model.compile(false);
	}

	@Override
	public void destroy() {
		compiled.values().forEach(CompiledModel::destroy);
		compiled.clear();
	}
}
