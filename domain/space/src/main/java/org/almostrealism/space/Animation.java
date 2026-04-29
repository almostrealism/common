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

package org.almostrealism.space;

import org.almostrealism.algebra.Gradient;
import org.almostrealism.algebra.Vector;
import org.almostrealism.algebra.VectorFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.io.ConsoleFeatures;
import org.almostrealism.geometry.TransformMatrix;
import org.almostrealism.physics.Clock;
import org.almostrealism.physics.RigidBody;
import org.almostrealism.time.Temporal;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

/**
 * A physics-based animation engine that extends {@link Scene} to simulate rigid body
 * dynamics and render frames over a series of time steps.
 *
 * <p>The animation loop advances each rigid body's state using a configurable time step
 * ({@code dt}), evaluates applied forces (gravity, custom fields, etc.), detects and
 * resolves collisions using impulse-based collision response, and optionally renders
 * an image and/or writes a properties snapshot at each frame.
 *
 * <p>The number of physics iterations ({@code itr}) and the simulation time step ({@code dt})
 * are set independently from the frame rate ({@code fdt}). Multiple physics steps can be
 * executed per rendered frame.
 *
 * <p>If a velocity-dependent time step ({@code vdt}) is configured, the step size is
 * automatically adjusted each frame to keep the average object speed below a target value.
 *
 * @param <T> the type of surface objects in this animation, must extend {@link ShadableSurface}
 * @author Michael Murray
 * @see Scene
 * @see RigidBody
 */
public class Animation<T extends ShadableSurface> extends Scene<T> implements Runnable, VectorFeatures, ConsoleFeatures {
	/** Number of physics iterations to execute. */
	private int itr;

	/** Physics time step per iteration (seconds). */
	private double dt;

	/** Duration of each rendered frame (seconds); inverse of frames per second. */
	private double fdt;

	/** Velocity-dependent time step divisor; if greater than 0.0, overrides {@code dt} each frame. */
	private double vdt;

	/** Total simulated time elapsed since the start of the animation (seconds). */
	private double totalTime;

	/** When true, the animation thread sleeps for the actual frame duration between frames. */
	private boolean sleep;

	/** When true, an image is rendered and written at each frame. */
	private boolean render;

	/** When true, a properties file with simulation state is written at each frame. */
	private boolean logState;

	/** Forces applied to rigid bodies during each iteration. */
	private final List<Function<RigidBody, Gradient<?>>> forces;

	/** Directory path for output files (state files, rendered images, encode scripts). */
	private String dir;

	/** Physics clock tracking simulated time. */
	private Clock clock;

	/** Optional listener notified at each rendered frame tick. */
	private Temporal listener;

	/**
	 * Constructs a new {@link Animation} with an empty scene, an initialized physics clock,
	 * and no forces, lights, or camera.
	 */
	public Animation() {
		this.forces = new ArrayList<>();
		this.clock = new Clock();
	}

	/**
	 * Adds a gradient force to the set of forces that will be evaluated each iteration.
	 *
	 * @param f  The Force object to add.
	 */
	public void addForce(Gradient f) { addForce((Function<RigidBody, Gradient<?>>) rb -> f); }

	/**
	 * Adds a {@link Function} that produces a gradient force to the set of forces that
	 * will be evaluated each iteration.
	 *
	 * @param f  The force {@link Function} to add.
	 */
	public void addForce(Function<RigidBody, Gradient<?>> f) { forces.add(f); }

	/**
	 * Sets the total number of physics iterations to execute when the animation is run.
	 *
	 * @param itr the number of iterations
	 */
	public void setIterations(int itr) { this.itr = itr; }

	/**
	 * Returns the total number of physics iterations to execute.
	 *
	 * @return the number of iterations
	 */
	public int getIterations() { return this.itr; }

	/**
	 * Sets the physics time step duration.
	 *
	 * @param dt the duration of each physics tick in seconds
	 */
	protected void setTickDuration(double dt) { this.dt = dt; }

	/**
	 * Returns the physics time step duration.
	 *
	 * @return the duration of each physics tick in seconds
	 */
	public double getTickDuration() { return dt; }

	/**
	 * Sets the number of frames to render per second.
	 *
	 * @param fps  The number of frames per second.
	 */
	public void setFPS(double fps) { this.fdt = 1.0 / fps; }

	/**
	 * Returns the duration of each rendered frame in seconds.
	 *
	 * @return the frame duration in seconds
	 */
	public double getFrameDuration() { return this.fdt; }

	/**
	 * If the value of vdt is set to anything greater than 0.0, the time interval for each
	 * iteration will be set so that it is the value of vdt divided by the average velocity
	 * of the objects in the screen.
	 */
	public void setVDT(double vdt) { this.vdt = vdt; }

	/**
	 * Returns the total time in seconds since the start of the simulation.
	 *
	 * @return  The total time in seconds since the start of the simulation.
	 */
	public double getTime() { return this.totalTime; }

