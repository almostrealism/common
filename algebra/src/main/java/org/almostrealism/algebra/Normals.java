/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.algebra;

/** Normals computed using {@link Vector#computeFacetedNormals(Vector[], int[], boolean)}. */
public class Normals {
	public Vector[] normals;
	public int[] normalIndices;

	public Normals(Vector[] normals, int[] normalIndices) {
		this.normals = normals;
		this.normalIndices = normalIndices;
	}
}
