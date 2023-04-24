/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.color.computations;

import io.almostrealism.code.ProducerComputation;
import io.almostrealism.scope.Scope;
import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.TripleFunction;
import org.almostrealism.color.DynamicRGBProducer;
import org.almostrealism.color.RGB;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class AverageColor implements ProducerComputation<RGB>, KernelizedProducer<RGB> {
	private static class Color {
		double p;
		RGB c;
	}
	
	private List<Color> colors;
	private double tot;
	private boolean invert;
	
	public AverageColor() {
		this.colors = new ArrayList<Color>();
	}
	
	public void addColor(double p, RGB c) {
		if (this.invert) p = 1.0 / p;
		
		Color color = new Color();
		color.p = p;
		color.c = c;
		this.colors.add(color);
		this.tot += p;
	}
	
	public void setInvert(boolean invert) { this.invert = invert; }

	@Override
	public KernelizedEvaluable<RGB> get() {
		return new DynamicRGBProducer(args -> {
			RGB c = new RGB(0.0, 0.0, 0.0);
			Iterator itr = this.colors.iterator();

			w:
			while (itr.hasNext()) {
				Color n = (Color) itr.next();
				if (n.c == null) continue w;
				c.addTo(n.c.multiply(n.p / this.tot));
			}

			return c;
		}).get();
	}

	@Override
	public Scope<RGB> getScope() {
		throw new RuntimeException("Not implemented");
	}

	@Override
	public void compact() {
		// TODO  AverageColor should be modified to accept ColorProducers instead
		//       of RGB values and this method should delegate to them
	}
}
