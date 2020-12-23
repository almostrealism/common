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

/**
 * A collection of {@link Scalar}s of a fixed length, that is contiguous in
 * RAM and usable for kernel methods.
 *
 * @author  Michael Murray
 */
public class ScalarBank extends MemoryBankAdapter<Scalar> {
	public ScalarBank(int count) {
		super(2, count, delegateSpec ->
				new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()));
	}

	public ScalarBank(int count, CacheLevel cacheLevel) {
		super(2, count, delegateSpec ->
				new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()), cacheLevel);
	}

	public ScalarBank(int count, MemWrapper delegate, int delegateOffset, CacheLevel cacheLevel) {
		super(3, count, delegateSpec ->
						new Scalar(delegateSpec.getDelegate(), delegateSpec.getOffset()),
				delegate, delegateOffset);
	}
}