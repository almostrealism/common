# Almost Realism Space Module (`ar-space`)

The Space Module provides 3D geometry primitives, scene management, mesh representation, and spatial acceleration structures for ray tracing and rendering. It implements concrete surface types that integrate with the geometry and color modules to enable complete 3D scene rendering.

## Purpose

This module exists to:

1. **Provide 3D Surface Primitives** - Spheres, planes, cylinders, meshes, and more
2. **Manage Scenes** - Organize surfaces, lights, and cameras
3. **Enable Ray Tracing** - Ray-surface intersection with spatial acceleration
4. **Support CSG Operations** - Constructive solid geometry (union, difference, intersection)
5. **Integrate Physics** - Rigid body surfaces with collision detection
6. **Optimize Rendering** - BSP trees, KdTrees, and bounding volumes

## What It Provides

### 1. Surface Primitives

```java
import org.almostrealism.primitives.*;
import org.almostrealism.color.RGB;
import org.almostrealism.algebra.Vector;

// Sphere
Sphere sphere = new Sphere(
    new Vector(0, 0, 0),      // Center
    1.0,                       // Radius
    new RGB(0.8, 0.2, 0.2)    // Color
);

// Plane
Plane plane = new Plane(
    new Vector(0, -1, 0),      // Position
    new Vector(0, 1, 0),       // Normal direction
    10.0,                      // Width
    10.0,                      // Height
    0.1                        // Thickness
);

// Cylinder
Cylinder cylinder = new Cylinder(
    new Vector(0, 0, 0),       // Center
    1.0,                        // Radius
    5.0                         // Height
);

// Cone
Cone cone = new Cone(
    new Vector(0, 0, 0),       // Base center
    1.0,                        // Base radius
    3.0                         // Height
);
```

### 2. Mesh Representation

```java
import org.almostrealism.space.Mesh;

// Create mesh
Mesh mesh = new Mesh();

// Add vertices
mesh.addVector(new Vector(0, 0, 0));  // Index 0
mesh.addVector(new Vector(1, 0, 0));  // Index 1
mesh.addVector(new Vector(0, 1, 0));  // Index 2

// Add triangle (vertex indices)
mesh.addTriangle(0, 1, 2);

// Set vertex colors
mesh.setColor(0, new RGB(1, 0, 0));   // Red
mesh.setColor(1, new RGB(0, 1, 0));   // Green
mesh.setColor(2, new RGB(0, 0, 1));   // Blue

// Set vertex normals
mesh.setNormal(0, new Vector(0, 0, 1));
mesh.setNormal(1, new Vector(0, 0, 1));
mesh.setNormal(2, new Vector(0, 0, 1));
```

### 3. Scene Management

```java
import org.almostrealism.space.Scene;
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.projection.PinholeCamera;

// Create scene
Scene<ShadableSurface> scene = new Scene<>();

// Add surfaces
scene.add(sphere);
scene.add(plane);
scene.add(mesh);

// Add lights
Light light1 = new PointLight(new Vector(5, 5, 5), 1.0, new RGB(1, 1, 1));
Light light2 = new PointLight(new Vector(-5, 5, 5), 0.5, new RGB(0.7, 0.7, 1.0));
scene.addLight(light1);
scene.addLight(light2);

// Set camera
PinholeCamera camera = new PinholeCamera(
    new Vector(0, 0, -10),    // Position
    new Vector(0, 0, 1),      // Look direction
    new Vector(0, 1, 0)       // Up direction
);
scene.setCamera(camera);

// Calculate bounding volume for entire scene
BoundingSolid bounds = scene.calculateBoundingSolid();
```

### 4. Surface Properties and Materials

```java
import org.almostrealism.space.AbstractSurface;
import org.almostrealism.color.computations.*;

AbstractSurface surface = sphere;  // or any AbstractSurface subclass

// Basic properties
surface.setColor(new RGB(0.8, 0.1, 0.1));
surface.setIndexOfRefraction(1.5);          // For glass, water, etc.
surface.setReflectedPercentage(0.8);        // 80% reflection
surface.setRefractedPercentage(0.2);        // 20% refraction
surface.setAbsorbtionCoefficient(0.05);     // Light absorption

// Shading
surface.addShader(new DiffuseShader());
surface.addShader(new HighlightShader());
surface.setShadeBack(true);                 // Render backfaces

// Transparency
surface.setTransmitAbsorb(true);
```

### 5. Constructive Solid Geometry (CSG)

```java
import org.almostrealism.primitives.CSG;

Sphere sphere1 = new Sphere(new Vector(-0.5, 0, 0), 1.0, new RGB(1, 0, 0));
Sphere sphere2 = new Sphere(new Vector(0.5, 0, 0), 1.0, new RGB(0, 1, 0));

// Union (combine shapes)
CSG union = new CSG(sphere1, sphere2, CSG.UNION);

// Difference (subtract sphere2 from sphere1)
CSG difference = new CSG(sphere1, sphere2, CSG.DIFFERENCE);

// Intersection (only overlapping region)
CSG intersection = new CSG(sphere1, sphere2, CSG.INTERSECTION);
```

