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

package org.almostrealism.time;

import org.almostrealism.algebra.TransformMatrix;
import org.almostrealism.algebra.Vector;
import org.almostrealism.physics.RigidBody;
import org.almostrealism.space.Gradient;
import org.almostrealism.space.Scene;
import org.almostrealism.space.ShadableSurface;
import org.almostrealism.util.StaticProducer;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;

public class Animation<T extends ShadableSurface> extends Scene<T> implements Runnable {
	private int itr;
	private double dt, fdt, vdt, totalTime;
	private boolean sleep, render;
	private boolean logState;

	private List<Function<RigidBody, Gradient<?>>> forces;

	private String dir;

	private Clock clock;
	private Temporal listener;

	public Animation() {
		this.forces = new ArrayList<>();
		this.clock = new Clock();
	}

	/**
	 * Adds a gradient force to the set of forces that will be evaluated each iteration.
	 *
	 * @param f  The Force object to add.
	 */
	public void addForce(Gradient f) { addForce(rb -> f); }

	/**
	 * Adds a {@link Function} that produces a gradient force to the set of forces that
	 * will be evaluated each iteration.
	 *
	 * @param f  The force {@link Function} to add.
	 */
	public void addForce(Function<RigidBody, Gradient<?>> f) { forces.add(f); }

	public void setIterations(int itr) { this.itr = itr; }

	public int getIterations() { return this.itr; }

	protected void setTickDuration(double dt) { this.dt = dt; }

	public double getTickDuration() { return dt; }

	/**
	 * Sets the number of frames to render per second.
	 *
	 * @param fps  The number of frames per second.
	 */
	public void setFPS(double fps) { this.fdt = 1.0 / fps; }

	public double getFrameDuration() { return this.fdt; }

	/**
	 * If the value of vdt is set to anything greater than 0.0, the time interval for each
	 * iteration will be set so that it is the value of vdt divided by the average velocity
	 * of the objects in the screen.
	 */
	public void setVDT(double vdt) { this.vdt = vdt; }

	/** @return  The total time in seconds since the start of the simulation. */
	public double getTime() { return this.totalTime; }

	public void setClock(Clock c) { this.clock = c; }
	public Clock getClock() { return this.clock; }

	/** @return  A clone of the superclass of this Simulation object. */
	public Scene getScene() { return (Scene) super.clone(); }

	/**
	 * Sets the sleep each frame flag.
	 *
	 * @param sleep  True if the simulation thread should wait the actual time between frames,
	 *               false otherwise.
	 */
	public void setSleepEachFrame(boolean sleep) { this.sleep = sleep; }

	public boolean getSleepEachFrame() { return this.sleep; }

	/**
	 * @return  True if the simulation will render an image for each frame, false otherwise.
	 */
	public boolean getRenderEachFrame() { return render; }

	/**
	 * @param render  True if the simulation should render an image for each frame, false otherwise.
	 */
	public void setRenderEachFrame(boolean render) { this.render = render; }

	public double getAverageLinearVelocity() {
		double total = 0.0;

		for (ShadableSurface s : this) {
			if (s instanceof RigidBody)
				total += ((RigidBody) s).getState().getLinearVelocity().length();
		}

		return total / size();
	}

	public void setOutputDirectory(String dir) { this.dir = dir; }

	public String getOutputDirectory() { return this.dir; }

	/**
	 * @return  True if the simulation will output a properties file for each frame, false otherwise.
	 */
	public boolean getLogEachFrame() { return this.logState; }

	/**
	 * @param log  True if the simulation should output a properties file for each frame, false otherwise.
	 */
	public void setLogEachFrame(boolean log) { this.logState = log; }

	public void setListener(Temporal l) { this.listener = l; }

