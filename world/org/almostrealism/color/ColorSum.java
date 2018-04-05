/*
 * Copyright 2018 Michael Murray
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import io.almostrealism.code.Scope;
import org.almostrealism.uml.Function;

/**
 * @author  Michael Murray
 */
@Function
public class ColorSum extends ColorFutureAdapter {
	public ColorSum() { }
	
	public ColorSum(Future<ColorProducer>... producers) { addAll(Arrays.asList(producers)); }
	
	@Override
	public RGB evaluate(Object[] args) {
		RGB rgb = new RGB();
		
		for (Future<ColorProducer> c : this) {
			try {
				rgb.addTo(c.get().evaluate(args));
			} catch (InterruptedException | ExecutionException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		return rgb;
	}

	@Override
	public Scope getScope(String prefix) {
		throw new RuntimeException("getScope is not implemented"); // TODO
	}

	// TODO  Combine ColorSums that are equal by converting to ColorProduct
	// TODO  If all the members of a ColorSum are ColorProducts, and those
	//       ColorProducts contain matching terms, the matching terms can
	//       be extracted and a the multiplication can be performed after
	//       the sum. In a simple example this could take 3 products and
	//       two sums and convert it into two sums and two sums and one
	//       product
	@Override
	public void compact() {
		super.compact();

		List<Future<ColorProducer>> p = new ArrayList<>();
		List<StaticColorProducer> replaced = new ArrayList<>();

		for (ColorProducer c : getStaticColorProducers()) {
			if (c instanceof ColorSum) {
				replaced.add(new StaticColorProducer(c));

				for (Future<ColorProducer> cp : ((ColorSum) c)) {
					p.add(cp);
				}
			}
		}

		for (StaticColorProducer s : replaced) {
			remove(replaced);
		}

		addAll(p);
	}
}
