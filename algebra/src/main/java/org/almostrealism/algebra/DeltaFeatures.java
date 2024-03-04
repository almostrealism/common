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

package org.almostrealism.algebra;

import io.almostrealism.code.Computation;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.relation.Parent;
import io.almostrealism.relation.Producer;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.CollectionProducerComputation;
import org.almostrealism.collect.computations.ReshapeProducer;
import org.almostrealism.hardware.PassThroughProducer;
import org.almostrealism.hardware.mem.MemoryDataDestination;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

public interface DeltaFeatures {
	static Function<Expression, Predicate<Expression>> matcher(Producer<?> target) {
		return index -> exp -> {
			if (!(exp instanceof InstanceReference)) return false;

			InstanceReference ref = (InstanceReference) exp;
			Variable v = ref.getReferent();

			w: while (true) {
				if (match(v.getProducer(), target)) {
					break w;
				}

				if (v.getDelegate() == null && v.getProducer() instanceof CollectionProducerComputation.IsolatedProcess) {
					Computation.console.features(DeltaFeatures.class)
							.warn("Isolated producer cannot be matched");
				}

				v = v.getDelegate();
				if (v == null) {
					return false;
				}
			}

			return true;
		};
	}

	static boolean deepMatch(Supplier<?> p, Supplier<?> target) {
		if (match(p, target)) {
			return true;
		} if (p instanceof Parent) {
			for (Supplier<?> child : ((Parent<Supplier>) p).getChildren()) {
				if (deepMatch(child, target)) {
					return true;
				}
			}
		}

		return false;
	}

	static boolean match(Supplier<?> p, Supplier<?> q) {
		while (p instanceof ReshapeProducer || p instanceof MemoryDataDestination) {
			if (p instanceof ReshapeProducer) {
				p = ((ReshapeProducer<?>) p).getChildren().iterator().next();
			} else {
				p = (Producer<?>) ((MemoryDataDestination) p).getDelegate();
			}
		}

		while (q instanceof ReshapeProducer || q instanceof MemoryDataDestination) {
			if (q instanceof ReshapeProducer) {
				q = ((ReshapeProducer<?>) q).getChildren().iterator().next();
			} else {
				q = (Producer<?>) ((MemoryDataDestination) q).getDelegate();
			}
		}

		if (Objects.equals(p, q)) {
			return true;
		} else if (p instanceof PassThroughProducer && 	q instanceof PassThroughProducer) {
			return ((PassThroughProducer) p).getIndex() == ((PassThroughProducer) q).getIndex();
		}

		return false;
	}
}
