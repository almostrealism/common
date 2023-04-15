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

package io.almostrealism.code;

import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;
import io.almostrealism.scope.Metric;
import io.almostrealism.scope.Scope;
import org.almostrealism.io.PrintWriter;

import java.util.List;
import java.util.Optional;
import java.util.Stack;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class CodePrintWriterAdapter implements CodePrintWriter {
	protected boolean enableWarnOnExplictParams = true;

	protected PrintWriter p;

	private String nameSuffix = "";
	private String scopePrefixExt, scopePrefixInt;
	private String scopeSuffix = "{";
	private String scopeClose = "}";

	private boolean enableArrayVariables;

	private final Stack<String> scopeName;

	public CodePrintWriterAdapter(PrintWriter p) {
		this.p = p;
		this.scopeName = new Stack<>();
	}

	protected void setNameSuffix(String suffix) { this.nameSuffix = suffix; }

	protected void setScopePrefix(String prefix) {
		setExternalScopePrefix(prefix);
		setInternalScopePrefix(prefix);
	}

	protected void setExternalScopePrefix(String prefix) { this.scopePrefixExt = prefix; }
	protected void setInternalScopePrefix(String prefix) { this.scopePrefixInt = prefix; }

	protected void setScopeSuffix(String suffix) { this.scopeSuffix = suffix; }
	protected void setScopeClose(String close) { this.scopeClose = close; }

	protected void setEnableArrayVariables(boolean enableArrayVariables) {
		this.enableArrayVariables = enableArrayVariables;
	}

	protected String typePrefix(Class type) {
		if (type == null) {
			return "";
		} else {
			return nameForType(type) + " ";
		}
	}

	protected String getCurrentScopeName() {
		return scopeName.peek();
	}

	protected abstract String nameForType(Class<?> type);

	protected String annotationForPhysicalScope(PhysicalScope scope) {
		return null;
	}

	@Override
	public void println(Metric m) {
		m.getVariables().keySet().forEach(this::comment);
	}

	@Override
	public void comment(String text) {
		println("// " + text);
	}

	@Override
	@Deprecated
	public void println(String s) { p.println(s); }

	@Override
	public void println(Scope s) {
		beginScope(s.getName(), null, s.getArgumentVariables(), Accessibility.EXTERNAL);
		s.write(this);
		endScope();
	}

	@Override
	public void flush() { }

	@Override
	public void beginScope(String name, OperationMetadata metadata, List<ArrayVariable<?>> arguments, Accessibility access) {
		scopeName.push(name);

		StringBuilder buf = new StringBuilder();

		String scopePrefix = access == Accessibility.EXTERNAL ? scopePrefixExt : scopePrefixInt;

		if (name != null) {
			if (scopePrefix != null) { buf.append(scopePrefix); buf.append(" "); }

			buf.append(name);

			if (nameSuffix != null) {
				buf.append(nameSuffix);
			}

			buf.append("(");
			renderArguments(arguments, buf::append, access);
			buf.append(")");
		}

		if (scopeSuffix != null) { buf.append(" "); buf.append(scopeSuffix); }

		p.println(buf.toString());
	}

	public String renderMethod(Method method) {
		StringBuilder buf = new StringBuilder();
		buf.append(method.getName());
		buf.append("(");
		renderParameters(method.getArguments(), buf::append);
		buf.append(");");
		return buf.toString();
	}

	protected void renderParametersExplicit(List<Expression> parameters, Consumer<String> out) {
		for (int i = 0; i < parameters.size(); i++) {
			Expression arg = parameters.get(i);

			out.accept(arg.getExpression());

			if (i < parameters.size() - 1) {
				out.accept(", ");
			}
		}
	}

	protected void renderParameters(List<Expression> parameters, Consumer<String> out) {
		Optional<Expression> explicit = parameters.stream().filter(exp -> !(exp instanceof InstanceReference)).findFirst();
		if (explicit.isPresent()) {
			if (enableWarnOnExplictParams) System.out.println("WARN: Explicit parameter (" + explicit.get().getExpression() + ") provided to method; falling back to explicit rendering");
			renderParametersExplicit(parameters, out);
			return;
		}

		List<ArrayVariable<?>> arguments = parameters.stream()
				.map(exp -> (InstanceReference) exp)
				.map(InstanceReference::getReferent)
				.map(v -> (ArrayVariable<?>) v)
				.collect(Collectors.toList());

		if (enableArrayVariables) {
			if (!arguments.isEmpty()) {
				renderArguments(arguments, out, false, false, null, "", "");
				out.accept(", ");
				renderArguments(arguments, out, false, false, Integer.class, "", "Offset");
				out.accept(", ");
				renderArguments(arguments, out, false, false, Integer.class, "", "Size");
				out.accept(", ");
				renderArguments(arguments, out, false, false, Integer.class, "", "Dim0");
			}
		} else {
			renderArguments(arguments, out, false, false, null, "", "");
		}
	}

	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (enableArrayVariables) {
			if (!arguments.isEmpty()) {
				renderArguments(arguments, out, true, true, null, "*", "");
				out.accept(", ");
				renderArguments(arguments, out, true, false, Integer.class, "", "Offset");
				out.accept(", ");
				renderArguments(arguments, out, true, false, Integer.class, "", "Size");
				out.accept(", ");
				renderArguments(arguments, out, true, false, Integer.class, "", "Dim0");
			}
		} else {
			renderArguments(arguments, out, true, true, null, "", "");
		}
	}

	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, boolean enableType,
								   boolean enableAnnotation, Class replaceType, String prefix, String suffix) {
		for (int i = 0; i < arguments.size(); i++) {
			ArrayVariable<?> arg = arguments.get(i);

			if (enableAnnotation && annotationForPhysicalScope(arg.getPhysicalScope()) != null) {
				out.accept(annotationForPhysicalScope(arg.getPhysicalScope()));
				out.accept(" ");
			}

			if (enableType) {
				out.accept(nameForType(replaceType == null ? arguments.get(i).getType() : replaceType));
				out.accept(" ");
			}

			out.accept(prefix);
			out.accept(arguments.get(i).getName());
			out.accept(suffix);

			if (i < arguments.size() - 1) {
				out.accept(", ");
			}
		}
	}

	@Override
	public void endScope() {
		scopeName.pop();
		p.println();
		if (scopeClose != null) p.println(scopeClose);
	}
}
