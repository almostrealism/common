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

package org.almostrealism.studio.generate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.almostrealism.music.notes.NoteAudioChoice;
import org.almostrealism.music.notes.NoteAudioSource;
import org.almostrealism.util.KeyUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified representation of an audio category that can be used for both
 * organizing samples (via {@link NoteAudioChoice}) and generating new
 * audio via ML diffusion (via {@link AudioModel}).
 * <p>
 * This class uses composition to combine the organizational aspects of
 * {@link NoteAudioChoice} with the generative aspects of {@link AudioModel},
 * sharing a common identity and enabling users to work with a single
 * unified concept of "category" rather than separate notions.
 * </p>
 */
public class AudioCategory {
	/** Unique identifier for this category. */
	private String id;

	/** Human-readable name of this category. */
	private String name;

	/** The sample-based note audio choice backing this category. */
	private NoteAudioChoice choice;

	/** The ML generative model descriptor for this category. */
	private AudioModel model;

	/**
	 * Default constructor that creates a new category with a generated ID
	 * and empty choice/model components.
	 */
	public AudioCategory() {
		this.id = KeyUtils.generateKey();
		this.choice = new NoteAudioChoice();
		this.model = new AudioModel();
		syncIds();
	}

	/**
	 * Creates a new category with the given name.
	 *
	 * @param name the name for this category
	 */
	public AudioCategory(String name) {
		this();
		setName(name);
	}

	/**
	 * Creates a category from an existing {@link NoteAudioChoice}.
	 * This is used for migrating existing data.
	 *
	 * @param choice the existing choice to wrap
	 * @return a new AudioCategory wrapping the choice
	 */
	public static AudioCategory fromChoice(NoteAudioChoice choice) {
		AudioCategory category = new AudioCategory();
		category.id = choice.getId();
		category.name = choice.getName();
		category.choice = choice;
		category.model = new AudioModel(choice.getName());
		category.model.setId(choice.getId());
		return category;
	}

	/**
	 * Creates a category from an existing {@link AudioModel}.
	 * This is used for migrating existing data.
	 *
	 * @param model the existing model to wrap
	 * @return a new AudioCategory wrapping the model
	 */
	public static AudioCategory fromModel(AudioModel model) {
		AudioCategory category = new AudioCategory();
		category.id = model.getId();
		category.name = model.getName();
		category.choice = new NoteAudioChoice(model.getName());
		category.choice.setId(model.getId());
		category.model = model;
		return category;
	}

	/** Propagates the current {@link #id} to both the choice and model components. */
	private void syncIds() {
		if (choice != null) choice.setId(id);
		if (model != null) model.setId(id);
	}

	/** Returns the unique identifier of this category. */
	public String getId() { return id; }

	/**
	 * Sets the unique identifier of this category, propagating to both components.
	 *
	 * @param id the new identifier
	 */
	public void setId(String id) {
		this.id = id;
		syncIds();
	}

	/** Returns the human-readable name of this category. */
	public String getName() { return name; }

	/**
	 * Sets the name of this category, propagating to both
	 * the choice and model components.
	 *
	 * @param name the new name
	 */
	public void setName(String name) {
		this.name = name;
		if (choice != null) choice.setName(name);
		if (model != null) model.setName(name);
	}

	/**
	 * Returns the underlying {@link NoteAudioChoice} for pattern generation
	 * and sample organization.
	 *
	 * @return the choice component
	 */
	public NoteAudioChoice getChoice() { return choice; }

	/**
	 * Sets the choice component, propagating the current id and name to it.
	 *
	 * @param choice the note audio choice to use
	 */
	public void setChoice(NoteAudioChoice choice) {
		this.choice = choice;
		if (choice != null) {
			choice.setId(id);
			choice.setName(name);
		}
	}

	/**
	 * Returns the underlying {@link AudioModel} for ML audio generation.
	 *
	 * @return the model component
	 */
	public AudioModel getModel() { return model; }

	/**
	 * Sets the model component, propagating the current id and name to it.
	 *
	 * @param model the audio model to use
	 */
	public void setModel(AudioModel model) {
		this.model = model;
		if (model != null) {
			model.setId(id);
			model.setName(name);
		}
	}

	// ========== Delegated Choice Properties ==========

	/** Returns the audio sources associated with the choice component. */
	public List<NoteAudioSource> getSources() {
		return choice != null ? choice.getSources() : new ArrayList<>();
	}

	/**
	 * Sets the audio sources on the choice component.
	 *
	 * @param sources the list of audio sources
	 */
	public void setSources(List<NoteAudioSource> sources) {
		if (choice != null) choice.setSources(sources);
	}

	/** Returns the channel assignments from the choice component. */
	public List<Integer> getChannels() {
		return choice != null ? choice.getChannels() : new ArrayList<>();
	}

	/**
	 * Sets the channel assignments on the choice component.
	 *
	 * @param channels the list of channel indices
	 */
	public void setChannels(List<Integer> channels) {
		if (choice != null) choice.setChannels(channels);
	}

	/** Returns whether this category is configured for melodic (pitched) playback. */
	public boolean isMelodic() {
		return choice != null && choice.isMelodic();
	}

	/**
	 * Sets whether this category is configured for melodic playback.
	 *
	 * @param melodic {@code true} for melodic playback
	 */
	public void setMelodic(boolean melodic) {
		if (choice != null) choice.setMelodic(melodic);
	}

	/** Returns the minimum playback scale factor from the choice component. */
	public double getMinScale() {
		return choice != null ? choice.getMinScale() : 0.0625;
	}

	/**
	 * Sets the minimum playback scale factor on the choice component.
	 *
	 * @param minScale the minimum scale factor
	 */
	public void setMinScale(double minScale) {
		if (choice != null) choice.setMinScale(minScale);
	}

