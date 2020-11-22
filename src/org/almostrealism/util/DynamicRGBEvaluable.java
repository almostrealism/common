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

package org.almostrealism.util;

import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBBank;
import org.almostrealism.hardware.KernelizedEvaluable;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.Function;

public class DynamicRGBEvaluable extends DynamicEvaluable<RGB> implements KernelizedEvaluable<RGB> {

	public DynamicRGBEvaluable(Function<Object[], RGB> function) {
		super(function);
	}

	@Override
	public MemoryBank<RGB> createKernelDestination(int size) { return new RGBBank(size); }
}
