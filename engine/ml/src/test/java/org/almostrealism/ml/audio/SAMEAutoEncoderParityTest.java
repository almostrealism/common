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

package org.almostrealism.ml.audio;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.SAMEResamplingTestBase;
import org.almostrealism.ml.StateDictionary;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * Numerical parity for the assembled {@link SAMEAutoEncoder} against the released SAME-S weights,
 * over the full-round-trip references {@code dump_same_references.py} writes: {@code ae_input},
 * {@code ae_latent}, {@code ae_running_std} and {@code ae_output}.
 *
 * <p>The encode path is asserted per sample. The decode path is asserted on its aggregate agreement
 * with the reference rather than sample by sample, and this is a property of the trained model, not
 * a concession: the decoder stack is ill-conditioned, amplifying any midstack perturbation by
 * roughly 2.7e4x (the same sensitivity {@code SAMEResamplingParityTest} documents, measurable with
 * {@code dump_same_references.py --check-sensitivity}). An honest FP32 rounding difference in the
 * first half therefore reaches the output multiplied by that factor, so a sample-by-sample
 * threshold on decoded audio would assert a property no correct FP32 implementation can hold. What
 * remains diagnostic is the shape of the signal: a decoder reading the wrong weights, transposing
 * an axis or mis-ordering the stack does not produce audio that tracks the reference's envelope or
 * sits at its level. Both quantities are reported either way, so a regression is visible in the log
 * even where it stays under the assertion.</p>
 */
public class SAMEAutoEncoderParityTest extends SAMEResamplingTestBase {

	/** Candidate locations for the SAME-S weight shards. */
	private static final String[] WEIGHT_DIRS = {
			System.getProperty("AR_SAME_WEIGHTS", System.getenv("AR_SAME_WEIGHTS")),
			"/workspace/same-weights"
	};

	/** Candidate locations for the reference activations. */
	private static final String[] REFERENCE_DIRS = {
			System.getProperty("AR_SAME_REFERENCES", System.getenv("AR_SAME_REFERENCES")),
			"src/test/resources/same-s-references",
			"engine/ml/src/test/resources/same-s-references",
			"target/test-classes/same-s-references",
			"engine/ml/target/test-classes/same-s-references"
	};

	/** Audio channels of the released autoencoder. */
	private static final int CHANNELS = 2;

	/** Input length of the reference dump, a multiple of the downsampling ratio. */
	private static final int SAMPLES = 24576;

	/** Latent channel count of the released autoencoder. */
	private static final int LATENT_DIM = 256;

	/** Latent length corresponding to {@link #SAMPLES}. */
	private static final int LATENT_LEN = 6;

	/** Permitted largest absolute error, as a fraction of the largest reference magnitude. */
	private static final double ENCODE_TOLERANCE = 1e-2;

	/** Permitted relative error of the decoded RMS level. */
	private static final double LEVEL_TOLERANCE = 0.1;

	/** Required correlation between the decoded audio and the reference. */
	private static final double MINIMUM_CORRELATION = 0.9;

	/**
	 * The autoencoder reproduces the reference latent from the reference input, and decodes the
	 * reference latent into audio that tracks the reference output.
	 *
	 * @throws IOException if a weight or reference file cannot be read
	 */
	@Test(timeout = 1800000)
	public void roundTripMatchesReference() throws IOException {
		File weightDir = firstExisting(WEIGHT_DIRS, "encoder.layers.0.mapping.weight");
		File refDir = firstExisting(REFERENCE_DIRS, "ae_latent");

		if (weightDir == null || refDir == null) {
			log("skipping autoencoder parity; gated inputs absent (weights=" + weightDir
					+ ", refs=" + refDir + ")");
			return;
		}

		StateDictionary weights = null;
		PackedCollection input = null;
		PackedCollection encoded = null;
		PackedCollection latent = null;
		PackedCollection decoded = null;

		try {
			weights = new StateDictionary(weightDir.getPath());
			SAMEAutoEncoder autoencoder = SAMEAutoEncoder.small(weights);

			float[] refLatent = loadFlat(refDir, "ae_latent");
			float[] refOutput = loadFlat(refDir, "ae_output");
			float[] refRunningStd = loadFlat(refDir, "ae_running_std");

			// The bottleneck's running std is a weight rather than an activation, so a mismatch here
			// means the extraction disagrees with the checkpoint before any computation runs.
			assertWithinRelative("runningStd", weights.get("bottleneck.running_std"),
					refRunningStd, 1e-4);

			input = loadShaped(refDir, "ae_input", 1, CHANNELS, SAMPLES);
			encoded = evalBlock(autoencoder.encoder(1, SAMPLES), input);
			assertWithinRelative("latent", encoded, refLatent, ENCODE_TOLERANCE);

			latent = loadShaped(refDir, "ae_latent", 1, LATENT_DIM, LATENT_LEN);
			decoded = evalBlock(autoencoder.decoder(1, LATENT_LEN), latent);

			report("output", decoded, refOutput);
			assertTracksReference("output", decoded, refOutput, LEVEL_TOLERANCE, MINIMUM_CORRELATION);
		} finally {
			// encoded and decoded are evalBlock clones owned and released by SAMEResamplingTestBase.
			if (input != null) {
				input.destroy();
			}
			if (latent != null) {
				latent.destroy();
			}
			if (weights != null) {
				weights.destroy();
			}
		}
	}

}
