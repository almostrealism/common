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

package org.almostrealism.ml.midi;

/**
 * Hyperparameter configuration for the SkyTNT midi-model.
 *
 * <p>The default no-arg constructor creates the tv2o-medium configuration, which
 * corresponds to the {@code skytnt/midi-model-tv2o-medium} checkpoint on HuggingFace.
 * The model consists of two independent LLaMA-style transformers and one shared LM head:</p>
 * <ul>
 *   <li>{@code net} — 12-layer main sequence transformer</li>
 *   <li>{@code net_token} — 3-layer token transformer for within-event generation</li>
 *   <li>{@code lm_head} — shared Linear(1024 to 3406, no bias)</li>
 * </ul>
 *
 * <p>A secondary constructor accepting explicit dimensions is provided to support
 * smaller synthetic configurations for unit testing without real model weights.</p>
 *
 * @see SkyTntMidi
 * @see SkyTntTokenizerV2
 */
public class SkyTntConfig {

    // -----------------------------------------------------------------------
    // Shared
    // -----------------------------------------------------------------------

    /** Vocabulary size (flat token count). Shared between both transformers. */
    public final int vocabSize;

    /** Hidden / embedding dimension for both transformers. */
    public final int hiddenSize;

    /** PAD token ID. */
    public final int padId = 0;

    /** BOS token ID. */
    public final int bosId = 1;

    /** EOS token ID. */
    public final int eosId = 2;

    /** Maximum token-sequence positions per event (one event = up to 8 token IDs). */
    public final int maxTokenSeq = 8;

    /** RMSNorm epsilon used by both transformers. */
    public final double rmsNormEps;

    /** RoPE theta for standard position encoding, used by both transformers. */
    public final double ropeTheta;

    // -----------------------------------------------------------------------
    // Main transformer (net)
    // -----------------------------------------------------------------------

    /** Number of LLaMA layers in the main transformer. */
    public final int netLayers;

    /** Number of attention heads (Q = KV, no GQA) in the main transformer. */
    public final int netHeads;

    /** Attention head dimension: hiddenSize / netHeads. */
    public final int netHeadSize;

    /** FFN intermediate dimension for the main transformer (SwiGLU). */
    public final int netIntermediateSize;

    /** Maximum event-sequence length processed by the main transformer. */
    public final int maxEventSeqLen;

    // -----------------------------------------------------------------------
    // Token transformer (net_token)
    // -----------------------------------------------------------------------

    /** Number of LLaMA layers in the token transformer. */
    public final int netTokenLayers;

    /** Number of attention heads in the token transformer. */
    public final int netTokenHeads;

    /** Attention head dimension: hiddenSize / netTokenHeads. */
    public final int netTokenHeadSize;

    /** FFN intermediate dimension for the token transformer (1:1 ratio in medium). */
    public final int netTokenIntermediateSize;

    /**
     * Create the default tv2o-medium configuration.
     *
     * <ul>
     *   <li>net: 12 layers, hidden=1024, heads=16, ffn=4096</li>
     *   <li>net_token: 3 layers, hidden=1024, heads=4, ffn=1024</li>
     *   <li>vocab=3406, maxEventSeqLen=4096, ropeTheta=10000, rmsNormEps=1e-5</li>
     * </ul>
     */
    public SkyTntConfig() {
        this(3406, 1024, 1e-5, 10000.0,
                12, 16, 4096, 4096,
                3, 4, 1024);
    }

    /**
     * Create a SkyTntConfig with explicit dimensions.
     *
     * <p>This constructor is primarily intended for unit tests that need a smaller
     * synthetic configuration without real model weights.</p>
     *
     * @param vocabSize               shared vocabulary size
     * @param hiddenSize              embedding / hidden dimension
     * @param rmsNormEps              RMSNorm epsilon
     * @param ropeTheta               RoPE theta
     * @param netLayers               number of main-transformer layers
     * @param netHeads                number of main-transformer attention heads
     * @param netIntermediateSize     main-transformer FFN intermediate size
     * @param maxEventSeqLen          maximum event-sequence length
     * @param netTokenLayers          number of token-transformer layers
     * @param netTokenHeads           number of token-transformer attention heads
     * @param netTokenIntermediateSize token-transformer FFN intermediate size
     */
    public SkyTntConfig(int vocabSize, int hiddenSize, double rmsNormEps, double ropeTheta,
                        int netLayers, int netHeads, int netIntermediateSize, int maxEventSeqLen,
                        int netTokenLayers, int netTokenHeads, int netTokenIntermediateSize) {
        this.vocabSize = vocabSize;
        this.hiddenSize = hiddenSize;
        this.rmsNormEps = rmsNormEps;
        this.ropeTheta = ropeTheta;
        this.netLayers = netLayers;
        this.netHeads = netHeads;
        this.netHeadSize = hiddenSize / netHeads;
        this.netIntermediateSize = netIntermediateSize;
        this.maxEventSeqLen = maxEventSeqLen;
        this.netTokenLayers = netTokenLayers;
        this.netTokenHeads = netTokenHeads;
        this.netTokenHeadSize = hiddenSize / netTokenHeads;
        this.netTokenIntermediateSize = netTokenIntermediateSize;
    }
}
