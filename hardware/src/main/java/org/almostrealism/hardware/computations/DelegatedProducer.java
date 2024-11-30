/*
 * Copyright 2024 Michael Murray
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

package org.almostrealism.hardware.computations;

import io.almostrealism.code.OperationInfo;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.relation.Countable;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.io.Describable;

import java.util.Collection;
import java.util.Collections;

public class DelegatedProducer<T> implements
		Process<Process<?, ?>, Evaluable<? extends T>>,
		Producer<T>, Countable,
		OperationInfo {
	protected Producer<T> op;
	protected boolean direct;

	public DelegatedProducer(Producer<T> op) {
		this(op, true);
	}

	public DelegatedProducer(Producer<T> op, boolean directDelegate) {
		this.op = op;
		this.direct = directDelegate;
	}

	@Override
	public OperationMetadata getMetadata() {
		return op instanceof OperationInfo ? ((OperationInfo) op).getMetadata() : null;
	}

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return op instanceof Process ? ((Process) op).getChildren() : Collections.emptyList();
	}

	@Override
	public Evaluable<T> get() {
		if (direct) {
			// Return the original Evaluable
			return op.get();
		} else {
			// Hide any information about the original Evaluable
			Evaluable<T> original = op.get();
			return original::evaluate;
		}
	}

	@Override
	public long getCountLong() {
		return Countable.countLong(op);
	}

	@Override
	public boolean isFixedCount() {
		return Countable.isFixedCount(op);
	}

	@Override
	public long getOutputSize() {
		throw new UnsupportedOperationException();
	}

	@Override
	public Process<Process<?, ?>, Evaluable<? extends T>> isolate() {
		return this;
	}

	@Override
	public String describe() {
		return Describable.describe(op);
	}
}
