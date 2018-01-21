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

package org.almostrealism.physics;

/**
 * Constants for unit conversion.
 * 
 * @author  Mike Murray
 */
public interface PhysicalConstants {
	/**
	 * Planck's constant in electron volt micro seconds.
	 */
	public static final double H = 4.13500021 * Math.pow(10.0, -9.0);
	
	/**
	 * Speed of light in meters per second. (Same as micrometers per microsecond).
	 */
	public static final double C = 299792458;
	
	/**
	 * The product of the speed of light and Planck's constant.
	 */
	public static final double HC = H * C;
	
	public static final double evMsecToWatts = 1.60217646 * Math.pow(10.0, -13.0);
	public static final double wattsToEvMsec = 1 / evMsecToWatts;
}
