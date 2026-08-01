/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.music.notes;
import org.almostrealism.audio.notes.NoteAudio;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.almostrealism.relation.Validity;
import org.almostrealism.audio.CellFeatures;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.music.pattern.ElementParity;
import org.almostrealism.music.pattern.PatternElement;
import org.almostrealism.music.pattern.PatternElementFactory;
import org.almostrealism.music.pattern.PatternLayer;
import org.almostrealism.music.pattern.PatternLayerSeeds;
import org.almostrealism.music.pattern.ScaleTraversalStrategy;
import org.almostrealism.audio.tone.KeyboardTuning;
import org.almostrealism.io.Console;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.util.KeyUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Represents a collection of audio sources that can be selected for pattern elements.
 *
 * <p>{@code NoteAudioChoice} is a key component in the pattern system, grouping related
 * {@link NoteAudioSource}s that share similar characteristics (e.g., different velocities
 * of the same drum, different articulations of the same instrument).</p>
 *
 * <h2>Pattern System Integration</h2>
 *
 * <p>In the pattern hierarchy:</p>
 * <pre>
 * PatternSystemManager
 *     +-- NoteAudioChoice[]  &lt;-- This class
 *         +-- NoteAudioSource[]
 *             +-- PatternNoteAudio[]
 * </pre>
 *
 * <h2>Key Properties</h2>
 *
 * <ul>
 *   <li><strong>sources</strong>: List of audio sources available for selection</li>
 *   <li><strong>melodic</strong>: Whether this choice is pitched (true) or percussive (false)</li>
 *   <li><strong>weight</strong>: Selection probability weight for genetic algorithms</li>
 *   <li><strong>minScale/maxScale</strong>: Time granularity bounds for pattern elements</li>
 *   <li><strong>channels</strong>: Which audio channels can use this choice</li>
 *   <li><strong>seed</strong>: Whether this choice can seed new pattern layers</li>
 *   <li><strong>bias</strong>: Selection bias adjustment</li>
 * </ul>
 *
 * <h2>Layer Generation</h2>
 *
 * <p>The {@link #seeds} method creates {@link PatternLayerSeeds} for generating new
 * pattern layers. The {@link #apply} methods create {@link PatternLayer}s by expanding
 * existing elements with the configured element factory.</p>
 *
 * <h2>Validity Filtering</h2>
 *
 * <p>{@link #getValidPatternNotes()} filters to only notes that pass validity checks,
 * ensuring audio sources are properly loaded and usable.</p>
 *
 * @see NoteAudioSource
 * @see PatternNoteAudio
 * @see PatternLayerManager
 * @see PatternSystemManager
 *
 * @author Michael Murray
 */
public class NoteAudioChoice implements ConsoleFeatures {
	/** Optional distribution array for tracking granularity choices; may be null. */
	public static int[] GRANULARITY_DIST;

	/** Unique identifier for this choice. */
	private String id;

	/** Human-readable name for this choice. */
	private String name;

	/** The audio sources available in this choice. */
	private List<NoteAudioSource> sources;

	/** Whether this choice is melodic (pitched) or percussive. */
	private boolean melodic;

	/** Selection probability weight for the genetic algorithm. */
	private double weight;

	/** Minimum time scale granularity for pattern elements. */
	private double minScale;

	/** Maximum time scale granularity for pattern elements. */
	private double maxScale;

	/** Maximum depth for scale traversal when generating pattern layers. */
	private int maxScaleTraversalDepth;

	/** The audio channel indices that may use this choice. */
	private List<Integer> channels;

	/** Whether this choice can seed new pattern layers. */
	private boolean seed;

	/** Selection bias adjustment used when generating pattern layers. */
	private double bias;

	/** Function that selects the granularity for pattern layer seeds. */
	private ParameterFunction granularitySelection;

	/** Creates an uninitialized {@code NoteAudioChoice} with a null name. */
	public NoteAudioChoice() { this(null); }

	/**
	 * Creates a {@code NoteAudioChoice} with the given name and default weight of 1.0.
	 *
	 * @param name human-readable name
	 */
	public NoteAudioChoice(String name) { this(name, 1.0); }

	/**
	 * Creates a {@code NoteAudioChoice} with the given name and weight, and default scale bounds.
	 *
	 * @param name   human-readable name
	 * @param weight selection probability weight
	 */
	public NoteAudioChoice(String name, double weight) {
		this(name, weight, 0.0625, 16.0);
	}

	/**
	 * Creates a fully specified {@code NoteAudioChoice}.
	 *
	 * @param name     human-readable name
	 * @param weight   selection probability weight
	 * @param minScale minimum time scale granularity
	 * @param maxScale maximum time scale granularity
	 */
	public NoteAudioChoice(String name, double weight, double minScale, double maxScale) {
		setId(KeyUtils.generateKey());
		setName(name);
		setSources(new ArrayList<>());
		setWeight(weight);
		setMinScale(minScale);
		setMaxScale(maxScale);
		setMaxScaleTraversalDepth(9);
		setSeed(true);
		setBias(-0.2);
		setChannels(new ArrayList<>());
		setSources(new ArrayList<>());
		initSelectionFunctions();
	}

	/** Initializes the granularity selection function with a random value. */
	public void initSelectionFunctions() {
		granularitySelection = ParameterFunction.random();
	}

	/**
	 * Propagates the keyboard tuning to all sources in this choice.
	 *
	 * @param tuning the tuning to apply
	 */
	public void setTuning(KeyboardTuning tuning) {
		getSources().forEach(n -> n.setTuning(tuning));
	}

	/** Returns the unique identifier for this choice. */
	public String getId() { return id; }

	/** Sets the unique identifier for this choice. */
	public void setId(String id) {
		this.id = id;
	}

	/** Returns the human-readable name for this choice. */
	public String getName() { return name; }

	/** Sets the human-readable name for this choice. */
	public void setName(String name) {
		this.name = name;
	}

	/** Returns the list of audio sources in this choice. */
	public List<NoteAudioSource> getSources() {
		return sources;
	}

	/** Sets the list of audio sources for this choice. */
	public void setSources(List<NoteAudioSource> sources) {
		this.sources = sources;
	}

	/** Returns {@code true} if this choice is melodic (pitched). */
	public boolean isMelodic() {
		return melodic;
	}

	/** Sets whether this choice is melodic (pitched) or percussive. */
	public void setMelodic(boolean melodic) {
		this.melodic = melodic;
	}

	/** Returns the selection probability weight for the genetic algorithm. */
	// TODO Use this value to determine the likelihood of selection
	public double getWeight() { return weight; }

	/** Sets the selection probability weight. */
	public void setWeight(double weight) { this.weight = weight; }

	/** Returns the minimum time scale granularity for pattern elements. */
	public double getMinScale() { return minScale; }

	/** Sets the minimum time scale granularity. */
	public void setMinScale(double minScale) { this.minScale = minScale; }

	/** Returns the maximum time scale granularity for pattern elements. */
	public double getMaxScale() { return maxScale; }

	/** Sets the maximum time scale granularity. */
	public void setMaxScale(double maxScale) { this.maxScale = maxScale; }

	/** Returns the maximum depth for scale traversal. */
	public int getMaxScaleTraversalDepth() { return maxScaleTraversalDepth; }

	/** Sets the maximum depth for scale traversal. */
	public void setMaxScaleTraversalDepth(int maxScaleTraversalDepth) { this.maxScaleTraversalDepth = maxScaleTraversalDepth; }

	/** Returns the audio channel indices permitted for this choice. */
	public List<Integer> getChannels() { return channels; }

	/** Sets the audio channel indices permitted for this choice. */
	public void setChannels(List<Integer> channels) { this.channels = channels; }

	/** Returns {@code true} if this choice can seed new pattern layers. */
	public boolean isSeed() { return seed; }

	/** Sets whether this choice can seed new pattern layers. */
	public void setSeed(boolean seed) { this.seed = seed; }

	/** Returns {@code true} if this choice has at least one audio source. */
	public boolean hasSources() { return getSources() != null && !getSources().isEmpty(); }

	/** Returns {@code true} if this choice has at least one valid note. */
	public boolean hasValidNotes() { return hasSources() && !getValidNotes().isEmpty(); }

	/** Returns the granularity selection function. */
	public ParameterFunction getGranularitySelection() { return granularitySelection; }

	/** Sets the granularity selection function. */
	public void setGranularitySelection(ParameterFunction granularitySelection) {
		this.granularitySelection = granularitySelection;
	}

	/** Returns the selection bias adjustment. */
	public double getBias() { return bias; }

	/** Sets the selection bias adjustment. */
	public void setBias(double bias) {
		this.bias = bias;
	}

	/**
	 * Returns {@code true} if any source in this choice references the given file path.
	 *
	 * @param canonicalPath the canonical path to check
	 * @return {@code true} if the path is used
	 */
	public boolean checkResourceUsed(String canonicalPath) {
		return getSources().stream().anyMatch(s -> s.checkResourceUsed(canonicalPath));
	}

	/**
	 * Returns all notes from all sources in this choice, including invalid ones.
	 *
	 * @return a flat list of all notes
	 */
	@JsonIgnore
	public List<NoteAudio> getAllNotes() {
		return getSources().stream()
				.map(NoteAudioSource::getNotes)
				.flatMap(List::stream)
				.collect(Collectors.toList());
	}

	/**
	 * Returns all valid notes from all sources, sorted by natural ordering.
	 *
	 * @return a sorted list of valid notes
	 */
	@JsonIgnore
	public List<NoteAudio> getValidNotes() {
		return getAllNotes().stream()
				.filter(Validity::valid)
				.sorted()
				.collect(Collectors.toList());
	}

	/**
	 * Returns all pattern notes from all sources, including invalid ones.
	 *
	 * @return a flat list of all pattern notes
	 */
	@JsonIgnore
	public List<PatternNoteAudio> getAllPatternNotes() {
		return getSources().stream()
				.map(NoteAudioSource::getPatternNotes)
				.flatMap(List::stream)
				.collect(Collectors.toList());
	}

	/**
	 * Returns all valid pattern notes from all sources.
	 *
	 * @return a list of valid pattern notes
	 */
	@JsonIgnore
	public List<PatternNoteAudio> getValidPatternNotes() {
		return getAllPatternNotes().stream()
				.filter(Validity::valid)
				.collect(Collectors.toList());
	}

	/**
	 * Creates a {@link PatternLayerSeeds} for this choice using the given parameters.
	 *
	 * @param params          the parameter set
	 * @param biasAdjustment  adjustment factor applied to the bias
	 * @return a new set of seeds for layer generation
	 */
	public PatternLayerSeeds seeds(ParameterSet params, double biasAdjustment) {
		double granularity = granularitySelection.power(2, 3, -3).apply(params);

		if (GRANULARITY_DIST != null) {
			int i;
			double g = granularity;
			for (i = 0; g < 1.0; i++) {
				g *= 2;
			}

			GRANULARITY_DIST[i]++;
		}

		double bias = (getBias() + 1.0) * biasAdjustment - 1.0;
		return new PatternLayerSeeds(0, granularity, getMinScale(), getMaxScale(), bias, this, params);
	}

	/**
	 * Applies the element factory to a list of elements and returns the resulting pattern layer.
	 *
	 * @param factory                the factory used to create pattern elements
	 * @param elements               the source elements
	 * @param scale                  the time scale
	 * @param scaleTraversalStrategy the traversal strategy
	 * @param depth                  the traversal depth
	 * @param params                 the parameter set
	 * @return a new pattern layer
	 */
	public PatternLayer apply(PatternElementFactory factory, List<PatternElement> elements, double scale, ScaleTraversalStrategy scaleTraversalStrategy, int depth, ParameterSet params) {
		PatternLayer layer = new PatternLayer();
		layer.setChoice(this);

		elements.forEach(e -> layer.getElements().addAll(apply(factory, e, scale, scaleTraversalStrategy, depth, params).getElements()));
		return layer;
	}

	/**
	 * Applies the element factory to a single element and returns the resulting pattern layer.
	 *
	 * @param factory                the factory used to create pattern elements
	 * @param element                the source element
	 * @param scale                  the time scale
	 * @param scaleTraversalStrategy the traversal strategy
	 * @param depth                  the traversal depth
	 * @param params                 the parameter set
	 * @return a new pattern layer
	 */
	public PatternLayer apply(PatternElementFactory factory, PatternElement element, double scale, ScaleTraversalStrategy scaleTraversalStrategy, int depth, ParameterSet params) {
		PatternLayer layer = new PatternLayer();
		layer.setChoice(this);

		factory.apply(ElementParity.LEFT, element.getPosition(), scale, getBias(),
					scaleTraversalStrategy, depth, true, isMelodic(), params)
				.ifPresent(layer.getElements()::add);

		factory.apply(ElementParity.RIGHT, element.getPosition(), scale, getBias(),
					scaleTraversalStrategy, depth, true, isMelodic(), params)
				.ifPresent(layer.getElements()::add);

		return layer;
	}

	@Override
	public Console console() {
		return CellFeatures.console;
	}

	/**
	 * Creates a {@code NoteAudioChoice} from a single source.
	 *
	 * @param name                  human-readable name
	 * @param source                the audio source
	 * @param channel               the channel index
	 * @param maxScaleTraversalDepth maximum traversal depth
	 * @param melodic               {@code true} if the choice is melodic
	 * @return a new {@code NoteAudioChoice}
	 */
	public static NoteAudioChoice fromSource(String name, NoteAudioSource source,
											 int channel, int maxScaleTraversalDepth,
											 boolean melodic) {
		NoteAudioChoice c = new NoteAudioChoice();
		c.setName(name);
		c.setSources(new ArrayList<>());
		c.getSources().add(source);
		c.setMelodic(melodic);
		c.setMaxScaleTraversalDepth(maxScaleTraversalDepth);
		c.getChannels().add(channel);
		return c;
	}

	/**
	 * Returns a supplier that filters choices by melodic flag and valid notes.
	 *
	 * @param choices the full list of choices
	 * @param melodic {@code true} to include only melodic choices
	 * @return a supplier of the filtered list
	 */
	public static Supplier<List<NoteAudioChoice>> choices(List<NoteAudioChoice> choices, boolean melodic) {
		return () -> choices.stream()
				.filter(c -> c.isMelodic() || !melodic)
				.filter(c -> !c.getValidPatternNotes().isEmpty())
				.collect(Collectors.toList());
	}
}
