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

package org.almostrealism.algebra.computations.jni;

import org.almostrealism.algebra.ScalarBank;

import java.util.function.Supplier;

public class NativeScalarBankDotProduct25 extends NativeScalarBankDotProduct {
	public NativeScalarBankDotProduct25() {
		super(25);
	}

	public NativeScalarBankDotProduct25(Supplier<ScalarBank> temp) {
		super(25, temp);
	}

	@Override
	public native void apply(long commandQueue, long[] arg, int[] offset, int[] size, int count);
}
