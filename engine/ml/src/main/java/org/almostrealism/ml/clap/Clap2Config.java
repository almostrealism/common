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

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.ml.StateDictionary;

import java.util.HashMap;
import java.util.Map;

/**
 * The architecture of a CLAP2 contrastive audio-text model: the widths, depths and head counts of
 * its audio and text towers, the shared embedding width, and the fixed audio front end (32 kHz
 * mono, a 128-band log-mel spectrogram with a 1024-sample window and a 320-sample hop, cut into
 * 16 x 16 patches).
 *
 * <p>Every size of the released family (XXS to L) shares the front end and differs only in the
 * tower dimensions, so a configuration is read from the weights themselves by
 * {@link #of(StateDictionary, int)}: the widths and inner sizes from the tensor shapes, the layer
 * counts from the block groups, the text head count from the width of the per-head query norm,
 * and the audio head count, which no weight shape determines, from the {@link #AUDIO_HEADS_KEY}
 * entry the extraction script writes beside the weights.</p>
 */
public final class Clap2Config {

	/** The sample rate the audio tower expects, in Hz. */
	public static final int SAMPLE_RATE = 32000;

	/** The STFT window and FFT size of the front end. */
	public static final int FFT_SIZE = 1024;

	/** The STFT hop of the front end, in samples (100 frames per second at 32 kHz). */
	public static final int HOP_SIZE = 320;

	/** The number of mel bands. */
	public static final int MEL_BANDS = 128;

	/** The lowest mel point, in Hz. */
	public static final double MEL_MIN_HZ = 50.0;

	/** The highest mel point, in Hz. */
	public static final double MEL_MAX_HZ = 16000.0;

	/** The number of mel bands one patch spans. */
	public static final int PATCH_FREQ = 16;

	/** The number of frames one patch spans. */
	public static final int PATCH_TIME = 16;

	/** The number of values in one flattened patch: its mel bands times its frames. */
	public static final int PATCH_SIZE = PATCH_FREQ * PATCH_TIME;

	/** The number of patch rows along the frequency axis. */
	public static final int FREQUENCY_PATCHES = MEL_BANDS / PATCH_FREQ;

	/**
	 * The entry that records the audio head count, which no weight shape determines: the
	 * extraction script writes a tensor with one element per head under this key (its values are
	 * unused; only its length is read). The text head count follows from the width of the text
	 * query norm, which spans one head.
	 */
	public static final String AUDIO_HEADS_KEY = "config.audio.heads";

	/** The rotary base of both towers. */
	public static final double ROPE_THETA = 10000.0;

	/** The epsilon of every RMS norm of both towers. */
	public static final double NORM_EPSILON = 1e-6;

	/** Token id that opens every caption. */
	public static final int BOS_TOKEN = 50259;

	/** Token id that closes every caption. */
	public static final int EOS_TOKEN = 50257;

	/** Token id that fills the positions beyond a caption. */
	public static final int PAD_TOKEN = 50258;

	/** Width of the audio trunk. */
	private final int audioWidth;

	/** Number of audio blocks. */
	private final int audioLayers;

	/** Number of audio attention heads (also used by the audio pool). */
	private final int audioHeads;

	/** Inner width of each audio SwiGLU feed-forward. */
	private final int audioInnerSize;

	/** Width of the text trunk. */
	private final int textWidth;

	/** Number of text blocks. */
	private final int textLayers;

	/** Number of text attention heads (also used by the text pool). */
	private final int textHeads;

	/** Inner width of each text SwiGLU feed-forward. */
	private final int textInnerSize;

	/** Number of tokens in the text vocabulary. */
	private final int vocabularySize;

	/** Width of the shared audio-text embedding space. */
	private final int embedDim;

	/** Number of token positions the text tower is compiled for. */
	private final int maxTextLength;

