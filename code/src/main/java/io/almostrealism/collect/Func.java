package io.almostrealism.collect;

import io.almostrealism.expression.Expression;

public class Func {

	public interface v2e3 {
		Expression apply(CollectionVariable p1, CollectionVariable p2, Expression x, Expression y, Expression z);

		default KernelExpression toKernel() {
			return (i, p) -> apply(i.v(0), i.v(1), p.l(0), p.l(1), p.l(2));
		}
	}

	public interface v3e3 {
		Expression apply(CollectionVariable p1, CollectionVariable p2, CollectionVariable p3, Expression x, Expression y, Expression z);

		default KernelExpression toKernel() {
			return (i, p) -> apply(i.v(0), i.v(1), i.v(2), p.l(0), p.l(1), p.l(2));
		}
	}

	public static KernelExpression kernel3(v2e3 f) {
		return f.toKernel();
	}

	public static KernelExpression kernel3(v3e3 f) {
		return f.toKernel();
	}
}
