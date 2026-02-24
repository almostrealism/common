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

package io.almostrealism.kernel;

import io.almostrealism.expression.Expression;

import java.util.OptionalLong;

public class KernelIndexChild extends IndexChild {
	private KernelStructureContext context;

	public KernelIndexChild(KernelStructureContext context, Index childIndex) {
		super(new KernelIndex(context), childIndex);
		this.context = context;
	}

	@Override
	public String initName() {
		return "k" + getChildIndex().getName();
	}

	@Deprecated
	public KernelIndexChild renderAlias() {
		setRenderAlias(true);
		return new KernelIndexChild(context, getChildIndex());
	}

	public Class<? extends Number> getAliasType() {
		if (upperBound().orElse(Long.MAX_VALUE) > Integer.MAX_VALUE) {
			return Long.class;
		} else {
			return Integer.class;
		}
	}

	public int kernelIndex(int index) {
		return Math.toIntExact(index / getChildIndex().getLimit().getAsLong());
	}

	@Override
	public OptionalLong upperBound(KernelStructureContext context) {
		if (context == null) context = this.context;
		if (context == null) return OptionalLong.empty();

		OptionalLong max = context.getKernelMaximum();
		if (!max.isPresent()) return OptionalLong.empty();

		OptionalLong limit = getChildIndex().getLimit();
		if (!limit.isPresent()) return OptionalLong.empty();

		return OptionalLong.of(max.getAsLong() * limit.getAsLong() - 1);
	}

	@Override
	public boolean isPossiblyNegative() {
		return getChildren().get(1).isPossiblyNegative();
	}

	@Override
	public boolean compare(Expression o) {
		if (!(o instanceof KernelIndexChild)) return false;
		return ((KernelIndexChild) o).getChildIndex().equals(getChildIndex());
	}
}
