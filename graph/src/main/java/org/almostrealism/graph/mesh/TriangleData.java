/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.graph.mesh;

import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorBank;
import org.almostrealism.hardware.MemWrapper;
import io.almostrealism.relation.Producer;
import io.almostrealism.relation.DynamicProducer;

public class TriangleData extends VectorBank {
	public TriangleData() {
		super(4);
	}

	protected TriangleData(MemWrapper delegate, int delegateOffset) {
		super(4, delegate, delegateOffset);
	}

	public void setABC(Vector abc) { set(0, abc); }
	public void setDEF(Vector def) { set(1, def); }
	public void setJKL(Vector jkl) { set(2, jkl); }
	public void setNormal(Vector normal) { set(3, normal); }

	public Vector getABC() { return get(0); }
	public Vector getDEF() { return get(1); }
	public Vector getJKL() { return get(2); }
	public Vector getNormal() { return get(3); }

	public static Producer<TriangleData> blank() {
		return new DynamicProducer(args -> new TriangleData());
	}
}
