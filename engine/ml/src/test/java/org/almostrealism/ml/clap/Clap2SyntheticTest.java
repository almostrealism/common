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

import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.dsl.PdslLoader;
import org.almostrealism.ml.AttentionFeatures;
import org.almostrealism.ml.StateDictionary;
import org.almostrealism.model.Block;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Proves the native CLAP2 port builds and runs without real weights: both towers compile over
 * {@link Clap2WeightFixture random weights} and produce finite, content-dependent embeddings, the
 * text tower's padding is masked, the scoring is a cosine similarity, and the audio front end lays
 * out its patches and rotary angles as the reference model does.
 */
public class Clap2SyntheticTest extends TestSuiteBase implements AttentionFeatures {

	/** Sample count of the test clips: half a second, 51 frames, 4 time patches, 33 positions. */
	private static final int SAMPLES = Clap2Config.SAMPLE_RATE / 2;

	/** The token positions the text tower is compiled for. */
	private static final int TEXT_LENGTH = 8;

	/**
	 * A test clip: a sum of two sines at the given frequencies.
	 *
	 * @param first  the first frequency, in Hz
	 * @param second the second frequency, in Hz
	 * @return the waveform, {@link #SAMPLES} samples
	 */
	private PackedCollection clip(double first, double second) {
		CollectionProducer time = integers(0, SAMPLES).divide(c((double) Clap2Config.SAMPLE_RATE));
		return sin(time.multiply(c(2 * Math.PI * first))).multiply(c(0.5))
				.add(sin(time.multiply(c(2 * Math.PI * second))).multiply(c(0.25))).evaluate();
	}

	/**
	 * Asserts that every value of a collection is finite and that it is not all zero.
	 *
	 * @param label the value's name, for the message
	 * @param value the value
	 */
	private void assertFiniteAndNonZero(String label, PackedCollection value) {
		double sum = 0;
		for (int i = 0; i < value.getShape().getTotalSize(); i++) {
			double v = value.toDouble(i);
			assertTrue(label + "[" + i + "] = " + v, Double.isFinite(v));
			sum += Math.abs(v);
		}
		assertTrue(label + " is all zero", sum > 0);
	}

	/** The audio tower embeds a clip into a finite vector that depends on the clip's content. */
	@Test(timeout = 600000)
	public void audioTowerProducesContentDependentEmbeddings() {
		StateDictionary weights = new Clap2WeightFixture().weights(7);
		Clap2 model = new Clap2(weights, TEXT_LENGTH);
		Clap2AudioEncoder audio = model.getAudioEncoder();

		PackedCollection low = audio.encode(clip(220, 330)).clone();
		PackedCollection high = audio.encode(clip(2500, 5100)).clone();

		assertEquals(Clap2WeightFixture.EMBED, low.getShape().getTotalSize());
		assertFiniteAndNonZero("low", low);
		assertFiniteAndNonZero("high", high);
		assertTrue("Different clips must embed differently", Clap2WeightFixture.cosine(low, high) < 0.9999);
		model.destroy();
	}

	/** Padding beyond a caption is masked: the compiled length does not change its embedding. */
	@Test(timeout = 600000)
	public void textEmbeddingIsIndependentOfTheCompiledLength() {
		StateDictionary weights = new Clap2WeightFixture().weights(11);
		Clap2TextEncoder shorter = new Clap2TextEncoder(Clap2Config.of(weights, TEXT_LENGTH), weights);
		Clap2TextEncoder longer = new Clap2TextEncoder(Clap2Config.of(weights, 2 * TEXT_LENGTH), weights);

		PackedCollection caption = pack(50259, 32, 4829, 13026, 50257);
		PackedCollection other = pack(50259, 32, 9000, 1500, 13, 50257);
		PackedCollection a = shorter.encode(caption).clone();
		PackedCollection b = longer.encode(caption).clone();
		PackedCollection c = shorter.encode(other).clone();

		assertEquals(Clap2WeightFixture.EMBED, a.getShape().getTotalSize());
		assertFiniteAndNonZero("caption", a);
		for (int i = 0; i < a.getShape().getTotalSize(); i++) {
			assertEquals("embedding[" + i + "]", a.toDouble(i), b.toDouble(i), 1e-4);
		}
		assertTrue("Different captions must embed differently", Clap2WeightFixture.cosine(a, c) < 0.9999);

		shorter.destroy();
		longer.destroy();
	}

