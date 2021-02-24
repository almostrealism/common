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

package org.almostrealism.physics;

/**
 * Constants for unit conversion.
 * 
 * @author  Michael Murray
 */
public interface PhysicalConstants {
	/** Planck's constant in electron volt micro seconds. */
	double H = 4.13500021 * Math.pow(10.0, -9.0);
	
	/** Speed of light in meters per second. (Same as micrometers per microsecond). */
	double C = 299792458;
	
	/** The product of the speed of light and Planck's constant. */
	double HC = H * C;

	/** Rydberg constant per micrometer. */
	double R = 10.9737316;

	/** The product of {@link #HC} and {@link #R}. */
	double HCR = HC * R;

	double G = 6.67 * Math.pow(10.0, -11.0);

	double evMsecToWatts = 1.60217646 * Math.pow(10.0, -13.0);
	double wattsToEvMsec = 1 / evMsecToWatts;

	double violet = 0.390;
	double red = 0.700;
}
