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

package org.almostrealism.space;

import java.util.*;

import io.almostrealism.code.Scope;
import io.almostrealism.code.Variable;
import org.almostrealism.algebra.*;
import org.almostrealism.algebra.Vector;
import org.almostrealism.color.*;
import org.almostrealism.color.computations.ColorProduct;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.color.computations.RGBAdd;
import org.almostrealism.color.computations.RGBProducer;
import org.almostrealism.geometry.TransformAsLocation;
import org.almostrealism.graph.mesh.Mesh;
import org.almostrealism.physics.Porous;
import org.almostrealism.relation.Constant;
import org.almostrealism.relation.Operator;
import org.almostrealism.texture.Texture;
import org.almostrealism.util.AdaptProducer;
import org.almostrealism.util.CollectionUtils;
import org.almostrealism.util.Producer;
import org.almostrealism.util.StaticProducer;

/**
 * {@link AbstractSurface} is an abstract implementation of {@link ShadableSurface} that takes
 * care of all of the standard methods of {@link ShadableSurface} that are shared by most
 * {@link ShadableSurface} implementations in the same way. By default the location is at the
 * origin, the size is 1.0, and the color is black.
 * 
 * @author  Michael Murray
 */
public abstract class AbstractSurface extends TriangulatableGeometry implements ShadableSurface, Porous {
	private boolean shadeFront, shadeBack;

	private RGB color;

	private double rindex = 1.0, reflectP = 1.0, refractP = 0.0;
	private double porosity;

	private Texture textures[];
	private ShaderSet shaders = new ShaderSet();

	private AbstractSurface parent;

	private Operator<Vector> in;
	
	/**
	 * Sets all values of this AbstractSurface to the defaults specified above.
	 */
	public AbstractSurface() {
		this.setShadeFront(true);
		this.setShadeBack(false);
		
		this.setTextures(new Texture[0]);
		
		this.setColor(new RGB(0.0, 0.0, 0.0));
	}

	public AbstractSurface(RGB color) {
		this.setShadeFront(true);
		this.setShadeBack(false);

		this.setTextures(new Texture[0]);

		this.setColor(color);
	}

	public AbstractSurface(RGB color, boolean addDefaultDiffuseShader) {
		this(color);
		if (!addDefaultDiffuseShader)
			this.setShaders(new Shader[0]);
	}
	
	/**
	 * Sets the location and size of this {@link AbstractSurface} to those specified, and uses
	 * the defaults for the other values.
	 */
	public AbstractSurface(Vector location, double size) {
		this();
		
		this.setLocation(location);
		this.setSize(size);
	}
	
	/**
	 * Sets the location, size, and color of this {@link AbstractSurface} to those specified.
	 */
	public AbstractSurface(Vector location, double size, RGB color) {
		this.setShadeFront(true);
		this.setShadeBack(false);

		this.setTextures(new Texture[0]);

		this.setLocation(location);
		this.setSize(size);
		this.setColor(color);
	}
	
	/**
	 * Sets the location, size, and color of this {@link AbstractSurface} to those specified.
	 */
	public AbstractSurface(Vector location, double size, RGB color, boolean addDefaultDiffuseShader) {
		this(location, size, color);
		
		if (!addDefaultDiffuseShader)
			this.setShaders(new Shader[0]);
	}
	
	/**
	 * Sets the parent surface group of this {@link AbstractSurface} to the specified {@link SurfaceGroup}.
	 */
	public void setParent(SurfaceGroup parent) { this.parent = parent; }
	
	/**
	 * Returns the parent of this {@link AbstractSurface} as a {@link SurfaceGroup} object.
	 */
	public SurfaceGroup getParent() { return (SurfaceGroup) parent; }
	
	/**
	 * Sets the flag indicating that the front side of this AbstractSurface should be shaded
	 * to the specified boolean value.
	 */
	public void setShadeFront(boolean shade) { this.shadeFront = shade; }
	
	/**
	 * Sets the flag indicating that the back side of this AbstractSurface should be shaded
	 * to the specified boolean value.
	 */
	public void setShadeBack(boolean shade) { this.shadeBack = shade; }
	
