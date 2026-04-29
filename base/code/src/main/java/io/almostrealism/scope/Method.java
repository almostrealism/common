/*
 * Copyright 2025 Michael Murray
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

import io.almostrealism.code.Statement;
import io.almostrealism.expression.Expression;
import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.uml.Nameable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link Method} is included in a {@link Scope} to indicate that a function should
 * be called in whatever language that the {@link Scope} is being exported to.
 *
 * <p>A {@code Method} is both an {@link Expression} (so it can appear as a sub-expression
 * in larger expression trees) and a {@link Statement} (so it can be emitted as a standalone
 * function-call statement). The optional {@link #getMember() member} field allows object- or
 * struct-qualified calls of the form {@code member.name(args)}.</p>
 *
 * @param <T> the return type of the method call
 */
public class Method<T> extends Expression<T> implements Statement<Expression<?>>, Nameable {
	/** The object or struct on which this method is called, or {@code null} for free functions. */
	private String member, name;

	/** The positional arguments passed to the method call. */
	private List<Expression<?>> arguments;

	/**
	 * Optional substitutions that replace specific {@link ArrayVariable} names before
	 * rendering the argument list, used to remap kernel array parameters.
	 */
	private Map<String, String> arrayVariableReplacements;

	/**
	 * Creates a {@link Method} with {@link String} return type and no member qualifier.
	 *
	 * @param name      the function name
	 * @param arguments the argument list
	 */
	public Method(String name, List<Expression<?>> arguments) {
		this((Class<T>) String.class, name, arguments);
	}

	/**
	 * Creates a {@link Method} with an explicit return type and no member qualifier.
	 *
	 * @param type      the return type of the method
	 * @param name      the function name
	 * @param arguments the argument list
	 */
	public Method(Class<T> type, String name, List<Expression<?>> arguments) {
		this(type, null, name, arguments);
	}

	/**
	 * Creates a {@link Method} with {@link String} return type and a member qualifier.
	 *
	 * @param member    the object or struct name
	 * @param name      the function name
	 * @param arguments the argument list
	 */
	public Method(String member, String name, List<Expression<?>> arguments) {
		this((Class<T>) String.class, member, name, arguments);
	}

	/**
	 * Creates a {@link Method} with an explicit return type, member qualifier, and argument list.
	 *
	 * @param type      the return type of the method
	 * @param member    the object or struct name, or {@code null} for free functions
	 * @param name      the function name
	 * @param arguments the argument list
	 */
	public Method(Class<T> type, String member, String name, List<Expression<?>> arguments) {
		super(type, arguments.toArray(new Expression[0]));
		this.member = member;
		this.name = name;
		this.arguments = arguments;
	}

	/**
	 * Creates a {@link Method} with {@link String} return type, no member qualifier, and
	 * vararg arguments.
	 *
	 * @param name the function name
	 * @param v    the arguments
	 */
	public Method(String name, Expression<?>... v) {
		this((Class<T>) String.class, name, v);
	}

	/**
	 * Creates a {@link Method} with an explicit return type, no member qualifier, and
	 * vararg arguments.
	 *
	 * @param type the return type of the method
	 * @param name the function name
	 * @param v    the arguments
	 */
	public Method(Class<T> type, String name, Expression<?>... v) {
		this(type, null, name, v);
	}

	/**
	 * Creates a {@link Method} with {@link String} return type, a member qualifier, and
	 * vararg arguments.
	 *
	 * @param member the object or struct name
	 * @param name   the function name
	 * @param v      the arguments
	 */
	public Method(String member, String name, Expression<?>... v) {
		this((Class<T>) String.class, member, name, v);
	}

	/**
	 * Creates a {@link Method} with an explicit return type, member qualifier, and vararg arguments.
	 *
	 * @param type   the return type of the method
	 * @param member the object or struct name, or {@code null} for free functions
	 * @param name   the function name
	 * @param v      the arguments
	 */
	public Method(Class<T> type, String member, String name, Expression<?>... v) {
		this(type, member, name, Arrays.asList(v));
	}

	/**
	 * Registers a substitution that replaces the name of {@code methodArg} with the name
	 * of {@code replacement} when rendering this method's argument list.
	 *
	 * @param methodArg   the array variable whose name should be replaced
	 * @param replacement the array variable providing the replacement name
	 */
	public void setArgument(ArrayVariable<?> methodArg, ArrayVariable<?> replacement) {
		setArgument(methodArg.getName(), replacement.getName());
	}

	/**
	 * Registers a name substitution for an argument position.
	 *
	 * @param methodArg   the original argument name
	 * @param replacement the replacement name to emit in generated code
	 */
	protected void setArgument(String methodArg, String replacement) {
		if (arrayVariableReplacements == null) {
			arrayVariableReplacements = new HashMap<>();
		}

		arrayVariableReplacements.put(methodArg, replacement);
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Renders a call expression of the form {@code name(args)} or
	 * {@code member.name(args)} depending on whether a member qualifier is set.</p>
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		if (getMember() == null) {
			return getName() + "(" + toString(lang, getArguments()) + ")";
		} else {
			return getMember() + "." + getName() + "(" + toString(lang, getArguments()) + ")";
		}
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Delegates to {@link #getExpression(LanguageOperations)} — the statement form
	 * of a method call is the same as its expression form.</p>
	 */
	@Override
	public String getStatement(LanguageOperations lang) { return getExpression(lang); }

	/** {@inheritDoc} */
	@Override
	public void setName(String n) { this.name = n; }

	/** {@inheritDoc} */
	@Override
	public String getName() { return name; }

	/**
	 * Sets the member qualifier for this method call.
	 *
	 * @param m the object or struct name; {@code null} produces a free-function call
	 */
	public void setMember(String m) { this.member = m; }

	/**
	 * Returns the member qualifier for this method call, or {@code null} for free functions.
	 *
	 * @return the member qualifier, or {@code null}
	 */
	public String getMember() { return this.member; }

	/**
	 * Returns the list of arguments passed to this method call.
	 *
	 * @return the argument list
	 */
	public List<Expression<?>> getArguments() { return arguments; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns a new {@link Method} with the same type, member, and name but with
	 * the provided child expressions as arguments. Any array variable replacements
	 * are copied to the new instance.</p>
	 */
	@Override
	public Expression<T> recreate(List<Expression<?>> children) {
		Method m = new Method<>(getType(), getMember(), getName(), children);
		if (arrayVariableReplacements != null) {
			arrayVariableReplacements.forEach(m::setArgument);
		}
		return m;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Two {@link Method} expressions are equal when they have the same name, member
	 * qualifier, and argument list.</p>
	 */
	@Override
	public boolean compare(Expression e) {
		return e instanceof Method &&
				((Method) e).getName().equals(getName()) &&
				((Method) e).getMember().equals(getMember()) &&
				((Method) e).getArguments().equals(getArguments());
	}

	/**
	 * Renders the given argument list to a comma-separated string using the supplied
	 * {@link LanguageOperations}.
	 *
	 * @param lang      the language backend used for expression rendering
	 * @param arguments the arguments to render
	 * @return a comma-separated string of the rendered argument expressions
	 */
	protected static String toString(LanguageOperations lang, List<Expression<?>> arguments) {
		StringBuilder buf = new StringBuilder();

		for (int i = 0; i < arguments.size(); i++) {
			Expression<?> v = arguments.get(i);
			buf.append(v.getExpression(lang));

			if (i < (arguments.size() - 1)) {
				buf.append(", ");
			}
		}

		return buf.toString();
	}
}
