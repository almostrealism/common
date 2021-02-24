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

/**
 * {@link ImageCoordinates} stores a pair of floating point values and
 * allows a more generous equality comparison than using the equality
 * operator on floating point values. An {@link ImageCoordinates} object
 * is equal to another if the difference between both coordinate pairs
 * is less than {@value #comparatorAccuracy}.
 * 
 * @author  Michael Murray
 */
public class ImageCoordinates {
	public static final double comparatorAccuracy = 0.0001;
}
