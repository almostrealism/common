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

import org.almostrealism.algebra.Triple;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.RGB;
import org.almostrealism.util.Editable;
import org.almostrealism.util.Producer;

// TODO  Add vector direction in place of axis selection.

/**
 * The StripeTexture object can be used to stripe a surface.
 * 
 * @author Mike Murray
 */
public class StripeTexture implements Texture, Editable {
  private static final String propNames[] = {"Stripe Width", "Smooth", "Axis",
  											"First Color", "Second Color",
											"Offset"};
  private static final String propDesc[] = {"The width of each stripe", "Smooth stripes or solid stripes", "The axis for the stripes",
						"The first color to use for the stripes", "The second color to use for the stripes",
						"Stripe offset"};
  private static final Class propTypes[] = {Double.class, Boolean.class, Editable.Selection.class, RGB.class, RGB.class, Double.class};
  private static final String axisOptions[] = {"X Axis", "Y Axis", "Z Axis"};
  public static final int XAxis = 0;
  public static final int YAxis = 1;
  public static final int ZAxis = 2;
  
  private Object props[];

	/**
	 * Constructs a StripeTexture object that can be used to stripe a surface. The default colors are black and white
	 * with a stripe width of 1.0 that is solid (not smooth) across the x axis.
	 */
	public StripeTexture() {
		Object props[] = {new Double(1.0), new Boolean(false),
							new Editable.Selection(StripeTexture.axisOptions),
							new RGB(1.0, 1.0, 1.0), new RGB(0.0, 0.0, 0.0),
							new Double(0.0)};
		
		this.setPropertyValues(props);
	}
	
	/**
	 * Constructs a StripeTexture object using the specified properties.
	 */
	public StripeTexture(Object props[]) {
		this.setPropertyValues(props);
	}
	
	/**
	 * @return  The color of the texture represented by this StripeTexture object at the specified point as an RGB object.
	 */
	public RGB getColorAt(Vector point) {
		if (this.props == null)
			return null;
		else
			return this.getColorAt(point, this.props);
	}
	
	/**
	 * @throws IllegalArgumentException  If one of the objects specified is not of the correct type.
	 * @return  The color of the texture represented by this StripeTexture object at the specified point as an RGB object.
	 */
	public RGB getColorAt(Vector point, Object props[]) {
		for (int i = 0; i < StripeTexture.propTypes.length; i++) {
			if (StripeTexture.propTypes[i].isInstance(props[i]) == false)
				throw new IllegalArgumentException("Illegal argument: " + props[i].toString());
		}
		
		double width = ((Double)props[0]).doubleValue();
		boolean smooth = ((Boolean)props[1]).booleanValue();
		int axis = ((Editable.Selection)props[2]).getSelected();
		
		double offset = ((Double)props[5]).doubleValue();
		
		double value;
		
		if (axis == 0)
			value = point.getX();
		else if (axis == 1)
			value = point.getY();
		else if (axis == 2)
			value = point.getZ();
		else
			return null;
		
		RGB c1 = (RGB)props[3];
		RGB c2 = (RGB)props[4];
		
		if (smooth == true) {
			double t = (1 + Math.sin(Math.PI * ((value / width) + offset))) / 2.0;
			
			return (c1.multiply(1.0 - t)).add(c2.multiply(t));
		} else {
			if (Math.sin(Math.PI * ((value / width) + offset)) > 0)
				return c1;
			else
				return c2;
		}
	}
	
	/**
	 * @param args[] {point, arg0, arg1, ...}
	 * @throws IllegalArgumentException  If args does not contain the correct object types.
	 * 
	 * @see org.almostrealism.color.ColorProducer#evaluate(java.lang.Object[])
	 */
	public RGB evaluate(Object args[]) {
	    if (!(args[0] instanceof Vector)) throw new IllegalArgumentException("Illegal argument: " + args[0]);
	    
	    Object o[] = new Object[args.length - 1];
	    
	    for (int i = 0; i < o.length; i++) o[i] = args[i + 1];
	    
	    return this.getColorAt((Vector)args[0], o);
	}
	
	@Override
	public RGB operate(Triple location) {
		return getColorAt(new Vector(location.getA(),
									location.getB(),
									location.getC()));
	}
	
	/**
	 * @return  An array of String objects with names for each editable property of this StripeTexture object.
	 */
	public String[] getPropertyNames() { return StripeTexture.propNames; }
	
	/**
	 * @return  An array of String objects with descriptions for each editable property of this StripeTexture object.
	 */
	public String[] getPropertyDescriptions() { return StripeTexture.propDesc; }
	
	/**
	 * @return  An array of Class objects representing the class types of each editable property of this StripeTexture object.
	 */
	public Class[] getPropertyTypes() { return StripeTexture.propTypes; }
	
	/**
	 * @return  The values of the properties of this StripeTexture object as an Object array.
	 */
	public Object[] getPropertyValues() { return this.props; }
	
	/**
	 * Sets the value of the property of this StripeTexture object at the specified index to the specified value.
	 * 
	 * @throws IllegalArgumentException  If the object specified is not of the correct type.
	 * @throws IndexOutOfBoundsException  If the index specified does not correspond to an editable property of this
     *                                    StripeTexture object.
	 */
	public void setPropertyValue(Object value, int index) {
		if (this.props == null)
			this.props = new Object[StripeTexture.propTypes.length];
		
		if (index >= StripeTexture.propTypes.length)
			throw new IndexOutOfBoundsException("Index out of bounds: " + index);
		else if (StripeTexture.propTypes[index].isInstance(value) == false)
			throw new IllegalArgumentException("Illegal argument: " + value.toString());
		else
			this.props[index] = value;
	}
	
	/**
	 * Sets the values of properties of this StripeTexture object to those specified.
	 * 
	 * @throws IllegalArgumentException  If one of the objects specified is not of the correct type.
	 *                                   (Note: none of the values after the erroneous value will be set)
	 * @throws IndexOutOfBoundsException  If the length of the specified array is longer than permitted.
	 */
	public void setPropertyValues(Object values[]) {
		for (int i = 0; i < values.length; i++) {
			this.setPropertyValue(values[i], i);
		}
	}
	
	/**
	 * @return  {first color, second color}.
	 */
	public Producer[] getInputPropertyValues() {
		return new Producer[] {(Producer)this.props[3], (Producer)this.props[4]};
	}
	
	/**
	 * Sets the values of properties of this HighlightShader object to those specified.
	 * 
	 * @throws IllegalArgumentException  If the Producer object specified is not of the correct type.
	 * @throws IndexOutOfBoundsException  If the index > 1.
	 */
	public void setInputPropertyValue(int index, Producer p) {
		if (index == 0)
			this.setPropertyValue(p, 3);
		else if (index == 1)
			this.setPropertyValue(p, 4);
		else
			throw new IndexOutOfBoundsException("Index out of bounds: " + index);
	}
	
	public void setStripeWidth(double w) { this.props[0] = new Double(w); }
	public void setSmooth(boolean s) { this.props[1] = new Boolean(s); }
	public void setAxis(int axis) { ((Editable.Selection)this.props[2]).setSelected(axis); }
	public void setFirstColor(RGB color) { this.props[3] = color; }
	public void setSecondColor(RGB color) { this.props[4] = color; }
	public void setOffset(double off) { this.props[5] = new Double(off); }
	
	/**
	 * @return "Stripe Texture".
	 */
	public String toString() { return "Stripe Texture"; }
}