	/**
	 * Runs one layer of the CLAP2 asset over a {@code [1, length, WIDTH]} input whose first
	 * {@code prompt} rows are {@code promptRows} and whose remaining rows are random, with a key
	 * mask that admits only the prompt rows.
	 *
	 * @param layer      the layer name
	 * @param arguments  the layer's arguments, without the masks and lengths
	 * @param promptRows the prompt rows, {@code prompt * WIDTH} values
	 * @param prompt     the number of prompt rows
	 * @param length     the padded length
	 * @return the layer output
	 */
	private PackedCollection runMasked(String layer, Map<String, Object> arguments, PackedCollection promptRows,
									   int prompt, int length) {
		int width = Clap2WeightFixture.WIDTH;
		PackedCollection padding = new PackedCollection(shape(length - prompt, width)).randnFill();
		PackedCollection input = concat(0, cp(promptRows).reshape(shape(prompt, width)), cp(padding))
				.reshape(shape(1, length, width)).evaluate();
		CollectionProducer mask = lessThan(integers(0, length), c((double) prompt)).reshape(shape(1, length));

		Map<String, Object> args = new HashMap<>(arguments);
		args.put("batch", 1);
		args.put("seq_len", length);
		args.put("heads", Clap2WeightFixture.HEADS);
		args.put("head_dim", Clap2WeightFixture.WIDTH / Clap2WeightFixture.HEADS);
		args.put("inner_dim", Clap2WeightFixture.TEXT_INNER);
		args.put("inv_freq", computeInvFreq(Clap2WeightFixture.WIDTH / Clap2WeightFixture.HEADS, Clap2Config.ROPE_THETA));
		args.put("padding_mask", everyPositionValid(1, length));
		args.put("key_mask", mask);
		args.put("epsilon", Clap2Config.NORM_EPSILON);

		PdslLoader loader = new PdslLoader();
		Block block = loader.buildLayer(loader.parseResource("/pdsl/clap2.pdsl"), layer, shape(1, length, width), args);
		Model model = new Model(shape(1, length, width));
		model.add(block);
		return model.compile(false).forward(input);
	}

	/**
	 * Padded rows reach neither the prompt rows of a text block nor the pooled vector: each stage
	 * of the text tower, run on the same prompt at two padded lengths, agrees on the prompt.
	 */
	@Test(timeout = 600000)
	public void textBlockAndPoolIgnorePaddedRows() {
		StateDictionary weights = new Clap2WeightFixture().weights(13);
		int width = Clap2WeightFixture.WIDTH;
		int prompt = 5;
		PackedCollection promptRows = new PackedCollection(shape(prompt, width)).randnFill();

		String b = "text.blocks.0.";
		Map<String, Object> block = new HashMap<>();
		block.put("norm1", weights.get(b + "norm1.weight"));
		block.put("qkv_weight", weights.get(b + "attn.qkv.weight"));
		block.put("q_norm", weights.get(b + "attn.q_norm.weight"));
		block.put("k_norm", weights.get(b + "attn.k_norm.weight"));
		block.put("proj_weight", weights.get(b + "attn.proj.weight"));
		block.put("norm2", weights.get(b + "norm2.weight"));
		block.put("w12", weights.get(b + "mlp.w12.weight"));
		block.put("w3", weights.get(b + "mlp.w3.weight"));
		PackedCollection shortBlock = runMasked("clap2_text_block", block, promptRows, prompt, 8);
		PackedCollection longBlock = runMasked("clap2_text_block", block, promptRows, prompt, 12);
		for (int i = 0; i < prompt * width; i++) {
			assertEquals("block output " + i, shortBlock.toDouble(i), longBlock.toDouble(i), 1e-4);
		}

		Map<String, Object> pool = new HashMap<>();
		pool.put("weights", weights);
		PackedCollection shortPool = runMasked("clap2_text_pool", pool, promptRows, prompt, 8);
		PackedCollection longPool = runMasked("clap2_text_pool", pool, promptRows, prompt, 12);
		assertEquals(width, shortPool.getShape().getTotalSize());
		for (int i = 0; i < width; i++) {
			assertEquals("pooled " + i, shortPool.toDouble(i), longPool.toDouble(i), 1e-4);
		}
	}

