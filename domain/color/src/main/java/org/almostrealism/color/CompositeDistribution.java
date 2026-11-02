/*
 * Copyright 2026 Michael Murray
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

/**
 * A {@link ProbabilityDistribution} defined by combining a set of child distributions.
 *
 * <p>This is the shared base for the ways child distributions can be combined. The operations
 * that are independent of the combination strategy live here: a range is always added to the
 * first child, the probability density at a point is always the sum of the children's densities,
 * a range is contained if any child contains it, and loading from a file always replaces the
 * children with a single child populated from the file. Subclasses supply the strategy-specific
 * behavior — how the children's ranges are combined ({@link #getRanges()}), how the combined
 * distribution is sampled ({@link #getSample(double)}) and integrated ({@link #getIntegrated()}).</p>
 *
 * @see OverlayDistribution
 * @see RangeSumDistribution
 * @author Michael Murray
 */
public abstract class CompositeDistribution extends ProbabilityDistribution {
	/** The child distributions combined by this distribution. */
	private ProbabilityDistribution children[];

	/**
	 * Constructs a {@link CompositeDistribution} with no children.
	 */
	protected CompositeDistribution() {
		this(new ProbabilityDistribution[0]);
	}

	/**
	 * Constructs a {@link CompositeDistribution} with the given child distributions.
	 *
	 * @param children the child distributions to combine
	 */
	protected CompositeDistribution(ProbabilityDistribution children[]) {
		this.children = children;
	}

	/**
	 * Returns the child distributions combined by this distribution.
	 *
	 * @return the child distributions
	 */
	protected ProbabilityDistribution[] getChildren() {
		return this.children;
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
	 * Loads the distribution from a file, replacing all children with a single child
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
}
