/*
 * Copyright 2019 Michael Murray
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
import org.almostrealism.util.Producer;

public class RGBBlack implements Producer<RGB> {
	private static RGBBlack local = new RGBBlack();

	private RGB black;

	public RGBBlack() { black = new RGB(); }

	public static RGBBlack getInstance() { return local; }

	@Override
	public RGB evaluate(Object[] args) { return (RGB) black.clone(); }

	@Override
	public void compact() { }
}
