/*
 * Copyright 2023 Michael Murray
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

package org.almostrealism.raytrace;

import org.almostrealism.color.buffer.ArrayColorBuffer;
import org.almostrealism.color.buffer.AveragedVectorMap2D;
import org.almostrealism.color.buffer.AveragedVectorMap2D96Bit;
import org.almostrealism.physics.BufferListener;
import org.almostrealism.color.buffer.ColorBuffer;
import org.almostrealism.color.buffer.TriangularMeshColorBuffer;
import org.almostrealism.electrostatic.PotentialMap;
import org.almostrealism.geometry.Elipse;
import io.almostrealism.code.Operator;
import io.almostrealism.relation.Evaluable;
import io.almostrealism.relation.Factory;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorMath;
import org.almostrealism.algebra.ZeroVector;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.Colorable;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.color.ShaderContext;
import org.almostrealism.color.Spectrum;
import org.almostrealism.color.Transparent;
import org.almostrealism.color.computations.GeneratedColorProducer;
import org.almostrealism.geometry.BoundingSolid;
import org.almostrealism.geometry.Camera;
import org.almostrealism.geometry.Curve;
import org.almostrealism.geometry.Ray;
import org.almostrealism.geometry.ShadableIntersection;
import org.almostrealism.io.Console;
import org.almostrealism.physics.Absorber;
import org.almostrealism.physics.AbsorberSet;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.Fast;
import org.almostrealism.physics.PhysicalConstants;
import org.almostrealism.physics.VolumeAbsorber;
import org.almostrealism.space.Scene;
import org.almostrealism.physics.Volume;
import org.almostrealism.stats.BRDF;
import org.almostrealism.stats.SphericalProbabilityDistribution;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/**
 * An {@link AbsorberHashSet} is an implementation of {@link AbsorberSet}
 * that uses a {@link HashSet} to store the child absorbers.
 * 
 * @author  Michael Murray
 */
