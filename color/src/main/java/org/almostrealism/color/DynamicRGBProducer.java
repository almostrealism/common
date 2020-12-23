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

package org.almostrealism.color;

import io.almostrealism.code.Scope;
import io.almostrealism.code.NameProvider;
import org.almostrealism.hardware.DynamicProducerForMemWrapper;

import java.util.function.Function;

public class DynamicRGBProducer extends DynamicProducerForMemWrapper<RGB> implements RGBProducer {

	public DynamicRGBProducer(Function<Object[], RGB> function) {
		super(function, RGBBank::new);
	}

	@Override
	public Scope<RGB> getScope(NameProvider provider) {
		throw new RuntimeException("Not implemented");
	}
}
