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

import io.almostrealism.relation.Evaluable;
import org.almostrealism.algebra.Vector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.RGB;
import org.almostrealism.color.computations.GeneratedColorProducer;

// TODO  Add vector direction in place of axis selection.

/**
 * The {@link StripeTexture} can be used to stripe a surface.
 *
 * @author  Michael Murray
 */
// TODO  ColorProducers should be allowed to be specified in place of RGB values.
public class StripeTexture implements Texture {
  /** Constant identifying the X axis as the direction perpendicular to stripes. */
  public static final int XAxis = 0;

  /** Constant identifying the Y axis as the direction perpendicular to stripes. */
  public static final int YAxis = 1;

  /** Constant identifying the Z axis as the direction perpendicular to stripes. */
  public static final int ZAxis = 2;

  /** The width of each stripe period in world-space units along the selected axis. */
  private double stripeWidth;

  /** When {@code true}, stripes blend smoothly using a sine function; otherwise they have hard edges. */
  private boolean smooth;

  /** The axis perpendicular to which stripes are drawn (XAxis, YAxis, or ZAxis). */
  private int axis;

  /** The first stripe color (applied when the sine-based test is positive). */
  private RGB color1;

  /** The second stripe color (applied when the sine-based test is negative or zero). */
  private RGB color2;

  /** A phase offset added to the stripe position calculation. */
  private double offset;

	/**
	 * Constructs a StripeTexture object that can be used to stripe a surface. The default colors are black and white
	 * with a stripe width of 0.1 that is solid (not smooth) across the x axis.
	 */
	public StripeTexture() {
		this.stripeWidth = 0.1;
		this.smooth = false;
		this.axis = XAxis;
		this.color1 = new RGB(1.0, 1.0, 1.0);
		this.color2 = new RGB(0.0, 0.0, 0.0);
		this.offset = 0.0;
	}

	/**
	 * @return  The color of the texture represented by this {@link StripeTexture}
	 *          object at the specified point as an RGB object.
	 */
	@Override
	public RGB operate(Vector t) {
		PackedCollection result = this.getColorAt(new Object[0]).evaluate(t);
		return result instanceof RGB ? (RGB) result : new RGB(result.toDouble(0), result.toDouble(1), result.toDouble(2));
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Evaluable<PackedCollection> getColorAt(Object[] params) {
		return GeneratedColorProducer.fromProducer(this, () -> args -> {
			Vector l = args.length > 0 ? (Vector) args[0] : new Vector(1.0, 1.0, 1.0);
			Vector point = new Vector(l.getX(), l.getY(), l.getZ());

			double value;

			if (axis == XAxis)
				value = point.getX();
			else if (axis == YAxis)
				value = point.getY();
			else if (axis == ZAxis)
				value = point.getZ();
			else
				return null;

			if (smooth) {
				double t = (1 + Math.sin(Math.PI * ((value / stripeWidth) + offset))) / 2.0;

				return (color1.multiply(1.0 - t)).add(color2.multiply(t));
			} else {
				if (Math.sin(Math.PI * ((value / stripeWidth) + offset)) > 0)
					return color1;
				else
					return color2;
			}
		}).get();
	}

	/** Sets the width of each stripe. */
	public void setStripeWidth(double w) { this.stripeWidth = w; }

	/** Sets whether stripes blend smoothly or have hard edges. */
	public void setSmooth(boolean s) { this.smooth = s; }

	/** Sets the axis along which stripes are oriented (XAxis, YAxis, or ZAxis). */
	public void setAxis(int axis) { this.axis = axis; }

	/** Sets the first color used for the stripes. */
	public void setFirstColor(RGB color) { this.color1 = color; }

	/** Sets the second color used for the stripes. */
	public void setSecondColor(RGB color) { this.color2 = color; }

	/** Sets the stripe offset. */
	public void setOffset(double off) { this.offset = off; }

	/**
	 * @return "Stripe Texture".
	 */
	@Override
	public String toString() { return "Stripe Texture"; }
}
