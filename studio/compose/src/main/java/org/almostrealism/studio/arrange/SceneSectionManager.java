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

package org.almostrealism.studio.arrange;
import org.almostrealism.music.arrange.ChannelSection;

import io.almostrealism.lifecycle.Setup;
import io.almostrealism.lifecycle.Destroyable;
import org.almostrealism.music.data.ChannelInfo;
import org.almostrealism.hardware.OperationList;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.time.Frequency;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntPredicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Manages the ordered collection of {@link SceneSection}s for an audio scene,
 * handling creation, setup, and destruction of section factories and their
 * corresponding operation lists.
 */
public class SceneSectionManager implements Setup, Destroyable {
	/** Predicate that selects all channels except channel 5 for looping repeats. */
	public static final IntPredicate DEFAULT_REPEAT_CHANNELS = c -> c != 5;

	/** The ordered list of scene sections. */
	private final List<SceneSection> sections;

	/** Aggregated setup operations from all registered section factories. */
	private final OperationList setup;

	/** One projected chromosome per section, providing gene parameters. */
	private final List<ProjectedChromosome> chromosomes;

	/** Number of pattern channels per section. */
	private final int channels;

	/** Supplier providing the current tempo frequency. */
	private final Supplier<Frequency> tempo;

	/** Supplier providing the current measure duration in seconds. */
	private final DoubleSupplier measureDuration;

	/** Audio sample rate. */
	private final int sampleRate;

	/** Indices of channels that receive wet (effects-chain) processing. */
	private List<Integer> wetChannels;

	/**
	 * Creates a scene section manager.
	 *
	 * @param chromosomes     one chromosome per section
	 * @param channels        number of pattern channels
	 * @param tempo           supplier of the current tempo
	 * @param measureDuration supplier of the current measure duration in seconds
	 * @param sampleRate      audio sample rate
	 */
	public SceneSectionManager(List<ProjectedChromosome> chromosomes, int channels,
							   Supplier<Frequency> tempo,
							   DoubleSupplier measureDuration, int sampleRate) {
		this.sections = new ArrayList<>();
		this.setup = new OperationList("SceneSectionManager Setup");
		this.chromosomes = chromosomes;
		this.channels = channels;
		this.tempo = tempo;
		this.measureDuration = measureDuration;
		this.sampleRate = sampleRate;
		this.wetChannels = new ArrayList<>();
	}

	/** Returns the list of channel indices that receive wet effects processing. */
	public List<Integer> getWetChannels() { return wetChannels; }

	/**
	 * Sets the list of channel indices that should receive wet effects processing.
	 *
	 * @param wetChannels list of zero-based channel indices
	 */
	public void setWetChannels(List<Integer> wetChannels) { this.wetChannels = wetChannels; }

	/** Returns an unmodifiable view of all registered scene sections. */
	public List<SceneSection> getSections() { return Collections.unmodifiableList(sections); }

	/**
	 * Returns all channel sections for the given channel across every registered scene section.
	 *
	 * @param channel the channel whose sections to retrieve
	 * @return ordered list of channel sections
	 */
	public List<ChannelSection> getChannelSections(ChannelInfo channel) {
		return sections.stream().map(s -> s.getChannelSection(channel)).collect(Collectors.toList());
	}

	/**
	 * Creates and registers a new scene section at the given position with the given length.
	 *
	 * @param position starting measure position of the section
	 * @param length   length of the section in measures
	 * @return the newly created scene section
	 */
	public SceneSection addSection(int position, int length) {
		ProjectedChromosome chromosome = chromosomes.get(sections.size());
		DefaultChannelSectionFactory channelFactory = new DefaultChannelSectionFactory(chromosome, channels,
																		c -> getWetChannels().contains(c),
																		DEFAULT_REPEAT_CHANNELS,
																		tempo, measureDuration, length, sampleRate);
		SceneSection s = SceneSection.createSection(position, length, channels, () -> channelFactory.createSection(position));
		sections.add(s);
		setup.add(channelFactory.setup());
		return s;
	}

	/**
	 * Removes and destroys the scene section at the given index.
	 *
	 * @param index zero-based index of the section to remove
	 */
	public void removeSection(int index) {
		sections.remove(index).destroy();
		setup.remove(index);
		chromosomes.get(0).removeAllGenes();
	}

	@Override
	public Supplier<Runnable> setup() { return setup; }

	@Override
	public void destroy() {
		Destroyable.super.destroy();
		sections.forEach(SceneSection::destroy);
		sections.clear();
	}
}
