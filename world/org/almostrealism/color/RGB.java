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

package org.almostrealism.color;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.Triple;
import org.almostrealism.math.Hardware;
import org.almostrealism.math.MemWrapper;
import org.almostrealism.util.Defaults;
import org.almostrealism.util.Producer;
import org.jocl.CL;
import org.jocl.Pointer;
import org.jocl.Sizeof;
import org.jocl.cl_mem;

/**
 * An RGB object represents a color defined by three channels: red, green, and blue.
 * An RGB object stores these channels as double values between 0.0 (no color) and
 * 1.0 (strongest color).
 */
public class RGB implements ColorProducer, Triple, MemWrapper, Externalizable, Cloneable {
	private interface Data extends MemWrapper {
		void set(int i, double r);
		void add(int i, double r);
		void scale(int i, double r);
		
		double get(int i);
		double length();
		void write(ObjectOutput out) throws IOException;
		void read(ObjectInput in) throws IOException;
		void setMem(double[] source);
	}
	
	private static class Data192 implements Data {
		public static final int depth = 192;

		private cl_mem mem;

		public Data192() {
			mem = CL.clCreateBuffer(Hardware.getLocalHardware().getContext(),
					CL.CL_MEM_READ_WRITE,3 * Sizeof.cl_double,
					null, null);
		}

		public void set(int i, double r) {
			setMem(i, new double[] { r }, 0, 1);
		}

		public void add(int i, double r) {
			double rgb[] = toArray();
			rgb[i] += r;
			setMem(rgb);
		}

		public void scale(int i, double r) {
			double rgb[] = toArray();
			rgb[i] *= r;
			setMem(rgb);
		}

		public double get(int i) { return toArray()[i]; }

		/** Returns the sum of the components (not vector length). */
		public double length() {
			double rgb[] = toArray();
			return rgb[0] + rgb[1] + rgb[2];
		}
		
		public void read(ObjectInput in) throws IOException {
			double rgb[] = new double[3];
			rgb[0] = in.readDouble();
			rgb[1] = in.readDouble();
			rgb[2] = in.readDouble();
			setMem(rgb);
		}
		
		public void write(ObjectOutput out) throws IOException {
			double rgb[] = toArray();
			out.writeDouble(rgb[0]);
			out.writeDouble(rgb[1]);
			out.writeDouble(rgb[2]);
		}

		public cl_mem getMem() { return mem; }

		public double[] toArray() {
			double d[] = new double[3];
			getMem(0, d, 0, 3);
			return d;
		}

		public void setMem(double[] source) {
			setMem(0, source, 0, 3);
		}

		protected void setMem(double[] source, int offset) {
			setMem(0, source, offset, 3);
		}

		protected void setMem(int offset, double[] source, int srcOffset, int length) {
			Pointer src = Pointer.to(source).withByteOffset(srcOffset* Sizeof.cl_double);
			CL.clEnqueueWriteBuffer(Hardware.getLocalHardware().getQueue(), mem, CL.CL_TRUE,
					offset * Sizeof.cl_double, length * Sizeof.cl_double,
					src, 0, null, null);
		}

		protected void setMem(int offset, RGB src, int srcOffset, int length) {
			CL.clEnqueueCopyBuffer(Hardware.getLocalHardware().getQueue(), src.getMem(), this.mem,
					srcOffset * Sizeof.cl_double,
					offset * Sizeof.cl_double,length * Sizeof.cl_double,
					0,null,null);
		}

		protected void getMem(int sOffset, double out[], int oOffset, int length) {
			Pointer dst = Pointer.to(out).withByteOffset(oOffset * Sizeof.cl_double);
			CL.clEnqueueReadBuffer(Hardware.getLocalHardware().getQueue(), mem,
					CL.CL_TRUE, sOffset * Sizeof.cl_double,
					length * Sizeof.cl_double, dst, 0,
					null, null);
		}

		protected void getMem(double out[], int offset) { getMem(0, out, offset, 3); }

		@Override
		public void destroy() {
			if (mem == null) return;
			CL.clReleaseMemObject(mem);
			mem = null;
		}

