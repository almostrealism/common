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

package org.almostrealism.graph.model.test;

import org.almostrealism.CodeFeatures;
import org.almostrealism.model.Model;
import org.junit.Test;

public class TrainModelTest implements CodeFeatures {
	@Test
	public void train() {
		Model model = new Model(shape(100, 100));
		model.addBlock(convolution2d(100, 100, 8, 3));
	}
}
