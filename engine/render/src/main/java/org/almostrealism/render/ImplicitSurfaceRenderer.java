/*
 * Copyright 2026 Michael Murray
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

import io.almostrealism.compute.Process;
import io.almostrealism.lifecycle.Destroyable;
import io.almostrealism.relation.Producer;
import org.almostrealism.CodeFeatures;
import org.almostrealism.collect.CollectionProducer;
import org.almostrealism.collect.PackedCollection;
import org.almostrealism.color.AmbientLight;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBFeatures;
import org.almostrealism.geometry.Camera;
import org.almostrealism.geometry.ImplicitSurface;
import org.almostrealism.hardware.OperationList;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Renders an {@link ImplicitSurface} into an RGB image with one program per pixel: the camera
 * ray through the pixel is marched against the surface, the hit is refined, and the hit is
 * shaded, all as producer graphs over the batch of every pixel of the image.
 *
 * <p>{@link #frame()} returns the operations that render one frame. Compile them once and run
 * the result for every frame; the surface is read through producers, so a surface whose data
 * changes between frames renders its current state each time:</p>
 * <pre>{@code
 * ImplicitSurfaceRenderer renderer = new ImplicitSurfaceRenderer(surface, camera, 256, 256);
 * renderer.addLight(new PointLight(new Vector(4, 6, 4), 1.0, new RGB(1, 1, 1)));
 * Runnable frame = renderer.frame().get();
 * frame.run();
 * saveRgb("frame.png", cp(renderer.getImage())).get().run();
 * }</pre>
 *
 * <h2>Marching</h2>
 * <p>Each pixel keeps a bracket {@code [near, far]} of distances along its ray, with the
 * surface value positive at {@code near}. For {@link #setMarchSteps(int) marchSteps} steps
 * the ray advances by {@code f(p) / L}, where {@code L} is the
 * {@link ImplicitSurface#lipschitzBound() Lipschitz bound} of the surface (at least
 * {@link #setMinimumStep(double) minimumStep}), until the value at the probe is no longer
 * positive, which closes the bracket on the surface. The bracket is then narrowed by
 * {@link #setRefinementSteps(int) refinementSteps} bisections and a final secant step. A
 * pixel whose bracket never closes before {@link #setMaximumDistance(double) maximumDistance}
 * misses the surface. So does a pixel whose ray starts inside the surface (where the value
 * at distance zero is not positive): there is no bracket with a positive near end, so the
 * pixel shows the environment rather than a hit at distance zero.</p>
 *
 * <h2>Shading</h2>
 * <p>With {@code n} the surface normal, {@code d} the ray direction and {@code r} the
 * reflection of {@code d} about {@code n}, the color of a hit is</p>
 * <pre>
 * (1 - F) base (ambient + sum(light * max(n.l, 0)))
 *     + F environment(r) + sum(light * specular * max(n.h, 0)^shininess)
 * </pre>
 * <p>where {@code l} is the direction to a point light, {@code h} the half vector between
 * {@code l} and {@code -d}, and {@code F = F0 + (1 - F0)(1 - max(-d.n, 0))^5} the Schlick
 * approximation of the Fresnel reflectance. The environment is a gradient from the horizon
 * color, for directions at or below the horizon, to the zenith color straight up; a ray that
 * misses the surface shows the environment in its own direction. Lights cast no shadows and
 * do not attenuate with distance. Colors are clamped to {@code [0, 1]}.</p>
 *
 * <h2>Compilation</h2>
 * <p>The march state of each pixel lives in collections owned by the renderer, and every
 * step is a set of assignments that read and write only the state of their own pixel. All of
 * these assignments have one element per pixel, so the list of them compiles to a single
 * kernel that runs the whole march of a pixel in one thread. Writing the march as one
 * nested expression instead would repeat the expression of every earlier step inside every
 * later one. The Lipschitz bound, which may reduce over the surface data, is computed once
 * per frame before that kernel, and is optimized so that its reductions are isolated in
 * kernels of their own.</p>
 *
 * <p>The renderer owns the per-pixel state and the image it writes, and releases them when
 * it is {@link #destroy() destroyed}; the collections returned by {@link #getImage()},
 * {@link #getDistance()} and {@link #getReflectance()} are views of that state and are no
 * longer valid afterwards.</p>
 *
 * @author Michael Murray
 */