		@Override
		public void finalize() throws Throwable {
			destroy();
		}
	}

//	private static class Data48 implements Data {
//		public static final int depth = 48;
//
//		double max = Short.MAX_VALUE;
//		short rgb[] = new short[3];
//		public void set(int i, double r) { this.rgb[i] = (short) (r * max); }
//		public void add(int i, double r) { this.rgb[i] += (short) (r * max); }
//		public void scale(int i, double r) { this.rgb[i] *= r; }
//		public double get(int i) { return ((double) this.rgb[i]) / max; }
//		public double length() { return (this.rgb[0] + this.rgb[1] + this.rgb[2]) / max; }
//
//		public void read(ObjectInput in) throws IOException {
//			this.rgb[0] = in.readShort();
//			this.rgb[1] = in.readShort();
//			this.rgb[2] = in.readShort();
//		}
//
//		public void write(ObjectOutput out) throws IOException {
//			out.writeShort(this.rgb[0]);
//			out.writeShort(this.rgb[1]);
//			out.writeShort(this.rgb[2]);
//		}
//	}
	
  public static final long byteMask = 15;
  public static int defaultDepth = 192;
  
  private int colorDepth = RGB.defaultDepth;
  private double gamma = 0.75;
  private Data data;

	/**
  	 * Constructs an RGB object with all channels set to 0.0.
  	 */
	public RGB() { this(RGB.defaultDepth); }
	
	/**
	  Constructs an RGB object with all channels set to 0.0.
	*/
	public RGB(int model) {
		this(model, 0.0, 0.0, 0.0);
	}
	
	/**
	 * Constructs an RGB object with the specified red (r), green (g), and blue (b) channel
	 * values. If any of the values are less than 0.0, the channel will be set to 0.0.
	 * If any value is greater than 1.0, the channel is set 1.0.
	 */
	public RGB(double r, double g, double b) {
		this(RGB.defaultDepth, r, g, b);
	}
	
	/**
	 * Constructs an RGB object with the specified red (r), green (g), and blue (b) channel
	 * values. If any of the values are less than 0.0, the channel will be set to 0.0.
	 * If any value is greater than 1.0, the channel is set 1.0.
	 */
	public RGB(int model, double r, double g, double b) {
		this(model, r, g, b, true);
	}

	private RGB(int model, double r, double g, double b, boolean init) {
		this.initColorModule(model);
		if (init) this.data.setMem(new double[] { r, g, b });
	}
	
	public RGB(double nanom) {
		this(RGB.defaultDepth, nanom);
	}
	
	public RGB(int model, double nanom) {
		this.initColorModule(model);
		
		double f, r = 0.0, g = 0.0, b = 0.0;

		if (nanom >= 350 && nanom <= 439) {
			r = (440.0 - nanom) / (440.0 - 350.0);
			b = 1.0;
		} else if(nanom >= 440 && nanom <= 489) {
			g = (nanom - 440) / (490 - 440);
			b = 1.0;
		} else if(nanom >= 490 && nanom <= 509) {
			g = 1.0;
			b = (510.0 - nanom) / (510.0 - 490.0);
		} else if(nanom >= 510 && nanom <= 579) {
			r = (nanom - 510.0) / (580.0 - 510.0);
			g = 1.0;
		} else if(nanom >= 580 && nanom <= 644) {
			r = 1.0;
			g = (645.0 - nanom) / (645.0 - 580.0);
		} else if(nanom >= 645 && nanom <= 780) {
			r = 1.0;
		}

		if (nanom >= 350 && nanom <= 419) {
			f = 0.3 + 0.7 * (nanom - 350.0) / (420.0 - 350.0);
		} else if(nanom >= 420 && nanom <= 700) {
			f = 1.0;
		} else if(nanom >= 701 && nanom <= 780) {
			f = 0.3 + 0.7 * (780.0 - nanom) / (780.0 - 700.0);
		} else {
			f = 0.0;
		}

		this.setRed(this.adjust(r, f));
		this.setGreen(this.adjust(g, f));
		this.setBlue(this.adjust(b, f));
	}
	
	private void initColorModule(int model) {
		if (model == 192)
			this.data = new Data192();
		else if (model == 48)
//			this.data = new Data48();
			throw new RuntimeException("48 bit color temporarily disabled");
		else
			throw new RuntimeException(new IllegalArgumentException(model +
										" bit color module not available."));
		
		this.colorDepth = model;
	}
	
