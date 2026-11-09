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

package io.almostrealism.kernel;

import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.sequence.DefaultIndex;

import java.util.OptionalLong;

/**
 * The lane that is executing, within its work item, in a kernel that assigns several lanes to
 * each of its work items (see {@link io.almostrealism.scope.Scope#getLaneCount()}).
 *
 * <p>The lanes of a work item run the work item's code together, as one SIMD group of the
 * target; this index tells them apart, from zero up to but excluding the lane count, and renders
 * as the lane variable of the target language (see {@link LanguageOperations#laneIndex()}). The
 * {@link KernelIndex} of a lane is the index of its work item, so an expression that does not
 * refer to the lane has the same value in every lane of a work item.</p>
 *
 * @see KernelStructureContext#getLaneCount()
 * @see io.almostrealism.expression.LaneSum
 */
public class LaneIndex extends DefaultIndex {

	/**
	 * Creates the lane index of a kernel that assigns the given number of lanes to each work item.
	 *
	 * @param laneCount the lanes of each work item, at least 2
	 * @throws IllegalArgumentException if {@code laneCount} is less than 2
	 */
	public LaneIndex(int laneCount) {
		super("kernelLane", laneCount);

		if (laneCount < 2) {
			throw new IllegalArgumentException("A lane index needs at least two lanes, not " + laneCount);
		}
	}

	/**
	 * Renders the lane variable of the target language.
	 *
	 * @param lang the language to render for
	 * @return the lane variable
	 */
	@Override
	public String getExpression(LanguageOperations lang) {
		return lang.laneIndex();
	}

	/**
	 * Returns zero, the first lane of a work item.
	 *
	 * @param context the kernel structure context, which does not affect the bound
	 * @return zero
	 */
	@Override
	protected OptionalLong computeLowerBound(KernelStructureContext context) {
		return OptionalLong.of(0);
	}

	/**
	 * Returns a lane index for the given number of lanes, keeping this index's rendering rather
	 * than becoming a plain named index that the target would not declare.
	 *
	 * @param limit the lanes of each work item
	 * @return the lane index of {@code limit} lanes
	 */
	@Override
	public DefaultIndex withLimit(long limit) {
		return new LaneIndex(Math.toIntExact(limit));
	}
}