	/** The similarity is the cosine of the clip embedding with each caption embedding. */
	@Test(timeout = 120000)
	public void similarityIsTheCosineOfTheEmbeddings() {
		StateDictionary weights = new Clap2WeightFixture().weights(3);
		Clap2 model = new Clap2(weights, TEXT_LENGTH);

		int width = Clap2WeightFixture.EMBED;
		PackedCollection clipEmbedding = new PackedCollection(shape(width)).randnFill();
		PackedCollection others = new PackedCollection(shape(2, width)).randnFill();
		PackedCollection captions = concat(0, cp(clipEmbedding).reshape(shape(1, width)), cp(others)).evaluate();

		PackedCollection similarity = model.similarity(cp(clipEmbedding), cp(captions)).evaluate();
		PackedCollection logits = model.logits(cp(clipEmbedding), cp(captions)).evaluate();
		assertEquals(3, similarity.getShape().getTotalSize());
		assertEquals(1.0, similarity.toDouble(0), 1e-4);
		for (int k = 0; k < 3; k++) {
			PackedCollection row = captions.range(shape(width), k * width);
			assertEquals("similarity[" + k + "]", Clap2WeightFixture.cosine(clipEmbedding, row), similarity.toDouble(k), 1e-4);
			assertEquals("logit[" + k + "]", 100.0 * similarity.toDouble(k), logits.toDouble(k), 1e-2);
		}
	}

	/**
	 * The front end lays the patches out as the reference does: a register row that is zero
	 * except for its last column, then one row per {@code 16 x 16} patch, frequency row by
	 * frequency row, each patch holding its mel bands in band-major order, with the frames beyond
	 * the clip filled with its minimum.
	 */
	@Test(timeout = 300000)
	public void patchesFollowTheReferenceLayout() {
		StateDictionary weights = new Clap2WeightFixture().weights(5);
		Clap2AudioEncoder audio = new Clap2AudioEncoder(Clap2Config.of(weights, TEXT_LENGTH), weights);
		PackedCollection waveform = clip(440, 3000);

		PackedCollection mel = audio.logMelSpectrogram(Clap2Config.FFT_SIZE, Clap2Config.HOP_SIZE,
				Clap2Config.SAMPLE_RATE, Clap2Config.MEL_BANDS, Clap2Config.MEL_MIN_HZ,
				Clap2Config.MEL_MAX_HZ, cp(waveform)).evaluate();
		PackedCollection patches = audio.patches(cp(waveform)).evaluate();

		int frames = 1 + SAMPLES / Clap2Config.HOP_SIZE;
		int timePatches = Clap2Config.timePatches(SAMPLES);
		int width = Clap2Config.PATCH_SIZE + 1;
		assertEquals(4, timePatches);
		assertEquals((1 + Clap2Config.FREQUENCY_PATCHES * timePatches) * width, patches.getShape().getTotalSize());

		double floor = Double.MAX_VALUE;
		for (int i = 0; i < mel.getShape().getTotalSize(); i++) {
			floor = Math.min(floor, mel.toDouble(i));
		}

		for (int j = 0; j < width; j++) {
			assertEquals("register[" + j + "]", j == width - 1 ? 1.0 : 0.0, patches.toDouble(j), 1e-6);
		}

		for (int f = 0; f < Clap2Config.FREQUENCY_PATCHES; f++) {
			for (int t = 0; t < timePatches; t++) {
				int row = 1 + f * timePatches + t;
				for (int pf = 0; pf < Clap2Config.PATCH_FREQ; pf++) {
					for (int pt = 0; pt < Clap2Config.PATCH_TIME; pt++) {
						int frame = t * Clap2Config.PATCH_TIME + pt;
						int band = f * Clap2Config.PATCH_FREQ + pf;
						double expected = frame < frames ? mel.toDouble(frame * Clap2Config.MEL_BANDS + band) : floor;
						int column = pf * Clap2Config.PATCH_TIME + pt;
						assertEquals("patch (" + f + ", " + t + ") value " + column,
								expected, patches.toDouble(row * width + column), 1e-4);
					}
				}
				assertEquals(0.0, patches.toDouble(row * width + width - 1), 1e-6);
			}
		}
	}

