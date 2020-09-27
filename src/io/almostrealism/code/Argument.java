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

package io.almostrealism.code;

import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.util.Producer;
import org.almostrealism.util.ProducerWithRank;

/**
 * A parameter for a {@link Method}. Note that this type will extract
 * the internal {@link Producer} from instances of {@link ProducerWithRank}
 * and the rank will not be available, and will extract the internal
 * {@link Producer} from instances of {@link GeneratedColorProducer}
 * and the generator will not be available.
 */
public class Argument<T> extends Variable<T> {
	public Argument(Producer<T> p) { super(null, p); }
	public Argument(String name, Producer<T> p) { super(name, p); }
	public Argument(String name, Method<T> m) { super(name, null, m); }

	@Override
	public void setProducer(Producer<T> producer) {
		w: while (producer instanceof ProducerWithRank || producer instanceof GeneratedColorProducer) {
			if (producer instanceof ProducerWithRank) {
				if (((ProducerWithRank<T>) producer).getProducer() == producer) {
					break w;
				}

				producer = ((ProducerWithRank) producer).getProducer();
			}

			if (producer instanceof GeneratedColorProducer) {
				producer = ((GeneratedColorProducer) producer).getProducer();
			}
		}

		super.setProducer(producer);
	}
}