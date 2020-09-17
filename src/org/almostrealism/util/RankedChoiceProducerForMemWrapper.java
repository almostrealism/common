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

import org.almostrealism.hardware.MemWrapper;

public class RankedChoiceProducerForMemWrapper<T extends MemWrapper> extends RankedChoiceProducer<T> {
	public RankedChoiceProducerForMemWrapper(double e) {
		super(e);
	}

	public RankedChoiceProducerForMemWrapper(double e, boolean tolerateNull) {
		super(e, tolerateNull);
	}

	public AcceleratedRankedChoiceProducer<T> getAccelerated(int memLength, Producer<T> blankValue) {
		return new AcceleratedRankedChoiceProducer(memLength, blankValue, this, getEpsilon(), blankValue::evaluate);
	}
}
