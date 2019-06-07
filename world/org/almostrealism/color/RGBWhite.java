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

package org.almostrealism.color;

import org.almostrealism.util.Producer;

public class RGBWhite implements Producer<RGB> {
	private static RGBWhite local = new RGBWhite();

	private RGB black;

	public RGBWhite() { black = new RGB(1.0, 1.0, 1.0); }

	public static RGBWhite getInstance() { return local; }

	@Override
	public RGB evaluate(Object[] args) { return (RGB) black.clone(); }

	@Override
	public void compact() { }
}