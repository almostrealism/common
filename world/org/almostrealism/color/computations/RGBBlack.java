/*
 * Copyright 2020 Michael Murray
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

import org.almostrealism.color.RGB;
import org.almostrealism.util.AcceleratedStaticRGBComputation;

public class RGBBlack extends AcceleratedStaticRGBComputation {
	private static RGBBlack local = new RGBBlack();

	public RGBBlack() { super(new RGB(), RGB.blank()); }

	public static RGBBlack getInstance() { return local; }

	public static RGBProducer getProducer() { return new DefaultRGBProducer(getInstance()); }
}
