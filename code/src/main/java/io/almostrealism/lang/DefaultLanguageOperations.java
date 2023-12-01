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

package io.almostrealism.lang;

import io.almostrealism.code.Accessibility;
import io.almostrealism.code.Precision;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.InstanceReference;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.scope.Method;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class DefaultLanguageOperations implements LanguageOperations {
	protected boolean enableWarnOnExplictParams = true;

	private Precision precision;
	private boolean enableArrayVariables;

	public DefaultLanguageOperations(Precision precision, boolean enableArrayVariables) {
		this.precision = precision;
		this.enableArrayVariables = enableArrayVariables;
	}

	public boolean isEnableArrayVariables() {
		return enableArrayVariables;
	}

	@Override
	public Precision getPrecision() { return precision; }

	@Override
	public String pow(String a, String b) { return "pow(" + a + ", " + b + ")"; }

	@Override
	public String min(String a, String b) {
		return "min(" + a + ", " + b + ")";
	}

	@Override
	public String max(String a, String b) {
		return "max(" + a + ", " + b + ")";
	}

	@Override
	public String renderMethod(Method method) {
		StringBuilder buf = new StringBuilder();
		buf.append(method.getName());
		buf.append("(");
		renderParameters(method.getName(), method.getArguments(), buf::append);
		buf.append(");");
		return buf.toString();
	}

	protected void renderParametersExplicit(List<Expression> parameters, Consumer<String> out) {
		for (int i = 0; i < parameters.size(); i++) {
			Expression arg = parameters.get(i);

			out.accept(arg.getExpression(this));

			if (i < parameters.size() - 1) {
				out.accept(", ");
			}
		}
	}

	protected void renderParameters(String methodName, List<Expression> parameters, Consumer<String> out) {
		Optional<Expression> explicit = parameters.stream().filter(exp -> !(exp instanceof InstanceReference)).findFirst();
		if (explicit.isPresent()) {
			if (enableWarnOnExplictParams) {
				System.out.println("WARN: Explicit parameter (" + explicit.get().getExpression(this) +
									") provided to method; falling back to explicit rendering");
			}
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
				renderArguments(arguments, out, false, false, Accessibility.INTERNAL, null, "", "");
				out.accept(", ");
				renderArguments(arguments, out, false, false, Accessibility.INTERNAL, Integer.class, "", "Offset");
				out.accept(", ");
				renderArguments(arguments, out, false, false, Accessibility.INTERNAL, Integer.class, "", "Size");
				out.accept(", ");
				renderArguments(arguments, out, false, false, Accessibility.INTERNAL, Integer.class, "", "Dim0");
			}
		} else {
			renderArguments(arguments, out, false, false,  Accessibility.INTERNAL,null, "", "");
		}
	}

	public void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (enableArrayVariables) {
			if (!arguments.isEmpty()) {
				renderArguments(arguments, out, true, true, access, null, "*", "");
				out.accept(", ");
				renderArguments(arguments, out, true, false, access, Integer.class, "", "Offset");
				out.accept(", ");
				renderArguments(arguments, out, true, false, access, Integer.class, "", "Size");
				out.accept(", ");
				renderArguments(arguments, out, true, false, access, Integer.class, "", "Dim0");
			}
		} else {
			renderArguments(arguments, out, true, true, access, null, "", "");
		}
	}

	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, boolean enableType,
								   boolean enableAnnotation, Accessibility access, Class replaceType, String prefix, String suffix) {
		for (int i = 0; i < arguments.size(); i++) {
			ArrayVariable<?> arg = arguments.get(i);

			out.accept(argumentPre(arg, enableType, enableAnnotation, replaceType));

			out.accept(prefix);
			out.accept(arguments.get(i).getName());
			out.accept(suffix);
			out.accept(argumentPost(i, enableAnnotation, access));

			if (i < arguments.size() - 1) {
				out.accept(", ");
			}
		}
	}

	protected String argumentPre(ArrayVariable arg, boolean enableType, boolean enableAnnotation) {
		return argumentPre(arg, enableType, enableAnnotation, null);
	}

	protected String argumentPre(ArrayVariable arg, boolean enableType, boolean enableAnnotation, Class replaceType) {
		StringBuilder buf = new StringBuilder();

		if (enableAnnotation && annotationForPhysicalScope(arg.getPhysicalScope()) != null) {
			buf.append(annotationForPhysicalScope(arg.getPhysicalScope()));
			buf.append(" ");
		}

		if (enableType) {
			buf.append(nameForType(replaceType == null ? arg.getType() : replaceType));
			buf.append(" ");
		}

		return buf.toString();
	}

	protected String argumentPost(int index, boolean enableAnnotation, Accessibility access) {
		return "";
	}
}
