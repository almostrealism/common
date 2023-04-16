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

package org.almostrealism.color.computations;

import io.almostrealism.relation.Evaluable;
import org.almostrealism.color.RGB;

public class RGBBlack extends StaticRGBComputation {
	private static RGBBlack local = new RGBBlack();
	private static Evaluable<RGB> evaluable = local.get();

	public RGBBlack() { super(new RGB()); }

	public static RGBBlack getInstance() { return local; }

	public static Evaluable<RGB> getEvaluable() { return evaluable; }
}
