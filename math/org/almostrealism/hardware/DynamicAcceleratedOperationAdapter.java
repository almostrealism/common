/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.code.ComputationOperationAdapter;
import org.almostrealism.util.Producer;

import java.util.Arrays;

public abstract class DynamicAcceleratedOperationAdapter extends ComputationOperationAdapter<Void> implements ComputerFeatures {
	public DynamicAcceleratedOperationAdapter(Producer<?>... inputArgs) {
		this(inputArgs, new Producer[0]);
	}

	public DynamicAcceleratedOperationAdapter(Producer<?>[] inputArgs, Object[] additionalArguments) {
		this.setArguments(Arrays.asList(arguments(AcceleratedProducer.producers(inputArgs, additionalArguments))));
		init();
	}

	@Override
	public String getDefaultAnnotation() { return "__global"; }
}