	/**
	 * Creates a configuration.
	 *
	 * @param audioWidth     width of the audio trunk
	 * @param audioLayers    number of audio blocks
	 * @param audioHeads     number of audio attention heads
	 * @param audioInnerSize inner width of each audio feed-forward
	 * @param textWidth      width of the text trunk
	 * @param textLayers     number of text blocks
	 * @param textHeads      number of text attention heads
	 * @param textInnerSize  inner width of each text feed-forward
	 * @param vocabularySize number of tokens in the vocabulary
	 * @param embedDim       width of the shared embedding space
	 * @param maxTextLength  number of token positions the text tower is compiled for
	 * @throws IllegalArgumentException if a head count, width, layer count, inner size, vocabulary
	 *                                  size, embedding width or text length is not positive, a
	 *                                  width does not divide
	 *                                  into its heads, an audio head width is not a multiple of
	 *                                  four (the axial rotary embedding gives each of its two axes a
	 *                                  quarter of the head's features in pairs), or a text head width
	 *                                  is odd (its rotary embedding rotates features in pairs)
	 */
	public Clap2Config(int audioWidth, int audioLayers, int audioHeads, int audioInnerSize,
					   int textWidth, int textLayers, int textHeads, int textInnerSize,
					   int vocabularySize, int embedDim, int maxTextLength) {
		if (audioHeads <= 0 || textHeads <= 0) {
			throw new IllegalArgumentException("Head counts must be positive, but the audio tower has "
					+ audioHeads + " and the text tower " + textHeads);
		}
		requirePositive("audioWidth", audioWidth);
		requirePositive("audioLayers", audioLayers);
		requirePositive("audioInnerSize", audioInnerSize);
		requirePositive("textWidth", textWidth);
		requirePositive("textLayers", textLayers);
		requirePositive("textInnerSize", textInnerSize);
		requirePositive("vocabularySize", vocabularySize);
		requirePositive("embedDim", embedDim);
		requirePositive("maxTextLength", maxTextLength);
		if (audioWidth % audioHeads != 0 || (audioWidth / audioHeads) % 4 != 0) {
			throw new IllegalArgumentException("Audio width " + audioWidth + " must divide into "
					+ audioHeads + " heads whose width is a multiple of 4");
		}
		if (textWidth % textHeads != 0 || (textWidth / textHeads) % 2 != 0) {
			throw new IllegalArgumentException("Text width " + textWidth + " must divide into "
					+ textHeads + " heads of even width");
		}

		this.audioWidth = audioWidth;
		this.audioLayers = audioLayers;
		this.audioHeads = audioHeads;
		this.audioInnerSize = audioInnerSize;
		this.textWidth = textWidth;
		this.textLayers = textLayers;
		this.textHeads = textHeads;
		this.textInnerSize = textInnerSize;
		this.vocabularySize = vocabularySize;
		this.embedDim = embedDim;
		this.maxTextLength = maxTextLength;
	}

	/**
	 * Rejects a size of the architecture that is not positive.
	 *
	 * @param name  the name of the size, for the exception's message
	 * @param value the size
	 * @throws IllegalArgumentException if {@code value} is zero or negative
	 */
	private static void requirePositive(String name, int value) {
		if (value <= 0) {
			throw new IllegalArgumentException(name + " must be positive, but is " + value);
		}
	}

	/**
	 * Reads the configuration of a checkpoint from its weights, compiling the text tower for
	 * {@code maxTextLength} token positions (captions are short, so far fewer than the 256 the
	 * model was trained with; padding is masked, so the length does not change any embedding).
	 *
	 * @param weights       the weights, as the CLAP2 extraction script writes them
	 * @param maxTextLength the number of token positions to compile the text tower for
	 * @return the configuration
	 * @throws IllegalArgumentException if a tensor or head count the configuration needs is missing
	 */
	public static Clap2Config of(StateDictionary weights, int maxTextLength) {
		PackedCollection patchEmbed = weights.require("audio.patch_embed.weight");
		PackedCollection audioFc2 = weights.require("audio.blocks.0.mlp.fc2.weight");
		PackedCollection tokenEmbedding = weights.require("text.token_embedding.weight");
		PackedCollection textW3 = weights.require("text.blocks.0.mlp.w3.weight");
		PackedCollection projection = weights.require("text.projection.weight");
		int textWidth = tokenEmbedding.getShape().length(1);
		int textHeadDim = weights.require("text.blocks.0.attn.q_norm.weight").getShape().getTotalSize();

		return new Clap2Config(
				patchEmbed.getShape().length(0),
				weights.group("audio.blocks").members().size(),
				weights.require(AUDIO_HEADS_KEY).getShape().getTotalSize(),
				audioFc2.getShape().length(1),
				textWidth,
				weights.group("text.blocks").members().size(),
				textWidth / textHeadDim,
				textW3.getShape().length(1),
				tokenEmbedding.getShape().length(0),
				projection.getShape().length(0),
				maxTextLength);
	}

