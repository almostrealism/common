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

import org.almostrealism.hardware.MemoryBankAdapter;
import org.almostrealism.util.Producer;

import java.util.function.Supplier;

/**
 * A collection of {@link Ray}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
public class RayBank extends MemoryBankAdapter<Ray> {
	public RayBank(int count) {
		super(6, count, delegateSpec ->
				new Ray(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public static RayBank fromProducer(Supplier<Producer<? extends Ray>> producer, int count) {
		RayBank bank = new RayBank(count);
		for (int i = 0; i < bank.getCount(); i++) {
			bank.set(i, producer.get().evaluate());
		}

		return bank;
	}
}
