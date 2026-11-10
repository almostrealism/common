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

package org.almostrealism.render;

import org.almostrealism.algebra.Vector;
import org.almostrealism.color.BlendingShader;
import org.almostrealism.color.DiffuseShader;
import org.almostrealism.color.DirectionalAmbientLight;
import org.almostrealism.color.Light;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.color.ShadableSurface;
import org.almostrealism.physics.RigidBody;
import org.almostrealism.physics.RigidBodyStateShader;
import org.almostrealism.primitives.RigidPlane;
import org.almostrealism.primitives.RigidSphere;
import org.almostrealism.primitives.SphericalLight;
import org.almostrealism.projection.PinholeCamera;
import org.almostrealism.projection.ThinLensCamera;
import org.almostrealism.raytrace.FogParameters;
import org.almostrealism.raytrace.RenderParameters;
import org.almostrealism.rayshade.ReflectionShader;
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.space.Animation;
import org.almostrealism.space.StandardLightingRigs;
import org.almostrealism.texture.ImageCanvas;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * An {@link Animation} that ray traces each frame with {@link RayTracedScene} and writes it to
 * the output directory as a JPEG, and writes a script that encodes the frames as a video.
 *
 * <p>Its state can be stored as and restored from {@link Properties}: the camera, and the rigid
 * spheres and planes of the scene with their lights, shaders and physical state. Each body is
 * stored under the keys {@code bodies.<index>.*}.</p>
 *
 * @param <T> the type of surface in the animation
 * @author Michael Murray
 */
public class RayTracedAnimation<T extends ShadableSurface> extends Animation<T> implements RGBFeatures {
	/** The width of each rendered frame, in pixels. */
	private int imageWidth;

	/** The height of each rendered frame, in pixels. */
	private int imageHeight;

	/** The most recently rendered frame, or null if no frame has been rendered. */
	private RGB[][] image;

	/** The frame files written so far, in order, for the encode script. */
	private final List<File> frameFiles;

	/** Creates an animation with no frames rendered and no image dimensions set. */
	public RayTracedAnimation() {
		this.frameFiles = new ArrayList<>();
	}

	/**
	 * Sets the dimensions of each rendered frame.
	 *
	 * @param w the width in pixels
	 * @param h the height in pixels
	 */
	public void setImageDimensions(int w, int h) {
		this.imageWidth = w;
		this.imageHeight = h;
	}

	/**
	 * Returns the most recently rendered frame.
	 *
	 * @return the frame's pixels, indexed by x then y, or null if no frame has been rendered
	 */
	public RGB[][] getImage() { return image; }

	/**
	 * Renders the current state of the scene and writes it to the output directory as
	 * {@code frame_<instance>.<i>.jpeg}. A frame that cannot be written is reported as a
	 * warning, and the animation continues.
	 *
	 * @param i        the iteration number of the frame
	 * @param instance the instance identifier of the animation
	 */
	@Override
	public void writeImage(int i, String instance) {
		File f = outputFile("frame_" + instance + "." + i + ".jpeg");

		RenderParameters p = new RenderParameters(imageWidth, imageHeight, 1, 1);
		image = new RayTracedScene(this, new FogParameters(), p).realize(p).get().evaluate();

		try {
			ImageCanvas.encodeImageFile(image, f, ImageCanvas.JPEGEncoding);
			frameFiles.add(f);
			log("Wrote frame " + i + "/" + getIterations() + ": " + f);
		} catch (IOException e) {
			warn("Could not write frame " + i + " to " + f, e);
		}
	}

	/**
	 * Writes {@code encode.sh} to the output directory: a {@code mencoder} command that encodes
	 * the frames written so far as an MPEG-4 video at this animation's frame rate.
	 *
	 * @param instance the instance identifier of the animation
	 */
	@Override
	public void writeEncodeScript(String instance) {
		File script = outputFile("encode.sh");

		List<String> names = new ArrayList<>();
		for (File f : frameFiles) names.add(f.getName());

		int fps = Math.max(1, (int) Math.round(1.0 / getFrameDuration()));

		try (PrintWriter out = new PrintWriter(new FileWriter(script))) {
			out.println("#!/bin/sh");
			out.println("mencoder mf://" + String.join(",", names) +
					" -mf w=" + imageWidth + ":h=" + imageHeight + ":fps=" + fps +
					":type=jpg -ovc lavc -lavcopts vcodec=mpeg4 -oac copy -o output.avi");
		} catch (IOException e) {
			warn("Could not write encode script " + script, e);
		}
	}

