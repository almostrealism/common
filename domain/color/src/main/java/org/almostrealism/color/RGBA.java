/*
 * Copyright 2018 Michael Murray
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

package org.almostrealism.color;
import org.almostrealism.collect.PackedCollection;

/**
 * Represents a color in the RGBA (Red, Green, Blue, Alpha) color space.
 *
 * <p>{@link RGBA} extends {@link RGB} to include an alpha (transparency) channel.
 * The alpha value ranges from 0.0 (fully transparent) to 1.0 (fully opaque).</p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Fully opaque red
 * RGBA solidRed = new RGBA(1.0, 0.0, 0.0, 1.0);
 *
 * // Semi-transparent blue
 * RGBA transparentBlue = new RGBA(0.0, 0.0, 1.0, 0.5);
 *
 * // From array (alpha defaults to 1.0 if not provided)
 * RGBA fromArray = new RGBA(1.0, 0.5, 0.0);
 * }</pre>
 *
 * <h2>Alpha Channel Storage</h2>
 * <p>Note that the alpha channel is stored separately from the RGB channels
 * (which use {@link PackedCollection} storage). This means the alpha value
 * is not hardware-accelerated in the same way as RGB operations.</p>
 *
 * @see RGB
 * @see RGBFeatures
 * @author Michael Murray
 */
public class RGBA extends RGB {
	/** The alpha (transparency) channel value. */
	private double a;

	/**
	 * Constructs a fully transparent black RGBA color.
	 *
	 * <p>All channels including alpha are initialized to 0.0.</p>
	 */
	public RGBA() {
		super(0.0, 0.0,0.0);
		this.a = 0.0;
	}

	/**
	 * Constructs an RGBA color with the specified channel values.
	 *
	 * @param r the red channel value (0.0 to 1.0)
	 * @param g the green channel value (0.0 to 1.0)
	 * @param b the blue channel value (0.0 to 1.0)
	 * @param a the alpha channel value (0.0 = transparent, 1.0 = opaque)
	 */
	public RGBA(double r, double g, double b, double a) {
		super(r, g, b);
		this.a = a;
	}

	/**
	 * Constructs an RGBA color from a variable number of channel values.
	 *
	 * <p>If fewer than 4 values are provided, the alpha channel defaults to 1.0 (fully opaque).</p>
	 *
	 * @param values array containing r, g, b, and optionally a channel values
	 * @throws ArrayIndexOutOfBoundsException if fewer than 3 values are provided
	 */
	public RGBA(double... values) {
		this(values[0], values[1], values[2], values.length > 3 ? values[3] : 1.0);
	}

	/**
	 * Returns the red channel as a float.
	 *
	 * @return the red channel value cast to float
	 */
	public float r() { return (float) getRed(); }

	/**
	 * Returns the green channel as a float.
	 *
	 * @return the green channel value cast to float
	 */
	public float g() { return (float) getGreen(); }

	/**
	 * Returns the blue channel as a float.
	 *
	 * @return the blue channel value cast to float
	 */
	public float b() { return (float) getBlue(); }

	/**
	 * Returns the alpha channel as a float.
	 *
	 * @return the alpha channel value cast to float
	 */
	public float a() { return (float) getAlpha(); }

	/**
	 * Sets the alpha (transparency) channel value.
	 *
	 * @param a the new alpha value (0.0 = transparent, 1.0 = opaque)
	 */
	public void setAlpha(double a) { this.a = a; }

	/**
	 * Returns the alpha (transparency) channel value.
	 *
	 * @return the alpha value (0.0 = transparent, 1.0 = opaque)
	 */
	public double getAlpha() { return this.a; }

	/**
	 * Returns all four channel values as a double array.
	 *
	 * @return array containing [red, green, blue, alpha] values
	 */
	@Override
	public double[] toArray() { return new double[] { getRed(), getGreen(), getBlue(), getAlpha() }; }
}
