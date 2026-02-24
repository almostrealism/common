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
	public static final boolean enableFrontBackDetection = false;

	@Override
	public Operator<PackedCollection> get() {
		return null;
	}

	private interface SetListener { void noteUpdate(); }
	
	public static class StoredItem {
		public Absorber absorber;
		private Volume volume;
		public Producer<PackedCollection> position;
		boolean checked, fast, highlight;
		
		SphericalProbabilityDistribution brdf;
		
		private BufferListener listener;
		private ColorBuffer buf;
		private AveragedVectorMap2D incidence, exitance;
		private int[] wh;

		public StoredItem() { }

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

		public void setBufferListener(BufferListener listener) { this.listener = listener; }
		
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
		
		public int[] getColorBufferDimensions() {
			if (this.buf instanceof ArrayColorBuffer)
				return ((ArrayColorBuffer) this.buf).getColorBufferDimensions();
			else if (this.wh != null)
				return this.wh;
			else
				return new int[2];
		}
		
		public double getColorBufferScale() {
			if (this.buf == null)
				return 0.0;
			else
				return this.buf.getScale();
		}
		
		public RGB getColorAt(double u, double v, boolean front, boolean direct) {
			return this.getColorAt(u, v, front, direct, null);
		}
		
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
		
		public void addColor(double u, double v, boolean front, RGB c) {
			this.buf.addColor(u, v, front, c);
			if (this.listener != null)
				this.listener.updateColorBuffer(u, v, this.getVolume(), this.buf, front);
		}
		
		public void addIncidence(double u, double v, Producer<PackedCollection> e, boolean front) {
			this.incidence.addVector(u, v, e, front);
			if (this.listener != null)
				this.listener.updateIncidenceBuffer(u, v, this.getVolume(), this.incidence, front);
		}

		public void addExitance(double u, double v, Producer<PackedCollection> e, boolean front) {
			this.exitance.addVector(u, v, e, front);
			if (this.listener != null)
				this.listener.updateExitanceBuffer(u, v, this.getVolume(), this.exitance, front);
		}
		
		public Volume getVolume() {
			if (this.volume == null)
				this.volume = AbsorberHashSet.getVolume(this.absorber);
			return this.volume;
		}
		
		public String toString() { return "StoredItem[" + this.absorber + "]"; }
	}
	
	public static final int DEFAULT_ORDER = 1;
	public static final int RANDOM_ORDER = 2;
	public static final int POPULAR_ORDER = 4;
	public static boolean solidColorBuffer = false, triangularColorBuffer = false;
	
	public int colorDepth = 192; //  48; TODO  Re-enable 48 bit color when available
	
	private Clock clock;
	private PotentialMap map;
	private StoredItem emitter, rclosest, closest, lastAdded;
	private BufferListener listener;
	
	private final Set sList;
	
	private RGB rgb = new RGB(this.colorDepth, 0.0, 0.0, 0.0);
	
	private int order = 1;
	private boolean fast = true;
	private double delay, e;
	private double spreadAngle;
	private int spreadCount;
	
	private double max = Double.MAX_VALUE, bound = Double.MAX_VALUE;
	private boolean cleared = false;
	
	private Iterator items;
	private Thread itemsUser;
	private boolean itemsNow, itemsEnabled = false;
	
	public AbsorberHashSet() {
		this.sList = new HashSet();
	}

	@Override
	public int addAbsorber(Absorber a, Producer x) {
		return this.addAbsorber(a, x, a instanceof Fast);
	}

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

	public int removeAbsorber(Absorber a) {
		Iterator itr = this.iterator();
		
		while (itr.hasNext()) {
			StoredItem it = (StoredItem) itr.next();
			if (it.equals(a)) this.remove(it);
			this.notifySetListeners();
		}
		
		return this.size();
	}
	
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
	
	public void clearColorBuffers() {
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			if (n.buf != null) n.buf.clear();
		}
	}
	
	public void loadColorBuffers(Factory<Scene<ShadableSurface>> loader) throws IOException {
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			if (n.buf != null) this.loadColorBuffer(loader, n);
		}
	}
	
	public void loadColorBuffer(Factory<Scene<ShadableSurface>> loader, StoredItem it) throws IOException {
		// TODO
	}
	
	public void setBRDF(SphericalProbabilityDistribution brdf) {
		this.setBRDF(this.lastAdded.absorber, brdf);
	}

	// TODO  This should store the dimensions, and apply them to new absorbers that are added
	public void setColorBufferDimensions(int w, int h, double m) {
		this.setColorBufferDimensions(this.lastAdded.absorber, w, h, m);
	}
	
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
	
	public void setBufferListener(BufferListener l) {
		this.listener = l;
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			StoredItem n = (StoredItem) itr.next();
			n.setBufferListener(this.listener);
		}
	}
	
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
			double d = this.getDistance(x.add(p.multiply(this.delay)), p, false);
			
			if (a instanceof Fast) {
				double t = this.delay / PhysicalConstants.C;
				((Fast) a).setAbsorbDelay(t);
				((Fast) a).setOrigPosition(nx.toArray());
			}
			
			Absorber b = null;
			if (this.closest != null) b = this.closest.absorber;
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
	
	protected static Volume getVolume(Absorber a) {
		Volume v = null;
		
		if (a instanceof VolumeAbsorber)
			v = ((VolumeAbsorber)a).getVolume();
		else if (a instanceof Volume)
			v = (Volume) a;
		
		return v;
	}
	
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
			
			public boolean hasNext() { return this.itr.hasNext(); }
			public Object next() { return ((StoredItem)this.itr.next()).absorber; }
			public void remove() { this.itr.remove(); }
		};
		
		return itr;
	}
	
	public void notifySetListeners() {
		Iterator itr = this.sList.iterator();
		while (itr.hasNext()) ((SetListener)itr.next()).noteUpdate();
	}
	
	public boolean addSetListener(SetListener l) {
		this.sList.add(l);
		return true;
	}
	
	public Iterator iterator() { return this.iterator(false); }
	
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
					public void noteUpdate() { myNoteUpdate(); }
				};
				
				private int index = 0;
				private StoredItem[] data = AbsorberHashSet.this.toArray(new StoredItem[0]);
				private final boolean b = AbsorberHashSet.this.addSetListener(l);
				
				public boolean hasNext() {
					if (this.index >= data.length) {
						this.index = 0;
						AbsorberHashSet.this.itemsUser = null;
						return false;
					} else {
						return true;
					}
				}
				
				public Object next() { return this.data[this.index++]; }
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
			
			public boolean hasNext() { return index < itrs.length; }
			public Object next() { return itrs[this.index++]; }
			public void remove() { }
		};
	}
	
	protected void clearChecked() {
		if (this.cleared) return;
		
		Iterator itr = this.iterator(false);
		
		while (itr.hasNext()) {
			((StoredItem)itr.next()).checked = false;
		}
		
		this.cleared = true;
	}

	public void setBound(double bound) {
		this.bound = bound;
		this.max = Math.min(this.max, 2*this.bound);
	}
	
	public double getBound() { return this.bound; }

	public BoundingSolid calculateBoundingSolid() { throw new RuntimeException("getBoundingSolid not implemented"); }

	public double getDistance(Vector p, Vector d) {
		return this.getDistance(p, d, true, false);
	}
	
	public double getDistance(Vector p, Vector d, boolean fast) {
		return this.getDistance(p, d, fast, false);
	}
	
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
					ex.printStackTrace();
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