### 6. Physics-Enabled Surfaces

```java
import org.almostrealism.primitives.*;
import org.almostrealism.physics.RigidBody;

// Rigid sphere with physics
RigidSphere rigidSphere = new RigidSphere(
    new Vector(0, 5, 0),       // Position
    1.0,                        // Radius
    new RGB(0.5, 0.5, 0.8)
);

// Set mass and inertia
rigidSphere.setMass(10.0);
rigidSphere.calculateInertialTensor();

// Apply forces
rigidSphere.addForce(new Vector(0, -9.8, 0));  // Gravity

// Rigid plane
RigidPlane ground = new RigidPlane(
    new Vector(0, -10, 0),
    new Vector(0, 1, 0),
    100.0, 100.0, 1.0
);
```

## Key Classes

### AbstractSurface

```java
public abstract class AbstractSurface extends TriangulatableGeometry
    implements ShadableSurface, RGBFeatures, Porous {

    // Material properties
    public void setColor(RGB color);
    public void setIndexOfRefraction(double ior);
    public void setReflectedPercentage(double percent);
    public void setRefractedPercentage(double percent);

    // Shading
    public void addShader(Shader shader);
    public void setShadeBack(boolean shade);

    // Geometry
    public abstract ContinuousField intersectAt(Producer<Ray> ray);
    public abstract BoundingSolid calculateBoundingSolid();
    public abstract Mesh triangulate();
}
```

### Scene

```java
public class Scene<T extends ShadableSurface> extends SurfaceList<T> {
    public void setCamera(Camera camera);
    public Camera getCamera();

    public void addLight(Light light);
    public void setLights(List<Light> lights);
    public List<Light> getLights();

    public void add(T surface);
    public List<T> getSurfaces();

    public BoundingSolid calculateBoundingSolid();
    public Scene<T> clone();
}
```

### Mesh

```java
public class Mesh extends AbstractSurface {
    public void addVector(Vector v);                  // Add vertex
    public void addTriangle(int i1, int i2, int i3); // Add triangle by indices

    public void setColor(int index, RGB color);       // Per-vertex color
    public void setNormal(int index, Vector normal);  // Per-vertex normal

    public int getCount();                            // Number of vertices
    public Vector getVector(int index);               // Get vertex position
    public Triangle getTriangle(int index);           // Get triangle

    public ContinuousField intersectAt(Producer<Ray> ray);  // Ray intersection
}
```

### Triangle

```java
public class Triangle extends AbstractSurface {
    public Triangle(Vector v1, Vector v2, Vector v3);

    public void setColor(int vertex, RGB color);     // Per-vertex color
    public void setNormal(int vertex, Vector normal); // Per-vertex normal

    public Vector getNormal();                        // Face normal
    public ContinuousField intersectAt(Producer<Ray> ray);
}
```

## Spatial Acceleration

### BSP Tree (Binary Space Partitioning)

```java
import org.almostrealism.space.SpacePartition;

// Partition surfaces for faster ray tracing
SpacePartition<ShadableSurface> partition = new SpacePartition<>(
    surfaces,
    4  // Max depth
);

// Intersection automatically uses BSP for culling
ContinuousField intersection = partition.intersectAt(ray);
```

### KdTree (Spatial Indexing)

```java
// Mesh automatically uses KdTree for vertex lookups
Mesh mesh = new Mesh();
mesh.addVector(vertex1);
mesh.addVector(vertex2);
// ... many vertices ...

// Internally uses KdTree for efficient nearest-neighbor queries
```

### Cached Intersection

```java
// Mesh uses CachedMeshIntersectionKernel for performance
// - Caches intersection results
// - Batch dimension-aware
// - Returns cached values for repeated queries (same pixel)
```

## Ray Tracing Integration

```java
// Complete rendering pipeline
Scene<ShadableSurface> scene = buildScene();

// Render using ray tracing (in render module)
RayIntersectionEngine engine = new RayIntersectionEngine(scene, fogParams);
RayTracedScene rayTracedScene = new RayTracedScene(engine, camera, params);

// Render image
OutputLine output = rayTracedScene.renderLine(y);  // Render scanline
```

## Common Patterns

### Pattern 1: Building a Complete Scene

