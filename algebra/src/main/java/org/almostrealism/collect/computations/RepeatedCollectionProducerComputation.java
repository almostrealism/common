package org.almostrealism.collect.computations;

import io.almostrealism.code.HybridScope;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.collect.CollectionExpression;
import io.almostrealism.collect.TraversableExpression;
import io.almostrealism.collect.TraversalPolicy;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.c.OpenCLPrintWriter;
import org.almostrealism.collect.PackedCollection;

import java.util.function.BiFunction;
import java.util.function.Supplier;

public class RepeatedCollectionProducerComputation<T extends PackedCollection<?>> extends CollectionProducerComputationBase<T, T> {
	private BiFunction<TraversableExpression[], Expression, Expression> initial;
	private BiFunction<TraversableExpression[], Expression, Expression> condition;
	private BiFunction<TraversableExpression[], Expression, Expression> expression;
	private int memLength;

	@SafeVarargs
	public RepeatedCollectionProducerComputation(TraversalPolicy shape,
												 BiFunction<TraversableExpression[], Expression, Expression> initial,
												 BiFunction<TraversableExpression[], Expression, Expression> condition,
												 BiFunction<TraversableExpression[], Expression, Expression> expression,
												 Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		this(shape, 1, initial, condition, expression, args);
	}

	@SafeVarargs
	public RepeatedCollectionProducerComputation(TraversalPolicy shape, int size,
												 BiFunction<TraversableExpression[], Expression, Expression> initial,
												 BiFunction<TraversableExpression[], Expression, Expression> condition,
												 BiFunction<TraversableExpression[], Expression, Expression> expression,
												 Supplier<Evaluable<? extends PackedCollection<?>>>... args) {
		super(shape, (Supplier[]) args);
		this.initial = initial;
		this.condition = condition;
		this.expression = expression;
		this.memLength = size;
	}

	@Override
	public int getMemLength() { return memLength; }

	@Override
	public Scope<T> getScope() {
		HybridScope<T> scope = new HybridScope<>(this);
		scope.setMetadata(new OperationMetadata(getFunctionName(), "Repeated"));

		String i = getVariablePrefix() + "_i";
		StaticReference<Integer> ref = new StaticReference<>(Integer.class, i);
		String cond = condition.apply(getTraversableArguments(ref), ref).getSimpleExpression();

		Expression index = new KernelIndex(0).divide(e(getShape().getSize())).multiply(e(getShape().getSize()));
		TraversableExpression output = CollectionExpression.traverse(getOutputVariable(),
				size -> index.toInt().divide(e(getMemLength())).multiply(size));

		for (int j = 0; j < getMemLength(); j++) {
			scope.code().accept("\t" + output.getValueRelative(e(0)).getSimpleExpression() + " = " +
					initial.apply(getTraversableArguments(index), ref.add(j)).getSimpleExpression() + ";\n");
		}

		scope.code().accept("for (int " + i + " = 0; " + cond + ";) {\n");

		for (int j = 0; j < getMemLength(); j++) {
			scope.code().accept("\t" + output.getValueRelative(e(0)).getSimpleExpression() + " = " +
					expression.apply(getTraversableArguments(index), ref.add(j)).getSimpleExpression() + ";\n");
		}

		scope.code().accept("\t" + i + " = " + i + " + " + getMemLength() + ";\n");
		scope.code().accept("}\n");
		return scope;
	}
}