	/**
	 * Returns true if the front side of this AbstractSurface should be shaded.
	 * The "front side" is the side that the Vector object returned by the getNormalAt()
	 * method for this AbstractSurface points outward from.
	 */
	@Override
	public boolean getShadeFront() {
	    if (this.parent != null && this.parent.getShadeFront())
	        return true;
	    else
	        return this.shadeFront;
	}
	
	/**
	 * Returns true if the back side of this AbstractSurface should be shaded.
	 * The "back side" is the side that the vector opposite the Vector object
	 * returned by the getNormalAt() method for this AbstractSurface points outward from.
	 */
	@Override
	public boolean getShadeBack() {
	    if (this.parent != null && this.parent.getShadeBack())
	        return true;
	    else
	        return this.shadeBack;
	}
	
	/**
	 * @return  A Mesh object with location, size, color, scale coefficients,
	 *          rotation coefficients, and transformations as this AbstractSurface.
	 */
	@Override
	public Mesh triangulate() {
		Mesh m = super.triangulate();
		m.setColor(this.getColor());
		m.setPorosity(getPorosity());
		return m;
	}
	
	public void setIndexOfRefraction(double n) { this.rindex = n; }
	public double getIndexOfRefraction() { return this.rindex; }
	public double getIndexOfRefraction(Vector p) { return this.rindex; }
	
	public void setReflectedPercentage(double p) { this.reflectP = p; }
	public void setRefractedPercentage(double p) { this.refractP = p; }
	
	public double getReflectedPercentage() { return this.reflectP; }
	public double getReflectedPercentage(Vector p) { return this.reflectP; }
	public double getRefractedPercentage() { return this.refractP; }
	public double getRefractedPercentage(Vector p) { return this.refractP; }
	
	public void setPorosity(double p) { this.porosity = p; }

	@Override
	public double getPorosity() { return porosity; }

	public void setInput(Vector v) { this.in = new Constant<>(v); }
	public void setInput(Operator<Vector> in) { this.in = in; }
	public Operator<Vector> getInput() { return this.in; }

	/**
	 * Sets the Texture object (used to color this AbstractSurface) at the specified index
	 * to the specified Texture object.
	 */
	public void setTexture(int index, Texture texture) {
		this.textures[index] = texture;
	}
	
	/**
	 * Sets the Texture objects (used to color this AbstractSurface) to those specified.
	 */
	public void setTextures(Texture textures[]) {
		this.textures = textures;
	}
	
	/**
	 * Appends the specified Texture object to the list of Texture objects used to color this AbstractSurface.
	 */
	public void addTexture(Texture texture) {
		Texture newTextures[] = new Texture[this.textures.length + 1];
		
		for (int i = 0; i < this.textures.length; i++) { newTextures[i] = this.textures[i]; }
		newTextures[newTextures.length - 1] = texture;
		
		this.textures = newTextures;
	}
	
	/**
	 * Removes the Texture object at the specified index from the list of Texture objects used
	 * to color this AbstractSurface.
	 */
	public void removeTexture(int index) {
		Texture newTextures[] = new Texture[this.textures.length - 1];
		
		for (int i = 0; i < index; i++) { newTextures[i] = this.textures[i]; }
		for (int i = index + 1; i < newTextures.length; i++) { newTextures[i] = this.textures[i]; }
		
		this.textures = newTextures;
	}
	