	/** Returns the maximum playback scale factor from the choice component. */
	public double getMaxScale() {
		return choice != null ? choice.getMaxScale() : 16.0;
	}

	/**
	 * Sets the maximum playback scale factor on the choice component.
	 *
	 * @param maxScale the maximum scale factor
	 */
	public void setMaxScale(double maxScale) {
		if (choice != null) choice.setMaxScale(maxScale);
	}

	/** Returns the maximum scale traversal depth from the choice component. */
	public int getMaxScaleTraversalDepth() {
		return choice != null ? choice.getMaxScaleTraversalDepth() : 9;
	}

	/**
	 * Sets the maximum scale traversal depth on the choice component.
	 *
	 * @param depth the maximum traversal depth
	 */
	public void setMaxScaleTraversalDepth(int depth) {
		if (choice != null) choice.setMaxScaleTraversalDepth(depth);
	}

	/** Returns whether this category is a seed category in the choice component. */
	public boolean isSeed() {
		return choice != null && choice.isSeed();
	}

	/**
	 * Sets whether this category is a seed category on the choice component.
	 *
	 * @param seed {@code true} to mark as a seed
	 */
	public void setSeed(boolean seed) {
		if (choice != null) choice.setSeed(seed);
	}

	/** Returns the selection bias value from the choice component. */
	public double getBias() {
		return choice != null ? choice.getBias() : -0.2;
	}

	/**
	 * Sets the selection bias on the choice component.
	 *
	 * @param bias the bias value
	 */
	public void setBias(double bias) {
		if (choice != null) choice.setBias(bias);
	}

	// ========== Delegated Model Properties ==========

	/**
	 * Returns the text description/prompt for ML generation.
	 *
	 * @return the first text condition, or empty string if none
	 */
	@JsonIgnore
	public String getDescription() {
		if (model == null || model.getTextConditions() == null ||
				model.getTextConditions().isEmpty()) {
			return "";
		}
		return model.getTextConditions().get(0);
	}

	/**
	 * Sets the text description/prompt for ML generation.
	 *
	 * @param description the text prompt
	 */
	public void setDescription(String description) {
		if (model == null) return;

		if (model.getTextConditions() == null) {
			model.setTextConditions(new ArrayList<>());
		}

		model.getTextConditions().clear();
		if (description != null && !description.isBlank()) {
			model.getTextConditions().add(description);
		}
	}

	/** Returns the audio condition file paths from the model component. */
	public List<String> getAudioConditions() {
		return model != null ? model.getAudioConditions() : new ArrayList<>();
	}

	/**
	 * Sets the audio condition file paths on the model component.
	 *
	 * @param audioConditions list of audio condition file paths
	 */
	public void setAudioConditions(List<String> audioConditions) {
		if (model != null) model.setAudioConditions(audioConditions);
	}

	/** Returns the creativity (temperature) value from the model component, or {@code null}. */
	public Double getCreativity() {
		return model != null ? model.getCreativity() : null;
	}

	/**
	 * Sets the creativity value on the model component.
	 *
	 * @param creativity the generation temperature, or {@code null} for the default
	 */
	public void setCreativity(Double creativity) {
		if (model != null) model.setCreativity(creativity);
	}

	/** Returns the target generation duration in seconds from the model component. */
	public double getDuration() {
		return model != null ? model.getDuration() : 1.0;
	}

	/**
	 * Sets the target generation duration in seconds on the model component.
	 *
	 * @param duration generation duration in seconds
	 */
	public void setDuration(double duration) {
		if (model != null) model.setDuration(duration);
	}

	/** Returns whether this category generates pattern (loopable) audio. */
	public boolean isPattern() {
		return model != null && model.isPattern();
	}

	/**
	 * Sets whether this category generates pattern (loopable) audio.
	 *
	 * @param pattern {@code true} for loopable pattern generation
	 */
	public void setPattern(boolean pattern) {
		if (model != null) model.setPattern(pattern);
	}

	// ========== Capability Queries ==========

	/**
	 * Returns whether this category has file sources configured.
	 *
	 * @return true if sources are present
	 */
	@JsonIgnore
	public boolean hasFileSources() {
		return choice != null && choice.hasSources();
	}

	/**
	 * Returns whether this category has a text description configured.
	 *
	 * @return true if a non-empty description is present
	 */
	@JsonIgnore
	public boolean hasTextDescription() {
		return !getDescription().isBlank();
	}

	/**
	 * Returns whether this category has audio reference files configured
	 * for ML generation.
	 *
	 * @return true if audio conditions are present
	 */
	@JsonIgnore
	public boolean hasAudioConditions() {
		List<String> conditions = getAudioConditions();
		return conditions != null && !conditions.isEmpty();
	}

	/**
	 * Returns whether this category can be used for pattern generation.
	 * Requires file sources to be configured.
	 *
	 * @return true if pattern generation is possible
	 */
	@JsonIgnore
	public boolean canGeneratePatterns() {
		return hasFileSources();
	}

	/**
	 * Returns whether this category can be used for ML audio generation.
	 * Requires either a text description or audio reference files.
	 *
	 * @return true if ML generation is possible
	 */
	@JsonIgnore
	public boolean canGenerateML() {
		return hasTextDescription() || hasAudioConditions();
	}

	/**
	 * Checks if any source in this category uses the given resource path.
	 *
	 * @param canonicalPath the canonical path to check
	 * @return true if the resource is used
	 */
	public boolean checkResourceUsed(String canonicalPath) {
		return choice != null && choice.checkResourceUsed(canonicalPath);
	}
}
