/**
 * Ray tracing rendering engine with support for complex lighting and materials.
 * <p>
 * The raytrace package provides the core ray tracing implementation including
 * ray-surface intersection, lighting aggregation, shadow computation, and
 * material effects (reflection, refraction, diffuse, specular).
 * </p>
 *
 * <h2>Key Components</h2>
 * <ul>
 *   <li><b>{@link org.almostrealism.raytrace.RayTracedScene}</b> - Main scene orchestrator
 *       for rendering</li>
 *   <li><b>{@link org.almostrealism.raytrace.Engine}</b> - Ray tracing engine interface</li>
 *   <li><b>{@link org.almostrealism.raytrace.RayIntersectionEngine}</b> - Ray-surface
 *       intersection implementation</li>
 *   <li><b>{@link org.almostrealism.raytrace.LightingEngine}</b> - Per-surface lighting
 *       with shadow computation</li>
 *   <li><b>{@link org.almostrealism.raytrace.LightingEngineAggregator}</b> - Multi-light
 *       scene aggregation with ranked selection</li>
 * </ul>
 *
 * <h2>Rendering Pipeline</h2>
 * <pre>
 * Camera Ray Generation
 *    |
 * Ray-Scene Intersection
 *    |
 * Closest Surface Selection
 *    |
 * Shader Evaluation
 *   |-- Diffuse lighting
 *   |-- Specular highlights
 *   |-- Shadows (optional)
 *   |-- Reflections (recursive)
 *   +-- Refractions (recursive)
 *    |
 * Supersampling Average
 *    |
 * Final Pixel Color
 * </pre>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * // Create scene
 * Scene<ShadableSurface> scene = new Scene<>();
 * scene.add(sphere);
 * scene.add(plane);
 * scene.addLight(light);
 * scene.setCamera(camera);
 *
 * // Configure rendering
 * RenderParameters params = new RenderParameters();
 * params.width = 640;
 * params.height = 480;
 * params.ssWidth = 2;   // 2x2 supersampling
 * params.ssHeight = 2;
 *
 * // Create engine and render
 * RayIntersectionEngine engine = new RayIntersectionEngine(
 *     scene,
 *     new FogParameters()
 * );
 *
 * RayTracedScene rayTracedScene = new RayTracedScene(engine, camera, params);
 * RealizableImage image = rayTracedScene.realize(params);
 *
 * // Get pixel data
 * RGB[][] pixels = image.get().evaluate();
 * }</pre>
 *
 * <h2>Features</h2>
 * <ul>
 *   <li>Ray-surface intersection testing</li>
 *   <li>Multi-light scenes with ranked visibility</li>
 *   <li>Shadow computation (optional)</li>
 *   <li>Recursive reflection and refraction</li>
 *   <li>Anti-aliasing via supersampling</li>
 *   <li>Fog effects</li>
 *   <li>Parallel ray tracing (optional)</li>
 *   <li>GPU-accelerated aggregation (optional)</li>
 * </ul>
 *
 * <h2>Performance Configuration</h2>
 * <pre>{@code
 * // Enable GPU acceleration
 * RayIntersectionEngine.enableAcceleratedAggregator = true;
 *
 * // Enable shadow computation (disabled by default)
 * LightingEngine.enableShadows = true;
 *
 * // Enable parallel ray tracing
 * RayTracer.enableThreadPool = true;
 * }</pre>
 *
 * <h2>Integration with Other Modules</h2>
 * <ul>
 *   <li><b>ar-space</b> - Scene, Surface geometry, and spatial organization</li>
 *   <li><b>ar-color</b> - Shader implementations and lighting models</li>
 *   <li><b>ar-geometry</b> - Ray, Camera, and intersection data structures</li>
 * </ul>
 *
 * @see org.almostrealism.render.RayTracedScene
 * @see org.almostrealism.raytrace.RayIntersectionEngine
 * @see org.almostrealism.raytrace.LightingEngine
 */
package org.almostrealism.raytrace;
