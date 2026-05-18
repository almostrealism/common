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

/**
 * Abstract base class for C-family language backends (C, OpenCL, Metal, JNI).
 *
 * <p>Provides default implementations of the {@link LanguageOperations} vocabulary
 * that are shared across all C-style target languages: {@code pow}, {@code min},
 * {@code max}, {@code abs}, {@code declaration}, {@code assignment}, and the full
 * argument/parameter rendering pipeline used when generating function signatures
 * and call sites.</p>
 *
 * <p>The argument rendering pipeline uses the {@link ParamType} enum to emit each
 * array argument in up to three passes: the array pointer itself, an integer offset,
 * and an integer size. When {@code enableArrayVariables} is {@code false}, only the
 * pointer pass is emitted (used by backends that pass offsets and sizes through other
 * means).</p>
 *
 * <p>Subclasses must supply {@link #annotationForPhysicalScope},
 * {@link #nameForType}, and {@link #kernelIndex}; all other operations have
 * reasonable defaults here.</p>
 */
public abstract class DefaultLanguageOperations implements LanguageOperations {
	/**
	 * Whether 64-bit integer mode is enabled, controlled by the
	 * {@code AR_HARDWARE_INT_64} environment variable.
	 */
	boolean int64 = SystemUtils.isEnabled("AR_HARDWARE_INT_64").orElse(false);

	/** The floating-point precision setting for this backend. */
	private Precision precision;

	/**
	 * Whether array arguments are emitted with explicit offset and size parameters
	 * in function signatures and call sites.
	 */
	private boolean enableArrayVariables;

	/**
	 * Creates a language operations instance with the specified precision and array-variable mode.
	 *
	 * @param precision            the floating-point precision to use
	 * @param enableArrayVariables {@code true} to emit offset and size parameters alongside
	 *                             each array argument
	 */
	public DefaultLanguageOperations(Precision precision, boolean enableArrayVariables) {
		this.precision = precision;
		this.enableArrayVariables = enableArrayVariables;
	}

	/**
	 * Returns {@code true} if array arguments are emitted with explicit offset and size
	 * parameters in function signatures and call sites.
	 *
	 * @return {@code true} if array variable expansion is enabled
	 */
	public boolean isEnableArrayVariables() {
		return enableArrayVariables;
	}

	/** {@inheritDoc} */
	@Override
	public Precision getPrecision() { return precision; }

	/**
	 * {@inheritDoc}
	 *
	 * <p>Returns {@code true} if the {@code AR_HARDWARE_INT_64} environment variable is set
	 * or if the precision setting implies 64-bit integers.</p>
	 */
	@Override
	public boolean isInt64() {
		return int64 || LanguageOperations.super.isInt64();
	}

	/** {@inheritDoc} Returns {@code pow(a, b)}. */
	@Override
	public String pow(String a, String b) { return "pow(" + a + ", " + b + ")"; }

	/** {@inheritDoc} Returns {@code min(a, b)}. */
	@Override
	public String min(String a, String b) {
		return "min(" + a + ", " + b + ")";
	}

	/** {@inheritDoc} Returns {@code max(a, b)}. */
	@Override
	public String max(String a, String b) {
		return "max(" + a + ", " + b + ")";
	}

	/**
	 * Returns the absolute value expression using the generic {@code abs} function name.
	 *
	 * <p>This intentionally uses {@code abs}, not {@code fabs}. The generic function
	 * names in {@link DefaultLanguageOperations} (abs, min, max, pow, etc.) are
	 * language-neutral. Only {@link org.almostrealism.c.CJNILanguageOperations}
	 * prepends the {@code f} prefix to produce C-specific floating-point variants
	 * (fabs, fmin, fmax, powf). Other backends (Metal, OpenCL, etc.) use the
	 * generic names and must NOT have {@code fabs} injected here or in
	 * {@link org.almostrealism.c.CLanguageOperations}.</p>
	 *
	 * @param value the expression to take the absolute value of
	 * @return the abs expression string
	 */
	@Override
	public String abs(String value) {
		return "abs(" + value + ")";
	}

