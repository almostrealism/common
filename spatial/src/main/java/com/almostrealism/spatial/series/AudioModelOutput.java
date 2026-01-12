/*
 * Copyright 2025 Michael Murray
 * All Rights Reserved
 */

package com.almostrealism.spatial.series;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.almostrealism.collect.PackedCollection;

import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

/**
 * A timeseries representing output from a generative audio ML model.
 *
 * <p>{@code AudioModelOutput} extends {@link SimpleTimeseries} to carry
 * additional information about ML-generated audio:</p>
 * <ul>
 *   <li>Model name - identifies which model generated the audio</li>
 *   <li>Conditional text - the text prompt or conditions used</li>
 *   <li>Embedding vector - the latent representation used for generation</li>
 * </ul>
 *
 * <h2>Usage Examples</h2>
 *
 * <h3>With text conditioning:</h3>
 * <pre>{@code
 * AudioModelOutput output = new AudioModelOutput("generated_001", "ambient synth pad");
 * output.setModelName("stable-audio");
 * }</pre>
 *
 * <h3>With random embedding:</h3>
 * <pre>{@code
 * AudioModelOutput output = new AudioModelOutput("generated_002", 512);
 * PackedCollection embed = output.getPackedEmbed();
 * }</pre>
 *
 * @see SimpleTimeseries
 * @see PackedCollection
 */
public class AudioModelOutput extends SimpleTimeseries<AudioModelOutput> {

	private String modelName;
	private String conditions;
	private List<Double> embed;

	/**
	 * Creates an empty audio model output.
	 */
	public AudioModelOutput() { }

	/**
	 * Creates an audio model output with the specified key.
	 *
	 * @param key the unique identifier
	 */
	public AudioModelOutput(String key) {
		this(key, null, null);
	}

	/**
	 * Creates an audio model output with key and conditional text.
	 *
	 * @param key        the unique identifier
	 * @param conditions the conditional text prompt
	 */
	public AudioModelOutput(String key, String conditions) {
		this(key, conditions, null);
	}

	/**
	 * Creates an audio model output with key and embedding vector.
	 *
	 * @param key   the unique identifier
	 * @param embed the embedding vector
	 */
	public AudioModelOutput(String key, List<Double> embed) {
		this(key, null, embed);
	}

	/**
	 * Creates an audio model output with key and random embedding.
	 *
	 * @param key      the unique identifier
	 * @param embedDim the dimension of the random embedding vector
	 */
	public AudioModelOutput(String key, int embedDim) {
		this(key, null, embedDim);
	}

	/**
	 * Creates an audio model output with key, conditions, and random embedding.
	 *
	 * <p>Generates a random Gaussian embedding vector of the specified dimension.</p>
	 *
	 * @param key        the unique identifier
	 * @param conditions the conditional text prompt
	 * @param embedDim   the dimension of the random embedding vector
	 */
	public AudioModelOutput(String key, String conditions, int embedDim) {
		this(key, conditions, Stream.generate(new Random()::nextGaussian).limit(embedDim).toList());
	}

	/**
	 * Creates an audio model output with all properties.
	 *
	 * @param key        the unique identifier
	 * @param conditions the conditional text prompt
	 * @param embed      the embedding vector
	 */
	public AudioModelOutput(String key, String conditions, List<Double> embed) {
		super(key);
		this.conditions = conditions;
		this.embed = embed;
	}

	/**
	 * Returns the name of the model that generated this audio.
	 *
	 * @return the model name, or {@code null} if not set
	 */
	public String getModelName() { return modelName; }

	/**
	 * Sets the name of the model that generated this audio.
	 *
	 * @param modelName the model name
	 */
	public void setModelName(String modelName) {
		this.modelName = modelName;
	}

	/**
	 * Returns the conditional text prompt used for generation.
	 *
	 * @return the conditional text, or {@code null} if not set
	 */
	public String getConditionalText() {
		return conditions;
	}

	/**
	 * Sets the conditional text prompt used for generation.
	 *
	 * @param conditions the conditional text
	 */
	public void setConditionalText(String conditions) {
		this.conditions = conditions;
	}

	/**
	 * Returns the embedding vector used for generation.
	 *
	 * @return the embedding as a list of doubles, or {@code null} if not set
	 */
	public List<Double> getEmbed() { return embed; }

	/**
	 * Sets the embedding vector used for generation.
	 *
	 * @param embed the embedding as a list of doubles
	 */
	public void setEmbed(List<Double> embed) {
		this.embed = embed;
	}

	/**
	 * Returns the embedding vector as a {@link PackedCollection}.
	 *
	 * <p>This is useful for passing the embedding to ML models or
	 * computation graphs.</p>
	 *
	 * @return the embedding as a PackedCollection, or {@code null} if no embedding
	 */
	@JsonIgnore
	public PackedCollection getPackedEmbed() {
		if (getEmbed() == null) return null;
		return PackedCollection.of(getEmbed().stream().mapToDouble(d -> d).toArray());
	}
}
