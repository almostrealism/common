/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.space.distribution;

import io.almostrealism.uml.Nameable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.stats.SphericalProbabilityDistribution;

import java.lang.reflect.Method;

/**
 * Sums one sample from each of several child distributions. The sum is normalized by default,
 * which {@link #setNormalizeResult(boolean)} can turn off.
 *
 * @author  Michael Murray
 */
public class OverlayBRDF extends DirectionDistribution implements Nameable {
	/** The distributions whose samples are summed. */
	private final SphericalProbabilityDistribution[] children;

	/** Whether the summed sample is normalized. */
	private boolean norm = true;

	/** The name of this distribution. */
	private String name;

	/**
	 * Creates an overlay of the given distributions.
	 *
	 * @param children the distributions whose samples are summed
	 */
	public OverlayBRDF(SphericalProbabilityDistribution[] children) {
		this.children = children;
	}

	@Override
	protected Vector direction(Vector in, Vector orient) {
		Vector result = new Vector();

		for (SphericalProbabilityDistribution child : children) {
			result.addTo(new Vector(child.getSample(in.toArray(), orient.toArray()).get().evaluate(), 0));
		}

		if (norm) result.normalize();
		return result;
	}

	/**
	 * Sets whether the summed sample is normalized.
	 *
	 * @param norm true to normalize the sum
	 */
	public void setNormalizeResult(boolean norm) { this.norm = norm; }

	/**
	 * Returns whether the summed sample is normalized.
	 *
	 * @return true if the sum is normalized
	 */
	public boolean getNormalizeResult() { return this.norm; }

	/**
	 * Returns {@link #createOverlayBRDF(SphericalProbabilityDistribution[])}, for tools that combine
	 * distributions reflectively.
	 *
	 * @return the factory method
	 */
	public static Method getOverlayMethod() {
		try {
			return OverlayBRDF.class.getMethod("createOverlayBRDF",
					SphericalProbabilityDistribution[].class);
		} catch (NoSuchMethodException e) {
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Creates an overlay of the given distributions.
	 *
	 * @param children the distributions whose samples are summed
	 * @return the overlay
	 */
	public static OverlayBRDF createOverlayBRDF(SphericalProbabilityDistribution[] children) {
		return new OverlayBRDF(children);
	}

	@Override
	public void setName(String n) { this.name = n; }

	@Override
	public String getName() { return name; }
}