	/**
	 * Returns a Set object that maintains the Texture objects stored by this AbstractSurface.
	 */
	public Set<Texture> getTextureSet() {
		Set<Texture> textureSet = new Set<Texture>() {
			/** @return  The number of elements stored by this set. */
			public int size() { return textures.length; }
			
			/** @return  True if this set contains no elements, false otherwise. */
			public boolean isEmpty() {
				return (textures.length <= 0);
			}
			
			/** @return  An Iterator object using the elements stored by this set. */
			public Iterator<Texture> iterator() {
				Iterator<Texture> itr = new Iterator<Texture>() {
					int index = 0;

					public boolean hasNext() {
						if (index < textures.length)
							return true;
						else
							return false;
					}

					public Texture next() throws NoSuchElementException {
						if (this.index >= textures.length)
							throw new NoSuchElementException("No element at " + this.index);
						return textures[this.index++];
					}

					public void remove() {
						removeTexture(this.index);
					}
				};

				return itr;
			}

			/**
			 * @return  An array containing all of the elements stored by this set.
			 */
			public Object[] toArray() { return textures; }
			
			/**
			 * @return  An array containing all of the elements stored by this set.
			 */
			public Object[] toArray(Object o[]) { return this.toArray(); }
			
			/**
			 * Adds the specified Object to this set and returns true.
			 * 
			 * 
			 * @throws IllegalArgumentException  If the specified Object is not an instance of Texture.
			 */
			public boolean add(Texture o) {
				if (o instanceof Texture == false)
					throw new IllegalArgumentException("Illegal argument: " + o.toString());
				
				addTexture((Texture)o);
				
				return true;
			}
			
			/**
			 * Adds all of the elements stored by the specified Collection object to this set.
			 * @return  True if the set changed as a result.
			 * 
			 * @throws IllegalArgumentException  If an element in the specified Collection object is not
			 *                                   an instance of Texture. Note: Elements that have not yet been added
			 *                                   to the set at the time this error occurs will not be added.
			 * @throws NullPointerException  If the specified Collection object is null.
			 */
			public boolean addAll(Collection c) {
				boolean added = false;
				
				Iterator<Texture> itr = c.iterator();
				
				while (itr.hasNext()) {
					this.add(itr.next());
					added = true;
				}
				
				return added;
			}
			
			/**
			 * Removes all occurences specified element from this set and returns true
			 * if the set changed as a result.
			 */
			public boolean remove(Object o) {
				boolean removed = false;
				
				for (int i = 0; i < textures.length; i++) {
					if (o.equals(textures[i])) {
						removeTexture(i--);
						removed = true;
					}
				}
				
				return removed;
			}
			
			/**
			 * Removes all of the elements stored by the specified Collection object from this set.
			 * @return  True if the set changed as a result.
			 * 
			 * @throws NullPointerException  If the specified Collection object is null.
			 */
			public boolean removeAll(Collection c) {
				if (c == null)
					throw new NullPointerException();
				
				boolean removed = false;
				
				Iterator itr = c.iterator();
				
				while (itr.hasNext()) {
					if (this.remove(itr.next()))
						removed = true;
				}
				
				return removed;
			}
			
			/**
			 * Removes all elements stored by this set that are not contained in the specified Collection object.
			 * @return  True if the set changed as a result.
			 * 
			 * @throws NullPointerException  If the specified Collection object is null.
			 */
			public boolean retainAll(Collection c) {
				if (c == null)
					throw new NullPointerException();
				
				boolean removed = false;
				
				Iterator itr = this.iterator();
				
				while (itr.hasNext()) {
					if (c.contains(itr.next()) == false) {
						itr.remove();
						removed = true;
					}
				}
				
				return removed;
			}
			
			/**
			 * Removes all elements of this set.
			 */
			public void clear() { textures = new Texture[0]; }
			
			/**
			 * @return  True if this set contains the specified Object, false otherwise.
			 */
			public boolean contains(Object o) {
				if (o instanceof Texture != true)
					return false;
				
				for (int i = 0; i < textures.length; i++) {
					if (o == null ? textures[i] == null : o.equals(textures[i]))
						return true;
				}
				
				return false;
			}
			
			/**
			 * @return  True if this set contains all of the elements of the specified Collection object.
			 * 
			 * @throws NullPointerException  If the specified Collection object is null.
			 */
			public boolean containsAll(Collection c) {
				if (c == null)
					throw new NullPointerException();
				
				Iterator itr = c.iterator();
				
				while (itr.hasNext()) {
					if (this.contains(itr.next()) == false)
						return false;
				}
				
				return true;
			}
			
			/**
			 * @return  True if the specified object is also an instance of Set with elements that
			 *          are equal to those.
			 */
			public boolean equals(Object o) {
				if (o instanceof Set == false)
					return false;
				
				if (((Set)o).size() != this.size())
					return false;
				
				if (this.containsAll((Set)o))
					return true;
				else
					return false;
			}
			
			/**
			 * @return  An integer hash code for this set by adding the hash codes for all elements
			 *          it stores.
			 */
			public int hashCode() {
				int hash = 0;
				
				Iterator itr = this.iterator();
				
				while (itr.hasNext())
					hash += itr.next().hashCode();
				
				return hash;
			}
		};
		
		return textureSet;
	}
	
