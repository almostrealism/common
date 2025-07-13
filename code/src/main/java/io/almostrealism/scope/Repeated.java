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

import io.almostrealism.lang.CodePrintWriter;
import io.almostrealism.code.ExpressionAssignment;
import io.almostrealism.profile.OperationMetadata;
import io.almostrealism.code.Statement;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.kernel.KernelStructureContext;

import java.util.List;

public class Repeated<T> extends Scope<T> {
	private Variable<Integer, ?> index;
	private Expression<Integer> interval;
	private Expression<Boolean> condition;

	public Repeated() { }

	public Repeated(String name) {
		this();
		setName(name);
	}

	public Repeated(String name, OperationMetadata metadata) {
		this(name);
		setMetadata(new OperationMetadata(metadata));

		if (metadata == null)
			throw new IllegalArgumentException();
	}

	public Repeated(Variable<Integer, ?> idx, Expression<Boolean> condition) {
		this(idx, condition, new IntegerConstant(1));
	}

	public Repeated(Variable<Integer, ?> idx, Expression<Boolean> condition, Expression<Integer> interval) {
		this();
		setIndex(idx);
		setInterval(interval);
		setCondition(condition);
	}

	public Variable<Integer, ?> getIndex() { return index; }
	public void setIndex(Variable<Integer, ?> index) { this.index = index; }

	public Expression<Integer> getInterval() { return interval; }
	public void setInterval(Expression<Integer> interval) { this.interval = interval; }

	public Expression<Boolean> getCondition() { return condition; }
	public void setCondition(Expression<Boolean> condition) { this.condition = condition; }

	@Override
	public void write(CodePrintWriter w) {
		w.renderMetadata(getMetadata());
		for (Method m : getMethods()) { w.println(m); }
		for (Statement s : getStatements()) { w.println(s); }
		for (ExpressionAssignment<?> v : getVariables()) { w.println(v); }

		w.println("for (int " + getIndex().getName() + " = 0; " + getCondition().getExpression(w.getLanguage()) + ";) {");
		for (Scope v : getChildren()) { v.write(w); }
		w.println(getIndex().getName() + " = " + getIndex().getName() + " + " + interval.getExpression(w.getLanguage()) + ";");
		w.println("}");

		for (Metric m : getMetrics()) { w.println(m); }
		w.flush();
	}

	@Override
	public Scope<T> simplify(KernelStructureContext context, int depth) {
		Repeated<T> scope = (Repeated<T>) super.simplify(context, depth);
		scope.setInterval(getInterval().simplify(context, depth + 1));
		scope.setCondition(getCondition().simplify(context, depth + 1));
		return scope;
	}

	@Override
	public Repeated<T> generate(List<Scope<T>> children) {
		Repeated<T> scope = getMetadata() == null ? new Repeated<>(getName()) : new Repeated<>(getName(), getMetadata());
		scope.setIndex(getIndex());
		scope.getChildren().addAll(children);
		return scope;
	}
}
