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

package org.almostrealism.algebra.computations;

import org.almostrealism.algebra.Pair;
import org.almostrealism.algebra.Scalar;
import org.almostrealism.algebra.ScalarProducer;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;

public class ScalarFromPair extends DynamicAcceleratedProducerAdapter<Scalar> implements ScalarProducer {
	public static final int X = 0;
	public static final int Y = 1;

	private Producer<Pair> pair;
	private int coordinate;

	private String value;
	private boolean isStatic;

	public ScalarFromPair(Producer<Pair> pair, int coordinate) {
		super(2, Scalar.blank(), pair);
		this.pair = pair;
		this.coordinate = coordinate;
	}

	@Override
	public String getValue(int pos) {
		if (value == null) {
			if (pos == 0) {
				return getArgumentValueName(1, coordinate);
			} else if (pos == 1) {
				return "1.0";
			} else {
				throw new IllegalArgumentException(String.valueOf(pos));
			}
		} else {
			return pos == 0 ? value : "1.0";
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyDynamicAcceleratedAdapters()) {
			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(getInputProducers()[0]);

			value = ((DynamicAcceleratedProducerAdapter) getInputProducers()[1].getProducer()).getValue(coordinate);

			if (getInputProducers()[1].getProducer().isStatic()) {
				isStatic = true;
			}

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}

	@Override
	public boolean isStatic() { return isStatic; }
}