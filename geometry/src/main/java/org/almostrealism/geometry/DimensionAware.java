/*
 * Copyright 2020 Michael Murray
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

package org.almostrealism.geometry;

public interface DimensionAware {
	void setDimensions(int width, int height, int ssw, int ssh);

	static int getPosition(double x, double y, int width, int height, int ssw, int ssh) {
		if (width < 0) throw new IllegalArgumentException("Width cannot be less than zero");
		if (height < 0) throw new IllegalArgumentException("Height cannot be less than zero");
		if (ssw < 0) throw new IllegalArgumentException("Supersample width cannot be less than zero");
		if (ssh < 0) throw new IllegalArgumentException("Supersample height cannot be less than zero");
		return (int) (y * width * ssw * ssh) + (int) (x * ssh);
	}
}
