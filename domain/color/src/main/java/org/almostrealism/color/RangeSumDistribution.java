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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A {@link ProbabilityDistribution} that combines multiple child distributions by summing
 * their probability ranges, merging overlapping intervals with accumulated densities.
 *
 * <p>Unlike {@link OverlayDistribution}, which queries children independently,
 * {@link RangeSumDistribution} attempts to build a unified set of ranges by merging the
 * child distributions' intervals. Where intervals overlap, densities are summed.
 * Note that the sampling ({@link #getSample(double)}) and integration
 * ({@link #getIntegrated()}) methods are not yet fully implemented.</p>
 *
 * @see ProbabilityDistribution
 * @see OverlayDistribution
 * @author Mike Murray
 */
public class RangeSumDistribution extends CompositeDistribution {
	/**
	 * Constructs a {@link RangeSumDistribution} with no children.
	 */
	public RangeSumDistribution() {
		super();
	}

	/**
	 * Constructs a {@link RangeSumDistribution} with the given child distributions.
	 *
	 * @param children the child distributions whose ranges will be merged
	 */
	public RangeSumDistribution(ProbabilityDistribution children[]) {
		super(children);
	}

	/**
	 * Computes a merged set of ranges from all child distributions.
	 *
	 * <p>Ranges from children are combined so that overlapping intervals have their
	 * probability densities summed. The implementation is incomplete in the
	 * multi-child case (marked TODO).</p>
	 *
	 * @return a 2D array of {@code [start, end, probability]} triples
	 */
	@Override
	protected double[][] getRanges() {
		if (getChildren().length <= 0) return new double[0][0];
		if (getChildren().length == 1) return getChildren()[0].getRanges();
		
		List l = new ArrayList();
		
		double ranges[][] = getChildren()[0].getRanges();
		for (int i = 0; i < ranges.length; i++) l.add(ranges[i]);
		
		for (int i = 1; i < getChildren().length; i++) {
			ranges = getChildren()[i].getRanges();
			
			for (int j = 0; j < ranges.length; j++) {
				Iterator itr = l.iterator();
				
				boolean in = false;

				w: while (itr.hasNext()) {
					double r[] = (double[]) itr.next();

					if (ranges[j][0] < r[0] && !in) {
						in = true;
						continue w;
					} else if (ranges[j][0] <= r[1] && !in) {
						in = true;
						itr.remove();
						continue w;
					}

					if (ranges[j][1] >= r[1] && in) {
						r[2] += ranges[j][2];
						if (ranges[j][1] == r[1]) break w;
					} else if (ranges[j][1] > r[0] && in) {
						itr.remove();
						break w;
					}
				}
				
				// TODO
			}
		}
		
		return (double[][]) l.toArray(new double[0][3]);
	}
	
	/**
	 * Sampling is not yet implemented; always returns {@code 0.0}.
	 *
	 * @param r a uniform random variate in [0, 1] (unused)
	 * @return {@code 0.0}
	 */
	@Override
	public double getSample(double r) {
		// TODO
		return 0.0;
	}

	/**
	 * RGB integration is not yet implemented; always returns {@code null}.
	 *
	 * @return {@code null}
	 */
	@Override
	public RGB getIntegrated() {
		// TODO
		return null;
	}

	/**
	 * Returns the {@link Method} object for {@link #createRangeSumDistribution(ProbabilityDistribution[])},
	 * useful for reflective invocation.
	 *
	 * @return the factory method for creating {@link RangeSumDistribution} instances
	 */
	public static Method getOverlayMethod() {
		try {
			return RangeSumDistribution.class.getMethod("createRangeSumDistribution",
								new Class[] {ProbabilityDistribution[].class});
		} catch (NoSuchMethodException e) {
			throw new RuntimeException(e);
		}
	}
	
	/**
	 * Factory method that constructs a {@link RangeSumDistribution} from the given children.
	 *
	 * @param children the child distributions to merge
	 * @return a new {@link RangeSumDistribution}
	 */
	public static RangeSumDistribution
				createRangeSumDistribution(ProbabilityDistribution children[]) {
		return new RangeSumDistribution(children);
	}
}
