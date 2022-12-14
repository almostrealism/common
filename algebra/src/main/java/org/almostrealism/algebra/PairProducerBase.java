/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.algebra;

import io.almostrealism.expression.MultiExpression;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.code.ProducerComputation;
import org.almostrealism.algebra.computations.ScalarExpressionComputation;
import org.almostrealism.hardware.KernelizedProducer;

import java.util.function.Supplier;

public interface PairProducerBase extends ProducerComputation<Pair<?>>, KernelizedProducer<Pair<?>>,
        MultiExpression<Double>, PairFeatures {

    default ScalarExpressionComputation l() { return l(this); }
    default ScalarExpressionComputation r() { return r(this); }

    default ScalarExpressionComputation x() { return l(this); }
    default ScalarExpressionComputation y() { return r(this); }

    default PairProducer multiplyComplex(Supplier<Evaluable<? extends Pair<?>>> p) {
        return multiplyComplex(this, p);
    }
}
