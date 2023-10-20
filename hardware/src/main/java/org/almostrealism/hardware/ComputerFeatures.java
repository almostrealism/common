/*
 * Copyright 2021 Michael Murray
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

package org.almostrealism.hardware;

import io.almostrealism.lang.LanguageOperations;
import io.almostrealism.expression.Expression;
import io.almostrealism.expression.IntegerConstant;
import io.almostrealism.expression.KernelIndex;
import io.almostrealism.expression.StaticReference;
import io.almostrealism.relation.Countable;
import io.almostrealism.scope.ArrayVariable;
import io.almostrealism.code.NameProvider;

public interface ComputerFeatures extends HardwareFeatures, NameProvider {

	@Override
	default String getVariableDimName(ArrayVariable v, int dim) {
		return KernelSupport.getValueDimName(v.getName(), dim);
	}

	@Override
	default String getVariableSizeName(ArrayVariable v) {
		return KernelSupport.getValueSizeName(v.getName());
	}

	@Override
	default Expression<?> getArrayPosition(LanguageOperations lang, ArrayVariable v, Expression pos, int kernelIndex) {
		Expression offset = new IntegerConstant(0);

		if (v.getProducer() instanceof Countable ||
				(v.getProducer() instanceof KernelSupport && ((KernelSupport) v.getProducer()).isKernelEnabled())) {
			KernelIndex idx = new KernelIndex(kernelIndex);
			Expression dim = new StaticReference(Integer.class, KernelSupport.getValueDimName(v.getName(), kernelIndex));

			Expression kernelOffset = idx.multiply(dim);

			return kernelOffset.add(offset).add(pos.toInt());
		} else {
			return offset.add(pos).toInt();
		}
	}
}
