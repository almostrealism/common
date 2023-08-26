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
import io.almostrealism.relation.Process;
import io.almostrealism.scope.Scope;
import io.almostrealism.scope.Variable;
import org.almostrealism.collect.PackedCollection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

		Set<Variable<?, ?>> dependencies = new HashSet<>();

		for (int j = 0; j < getMemLength(); j++) {
			Expression<?> out = output.getValueRelative(e(j));
			Expression<?> val = initial.apply(getTraversableArguments(index), ref.add(j));
			scope.code().accept("\t" + out.getSimpleExpression() + " = " + val.getSimpleExpression() + ";\n");
			dependencies.addAll(out.getDependencies());
			dependencies.addAll(val.getDependencies());
		}

		scope.code().accept("for (int " + i + " = 0; " + cond + ";) {\n");

		for (int j = 0; j < getMemLength(); j++) {
			Expression<?> out = output.getValueRelative(e(j));
			Expression<?> val = expression.apply(getTraversableArguments(index), ref.add(j));
			scope.code().accept("\t" + out.getSimpleExpression() + " = " + val.getSimpleExpression() + ";\n");
			dependencies.addAll(out.getDependencies());
			dependencies.addAll(val.getDependencies());
		}

		scope.code().accept("\t" + i + " = " + i + " + " + getMemLength() + ";\n");
		scope.code().accept("}\n");

		scope.setDependencies(dependencies);
		return scope;
	}

	@Override
	public RepeatedCollectionProducerComputation<T> generate(List<Process<?, ?>> children) {
		return new RepeatedCollectionProducerComputation<>(
				getShape(), getMemLength(),
				initial, condition, expression,
				children.stream().skip(1).toArray(Supplier[]::new));
	}
}