public class ImplicitSurfaceRenderer implements CodeFeatures, RGBFeatures, Destroyable {
	/** The surface to render. */
	private final ImplicitSurface surface;

	/** The camera that produces the ray through each pixel. */
	private final Camera camera;

	/** The width of the image, in pixels. */
	private final int width;

	/** The height of the image, in pixels. */
	private final int height;

	/** The point lights that illuminate the surface. */
	private final List<PointLight> lights;

	/** The ambient light that illuminates the surface. */
	private AmbientLight ambient;

	/** The diffuse color of the surface. */
	private RGB baseColor;

	/** The environment color at and below the horizon. */
	private RGB horizonColor;

	/** The environment color straight up. */
	private RGB zenithColor;

	/** The Fresnel reflectance of the surface at normal incidence. */
	private double normalReflectance;

	/** The strength of the specular highlights. */
	private double specular;

	/** The exponent that sets the size of the specular highlights. */
	private double shininess;

	/** The number of march steps along each ray. */
	private int marchSteps;

	/** The number of bisections of the bracket around each hit. */
	private int refinementSteps;

	/** The smallest step along a ray. */
	private double minimumStep;

	/** The distance along a ray beyond which it misses the surface. */
	private double maximumDistance;

	/** The ray of every pixel, {@code (n, 6)}. */
	private final PackedCollection rays;

	/** The distance of the current probe along every ray, {@code (n, 1)}. */
	private final PackedCollection probe;

	/** The near end of the bracket of every ray, where the surface value is positive. */
	private final PackedCollection near;

	/**
	 * The far end of the bracket of every ray, where the surface value is not positive, or
	 * {@link #unclosedDistance()} while the bracket has not closed.
	 */
	private final PackedCollection far;

	/** The surface value at the probe, or at the near end of the bracket. */
	private final PackedCollection value;

	/** The surface value at the far end of the bracket. */
	private final PackedCollection farValue;

	/** 1 for every ray that hits the surface and 0 for every ray that misses it. */
	private final PackedCollection hit;

	/** The Lipschitz bound of the surface, {@code (1)}. */
	private final PackedCollection bound;

	/** The distance to the hit along every ray, or -1 for a miss, {@code (n, 1)}. */
	private final PackedCollection distance;

	/** The Fresnel reflectance of every hit, or 0 for a miss, {@code (n, 1)}. */
	private final PackedCollection reflectance;

	/** The color of every pixel, {@code (n, 3)}. */
	private final PackedCollection image;

	/**
	 * Creates a renderer of the given surface, with no point lights, a dim white ambient
	 * light, a grey surface, a blue-grey sky gradient, 64 march steps and 8 bisections.
	 *
	 * @param surface the surface to render
	 * @param camera the camera that produces the ray through each pixel
	 * @param width the width of the image, in pixels
	 * @param height the height of the image, in pixels
	 */
	public ImplicitSurfaceRenderer(ImplicitSurface surface, Camera camera, int width, int height) {
		this.surface = surface;
		this.camera = camera;
		this.width = width;
		this.height = height;
		this.lights = new ArrayList<>();
		this.ambient = new AmbientLight(0.1, new RGB(1.0, 1.0, 1.0));
		this.baseColor = new RGB(0.5, 0.5, 0.5);
		this.horizonColor = new RGB(0.8, 0.85, 0.9);
		this.zenithColor = new RGB(0.25, 0.4, 0.7);
		this.normalReflectance = 0.04;
		this.specular = 0.5;
		this.shininess = 64.0;
		this.marchSteps = 64;
		this.refinementSteps = 8;
		this.minimumStep = 1e-3;
		this.maximumDistance = 100.0;

		int n = width * height;
		this.rays = new PackedCollection(shape(n, 6).traverse(1));
		this.probe = scalars(n);
		this.near = scalars(n);
		this.far = scalars(n);
		this.value = scalars(n);
		this.farValue = scalars(n);
		this.hit = scalars(n);
		this.distance = scalars(n);
		this.reflectance = scalars(n);
		this.bound = new PackedCollection(1);
		this.image = new PackedCollection(shape(n, 3).traverse(1));
	}

