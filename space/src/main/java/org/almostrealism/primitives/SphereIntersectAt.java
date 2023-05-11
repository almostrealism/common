/*
 * Copyright 2022 Michael Murray
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

package org.almostrealism.primitives;

import org.almostrealism.Ops;
import org.almostrealism.algebra.PairProducerBase;
import org.almostrealism.algebra.ScalarProducerBase;
import org.almostrealism.bool.AcceleratedConjunctionScalar;
import org.almostrealism.bool.GreaterThanScalar;
import org.almostrealism.geometry.Ray;
import org.almostrealism.bool.LessThanScalar;
import io.almostrealism.relation.Evaluable;

import java.util.function.Supplier;

import static org.almostrealism.Ops.*;

@Deprecated
public class SphereIntersectAt extends LessThanScalar {
	private SphereIntersectAt(ScalarProducerBase oDotD,
							  ScalarProducerBase oDotO, ScalarProducerBase dDotD) {
		super(discriminant(oDotD, oDotO, dDotD),
				Ops.ops().scalar(0.0),
				Ops.ops().scalar(-1.0),
				closest(t(oDotD, oDotO, dDotD)), false);
	}

	public SphereIntersectAt(Supplier<Evaluable<? extends Ray>> r) {
		this(Ops.ops().oDotd(r), Ops.ops().oDoto(r), Ops.ops().dDotd(r));
	}

	private static AcceleratedConjunctionScalar closest(PairProducerBase t) {
		return new AcceleratedConjunctionScalar(
				new LessThanScalar(t.x(), t.y(), t.x(), t.y(), false),
				new GreaterThanScalar(t.x(),
						Ops.ops().scalar(0.0),
						t.x(), new GreaterThanScalar(t.y(),
						Ops.ops().scalar(0.0), t.y(),
						Ops.ops().scalar(-1.0), false), false),
				new GreaterThanScalar(Ops.ops().l(t),
						Ops.ops().scalar(0)),
				new GreaterThanScalar(Ops.ops().r(t),
						Ops.ops().scalar(0)));
	}

	private static PairProducerBase t(ScalarProducerBase oDotD,
									  ScalarProducerBase oDotO,
									  ScalarProducerBase dDotD) {
		ScalarProducerBase dS = discriminantSqrt(oDotD, oDotO, dDotD);
		ScalarProducerBase minusODotD = oDotD.multiply(-1.0);
		ScalarProducerBase dDotDInv = dDotD.pow(-1.0);
		return Ops.ops().pair(minusODotD.add(dS).multiply(dDotDInv),
								minusODotD.add(dS.multiply(-1.0)).multiply(dDotDInv));
	}

	private static ScalarProducerBase discriminant(ScalarProducerBase oDotD,
											   ScalarProducerBase oDotO,
											   ScalarProducerBase dDotD) {
		return oDotD.pow(2.0).add(dDotD.multiply(oDotO.add(-1.0)).multiply(-1));
	}

	private static ScalarProducerBase discriminantSqrt(ScalarProducerBase oDotD,
												   ScalarProducerBase oDotO,
												   ScalarProducerBase dDotD) {
		return discriminant(oDotD, oDotO, dDotD).pow(0.5);
	}
}
