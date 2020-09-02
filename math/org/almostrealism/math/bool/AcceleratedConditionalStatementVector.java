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

package org.almostrealism.math.bool;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorProducer;
import org.almostrealism.util.CollectionUtils;
import org.almostrealism.util.Producer;

public interface AcceleratedConditionalStatementVector extends AcceleratedConditionalStatement<Vector>, VectorProducer {
	default AcceleratedConjunctionVector and(AcceleratedConditionalStatement<Vector> operand, Producer<Vector> trueValue, Producer<Vector> falseValue) {
		return and(trueValue, falseValue, operand);
	}

	default AcceleratedConjunctionVector and(Producer<Vector> trueValue, Producer<Vector> falseValue, AcceleratedConditionalStatement<Vector>... operands) {
		return new AcceleratedConjunctionVector(trueValue, falseValue,
				CollectionUtils.include(new AcceleratedConditionalStatement[0], this, operands));
	}
}