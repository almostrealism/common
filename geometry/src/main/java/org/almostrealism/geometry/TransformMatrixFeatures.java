/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.geometry;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.Product;
import io.almostrealism.expression.Sum;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Producer;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.CollectionFeatures;
import org.almostrealism.collect.CollectionVariable;
import org.almostrealism.collect.computations.DynamicExpressionComputation;
import org.almostrealism.geometry.computations.StaticTransformMatrixComputation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public interface TransformMatrixFeatures extends CollectionFeatures {
	default TransformMatrixProducer v(TransformMatrix v) { return value(v); }

	default TransformMatrixProducer value(TransformMatrix v) {
		return new StaticTransformMatrixComputation(v);
	}

	default DynamicExpressionComputation<Vector> transformAsLocation(TransformMatrix matrix, Supplier<Evaluable<? extends Vector>> vector) {
		return transformAsLocation(v(matrix), vector);
	}

	default DynamicExpressionComputation<Vector> transformAsLocation(Producer<TransformMatrix> matrix, Supplier<Evaluable<? extends Vector>> vector) {
		return transform(matrix, vector, true);
	}

	default DynamicExpressionComputation<Vector> transformAsOffset(TransformMatrix matrix, Supplier<Evaluable<? extends Vector>> vector) {
		return transformAsOffset(v(matrix), vector);
	}

	default DynamicExpressionComputation<Vector> transformAsOffset(Producer<TransformMatrix> matrix, Supplier<Evaluable<? extends Vector>> vector) {
		return transform(matrix, vector, false);
	}

	default DynamicExpressionComputation<Vector> transform(Producer<TransformMatrix> matrix, Supplier<Evaluable<? extends Vector>> vector, boolean includeTranslation) {
		DynamicExpressionComputation c = new DynamicExpressionComputation<>(shape(3), (BiFunction<CollectionVariable[], Expression, Expression>) (args, index) -> {
			Function<Integer, Expression<Double>> t = (i) ->  args[2].getValueAt(index.multiply(4).add(e(i)));
			Function<Integer, Expression<Double>> v = (i) ->  args[1].getValueAt(e(i));
			Function<Integer, Expression<Double>> p = (i) -> new Product(t.apply(i), v.apply(i));

			List<Expression<Double>> sum = new ArrayList<>();
			sum.add(p.apply(0));
			sum.add(p.apply(1));
			sum.add(p.apply(2));

			if (includeTranslation) {
				sum.add(t.apply(3));
			}

			return new Sum(sum.toArray(Expression[]::new));
		}, (Supplier) vector, (Supplier) matrix);
		c.setPostprocessor(Vector.postprocessor());
		return c;
	}

	static TransformMatrixFeatures getInstance() {
		return new TransformMatrixFeatures() {};
	}
}
