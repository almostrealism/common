# Almost Realism Geometry Module (`ar-geometry`)

The Geometry Module provides foundational 3D geometric primitives, transformation systems, ray tracing infrastructure, and camera implementations. It serves as the bridge between abstract mathematical operations and concrete rendering/physics systems.

## Purpose

This module exists to:

1. **Define 3D Primitives** - Rays, transformation matrices, and spatial data structures
2. **Enable Ray Tracing** - Ray-object intersection interfaces and utilities
3. **Provide Cameras** - Multiple camera projection models for rendering
4. **Handle Transformations** - Matrix-based rotation, scaling, translation
5. **Abstract Spatial Fields** - Continuous and discrete field representations

## What It Provides

### 1. Core Geometric Primitives

```java
import org.almostrealism.geometry.*;
import static org.almostrealism.geometry.RayFeatures.*;

// Create a ray (origin + direction)
Ray ray = new Ray(
    new Vector(0, 0, 0),      // Origin
    new Vector(0, 0, 1)       // Direction (normalized)
);

// Access components
Producer<Vector> origin = origin(ray);
Producer<Vector> direction = direction(ray);

// Evaluate point along ray: p = o + t*d
Producer<Vector> pointAtDistance = pointAt(ray, c(5.0));
```

### 2. Transformation Matrices

```java
import org.almostrealism.geometry.TransformMatrix;
import static org.almostrealism.geometry.TransformMatrixFeatures.*;

// Create transformations using features
Producer<PackedCollection<?>> translation = translationMatrix(
    vector(5.0, 0.0, 0.0)  // Move 5 units in X
);

Producer<PackedCollection<?>> scale = scaleMatrix(
    vector(2.0, 2.0, 2.0)  // Double size
);

// Combine transformations
TransformMatrix transform = new TransformMatrix(translation.get().evaluate(), 0);
TransformMatrix scaled = new TransformMatrix(scale.get().evaluate(), 0);
TransformMatrix combined = transform.multiply(scaled);

// Apply to vectors
Producer<Vector> transformed = transformAsLocation(combined, point);
Producer<Vector> direction = transformAsOffset(combined, vector);  // No translation
```

### 3. Geometry with Position/Rotation/Scale

```java
import org.almostrealism.geometry.BasicGeometry;

// Create positioned geometry
BasicGeometry obj = new BasicGeometry(new Vector(5, 2, 0));

// Set scale (per-axis)
obj.setScale(2.0f, 1.0f, 1.5f);

// Set rotation (radians)
obj.setRotationCoefficients(
    0.0,              // X rotation
    Math.PI / 4.0,    // Y rotation (45 degrees)
    0.0               // Z rotation
);

// Calculate complete transformation
obj.calculateTransform();

// Get world transformation matrix
TransformMatrix worldTransform = obj.getTransform(true);
```

### 4. Camera Systems

```java
import org.almostrealism.projection.*;

// Perspective camera (pinhole model)
PinholeCamera camera = new PinholeCamera(
    new Vector(0, 0, -10),    // Camera position
    new Vector(0, 0, 1),      // Look direction
    new Vector(0, 1, 0)       // Up direction
);

camera.setFocalLength(35.0);  // 35mm focal length
camera.setBlur(0.5);          // Depth of field blur

// Generate camera ray for pixel
Producer<Ray> ray = camera.rayAt(
    pair(pixelX, pixelY),
    pair(imageWidth, imageHeight)
);

// Orthographic camera (parallel projection)
OrthographicCamera ortho = new OrthographicCamera(
    new Vector(0, 0, -10),
    new Vector(0, 0, 1),
    new Vector(0, 1, 0),
    new Vector(10, 10, 1)     // Projection dimensions
);
```

### 5. Ray-Surface Intersection

```java
import org.almostrealism.geometry.*;

// Surfaces implement Intersectable<Scalar>
public interface Intersectable<T> {
    ContinuousField intersectAt(Producer<Ray> ray);
    Operator<Scalar> expect();
}

// Test ray against surface
ContinuousField intersections = surface.intersectAt(ray);

// Get first intersection (as ray with normal)
Producer<Ray> hitNormal = intersections.get(0);
Producer<Vector> hitPoint = origin(hitNormal);
Producer<Vector> normal = direction(hitNormal);

// Find closest intersection among multiple surfaces
ClosestIntersection closest = new ClosestIntersection(ray, surfaceList);
Producer<Ray> closestHit = closest.get(0);
```

### 6. Reflection

```java
import org.almostrealism.geometry.ReflectedRay;

// Compute reflected ray
ReflectedRay reflection = new ReflectedRay(
    intersectionPoint,
    incidentDirection,
    surfaceNormal,
    blurAmount  // 0.0 = perfect mirror, >0 = glossy
);

Evaluable<Ray> reflectedRay = reflection.get();
Ray reflected = reflectedRay.evaluate();
```