	/**
	 * Adds a point light. Its location, color and intensity are used; it does not attenuate.
	 *
	 * @param light the light to add
	 */
	public void addLight(PointLight light) { lights.add(light); }

	/**
	 * Sets the ambient light, whose color and intensity are used.
	 *
	 * @param ambient the ambient light
	 */
	public void setAmbientLight(AmbientLight ambient) { this.ambient = ambient; }

	/**
	 * Sets the diffuse color of the surface.
	 *
	 * @param baseColor the diffuse color
	 */
	public void setBaseColor(RGB baseColor) { this.baseColor = baseColor; }

	/**
	 * Sets the environment gradient, from the horizon to the zenith.
	 *
	 * @param horizon the environment color at and below the horizon
	 * @param zenith the environment color straight up
	 */
	public void setEnvironment(RGB horizon, RGB zenith) {
		this.horizonColor = horizon;
		this.zenithColor = zenith;
	}

	/**
	 * Sets the Fresnel reflectance of the surface at normal incidence, {@code F0} (0.04 for
	 * water and most dielectrics).
	 *
	 * @param normalReflectance the reflectance at normal incidence
	 */
	public void setNormalReflectance(double normalReflectance) { this.normalReflectance = normalReflectance; }

	/**
	 * Sets the strength and exponent of the specular highlights.
	 *
	 * @param specular the strength of the highlights
	 * @param shininess the exponent, larger for smaller highlights
	 */
	public void setSpecular(double specular, double shininess) {
		this.specular = specular;
		this.shininess = shininess;
	}

	/**
	 * Sets the number of march steps along each ray.
	 *
	 * @param marchSteps the number of steps
	 */
	public void setMarchSteps(int marchSteps) { this.marchSteps = marchSteps; }

	/**
	 * Sets the number of bisections of the bracket around each hit.
	 *
	 * @param refinementSteps the number of bisections
	 */
	public void setRefinementSteps(int refinementSteps) { this.refinementSteps = refinementSteps; }

	/**
	 * Sets the smallest step along a ray, which keeps the march advancing near the surface.
	 *
	 * @param minimumStep the smallest step
	 */
	public void setMinimumStep(double minimumStep) { this.minimumStep = minimumStep; }

	/**
	 * Sets the distance along a ray beyond which it misses the surface.
	 *
	 * @param maximumDistance the largest distance
	 */
	public void setMaximumDistance(double maximumDistance) { this.maximumDistance = maximumDistance; }

	/** Returns the width of the image, in pixels. */
	public int getWidth() { return width; }

	/** Returns the height of the image, in pixels. */
	public int getHeight() { return height; }

	/**
	 * Returns the image written by the last frame, {@code (height, width, 3)}, with row 0 at
	 * the top, in the layout that {@link RGBFeatures#saveRgb(String, CollectionProducer)}
	 * writes.
	 *
	 * @return the image
	 */
	public PackedCollection getImage() { return image.reshape(shape(height, width, 3)); }

	/**
	 * Returns the distance along the ray of every pixel to its hit, or -1 where the ray
	 * misses the surface, {@code (height, width)}, written by the last frame.
	 *
	 * @return the hit distances
	 */
	public PackedCollection getDistance() { return distance.reshape(shape(height, width)); }

	/**
	 * Returns the Fresnel reflectance of the hit of every pixel, or 0 where the ray misses
	 * the surface, {@code (height, width)}, written by the last frame.
	 *
	 * @return the reflectances
	 */
	public PackedCollection getReflectance() { return reflectance.reshape(shape(height, width)); }

