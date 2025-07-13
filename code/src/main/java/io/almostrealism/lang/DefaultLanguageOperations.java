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
import io.almostrealism.scope.Variable;
import org.almostrealism.io.SystemUtils;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public abstract class DefaultLanguageOperations implements LanguageOperations {
	boolean int64 = SystemUtils.isEnabled("AR_HARDWARE_INT_64").orElse(false);

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
	public boolean isInt64() {
		return int64 || LanguageOperations.super.isInt64();
	}

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
	public String declaration(Class type, String destination, String expression, String arrayLength) {
		if (type == null) {
			return assignment(destination, expression);
		} else if (arrayLength != null) {
			if (expression != null) {
				throw new UnsupportedOperationException();
			}

			String annotation = annotationForLocalArray(type, arrayLength);
			if (annotation != null && !annotation.isEmpty()) {
				return annotation + " " + nameForType(type) + " " + destination + "[" + arrayLength + "]";
			} else {
				return nameForType(type) + " " + destination + "[" + arrayLength + "]";
			}
		} else {
			return nameForType(type) + " " + destination + " = " + expression;
		}
	}

	@Override
	public String assignment(String destination, String expression) {
		return destination + " = " + expression;
	}

	public String annotationForLocalArray(Class type, String length) {
		return null;
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

	@Deprecated
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
		List<ArrayVariable<?>> arguments = parameters.stream()
				.filter(exp -> exp instanceof InstanceReference)
				.map(exp -> (InstanceReference) exp)
				.map(InstanceReference::getReferent)
				.filter(v -> v instanceof ArrayVariable<?>)
				.map(v -> (ArrayVariable<?>) v)
				.collect(Collectors.toList());

		if (enableArrayVariables) {
			if (!arguments.isEmpty()) {
				renderArguments(arguments, out, false, false, Accessibility.INTERNAL, ParamType.NONE);
				out.accept(", ");
				renderArguments(arguments, out, false, false, Accessibility.INTERNAL, ParamType.OFFSET);
				out.accept(", ");
				renderArguments(arguments, out, false, false, Accessibility.INTERNAL, ParamType.SIZE);
				out.accept(", ");
				renderArguments(arguments, out, false, false, Accessibility.INTERNAL, ParamType.DIM0);
			}
		} else {
			renderArguments(arguments, out, false, false,  Accessibility.INTERNAL, ParamType.NONE);
		}

		List<Expression> explicit = parameters.stream()
				.filter(exp -> !(exp instanceof InstanceReference) ||
						!(((InstanceReference<?, ?>) exp).getReferent() instanceof ArrayVariable<?>))
				.collect(Collectors.toList());

		if (!explicit.isEmpty()) {
			if (!arguments.isEmpty()) {
				out.accept(", ");
			}

			renderParametersExplicit(explicit, out);
		}
	}

	public void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (enableArrayVariables) {
			if (!arguments.isEmpty()) {
				renderArguments(arguments, out, true, true, access, ParamType.ARRAY);
				out.accept(", ");
				renderArguments(arguments, out, true, false, access, ParamType.OFFSET);
				out.accept(", ");
				renderArguments(arguments, out, true, false, access, ParamType.SIZE);
				out.accept(", ");
				renderArguments(arguments, out, true, false, access, ParamType.DIM0);
			}
		} else {
			renderArguments(arguments, out, true, true, access, ParamType.NONE);
		}
	}

	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, boolean enableType,
								   boolean enableAnnotation, Accessibility access, ParamType type) {
		for (int i = 0; i < arguments.size(); i++) {
			ArrayVariable<?> arg = arguments.get(i);

			if (arg.isDisableOffset() && type == ParamType.OFFSET) {
				out.accept("0");
			} else if (arg.isDisableOffset() && type == ParamType.DIM0) {
				out.accept("0");
			} else if (arg.isDisableOffset() && type == ParamType.SIZE) {
				out.accept(arg.getArraySize().getExpression(this));
			} else {
				out.accept(argumentPre(arg, enableType, enableAnnotation, type.getType(), access));
				out.accept(type.render(this, arg));
				out.accept(argumentPost(i, enableAnnotation, access));
			}

			if (i < arguments.size() - 1) {
				out.accept(", ");
			}
		}
	}

	public void renderParameters(List<Variable<?, ?>> arguments, Consumer<String> out, Accessibility access) {
		renderParameters(arguments, out, true, true, access, null, "", "");
	}

	protected void renderParameters(List<Variable<?, ?>> arguments, Consumer<String> out, boolean enableType,
									boolean enableAnnotation, Accessibility access, Class replaceType,
									String prefix, String suffix) {
		for (int i = 0; i < arguments.size(); i++) {
			Variable<?, ?> arg = arguments.get(i);

			out.accept(argumentPre(arg, enableType, enableAnnotation, replaceType, access));

			out.accept(prefix);
			out.accept(arguments.get(i).getName());
			out.accept(suffix);
			out.accept(argumentPost(i, enableAnnotation, access));

			if (i < arguments.size() - 1) {
				out.accept(", ");
			}
		}
	}

	protected String argumentPre(Variable arg, boolean enableType, boolean enableAnnotation) {
		return argumentPre(arg, enableType, enableAnnotation, null, null);
	}

	protected String argumentPre(Variable arg, boolean enableType, boolean enableAnnotation, Class replaceType, Accessibility access) {
		StringBuilder buf = new StringBuilder();

		if (enableAnnotation && annotationForPhysicalScope(access, arg.getPhysicalScope()) != null) {
			buf.append(annotationForPhysicalScope(access, arg.getPhysicalScope()));
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

	protected enum ParamType {
		NONE, ARRAY, OFFSET, SIZE, DIM0;

		public Class getType() {
			switch (this) {
				case NONE:
				case ARRAY:
					return null;
				case OFFSET:
				case SIZE:
				case DIM0:
					return Integer.class;
				default: throw new UnsupportedOperationException();
			}
		}

		public String getPrefix() {
			switch (this) {
				case NONE: return "";
				case ARRAY: return "*";
				case OFFSET:
				case SIZE:
				case DIM0:
					return "";
				default: throw new UnsupportedOperationException();
			}
		}

		public String getSuffix() {
			switch (this) {
				case NONE:
				case ARRAY:
					return "";
				case OFFSET: return "Offset";
				case SIZE: return "Size";
				case DIM0: return "Dim0";
				default: throw new UnsupportedOperationException();
			}
		}

		public String render(DefaultLanguageOperations lang, ArrayVariable<?> var) {
			if (var.getDelegate() == null) {
				return getPrefix() + var.getName() + getSuffix();
			} else if (this == OFFSET) {
				return render(lang, var.getDelegate()) + " + " +
						var.getDelegateOffset().getSimpleExpression(lang);
			} else {
				return getPrefix() + var.getRootDelegate().getName() + getSuffix();
			}
		}
	}
}
