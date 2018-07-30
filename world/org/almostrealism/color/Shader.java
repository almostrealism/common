/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.color;

import org.almostrealism.algebra.DiscreteField;
import org.almostrealism.space.LightingContext;
import org.almostrealism.util.Producer;

/**
 * The Shader interface is implemented by classes that provide a method for shading a surface.
 */
public interface Shader<C extends LightingContext> {
	/**
	 * Returns a {@link ColorProducer} object that represents the
	 * shaded color calculated using the values of the specified
	 * {@link ShaderContext} object.
	 */
	Producer<RGB> shade(C parameters, DiscreteField normals);
}
