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

package org.almostrealism.audio.pattern;

import org.almostrealism.CodeFeatures;
import org.almostrealism.audio.data.ParameterFunction;
import org.almostrealism.audio.data.ParameterSet;
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

public class ChordProgressionManager implements CodeFeatures {
	public static final int MAX_SIZE = 64;

	private final ProjectedChromosome chromosome;

	private Scale<?> key;
	private int chordDepth;
	private int size;
	private double duration;
	private List<ParameterFunction> regionLengthSelection;
	private List<ChordPositionFunction> chordSelection;

	private List<Region> regions;

	public ChordProgressionManager(int parameters) {
		this(new ProjectedGenome(parameters).addChromosome(),
				WesternScales.major(WesternChromatic.C1, 1));
	}

	public ChordProgressionManager(ProjectedChromosome chromosome, Scale<?> key) {
		this.chromosome = chromosome;
		setKey(key);
		setChordDepth(5);
		init();
	}

	private void init() {
		regionLengthSelection = IntStream.range(0, MAX_SIZE)
				.mapToObj(i -> ParameterFunction.random())
				.collect(Collectors.toUnmodifiableList());

		chordSelection = IntStream.range(0, MAX_SIZE)
				.mapToObj(i -> ChordPositionFunction.random())
				.collect(Collectors.toUnmodifiableList());

		chromosome.addGene(3);
	}

	protected ParameterSet getParams() {
		chromosome.getResultant(0, 0, c(1.0)).evaluate().toDouble();

		ParameterSet params = new ParameterSet();
		params.setX(chromosome.getResultant(0, 0, c(1.0)).evaluate().toDouble());
		params.setY(chromosome.getResultant(0, 1, c(1.0)).evaluate().toDouble());
		params.setZ(chromosome.getResultant(0, 2, c(1.0)).evaluate().toDouble());
		return params;
	}

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

	public Scale<?> getKey() { return key; }
	public void setKey(Scale<?> scale) { this.key = scale; }

	public int getChordDepth() { return chordDepth; }
	public void setChordDepth(int chordDepth) { this.chordDepth = chordDepth; }

	public int getSize() { return size; }
	public void setSize(int size) {
		if (size > MAX_SIZE) throw new IllegalArgumentException();
		this.size = size;
	}

	public double getDuration() { return duration; }
	public void setDuration(double duration) { this.duration = duration; }

	public List<ParameterFunction> getRegionLengthSelection() {
		return regionLengthSelection;
	}

	public void setRegionLengthSelection(List<ParameterFunction> regionLengthSelection) {
		this.regionLengthSelection = regionLengthSelection;
	}

	public List<ChordPositionFunction> getChordSelection() {
		return chordSelection;
	}

	public void setChordSelection(List<ChordPositionFunction> chordSelection) {
		this.chordSelection = chordSelection;
	}

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

	public String getRegionString() {
		StringBuilder sb = new StringBuilder();
		for (Region region : regions) {
			sb.append("X");
			IntStream.range(1, (int) region.length).forEach(i -> sb.append("_"));
		}

		return sb.toString();
	}

	public class Region {
		private final double start;
		private final double length;
		private final Scale<?> scale;

		public Region(double start, double length, Scale<?> scale) {
			this.start = start;
			this.length = length;
			this.scale = scale;
		}

		public Scale<?> getScale() {
			return scale;
		}

		public boolean contains(double position) {
			return position >= start && position < start + length;
		}
	}

	public static class Settings {
		private ScaleType scaleType;
		private WesternChromatic root;

		private int size;
		private double duration;
		private int chordDepth;

		private List<ParameterFunction> regionLengthSelection;
		private List<ChordPositionFunction> chordSelection;

		public Settings() { }

		public ScaleType getScaleType() { return scaleType; }
		public void setScaleType(ScaleType scaleType) { this.scaleType = scaleType; }

		public WesternChromatic getRoot() { return root; }
		public void setRoot(WesternChromatic root) { this.root = root; }

		public int getSize() { return size; }
		public void setSize(int size) { this.size = size; }

		public double getDuration() { return duration; }
		public void setDuration(double duration) { this.duration = duration; }

		public int getChordDepth() { return chordDepth; }
		public void setChordDepth(int chordDepth) { this.chordDepth = chordDepth; }

		public List<ParameterFunction> getRegionLengthSelection() {
			return regionLengthSelection;
		}

		public void setRegionLengthSelection(List<ParameterFunction> regionLengthSelection) {
			this.regionLengthSelection = regionLengthSelection;
		}

		public List<ChordPositionFunction> getChordSelection() {
			return chordSelection;
		}

		public void setChordSelection(List<ChordPositionFunction> chordSelection) {
			this.chordSelection = chordSelection;
		}

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

	public enum ScaleType {
		MAJOR, MINOR
	}
}
