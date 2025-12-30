# Almost Realism Color Module (`ar-color`)

The Color Module provides comprehensive color representation, shading systems, lighting models, and texture support for the Almost Realism rendering pipeline. It bridges low-level color arithmetic with high-level rendering abstractions through composable shaders and physically-based lighting.

## Purpose

This module exists to:

1. **Represent Colors** - RGB/RGBA models with configurable precision and operations
2. **Model Light Sources** - Point lights, ambient lights, directional lights with attenuation
3. **Enable Surface Shading** - Composable shader system for different lighting models
4. **Apply Textures** - Image-based and procedural texture mapping
5. **Handle Image I/O** - Load/save images and convert between color formats

## What It Provides

### 1. Color Representation

```java
import org.almostrealism.color.RGB;
import org.almostrealism.color.RGBA;
import static org.almostrealism.color.RGBFeatures.*;

// Create colors (0.0 to 1.0 range)
RGB red = new RGB(1.0, 0.0, 0.0);
RGB green = new RGB(0.0, 1.0, 0.0);
RGB blue = new RGB(0.0, 0.0, 1.0);

// From wavelength (350-780 nm)
RGB spectralRed = new RGB(650.0);  // Red wavelength

// With transparency
RGBA semiTransparent = new RGBA(1.0, 0.0, 0.0, 0.8);

// Using features
CollectionProducer white = white();
CollectionProducer black = black();
CollectionProducer custom = rgb(0.5, 0.7, 0.2);
```

### 2. Color Arithmetic

```java
// Addition
RGB mixed = red.add(blue);          // Creates purple
red.addTo(blue);                    // In-place addition

// Multiplication (scaling)
RGB dimRed = red.multiply(0.5);     // 50% brightness
red.multiplyBy(2.0);                // In-place scaling

// Division
RGB normalized = color.divide(255.0);

// Subtraction
RGB difference = color1.subtract(color2);
```

### 3. Lighting System

```java
import org.almostrealism.color.Light;
import org.almostrealism.color.PointLight;
import org.almostrealism.color.LightingContext;

// Create a point light
Light light = new PointLight(
    new Vector(5, 5, 5),           // Position
    1.0,                            // Intensity
    new RGB(1.0, 1.0, 1.0)         // White light
);

// Set distance attenuation: intensity = color / (da + db*d + dc*d^2)
light.setAttenuationCoefficients(
    0.0,   // Constant term (da)
    0.0,   // Linear term (db)
    1.0    // Quadratic term (dc)
);

// Create lighting context
LightingContext context = new LightingContext();
context.setLight(light);
context.setLightDirection(directionProducer);
```

### 4. Shading Models

```java
import org.almostrealism.color.computations.*;

// Diffuse (Lambertian) shading
Shader<ShaderContext> diffuse = new DiffuseShader();
Producer<RGB> color = diffuse.shade(context, normalField);

// Phong highlights
HighlightShader specular = new HighlightShader();
specular.setHighlightColor(new RGB(1.0, 1.0, 1.0));
specular.setExponent(32.0);  // Shininess

// Compose shaders
ShaderSet<ShaderContext> combined = new ShaderSet<>();
combined.add(diffuse);
combined.add(specular);

// Blending shader (cartoon/stylized rendering)
BlendingShader toon = new BlendingShader(
    new RGB(1.0, 0.5, 0.0),  // Hot color (lit)
    new RGB(0.2, 0.1, 0.0)   // Cold color (dark)
);
```

### 5. Texture Mapping

```java
import org.almostrealism.texture.ImageTexture;

// Image-based texture
ImageTexture texture = new ImageTexture(
    ImageTexture.SPHERICAL_PROJECTION,
    new URL("http://example.com/texture.jpg"),
    1.0,   // U scale
    1.0,   // V scale
    0.0,   // U offset
    0.0    // V offset
);

// Get color at 3D point
RGB colorAtPoint = texture.operate(pointVector);

// Available projection modes:
// - SPHERICAL_PROJECTION
// - PLANAR_XY_PROJECTION
// - PLANAR_XZ_PROJECTION
// - PLANAR_YZ_PROJECTION
```

### 6. Image I/O

```java
import org.almostrealism.color.GraphicsConverter;
import static org.almostrealism.color.RGBFeatures.*;

// Load image as RGB array
PackedCollection image = GraphicsConverter.loadRgb(new File("input.png"));

// Save RGB array as image
Supplier<Runnable> saveOp = saveRgb("output.png", colorProducer);
saveOp.get().run();

// Convert between formats
RGB rgb = GraphicsConverter.convertToRGB(awtColor);
Color awtColor = GraphicsConverter.convertToAWTColor(rgb);

// Load as raw channels
PackedCollection channels = channels(new File("image.png"));
```

## Key Interfaces

### RGB and RGBA

```java
public class RGB extends PackedCollection {
    public RGB(double r, double g, double b);      // From components
    public RGB(double wavelength);                 // From wavelength (350-780 nm)

    public double getRed();
    public double getGreen();
    public double getBlue();

    public RGB add(RGB color);
    public RGB subtract(RGB color);
    public RGB multiply(double scalar);
    public RGB divide(double scalar);

    public void addTo(RGB color);                  // In-place
    public void multiplyBy(double scalar);         // In-place
}

public class RGBA extends RGB {
    public RGBA(double r, double g, double b, double a);

    public double getAlpha();
    public void setAlpha(double alpha);
}
```

