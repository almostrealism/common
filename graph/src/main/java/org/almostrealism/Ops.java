/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism;

import java.util.function.Function;

public class Ops implements CodeFeatures {
	private static Ops ops = new Ops();

	private Ops() { }

	public static Ops o() { return ops; }

	public static <T> T op(Function<Ops, T> op) {
		return op.apply(o());
	}
}
