package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelSeriesProvider;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.scope.Variable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public class ExpressionAssignment<T> implements Statement<ExpressionAssignment<T>> {
	private final boolean declaration;
	private final Expression<T> destination;
	private final Expression<T> expression;


	public ExpressionAssignment(Expression<T> destination, Expression<T> expression) {
		this(false, destination, expression);
	}

	public ExpressionAssignment(boolean declaration, Expression<T> destination, Expression<T> expression) {
		this.declaration = declaration;
		this.destination = destination;
		this.expression = expression;
	}

	public boolean isDeclaration() { return declaration; }

	public Expression<T> getDestination() { return destination; }

	public Expression<T> getExpression() { return expression; }

	public PhysicalScope getPhysicalScope() {
		if (getDestination() == null) return null;

		return getDestination().getDependencies()
				.stream().map(Variable::getPhysicalScope).filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	public Expression<Integer> getArraySize() {
		if (getDestination() == null) return null;

		return getDestination().getDependencies()
				.stream().map(Variable::getArraySize).filter(Objects::nonNull)
				.findFirst().orElse(null);
	}

	public Supplier getProducer() {
		throw new UnsupportedOperationException();
	}

	public List<Variable<?, ?>> getDependencies() {
		List<Variable<?, ?>> deps = new ArrayList<>();
		if (destination != null) deps.addAll(destination.getDependencies());
		if (expression != null) deps.addAll(expression.getDependencies());
		return deps;
	}

	@Override
	public String getStatement(LanguageOperations lang) {
		if (declaration) {
			return lang.declaration(destination.getType(), destination.getExpression(lang), expression.getExpression(lang));
		} else {
			return lang.assignment(destination.getExpression(lang), expression.getExpression(lang));
		}
	}

	@Override
	public ExpressionAssignment<T> simplify(KernelSeriesProvider provider) {
		return new ExpressionAssignment<>(declaration, destination.simplify(provider), expression.simplify(provider));
	}
}
