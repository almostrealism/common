/*
 * Copyright 2017 Michael Murray
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

package org.almostrealism.graph;

import org.almostrealism.graph.mesh.Mesh;

/**
 * Implementors of {@link Triangulatable} are able to produce a
 * {@link Mesh}. The {@link Mesh} should be an approximation of
 * whatever geometry the implementor represents in other contexts.
 *
 * @author  Michael Murray
 */
public interface Triangulatable {
	/**
	 * Produce a {@link Mesh} from this {@link Triangulatable}.
	 */
	Mesh triangulate();
}