	/**
	 * Sets the physics clock used to track simulated time.
	 *
	 * @param c the clock to use
	 */
	public void setClock(Clock c) { this.clock = c; }

	/**
	 * Returns the physics clock tracking simulated time.
	 *
	 * @return the physics clock
	 */
	public Clock getClock() { return this.clock; }

	/** Delegates to {@link Scene#clone()}. */
	public Scene getScene() { return (Scene) super.clone(); }

	/**
	 * Sets the sleep each frame flag.
	 *
	 * @param sleep  True if the simulation thread should wait the actual time between frames,
	 *               false otherwise.
	 */
	public void setSleepEachFrame(boolean sleep) { this.sleep = sleep; }

	/**
	 * Returns whether the animation thread sleeps for the real frame duration between frames.
	 *
	 * @return true if the thread sleeps, false otherwise
	 */
	public boolean getSleepEachFrame() { return this.sleep; }

	/**
	 * Returns {@code true} if the simulation will render an image for each frame.
	 *
	 * @return  True if the simulation will render an image for each frame, false otherwise.
	 */
	public boolean getRenderEachFrame() { return render; }

	/**
	 * Sets whether the simulation should render an image for each frame.
	 *
	 * @param render  True if the simulation should render an image for each frame, false otherwise.
	 */
	public void setRenderEachFrame(boolean render) { this.render = render; }

	/**
	 * Computes the average linear velocity magnitude over all {@link RigidBody} objects in the scene.
	 *
	 * @return the mean linear velocity magnitude, or 0.0 if the scene is empty
	 */
	public double getAverageLinearVelocity() {
		double total = 0.0;

		for (ShadableSurface s : this) {
			if (s instanceof RigidBody)
				total += ((RigidBody) s).getState().getLinearVelocity().length();
		}

		return total / size();
	}

	/**
	 * Sets the output directory for state files, rendered images, and encode scripts.
	 *
	 * @param dir the directory path (with trailing separator), or null to use the working directory
	 */
	public void setOutputDirectory(String dir) { this.dir = dir; }

	/**
	 * Returns the output directory for state files and rendered images.
	 *
	 * @return the directory path, or null if not set
	 */
	public String getOutputDirectory() { return this.dir; }

	/**
	 * Returns {@code true} if the simulation will output a properties file for each frame.
	 *
	 * @return  True if the simulation will output a properties file for each frame, false otherwise.
	 */
	public boolean getLogEachFrame() { return this.logState; }

	/**
	 * Sets whether the simulation should output a properties file for each frame.
	 *
	 * @param log  True if the simulation should output a properties file for each frame, false otherwise.
	 */
	public void setLogEachFrame(boolean log) { this.logState = log; }

	/**
	 * Sets a listener that is notified at each rendered frame during the animation.
	 *
	 * @param l the temporal listener to notify
	 */
	public void setListener(Temporal l) { this.listener = l; }