	/**
	 * Returns the file with the given name in the output directory, or in the working directory
	 * if no output directory is set.
	 *
	 * @param name the file name
	 * @return the file
	 */
	private File outputFile(String name) {
		String dir = getOutputDirectory();
		return dir == null ? new File(name) : new File(dir, name);
	}

	/**
	 * Returns the simulation state of {@link Animation#generateProperties()} together with the
	 * camera and every rigid sphere and plane of the scene. The camera must be a
	 * {@link PinholeCamera}.
	 *
	 * @return the properties describing the current state
	 */
	@Override
	public Properties generateProperties() {
		Properties p = super.generateProperties();

		p.setProperty("bodies.length", String.valueOf(size()));

		PinholeCamera camera = (PinholeCamera) getCamera();
		setVector(p, "camera.loc", camera.getLocation());
		setVector(p, "camera.view", camera.getViewingDirection());
		p.setProperty("camera.foclen", String.valueOf(camera.getFocalLength()));
		p.setProperty("camera.proj.w", String.valueOf(camera.getProjectionWidth()));
		p.setProperty("camera.proj.h", String.valueOf(camera.getProjectionHeight()));

		for (int i = 0; i < size(); i++) {
			T body = get(i);

			if (body instanceof RigidSphere s) {
				p.setProperty(key(i, "type"), "sphere");
				p.setProperty(key(i, "size"), String.valueOf(s.getSize()));
				storeLight(p, i, s.getLight());
			} else if (body instanceof RigidPlane s) {
				p.setProperty(key(i, "type"), "plane");
				p.setProperty(key(i, "size"), String.valueOf(s.getSize()));
			}

			if (body instanceof AbstractSurface s) storeShaders(p, i, s);

			if (body instanceof RigidBody b) {
				RigidBody.State state = b.getState();
				p.setProperty(key(i, "mass"), String.valueOf(state.getMass()));
				setVector(p, key(i, "loc"), state.getLocation());
				setVector(p, key(i, "rot"), state.getRotation());
				setVector(p, key(i, "lv"), state.getLinearVelocity());
				setVector(p, key(i, "av"), state.getAngularVelocity());
			}
		}

		return p;
	}

	/**
	 * Stores the light of a rigid sphere, if it has one.
	 *
	 * @param p     the properties to write
	 * @param i     the index of the body
	 * @param light the sphere's light, or null if it is not lit
	 */
	private void storeLight(Properties p, int i, SphericalLight light) {
		if (light == null) return;

		double[] at = light.getAttenuationCoefficients();
		p.setProperty(key(i, "light.on"), "true");
		p.setProperty(key(i, "light.intensity"), String.valueOf(light.getIntensity()));
		p.setProperty(key(i, "light.samples"), String.valueOf(light.getSampleCount()));
		p.setProperty(key(i, "light.ata"), String.valueOf(at[0]));
		p.setProperty(key(i, "light.atb"), String.valueOf(at[1]));
		p.setProperty(key(i, "light.atc"), String.valueOf(at[2]));
	}

	/**
	 * Stores the rigid-body state shading and the reflectivity of a surface, where it has them.
	 *
	 * @param p       the properties to write
	 * @param i       the index of the body
	 * @param surface the surface
	 */
	private void storeShaders(Properties p, int i, AbstractSurface surface) {
		for (Object sh : surface.getShaderSet()) {
			if (sh instanceof RigidBodyStateShader rs) {
				if (rs.getType() == RigidBodyStateShader.FORCE) {
					p.setProperty(key(i, "shade.rbstate"), "force");
				} else if (rs.getType() == RigidBodyStateShader.VELOCITY) {
					p.setProperty(key(i, "shade.rbstate"), "velocity");
				}
			} else if (sh instanceof ReflectionShader rs) {
				p.setProperty(key(i, "shade.ref"), String.valueOf(rs.getReflectivity()));
			}
		}
	}