	/**
	 * Sets the Shader objects (used to shade this AbstractSurface) to those specified.
	 */
	public void setShaders(Shader shaders[]) {
	    if (this.shaders == null) this.shaders = new ShaderSet();
	    
		this.shaders.clear();
		
		for (int i = 0; i < shaders.length; i++) this.shaders.add(shaders[i]);
	}
	
	/**
	 * @param set  New ShaderSet object to use for shading.
	 */
	public void setShaders(ShaderSet set) { this.shaders = set; }
	
	/**
	 * Appends the specified Shader object to the list of Shader objects used to shade this AbstractSurface.
	 */
	public boolean addShader(Shader shader) {
		if (this.shaders == null) this.shaders = new ShaderSet();
		return this.shaders.add(shader);
	}

	/**
	 * @param set  New ShaderSet object to use for shading.
	 */
	public void setShaderSet(ShaderSet set) { setShaders(set); }

	/**
	 * Returns a {@link Set} that maintains the {@link Shader} objects stored by this AbstractSurface.
	 */
	public ShaderSet getShaderSet() { return this.shaders; }
	
	/**
	 * Calculates a color value for this {@link AbstractSurface} using the sum of the values
	 * calculated by the {@link Shader} objects stored by this AbstractSurface and the parent
	 * of this {@link AbstractSurface} and returns this value as an {@link RGB}.
	 */
	@Override
	public Producer<RGB> shade(ShaderContext p) {
//		System.out.println(this + ".shade(reflections = " + p.getReflectionCount() + ")");

		p.setSurface(this);
		
		Producer<RGB> color = null;
		
		if (this.shaders != null) {
			color = this.shaders.shade(p, p.getIntersection());
		}

		if (this.getParent() != null) {
			if (color == null) {
				color = getParent().shade(p);
			} else {
				color = new RGBAdd(color, getParent().shade(p));
			}
		}
		
		return color;
	}
	
	/**
	 * Sets the color of this AbstractSurface to the color represented by the specified RGB object.
	 */
	public void setColor(RGB color) { this.color = color; }
	
	/**
	 * Returns the Texture object at the specified index in the list of Texture objects used
	 * to color this AbstractSurface.
	 */
	public Texture getTexture(int index) { return this.textures[index]; }
	
	/**
	 * Returns the list of Texture objects used to color this AbstractSurface as an array of Texture objects.
	 */
	// TODO  Change to List
	public Texture[] getTextures() { return this.textures; }
	
	/** Returns the color of this {@link AbstractSurface} as an {@link RGB} object. */
	public RGB getColor() { return this.color; }

	@Override
	public Producer<RGB> getValueAt(Producer<Vector> point) { return getColorAt(point, true); }
	
	/**
	 * @return  The color of this AbstractSurface at the specified point as an RGB object.
	 */
	public RGBProducer getColorAt(Producer<Vector> point, boolean transform) {
	    if (transform && getTransform(true) != null)
	    	point = new TransformAsLocation(getTransform(true).getInverse(), point);
	    
	    Producer<RGB> colorAt = StaticProducer.of(getColor());
	    
	    if (textures.length > 0) {
	    	List<Producer<RGB>> texColors = new ArrayList<>();

	        for (int i = 0; i < this.textures.length; i++) {
	            texColors.add(AdaptProducer.fromFunction(this.textures[i], point));
	        }
	        
	        colorAt = new ColorProduct(
	        		CollectionUtils.include(new Producer[0], colorAt, texColors.toArray(new Producer[0])));
	    }

	    if (this.parent != null)
	        colorAt = new ColorProduct(colorAt, this.parent.getColorAt(point, transform));
		
		return GeneratedColorProducer.fromProducer(this, colorAt);
	}
	
	/**
	 * Delegates to {@link #getNormalAt(Producer)}.
	 */
	@Override
	public Vector operate(Triple p) {
		return getNormalAt(StaticProducer.of(new Vector(p.getA(), p.getB(), p.getC()))).evaluate(new Object[0]);
	}

	@Override
	public Scope getScope(String prefix) {
		Scope s = new Scope();
		s.getVariables().add(new Variable(prefix + "vector", operate(null))); // TODO Input?
		return s;
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) { return false; }

	@Override
	public boolean isCancelled() { return false; }

	@Override
	public boolean isDone() { return true; }

	@Override
	public BoundingSolid calculateBoundingSolid() { return null; }
}