```java
// Create scene
Scene<ShadableSurface> scene = new Scene<>();

// Ground plane
Plane ground = new Plane(
    new Vector(0, -5, 0),
    new Vector(0, 1, 0),
    100, 100, 1
);
ground.setColor(new RGB(0.3, 0.3, 0.3));
ground.addShader(new DiffuseShader());
scene.add(ground);

// Reflective sphere
Sphere sphere = new Sphere(new Vector(0, 0, 0), 2.0, new RGB(0.8, 0.2, 0.2));
sphere.setReflectedPercentage(0.5);
sphere.addShader(new DiffuseShader());
sphere.addShader(new HighlightShader());
scene.add(sphere);

// Lighting
PointLight keyLight = new PointLight(new Vector(10, 10, -10), 1.0, new RGB(1, 1, 1));
keyLight.setAttenuationCoefficients(0, 0, 0.01);
scene.addLight(keyLight);

// Camera
PinholeCamera camera = new PinholeCamera(
    new Vector(0, 0, -15),
    new Vector(0, 0, 1),
    new Vector(0, 1, 0)
);
camera.setFocalLength(35);
scene.setCamera(camera);
```

### Pattern 2: Creating Complex Meshes

```java
Mesh mesh = new Mesh();

// Add vertices in a grid
for (int y = 0; y < rows; y++) {
    for (int x = 0; x < cols; x++) {
        mesh.addVector(new Vector(x, 0, y));
    }
}

// Add triangles for grid
for (int y = 0; y < rows - 1; y++) {
    for (int x = 0; x < cols - 1; x++) {
        int i0 = y * cols + x;
        int i1 = i0 + 1;
        int i2 = i0 + cols;
        int i3 = i2 + 1;

        mesh.addTriangle(i0, i1, i2);  // Lower triangle
        mesh.addTriangle(i1, i3, i2);  // Upper triangle
    }
}

// Set normals
for (int i = 0; i < mesh.getCount(); i++) {
    mesh.setNormal(i, new Vector(0, 1, 0));
}
```

### Pattern 3: CSG Modeling

```java
// Create a sphere with a hole

// Outer sphere
Sphere outer = new Sphere(new Vector(0, 0, 0), 2.0, new RGB(0.8, 0.8, 0.8));

// Inner sphere (hole)
Sphere inner = new Sphere(new Vector(0, 0, 0), 1.5, new RGB(0.5, 0.5, 0.5));

// Hollow sphere = outer - inner
CSG hollowSphere = new CSG(outer, inner, CSG.DIFFERENCE);

scene.add(hollowSphere);
```

### Pattern 4: Triangulating Primitives

```java
// Convert primitive to mesh for export or GPU rendering
Sphere sphere = new Sphere(new Vector(0, 0, 0), 1.0, new RGB(1, 0, 0));

// Triangulate with default resolution
Mesh mesh = sphere.triangulate();

// Now mesh can be exported or rendered differently
```

### Pattern 5: Physics Simulation

```java
// Create physics scene
List<RigidBody> bodies = new ArrayList<>();

RigidSphere ball = new RigidSphere(new Vector(0, 10, 0), 1.0, new RGB(1, 0, 0));
ball.setMass(5.0);
ball.calculateInertialTensor();
bodies.add(ball);

RigidPlane ground = new RigidPlane(
    new Vector(0, 0, 0),
    new Vector(0, 1, 0),
    100, 100, 1
);
ground.setMass(Double.POSITIVE_INFINITY);  // Infinite mass (immovable)
bodies.add(ground);

// Simulation step
double dt = 0.016;  // 60 FPS
for (RigidBody body : bodies) {
    body.step(dt);
}
```

## Volume and Distance Estimation

```java
// Sphere implements DistanceEstimator
public interface DistanceEstimator {
    double estimateDistance(Ray r);
}

// Used for ray marching algorithms
Sphere sphere = new Sphere(...);
double distance = sphere.estimateDistance(ray);

// Volume interface for solid regions
public interface Volume<T> {
    boolean inside(Producer<Vector> point);
    double intersect(Vector point, Vector direction);
}
```

## Integration with Other Modules

### Geometry Module
- Uses **Ray**, **Camera**, **TransformMatrix**, **BoundingSolid**
- Implements **Intersectable** for ray-surface intersection
- Extends **BasicGeometry** for position/rotation/scale

### Color Module
- Implements **ShadableSurface** for rendering
- Uses **Shader** for lighting models
- Uses **Light** for scene illumination
- Uses **RGB** for surface colors

### Physics Module
- **RigidSphere**, **RigidPlane** integrate rigid body dynamics
- Collision detection via ray intersection
- Mass and inertia tensor calculations

### Graph Module
- **KdTree** for spatial indexing (from graph module)

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-geometry</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-color</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-physics</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-graph</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-space</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Further Reading

- See **ar-geometry** module for Ray, Camera, and intersection concepts
- See **ar-color** module for Shader, Light, and rendering
- See **ar-render** module for complete ray tracing pipeline
- See **ar-physics** module for rigid body dynamics
