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
 * Physical constants used throughout the physics simulation framework.
 * <p>
 * This interface provides fundamental physical constants and conversion factors used
 * for calculations involving photons, electrons, atoms, and other physics simulations.
 * All implementing classes have access to these constants as static fields.
 * </p>
 *
 * <h2>Unit System</h2>
 * <p>
 * The physics module uses a consistent unit system optimized for photon simulations:
 * </p>
 * <ul>
 *   <li><b>Distance</b> - Micrometers (micrometers)</li>
 *   <li><b>Time</b> - Microseconds (microseconds)</li>
 *   <li><b>Energy</b> - Electron volts (eV)</li>
 *   <li><b>Wavelength</b> - Micrometers</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Calculate photon wavelength from energy
 * double energyEv = 2.5;  // Green light
 * double wavelengthMicrons = PhysicalConstants.HC / energyEv;
 *
 * // Calculate orbital energy
 * double energy = PhysicalConstants.HCR * Z * Z * n * n;
 * }</pre>
 *
 * @author Michael Murray
 * @see Orbital
 * @see PhotonField
 */
public interface PhysicalConstants {

	/**
	 * Planck's constant (h) in electron volt microseconds.
	 * <p>
	 * Value: 4.13500021 x 10^-9 eV*microsecond
	 * </p>
	 */
	double H = 4.13500021 * Math.pow(10.0, -9.0);

	/**
	 * Speed of light (c) in meters per second.
	 * <p>
	 * This is also equal to micrometers per microsecond due to the unit prefixes
	 * canceling out (both are 10^-6), making it convenient for photon simulations.
	 * </p>
	 * <p>
	 * Value: 299,792,458 m/s = 299,792,458 micrometers/microsecond
	 * </p>
	 */
	double C = 299792458;

	/**
	 * The product of the speed of light and Planck's constant (hc).
	 * <p>
	 * This constant is useful for converting between photon energy and wavelength
	 * using the relation: E = hc / lambda, or lambda = hc / E.
	 * </p>
	 * <p>
	 * Value: {@link #H} * {@link #C}
	 * </p>
	 */
	double HC = H * C;

	/**
	 * Rydberg constant (R) per micrometer.
	 * <p>
	 * The Rydberg constant is used in calculating atomic energy levels and spectral
	 * lines, particularly for hydrogen-like atoms.
	 * </p>
	 * <p>
	 * Value: 10.9737316 per micrometer
	 * </p>
	 */
	double R = 10.9737316;

	/**
	 * The product of hc and the Rydberg constant (hcR).
	 * <p>
	 * This combined constant is used in orbital energy calculations:
	 * E_n = HCR * Z^2 * n^2, where Z is atomic number and n is principal quantum number.
	 * </p>
	 * <p>
	 * Value: {@link #HC} * {@link #R}
	 * </p>
	 */
	double HCR = HC * R;

	/**
	 * Gravitational constant (G) in SI units.
	 * <p>
	 * Value: 6.67 x 10^-11 m^3 / (kg * s^2)
	 * </p>
	 */
	double G = 6.67 * Math.pow(10.0, -11.0);

	/**
	 * Conversion factor from electron volts per microsecond to watts.
	 * <p>
	 * Power (watts) = Energy (eV/microsecond) * evMsecToWatts
	 * </p>
	 * <p>
	 * Value: 1.60217646 x 10^-13
	 * </p>
	 */
	double evMsecToWatts = 1.60217646 * Math.pow(10.0, -13.0);

	/**
	 * Conversion factor from watts to electron volts per microsecond.
	 * <p>
	 * Energy (eV/microsecond) = Power (watts) * wattsToEvMsec
	 * </p>
	 * <p>
	 * Value: 1 / {@link #evMsecToWatts}
	 * </p>
	 */
	double wattsToEvMsec = 1 / evMsecToWatts;

	/**
	 * Wavelength of violet light in micrometers.
	 * <p>
	 * This marks the short-wavelength (high-energy) boundary of visible light.
	 * </p>
	 * <p>
	 * Value: 0.390 micrometers (390 nm)
	 * </p>
	 */
	double violet = 0.390;

	/**
	 * Wavelength of red light in micrometers.
	 * <p>
	 * This marks the long-wavelength (low-energy) boundary of visible light.
	 * </p>
	 * <p>
	 * Value: 0.700 micrometers (700 nm)
	 * </p>
	 */
	double red = 0.700;
}
