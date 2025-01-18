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
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.Producer;
import org.almostrealism.io.Describable;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class DelegatedProducer<T> implements
		Process<Process<?, ?>, Evaluable<? extends T>>,
		Producer<T>, Countable,
		OperationInfo {
	protected Producer<T> op;
	protected boolean direct;
	protected OperationMetadata metadata;

	public DelegatedProducer(Producer<T> op) {
		this(op, true);
	}

	public DelegatedProducer(Producer<T> op, boolean directDelegate) {
		this.op = op;
		this.direct = directDelegate;
		prepareMetadata();
	}

	protected String extendDescription(String description, boolean brief) {
		if (brief) {
			return "delegate(" + description + ")";
		} else {
			return getClass().getSimpleName() + "(" + description + ")";
		}
	}

	protected void prepareMetadata() {
		if (op instanceof OperationInfo) {
			OperationMetadata child = ((OperationInfo) op).getMetadata();
			this.metadata = new OperationMetadata(
									extendDescription(child.getDisplayName(), true),
									extendDescription(child.getShortDescription(), false));
			this.metadata.setChildren(List.of(child));
		} else {
			this.metadata = new OperationMetadata("delegate",
									getClass().getSimpleName());
		}
	}

	@Override
	public OperationMetadata getMetadata() { return metadata; }

	@Override
	public Collection<Process<?, ?>> getChildren() {
		return op instanceof Process ? List.of((Process<?, ?>) op) : Collections.emptyList();
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
	public Parent<Process<?, ?>> generate(List<Process<?, ?>> children) {
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
