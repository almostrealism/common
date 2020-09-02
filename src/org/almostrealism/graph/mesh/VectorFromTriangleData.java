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

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.math.DynamicAcceleratedProducerAdapter;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class VectorFromTriangleData extends DynamicAcceleratedProducerAdapter<Vector> implements VectorProducer {
	public static final int ABC = 0;
	public static final int DEF = 3;
	public static final int JKL = 6;
	public static final int NORMAL = 9;

	private Producer<TriangleData> triangle;
	private int position;

	private String value[];

	public VectorFromTriangleData(Producer<TriangleData> triangle, int position) {
		super(3, Vector.blank(), triangle);
		this.triangle = triangle;
		this.position = position;
	}

	@Override
	public String getValue(Argument arg, int pos) {
		if (value == null) {
			if (pos >= 0 && pos < 3) {
				return getArgumentValueName(1, position + pos);
			} else {
				throw new IllegalArgumentException(String.valueOf(pos));
			}
		} else {
			return value[pos];
		}
	}

	@Override
	public void compact() {
		super.compact();

		if (value == null && isCompletelyValueOnly()) {
			List<Argument> newArgs = new ArrayList<>();
			newArgs.add(getInputProducers()[0]);

			value = new String[3];

			for (int i = 0; i < value.length; i++) {
				value[i] = getInputProducerValue(1, position + i);
				if (value[i].contains("Infinity")) {
					throw new IllegalArgumentException("Infinity is not supported");
				}
			}

			newArgs.addAll(Arrays.asList(excludeResult(getInputProducer(1).getInputProducers())));

			inputProducers = newArgs.toArray(new Argument[0]);
			removeDuplicateArguments();
		}
	}
}
