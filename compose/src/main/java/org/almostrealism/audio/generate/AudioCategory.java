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

package org.almostrealism.audio.generate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.almostrealism.audio.notes.NoteAudioChoice;
import org.almostrealism.audio.notes.NoteAudioSource;
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
	private String id;
	private String name;
	private NoteAudioChoice choice;
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

	private void syncIds() {
		if (choice != null) choice.setId(id);
		if (model != null) model.setId(id);
	}

	public String getId() { return id; }

	public void setId(String id) {
		this.id = id;
		syncIds();
	}

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

	public void setModel(AudioModel model) {
		this.model = model;
		if (model != null) {
			model.setId(id);
			model.setName(name);
		}
	}

	// ========== Delegated Choice Properties ==========

	public List<NoteAudioSource> getSources() {
		return choice != null ? choice.getSources() : new ArrayList<>();
	}

	public void setSources(List<NoteAudioSource> sources) {
		if (choice != null) choice.setSources(sources);
	}

	public List<Integer> getChannels() {
		return choice != null ? choice.getChannels() : new ArrayList<>();
	}

	public void setChannels(List<Integer> channels) {
		if (choice != null) choice.setChannels(channels);
	}

	public boolean isMelodic() {
		return choice != null && choice.isMelodic();
	}

	public void setMelodic(boolean melodic) {
		if (choice != null) choice.setMelodic(melodic);
	}

	public double getMinScale() {
		return choice != null ? choice.getMinScale() : 0.0625;
	}

	public void setMinScale(double minScale) {
		if (choice != null) choice.setMinScale(minScale);
	}

	public double getMaxScale() {
		return choice != null ? choice.getMaxScale() : 16.0;
	}

	public void setMaxScale(double maxScale) {
		if (choice != null) choice.setMaxScale(maxScale);
	}

	public int getMaxScaleTraversalDepth() {
		return choice != null ? choice.getMaxScaleTraversalDepth() : 9;
	}

	public void setMaxScaleTraversalDepth(int depth) {
		if (choice != null) choice.setMaxScaleTraversalDepth(depth);
	}

	public boolean isSeed() {
		return choice != null && choice.isSeed();
	}

	public void setSeed(boolean seed) {
		if (choice != null) choice.setSeed(seed);
	}

	public double getBias() {
		return choice != null ? choice.getBias() : -0.2;
	}

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

	public List<String> getAudioConditions() {
		return model != null ? model.getAudioConditions() : new ArrayList<>();
	}

	public void setAudioConditions(List<String> audioConditions) {
		if (model != null) model.setAudioConditions(audioConditions);
	}

	public Double getCreativity() {
		return model != null ? model.getCreativity() : null;
	}

	public void setCreativity(Double creativity) {
		if (model != null) model.setCreativity(creativity);
	}

	public double getDuration() {
		return model != null ? model.getDuration() : 1.0;
	}

	public void setDuration(double duration) {
		if (model != null) model.setDuration(duration);
	}

	public boolean isPattern() {
		return model != null && model.isPattern();
	}

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
