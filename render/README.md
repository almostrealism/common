# Almost Realism Render Module (`ar-render`)

The Render Module provides a complete ray tracing rendering engine with support for complex lighting, shadows, reflection, refraction, and anti-aliasing. It integrates with the space and color modules to produce high-quality 2D images from 3D scenes.

## Purpose

This module exists to:

1. **Enable Ray Tracing** - Classical ray-surface intersection rendering
2. **Support Complex Lighting** - Multi-light scenes with shadows
3. **Provide Material Effects** - Reflection, refraction, diffuse, specular
4. **Implement Anti-Aliasing** - Supersampling for smooth images
5. **Generate Images** - Output to various image formats

## What It Provides

### 1. Ray Traced Rendering

```java
import org.almostrealism.raytrace.*;
import org.almostrealism.space.*;

// Create scene
Scene<ShadableSurface> scene = new Scene<>();
scene.add(sphere);
scene.add(plane);
scene.addLight(light);
scene.setCamera(camera);

// Configure rendering
RenderParameters params = new RenderParameters();
params.width = 640;
params.height = 480;
params.ssWidth = 2;   // 2x2 supersampling
params.ssHeight = 2;

// Create ray tracing engine
RayIntersectionEngine engine = new RayIntersectionEngine(
    scene,
    new FogParameters()
);

// Render scene
RayTracedScene rayTracedScene = new RayTracedScene(engine, camera, params);
RealizableImage image = rayTracedScene.realize(params);

// Get image data
RGB[][] pixels = image.get().evaluate();
```

### 2. Lighting System

```java
import org.almostrealism.color.*;

// Point light with attenuation
PointLight keyLight = new PointLight(
    new Vector(5, 5, 10),       // Position
    1.0,                         // Intensity
    new RGB(1, 1, 1)            // White light
);

keyLight.setAttenuationCoefficients(
    0.0,    // Constant term
    0.0,    // Linear term
    0.01    // Quadratic term (inverse square falloff)
);

scene.addLight(keyLight);

// Add multiple lights
scene.addLight(fillLight);
scene.addLight(backLight);
```

### 3. Material Shading

```java
import org.almostrealism.color.computations.*;

// Diffuse (Lambertian) shading
DiffuseShader diffuse = new DiffuseShader();
diffuse.setFrontShade(true);
diffuse.setBackShade(false);

// Specular highlights
HighlightShader specular = new HighlightShader();
specular.setHighlightColor(new RGB(1, 1, 1));
specular.setExponent(64.0);  // Sharp highlights

// Reflection
ReflectionShader mirror = new ReflectionShader();
mirror.setReflectivity(0.9);
mirror.setMaxReflections(4);  // Recursion depth

// Refraction (glass, water)
RefractionShader glass = new RefractionShader();
glass.setIndexOfRefraction(1.5);  // Glass IOR
glass.setAttenuationFactors(0.9, 0.9, 0.95);  // Slight tint

// Apply shaders to surface
sphere.setShaders(new Shader[] { diffuse, specular, mirror });
```

### 4. Camera Configuration

```java
import org.almostrealism.projection.PinholeCamera;

// Perspective camera
PinholeCamera camera = new PinholeCamera(
    new Vector(0, 0, -10),      // Position
    new Vector(0, 0, 1),        // Look direction
    new Vector(0, 1, 0)         // Up direction
);

// Configure lens
camera.setFocalLength(35.0);    // 35mm lens
camera.setBlur(0.0);            // No depth of field blur

// Set projection dimensions (sensor size)
camera.setProjectionDimensions(0.04, 0.04);  // Width, height

scene.setCamera(camera);
```

### 5. Anti-Aliasing

```java
// Supersampling configuration in RenderParameters
params.ssWidth = 2;    // 2 samples per pixel horizontally
params.ssHeight = 2;   // 2 samples per pixel vertically

// Total: 2x2 = 4 samples per pixel, averaged for final color
// Higher values = smoother but slower rendering
```

### 6. Image Output

```java
import org.almostrealism.texture.ImageCanvas;

// Save as JPEG
ImageCanvas.encodeImageFile(
    image.get(),
    new File("output.jpg"),
    ImageCanvas.JPEGEncoding
);

// Or directly access pixel data
RGB[][] pixels = image.get().evaluate();
for (int y = 0; y < height; y++) {
    for (int x = 0; x < width; x++) {
        RGB color = pixels[y][x];
        // Process pixel...
    }
}
```

## Key Classes

### RayTracedScene