public class AbsorberHashSet extends HashSet<AbsorberHashSet.StoredItem> implements AbsorberSet<AbsorberHashSet.StoredItem>,
																		ShadableSurface, Colorable, RGBFeatures, CodeFeatures {
	/** When true, front/back face detection is used during photon absorption. */
	public static final boolean enableFrontBackDetection = false;

	@Override
	public Operator<PackedCollection> get() {
		return null;
	}

	/**
	 * Internal listener interface for receiving notifications when the absorber set is modified.
	 */
	private interface SetListener {
		/**
		 * Called when the absorber set has been updated (absorber added or removed).
		 */
		void noteUpdate();
	}

	/**
	 * A container associating an {@link Absorber} with its world-space position and optional
	 * rendering data (color buffers, BRDF, incidence/exitance maps).
	 */
	public static class StoredItem {
		/** The absorber held by this item. */
		public Absorber absorber;
		/** The spatial volume associated with the absorber (lazily computed if null). */
		private Volume volume;
		/** A producer providing the world-space position of this absorber. */
		public Producer<PackedCollection> position;
		/** Flags indicating whether this item has been checked during traversal, uses fast absorption, or is highlighted. */
		boolean checked, fast, highlight;

		/** The bidirectional reflectance distribution function for this absorber, if any. */
		SphericalProbabilityDistribution brdf;

		/** Listener notified when this item's color buffer changes. */
		private BufferListener listener;
		/** The color buffer accumulating emitted light from this absorber. */
		private ColorBuffer buf;
		/** Maps recording the incident and exitant irradiance distributions on this absorber's surface. */
		private AveragedVectorMap2D incidence, exitance;
		/** The [width, height] dimensions of the color buffer, used when the buffer type does not track them. */
		private int[] wh;

		/**
		 * Creates an empty {@link StoredItem} with no absorber or position.
		 */
		public StoredItem() { }

		/**
		 * Creates a {@link StoredItem} associating the given absorber with its world-space position.
		 *
		 * @param a The absorber
		 * @param p A producer for the absorber's world-space position
		 */
		public StoredItem(Absorber a, Producer<PackedCollection> p) {
			this.absorber = a;
			this.position = p;
		}

		public Absorber getAbsorber() { return absorber; }
		public void setAbsorber(Absorber absorber) { this.absorber = absorber; }
		public void setVolume(Volume volume) { this.volume = volume; }
		public Producer<PackedCollection> getPosition() { return position; }
		public void setPosition(Producer<PackedCollection> position) { this.position = position; }
		public boolean isChecked() { return checked; }
		public void setChecked(boolean checked) { this.checked = checked; }
		public boolean isFast() { return fast; }
		public void setFast(boolean fast) { this.fast = fast; }
		public boolean isHighlight() { return highlight; }
		public void setHighlight(boolean highlight) { this.highlight = highlight; }
		public SphericalProbabilityDistribution getBrdf() { return brdf; }
		public void setBrdf(SphericalProbabilityDistribution brdf) { this.brdf = brdf; }
		public BufferListener getListener() { return listener; }
		public void setListener(BufferListener listener) { this.listener = listener; }
		public ColorBuffer getBuf() { return buf; }
		public void setBuf(ColorBuffer buf) { this.buf = buf; }
		public AveragedVectorMap2D getIncidence() { return incidence; }
		public void setIncidence(AveragedVectorMap2D incidence) { this.incidence = incidence; }
		public AveragedVectorMap2D getExitance() { return exitance; }
		public void setExitance(AveragedVectorMap2D exitance) { this.exitance = exitance; }
		public int[] getWh() { return wh; }
		public void setWh(int[] wh) { this.wh = wh; }

		/**
		 * Sets the buffer listener that is notified when this item's color or incidence buffers change.
		 *
		 * @param listener The listener to notify on buffer updates
		 */
		public void setBufferListener(BufferListener listener) { this.listener = listener; }

		/**
		 * Initializes or replaces this item's color buffer with the specified dimensions and scale.
		 *
		 * <p>If {@link AbsorberHashSet#solidColorBuffer} is set, a 1x1 buffer is used regardless
		 * of the specified dimensions. If {@link AbsorberHashSet#triangularColorBuffer} is set,
		 * a triangular mesh buffer is used.</p>
		 *
		 * @param w Width of the color buffer in pixels
		 * @param h Height of the color buffer in pixels
		 * @param m Scale factor for the color buffer
		 */
		public void setColorBufferSize(int w, int h, double m) {
			if (AbsorberHashSet.solidColorBuffer) {
				this.buf = new ArrayColorBuffer();
				((ArrayColorBuffer) this.buf).setColorBufferSize(1, 1, 1.0);
			} else if (AbsorberHashSet.triangularColorBuffer) {
				this.buf = new TriangularMeshColorBuffer();
			} else {
				this.buf = new ArrayColorBuffer();
				((ArrayColorBuffer) this.buf).setColorBufferSize(w, h, m);
			}
			
			this.incidence = new AveragedVectorMap2D96Bit(w, h);
			this.exitance = new AveragedVectorMap2D96Bit(w, h);
			// TODO Remove next line.
			((AveragedVectorMap2D96Bit) this.exitance).setVector(0, 0, 0);
			
			this.wh = new int[] {w, h};
		}
		
		/**
		 * Returns the [width, height] dimensions of this item's color buffer.
		 *
		 * @return An int array with two elements: width and height
		 */
		public int[] getColorBufferDimensions() {
			if (this.buf instanceof ArrayColorBuffer)
				return ((ArrayColorBuffer) this.buf).getColorBufferDimensions();
			else if (this.wh != null)
				return this.wh;
			else
				return new int[2];
		}
		
		/**
		 * Returns the scale factor of this item's color buffer, or 0.0 if no buffer is set.
		 *
		 * @return The color buffer scale factor
		 */
		public double getColorBufferScale() {
			if (this.buf == null)
				return 0.0;
			else
				return this.buf.getScale();
		}
		
		/**
		 * Returns the color at the specified UV coordinates, delegating to the full form with no surface normal.
		 *
		 * @param u      Horizontal UV coordinate in [0, 1]
		 * @param v      Vertical UV coordinate in [0, 1]
		 * @param front  True for the front face; false for the back face
		 * @param direct True to return the emitted spectrum directly; false to return the buffered color
		 * @return The RGB color at the specified UV position
		 */
		public RGB getColorAt(double u, double v, boolean front, boolean direct) {
			return this.getColorAt(u, v, front, direct, null);
		}

		/**
		 * Returns the color at the specified UV coordinates, optionally modulated by a surface normal.
		 *
		 * @param u      Horizontal UV coordinate in [0, 1]
		 * @param v      Vertical UV coordinate in [0, 1]
		 * @param front  True for the front face; false for the back face
		 * @param direct True to return the emitted spectrum directly; false to return the buffered color
		 * @param n      Optional surface normal for directional modulation (currently unused)
		 * @return The RGB color at the specified UV position
		 */
		public RGB getColorAt(double u, double v, boolean front, boolean direct, Vector n) {
			RGB c = null;
			
			if (direct) {
				if (this.absorber instanceof Spectrum) {
					c = ((Spectrum) this.absorber).getSpectra().getIntegrated();
					
//					if (n != null) {
//						double vec[] = this.incidence.getVector(u, v, front);
//						double d = n.getX() * vec[0] + n.getY() * vec[1] + n.getZ() * vec[2];
//						c.multiplyBy(d);
//					}
				}
			} else {
				c = this.buf.getColorAt(u, v, front);
				c.multiplyBy(1.0 / this.exitance.getSampleCount(u, v, front));
			}
			
			// TODO Multiply by dot product of exitance and incidence.
			
			return c;
		}
		
		/**
		 * Adds the given RGB color to this item's color buffer at the specified UV position.
		 *
		 * @param u     Horizontal UV coordinate in [0, 1]
		 * @param v     Vertical UV coordinate in [0, 1]
		 * @param front True for the front face; false for the back face
		 * @param c     The color to add
		 */
		public void addColor(double u, double v, boolean front, RGB c) {
			this.buf.addColor(u, v, front, c);
			if (this.listener != null)
				this.listener.updateColorBuffer(u, v, this.getVolume(), this.buf, front);
		}
		
		/**
		 * Records an incident irradiance sample at the given UV position.
		 *
		 * @param u     Horizontal UV coordinate in [0, 1]
		 * @param v     Vertical UV coordinate in [0, 1]
		 * @param e     The incident irradiance direction vector
		 * @param front True for the front face; false for the back face
		 */
		public void addIncidence(double u, double v, Producer<PackedCollection> e, boolean front) {
			this.incidence.addVector(u, v, e, front);
			if (this.listener != null)
				this.listener.updateIncidenceBuffer(u, v, this.getVolume(), this.incidence, front);
		}

		/**
		 * Records an exitant irradiance sample at the given UV position.
		 *
		 * @param u     Horizontal UV coordinate in [0, 1]
		 * @param v     Vertical UV coordinate in [0, 1]
		 * @param e     The exitant irradiance direction vector
		 * @param front True for the front face; false for the back face
		 */
		public void addExitance(double u, double v, Producer<PackedCollection> e, boolean front) {
			this.exitance.addVector(u, v, e, front);
			if (this.listener != null)
				this.listener.updateExitanceBuffer(u, v, this.getVolume(), this.exitance, front);
		}
		
		/**
		 * Returns the {@link Volume} associated with this item's absorber, lazily computing it if needed.
		 *
		 * @return The volume, or {@code null} if the absorber has no associated volume
		 */
		public Volume getVolume() {
			if (this.volume == null)
				this.volume = AbsorberHashSet.getVolume(this.absorber);
			return this.volume;
		}
		
		@Override
		public String toString() { return "StoredItem[" + this.absorber + "]"; }
	}
	
	/** Order constant that iterates absorbers in insertion order. */
	public static final int DEFAULT_ORDER = 1;
	/** Order constant that iterates absorbers in a random shuffle order. */
	public static final int RANDOM_ORDER = 2;
	/** Order constant that iterates absorbers sorted by popularity (most accessed first). */
	public static final int POPULAR_ORDER = 4;
	/** When true, all items share a single 1x1 color buffer instead of per-item buffers. */
	public static boolean solidColorBuffer = false, triangularColorBuffer = false;

	/** The bit depth used for color buffers stored on {@link StoredItem}s. */
	public int colorDepth = 192; //  48; TODO  Re-enable 48 bit color when available

	/** The simulation clock shared with all child absorbers. */
	private Clock clock;
	/** The electrostatic potential map associated with this absorber set. */
	private PotentialMap map;
	/** The absorber currently selected to emit; the one closest to the camera; the most recently added. */
	private StoredItem emitter, rclosest, closest, lastAdded;
	/** Listener notified when color buffers change. */
	private BufferListener listener;

	/** The set of {@link SetListener}s notified when the absorber set is modified. */
	private final Set sList;

	/** The RGB color returned by {@link #getValueAt} for this set. */
	private RGB rgb = new RGB(this.colorDepth, 0.0, 0.0, 0.0);

	/** The iteration order method (DEFAULT_ORDER, RANDOM_ORDER, or POPULAR_ORDER). */
	private int order = 1;
	/** When true, the set uses fast absorption via the cached closest absorber. */
	private boolean fast = true;
	/** The delay (in distance units) and epsilon used in fast absorption calculations. */
	private double delay, e;
	/** The half-angle of the spread cone used when scattering absorbed photons. */
	private double spreadAngle;
	/** The number of spread samples generated per photon absorption. */
	private int spreadCount;

	/** Maximum distance beyond which absorbers are not considered for absorption. */
	private double max = Double.MAX_VALUE, bound = Double.MAX_VALUE;
	/** True after {@link #clearChecked()} has run; reset when the set is modified. */
	private boolean cleared = false;

	/** The reusable sorted/shuffled iterator built for this absorber set. */
	private Iterator items;
	/** The thread currently using the shared {@link #items} iterator. */
	private Thread itemsUser;
	/** True while the items array is being rebuilt; indicates the shared iterator is not ready. */
	private boolean itemsNow, itemsEnabled = false;
	
	/**
	 * Constructs an empty {@link AbsorberHashSet} with no absorbers and an empty set listener list.
	 */
	public AbsorberHashSet() {
		this.sList = new HashSet();
	}

	@Override
	public int addAbsorber(Absorber a, Producer x) {
		return this.addAbsorber(a, x, a instanceof Fast);
	}

	/**
	 * Adds an absorber at the specified position with an explicit fast-absorption flag.
	 *
	 * @param a    The absorber to add
	 * @param x    A producer providing the absorber's world-space position
	 * @param fast When true, the absorber participates in fast-path absorption
	 * @return The new size of the set, or -1 if the absorber was already present
	 */
	public int addAbsorber(Absorber a, Producer x, boolean fast) {
		a.setClock(this.clock);
		StoredItem item = new StoredItem(a, x);
		item.setBufferListener(this.listener);
		item.fast = fast;

		if (a instanceof BRDF) item.brdf = ((BRDF)a).getBRDF();

		if (this.add(item)) {
			this.lastAdded = item;
			this.notifySetListeners();
			return this.size();
		} else {
			return -1;
		}
	}

	@Override
	public int removeAbsorbers(double[] x, double radius) {
		Iterator itr = this.iterator();
		int tot = 0;
		
		while (itr.hasNext()) {
			StoredItem it = (StoredItem) itr.next();
			
			if (VectorMath.distance(x, it.position.get().evaluate().toArray()) <= radius) {
				this.remove(it);
				tot++;
			}
		}
		
		return tot;
	}

	/**
	 * Removes the {@link StoredItem} associated with the given absorber from the set.
	 *
	 * @param a The absorber to remove
	 * @return The size of the set after removal
	 */
	@Override
	public int removeAbsorber(Absorber a) {
		Iterator itr = this.iterator();
		
		while (itr.hasNext()) {
			StoredItem it = (StoredItem) itr.next();
			if (it.equals(a)) this.remove(it);
			this.notifySetListeners();
		}
		
		return this.size();
	}
	
	/**
	 * Initializes all absorbers by refreshing their BRDF references and enabling the
	 * shared items iterator for repeated traversal.
	 */
	public void init() {
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			
			if (n.absorber instanceof BRDF)
				n.brdf = ((BRDF)n.absorber).getBRDF();
			if (n.absorber instanceof AbsorberHashSet)
				((AbsorberHashSet)n.absorber).init();
		}
		
		this.itemsEnabled = true;
	}
	
	/**
	 * Clears all color buffer data from every stored item in the set.
	 */
	public void clearColorBuffers() {
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			if (n.buf != null) n.buf.clear();
		}
	}
	
	/**
	 * Loads color buffer data for all stored items using the given scene factory.
	 *
	 * @param loader A factory that provides the scene used to load color buffer data
	 * @throws IOException If an error occurs while loading color buffer data
	 */
	public void loadColorBuffers(Factory<Scene<ShadableSurface>> loader) throws IOException {
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			if (n.buf != null) this.loadColorBuffer(loader, n);
		}
	}
	
	/**
	 * Loads the color buffer for a single stored item using the given scene factory.
	 *
	 * @param loader A factory providing the scene used to load color buffer data
	 * @param it     The stored item whose color buffer is to be loaded
	 * @throws IOException If an error occurs while loading the color buffer
	 */
	public void loadColorBuffer(Factory<Scene<ShadableSurface>> loader, StoredItem it) throws IOException {
		// TODO
	}
	
	/**
	 * Sets the BRDF on the most recently added absorber.
	 *
	 * @param brdf The spherical probability distribution to assign
	 */
	public void setBRDF(SphericalProbabilityDistribution brdf) {
		this.setBRDF(this.lastAdded.absorber, brdf);
	}

	// TODO  This should store the dimensions, and apply them to new absorbers that are added
	/**
	 * Sets the color buffer dimensions on the most recently added absorber.
	 *
	 * @param w Width of the color buffer in pixels
	 * @param h Height of the color buffer in pixels
	 * @param m Scale factor applied to the color buffer
	 */
	public void setColorBufferDimensions(int w, int h, double m) {
		this.setColorBufferDimensions(this.lastAdded.absorber, w, h, m);
	}
	
	/**
	 * Sets the BRDF on the stored item associated with the specified absorber.
	 *
	 * @param a    The absorber whose BRDF is to be set
	 * @param brdf The spherical probability distribution to assign
	 */
	public void setBRDF(Absorber a, SphericalProbabilityDistribution brdf) {
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			
			if (n.absorber == a) {
				n.brdf = brdf;
				if (n.absorber instanceof BRDF) ((BRDF) n.absorber).setBRDF(brdf);
				return;
			}
		}
	}
	
	/**
	 * Sets the color buffer dimensions on the stored item associated with the specified absorber.
	 *
	 * @param a The absorber whose color buffer dimensions are to be set
	 * @param w Width of the color buffer in pixels
	 * @param h Height of the color buffer in pixels
	 * @param m Scale factor applied to the color buffer
	 */
	public void setColorBufferDimensions(Absorber a, int w, int h, double m) {
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			
			if (n.absorber == a) {
				n.setColorBufferSize(w, h, m);
				return;
			}
		}
	}
	
	/**
	 * Sets the buffer listener for all current and future stored items.
	 *
	 * @param l The listener to notify when color or incidence buffers change
	 */
	public void setBufferListener(BufferListener l) {
		this.listener = l;
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			n.setBufferListener(this.listener);
		}
	}
	
	/**
	 * Returns the position producer for the stored item associated with the given absorber.
	 *
	 * @param a The absorber to look up
	 * @return The position producer, or {@code null} if the absorber is not in this set
	 */
	public Producer<PackedCollection> getLocation(Absorber a) {
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			if (n.absorber == a) return n.position;
		}
		
		return null;
	}
	
	public void setOrderMethod(int order) { this.order = order; }
	public int getOrderMethod() { return this.order; }
	
	public void setSpreadAngle(double angle) { this.spreadAngle = angle; }
	public double getSpreadAngle() { return this.spreadAngle; }
	
	public void setSpreadCount(int count) { this.spreadCount = count; }
	public int getSpreadCount() { return this.spreadCount; }

	@Override
	public void setPotentialMap(PotentialMap m) { this.map = m; }

	@Override
	public PotentialMap getPotentialMap() { return this.map; }

	@Override
	public void setMaxProximity(double radius) { this.max = Math.min(2 * this.bound, radius); }

	@Override
	public double getMaxProximity() { return this.max; }
	
	public void setFastAbsorption(boolean fast) { this.fast = fast; }
	public boolean getFastAbsorption() { return this.fast; }

	@Override
	public boolean absorb(Vector x, Vector p, double energy) {
		if (fast && closest != null && closest.fast) {
			StoredItem as = closest;
			Absorber a = closest.absorber;
			Vector nx = x.subtract(new Vector(closest.position.get().evaluate(), 0));
			Vector y = nx.clone();
			y.addTo(p.multiply(this.delay + this.e));
			if (a instanceof Fast) {
				double t = this.delay / PhysicalConstants.C;
				((Fast) a).setAbsorbDelay(t);
				((Fast) a).setOrigPosition(nx.toArray());
			}

			this.closest = null;
			
			if (a.absorb(y, p, energy)) {
				Volume<?> v = getVolume(a);
				
				if (v != null) {
					double[] uv = v.getSurfaceCoords(v(y));
					Vector normal = (Vector) v.getNormalAt(v(y)).get().evaluate();
					boolean front = p.dotProduct(normal) < 0.0;
					as.addIncidence(uv[0], uv[1], v(p.minus()), front);
					this.spread(a, normal, nx.toArray(), y.toArray(), p, energy, as);
				}
				
				return true;
			}
			
			y.addTo(p.multiply(-2.0 * this.e));
			
			if (a.absorb(x, p, energy)) {
				Volume<?> v = getVolume(a);
				
				if (v != null) {
					double[] uv = v.getSurfaceCoords(v(x));

					Vector n = (Vector) v.getNormalAt(v(y)).get().evaluate();
					boolean front = p.dotProduct(n) < 0.0;
					as.addIncidence(uv[0], uv[1], v(p.minus()), front);
					this.spread(a, n, nx.toArray(), y.toArray(), p, energy, as);
				}
				
				return true;
			}
		}
		
		Iterator itr = this.iterator(true);
		
		w: while (itr.hasNext()) {
			StoredItem it = (StoredItem) itr.next();
			if (it.absorber instanceof Transparent) continue w;
			Vector l = x.subtract(new Vector(it.position.get().evaluate(), 0));
			if (l.length() > this.max) continue w;
			if (it.absorber.absorb(l, p, energy)) {
				Volume<?> v = getVolume(it.absorber);
				
				if (v != null) {
					double[] uv = v.getSurfaceCoords(v(l));
					Vector nl = (Vector) v.getNormalAt(v(l)).get().evaluate();
					boolean front = p.dotProduct(nl) < 0.0;
					it.addIncidence(uv[0], uv[1], v(p.minus()), front);
				}
				
				return true;
			}
		}
		
		return false;
	}
	
	/**
	 * Returns the {@link Volume} associated with the given absorber, if any.
	 *
	 * <p>Checks whether the absorber is a {@link VolumeAbsorber} or directly implements
	 * {@link Volume}, and returns the corresponding volume.</p>
	 *
	 * @param a The absorber to query
	 * @return The associated volume, or {@code null} if the absorber has no volume
	 */
	protected static Volume getVolume(Absorber a) {
		Volume v = null;
		
		if (a instanceof VolumeAbsorber)
			v = ((VolumeAbsorber)a).getVolume();
		else if (a instanceof Volume)
			v = (Volume) a;
		
		return v;
	}
	
	/**
	 * Spreads photon energy across the absorber surface using the configured spread angle and count.
	 *
	 * <p>Generates {@link #spreadCount} random sample positions on the surface of a conic section
	 * centered at the original incidence point. Each sample is tested for absorption and, if absorbed,
	 * the incidence direction is recorded on the item's incidence map.</p>
	 *
	 * @param a      The absorber receiving the spread photon energy
	 * @param n      The surface normal at the absorption point
	 * @param ox     The original photon position before absorption
	 * @param x      The adjusted position after applying the delay
	 * @param p      The photon momentum/direction vector
	 * @param energy The photon energy
	 * @param it     The stored item associated with the absorber
	 */
	protected void spread(Absorber a, Vector n, double[] ox,
						  double[] x, Vector p, double energy, StoredItem it) {
		if (this.spreadAngle <= 0.0) return;
		Volume<?> v = getVolume(a);
		Elipse.loadConicSection(ox, x, n.toArray(), this.spreadAngle);
		
		for (int i = 0; i < this.spreadCount; i++) {
			Vector s = new Vector(Elipse.getSample());
			
			// Consider changing p to s - ox
			if (!a.absorb(s, p, energy) && v != null) {
				double in = v.intersect(s, p);
				s.addTo(p.multiply(in + e));

				Producer<PackedCollection> is = v(s);

				if (a.absorb(s, p, energy)) {
					double[] uv = v.getSurfaceCoords(is);
					Vector ns = new Vector(v.getNormalAt(v(s)).get().evaluate(), 0);
					boolean front = p.dotProduct(ns) < 0.0;
					it.addIncidence(uv[0], uv[1], v(p.minus()), front);
				}
			} else if (v != null) {
				Producer<PackedCollection> is = v(s);
				double[] uv = v.getSurfaceCoords(is);
				Vector ns = new Vector(v.getNormalAt(v(s)).get().evaluate(), 0);
				boolean front = p.dotProduct(ns) < 0.0;
				it.addIncidence(uv[0], uv[1], v(p.minus()), front);
			}
		}
	}
	
	/**
	 * Selects the next absorber to emit a photon by finding the first absorber whose
	 * {@link Absorber#getNextEmit()} time is less than the current clock tick interval.
	 */
	protected void selectEmitter() {
		Iterator itr = this.iterator(true);
		
		while (itr.hasNext()) {
			StoredItem it = (StoredItem) itr.next();
			double e = it.absorber.getNextEmit();
			
			if (e < this.clock.getTickInterval()) {
				this.emitter = it;
				return;
			}
		}
	}

	@Override
	public Producer<PackedCollection> emit() {
		if (this.emitter == null) {
			return null;
		} else {
			Producer<PackedCollection> d = null;
			
			v: if (this.emitter.buf != null) {
				Volume<?> vol = getVolume(this.emitter.absorber);
				if (vol == null) break v;
				
				Producer<PackedCollection> p = this.emitter.absorber.getEmitPosition();

				double energy = this.emitter.absorber.getEmitEnergy();
				double n = 1000 * PhysicalConstants.HC / energy;
				
				d = this.emitter.absorber.emit();
				
				if (d == null)
					System.out.println("AbsorberHashSet: " +
										this.emitter.absorber +
										" emitted null.");
				
				boolean front = !enableFrontBackDetection || dotProduct(d, vol.getNormalAt(p)).get().evaluate().toDouble(0) >= 0.0;
				
				double[] uv = vol.getSurfaceCoords(p);
				RGB c = new RGB(this.colorDepth, n);
				this.emitter.addColor(uv[0], uv[1], front, c);
				this.emitter.addExitance(uv[0], uv[1], d, front);
			} else {
				d = this.emitter.absorber.emit();
			}
			
			this.emitter = null;
			return d;
		}
	}

	@Override
	public double getEmitEnergy() {
		if (this.emitter == null) this.selectEmitter();
		if (this.emitter == null) return 0.0;
		return this.emitter.absorber.getEmitEnergy();
	}

	@Override
	public double getNextEmit() {
		if (this.emitter == null) this.selectEmitter();
		if (this.emitter == null) return Integer.MAX_VALUE;
		return this.emitter.absorber.getNextEmit();
	}

	@Override
	public Producer<PackedCollection> getEmitPosition() {
		if (this.emitter == null) this.selectEmitter();
		if (this.emitter == null) return null;
		Producer<PackedCollection> x = emitter.absorber.getEmitPosition();
		return add(x, emitter.position);
	}

	@Override
	public void setClock(Clock c) {
		this.clock = c;
		
		if (this.clock != null)
			this.e = this.clock.getTickInterval() * PhysicalConstants.C / 100.0;
		
		Iterator itr = this.iterator();
		while (itr.hasNext()) ((StoredItem)itr.next()).absorber.setClock(this.clock);
	}

	@Override
	public Clock getClock() { return this.clock; }

	@Override
	public Iterator absorberIterator() {
		Iterator itr = new Iterator() {
			private final Iterator itr = AbsorberHashSet.super.iterator();

			@Override
			public boolean hasNext() { return this.itr.hasNext(); }
			@Override
			public Object next() { return ((StoredItem)this.itr.next()).absorber; }
			@Override
			public void remove() { this.itr.remove(); }
		};
		
		return itr;
	}
	
	/**
	 * Notifies all registered {@link SetListener}s that the absorber set has been modified.
	 */
	public void notifySetListeners() {
		Iterator itr = this.sList.iterator();
		while (itr.hasNext()) ((SetListener)itr.next()).noteUpdate();
	}
	
	/**
	 * Registers a {@link SetListener} to receive notifications when the absorber set is modified.
	 *
	 * @param l The listener to register
	 * @return Always returns {@code true}
	 */
	public boolean addSetListener(SetListener l) {
		this.sList.add(l);
		return true;
	}
	
	/**
	 * Returns an iterator over stored items without shuffling.
	 *
	 * @return An iterator that visits each stored item in default order
	 */
	@Override
	public Iterator iterator() { return this.iterator(false); }

	/**
	 * Returns an iterator over stored items, optionally shuffled for random traversal order.
	 *
	 * <p>When {@code shuffle} is false or {@link #order} is not {@link #RANDOM_ORDER}, a reusable
	 * sorted iterator is returned and cached for the current thread. When {@code shuffle} is true
	 * and {@link #order} is {@link #RANDOM_ORDER}, a new randomly shuffled iterator is returned.</p>
	 *
	 * @param shuffle When true and order is RANDOM_ORDER, the items are visited in random order
	 * @return An iterator that visits each stored item
	 */
	public Iterator iterator(boolean shuffle) {
		if (this.itemsNow || !this.itemsEnabled)
			return super.iterator();
		
		if ((this.itemsUser == Thread.currentThread()
				|| this.itemsUser == null)
				&& this.items != null) {
			this.itemsUser = Thread.currentThread();
			return this.items;
		} else if (this.items != null) {
			System.out.println("AbsorberHashSet: Needed extra iterator for " +
								Thread.currentThread() + " (" + this.itemsUser + ")");
		}
		
		if (!shuffle || this.order != RANDOM_ORDER) {
			this.itemsNow = true;
			
			this.items = new Iterator() {
				private final SetListener l = new SetListener() {
					@Override
					public void noteUpdate() { myNoteUpdate(); }
				};

				private int index = 0;
				private StoredItem[] data = AbsorberHashSet.this.toArray(new StoredItem[0]);

				{
					AbsorberHashSet.this.addSetListener(l);
				}

				@Override
				public boolean hasNext() {
					if (this.index >= data.length) {
						this.index = 0;
						AbsorberHashSet.this.itemsUser = null;
						return false;
					} else {
						return true;
					}
				}

				@Override
				public Object next() { return this.data[this.index++]; }
				@Override
				public void remove() { }

				public void myNoteUpdate() {
					AbsorberHashSet.this.itemsNow = true;
					this.data = AbsorberHashSet.this.toArray(new StoredItem[0]);
					AbsorberHashSet.this.itemsNow = false;
				}
			};
			
			this.itemsNow = false;
			
			return this.items;
		}
		
		// this.clearChecked();
		
		final StoredItem[] itrs = new StoredItem[this.size()];
		
		Iterator itr = super.iterator();
		
		int i = 0;
		
		while (itr.hasNext() && i < itrs.length) {
			int start = (int) (Math.random() * itrs.length);
			boolean first = true;
			
			j: for (int j = start; first || j != start; j = (j + 1) % itrs.length) {
				first = false;
				if (itrs[j] != null) continue j;
				
				itrs[j] = (StoredItem) itr.next();
				i++;
				break;
			}
		}
		
		this.cleared = false;
		
		return new Iterator() {
			private int index = 0;

			@Override
			public boolean hasNext() { return index < itrs.length; }
			@Override
			public Object next() { return itrs[this.index++]; }
			@Override
			public void remove() { }
		};
	}
	
	/**
	 * Resets the {@code checked} flag on all stored items, enabling them to be considered
	 * in the next traversal pass.
	 */
	protected void clearChecked() {
		if (this.cleared) return;
		
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			((StoredItem)itr.next()).checked = false;
		}
		
		this.cleared = true;
	}

	/**
	 * Sets the spatial bounding radius and updates the maximum proximity accordingly.
	 *
	 * @param bound The new bounding radius; max proximity is clamped to {@code 2 * bound}
	 */
	public void setBound(double bound) {
		this.bound = bound;
		this.max = Math.min(this.max, 2*this.bound);
	}
	
	@Override
	public double getBound() { return this.bound; }

	@Override
	public BoundingSolid calculateBoundingSolid() { throw new RuntimeException("getBoundingSolid not implemented"); }

	/**
	 * Returns the distance from position {@code p} along direction {@code d} to the nearest absorber,
	 * using fast-path caching.
	 *
	 * @param p The starting position
	 * @param d The direction vector
	 * @return The distance to the nearest absorber, or a large value if none found
	 */
	@Override
	public double getDistance(Vector p, Vector d) {
		return this.getDistance(p, d, true, false);
	}

	/**
	 * Returns the distance from position {@code p} along direction {@code d} to the nearest absorber.
	 *
	 * @param p    The starting position
	 * @param d    The direction vector
	 * @param fast When true, uses the cached closest absorber for fast absorption
	 * @return The distance to the nearest absorber, or a large value if none found
	 */
	public double getDistance(Vector p, Vector d, boolean fast) {
		return this.getDistance(p, d, fast, false);
	}

	/**
	 * Returns the distance from position {@code p} along direction {@code d} to the nearest absorber,
	 * with optional exclusion of the camera absorber.
	 *
	 * <p>Iterates all stored items, computing intersection distances via their associated volumes.
	 * If fast mode is enabled and the closest absorber is a fast absorber, the delay is stored
	 * and 0.0 is returned to signal immediate fast-path absorption.</p>
	 *
	 * @param p             The starting position
	 * @param d             The direction vector
	 * @param fast          When true, uses the cached closest absorber for fast absorption
	 * @param excludeCamera When true, camera absorbers are skipped in the distance calculation
	 * @return The distance to the nearest absorber, or a large value if none found
	 */
	public double getDistance(Vector p, Vector d, boolean fast, boolean excludeCamera) {
		Iterator itr = this.iterator(false);
		
		double l = Double.MAX_VALUE - 1.0;
		this.closest = null;
		
		w: while (itr.hasNext()) {
			StoredItem s = (StoredItem) itr.next();
			double dist = 0.0;
			
			Vector x = p.subtract(new Vector(s.position.get().evaluate(), 0));
			
			if (s.absorber instanceof Transparent) {
				continue w;
			} else if (excludeCamera && s.absorber instanceof Camera) {
				continue w;
			} else if (s.absorber instanceof AbsorberSet) {
				dist = ((AbsorberSet) s.absorber).getDistance(x, d);
			} else if (s.absorber instanceof Volume) {
				dist = ((Volume) s.absorber).intersect(x, d);
			} else if (s.absorber instanceof VolumeAbsorber) {
				dist = ((VolumeAbsorber) s.absorber).getVolume().intersect(x, d);
			} else {
				Console.root().warn("Unconstrained absorber", null);
			}
			
			if (dist < l) {
				l = dist;
				this.closest = s;
			}
		}
		
		if (fast && this.closest != null && this.fast && this.closest.fast) {
			this.delay = l;
			return 0.0;
		} else {
			return l;
		}
	}

	@Override
	public void setColor(double r, double g, double b) {
		this.rgb = new RGB(this.colorDepth, r, g, b);
	}

	@Override
	public Producer<PackedCollection> getValueAt(Producer<PackedCollection> point) {
		return GeneratedColorProducer.fromProducer(this, v(rgb));
	}

	@Override
	public Producer<PackedCollection> getNormalAt(Producer<PackedCollection> point) {
		Volume<?> v = getVolume(this.rclosest.absorber);
		if (v == null) return ZeroVector.getInstance();
		return v.getNormalAt(add(point, minus((Producer) v(rclosest.position))));
	}

	@Override
	public boolean getShadeBack() { return false; }

	@Override
	public boolean getShadeFront() { return true; }

	@Override
	public ShadableIntersection intersectAt(Producer<?> r) {
		return new ShadableIntersection(this, r, () -> (Evaluable<PackedCollection>) args -> {
			Ray ray = new Ray((PackedCollection) r.get().evaluate(args), 0);

			double dist = getDistance(ray.getOrigin(), ray.getDirection(), false, true);

			PackedCollection di = null;

			if (dist < Double.MAX_VALUE - 2 && dist > 0 && AbsorberHashSet.this.closest != null) {
				di = new PackedCollection(1);
				di.setMem(0, dist);
			}

			AbsorberHashSet.this.rclosest = AbsorberHashSet.this.closest;
			return di;
		});
	}

	@Override
	public Operator<PackedCollection> expect() {
		return null;
	}

	@Override
	public Producer<PackedCollection> shade(ShaderContext p) {
		return () -> new Evaluable<PackedCollection>() {

			@Override
			public PackedCollection evaluate(Object[] args) {
				if (rclosest == null) return new RGB(colorDepth, 0.0, 0.0, 0.0);

				if (rclosest.highlight) {
					if (Math.random() < 0.1)
						System.out.println("AbsorberHashSet: " + rclosest + " was highlighted.");
					return new RGB(colorDepth, 1.0, 1.0, 1.0);
				}

				Ray point = null;

				try {
					point = new Ray(p.getIntersection().get(0).get().evaluate(), 0);
				} catch (Exception ex) {
					System.err.println("AbsorberHashSet: " + ex.getMessage());
				}

				Vector po = point.getOrigin();
				Vector n = new Vector(getNormalAt(v(po)).get().evaluate(), 0);
				double[] norm = { n.getX(), n.getY(), n.getZ() };
				double d = n.dotProduct(new Vector(p.getLightDirection().get().evaluate(args), 0));
				boolean front = point.getOrigin().dotProduct(n) >= 0.0;

				if (d < 0) {
					if (getShadeBack())
						d = 0;
					else
						d = -d;
				}

				Volume vol = rclosest.getVolume();

				RGB r = rgb.clone(); // Absorption plane

				RGB b = null; // Indirect
				RGB c = null; // Raytraced

				if (vol != null && d >= 0) {
					double[] uv = vol.getSurfaceCoords(subtract(v(po), (Producer) v(rclosest.position)));
					c = rclosest.getColorAt(uv[0], uv[1], front, true, n); // Base color
					b = rclosest.getColorAt(uv[0], uv[1], front, false, n);
					double[] v = rclosest.incidence.getVector(uv[0], uv[1], front);
					v[0] *= -1;
					v[1] *= -1;
					v[2] *= -1;

					double[] vd = point.getDirection().getData();

					if (rclosest.brdf != null)
						b.multiplyBy(new Vector(vd).dotProduct(new Vector(rclosest.brdf.getSample(v, norm).get().evaluate(), 0)));
					else
						b.multiplyBy(new Vector(rclosest.exitance.getVector(uv[0], uv[1], front)).dotProduct(new Vector(vd)));
				}

				// Transmitted or reflected

				if (rclosest.brdf != null && p.getReflectionCount() < 2) {
					Vector viewer = point.getDirection();
					double[] v = {viewer.getX(), viewer.getY(), viewer.getZ()};
					Producer<PackedCollection> s = rclosest.brdf.getSample(v, norm);

					p.addReflection();

					Vector vpo = po.clone();
					Vector vs = new Vector(s.get().evaluate(), 0);

					LightingEngineAggregator l = new LightingEngineAggregator(v(new Ray(vpo, vs)),
							(Iterable<Curve<PackedCollection>>) p.getAllSurfaces(), p.getAllLights(), p);
					PackedCollection cl = l.evaluate(args);
					c = cl instanceof RGB ? (RGB) cl : new RGB(cl.toDouble(0), cl.toDouble(1), cl.toDouble(2));
					if (c != null)
						c.multiplyBy(p.getLight().getIntensity() * d);

//			if (c != null && c.length() > 0.05) System.out.println(c);
//
//			double dist = this.getDistance(po, s, false, true);
//
//			v: if (this.closest != null) {
//				vol = this.getVolume(this.closest.absorber);
//				if (vol == null) break v;
//				VectorMath.addMultiple(po, s, dist);
//				po = VectorMath.subtract(po, this.closest.position);
//
//				norm = vol.getNormal(po);
//				front = VectorMath.dot(norm, s) <= 0.0;
//
//				double uv[] = vol.getSurfaceCoords(po);
//
//				if (uv[0] < 0.0 || uv[0] > 1.0 || uv[1] < 0.0 || uv[1] > 1.0) {
//					if (Math.random() < 0.1)
//						System.out.println("AbsorberHashSet: Invalid surface coords from " +
//											vol + " (" + uv[0] + ", " + uv[1] + ")");
//				} else {
//					c = this.closest.getColorAt(uv[0], uv[1], front, false);
//				}
//			}
				}

				if (b != null) r.addTo(b);
				if (c != null) r.addTo(c);

				if (Math.random() < 0.0000001) {
					System.out.println("****************");
					System.out.println("AbsorberHashSet: " + rclosest + " " + closest);
					System.out.println("AbsorberHashSet: Colors = " + rgb + " " + b + " " + c );
					System.out.println("AbsorberHashSet: Final = " + r);
					System.out.println();
				}

				return r;
			}
		};
	}
}
