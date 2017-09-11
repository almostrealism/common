/*
 * Copyright 2016 Michael Murray
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

package org.almostrealism.texture;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.ColorProducer;
import org.almostrealism.color.RGB;

/**
 * The Texture interface is implemented by classes that can be used to texture a surface.
 * 
 * @author Mike Murray
 */
public interface Texture extends ColorProducer {
	/**
	 * Returns the color of the texture represented by this Texture object at the specified point as an RGB object
	 * using the arguments stored by this Texture object.
	 */
	RGB getColorAt(Vector point);
	
	/**
	 * Returns the color of the texture represented by this Texture object at the specified point as an RGB object
	 * using the specified arguments.
	 */
	RGB getColorAt(Vector point, Object args[]);
}
