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

import org.almostrealism.util.Producer;

public class ZeroVector extends ImmutableVector {
	private static ZeroVector local = new ZeroVector();

	private Scalar zero = new Scalar(0.0);

	public ZeroVector() {
		super(0, 0, 0);
	}

	public static ZeroVector getInstance() { return local; }
}
