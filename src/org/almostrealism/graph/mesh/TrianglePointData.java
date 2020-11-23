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
import org.almostrealism.relation.Producer;
import org.almostrealism.util.DynamicProducer;

public class TrianglePointData extends VectorBank {
	public TrianglePointData() {
		super(3);
	}

	protected TrianglePointData(MemWrapper delegate, int delegateOffset) {
		super(3, delegate, delegateOffset);
	}

	public void setP1(Vector p1) { set(0, p1); }
	public void setP2(Vector p2) { set(1, p2); }
	public void setP3(Vector p3) { set(2, p3); }

	public Vector getP1() { return get(0); }
	public Vector getP2() { return get(1); }
	public Vector getP3() { return get(2); }

	public static Producer<TrianglePointData> blank() {
		return new DynamicProducer(args -> new TrianglePointData());
	}
}