	/**
	 * Restores the simulation state of {@link Animation#loadProperties(Properties)}, and replaces
	 * the camera with a {@link ThinLensCamera} and adds the rigid bodies described by the given
	 * properties. The image height is corrected to match the camera's projection aspect ratio.
	 *
	 * @param p the properties to read
	 */
	@Override
	public void loadProperties(Properties p) {
		super.loadProperties(p);

		ThinLensCamera c = new ThinLensCamera();
		c.setLocation(getVector(p, "camera.loc", new Vector(0.0, 0.0, 0.0)));
		c.setViewingDirection(getVector(p, "camera.view", new Vector(0.0, 0.0, 1.0)));
		c.setUpDirection(new Vector(0.0, 1.0, 0.0));

		if (p.getProperty("camera.foc") != null) c.setFocus(getDouble(p, "camera.foc", 0.0));
		if (p.getProperty("camera.foclen") != null) c.setFocalLength(getDouble(p, "camera.foclen", 0.0));
		if (p.getProperty("camera.lensradius") != null) c.setLensRadius(getDouble(p, "camera.lensradius", 0.0));
		if (p.getProperty("camera.proj.w") != null) c.setProjectionWidth(getDouble(p, "camera.proj.w", 0.0));
		if (p.getProperty("camera.proj.h") != null) c.setProjectionHeight(getDouble(p, "camera.proj.h", 0.0));

		c.updateUVW();

		int expectedHeight = (int) (imageWidth * c.getProjectionHeight() / c.getProjectionWidth());
		if (imageHeight != expectedHeight) {
			warn("Correcting image height to " + expectedHeight + " to match the camera projection");
			imageHeight = expectedHeight;
		}

		List<RigidBody> bodies = new ArrayList<>();
		int len = Integer.parseInt(p.getProperty("bodies.length", "0"));

		for (int i = 0; i < len; i++) {
			RigidBody b = loadBody(p, i);
			if (b != null) bodies.add(b);
		}

		double ambient = getDouble(p, "light.ambient", 0.0);
		if (ambient != 0.0) {
			addLight(new DirectionalAmbientLight(ambient,
					new RGB(1.0, 1.0, 1.0), new Vector(0.0, -1.0, -0.3)));
		}

		if (Boolean.parseBoolean(p.getProperty("light.default.rig", "false"))) {
			StandardLightingRigs.addDefaultLights(this);
		}

		String viewFrom = p.getProperty("camera.view.from");
		String viewTo = p.getProperty("camera.view.to");

		if (viewFrom != null && viewTo != null) {
			RigidBody from = bodies.get(Integer.parseInt(viewFrom));
			RigidBody to = bodies.get(Integer.parseInt(viewTo));

			Vector vd = to.getState().getLocation().subtract(from.getState().getLocation());
			vd.divideBy(vd.length());

			c.setLocation(vd.multiply(((AbstractSurface) from).getSize() * 1.05));
			c.setViewDirection(vd);
		}

		setCamera(c);

		for (RigidBody b : bodies) add((T) b);
	}

	/**
	 * Creates the rigid body stored at the given index, with its light, shaders and state.
	 * A plane stored as lit is loaded unlit with a warning, since only spheres can emit light.
	 *
	 * @param p the properties to read
	 * @param i the index of the body
	 * @return the body, or null if no body type is stored at the index
	 */
	private RigidBody loadBody(Properties p, int i) {
		String type = p.getProperty(key(i, "type"));
		boolean lit = p.getProperty(key(i, "light.on")) != null;

		RigidBody b;

		if ("sphere".equals(type)) {
			RigidSphere s = new RigidSphere();
			s.setRadius(getDouble(p, key(i, "size"), 1.0));
			s.setColor(new RGB(0.8, 0.8, 0.8));
			if (lit) loadLight(p, i, s);
			b = s;
		} else if ("plane".equals(type)) {
			RigidPlane s = new RigidPlane();
			s.setColor(new RGB(0.8, 0.8, 0.8));
			if (lit) warn("Body " + i + " is a plane, which cannot be lit; its light is ignored");
			b = s;
		} else {
			return null;
		}

		loadShaders(p, i, (AbstractSurface) b);

		RigidBody.State s = b.getState();
		s.setMass(getDouble(p, key(i, "mass"), 1.0));
		s.setLocation(getVector(p, key(i, "loc"), new Vector(0.0, 0.0, 0.0)));
		s.setRotation(getVector(p, key(i, "rot"), new Vector(0.0, 0.0, 0.0)));
		s.setLinearVelocity(getVector(p, key(i, "lv"), new Vector(0.0, 0.0, 0.0)));
		s.setAngularVelocity(getVector(p, key(i, "av"), new Vector(0.0, 0.0, 0.0)));

		b.updateModel();
		return b;
	}