## Key Classes

### Ray

```java
public class Ray extends PackedCollection<Ray> {
    // Stores [ox, oy, oz, dx, dy, dz] in contiguous memory

    public Producer<Vector> getOrigin();
    public Producer<Vector> getDirection();

    public void setOrigin(Vector origin);
    public void setDirection(Vector direction);

    // Evaluate point along ray: p = o + t*d
    public Producer<Vector> pointAt(Producer<PackedCollection<?>> t);

    // Dot product utilities
    public Producer<PackedCollection<?>> oDoto();  // origin · origin
    public Producer<PackedCollection<?>> dDotd();  // direction · direction
    public Producer<PackedCollection<?>> oDotd();  // origin · direction
}
```

### TransformMatrix

```java
public class TransformMatrix extends PackedCollection<TransformMatrix> {
    // 4x4 homogeneous transformation matrix (16 values)

    // Transform types
    public static final int TRANSFORM_AS_LOCATION = 0;  // Include translation
    public static final int TRANSFORM_AS_OFFSET = 1;    // No translation
    public static final int TRANSFORM_AS_NORMAL = 2;    // Use inverse transpose

    public Producer<Vector> transform(Producer<Vector> v, int type);
    public Ray transform(Ray ray);

    public TransformMatrix multiply(TransformMatrix m);
    public TransformMatrix transpose();
    public TransformMatrix getInverse();

    public Producer<PackedCollection<?>> determinant();
}
```

### BasicGeometry

```java
public class BasicGeometry implements Positioned, Oriented, Scaled {
    public void setLocation(Vector location);
    public void setScale(float sx, float sy, float sz);
    public void setRotationCoefficients(double rx, double ry, double rz);

    // Build complete transformation
    public void calculateTransform();

    // Get final world transform
    public TransformMatrix getTransform(boolean deep);
}
```

### Intersection

```java
public class Intersection {
    public static final double e = 0.00000001;  // Epsilon for comparisons

    protected Producer<Vector> point;           // 3D intersection location
    protected Producer<PackedCollection<?>> distance;  // Distance along ray
}
```

### ShadableIntersection

```java
public class ShadableIntersection extends Intersection implements ContinuousField {
    protected Producer<Vector> incident;  // View direction
    protected Producer<Ray> normal;       // Surface normal as ray

    public Producer<Vector> getNormalAt();  // Returns -normalize(incident)
}
```

## Transformation Pipeline

```java
// Build transformation step-by-step
BasicGeometry obj = new BasicGeometry(location);

// 1. Set position
obj.setLocation(new Vector(5, 2, 0));

// 2. Set size/scale
obj.setSize(2.0f);
obj.setScale(1.0f, 2.0f, 1.0f);

// 3. Set rotation (X, Y, Z in radians)
obj.setRotationCoefficients(0, Math.PI / 4, 0);

// 4. Calculate combined transform
obj.calculateTransform();

// Order: Translation → Size*Scale → RotateX → RotateY → RotateZ → Additional

// 5. Apply to points
TransformMatrix worldTransform = obj.getTransform(true);
Vector transformedPoint = worldTransform.transform(localPoint,
                                                    TransformMatrix.TRANSFORM_AS_LOCATION);
```

## Ray Tracing Integration

```java
// Complete ray tracing flow
Camera camera = new PinholeCamera(cameraPos, lookDir, upDir);

// 1. Generate camera ray for pixel
Producer<Ray> ray = camera.rayAt(
    pair(pixelX, pixelY),
    pair(width, height)
);

// 2. Find closest intersection
List<Intersectable<Scalar>> surfaces = scene.getSurfaces();
ClosestIntersection closest = new ClosestIntersection(ray, surfaces);

// 3. Get intersection data
Producer<Ray> hitNormal = closest.get(0);
Producer<Vector> hitPoint = origin(hitNormal);
Producer<Vector> normal = direction(hitNormal);

// 4. Compute reflection for recursive tracing
ReflectedRay reflection = new ReflectedRay(
    hitPoint,
    direction(ray),
    normal,
    0.0  // Perfect reflection
);

// 5. Pass to shader (in color module)
Producer<RGB> color = shader.shade(context, normalField);
```

## Camera Projection Models

### Pinhole Camera (Perspective)

```java
PinholeCamera camera = new PinholeCamera(location, viewDir, upDir);

// Field of view from focal length
camera.setFocalLength(35.0);  // FOV = 2*atan(dimension/(2*focal))

// Depth of field
camera.setBlur(0.5);  // Aperture blur amount

// Enable hardware acceleration
PinholeCamera.enableHardwareAcceleration = true;
```

