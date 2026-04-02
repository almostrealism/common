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

/**
 * Abstract syntax tree (AST) nodes for the expression language used in generated native code.
 *
 * <p>Each class in this package represents a typed node in an expression tree. Trees are
 * built by composing these nodes and then rendered to target-language strings via
 * {@link io.almostrealism.expression.Expression#getExpression(io.almostrealism.lang.LanguageOperations)}.
 * The optimizer simplifies trees before rendering via
 * {@link io.almostrealism.expression.Expression#simplify(io.almostrealism.kernel.KernelStructureContext, int)}.</p>
 *
 * <p>Included node types:</p>
 * <ul>
 *   <li><b>Constants</b>: {@code IntegerConstant}, {@code DoubleConstant}, {@code LongConstant},
 *       {@code BooleanConstant}, {@code Epsilon}, {@code MinimumValue}</li>
 *   <li><b>Arithmetic</b>: {@code Sum}, {@code Difference}, {@code Product}, {@code Quotient},
 *       {@code Mod}, {@code Exponent}, {@code Minus}</li>
 *   <li><b>Trigonometry</b>: {@code Sine}, {@code Cosine}, {@code Tangent}</li>
 *   <li><b>Math functions</b>: {@code Exp}, {@code Logarithm}, {@code Floor}, {@code Rectify},
 *       {@code Max}, {@code Min}</li>
 *   <li><b>Logic</b>: {@code Equals}, {@code Greater}, {@code Less}, {@code Conditional},
 *       {@code Conjunction}, {@code Negation}, {@code Mask}</li>
 *   <li><b>References</b>: {@code InstanceReference}, {@code StaticReference}, {@code SizeValue}</li>
 * </ul>
 *
 * @see io.almostrealism.expression.Expression
 */
package io.almostrealism.expression;