	/**
	 * The axial angles leave the register token unrotated and turn the first half of every head
	 * by the patch's frequency row and the second half by its time column.
	 */
	@Test(timeout = 120000)
	public void axialAnglesFollowThePatchGrid() {
		StateDictionary weights = new Clap2WeightFixture().weights(5);
		Clap2Config config = Clap2Config.of(weights, TEXT_LENGTH);
		Clap2AudioEncoder audio = new Clap2AudioEncoder(config, weights);

		int timePatches = 3;
		PackedCollection angles = audio.axialAngles(timePatches).evaluate();
		int pairs = config.getAudioHeadDim() / 2;
		int quarter = pairs / 2;
		int rows = 1 + Clap2Config.FREQUENCY_PATCHES * timePatches;
		assertEquals(rows * pairs, angles.getShape().getTotalSize());

		for (int p = 0; p < rows; p++) {
			int f = p == 0 ? 0 : (p - 1) / timePatches;
			int t = p == 0 ? 0 : (p - 1) % timePatches;
			for (int i = 0; i < pairs; i++) {
				double band = Math.pow(Clap2Config.ROPE_THETA, -(double) (i % quarter) / quarter);
				double expected = p == 0 ? 0.0 : (i < quarter ? f : t) * band;
				assertEquals("angle[" + p + ", " + i + "]", expected, angles.toDouble(p * pairs + i), 1e-5);
			}
		}
	}

	/** A caption longer than the compiled length is rejected before any kernel reads it. */
	@Test(timeout = 60000)
	public void overlongCaptionIsRejected() {
		StateDictionary weights = new Clap2WeightFixture().weights(13);
		Clap2TextEncoder text = new Clap2TextEncoder(Clap2Config.of(weights, TEXT_LENGTH), weights);
		try {
			text.encode(new PackedCollection(shape(TEXT_LENGTH + 1)).fill(32));
			Assert.fail("A caption of " + (TEXT_LENGTH + 1) + " tokens must be rejected");
		} catch (IllegalArgumentException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("exceeds the compiled length " + TEXT_LENGTH));
		} finally {
			text.destroy();
		}
	}

	/**
	 * The extreme caption lengths the encoder accepts, one token and exactly the compiled length, both
	 * embed to finite vectors, and the one-token caption embeds differently from the full one.
	 */
	@Test(timeout = 600000)
	public void captionLengthBoundariesEncode() {
		StateDictionary weights = new Clap2WeightFixture().weights(13);
		Clap2TextEncoder text = new Clap2TextEncoder(Clap2Config.of(weights, TEXT_LENGTH), weights);

		PackedCollection single = text.encode(pack(50259)).clone();
		PackedCollection full = text.encode(pack(50259, 32, 4829, 13026, 9000, 1500, 13, 50257)).clone();

		assertEquals(Clap2WeightFixture.EMBED, single.getShape().getTotalSize());
		assertFiniteAndNonZero("single", single);
		assertFiniteAndNonZero("full", full);
		assertTrue("A one-token caption must embed differently from a full one",
				Clap2WeightFixture.cosine(single, full) < 0.9999);
		text.destroy();
	}
}