**Projection:**
- Rays converge at camera origin (single point)
- Objects farther away appear smaller
- Natural perspective distortion

### Orthographic Camera (Parallel)

```java
OrthographicCamera camera = new OrthographicCamera(
    location, viewDir, upDir,
    new Vector(10, 10, 1)  // Width, height, depth of projection volume
);
```

**Projection:**
- Rays parallel to view direction
- No perspective distortion
- Objects same size regardless of distance
- Useful for technical/architectural visualization

## Common Patterns

### Pattern 1: Building Transformation Hierarchy

```java
// Parent object
BasicGeometry parent = new BasicGeometry(new Vector(0, 0, 0));
parent.setRotationCoefficients(0, Math.PI / 2, 0);  // 90° Y rotation
parent.calculateTransform();

// Child object
BasicGeometry child = new BasicGeometry(new Vector(5, 0, 0));
child.setScale(0.5f, 0.5f, 0.5f);

// Combine transforms: child inherits parent's transformation
TransformMatrix parentTransform = parent.getTransform(true);
child.addTransform(parentTransform);
child.calculateTransform();

// Child is now at (0, 0, 5) in world space (rotated with parent)
```

### Pattern 2: Ray-Sphere Intersection

```java
// Sphere implements Intersectable
public ContinuousField intersectAt(Producer<Ray> ray) {
    // Solve: |o + t*d - center|² = radius²
    // Quadratic: a*t² + b*t + c = 0

    Producer<Vector> oc = subtract(origin(ray), sphereCenter);

    Producer<PackedCollection<?>> a = dDotd(ray);
    Producer<PackedCollection<?>> b = multiply(c(2.0), dot(oc, direction(ray)));
    Producer<PackedCollection<?>> c = subtract(dot(oc, oc),
                                                c(radius * radius));

    // Discriminant: b² - 4ac
    Producer<PackedCollection<?>> discriminant =
        subtract(multiply(b, b), multiply(c(4.0), multiply(a, c)));

    // If discriminant >= 0, ray hits sphere
    // t = (-b ± sqrt(discriminant)) / (2a)
}
```

### Pattern 3: Field of View Calculation

```java
PinholeCamera camera = new PinholeCamera(pos, dir, up);

// Set horizontal FOV to 60 degrees
double fovRadians = Math.toRadians(60.0);
double imageWidth = 1920.0;
double focalLength = (imageWidth / 2.0) / Math.tan(fovRadians / 2.0);

camera.setFocalLength(focalLength);
```

### Pattern 4: Hardware-Accelerated Ray Generation

```java
import org.almostrealism.projection.ProjectionFeatures;

// Camera uses ProjectionFeatures for GPU acceleration
PinholeCamera.enableHardwareAcceleration = true;

Producer<Ray> ray = camera.rayAt(screenPos, screenDim);

// Internally compiles to native code:
// - Ray direction calculated on GPU
// - Batch processing of many rays
// - Significant speedup for high-resolution rendering
```

## Spatial Fields

### Discrete Field

```java
// Collection of rays representing discrete points
DiscreteField field = ...; // Implements NodeList<Producer<Ray>>

// Iterate over points
for (Producer<Ray> point : field) {
    Producer<Vector> position = origin(point);
    Producer<Vector> normal = direction(point);
    // Process each point...
}
```

### Continuous Field

```java
// Field with gradients
ContinuousField field = ...; // Extends DiscreteField + Gradient<Vector>

// Has normal directions AND gradients
Producer<Vector> gradient = field.getGradient(position);
```

## Integration with Other Modules

### Algebra Module
- Uses **Vector** for 3D positions and directions
- Uses **Scalar** for distance values
- Uses **Pair** for 2D screen coordinates

### Space Module
- **AbstractSurface** extends BasicGeometry
- Surfaces implement Intersectable for ray tracing
- **Scene** manages collections of geometric objects

### Color Module
- **ShadableIntersection** provides data for shaders
- Intersection normals used in lighting calculations

### Render Module
- **RayIntersectionEngine** uses Ray and Intersectable
- Camera provides rays for each pixel
- Reflection enables recursive ray tracing

### Physics Module
- BasicGeometry provides transform for rigid bodies
- Ray intersection for collision detection

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-algebra</artifactId>
    <version>0.72</version>
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-geometry</artifactId>
    <version>0.72</version>
</dependency>
```

## Further Reading

- See **ar-algebra** module for Vector, Scalar, and Matrix fundamentals
- See **ar-space** module for concrete surface implementations
- See **ar-color** module for shading and lighting
- See **ar-render** module for complete ray tracing pipeline
