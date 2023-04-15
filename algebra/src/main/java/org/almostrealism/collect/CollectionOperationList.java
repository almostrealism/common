/*
 * Copyright 2023 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.almostrealism.collect;

import io.almostrealism.relation.Producer;
import org.almostrealism.hardware.KernelOperation;
import org.almostrealism.hardware.MemoryBank;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.OperationList;

public class CollectionOperationList extends OperationList implements CollectionFeatures {

	public KernelOperation kernel(KernelExpression kernel, PackedCollection destination, Producer<? extends MemoryData>... arguments){
		return kernel(kernel, destination.getShape(), destination, arguments);
	}

	public KernelOperation kernel(KernelExpression kernel, TraversalPolicy shape, MemoryBank destination, Producer<? extends MemoryData>... arguments) {
		return add(kernel(kernelIndex(), shape, kernel, arguments), destination);
	}

	public KernelOperation kernel(Func.v2e3 f, PackedCollection<?> destination, Producer<? extends MemoryData>... arguments) {
		return kernel(Func.kernel3(f), destination, arguments);
	}

	public KernelOperation kernel(Func.v2e3 f, TraversalPolicy shape, MemoryBank destination, Producer<? extends MemoryData>... arguments) {
		return kernel(Func.kernel3(f), shape, destination, arguments);
	}

	public KernelOperation kernel(Func.v3e3 f, PackedCollection<?> destination, Producer<? extends MemoryData>... arguments) {
		return kernel(Func.kernel3(f), destination, arguments);
	}

	public KernelOperation kernel(Func.v3e3 f, TraversalPolicy shape, MemoryBank destination, Producer<? extends MemoryData>... arguments) {
		return kernel(Func.kernel3(f), shape, destination, arguments);
	}
}
