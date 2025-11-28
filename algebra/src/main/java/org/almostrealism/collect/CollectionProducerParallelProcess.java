/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.collect;

import io.almostrealism.code.ComputableParallelProcess;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.compute.Process;

/**
 * A {@link CollectionProducer} that can be executed as a parallel process.
 *
 * <p>
 * {@link CollectionProducerParallelProcess} combines the capabilities of a {@link CollectionProducer}
 * with {@link ComputableParallelProcess}, enabling hardware-accelerated parallel execution of
 * collection computations. This is the foundation for compiling collection operations to GPU/CPU kernels.
 * </p>
 *
 * <p>
 * This interface is typically implemented by computation classes that produce collection results
 * and can be compiled to native code or hardware kernels for efficient execution. The parallel
 * process capabilities enable:
 * <ul>
 *   <li>Multi-threaded CPU execution</li>
 *   <li>GPU kernel compilation and execution</li>
 *   <li>Optimization and fusion of operations</li>
 *   <li>Memory-efficient batch processing</li>
 * </ul>
 *
 * @author  Michael Murray
 * @see CollectionProducer
 * @see ComputableParallelProcess
 * @see org.almostrealism.collect.computations.CollectionProducerComputationBase
 */
public interface CollectionProducerParallelProcess extends
			CollectionProducer, ComputableParallelProcess<Process<?, ?>, Evaluable<? extends PackedCollection>> {
}
