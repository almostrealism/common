# Rendering a Simulation Frame Sequence

This guide covers how to turn a running simulation into a sequence of rendered frames — a ray-traced
image sequence, or a colour-mapped field visualisation for debugging — **without the frame loop
driving the physics**. The simulation is stepped only by `SimulationRunner` (see
[Simulation Loop Architecture](simulation-loop-examples.md)); the frame loop observes the state the
runner produces and never steps it.

## The capability in three parts

1. **`SimulationRunner.runFrames(stepsPerFrame, frameCount, frame)`** (`compute/time`) — the
   render-agnostic frame driver. It observes the simulation at fixed step intervals and invokes a
   per-frame hook. This is where the host boundary between the physics step and whatever consumes a
   frame lives.
2. **`RayTracedFrameSequence`** (`engine/render`) — the ray-traced implementation of that hook:
   reconstruct a `Scene` from the state, ray trace it, and hand each frame to an `ImageSequence`.
4. **`ImageSequence`** (`domain/color`) — the single owner of frame-sequence output: the output
   directory, zero-padded (optionally prefixed) frame naming, encoding each frame through
   `ImageCanvas`, tracking which frames were written, and the `ffmpeg` encode script bounded to the
   contiguous run on disk. It is renderer-agnostic — a caller hands it an `RGB[][]` (or image
   producer) however it was produced — so every frame-sequence writer shares it, and there is one
   way to write a frame sequence.
3. **`RGBFeatures.heatmap` / `RGBFeatures.saveHeatmap`** (`domain/color`) — a colour-mapped heatmap of
   a 2D `PackedCollection` field, for inspecting a height field or scalar field directly.

The split follows the dependency graph: `compute/time` has no dependency on `engine/render` or
`domain/color`, so the frame driver is a plain `IntConsumer` hook; the ray-traced and colour-map
implementations live in the render and colour modules that may depend on it.

## Driving frames — `runFrames`

```java
// SimulationRunner.java
public void runFrames(int stepsPerFrame, int frameCount, IntConsumer frame)
```

`runFrames` attaches a boundary listener at interval `stepsPerFrame` and runs
`frameCount * stepsPerFrame` steps. A boundary listener is due before steps
`0, stepsPerFrame, 2 * stepsPerFrame, ...`, so the hook sees:

- **frame `0`** at the **initial condition** (before any step), and
- **frame `f`** after `f * stepsPerFrame` steps,

for exactly `frameCount` frames, while the device advances exactly `frameCount * stepsPerFrame`
steps. The frame index passed to the hook is `step / stepsPerFrame`. Call it on a runner that has not
been advanced (or has just been `reset()`), since the frame index is derived from the global step
counter.

The hook is a `java.util.function.IntConsumer` — it is told only the frame index and is free to do
any host-side work with the current state: rebuild geometry and render it, write a field image,
checkpoint, log. The one rule is that **the hook must not advance the simulation**, which is what
keeps `SimulationRunner` the sole owner of the step loop.

## Ray-traced frames — `RayTracedFrameSequence`

```java
// engine/render
RayTracedFrameSequence driver = new RayTracedFrameSequence(
        runner,            // SimulationRunner — owns the physics step loop
        stepsPerFrame,     // steps between frames
        frameCount,        // number of frames
        sceneForFrame,     // IntFunction<Scene<? extends ShadableSurface>>: rebuild the scene from state
        params,            // RenderParameters: resolution and supersampling
        outputDirectory);  // File: where frames and encode.sh are written
driver.run();
```

`run()` prepares the `ImageSequence`'s output directory, calls
`runner.runFrames(stepsPerFrame, frameCount, this::renderFrame)`, and then writes the encode script
through the sequence. For each frame it:

1. calls `sceneForFrame.apply(frameIndex)` — the place a caller rebuilds renderable geometry from the
   current simulation state (for example tessellating a height field into a mesh); the returned
   `Scene` carries its own camera;
2. renders the frame to pixels with `RayTracedScene.render()`;
3. hands the pixels to the `ImageSequence`, which encodes them to `frame_<zero-padded index>.png` and
   tracks the written file.

After the last frame the `ImageSequence` writes `encode.sh`, an `ffmpeg` command whose input pattern
`frame_%0<width>d.png` with `-start_number 0 -frames:v <count>` references exactly the zero-padded
frames on disk, so the frame list matches. The frame rate defaults to
`ImageSequence.DEFAULT_FRAME_RATE` and is settable with `setFrameRate(int)`.

The actual ray trace is the overridable `renderScene(Scene)` template-method hook, returning the
frame's pixels; the directory, naming, tracking and encode script all belong to the shared
`ImageSequence` and are independent of it.

