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

package org.almostrealism.ml;

import io.almostrealism.compute.ParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.model.Block;
import org.almostrealism.model.CompiledModel;
import org.almostrealism.model.Model;
import org.almostrealism.util.TestSuiteBase;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared infrastructure for the {@link TransformerResamplingFeatures} tests: the SAME-S encoder and
 * decoder {@link ResamplingConfig}s, a top-of-stack producer evaluator, and flat reference-comparison
 * statistics. Subclasses supply either synthetic weights (the in-CI shape/composition test) or the
 * real released SAME weights (the gated numerical-parity test).
 */
public abstract class SAMEResamplingTestBase extends TestSuiteBase implements TransformerResamplingFeatures {

	/**
	 * Evaluates a producer at the test boundary (top of the call stack), applying the optimization pass
	 * the framework requires for standalone producer evaluation.
	 *
	 * @param producer the producer to evaluate
	 * @return the evaluated collection
	 */
	protected PackedCollection eval(Producer<PackedCollection> producer) {
		return ((Evaluable<PackedCollection>) ((ParallelProcess) producer).optimize().get()).evaluate();
	}

	/**
	 * Compiles a resampling {@link Block} into an inference {@link Model} and runs a single forward pass
	 * on {@code input}. This is the top-of-stack boundary at which compilation and evaluation are
	 * permitted; the block itself never evaluates.
	 *
	 * @param block the resampling block (from {@link TransformerResamplingFeatures#transformerResamplingBlock})
	 * @param input the block input, matching {@link Block#getInputShape()}
	 * @return the forward-pass output
	 */
	protected PackedCollection evalBlock(Block block, PackedCollection input) {
		Model model = new Model(block.getInputShape());
		model.add(block);
		CompiledModel compiled = model.compile(false);
		return compiled.forward(input);
	}

	/**
	 * The SAME-S encoder resampling-block configuration: {@code 512 -> 768} channels, stride 16,
	 * 12 heads of dimension 64, depth 6, chunk size 32 with midpoint shift, {@code 1x1} mapping.
	 *
	 * @return the encoder configuration
	 */
	protected ResamplingConfig sameEncoderConfig() {
		return new ResamplingConfig(512, 768, 12, 64, 16, 32, 6,
				true, true, true, 3.0, 1, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/**
	 * The SAME-S decoder resampling-block configuration: {@code 768 -> 512} channels, stride 16,
	 * 12 heads of dimension 64, depth 6, chunk size 32 with midpoint shift, {@code 3} -kernel mapping.
	 *
	 * @return the decoder configuration
	 */
	protected ResamplingConfig sameDecoderConfig() {
		return new ResamplingConfig(768, 512, 12, 64, 16, 32, 6,
				false, true, true, 3.0, 3, ResamplingConfig.AttentionWindow.CHUNKED);
	}

	/**
	 * The full set of weight keys a resampling block reads, mapped to their tensor shapes. Both the
	 * synthetic-weight shape test and the real-weight parity test build their {@link StateDictionary}
	 * from this single source so the key set and shapes cannot drift between them.
	 *
	 * @param config the block configuration
	 * @param prefix the weight key prefix (e.g. {@code "encoder.layers.0"})
	 * @return an insertion-ordered map of weight key to shape
	 */
	protected Map<String, int[]> blockWeightShapes(ResamplingConfig config, String prefix) {
		int dim = config.getDim();
		int dimHead = config.getDimHead();
		int inner = config.getInnerFfDim();
		int invFreqLen = Math.max(1, dimHead / 4);

		Map<String, int[]> shapes = new LinkedHashMap<>();
		shapes.put(prefix + ".mapping.weight",
				new int[]{config.getOutChannels(), config.getInChannels(), config.getMappingKernel()});
		shapes.put(prefix + ".mapping.bias", new int[]{config.getOutChannels()});
		shapes.put(prefix + ".new_tokens", new int[]{1, 1, dim});

		for (int i = 0; i < config.getDepth(); i++) {
			String lk = prefix + ".transformers." + i;
			shapes.put(lk + ".pre_norm.alpha", new int[]{1});
			shapes.put(lk + ".pre_norm.gamma", new int[]{dim});
			shapes.put(lk + ".pre_norm.beta", new int[]{dim});
			shapes.put(lk + ".ff_norm.alpha", new int[]{1});
			shapes.put(lk + ".ff_norm.gamma", new int[]{dim});
			shapes.put(lk + ".ff_norm.beta", new int[]{dim});
			shapes.put(lk + ".self_attn.to_qkv.weight", new int[]{5 * dim, dim});
			shapes.put(lk + ".self_attn.to_out.weight", new int[]{dim, dim});
			shapes.put(lk + ".self_attn.q_norm.alpha", new int[]{1});
			shapes.put(lk + ".self_attn.q_norm.gamma", new int[]{dimHead});
			shapes.put(lk + ".self_attn.q_norm.beta", new int[]{dimHead});
			shapes.put(lk + ".self_attn.k_norm.alpha", new int[]{1});
			shapes.put(lk + ".self_attn.k_norm.gamma", new int[]{dimHead});
			shapes.put(lk + ".self_attn.k_norm.beta", new int[]{dimHead});
			shapes.put(lk + ".rope.inv_freq", new int[]{invFreqLen});
			shapes.put(lk + ".ff.ff.0.proj.weight", new int[]{2 * inner, dim});
			shapes.put(lk + ".ff.ff.0.proj.bias", new int[]{2 * inner});
			shapes.put(lk + ".ff.ff.2.weight", new int[]{dim, inner});
			shapes.put(lk + ".ff.ff.2.bias", new int[]{dim});
		}
		return shapes;
	}

	/**
	 * Computes element-wise difference statistics between a flattened computed collection and a flat
	 * reference array of the same length.
	 *
	 * @param actual    the computed collection
	 * @param reference the flat reference values
	 * @return {@code [maxAbsDiff, meanAbsDiff, rmse, maxAbsReference]}
	 */
	protected double[] diffStats(PackedCollection actual, float[] reference) {
		double[] a = actual.toArray(0, actual.getShape().getTotalSize());
		if (a.length != reference.length) {
			throw new IllegalArgumentException(
					"Length mismatch: computed " + a.length + " vs reference " + reference.length);
		}

		double maxAbs = 0;
		double sumAbs = 0;
		double sumSq = 0;
		double maxRef = 0;
		for (int i = 0; i < a.length; i++) {
			double d = Math.abs(a[i] - reference[i]);
			maxAbs = Math.max(maxAbs, d);
			sumAbs += d;
			sumSq += d * d;
			maxRef = Math.max(maxRef, Math.abs(reference[i]));
		}
		return new double[]{maxAbs, sumAbs / a.length, Math.sqrt(sumSq / a.length), maxRef};
	}

	/**
	 * Convenience wrapper returning the maximum absolute difference between a computed collection and a
	 * flat reference.
	 *
	 * @param actual    the computed collection
	 * @param reference the flat reference values
	 * @return the maximum absolute difference
	 */
	protected double maxAbsDiff(PackedCollection actual, float[] reference) {
		return diffStats(actual, reference)[0];
	}
}
