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
public class OverlayDistribution extends CompositeDistribution {
	/**
	 * Constructs an {@link OverlayDistribution} with no children.
	 */
	public OverlayDistribution() {
		super();
	}

	/**
	 * Constructs an {@link OverlayDistribution} with the given child distributions.
	 *
	 * @param children the child distributions to overlay
	 */
	public OverlayDistribution(ProbabilityDistribution children[]) {
		super(children);
	}

	/**
	 * Returns the ranges of the first child distribution, or an empty array if there are no children.
	 *
	 * @return a 2D array of {@code [start, end, probability]} triples from the first child
	 */
	@Override
	protected double[][] getRanges() {
		if (getChildren().length <= 0) return new double[0][3];
		return getChildren()[0].getRanges();
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
		
		for (int i = 0; i < getChildren().length; i++)
			v = v + getChildren()[i].integrate(limit);
		
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
		if (getChildren().length <= 0) return 0.0;
		if (getChildren().length == 1) return getChildren()[0].getSample(r);
		
		double d = getChildren()[0].getSample(r);
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
		
		for (int i = 0; i < getChildren().length; i++)
			c.addTo(getChildren()[i].getIntegrated());
		
		return c;
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
