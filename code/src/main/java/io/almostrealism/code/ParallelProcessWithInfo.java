/*
 * Copyright 2024 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.almostrealism.code;

import io.almostrealism.relation.ParallelProcess;
import io.almostrealism.relation.Process;
import io.almostrealism.relation.ProcessContext;

public interface ParallelProcessWithInfo<P extends Process<?, ?>, T> extends ParallelProcess<P, T>, OperationInfo {

	@Override
	default ParallelProcess<P, T> optimize(ProcessContext ctx) {
		ParallelProcess<P, T> result = ParallelProcess.super.optimize(ctx);

		if (result instanceof OperationInfo) {
			if (((OperationInfo) result).getMetadata() == null) {
				throw new UnsupportedOperationException();
			}

			((OperationInfo) result).getMetadata().setId(getMetadata().getId());
		}

		return result;
	}
}