	private double adjust(double c, double f) {
		return c * f;
//		if (c == 0.0)
//			return 0;
//		else
//			return Math.pow(c * f, this.gamma);
	}

	/**
	 * Sets the value of the red channel of this RGB object to the specified double value.
	 * If the value is less than 0.0, the channel is set to 0.0.
	 * If the value is greater than 1.0, the channel is set to 1.0.
	 */
	public void setRed(double r) {
		if (r < 0.0) {
			this.data.set(0, 0.0);
		} else if (r > 1.0) {
			this.data.set(0, 1.0);
		} else {
			this.data.set(0, r);
		}
	}
	
	/**
	 * Sets the value of the green channel of this RGB to the specified double value.
	 * If the value is less than 0.0, the channel is set to 0.0.
	 * If the value is greater than 1.0, the channel is set to 1.0.
	 */
	public void setGreen(double g) {
		if (g < 0.0) {
			this.data.set(1, 0.0);
		} else if (g > 1.0) {
			this.data.set(1, 1.0);
		} else {
			this.data.set(1, g);
		}
	}
	
	/**
	 * Sets the value of the blue channel of this RGB object to the specified double value.
	 * If the value is less than 0.0, the channel is set to 0.0.
	 * If the value is greater than 1.0, the channel is set to 1.0.
	 */
	public void setBlue(double b) {
		if (b < 0.0) {
			this.data.set(2, 0.0);
		} else if (b > 1.0) {
			this.data.set(2, 1.0);
		} else {
			this.data.set(2, b);
		}
	}
	
	/**
	 * @return  The sum of each component of this RGB object.
	 */
	public double length() { return this.data.length(); }
	
	/**
	  Returns the value of the red channel of this RGB object as a double value.
	*/
	public double getRed() { return this.data.get(0); }
	
	/**
	  Returns the value of the green channel of this RGB object as a double value.
	*/
	public double getGreen() { return this.data.get(1); }
	
	/**
	  Returns the value of the blue channel of this RGB object as a double value.
	*/
	public double getBlue() { return this.data.get(2); }
	
	@Override
	public RGB operate(Triple in) { return this; }

	@Override
	public double getA() { return getRed(); }

	@Override
	public double getB() { return getGreen(); }

	@Override
	public double getC() { return getBlue(); }

	@Override
	public void setA(double a) { setRed(a); }

	@Override
	public void setB(double b) { setGreen(b); }	

	@Override
	public void setC(double c) { setBlue(c); }
	
	/**
	 * Returns the sum of the RGB value represented by this RGB object and that of the
	 * specified RGB object as an RGB object. Consider using the addTo method to avoid
	 * creating unnecessary new RGB objects.
	 */
	public RGB add(RGB rgb) {
		RGB sum = new RGB(this.colorDepth,
							this.getRed() + rgb.getRed(),
							this.getGreen() + rgb.getGreen(),
							this.getBlue() + rgb.getBlue());
		
		return sum;
	}
	
	/**
	 * Adds the specified RGB object to this RGB object.
	 * 
	 * @param rgb  Value to add.
	 */
	public void addTo(RGB rgb) {
	    this.data.add(0, rgb.getRed());
	    this.data.add(1, rgb.getGreen());
	    this.data.add(2, rgb.getBlue());
	}
	
	/**
	 * Returns the difference of the RGB value represented by this RGB object and that of the specified RGB object as an RGB object.
	 * Consider using the subtractFrom method to avoid creating unnecessary new RGB objects.
	 */
	public RGB subtract(RGB rgb) {
		RGB difference = new RGB(this.colorDepth,
									this.getRed() - rgb.getRed(),
									this.getGreen() - rgb.getGreen(),
									this.getBlue() - rgb.getBlue());
		
		return difference;
	}
	
	/**
	 * Subtracts the specified RGB object from this RGB object.
	 * 
	 * @param rgb  Value to subtract.
	 */
	public void subtractFrom(RGB rgb) {
	    this.data.add(0, -rgb.getRed());
	    this.data.add(1, -rgb.getGreen());
	    this.data.add(2, -rgb.getBlue());
	}
	
