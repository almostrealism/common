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

package org.almostrealism.hardware;

import io.almostrealism.relation.Evaluable;
import io.almostrealism.uml.Multiple;

/**
 * A {@link KernelizedEvaluable} is a {@link Evaluable} that can be evaluated
 * for a {@link MemoryBank} with one operation. The default implementation
 * of this {@link MemoryBank} evaluation simply delegates to the normal
 * {@link #evaluate(Object[])} method for each element of the
 * {@link MemoryBank}.
 *
 * @author  Michael Murray
 */
@Deprecated
public interface KernelizedEvaluable<T extends MemoryData> extends Evaluable<T> {

	@Override
	default Evaluable<T> into(Object destination) {
		return withDestination((MemoryBank<T>) destination);
	}

	default Evaluable<T> withDestination(MemoryBank<T> destination) {
		return new DestinationEvaluable<>((Evaluable) this, destination);
	}

	default int getArgsCount() {
		throw new UnsupportedOperationException();
	}
}
