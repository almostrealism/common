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

package org.almostrealism.color;

import java.io.IOException;
import java.lang.reflect.Method;

/**
 * A {@link ProbabilityDistribution} that combines multiple child distributions by overlaying
 * their probability densities, aggregating samples and integration across all children.
 *
 * <p>{@code OverlayDistribution} delegates range management to the first child distribution,
 * but computes probability density, integration, and the expected RGB color as the sum across
 * all children. Sampling uses a Newton-style iteration to find the domain value whose cumulative
 * probability matches the requested quantile.</p>
 *
 * <p>This is useful for building composite spectral emission models from independent spectral
 * components that may cover overlapping wavelength ranges.</p>
 *
 * @see ProbabilityDistribution
 * @see RangeSumDistribution
 * @author Mike Murray
 */
public class OverlayDistribution extends ProbabilityDistribution {
	/** The child distributions whose densities are combined by this overlay. */
	private ProbabilityDistribution children[];

	/**
	 * Constructs an {@link OverlayDistribution} with no children.
	 */
	public OverlayDistribution() {
		this(new ProbabilityDistribution[0]);
	}

	/**
	 * Constructs an {@link OverlayDistribution} with the given child distributions.
	 *
	 * @param children the child distributions to overlay
	 */
	public OverlayDistribution(ProbabilityDistribution children[]) {
		this.children = children;
	}

	/**
	 * Adds a range to the first child distribution.
	 *
	 * <p>Has no effect if there are no children.</p>
	 *
	 * @param start the start of the range
	 * @param end   the end of the range
	 * @param p     the probability density for the range
	 */
	@Override
	public void addRange(double start, double end, double p) {
		if (this.children.length <= 0) return;
		this.children[0].addRange(start, end, p);
	}
	
	/**
	 * Returns the ranges of the first child distribution, or an empty array if there are no children.
	 *
	 * @return a 2D array of {@code [start, end, probability]} triples from the first child
	 */
	@Override
	protected double[][] getRanges() {
		if (this.children.length <= 0) return new double[0][3];
		return this.children[0].getRanges();
	}

	/**
	 * Returns the sum of the cumulative integrals of all child distributions up to {@code limit}.
	 *
	 * @param limit the upper bound of integration
	 * @return the total integrated probability across all children up to {@code limit}
	 */
	@Override
	public double integrate(double limit) {
		double v = 0.0;
		
		for (int i = 0; i < this.children.length; i++)
			v = v + this.children[i].integrate(limit);
		
		return v;
	}
	
	/**
	 * Returns a sample from the overlaid distribution corresponding to the quantile {@code r}.
	 *
	 * <p>When only one child is present, sampling is delegated directly to it. With multiple
	 * children, a Newton-style iteration adjusts the sample until the cumulative integral
	 * matches {@code r} within a tolerance of 0.0001.</p>
	 *
	 * @param r a uniform random variate in [0, 1]
	 * @return the sampled domain value
	 */
	@Override
	public double getSample(double r) {
		if (this.children.length <= 0) return 0.0;
		if (this.children.length == 1) return this.children[0].getSample(r);
		
		double d = this.children[0].getSample(r);
		double v = this.integrate(d);
		
		while (Math.abs(v - r) > 0.0001) {
			d = d * r / v;
			v = this.integrate(d);
		}
		
		return v;
	}
	
	/**
	 * Returns the expected RGB color of the overlaid distribution by summing the integrated
	 * colors of all child distributions.
	 *
	 * @return the sum of {@link ProbabilityDistribution#getIntegrated()} across all children
	 */
	@Override
	public RGB getIntegrated() {
		RGB c = new RGB();
		
		for (int i = 0; i < this.children.length; i++)
			c.addTo(this.children[i].getIntegrated());
		
		return c;
	}
	
	/**
	 * Returns the total probability density at {@code x} by summing the densities
	 * of all child distributions.
	 *
	 * @param x the domain value at which to evaluate the probability density
	 * @return the sum of probability densities across all children at {@code x}
	 */
	@Override
	public double getProbability(double x) {
		double p = 0.0;
		
		for (int i = 0; i < this.children.length; i++)
			p += this.children[i].getProbability(x);
		
		return p;
	}
	
	/**
	 * Returns {@code true} if any child distribution contains the specified range.
	 *
	 * @param start the start of the range to test
	 * @param end   the end of the range to test
	 * @return {@code true} if at least one child contains the range
	 */
	@Override
	public boolean contains(double start, double end) {
		for (int i = 0; i < this.children.length; i++)
			if (this.children[i].contains(start, end)) return true;
		return false;
	}
	
	/**
	 * Loads the overlay from a file, replacing all children with a single child distribution
	 * populated from the file contents.
	 *
	 * @param file the path to the input file
	 * @param div  the delimiter used between columns in the file
	 * @throws IOException if the file cannot be read
	 */
	@Override
	public void loadFromFile(String file, String div) throws IOException {
		this.children = new ProbabilityDistribution[1];
		this.children[0] = new ProbabilityDistribution();
		this.children[0].loadFromFile(file, div);
	}
	
	/**
	 * Returns the {@link Method} object for {@link #createOverlayDistribution(ProbabilityDistribution[])},
	 * useful for reflective invocation.
	 *
	 * @return the factory method for creating {@link OverlayDistribution} instances
	 */
	public static Method getOverlayMethod() {
		try {
			return OverlayDistribution.class.getMethod("createOverlayDistribution",
								new Class[] {ProbabilityDistribution[].class});
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Factory method that constructs an {@link OverlayDistribution} from the given children.
	 *
	 * @param children the child distributions to overlay
	 * @return a new {@link OverlayDistribution}
	 */
	public static OverlayDistribution
				createOverlayDistribution(ProbabilityDistribution children[]) {
		return new OverlayDistribution(children);
	}
}