	/** Runs the animation. */
	@Override
	public void run() {
		log("Starting simulation (" + this.itr + "): dt = " + this.dt + "  fdt = " + this.fdt);

		String instance = "0";

		try {
			Properties pr = new Properties();
			pr.load(new FileInputStream(this.dir == null ? "instance" : (this.dir + "instance")));
			instance = pr.getProperty("instance");
		} catch (FileNotFoundException fnf) {
			log("Instance file not found. Zero will be used.");
		} catch (IOException ioe) {
			log("Error reading instance file. Zero will be used.");
		}

		int iterationsPerFrame = (int) (this.fdt / this.dt);

		for (int i = 0; i < this.itr; i++) {
			try {
				if (getSleepEachFrame() && i * this.dt % this.fdt == 0) Thread.sleep((int)(this.fdt * 1000));
			} catch (InterruptedException ie) {
				warn(ie.getMessage(), ie);
			}

			for (ShadableSurface s : this)
				if (s instanceof RigidBody) ((RigidBody) s).getState().update(dt);

			// TODO  Keep this array from iteration to iteration
			boolean[][] intersected = new boolean[size()][size()];

			s: for (ShadableSurface s : this) {
				if (!(s instanceof RigidBody)) continue s;

				Vector g = new Vector(0.0, 0.0, 0.0);

				for (Function<RigidBody, Gradient<?>> f : forces) {
					Gradient<?> grad = f.apply((RigidBody) s);

					if (grad != null) {
						g.addTo((Vector) grad.getNormalAt(v(((RigidBody) s).getState().getLocation())).get()
								.evaluate(new Object[] { ((RigidBody) s).getState() }));
					}
				}

				((RigidBody) s).getState().setForce(g);
			}

			j: for (int j = 0; j < size(); j++) {
				if (!(get(j) instanceof RigidBody)) continue j;

				k: for (int k = 0; k < size(); k++) {
					if (k == j) continue k;
					if (intersected[j][k]) continue k;
					if (!(get(k) instanceof RigidBody)) continue k;

					RigidBody a = (RigidBody) get(j);
					RigidBody b = (RigidBody) get(k);

					Vector[] intersect = a.intersect(b);

					if (intersect.length >= 2) {
						intersected[j][k] = true;
						intersected[k][j] = true;

						Vector p = intersect[0];
						Vector n = intersect[1];

						// System.out.println(this.totalTime + ": Intersection (" + a + " / " +
						//					b + "): " + p.toString() + " / " + n.toString());

						Vector pa = a.getState().getLinearVelocity().add(a.getState().getAngularVelocity().crossProduct(p.subtract(a.getState().getLocation())));
						Vector pb = b.getState().getLinearVelocity().add(b.getState().getAngularVelocity().crossProduct(p.subtract(b.getState().getLocation())));
						double e = (a.getState().getRestitution() + b.getState().getRestitution()) / 2.0;
						double vr = n.dotProduct(pa.subtract(pb));

						if (vr >= 0) continue k;

						Vector ra = p.subtract(a.getState().getLocation());
						Vector rb = p.subtract(b.getState().getLocation());

						TransformMatrix ita = a.getState().getInertia().getInverse();
						TransformMatrix itb = b.getState().getInertia().getInverse();

						double l = (-(1.0 + e) * vr) / (1 / a.getState().getMass() + 1 / b.getState().getMass() +
								n.dotProduct(ita.transformAsOffset(ra.crossProduct(n)).crossProduct(ra)) +
								n.dotProduct(itb.transformAsOffset(rb.crossProduct(n)).crossProduct(rb)));

						Vector li = n.multiply(l);

						a.getState().linearImpulse(li);
						b.getState().linearImpulse(li.minus());

						a.getState().angularImpulse(p.subtract(a.getState().getLocation()).crossProduct(li));
						b.getState().angularImpulse(p.subtract(b.getState().getLocation()).crossProduct(li));
					}
				}
			}

			this.totalTime = this.totalTime + this.dt;

			// Move clock forward until it reaches the total time elapsed
//			while (clock.getTime() < totalTime * 1000 * 1000) {
//				clock.tick().run();
//			}

			if ((i + 1) % iterationsPerFrame == 0 || this.vdt > 0.0) {
				if (this.listener != null) this.listener.tick().get().run();

				try {
					long time = System.currentTimeMillis();
					Properties p = this.generateProperties();

					log("Writing simulation state: " + time);

					String head = "Simulation state for instance " + instance + ": " + this.totalTime;

					if (this.logState)
						p.store(new FileOutputStream(dir == null ? (time + ".state") : (dir + time + ".state")), head);
					p.store(new FileOutputStream(dir == null ? "latest.state" : (dir + "latest.state")), head);
				} catch (IOException ioe) {
					log("IO error writing state " + i * this.dt);
				}

				if (this.render) this.writeImage(i, instance);
			}

			if (this.vdt > 0.0) {
				double a = this.getAverageLinearVelocity();

				if (a == 0.0)
					this.dt = this.vdt;
				else
					this.dt = this.vdt / this.getAverageLinearVelocity();

				log("dt = " + this.dt);
			}
		}

		this.writeEncodeScript(instance);
	}

	/**
	 * Generates a {@link Properties} object containing the current simulation state,
	 * including time step, total elapsed time, frame duration, and velocity time step.
	 *
	 * @return a properties snapshot of the simulation state
	 */
	public Properties generateProperties() {
		Properties p = new Properties();

		p.setProperty("simulation.dt", String.valueOf(this.dt));
		p.setProperty("simulation.time", String.valueOf(this.totalTime));
		p.setProperty("simulation.fdt", String.valueOf(this.fdt));
		p.setProperty("simulation.vdt", String.valueOf(this.vdt));

		return p;
	}

	/**
	 * Restores simulation state from a previously saved {@link Properties} snapshot.
	 *
	 * @param p the properties object containing simulation state values
	 */
	public void loadProperties(Properties p) {
		this.fdt = Double.parseDouble(p.getProperty("simulation.fdt", "1.0"));
		this.vdt = Double.parseDouble(p.getProperty("simulation.vdt", "-1.0"));
		this.totalTime = Double.parseDouble(p.getProperty("simulation.time", "0.0"));

		dt = Double.parseDouble(p.getProperty("simulation.dt", "1"));
	}

	/**
	 * Writes a rendered image for the specified frame to the output directory.
	 * Subclasses override this method to implement actual image rendering.
	 *
	 * @param i        the iteration index
	 * @param instance the simulation instance identifier
	 */
	public void writeImage(int i, String instance) { }

	/**
	 * Writes a script that can be used to encode the rendered frames into a video.
	 * Subclasses override this method to produce the actual encode script.
	 *
	 * @param instance the simulation instance identifier
	 */
	public void writeEncodeScript(String instance) { }
}
