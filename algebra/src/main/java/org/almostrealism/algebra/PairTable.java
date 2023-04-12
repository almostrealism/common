/*
 * Copyright 2021 Michael Murray
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
import org.almostrealism.hardware.mem.MemoryBankAdapter;
import io.almostrealism.relation.Evaluable;

/**
 * A collection of {@link PairBank}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
@Deprecated
public class PairTable extends MemoryBankAdapter<PackedCollection<Pair<?>>> { // implements MemoryTable<Pair> {
	public PairTable(int width, int count) {
		super(2 * width, count, delegateSpec ->
				Pair.bank(width, delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	// TODO  These should come from MemoryTable, but it is not easy to get generics to work

	public Pair get(int r, int c) {
		return get(r).get(c);
	}

	public void set(int r, int c, Pair value) {
		get(r).set(c, value);
	}

	public int getWidth() {
		return get(0).getCount();
	}
}