	/**
	 * The number of spectrogram frames of a clip: the STFT is centered, so a clip yields one frame
	 * per hop plus one.
	 *
	 * @param samples the number of samples in the clip
	 * @return the number of frames
	 */
	public static int frames(int samples) {
		return 1 + samples / HOP_SIZE;
	}

	/**
	 * The number of time patches of a clip: its {@link #frames(int) frames} rounded up to whole
	 * patches.
	 *
	 * @param samples the number of samples in the clip
	 * @return the number of patches along the time axis
	 */
	public static int timePatches(int samples) {
		return (frames(samples) + PATCH_TIME - 1) / PATCH_TIME;
	}

	/**
	 * The length of the audio tower's sequence: the register token followed by every patch of the
	 * {@link #FREQUENCY_PATCHES} by {@code timePatches} grid.
	 *
	 * @param timePatches the number of patches along the time axis
	 * @return the number of sequence positions
	 */
	public static int audioSequenceLength(int timePatches) {
		return 1 + FREQUENCY_PATCHES * timePatches;
	}

	/**
	 * Returns the architecture of the audio tower as arguments of its PDSL layer: {@code heads},
	 * {@code head_dim}, {@code inner_dim}, {@code embed_dim} and {@code epsilon}. The caller adds
	 * the entries that depend on the clip and the weights.
	 *
	 * @return a mutable map from PDSL argument name to value
	 */
	public Map<String, Object> toAudioPdslSettings() {
		return towerSettings(getAudioHeads(), getAudioHeadDim(), getAudioInnerSize());
	}

	/**
	 * Returns the architecture of the text tower as arguments of its PDSL layer: {@code heads},
	 * {@code head_dim}, {@code inner_dim}, {@code embed_dim}, {@code seq_len} (the compiled text
	 * length) and {@code epsilon}. The caller adds the entries that depend on the weights.
	 *
	 * @return a mutable map from PDSL argument name to value
	 */
	public Map<String, Object> toTextPdslSettings() {
		Map<String, Object> settings = towerSettings(getTextHeads(), getTextHeadDim(), getTextInnerSize());
		settings.put("seq_len", maxTextLength);
		return settings;
	}

	/**
	 * The PDSL arguments every tower shares, for one tower's head layout.
	 *
	 * @param heads    the tower's attention heads
	 * @param headDim  the width of one head
	 * @param innerDim the inner width of the tower's feed-forwards
	 * @return a mutable map from PDSL argument name to value
	 */
	private Map<String, Object> towerSettings(int heads, int headDim, int innerDim) {
		Map<String, Object> settings = new HashMap<>();
		settings.put("heads", heads);
		settings.put("head_dim", headDim);
		settings.put("inner_dim", innerDim);
		settings.put("embed_dim", embedDim);
		settings.put("epsilon", NORM_EPSILON);
		return settings;
	}

	/** The width of the audio trunk. */
	public int getAudioWidth() { return audioWidth; }

	/** The number of audio blocks. */
	public int getAudioLayers() { return audioLayers; }

	/** The number of audio attention heads. */
	public int getAudioHeads() { return audioHeads; }

	/** The width of one audio head. */
	public int getAudioHeadDim() { return audioWidth / audioHeads; }

	/** The inner width of each audio feed-forward. */
	public int getAudioInnerSize() { return audioInnerSize; }

	/** The width of the text trunk. */
	public int getTextWidth() { return textWidth; }

	/** The number of text blocks. */
	public int getTextLayers() { return textLayers; }

	/** The number of text attention heads. */
	public int getTextHeads() { return textHeads; }

	/** The width of one text head. */
	public int getTextHeadDim() { return textWidth / textHeads; }

	/** The inner width of each text feed-forward. */
	public int getTextInnerSize() { return textInnerSize; }

	/** The number of tokens in the vocabulary. */
	public int getVocabularySize() { return vocabularySize; }

	/** The width of the shared embedding space. */
	public int getEmbedDim() { return embedDim; }

	/** The number of token positions the text tower is compiled for. */
	public int getMaxTextLength() { return maxTextLength; }
}