	/**
	 * @return  The product of the RGB value represented by this RGB object and the specified double
	 * value as an RGB object. Consider using the multiplyBy method to avoid creating unnecessary
	 * new RGB objects.
	 */
	public RGB multiply(double value) {
		RGB product = new RGB(this.colorDepth,
								this.getRed() * value,
								this.getGreen() * value,
								this.getBlue() * value);
		
		return product;
	}
	
	/**
	 * Multiplies this RGB object by the specified double value.
	 * 
	 * @param value  Value to multiply by.
	 */
	public void multiplyBy(double value) {
	    this.data.scale(0, value);
	    this.data.scale(1, value);
	    this.data.scale(2, value);
	}
	
	/**
	 * Returns the product of the RGB value represented by this RGB object and that of the specified RGB object as an RGB object.
	 * Consider using the multiplyBy method to avoid creating unnecessary new RGB objects.
	 */
	public RGB multiply(RGB rgb) {
		RGB product = new RGB(this.colorDepth,
								this.getRed() * rgb.getRed(),
								this.getGreen() * rgb.getGreen(),
								this.getBlue() * rgb.getBlue());
		
		return product;
	}
	
	/**
	 * Multiplies this RGB object by the specified RGB object.
	 * 
	 * @param rgb  Value to multiply by.
	 */
	public void multiplyBy(RGB rgb) {
	    this.data.scale(0, rgb.getRed());
	    this.data.scale(1, rgb.getGreen());
	    this.data.scale(2, rgb.getBlue());
	}
	
	/**
	 * Returns the quotient of the division of the RGB value represented by this RGB object as an RGB object.
	 * Consider using the divideBy method to avoid creating unnecessary new RGB objects.
	 */
	public RGB divide(double value) {
		RGB quotient = new RGB(this.colorDepth,
								this.getRed() / value,
								this.getGreen() / value,
								this.getBlue() / value);
		
		return quotient;
	}
	
	/**
	 * Divides this RGB object by the specified value.
	 * 
	 * @param value  Value to divide by.
	 */
	public void divideBy(double value) {
	    this.data.scale(0, 1.0 / value);
	    this.data.scale(1, 1.0 / value);
	    this.data.scale(2, 1.0 / value);
	}
	
	/**
	 * Returns the quotient of the division of the RGB value represented by this RGB object and that of the specified RGB object as an RGB object.
	 * Consider using the divideBy method to avoid creating unnecessary new RGB objects.
	 */
	public RGB divide(RGB rgb) {
		RGB quotient = new RGB(this.colorDepth,
								this.getRed() / rgb.getRed(),
								this.getGreen() / rgb.getGreen(),
								this.getBlue() / rgb.getBlue());
		
		return quotient;
	}
	
	/**
	 * Divides this RGB object by the specified RGB object.
	 * 
	 * @param rgb  Value to divide by.
	 */
	public void divideBy(RGB rgb) {
	    this.data.scale(0, 1.0 / rgb.getRed());
	    this.data.scale(1, 1.0 / rgb.getGreen());
	    this.data.scale(2, 1.0 / rgb.getBlue());
	}
	
	/**
	 * @return  this.
	 */
	public RGB evaluate(Object args[]) { return (RGB) this.clone(); }

	public void compact() {
		// TODO  RGB should optionally accept any triple producer.
		//       If this is the source of data, this compact method
		//       should delegate to it.
	}

	@Override
	public Scope getScope(String prefix) {
		Scope<Variable<Double>> s = new Scope<>();
		s.getVariables().add(new Variable<>(prefix + "r", getRed()));
		s.getVariables().add(new Variable<>(prefix + "g", getGreen()));
		s.getVariables().add(new Variable<>(prefix + "b", getBlue()));
		return s;
	}
	
	/**
	 * Returns true if the color represented by this RGB object is the same as the color
	 * represented by the specified RGB object, false otherwise. If o is not an RGB object,
	 * false is returned.
	 */
	public boolean equals(Object o) {
		if (o instanceof RGB)
			return this.equals((RGB) o);
		else
			return false;
	}
	
