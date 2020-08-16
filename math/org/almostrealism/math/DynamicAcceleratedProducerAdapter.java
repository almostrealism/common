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

package org.almostrealism.math;

import org.almostrealism.util.AcceleratedStaticProducer;
import org.almostrealism.util.Producer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public abstract class DynamicAcceleratedProducerAdapter<T extends MemWrapper> extends DynamicAcceleratedProducer<T> {
	private int memLength;

	public DynamicAcceleratedProducerAdapter(int memLength, Producer<?>... inputArgs) {
		super(true, inputArgs, new Producer[0]);
		this.memLength = memLength;
	}

	public DynamicAcceleratedProducerAdapter(int memLength, Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(true, inputArgs, additionalArguments);
		this.memLength = memLength;
	}

	public DynamicAcceleratedProducerAdapter(int memLength, boolean kernel, Producer<?>[] inputArgs, Object[] additionalArguments) {
		super(kernel, inputArgs, additionalArguments);
		this.memLength = memLength;
	}

	public int getMemLength() { return memLength; }

	@Override
	public String getBody(Function<Integer, String> outputVariable) {
		StringBuffer buf = new StringBuffer();

		for (int i = 0; i < memLength; i++) {
			buf.append(outputVariable.apply(i) + " = " + getValue(i) + ";\n");
		}

		return buf.toString();
	}

	public abstract String getValue(int pos);

	public boolean isValueOnly() {
		return true;
	}

	protected boolean isCompletelyValueOnly() {
		// Confirm that all inputs are themselves dynamic accelerated adapters
		for (int i = 1; i < getInputProducers().length; i++) {
			if (getInputProducers()[i].getProducer() instanceof DynamicAcceleratedProducerAdapter == false)
				return false;
			if (!((DynamicAcceleratedProducerAdapter) getInputProducers()[i].getProducer()).isValueOnly())
				return false;
		}

		return true;
	}

	protected static List<DynamicAcceleratedProducerAdapter> extractStaticProducers(Argument args[]) {
		List<DynamicAcceleratedProducerAdapter> staticProducers = new ArrayList<>();

		for (int i = 1; i < args.length; i++) {
			if (args[i].getProducer() instanceof DynamicAcceleratedProducerAdapter && args[i].getProducer().isStatic()) {
				staticProducers.add((DynamicAcceleratedProducerAdapter) args[i].getProducer());
			}
		}

		return staticProducers;
	}

	protected static List<DynamicAcceleratedProducerAdapter> extractDynamicProducers(Argument args[]) {
		List<DynamicAcceleratedProducerAdapter> dynamicProducers = new ArrayList<>();

		for (int i = 1; i < args.length; i++) {
			if (args[i].getProducer() instanceof DynamicAcceleratedProducerAdapter == false ||
					!args[i].getProducer().isStatic()) {
				dynamicProducers.add((DynamicAcceleratedProducerAdapter) args[i].getProducer());
			}
		}

		return dynamicProducers;
	}
}