	/**
	 * Returns the operations that render one frame into {@link #getImage()},
	 * {@link #getDistance()} and {@link #getReflectance()}: the Lipschitz bound of the
	 * surface, followed by the march, refinement and shading of every pixel as one list of
	 * per-pixel assignments that compiles to a single kernel.
	 *
	 * @return the operations of one frame
	 */
	public OperationList frame() {
		OperationList pixels = new OperationList("Implicit Surface Pixels");
		pixels.add(a(cp(rays), camera.rayAt(pixelPositions(), pair(width, height))));
		pixels.add(a(cp(probe), c(0.0)));
		pixels.add(a(cp(near), c(0.0)));
		pixels.add(a(cp(far), c(unclosedDistance())));

		for (int i = 0; i < marchSteps; i++) {
			addMarchStep(pixels);
		}

		pixels.add(a(cp(hit), greaterThan(cp(far), c(0.0),
				lessThan(cp(far), c(unclosedDistance()), c(1.0), c(0.0)), c(0.0))));

		for (int i = 0; i < refinementSteps; i++) {
			addBisection(pixels);
		}

		addSecant(pixels);
		addShading(pixels);

		OperationList frame = new OperationList("Implicit Surface Frame");
		frame.add(Process.optimized(a(cp(bound), surface.lipschitzBound())));
		frame.add(pixels);
		return frame;
	}

	/**
	 * Adds one march step: evaluate the surface at the probe, close the bracket there if the
	 * value is not positive, and otherwise move the near end to the probe and advance the
	 * probe by the conservative step.
	 *
	 * @param ops the per-pixel operations
	 */
	protected void addMarchStep(OperationList ops) {
		ops.add(a(cp(value), surface.value(pointAt(cp(probe)))));
		ops.add(a(cp(far), greaterThan(cp(value), c(0.0), cp(far), min(cp(far), cp(probe)))));

		CollectionProducer open = greaterThan(cp(far), c(maximumDistance), c(1.0), c(0.0));
		ops.add(a(cp(near), add(cp(near), open.multiply(subtract(cp(probe), cp(near))))));

		CollectionProducer step = max(cp(value).divide(cp(bound)), c(minimumStep));
		CollectionProducer next = min(add(cp(probe), step), c(maximumDistance));
		ops.add(a(cp(probe), add(cp(probe), open.multiply(subtract(next, cp(probe))))));
	}

	/**
	 * Returns the far end of a bracket that has not closed. It lies beyond
	 * {@link #setMaximumDistance(double) maximumDistance}, where no probe reaches, so that a
	 * bracket closing at a probe clamped to {@code maximumDistance} remains distinguishable
	 * from one that never closed.
	 *
	 * @return the far end of an unclosed bracket
	 */
	protected double unclosedDistance() {
		return 2.0 * maximumDistance + 1.0;
	}

	/**
	 * Adds one bisection of the bracket: evaluate the surface at the midpoint and replace
	 * the end of the bracket on the same side of the surface.
	 *
	 * @param ops the per-pixel operations
	 */
	protected void addBisection(OperationList ops) {
		ops.add(a(cp(probe), add(cp(near), cp(far)).multiply(c(0.5))));
		ops.add(a(cp(value), surface.value(pointAt(cp(probe)))));
		ops.add(a(cp(near), greaterThan(cp(value), c(0.0), cp(probe), cp(near))));
		ops.add(a(cp(far), greaterThan(cp(value), c(0.0), cp(far), cp(probe))));
	}

	/**
	 * Adds the secant step, which places the hit where the line through the surface values
	 * at the two ends of the bracket crosses zero, and records the hit distance.
	 *
	 * @param ops the per-pixel operations
	 */
	protected void addSecant(OperationList ops) {
		ops.add(a(cp(value), surface.value(pointAt(cp(near)))));
		ops.add(a(cp(farValue), surface.value(pointAt(cp(far)))));

		CollectionProducer span = max(subtract(cp(value), cp(farValue)), c(1e-12));
		ops.add(a(cp(probe), add(cp(near),
				subtract(cp(far), cp(near)).multiply(cp(value)).divide(span))));
		ops.add(a(cp(distance), greaterThan(cp(hit), c(0.5), cp(probe), c(-1.0))));
	}

