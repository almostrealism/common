/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware.mem;

import io.almostrealism.relation.Delegated;
import org.almostrealism.hardware.DestinationSupport;
import org.almostrealism.hardware.DynamicProducerForMemoryData;
import org.almostrealism.hardware.MemoryData;
import org.almostrealism.hardware.MemoryBank;

import java.util.function.IntFunction;

public class MemoryDataDestination<T extends MemoryData> extends DynamicProducerForMemoryData<T> implements Delegated<DestinationSupport<T>> {
	private final DestinationSupport<T> destination;

	public MemoryDataDestination(DestinationSupport<T> destination) {
		this(destination, null);
	}

	public MemoryDataDestination(DestinationSupport<T> destination, IntFunction<MemoryBank<T>> kernelDestination) {
		super(args -> destination.getDestination().get(), kernelDestination);
		this.destination = destination;
	}

	@Override
	public DestinationSupport<T> getDelegate() { return destination; }
}