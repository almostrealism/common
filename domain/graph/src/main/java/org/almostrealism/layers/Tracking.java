/*
 * Copyright 2025 Michael Murray
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

package org.almostrealism.layers;

/**
 * Interface for components whose forward-pass input recording can be toggled
 * at runtime. Implementations are responsible for propagating the setting to
 * any children (sub-layers, sub-blocks, branch members) that also implement
 * {@code Tracking}; the caller does not walk the tree.
 *
 * <p>This mirrors the {@link Learning} pattern: each composite block decides
 * how to fan the call out to its members.</p>
 *
 * @see Learning
 * @author Michael Murray
 */
public interface Tracking {

	/**
	 * Toggles whether this component (and any tracking-capable children) copies
	 * its forward-pass input into a dedicated buffer for later use during
	 * backpropagation. Disabling tracking eliminates the copy overhead for
	 * inference-only execution.
	 *
	 * <p>Must be called before the computation graph is optimized.</p>
	 *
	 * @param inputTracking whether to enable input tracking
	 */
	void setInputTracking(boolean inputTracking);
}
