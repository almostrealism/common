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

package org.almostrealism.audio.data;

import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.heredity.Gene;

public class ParameterSet {
	private double x, y, z;

	public ParameterSet() { }

	public ParameterSet(double x, double y, double z) {
		this.x = x;
		this.y = y;
		this.z = z;
	}

	public double getX() {
		return x;
	}

	public void setX(double x) {
		this.x = x;
	}

	public double getY() {
		return y;
	}

	public void setY(double y) {
		this.y = y;
	}

	public double getZ() {
		return z;
	}

	public void setZ(double z) {
		this.z = z;
	}

	public static ParameterSet random() {
		return new ParameterSet(Math.random(), Math.random(), Math.random());
	}

	public static ParameterSet fromGene(Gene<PackedCollection> gene) {
		CollectionProducer one =
				CollectionFeatures.getInstance().c(1.0);

		ParameterSet params = new ParameterSet();
		params.setX(gene.getResultant(0, null).evaluate().toDouble());
		params.setY(gene.getResultant(1, null).evaluate().toDouble());
		params.setZ(gene.getResultant(2, null).evaluate().toDouble());
		return params;
	}
}
