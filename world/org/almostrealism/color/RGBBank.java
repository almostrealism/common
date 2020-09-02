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

import org.almostrealism.math.MemoryBankAdapter;
import org.almostrealism.util.Producer;

/**
 * A collection of {@link RGB}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
public class RGBBank extends MemoryBankAdapter<RGB> {
	public RGBBank(int count) {
		super(3, count, delegateSpec ->
				new RGB(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public static RGBBank fromProducer(Producer<RGB> producer, int count) {
		RGBBank bank = new RGBBank(count);
		for (int i = 0; i < bank.getCount(); i++) {
			bank.set(i, producer.evaluate());
		}

		return bank;
	}
}