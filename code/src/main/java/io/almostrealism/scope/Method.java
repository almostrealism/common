/*
 * Copyright 2020 Michael Murray
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

import io.almostrealism.expression.Expression;
import io.almostrealism.relation.Nameable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * A {@link Method} is included in a {@link Scope} to indicate that a function should
 * be called in whatever language that the {@link Scope} is being exported to.
 * 
 * T is the type of the return value of the method.
 */
public class Method<T> extends Expression<T> implements Nameable {
	private String member, name;
	private List<Expression<?>> arguments;

	public Method(String name, List<Expression<?>> arguments) {
		this((Class<T>) String.class, name, arguments);
	}

	public Method(Class<T> type, String name, List<Expression<?>> arguments) {
		this(type, null, name, arguments);
	}

	public Method(String member, String name, List<Expression<?>> arguments) {
		this((Class<T>) String.class, member, name, arguments);
	}

	public Method(Class<T> type, String member, String name, List<Expression<?>> arguments) {
		super(type, null, arguments.toArray(new Expression[0]));
		this.member = member;
		this.name = name;
		this.arguments = arguments;
		setExpression(text());
	}

	public Method(String name, Expression<?>... v) {
		this((Class<T>) String.class, name, v);
	}

	public Method(Class<T> type, String name, Expression<?>... v) {
		this(type, null, name, v);
	}

	public Method(String member, String name, Expression<?>... v) {
		this((Class<T>) String.class, member, name, v);
	}

	public Method(Class<T> type, String member, String name, Expression<?>... v) {
		this(type, member, name, Arrays.asList(v));
	}

	protected Supplier<String> text() {
		return () -> {
			if (getMember() == null) {
				return getName() + "(" + toString(getArguments()) + ")";
			} else {
				return getMember() + "." + getName() + "(" + toString(getArguments()) + ")";
			}
		};
	}

	@Override
	public void setName(String n) { this.name = n; }
	@Override
	public String getName() { return name; }

	public void setMember(String m) { this.member = m; }
	public String getMember() { return this.member; }

	public List<Expression<?>> getArguments() { return arguments; }

	protected static String toString(List<Expression<?>> arguments) {
		StringBuffer buf = new StringBuffer();

		for (int i = 0; i < arguments.size(); i++) {
			Expression<?> v = arguments.get(i);
			buf.append(v.getExpression());

			if (i < (arguments.size() - 1)) {
				buf.append(", ");
			}
		}

		return buf.toString();
	}
}
