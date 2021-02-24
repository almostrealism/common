package io.almostrealism.code;

import io.almostrealism.relation.Pipeline;

public interface Operator<T> extends Pipeline<T>, Computation<T> {
}
