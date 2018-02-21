/*
 * Copyright 2017 Michael Murray
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

import org.almostrealism.texture.Texture;

/**
 * A {@link Light} implementation provides lighting information used for rendering.
 * The intensity and color of the {@link Light} may by specified.
 * 
 * @author  Michael Murray.
 */
public interface Light {
	boolean castShadows = true;
	
	/** Sets the intensity of this {@link Light}. */
	void setIntensity(double intensity);
	
	/** Sets the color of this {@link Light} to the color represented by the specified {@link RGB}. */
	void setColor(RGB color);
	
	/** Returns the intensity of this {@link Light} as a double value. */
	double getIntensity();
	
	/** Returns the color of this Light object as an {@link RGB} object. */
	RGB getColor();
	
	/**
	 * Returns the color of this {@link Light} at the specified point
	 * as an {@link RGB} object.
	 * 
	 * TODO  This is unnecessary. Some methods {@link Light}s may implement {@link Texture}
	 *       if they want to provide this kind of function.
	 */
	ColorProducer getColorAt();
}