	/** Runs the animation. */
	@Override
	public void run() {
		System.out.println("Starting simulation (" + this.itr + "): dt = " + this.dt + "  fdt = " + this.fdt);

		String instance = "0";

		try {
			Properties pr = new Properties();
			pr.load(new FileInputStream(this.dir == null ? "instance" : (this.dir + "instance")));
			instance = pr.getProperty("instance");
		} catch (FileNotFoundException fnf) {
			System.out.println("Instance file not found. Zero will be used.");
		} catch (IOException ioe) {
			System.out.println("Error reading instance file. Zero will be used.");
		}

		int iterationsPerFrame = (int) (this.fdt / this.dt);

		for (int i = 0; i < this.itr; i++) {
			try {
				if (getSleepEachFrame() && i * this.dt % this.fdt == 0) Thread.sleep((int)(this.fdt * 1000));
			} catch (InterruptedException ie) {
				ie.printStackTrace();
			}

			for (ShadableSurface s : this)
				if (s instanceof RigidBody) ((RigidBody) s).getState().update(dt);

			// TODO  Keep this array from iteration to iteration
			boolean intersected[][] = new boolean[size()][size()];

			s: for (ShadableSurface s : this) {
				if (s instanceof  RigidBody == false) continue s;

				Vector g = new Vector(0.0, 0.0, 0.0);

				for (Function<RigidBody, Gradient<?>> f : forces) {
					Gradient<?> grad = f.apply((RigidBody) s);

					if (grad != null) {
						g.addTo(grad.getNormalAt(StaticProducer.of(((RigidBody) s).getState().getLocation()))
								.evaluate(new Object[] { ((RigidBody) s).getState() }));
					}
				}

				((RigidBody) s).getState().setForce(g);
			}

			j: for (int j = 0; j < size(); j++) {
				if (get(j) instanceof RigidBody == false) continue j;

				k: for (int k = 0; k < size(); k++) {
					if (k == j) continue k;
					if (intersected[j][k]) continue k;
					if (get(k) instanceof RigidBody == false) continue k;

					RigidBody a = (RigidBody) get(j);
					RigidBody b = (RigidBody) get(k);

					Vector intersect[] = a.intersect(b);

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

			double microseconds = totalTime * 1000 * 1000;

			// Move clock forward until it reaches the total time elapsed
//			while (clock.getTime() < microseconds) {
//				clock.tick().run();
//			}

			if ((i + 1) % iterationsPerFrame == 0 || this.vdt > 0.0) {
				if (this.listener != null) this.listener.tick().run();

				try {
					long time = System.currentTimeMillis();
					Properties p = this.generateProperties();

					System.out.println("Writing simulation state: " + time);

					String head = "Simulation state for instance " + instance + ": " + this.totalTime;

					if (this.logState)
						p.store(new FileOutputStream(dir == null ? (time + ".state") : (dir + time + ".state")), head);
					p.store(new FileOutputStream(dir == null ? "latest.state" : (dir + "latest.state")), head);
				} catch (IOException ioe) {
					System.out.println("IO error writing state " + i * this.dt);
				}

				if (this.render) this.writeImage(i, instance);
			}

			if (this.vdt > 0.0) {
				double a = this.getAverageLinearVelocity();

				if (a == 0.0)
					this.dt = this.vdt;
				else
					this.dt = this.vdt / this.getAverageLinearVelocity();

				System.out.println("dt = " + this.dt);
			}
		}

		this.writeEncodeScript(instance);
	}

	public Properties generateProperties() {
		Properties p = new Properties();

		p.setProperty("simulation.dt", String.valueOf(this.dt));
		p.setProperty("simulation.time", String.valueOf(this.totalTime));
		p.setProperty("simulation.fdt", String.valueOf(this.fdt));
		p.setProperty("simulation.vdt", String.valueOf(this.vdt));

		return p;
	}

	public void loadProperties(Properties p) {
		this.fdt = Double.parseDouble(p.getProperty("simulation.fdt", "1.0"));
		this.vdt = Double.parseDouble(p.getProperty("simulation.vdt", "-1.0"));
		this.totalTime = Double.parseDouble(p.getProperty("simulation.time", "0.0"));

		dt = Double.parseDouble(p.getProperty("simulation.dt", "1"));
	}

	public void writeImage(int i, String instance) { }

	public void writeEncodeScript(String instance) { }
}