	/**
	 * Adds the shading of every pixel, which writes the image and the reflectance.
	 *
	 * @param ops the per-pixel operations
	 */
	protected void addShading(OperationList ops) {
		CollectionProducer d = direction(cp(rays));
		CollectionProducer p = pointAt(cp(probe));
		CollectionProducer n = surface.normal(p);

		CollectionProducer cosine = max(dotProduct(d, n).minus(), c(0.0));
		CollectionProducer fresnel = c(normalReflectance).add(
				c(1.0 - normalReflectance).multiply(c(1.0).subtract(cosine).pow(c(5.0))));

		CollectionProducer diffuse = constant(baseColor.multiply(ambient.getRadiance()));
		CollectionProducer highlights = constant(new RGB(0.0, 0.0, 0.0));

		for (PointLight light : lights) {
			CollectionProducer l = normalize(subtract(constant(light.getLocation()), p));
			CollectionProducer h = normalize(subtract(l, d));
			CollectionProducer lambert = max(dotProduct(n, l), c(0.0));
			CollectionProducer phong = max(dotProduct(n, h), c(0.0)).pow(c(shininess));

			diffuse = diffuse.add(scale(constant(baseColor.multiply(light.getRadiance())), lambert));
			highlights = highlights.add(scale(constant(light.getRadiance().multiply(specular)), phong));
		}

		CollectionProducer reflected = subtract(d, scale(n, dotProduct(d, n).multiply(c(2.0))));
		CollectionProducer surfaceColor = scale(diffuse, c(1.0).subtract(fresnel))
				.add(scale(environment(reflected), fresnel))
				.add(highlights);

		// Select rather than blend by the hit mask: for a miss, the surface shading reads the
		// normal at a point that need not be on the surface, where it may be undefined
		CollectionProducer color = greaterThan(cp(hit), c(0.5), surfaceColor, environment(d));

		ops.add(a(cp(image), bound(color, 0.0, 1.0)));
		ops.add(a(cp(reflectance), greaterThan(cp(hit), c(0.5), fresnel, c(0.0))));
	}

	/**
	 * Returns the environment color in each of the given directions: the horizon color at
	 * and below the horizon, blending to the zenith color straight up.
	 *
	 * @param directions the {@code (n, 3)} unit directions
	 * @return the {@code (n, 3)} colors
	 */
	protected CollectionProducer environment(CollectionProducer directions) {
		CollectionProducer elevation = bound(subset(shape(1), directions, 1), 0.0, 1.0);
		return constant(horizonColor).add(scale(constant(zenithColor.subtract(horizonColor)), elevation));
	}

	/**
	 * Returns the point at the given distance along the ray of every pixel.
	 *
	 * @param t the {@code (n, 1)} distances
	 * @return the {@code (n, 3)} points
	 */
	protected CollectionProducer pointAt(Producer<PackedCollection> t) {
		return add(origin(cp(rays)), scale(direction(cp(rays)), t));
	}

	/**
	 * Returns the screen position of every pixel, {@code (n, 2)}: pixel {@code k} is in
	 * column {@code k % width} and row {@code k / width} counted from the top, and its
	 * screen position is {@code (column, height - 1 - row)}, since the camera's screen
	 * {@code y} increases upwards.
	 *
	 * @return the screen positions
	 */
	protected CollectionProducer pixelPositions() {
		CollectionProducer index = integers(0, width * height).reshape(shape(width * height, 1).traverse(1));
		CollectionProducer column = mod(index, c((double) width));
		CollectionProducer row = floor(index.divide(c((double) width)));
		return pair(column, c(height - 1.0).subtract(row));
	}

	/**
	 * Returns the given three values, such as a color or a location, repeated for every
	 * pixel. The values are read when the frame runs.
	 *
	 * @param value the {@code (3)} values
	 * @return the {@code (n, 3)} repeated values
	 */
	protected CollectionProducer constant(PackedCollection value) {
		return c(repeatItems(shape(width * height, 3).traverse(1), cp(value)));
	}

	/**
	 * Releases the per-pixel state and the image owned by this renderer. The views returned
	 * by {@link #getImage()}, {@link #getDistance()} and {@link #getReflectance()} are no
	 * longer valid afterwards.
	 */
	@Override
	public void destroy() {
		Stream.of(rays, probe, near, far, value, farValue, hit, bound, distance, reflectance, image)
				.forEach(PackedCollection::destroy);
	}

	/**
	 * Creates an {@code (n, 1)} collection with one value for every pixel.
	 *
	 * @param n the number of pixels
	 * @return the collection
	 */
	private PackedCollection scalars(int n) {
		return new PackedCollection(shape(n, 1).traverse(1));
	}
}