```java
public class RayTracedScene extends Realization<RealizableImage, RenderParameters> {
    public RayTracedScene(Engine engine, Camera camera);
    public RayTracedScene(Engine engine, Camera camera, RenderParameters params);

    public RealizableImage realize(RenderParameters params);
}
```

### Engine Interface

```java
public interface Engine {
    Producer<RGB> trace(Producer<Ray> ray);
}
```

**Implementations:**
- `RayIntersectionEngine` - Ray-surface intersection tracing
- `RayMarchingEngine` - Distance field ray marching (partial)

### RayIntersectionEngine

```java
public class RayIntersectionEngine implements Engine {
    public RayIntersectionEngine(Scene scene, FogParameters fog);

    @Override
    public Producer<RGB> trace(Producer<Ray> ray);

    public static boolean enableAcceleratedAggregator;  // GPU acceleration
}
```

### RenderParameters

```java
public class RenderParameters {
    public int width, height;       // Image dimensions
    public int ssWidth, ssHeight;   // Supersampling grid
    public int x, y;                // Upper-left corner
    public int dx, dy;              // Visible dimensions
}
```

### LightingEngine

```java
public class LightingEngine<T extends ContinuousField> {
    public LightingEngine(Surface surface, Light light);

    public Producer<RGB> shade(Producer<Ray> ray);

    public static boolean enableShadows;  // Shadow computation
}
```

## Rendering Pipeline

```
1. Camera Ray Generation
   ↓
2. Ray-Scene Intersection
   ↓
3. Closest Surface Selection
   ↓
4. Shader Evaluation
   ├── Diffuse lighting
   ├── Specular highlights
   ├── Shadows (optional)
   ├── Reflections (recursive)
   └── Refractions (recursive)
   ↓
5. Supersampling Average
   ↓
6. Final Pixel Color
```

## Common Patterns

### Pattern 1: Basic Scene Rendering

```java
// 1. Create geometry
Sphere sphere = new Sphere(new Vector(0, 0, 0), 1.0, new RGB(0.8, 0.2, 0.2));
sphere.addShader(new DiffuseShader());

Plane ground = new Plane(
    new Vector(0, -2, 0),
    new Vector(0, 1, 0),
    10, 10, 0.1
);
ground.setColor(new RGB(0.3, 0.3, 0.3));

// 2. Create lighting
PointLight light = new PointLight(new Vector(5, 5, 5), 1.0, new RGB(1, 1, 1));
light.setAttenuationCoefficients(0, 0, 0.01);

// 3. Assemble scene
Scene<ShadableSurface> scene = new Scene<>();
scene.add(sphere);
scene.add(ground);
scene.addLight(light);

// 4. Configure camera
PinholeCamera camera = new PinholeCamera(
    new Vector(0, 0, -5),
    new Vector(0, 0, 1),
    new Vector(0, 1, 0)
);
camera.setFocalLength(35.0);
scene.setCamera(camera);

// 5. Render
RenderParameters params = new RenderParameters();
params.width = 640;
params.height = 480;
params.dx = 640;
params.dy = 480;
params.ssWidth = 2;
params.ssHeight = 2;

RayTracedScene rayTracedScene = new RayTracedScene(
    new RayIntersectionEngine(scene, new FogParameters()),
    camera,
    params
);

RealizableImage image = rayTracedScene.realize(params);

// 6. Save
ImageCanvas.encodeImageFile(image.get(), new File("output.jpg"),
                           ImageCanvas.JPEGEncoding);
```

### Pattern 2: Multi-Light Scene

```java
// Key light (main illumination)
PointLight keyLight = new PointLight(
    new Vector(10, 10, -10),
    1.0,
    new RGB(1.0, 1.0, 0.9)  // Warm white
);

// Fill light (soften shadows)
PointLight fillLight = new PointLight(
    new Vector(-5, 5, -5),
    0.5,
    new RGB(0.7, 0.7, 1.0)  // Cool tint
);

// Back light (rim lighting)
PointLight backLight = new PointLight(
    new Vector(0, 5, 5),
    0.3,
    new RGB(1.0, 1.0, 1.0)
);

scene.addLight(keyLight);
scene.addLight(fillLight);
scene.addLight(backLight);
```

### Pattern 3: Reflective Surfaces

```java
// Create reflective sphere
Sphere mirrorSphere = new Sphere(new Vector(0, 0, 0), 1.0, new RGB(0.9, 0.9, 0.9));

// Configure reflection
ReflectionShader reflection = new ReflectionShader();
reflection.setReflectivity(0.95);           // 95% reflective
reflection.setReflectiveColor(white());
reflection.setBlur(0.0);                    // Perfect mirror
reflection.setMaxReflections(4);            // Recursion depth

mirrorSphere.setShaders(new Shader[] { reflection });

// Engine will trace reflected rays recursively
```

