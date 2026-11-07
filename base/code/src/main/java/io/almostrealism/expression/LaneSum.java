/*
 * Copyright 2026 Michael Murray
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

package io.almostrealism.expression;

import io.almostrealism.lang.LanguageOperations;

import java.util.List;

/**
 * The sum of a value over every lane of the work item that is executing, in a kernel that
 * assigns several lanes to each of its work items (see
 * {@link io.almostrealism.scope.Scope#getLaneCount()}). Every lane of the work item receives
 * the same total.
 *
 * <p>The sum is performed by the target in hardware, across the SIMD group that executes the
 * work item, and renders as {@link LanguageOperations#laneSum(String)}. Since it depends on the
 * values of other lanes, its value cannot be computed from its operand alone: it is never folded
 * or evaluated, and it is only meaningful where every lane of the work item reaches it.</p>
 *
 * @see io.almostrealism.kernel.LaneIndex
 */
public class LaneSum extends Expression<Double> {

	/**
	 * Creates the sum, across the lanes of a work item, of the value each lane contributes.
	 *
	 * @param value the value each lane contributes
	 */
	public LaneSum(Expression<Double> value) {
		super(Double.class, value);
	}

	/**
	 * Renders the target's sum across lanes of the operand.
	 *
	 * @param lang the language to render for
	 * @return the rendered sum
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.laneSum(getChildren().get(0).getExpression(lang));
	}

	@Override
	public String getWrappedExpression(LanguageOperations lang) { return getExpression(lang); }

	@Override
	protected Expression<Double> recreate(List<Expression<?>> children) {
		return new LaneSum((Expression<Double>) children.get(0));
	}
}
