/*
 * Copyright 2024 Michael Murray
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

/**
 * Configuration interface for controlling expression caching behavior during scope optimization.
 * <p>{@link CachingSettings} allows implementations to define which expressions should be
 * candidates for caching (common subexpression elimination) and provides a description
 * of the caching strategy for debugging purposes.</p>
 *
 * <p>Expression caching is an optimization technique where frequently-used subexpressions
 * are computed once and stored in temporary variables, reducing redundant computation
 * during code execution.</p>
 *
 * <h2>Implementation Guidelines</h2>
 * <p>Implementations should consider:</p>
 * <ul>
 *   <li>Expression complexity (simple expressions may not benefit from caching)</li>
 *   <li>Expression frequency (how often the expression appears)</li>
 *   <li>Expression purity (side-effect-free expressions are safe to cache)</li>
 *   <li>Memory vs. computation trade-offs</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * CachingSettings settings = new CachingSettings() {
 *     public boolean isExpressionCacheTarget(Expression<?> e) {
 *         return e.getComplexity() > 3 && e.isPure();
 *     }
 *
 *     public String shortDesc() {
 *         return "complexity>3";
 *     }
 * };
 * }</pre>
 *
 * @see Expression
 * @see ExpressionCache
 * @see Scope#processReplacements
 */
public interface CachingSettings {

	/**
	 * Determines whether the given expression should be considered for caching.
	 * <p>Expressions that return true from this method may be extracted into
	 * temporary variables to avoid redundant computation.</p>
	 *
	 * @param e the expression to evaluate for caching eligibility
	 * @return true if the expression should be cached, false otherwise
	 */
	boolean isExpressionCacheTarget(Expression<?> e);

	/**
	 * Returns a short description of this caching strategy.
	 * <p>Used for debugging and logging to identify which caching
	 * configuration is in effect.</p>
	 *
	 * @return a brief human-readable description of the caching settings
	 */
	String shortDesc();
}