	/**
	 * Turns on the light of a rigid sphere, configures it from the given properties, and adds it
	 * to the scene's lights.
	 *
	 * @param p      the properties to read
	 * @param i      the index of the body
	 * @param sphere the sphere
	 */
	private void loadLight(Properties p, int i, RigidSphere sphere) {
		sphere.setLighting(true);

		SphericalLight light = sphere.getLight();
		light.setColor(new RGB(1.0, 1.0, 1.0));
		light.setIntensity(getDouble(p, key(i, "light.intensity"), 0.0));
		light.setSampleCount(Integer.parseInt(p.getProperty(key(i, "light.samples"), "0")));
		light.setAttenuationCoefficients(
				getDouble(p, key(i, "light.ata"), 0.0),
				getDouble(p, key(i, "light.atb"), 0.0),
				getDouble(p, key(i, "light.atc"), 1.0));

		addLight((Light) sphere);
	}

	/**
	 * Adds the stored rigid-body state shading and reflection to a surface, followed by the
	 * default diffuse shader.
	 *
	 * @param p       the properties to read
	 * @param i       the index of the body
	 * @param surface the surface
	 */
	private void loadShaders(Properties p, int i, AbstractSurface surface) {
		String rbstate = p.getProperty(key(i, "shade.rbstate"));

		if (rbstate != null) {
			BlendingShader bs = new BlendingShader(rgb(0.8, 0.0, 0.0), rgb(0.0, 0.0, 0.8));

			if (rbstate.equals("force")) {
				surface.addShader(new RigidBodyStateShader(RigidBodyStateShader.FORCE, 0.0, 1.0, bs));
			} else if (rbstate.equals("velocity")) {
				surface.addShader(new RigidBodyStateShader(RigidBodyStateShader.VELOCITY, 0.0, 1.0, bs));
			}
		}

		String ref = p.getProperty(key(i, "shade.ref"));
		if (ref != null) surface.addShader(new ReflectionShader(Double.parseDouble(ref), white()));

		surface.addShader(DiffuseShader.defaultDiffuseShader);
	}

	/**
	 * Returns the property key for an attribute of the body at the given index.
	 *
	 * @param i    the index of the body
	 * @param name the attribute name
	 * @return the key {@code bodies.<i>.<name>}
	 */
	private static String key(int i, String name) {
		return "bodies." + i + "." + name;
	}

	/**
	 * Returns the double stored under the given key.
	 *
	 * @param p            the properties to read
	 * @param key          the key
	 * @param defaultValue the value to return if the key is absent
	 * @return the stored value, or the default
	 */
	private static double getDouble(Properties p, String key, double defaultValue) {
		String value = p.getProperty(key);
		return value == null ? defaultValue : Double.parseDouble(value);
	}

	/**
	 * Stores a vector as the three keys {@code <prefix>.x}, {@code <prefix>.y} and {@code <prefix>.z}.
	 *
	 * @param p      the properties to write
	 * @param prefix the key prefix
	 * @param v      the vector
	 */
	private static void setVector(Properties p, String prefix, Vector v) {
		p.setProperty(prefix + ".x", String.valueOf(v.getX()));
		p.setProperty(prefix + ".y", String.valueOf(v.getY()));
		p.setProperty(prefix + ".z", String.valueOf(v.getZ()));
	}

	/**
	 * Returns the vector stored under {@code <prefix>.x}, {@code <prefix>.y} and
	 * {@code <prefix>.z}, taking each absent component from the given defaults.
	 *
	 * @param p        the properties to read
	 * @param prefix   the key prefix
	 * @param defaults the vector supplying absent components, which is modified and returned
	 * @return the vector
	 */
	private static Vector getVector(Properties p, String prefix, Vector defaults) {
		defaults.setX(getDouble(p, prefix + ".x", defaults.getX()));
		defaults.setY(getDouble(p, prefix + ".y", defaults.getY()));
		defaults.setZ(getDouble(p, prefix + ".z", defaults.getZ()));
		return defaults;
	}
}
