/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.algebra;

import org.almostrealism.collect.PackedCollection;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.relation.Evaluable;

/**
 * A collection of {@link PackedCollection}s of {@link Scalar}s
 * of a fixed length, that is contiguous in RAM and usable for
 * kernel operations.
 *
 * @author  Michael Murray
 */
@Deprecated
public class ScalarTable extends PackedCollection<PackedCollection<Scalar>> { // implements MemoryTable<Scalar> {
	public ScalarTable(int width, int count) {
		super(new TraversalPolicy(count, width, 2), 1, delegateSpec ->
				Scalar.scalarBank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	// TODO  These should come from MemoryTable, but it is not easy to get generics to work

	public Scalar get(int r, int c) {
		return get(r).get(c);
	}

	public void set(int r, int c, Scalar value) {
		get(r).set(c, value);
	}

	public int getWidth() {
		return get(0).getCount();
	}

	public ScalarTable copy(int w, int h) {
		ScalarTable out = new ScalarTable(w, h);
		for (int i = 0; i < h; i++) {
			for (int j = 0; j < w; j++) {
				out.set(i, j, get(i, j));
			}
		}
		return out;
	}

	// TODO  ... see above ...

	public static ScalarTable fromProducer(Evaluable<PackedCollection<Scalar>> producer, int width, int count) {
		ScalarTable table = new ScalarTable(width, count);
		for (int i = 0; i < table.getCount(); i++) {
			table.set(i, producer.evaluate());
		}

		return table;
	}
}
