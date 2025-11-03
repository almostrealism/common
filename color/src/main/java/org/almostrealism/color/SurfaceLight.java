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

package org.almostrealism.color;

/**
 * A {@link SurfaceLight} implementation represents a light that can be described
 * by some (possibly infinite) set of other {@link Light}s. A correct implementation
 * should be able to return any number of samples ({@link Light}s) that are well
 * distributed and representative of the surface. Factoring in number of samples
 * to create an intensity value for each sample is handled externally.
 * 
 * @author Michael Murray
 */
public interface SurfaceLight extends Light {
	/**
	 * @param total  Total number of samples to return.
	 * @return  An array containing samples for this SurfaceLight instance.
	 */
	Light[] getSamples(int total);
	
	/**
	 * @return  An array containing samples for this SurfaceLight instance.
	 */
	Light[] getSamples();
}