	/**
	 * Returns true if the color represented by this RGB object is the same as the color
	 * represented by the specified RGB object, false otherwise.
	 */
	public boolean equals(RGB rgb) {
		// TODO  An error threshold should be used to account for the
		//       fact that floating point values are often not identical
		//       but may produce indistinguishable colors
		if (this.getRed() != rgb.getRed()) return false;
		if (this.getGreen() != rgb.getGreen()) return false;
		if (this.getBlue() != rgb.getBlue()) return false;
		return true;
	}
	
	/** Returns an integer hash code for this RGB object. */
	public int hashCode() {
		double d = this.data.length() * 1000;
		return (int)d;
	}
	
	/**
	 * @return  An RGB object that represents the same color as this RGB object.
	 */
	public Object clone() {
		// TODO  Clone mem
		return new RGB(this.colorDepth, this.getRed(), this.getGreen(), this.getBlue());
	}
	
	/**
	 * @return  A String representation of this RGB object.
	 */
	public String toString() {
		String value = "[" + Defaults.displayFormat.format(this.getRed())  +
						", " + Defaults.displayFormat.format(this.getGreen()) +
						", " + Defaults.displayFormat.format(this.getBlue()) + "]";
		
		return value;
	}
	
	/**
	 * Parses a string representation of an RGB object of the format
	 * produced by the toString method.
	 * 
	 * @param s  String representation of RGB.
	 * @return  An RGB object
	 */
	public static RGB parseRGB(String s) {
		int i = s.indexOf(",");
		
		double r = Double.parseDouble(s.substring(s.indexOf("[") + 1, i));
		s = s.substring(i + 1);
		i = s.indexOf(",");
		double g = Double.parseDouble(s.substring(0, i));
		s = s.substring(i + 1);
		i = s.indexOf("]");
		double b = Double.parseDouble(s.substring(0, i));
		
		return new RGB(r, g, b);
	}
	
	public char[] encode() {
		long lr = Double.doubleToRawLongBits(this.getRed());
		long lg = Double.doubleToRawLongBits(this.getGreen());
		long lb = Double.doubleToRawLongBits(this.getBlue());
		
		char data[] = new char[48];
		
		for (int i = 0; i < 16; i++)
			data[i] = (char) (((lr & (RGB.byteMask << (4 * i))) >> (4 * i)) + 32);
		for (int i = 0; i < 16; i++)
			data[16 + i] = (char) (((lg & (RGB.byteMask << (4 * i))) >> (4 * i)) + 32);
		for (int i = 0; i < 16; i++)
			data[32 + i] = (char) (((lb & (RGB.byteMask << (4 * i))) >> (4 * i)) + 32);
		
		return data;
	}
	
	public static RGB decode(char data[]) { return RGB.decode(data, 0); }
	
	public static RGB decode(char data[], int index) {
		long lr = 0, lg = 0, lb = 0;
		
		for (int i = 0; i < 16; i++) lr = lr + (((byte)data[index + i]) - 32) << (4 * i);
		for (int i = 0; i < 16; i++) lg = lg + (((byte)data[index + 16 + i]) - 32) << (4 * i);
		for (int i = 0; i < 16; i++) lb = lb + (((byte)data[index + 32 + i]) - 32) << (4 * i);
		
		double r = Double.longBitsToDouble(lr);
		double g = Double.longBitsToDouble(lg);
		double b = Double.longBitsToDouble(lb);
		
		return new RGB(r, g, b);
	}
	
	/**
	 * @see java.io.Externalizable#writeExternal(java.io.ObjectOutput)
	 */
	public void writeExternal(ObjectOutput out) throws IOException {
		out.writeInt(this.colorDepth);
		this.data.write(out);
	}

	/**
	 * @see java.io.Externalizable#readExternal(java.io.ObjectInput)
	 */
	public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
		this.initColorModule(in.readInt());
		this.data.read(in);
	}

	public double[] toArray() { return new double[] { getRed(), getGreen(), getBlue() }; }

	@Override
	public cl_mem getMem() { return data.getMem(); }

	@Override
	public void destroy() { data.destroy(); }

	public static Producer<RGB> blank() {
		return new Producer<RGB>() {
			@Override
			public RGB evaluate(Object[] args) {
				return new RGB(defaultDepth, 0, 0, 0, false);
			}

			@Override
			public void compact() { }
		};
	}

	public static RGB gray(double value) { return new RGB(value, value, value); }
}
