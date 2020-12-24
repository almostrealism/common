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

package org.almostrealism.algebra;

import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.MemoryBankAdapter;
import io.almostrealism.relation.Evaluable;

/**
 * A collection of {@link Vector}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
public class VectorBank extends MemoryBankAdapter<Vector> {
	public VectorBank(int count) {
		super(3, count, delegateSpec ->
				new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public VectorBank(int count, CacheLevel cacheLevel) {
		super(3, count, delegateSpec ->
				new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()), cacheLevel);
	}

	protected VectorBank(int count, MemWrapper delegate, int delegateOffset) {
		super(3, count, delegateSpec ->
				new Vector(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	public static VectorBank fromProducer(Evaluable<Vector> producer, int count) {
		VectorBank bank = new VectorBank(count);
		for (int i = 0; i < bank.getCount(); i++) {
			bank.set(i, producer.evaluate());
		}

		return bank;
	}
}