### Pattern 4: Glass/Transparent Objects

```java
// Glass sphere
Sphere glassSphere = new Sphere(new Vector(0, 0, 0), 1.0, new RGB(1, 1, 1));

// Configure refraction
RefractionShader glass = new RefractionShader();
glass.setIndexOfRefraction(1.5);            // Glass IOR
glass.setAttenuationFactors(0.95, 0.95, 0.98);  // Slight blue-green tint
glass.setTransmitAbsorb(true);

glassSphere.setShaders(new Shader[] { glass });
glassSphere.setRefractedPercentage(0.9);    // 90% transparent
glassSphere.setReflectedPercentage(0.1);    // 10% reflective
```

### Pattern 5: Composite Materials

```java
// Combine multiple shaders
DiffuseShader diffuse = new DiffuseShader();
HighlightShader specular = new HighlightShader();
specular.setExponent(32.0);

ReflectionShader reflection = new ReflectionShader();
reflection.setReflectivity(0.3);  // Subtle reflections

sphere.setShaders(new Shader[] { diffuse, specular, reflection });

// Result: Diffuse base + specular highlights + subtle reflections
```

### Pattern 6: Custom Render Region

```java
RenderParameters params = new RenderParameters();

// Full image size
params.width = 1920;
params.height = 1080;

// Only render a portion (e.g., top-left quadrant)
params.x = 0;
params.y = 0;
params.dx = 960;   // Half width
params.dy = 540;   // Half height

// Useful for distributed rendering or progressive rendering
```

### Pattern 7: Shadow Configuration

```java
// Enable shadows (disabled by default)
LightingEngine.enableShadows = true;

// Shadows are computed via secondary rays
// from intersection point to light source
// If blocked by another surface, pixel is shadowed
```

## Advanced Features

### Fog System

```java
FogParameters fog = new FogParameters();
fog.setFogColor(new RGB(0.8, 0.8, 0.9));  // Light blue-gray
fog.setFogDensity(0.05);                   // Fog thickness
fog.setFogRatio(0.5);                      // Blending ratio

RayIntersectionEngine engine = new RayIntersectionEngine(scene, fog);
```

### Parallel Ray Tracing

```java
// Enable thread pool for parallel ray tracing
RayTracer.enableThreadPool = true;

// Uses 10-thread pool by default for faster rendering
// Each ray traced in parallel
```

### GPU Acceleration

```java
// Enable GPU-accelerated lighting aggregation
RayIntersectionEngine.enableAcceleratedAggregator = true;

// Pre-caches intersection ranks on GPU
// Significantly faster for complex scenes
```

### Custom Engine Implementation

```java
public class CustomEngine implements Engine {
    @Override
    public Producer<RGB> trace(Producer<Ray> ray) {
        // Custom ray tracing logic
        // Return Producer<RGB> for pixel color
    }
}

RayTracedScene scene = new RayTracedScene(new CustomEngine(), camera);
```

## Integration with Other Modules

### Space Module
- Uses **Scene** for geometry and lighting
- **ShadableSurface** for renderable objects
- **Camera** for ray generation

### Color Module
- **Shader** implementations for materials
- **Light** sources for illumination
- **RGB** for color representation

### Geometry Module
- **Ray** for ray tracing
- **Intersection** for ray-surface tests
- **Camera** ray generation

## Performance Considerations

### Optimization Strategies

1. **Bounding Volumes** - Scene uses BSP trees for fast culling
2. **KV Caching** - Intersection results cached when enabled
3. **Parallel Tracing** - Thread pool for multi-core systems
4. **GPU Acceleration** - Hardware-accelerated aggregation
5. **Supersampling Control** - Balance quality vs. speed

### Typical Performance

```
640x480, 1x1 sampling:  ~1-5 seconds (simple scene)
640x480, 2x2 sampling:  ~4-20 seconds
1920x1080, 2x2 sampling: ~30-120 seconds

GPU acceleration: 2-10x speedup
Thread pool: ~8x speedup on 8-core CPU
```

## Environment Configuration

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native  # or opencl, metal
```

## Testing

Run render tests:

```bash
# All tests
mvn test -pl render

# Specific test
mvn test -pl render -Dtest=SimpleRenderTest
```

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-space</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-utils</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-render</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Further Reading

- See **ar-space** module for Scene, Surface, and geometry
- See **ar-color** module for Shader, Light, and materials
- See **ar-geometry** module for Ray, Camera, and transformations