	/**
	 * Returns the annotation or keyword to prepend when declaring a local array of the given type,
	 * or {@code null} if no annotation is needed.
	 *
	 * <p>The default implementation returns {@code null}. Subclasses may override this to emit
	 * language-specific qualifiers (e.g. {@code __local} in OpenCL).</p>
	 *
	 * @param type   the element type of the array
	 * @param length the array-length expression string
	 * @return the annotation string, or {@code null}
	 */
	public String annotationForLocalArray(Class type, String length) {
		return null;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>When {@code type} is {@code null}, falls back to {@link #assignment}.
	 * When {@code arrayLength} is non-{@code null}, emits a C-style array declaration
	 * optionally prefixed by {@link #annotationForLocalArray}.
	 * Otherwise emits a typed scalar declaration with initialiser.</p>
	 *
	 * @throws UnsupportedOperationException if both {@code arrayLength} and
	 *         {@code expression} are non-{@code null}
	 */
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

	/** {@inheritDoc} Returns {@code destination + " = " + expression}. */
	@Override
	public String assignment(String destination, String expression) {
		return destination + " = " + expression;
	}

	/**
	 * {@inheritDoc}
	 *
	 * <p>Renders the method call as {@code name(args);} using the full argument rendering
	 * pipeline, which separates array-variable pointers, offsets, and sizes from other
	 * explicit arguments.</p>
	 */
	@Override
	public String renderMethod(Method method) {
		StringBuilder buf = new StringBuilder();
		buf.append(method.getName());
		buf.append("(");
		renderParameters(method.getName(), method.getArguments(), buf::append);
		buf.append(");");
		return buf.toString();
	}

	/**
	 * Renders a list of explicit (non-array-variable) expression arguments to the output consumer,
	 * separating them with {@code ", "}.
	 *
	 * @param parameters the expression arguments to render
	 * @param out        the output consumer
	 * @deprecated use {@link #renderParameters(String, List, Consumer)} instead
	 */
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

	/**
	 * Renders the actual argument list for a method call site, separating array-variable
	 * arguments (pointer, offset, size) from other explicit expression arguments.
	 *
	 * @param methodName the name of the method being called (used for context)
	 * @param parameters the full argument expression list
	 * @param out        the output consumer
	 */
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

	/**
	 * Renders the full formal parameter declaration list for a function signature,
	 * emitting array pointers with type annotations and, when array variables are enabled,
	 * additional offset and size parameters.
	 *
	 * @param arguments the array variable arguments to render
	 * @param out       the output consumer
	 * @param access    the accessibility level used to determine physical-scope annotations
	 */
	public void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, Accessibility access) {
		if (enableArrayVariables) {
			if (!arguments.isEmpty()) {
				renderArguments(arguments, out, true, true, access, ParamType.ARRAY);
				out.accept(", ");
				renderArguments(arguments, out, true, false, access, ParamType.OFFSET);
				out.accept(", ");
				renderArguments(arguments, out, true, false, access, ParamType.SIZE);
			}
		} else {
			renderArguments(arguments, out, true, true, access, ParamType.NONE);
		}
	}

	/**
	 * Renders a single pass of the array argument list for a given {@link ParamType}.
	 *
	 * <p>When the variable has its offset disabled, the offset pass emits a literal {@code 0}
	 * and the size pass emits the variable's declared size expression directly.</p>
	 *
	 * @param arguments         the array variable arguments
	 * @param out               the output consumer
	 * @param enableType        {@code true} to emit the type name before each argument
	 * @param enableAnnotation  {@code true} to emit physical-scope annotations before each argument
	 * @param access            the accessibility level for annotation lookup
	 * @param type              which pass to render (pointer, offset, or size)
	 */
	protected void renderArguments(List<ArrayVariable<?>> arguments, Consumer<String> out, boolean enableType,
								   boolean enableAnnotation, Accessibility access, ParamType type) {
		for (int i = 0; i < arguments.size(); i++) {
			ArrayVariable<?> arg = arguments.get(i);

			if (arg.isDisableOffset() && type == ParamType.OFFSET) {
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

	/**
	 * Renders a formal parameter declaration list for non-array scalar arguments, using default
	 * options (type and annotation enabled, no prefix/suffix).
	 *
	 * @param arguments the variable arguments to render
	 * @param out       the output consumer
	 * @param access    the accessibility level for physical-scope annotations
	 */
	public void renderParameters(List<Variable<?, ?>> arguments, Consumer<String> out, Accessibility access) {
		renderParameters(arguments, out, true, true, access, null, "", "");
	}

	/**
	 * Renders a formal parameter declaration list with full control over type emission,
	 * annotations, type override, and name prefix/suffix.
	 *
	 * @param arguments        the variable arguments to render
	 * @param out              the output consumer
	 * @param enableType       {@code true} to emit the type name before each parameter
	 * @param enableAnnotation {@code true} to emit physical-scope annotations
	 * @param access           the accessibility level for annotation lookup
	 * @param replaceType      if non-{@code null}, use this type instead of the variable's declared type
	 * @param prefix           a string to prepend to each parameter name
	 * @param suffix           a string to append to each parameter name
	 */
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

	/**
	 * Returns the prefix string (type and annotation) for an argument, using no type override
	 * and no accessibility.
	 *
	 * @param arg              the variable argument
	 * @param enableType       {@code true} to include the type name
	 * @param enableAnnotation {@code true} to include the physical-scope annotation
	 * @return the argument prefix string
	 */
	protected String argumentPre(Variable arg, boolean enableType, boolean enableAnnotation) {
		return argumentPre(arg, enableType, enableAnnotation, null, null);
	}

	/**
	 * Returns the prefix string (physical-scope annotation and type name) for an argument.
	 *
	 * @param arg              the variable argument
	 * @param enableType       {@code true} to include the type name
	 * @param enableAnnotation {@code true} to include the physical-scope annotation
	 * @param replaceType      if non-{@code null}, use this type instead of the variable's declared type
	 * @param access           the accessibility level for annotation lookup
	 * @return the argument prefix string (may be empty)
	 */
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

	/**
	 * Returns the suffix string appended after each argument at the given index.
	 *
	 * <p>The default implementation returns an empty string. Subclasses may override this to
	 * append language-specific qualifiers or trailing annotations.</p>
	 *
	 * @param index            the zero-based argument position
	 * @param enableAnnotation {@code true} if annotations were enabled for this pass
	 * @param access           the accessibility level
	 * @return the argument suffix string
	 */
	protected String argumentPost(int index, boolean enableAnnotation, Accessibility access) {
		return "";
	}

	/**
	 * Describes which pass of the multi-part array argument rendering pipeline is active.
	 *
	 * <p>Each array variable is potentially emitted in three consecutive passes:
	 * <ul>
	 *   <li>{@link #NONE} / {@link #ARRAY} — the raw pointer or unqualified name</li>
	 *   <li>{@link #OFFSET} — an integer offset variable (name suffixed with {@code "Offset"})</li>
	 *   <li>{@link #SIZE} — an integer size variable (name suffixed with {@code "Size"})</li>
	 * </ul>
	 * </p>
	 */
	protected enum ParamType {
		/** Emits the variable name with no prefix or suffix (used at call sites). */
		NONE,
		/** Emits the variable name with a {@code *} pointer prefix (used in function signatures). */
		ARRAY,
		/** Emits an integer offset parameter for the array. */
		OFFSET,
		/** Emits an integer size parameter for the array. */
		SIZE;

		/**
		 * Returns the Java type associated with this pass, or {@code null} for pointer passes.
		 *
		 * @return {@code Integer.class} for {@link #OFFSET} and {@link #SIZE}; {@code null} otherwise
		 */
		public Class getType() {
			switch (this) {
				case NONE:
				case ARRAY:
					return null;
				case OFFSET:
				case SIZE:
					return Integer.class;
				default: throw new UnsupportedOperationException();
			}
		}

		/**
		 * Returns the string to prepend to the variable name when rendering this pass.
		 *
		 * @return {@code "*"} for {@link #ARRAY}; {@code ""} for all other passes
		 */
		public String getPrefix() {
			switch (this) {
				case NONE: return "";
				case ARRAY: return "*";
				case OFFSET:
				case SIZE:
					return "";
				default: throw new UnsupportedOperationException();
			}
		}

		/**
		 * Returns the string to append to the variable name when rendering this pass.
		 *
		 * @return {@code "Offset"} for {@link #OFFSET}, {@code "Size"} for {@link #SIZE},
		 *         or {@code ""} for pointer passes
		 */
		public String getSuffix() {
			switch (this) {
				case NONE:
				case ARRAY:
					return "";
				case OFFSET: return "Offset";
				case SIZE: return "Size";
				default: throw new UnsupportedOperationException();
			}
		}

		/**
		 * Renders the expression for the given array variable in this pass.
		 *
		 * <p>For delegated variables in the {@link #OFFSET} pass, the delegate's offset
		 * expression is appended. For all other passes, the root delegate's name is used.</p>
		 *
		 * @param lang the language operations instance used to render sub-expressions
		 * @param var  the array variable to render
		 * @return the rendered string for this pass
		 */
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