### Light

```java
public interface Light extends Node, Colorable {
    Producer<RGB> getColorAt(Producer<Vector> point);  // Color with attenuation

    void setAttenuationCoefficients(double da, double db, double dc);
    void setIntensity(double intensity);
    void setColor(RGB color);
}
```

### Shader

```java
public interface Shader<C extends LightingContext> extends Node, Editable {
    Producer<RGB> shade(C context, DiscreteField normals);
}
```

### Texture

```java
public interface Texture extends Node {
    RGB operate(Vector point);  // Get color at 3D point
}
```

## Shading Pipeline

The typical rendering flow:

```
1. Ray intersects surface
   ↓
2. Compute surface normal
   ↓
3. Create ShaderContext with:
   - Light sources
   - View direction
   - Surface properties
   ↓
4. Shader.shade() produces RGB
   ↓
5. Final pixel color
```

## Common Patterns

### Pattern 1: Setting Up Scene Lighting

```java
// Create multiple lights
Light keyLight = new PointLight(new Vector(5, 5, 5), 1.0, white);
Light fillLight = new PointLight(new Vector(-3, 2, 4), 0.5, new RGB(0.7, 0.7, 1.0));
Light backLight = new PointLight(new Vector(0, 0, -5), 0.3, white);

// Configure attenuation (quadratic falloff)
keyLight.setAttenuationCoefficients(0.0, 0.0, 1.0);
fillLight.setAttenuationCoefficients(0.0, 0.0, 1.0);

// Build lighting context
LightingContext context = new LightingContext();
context.setLight(keyLight);
context.addLight(fillLight);
context.addLight(backLight);
```

### Pattern 2: Composing Shaders

```java
// Create diffuse base
DiffuseShader diffuse = new DiffuseShader();
diffuse.setFrontShade(true);
diffuse.setBackShade(false);

// Add specular highlights
HighlightShader specular = new HighlightShader();
specular.setHighlightColor(new RGB(1.0, 1.0, 1.0));
specular.setExponent(64.0);  // Sharp highlights

// Combine into shader set
ShaderSet<ShaderContext> material = new ShaderSet<>();
material.add(diffuse);
material.add(specular);

// Apply to surface
Producer<RGB> shadedColor = material.shade(context, normalField);
```

### Pattern 3: Wavelength-Based Colors

```java
// Create spectral colors
RGB violet = new RGB(400.0);   // 400 nm
RGB blue = new RGB(470.0);     // 470 nm
RGB green = new RGB(530.0);    // 530 nm
RGB yellow = new RGB(580.0);   // 580 nm
RGB red = new RGB(650.0);      // 650 nm

// Render rainbow
for (double wavelength = 380.0; wavelength <= 780.0; wavelength += 10.0) {
    RGB color = new RGB(wavelength);
    // Use color...
}
```

### Pattern 4: Texture with Custom Projection

```java
// Load texture
ImageTexture wood = new ImageTexture(
    ImageTexture.PLANAR_XY_PROJECTION,
    new File("wood_grain.jpg").toURI().toURL(),
    2.0,   // Repeat texture 2x in U
    2.0,   // Repeat texture 2x in V
    0.0,   // No U offset
    0.0    // No V offset
);

// Apply to surface points
RGB colorAtPoint = wood.operate(surfacePoint);
```

### Pattern 5: Hardware-Accelerated Color Operations

```java
import static org.almostrealism.color.RGBFeatures.*;

// Create color producers (lazy evaluation)
CollectionProducer color1 = rgb(1.0, 0.0, 0.0);
CollectionProducer color2 = rgb(0.0, 1.0, 0.0);

// Compose operations (compiled to native code)
CollectionProducer blended = color1.multiply(c(0.5))
                                         .add(color2.multiply(c(0.5)));

// Evaluate on hardware
Evaluable<PackedCollection> eval = blended.get();
PackedCollection result = eval.evaluate();
RGB finalColor = new RGB(result, 0);
```

## Integration with Other Modules

### Geometry Module
- Uses **Vector** for light positions and directions
- Uses **Ray** for light directions and reflections
- Uses **DiscreteField** for per-point shading

### Space Module
- **ShadableSurface** interface combines Intersectable with shading
- **AbstractSurface** provides base for all renderable objects
- Surfaces reference shaders for material properties

### Collect Module
- **PackedCollection** for efficient color storage
- RGB extends PackedCollection for hardware acceleration
- Producer pattern for lazy color computation

### Render Module
- Ray tracing engine uses shaders to compute pixel colors
- **ShaderContext** passes rendering state to shaders

## Environment Configuration

The color module respects the standard hardware acceleration environment variables:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/
export AR_HARDWARE_DRIVER=native  # or opencl, metal
```

## Dependencies

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-geometry</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>

<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-stats</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Maven Dependency

```xml
<dependency>
    <groupId>org.almostrealism</groupId>
    <artifactId>ar-color</artifactId>
    <!-- Check pom.xml for current version -->
</dependency>
```

## Further Reading

- See **ar-geometry** module for Ray, Vector, and intersection concepts
- See **ar-space** module for Surface and Scene rendering
- See **ar-render** module for complete ray tracing pipeline
