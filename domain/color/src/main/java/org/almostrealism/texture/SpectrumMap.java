/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.texture;

import org.almostrealism.color.ProbabilityDistribution;
import org.almostrealism.color.RGB;

import java.io.IOException;

/**
 * A SpectrumMap instance combines a ProbabilityDistribution and an IntensityMap.
 * This functionality is provided through the getSample method which accepts a
 * position vector (u, v, w). The SpectrumMap object will first use the IntensityMap
 * instance that it stores internally to calculate an intensity value for the point
 * and then it will use the intensity value returned to locate a position on the
 * ProbabilityDistribution. The SpectrumMap will not function properly unless an
 * IntensityMap instance is specified. However, if no ProbabilityDistribution is
 * specified, the SpectrumMap class extends ProbabilityDistribution and will use the
 * methods of the super class if the internal instance is null.
 * 
 * @author  Mike Murray
 */
public class SpectrumMap extends ProbabilityDistribution implements IntensityMap {
	/** The intensity map used to compute a sample position within the spectral distribution. */
	private IntensityMap map;

	/** The probability distribution used for spectral sampling; if {@code null}, {@code this} is used. */
	private ProbabilityDistribution spectra;

	/**
	 * Constructs a {@link SpectrumMap} with no intensity map or probability distribution.
	 */
	public SpectrumMap() { this(null, null); }

	/**
	 * Constructs a {@link SpectrumMap} with the given intensity map and no separate distribution.
	 *
	 * @param m the intensity map used to determine the sample position
	 */
	public SpectrumMap(IntensityMap m) { this(m, null); }

	/**
	 * Constructs a {@link SpectrumMap} with the given intensity map and probability distribution.
	 *
	 * @param m    the intensity map used to determine the sample position
	 * @param spec the probability distribution; if {@code null}, this instance acts as its own distribution
	 */
	public SpectrumMap(IntensityMap m, ProbabilityDistribution spec) {
		this.map = m;
		this.spectra = spec;
	}

	/**
	 * Sets the intensity map used to derive the sample position within the distribution.
	 *
	 * @param m the new intensity map
	 */
	public void setIntensityMap(IntensityMap m) { this.map = m; }

	/**
	 * Returns the intensity map used to derive the sample position.
	 *
	 * @return the intensity map
	 */
	public IntensityMap getIntensityMap() { return this.map; }

	/**
	 * Sets the probability distribution used for spectral sampling.
	 *
	 * @param d the new probability distribution, or {@code null} to use this instance
	 */
	public void setProbabilityDistribution(ProbabilityDistribution d) { this.spectra = d; }

	/**
	 * Returns the probability distribution used for spectral sampling.
	 *
	 * <p>Returns {@code this} if no separate distribution has been set.</p>
	 *
	 * @return the effective probability distribution
	 */
	public ProbabilityDistribution getProbabilityDistribution() {
		if (this.spectra == null)
			return this;
		else
			return this.spectra;
	}
	
	/**
	 * Returns the intensity at the given 3D texture coordinate by delegating to the base intensity map.
	 *
	 * @param u the horizontal texture coordinate
	 * @param v the vertical texture coordinate
	 * @param w the depth texture coordinate
	 * @return the intensity at {@code (u, v, w)}
	 */
	@Override
	public double getIntensity(double u, double v, double w) {
		return this.map.getIntensity(u, v, w);
	}

	/**
	 * Returns a spectral sample corresponding to the intensity at the given texture coordinates.
	 *
	 * @param u the horizontal texture coordinate
	 * @param v the vertical texture coordinate
	 * @param w the depth texture coordinate
	 * @return the spectral sample at the intensity for position {@code (u, v, w)}
	 */
	public double getSample(double u, double v, double w) {
		return this.getSample(this.getIntensity(u, v, w));
	}

	/**
	 * Returns a spectral sample at the given quantile from the underlying distribution.
	 *
	 * @param r the quantile value in [0, 1]
	 * @return the spectral sample from the configured distribution
	 */
	@Override
	public double getSample(double r) {
		if (this.spectra != null) {
			return this.spectra.getSample(r);
		} else {
			return super.getSample(r);
		}
	}
	
	/**
	 * Adds a probability range to the underlying distribution.
	 *
	 * @param start the start of the range
	 * @param end   the end of the range
	 * @param p     the probability density for the range
	 */
	@Override
	public void addRange(double start, double end, double p) {
		if (this.spectra != null) {
			this.spectra.addRange(start, end, p);
		} else {
			super.addRange(start, end, p);
		}
	}
	
	/**
	 * Returns the ranges from the underlying distribution.
	 *
	 * @return the ranges array, or throws if a separate distribution is set
	 */
	@Override
	protected double[][] getRanges() {
		if (this.spectra != null) {
			throw new RuntimeException(new IllegalAccessException("getRanges is protected."));
		} else {
			return super.getRanges();
		}
	}
	
	/**
	 * Sets the probability of the range at the given index in the underlying distribution.
	 *
	 * @param index the index of the range to modify
	 * @param p     the new probability density
	 */
	@Override
	public void setRangeProbability(int index, double p) {
		if (this.spectra != null) {
			this.spectra.setRangeProbability(index, p);
		} else {
			super.setRangeProbability(index, p);
		}
	}
	
	/**
	 * Returns the cumulative integral of the underlying distribution up to {@code limit}.
	 *
	 * @param limit the upper bound of integration
	 * @return the integrated probability up to {@code limit}
	 */
	@Override
	public double integrate(double limit) {
		if (this.spectra != null) {
			return this.spectra.integrate(limit);
		} else {
			return super.integrate(limit);
		}
	}
	
	/**
	 * Returns the expected RGB color of the underlying spectral distribution.
	 *
	 * @return the integrated {@link RGB} color
	 */
	@Override
	public RGB getIntegrated() {
		if (this.spectra != null) {
			return this.spectra.getIntegrated();
		} else {
			return super.getIntegrated();
		}
	}
	
	/**
	 * Returns the probability density at {@code x} from the underlying distribution.
	 *
	 * @param x the domain value at which to evaluate the probability density
	 * @return the probability density at {@code x}
	 */
	@Override
	public double getProbability(double x) {
		if (this.spectra != null) {
			return this.spectra.getProbability(x);
		} else {
			return super.getProbability(x);
		}
	}
	
	/**
	 * Returns the number of nodes in the underlying distribution's binary search tree.
	 *
	 * @return the node count
	 */
	@Override
	public int getNodeCount() {
		if (this.spectra != null) {
			return this.spectra.getNodeCount();
		} else {
			return super.getNodeCount();
		}
	}
	
	/**
	 * Returns {@code true} if the underlying distribution contains the specified range.
	 *
	 * @param start the start of the range to test
	 * @param end   the end of the range to test
	 * @return {@code true} if the range overlaps any existing range
	 */
	@Override
	public boolean contains(double start, double end) {
		if (this.spectra != null) {
			return this.spectra.contains(start, end);
		} else {
			return super.contains(start, end);
		}
	}
	
	/**
	 * Loads the underlying distribution from a file.
	 *
	 * @param file the path to the input file
	 * @param div  the column delimiter used in the file
	 * @throws IOException if the file cannot be read
	 */
	@Override
	public void loadFromFile(String file, String div) throws IOException {
		if (this.spectra != null) {
			this.spectra.loadFromFile(file, div);
		} else {
			super.loadFromFile(file, div);
		}
	}
}
