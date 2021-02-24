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

package org.almostrealism.geometry;

import org.almostrealism.hardware.MemWrapper;
import org.almostrealism.hardware.mem.MemoryBankAdapter;
import io.almostrealism.relation.Evaluable;

/**
 * A collection of {@link TransformMatrix}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
public class TransformMatrixBank extends MemoryBankAdapter<TransformMatrix> {
	public TransformMatrixBank(int count) {
		super(16, count, delegateSpec ->
				new TransformMatrix(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	protected TransformMatrixBank(int count, MemWrapper delegate, int delegateOffset) {
		super(16, count, delegateSpec ->
						new TransformMatrix(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	public static TransformMatrixBank fromProducer(Evaluable<TransformMatrix> producer, int count) {
		TransformMatrixBank bank = new TransformMatrixBank(count);
		for (int i = 0; i < bank.getCount(); i++) {
			bank.set(i, producer.evaluate());
		}

		return bank;
	}
}
