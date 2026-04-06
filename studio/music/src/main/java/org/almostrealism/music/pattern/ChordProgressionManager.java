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

package org.almostrealism.music.pattern;

import org.almostrealism.CodeFeatures;
import org.almostrealism.music.data.ParameterFunction;
import org.almostrealism.music.data.ParameterSet;
import org.almostrealism.audio.tone.KeyPosition;
import org.almostrealism.audio.tone.Scale;
import org.almostrealism.audio.tone.StaticScale;
import org.almostrealism.audio.tone.WesternChromatic;
import org.almostrealism.audio.tone.WesternScales;
import org.almostrealism.heredity.ProjectedChromosome;
import org.almostrealism.heredity.ProjectedGenome;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Manages a chord progression over a fixed duration by dividing it into regions
 * with independently selected scales and chord positions.
 *
 * <p>Each region has a length selected from a binary-ratio grid and a scale
 * selected by a {@link ChordPositionFunction}. Regions are refreshed via
 * {@link #refreshParameters()} using the current chromosome values.</p>
 *
 * @see ChordPositionFunction
 * @see PatternLayerManager
 */
public class ChordProgressionManager implements CodeFeatures {
	/** Maximum number of regions supported. */
	public static final int MAX_SIZE = 64;

	/** The projected chromosome providing genetic parameter values. */
	private final ProjectedChromosome chromosome;

	/** The musical key (scale) for this progression. */
	private Scale<?> key;

	/** The number of notes per chord. */
	private int chordDepth;

	/** The number of regions in this progression. */
	private int size;

	/** The total duration in measures. */
	private double duration;

	/** Functions selecting the length of each region. */
	private List<ParameterFunction> regionLengthSelection;

	/** Functions selecting the chord positions for each region. */
	private List<ChordPositionFunction> chordSelection;

	/** The computed list of regions after the last {@link #refreshParameters()} call. */
	private List<Region> regions;

	/**
	 * Creates a {@code ChordProgressionManager} with the given number of parameters
	 * and a default C major key.
	 *
	 * @param parameters the number of genome parameters
	 */
	public ChordProgressionManager(int parameters) {
		this(new ProjectedGenome(parameters).addChromosome(),
				WesternScales.major(WesternChromatic.C1, 1));
	}

	/**
	 * Creates a {@code ChordProgressionManager} with the given chromosome and key.
	 *
	 * @param chromosome the projected chromosome
	 * @param key        the musical key
	 */
	public ChordProgressionManager(ProjectedChromosome chromosome, Scale<?> key) {
		this.chromosome = chromosome;
		setKey(key);
		setChordDepth(5);
		init();
	}

	/** Initializes selection functions and chromosome genes. */
	private void init() {
		regionLengthSelection = IntStream.range(0, MAX_SIZE)
				.mapToObj(i -> ParameterFunction.random())
				.collect(Collectors.toUnmodifiableList());

		chordSelection = IntStream.range(0, MAX_SIZE)
				.mapToObj(i -> ChordPositionFunction.random())
				.collect(Collectors.toUnmodifiableList());

		chromosome.addGene(3);
	}

	/**
	 * Returns the current {@link ParameterSet} derived from the chromosome.
	 *
	 * @return the parameter set
	 */
	protected ParameterSet getParams() {
		chromosome.getResultant(0, 0, c(1.0)).evaluate().toDouble();

		ParameterSet params = new ParameterSet();
		params.setX(chromosome.getResultant(0, 0, c(1.0)).evaluate().toDouble());
		params.setY(chromosome.getResultant(0, 1, c(1.0)).evaluate().toDouble());
		params.setZ(chromosome.getResultant(0, 2, c(1.0)).evaluate().toDouble());
		return params;
	}

	/**
	 * Returns the length for the region at the given index.
	 *
	 * @param index the zero-based region index
	 * @return the region length in measures
	 */
	protected double getRegionLength(int index) {
		List<Double> choices = new ArrayList<>();
		int last = 1;
		double ratio = duration / size;

		while (last < size) {
			choices.add(last * ratio);
			last *= 2;
		}

		double choice = regionLengthSelection.get(index).positive().apply(getParams());
		return choices.get((int) (choice * choices.size()));
	}

	/**
	 * Returns the scale for the region at the given index and position.
	 *
	 * @param index    the zero-based region index
	 * @param position the start position of the region
	 * @return the selected scale
	 */
	protected Scale<?> getScale(int index, double position) {
		List<KeyPosition<?>> notes = new ArrayList<>();
		getKey().forEach(notes::add);

		List<Double> choices = chordSelection.get(index).applyAll(getParams(), position, duration, chordDepth);

		List<KeyPosition<?>> result = new ArrayList<>();
		for (int i = 0; result.size() < chordDepth && !notes.isEmpty(); i++) {
			result.add(notes.remove((int) (choices.get(i) * notes.size())));
		}

		return new StaticScale<>(result.toArray(new KeyPosition[0]));
	}

	/**
	 * Recomputes the list of regions using the current chromosome parameters.
	 *
	 * <p>Must be called before using {@link #forPosition(double)} to ensure regions
	 * reflect the latest genetic values.</p>
	 */
	public void refreshParameters() {
		regions = new ArrayList<>();
		double length = 0.0;

		while (length < duration) {
			double regionLength = getRegionLength(regions.size());

			Region region = new Region(length, regionLength, getScale(regions.size(), length));
			regions.add(region);
			length += regionLength;
		}
	}

	/**
	 * Returns a {@link Settings} snapshot of the current configuration.
	 *
	 * @return the current settings
	 */
	public Settings getSettings() {
		Settings settings = new Settings();

		Scale<WesternChromatic> scale = (Scale<WesternChromatic>) getKey();
		WesternChromatic root = scale.valueAt(0);
		ScaleType scaleType = scale.length() > 2 && scale.valueAt(2).position() - scale.valueAt(0).position() < 4 ? ScaleType.MINOR : ScaleType.MAJOR;

		settings.setRoot(root);
		settings.setScaleType(scaleType);
		settings.setSize(getSize());
		settings.setDuration(getDuration());
		settings.setChordDepth(getChordDepth());
		settings.setRegionLengthSelection(getRegionLengthSelection());
		settings.setChordSelection(getChordSelection());
		return settings;
	}

	/**
	 * Applies a {@link Settings} snapshot to this manager.
	 *
	 * @param settings the settings to apply
	 */
	public void setSettings(Settings settings) {
		WesternChromatic root = settings.getRoot();

		if (root != null) {
			if (ScaleType.MAJOR.equals(settings.getScaleType())) {
				setKey(WesternScales.major(root, 1));
			} else if (ScaleType.MINOR.equals(settings.getScaleType())) {
				setKey(WesternScales.minor(root, 1));
			}
		}

		setSize(settings.getSize());
		setDuration(settings.getDuration());
		setChordDepth(settings.getChordDepth());
		setRegionLengthSelection(settings.getRegionLengthSelection());
		setChordSelection(settings.getChordSelection());
	}

	/** Returns the musical key for this progression. */
	public Scale<?> getKey() { return key; }

	/** Sets the musical key for this progression. */
	public void setKey(Scale<?> scale) { this.key = scale; }

	/** Returns the chord depth (number of notes per chord). */
	public int getChordDepth() { return chordDepth; }

	/** Sets the chord depth. */
	public void setChordDepth(int chordDepth) { this.chordDepth = chordDepth; }

	/** Returns the number of regions. */
	public int getSize() { return size; }

	/** Sets the number of regions; must not exceed {@link #MAX_SIZE}. */
	public void setSize(int size) {
		if (size > MAX_SIZE) throw new IllegalArgumentException();
		this.size = size;
	}

	/** Returns the total duration in measures. */
	public double getDuration() { return duration; }

	/** Sets the total duration in measures. */
	public void setDuration(double duration) { this.duration = duration; }

	/** Returns the list of region length selection functions. */
	public List<ParameterFunction> getRegionLengthSelection() {
		return regionLengthSelection;
	}

	/** Sets the list of region length selection functions. */
	public void setRegionLengthSelection(List<ParameterFunction> regionLengthSelection) {
		this.regionLengthSelection = regionLengthSelection;
	}

	/** Returns the list of chord position selection functions. */
	public List<ChordPositionFunction> getChordSelection() {
		return chordSelection;
	}

	/** Sets the list of chord position selection functions. */
	public void setChordSelection(List<ChordPositionFunction> chordSelection) {
		this.chordSelection = chordSelection;
	}

	/**
	 * Returns the scale for the region that contains the given position.
	 *
	 * @param position the position in measures (wrapped to duration if needed)
	 * @return the scale for the containing region
	 */
	public Scale<?> forPosition(double position) {
		while (position >= duration) position -= duration;
		for (Region region : regions) {
			if (region.contains(position)) {
				return region.getScale();
			}
		}

		System.out.println("WARN: Exhausted regions");
		return getKey();
	}

	/**
	 * Returns a string representation of the regions using {@code X} for the start
	 * and {@code _} for each additional unit.
	 *
	 * @return a visual region string
	 */
	public String getRegionString() {
		StringBuilder sb = new StringBuilder();
		for (Region region : regions) {
			sb.append("X");
			IntStream.range(1, (int) region.length).forEach(i -> sb.append("_"));
		}

		return sb.toString();
	}

	/**
	 * A time region with a fixed start, length, and scale.
	 */
	public static class Region {
		/** The start position of this region in measures. */
		private final double start;

		/** The length of this region in measures. */
		private final double length;

		/** The scale associated with this region. */
		private final Scale<?> scale;

		/**
		 * Creates a {@code Region} with the given start, length, and scale.
		 *
		 * @param start  the start position in measures
		 * @param length the length in measures
		 * @param scale  the musical scale for this region
		 */
		public Region(double start, double length, Scale<?> scale) {
			this.start = start;
			this.length = length;
			this.scale = scale;
		}

		/** Returns the musical scale for this region. */
		public Scale<?> getScale() {
			return scale;
		}

		/**
		 * Returns {@code true} if the given position is within this region.
		 *
		 * @param position the position to check
		 * @return {@code true} if contained
		 */
		public boolean contains(double position) {
			return position >= start && position < start + length;
		}
	}

	/**
	 * Serializable snapshot of all configuration parameters for a {@link ChordProgressionManager}.
	 */
	public static class Settings {
		/** The scale type (MAJOR or MINOR). */
		private ScaleType scaleType;

		/** The root pitch of the key. */
		private WesternChromatic root;

		/** The number of regions. */
		private int size;

		/** The total duration in measures. */
		private double duration;

		/** The chord depth. */
		private int chordDepth;

		/** The region length selection functions. */
		private List<ParameterFunction> regionLengthSelection;

		/** The chord position selection functions. */
		private List<ChordPositionFunction> chordSelection;

		/** Creates an empty {@code Settings} instance. */
		public Settings() { }

		/** Returns the scale type. */
		public ScaleType getScaleType() { return scaleType; }

		/** Sets the scale type. */
		public void setScaleType(ScaleType scaleType) { this.scaleType = scaleType; }

		/** Returns the root pitch. */
		public WesternChromatic getRoot() { return root; }

		/** Sets the root pitch. */
		public void setRoot(WesternChromatic root) { this.root = root; }

		/** Returns the number of regions. */
		public int getSize() { return size; }

		/** Sets the number of regions. */
		public void setSize(int size) { this.size = size; }

		/** Returns the total duration in measures. */
		public double getDuration() { return duration; }

		/** Sets the total duration in measures. */
		public void setDuration(double duration) { this.duration = duration; }

		/** Returns the chord depth. */
		public int getChordDepth() { return chordDepth; }

		/** Sets the chord depth. */
		public void setChordDepth(int chordDepth) { this.chordDepth = chordDepth; }

		/** Returns the region length selection functions. */
		public List<ParameterFunction> getRegionLengthSelection() {
			return regionLengthSelection;
		}

		/** Sets the region length selection functions. */
		public void setRegionLengthSelection(List<ParameterFunction> regionLengthSelection) {
			this.regionLengthSelection = regionLengthSelection;
		}

		/** Returns the chord position selection functions. */
		public List<ChordPositionFunction> getChordSelection() {
			return chordSelection;
		}

		/** Sets the chord position selection functions. */
		public void setChordSelection(List<ChordPositionFunction> chordSelection) {
			this.chordSelection = chordSelection;
		}

		/**
		 * Creates a default {@code Settings} instance with D minor, 16 regions, and 8-measure duration.
		 *
		 * @return a new default settings instance
		 */
		public static Settings defaultSettings() {
			Settings settings = new Settings();
			settings.setScaleType(ScaleType.MINOR);
			settings.setRoot(WesternChromatic.D1);
			settings.setSize(16);
			settings.setDuration(8);
			settings.setChordDepth(5);
			settings.setRegionLengthSelection(IntStream.range(0, MAX_SIZE)
					.mapToObj(i -> ParameterFunction.random())
					.collect(Collectors.toUnmodifiableList()));
			settings.setChordSelection(IntStream.range(0, MAX_SIZE)
					.mapToObj(i -> ChordPositionFunction.random())
					.collect(Collectors.toUnmodifiableList()));
			return settings;
		}
	}

	/**
	 * The scale type for a chord progression key.
	 */
	public enum ScaleType {
		/** Major scale type. */
		MAJOR,
		/** Minor scale type. */
		MINOR
	}
}