> **Note on the lit render path.** Rendering a lit scene through `RayTracedScene` goes through
> `LightingEngineAggregator`, which is a **known issue** in the default lanes (the single-pixel
> render in `SimpleRenderTest` is likewise a known issue). `RayTracedFrameSequence` is ready for that
> path; the frame orchestration is tested independently of it by overriding `renderScene`, and the
> end-to-end `RayTracedFrameSequenceTest.rendersWithRayTracer` is a known issue until the render path
> works.

## Field visualisation — heatmaps

To inspect a 2D field (a height field, a scalar field) directly, map it to a colour-mapped image.
The colour mapping is a `Producer`; the PNG write happens at the host boundary.

```java
// RGBFeatures — a mixin; a type that needs these implements RGBFeatures
CollectionProducer image = heatmap(cp(field), min, max);          // (height, width, 3), blue -> red
saveHeatmap(new File("frame.png"), "png", cp(field), min, max).get().run();  // write, fixed scale
saveHeatmap(new File("frame.png"), "png", cp(field)).get().run();            // write, auto scale
```

- `heatmap(field, min, max)` normalises each value to `(value - min) / (max - min)`, clamps it to
  `[0, 1]`, and interpolates from blue (low) to red (high). An overload takes explicit `low`/`high`
  `RGB` endpoints.
- A **fixed** `min`/`max` gives every frame of a sequence a **shared scale**, so colours are
  comparable across frames.
- `saveHeatmap(file, format, field)` without a range scales each frame to its own min and max.

## Worked example — a diffusing field to a frame sequence

A 2D field diffuses under `SimulationRunner`; at each frame the field is written as a heatmap with a
shared scale, so the frames form a comparable sequence.

```java
int gw = 64, gh = 64, n = gw * gh;
PackedCollection operator = diffusionOperator(gw, gh, 0.1);  // (n, n) stencil
PackedCollection state = initialField(n);                    // (n)
PackedCollection next = new PackedCollection(shape(n));

Temporal diffusion = () -> {
    OperationList tick = new OperationList("Diffusion");
    tick.add(a("diffuse", cp(next), matmul(cp(operator), cp(state))));  // coupled: reads neighbours
    tick.add(a("copyBack", cp(state), cp(next)));                        // copy-back in a later op
    return tick;
};

SimulationRunner runner = new SimulationRunner(diffusion, 1e-3);
File dir = new File("out");

runner.runFrames(50, 20, frameIndex -> {
    CollectionProducer field = cp(state).reshape(shape(gh, gw));
    saveHeatmap(new File(dir, String.format("field_%03d.png", frameIndex)), "png",
            field, 0.0, 1.0).get().run();   // shared [0, 1] scale across all frames
});
```

Twenty frames are written, 50 steps apart, all on the same colour scale; the runner steps the
coupled diffusion and the frame hook only reads the state to colour it. Swapping the heatmap hook for
a `RayTracedFrameSequence` (rebuilding a mesh from the field each frame) renders the same sequence as
ray-traced images instead.

## Honest image encoding

Frames are written as PNG. `ImageCanvas`'s image encoding constant for this is `PNGEncoding`
(`ImageCanvas.encodeImageFile(image, file, ImageCanvas.PNGEncoding)` writes PNG bytes via `ImageIO`),
and frame files carry a `.png` extension so the name matches the bytes. `ImageSequence` is the one
place that applies this per frame of a sequence.

## One frame-sequence writer

`ImageSequence` is the single implementation of writing an image frame sequence. `RayTracedAnimation`
(whose base `space.Animation.run()` steps rigid-body physics itself) and `RayTracedFrameSequence`
(driven by `SimulationRunner`) differ only in **what advances time between frames** — the physics
clock — and both delegate the frame output (naming, tracking, the PNG encode, and the `ffmpeg`
encode script) to an `ImageSequence`. `texture.Animation` is a different concept — an unbounded,
caller-named frame renderer with no encode script — and uses the shared low-level
`ImageCanvas.encodeImageFile` primitive directly rather than an `ImageSequence`.

## Related files

| File | Role |
|---|---|
| `compute/time/.../SimulationRunner.java` | `runFrames` — the render-agnostic frame driver |
| `engine/render/.../RayTracedFrameSequence.java` | `SimulationRunner`-driven ray-traced frame sequence |
| `engine/render/.../RayTracedAnimation.java` | physics-driven ray-traced frame sequence |
| `engine/render/.../RayTracedScene.java` | `render` — the shared per-frame render stage |
| `domain/color/.../ImageSequence.java` | the single frame-sequence writer (naming, tracking, encode script) |
| `domain/color/.../RGBFeatures.java` | `heatmap` / `saveHeatmap` — field visualisation |
| `domain/color/.../ImageCanvas.java` | `PNGEncoding` and `encodeImageFile` |

## See Also

- [Simulation Loop Architecture](simulation-loop-examples.md) — the step contract and `SimulationRunner`
- [PackedCollection Examples](packed-collection-examples.md) — the Producer pattern for collection data
