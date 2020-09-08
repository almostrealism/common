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

package org.almostrealism.color;

import org.almostrealism.math.MemWrapper;
import org.almostrealism.math.MemoryBankAdapter;
import org.almostrealism.util.Producer;

/**
 * A collection of {@link RGBData192}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
public class RGBData192Bank extends MemoryBankAdapter<RGBData192> {
	public RGBData192Bank(int count) {
		super(3, count, delegateSpec ->
				new RGBData192(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	protected RGBData192Bank(int count, MemWrapper delegate, int delegateOffset) {
		super(3, count, delegateSpec ->
						new RGBData192(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}

	public static RGBData192Bank fromProducer(Producer<RGBData192> producer, int count) {
		RGBData192Bank bank = new RGBData192Bank(count);
		for (int i = 0; i < bank.getCount(); i++) {
			bank.set(i, producer.evaluate());
		}

		return bank;
	}
}
