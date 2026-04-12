/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.music.data;

import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Gene;

/**
 * A three-dimensional parameter vector used to evaluate {@link ParameterFunction}s.
 *
 * <p>Each {@code ParameterSet} holds three scalar values (x, y, z) in the range [0, 1]
 * that serve as inputs to the sinusoidal parameterization functions in the pattern system.
 * Parameter sets are derived from genetic chromosomes during pattern generation.</p>
 *
 * @see ParameterFunction
 * @see MultipleParameterFunction
 */
public class ParameterSet {
	/** The three parameter values (x, y, z). */
	private double x, y, z;

	/** Creates a {@code ParameterSet} with all-zero values. */
	public ParameterSet() { }

	/**
	 * Creates a {@code ParameterSet} with the given values.
	 *
	 * @param x the x parameter value
	 * @param y the y parameter value
	 * @param z the z parameter value
	 */
	public ParameterSet(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	/** Returns the x parameter value. */
	public double getX() {
		return x;
	}

	/** Sets the x parameter value. */
	public void setX(double x) {
		this.x = x;
	}

	/** Returns the y parameter value. */
	public double getY() {
		return y;
	}

	/** Sets the y parameter value. */
	public void setY(double y) {
		this.y = y;
	}

	/** Returns the z parameter value. */
	public double getZ() {
		return z;
	}

	/** Sets the z parameter value. */
	public void setZ(double z) {
		this.z = z;
	}

	/**
	 * Creates a {@code ParameterSet} with uniformly random values in [0, 1].
	 *
	 * @return a new randomly initialized instance
	 */
	public static ParameterSet random() {
		return new ParameterSet(Math.random(), Math.random(), Math.random());
	}

	/**
	 * Creates a {@code ParameterSet} from the first three values of a genetic gene.
	 *
	 * @param gene the gene to extract parameter values from
	 * @return a new instance with values from the gene
	 */
	public static ParameterSet fromGene(Gene<PackedCollection> gene) {
		ParameterSet params = new ParameterSet();
		params.setX(gene.getResultant(0, null).evaluate().toDouble());
		params.setY(gene.getResultant(1, null).evaluate().toDouble());
		params.setZ(gene.getResultant(2, null).evaluate().toDouble());
		return params;
	}
}
