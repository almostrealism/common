/*
 * Copyright 2023 Michael Murray
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

package io.almostrealism.scope;

import io.almostrealism.code.CodePrintWriter;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.code.OperationMetadata;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.Expression;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class Cases<T> extends Scope<T> {
	private List<Expression<Boolean>> conditions;

	public Cases() {
		conditions = new ArrayList<>();
	}

	public Cases(String name) {
		this();
		setName(name);
	}

	public Cases(String name, OperationMetadata metadata) {
		this(name);
		setMetadata(new OperationMetadata(metadata));

		if (metadata == null)
			throw new IllegalArgumentException();
	}

	public List<Expression<Boolean>> getConditions() { return conditions; }

	@Override
	public Scope<T> addCase(Expression<Boolean> condition, Scope<T> scope) {
		if (condition != null) conditions.add(condition);
		getChildren().add(scope);
		return scope;
	}

	protected <A> List<A> arguments(Function<Argument<?>, A> mapper) {
		List<Argument<?>> args = new ArrayList<>();
		args.addAll(extractArgumentDependencies(getConditions().stream()
				.flatMap(e -> e.getDependencies().stream()).collect(Collectors.toList())));
		args.addAll(super.arguments(Function.identity()));
		return Scope.removeDuplicateArguments(args).stream().map(mapper).collect(Collectors.toList());
	}

	@Override
	public void write(CodePrintWriter w) {
		w.renderMetadata(getMetadata());
		for (Method m : getMethods()) { w.println(m); }
		for (Statement s : getStatements()) { w.println(s); }
		for (ExpressionAssignment<?> v : getVariables()) { w.println(v); }

		for (int i = 0; i < getChildren().size(); i++) {
			if (i < getConditions().size()) {
				String pre = i > 0 ? "else if (" : "if (";
				w.println(pre + getConditions().get(i).getExpression(w.getLanguage()) + ") {");
			} else {
				w.println(" else {");
			}

			getChildren().get(i).write(w);
			w.println("}");
		}

		for (Metric m : getMetrics()) { w.println(m); }
		w.flush();
	}

	@Override
	public Scope<T> simplify(KernelStructureContext context) {
		Cases<T> scope = (Cases<T>) super.simplify(context);
		scope.getConditions().addAll(getConditions().stream().map(c -> c.simplify(context)).collect(Collectors.toList()));
		return scope;
	}

	@Override
	public Cases<T> generate(List<Scope<T>> children) {
		Cases<T> scope = getMetadata() == null ? new Cases<>(getName()) : new Cases<>(getName(), getMetadata());
		scope.getChildren().addAll(children);
		return scope;
	}
}
