# Grid Field Calculus: Finite-Difference Operators as Producers

`FieldCalculusFeatures` (`compute/algebra`, package `org.almostrealism.calculus`) provides
finite-difference operators on fields sampled over structured (regular, rectilinear) grids:
neighbour shifts with boundary conditions, gradient, divergence, Laplacian, the curvature of a
height field, and Poisson relaxation steps. Every operator returns a `CollectionProducer`, so a
stencil is a computation graph that compiles to one kernel — there is no host loop over cells.

It is a mixin like every `Features` interface: implement it. `CodeFeatures` (and therefore
`TestFeatures` / `TestSuiteBase`) already includes it. Code in `domain/physics` or any other
module above `compute/algebra` can implement `FieldCalculusFeatures` directly; it needs nothing
from `domain/graph`.

Tests: `FieldCalculusTests` (`engine/utils`, package `org.almostrealism.calculus.test`).

---

## 1. Layout conventions

| Kind | Shape | Meaning |
|------|-------|---------|
| Scalar field over a `d`-dimensional grid | `(n0, ..., n(d-1))` | every axis is a grid axis |
| Vector field over a `d`-dimensional grid | `(d, n0, ..., n(d-1))` | slice `k` of axis 0 is the component along grid axis `k` |

- **Grid spacing** is passed as `double... spacing`: one value per grid axis, or a single value
  for every axis (any other count is rejected with an `IllegalArgumentException`).
- **Index convention.** Along axis `k`, grid index `i` is the point `origin_k + i * h_k`. Axis 0
  is the slowest-varying (row-major). `gridIndex(shape, axis)` returns the index field along an
  axis, from which coordinate fields and analytic initial conditions are built on the device:

```java
TraversalPolicy shape = shape(ny, nx);
CollectionProducer y = gridIndex(shape, 0).multiply(h).add(y0);
CollectionProducer x = gridIndex(shape, 1).multiply(h).add(x0);
PackedCollection f = sin(x.multiply(kx)).multiply(sin(y.multiply(ky))).evaluate();
```

- **Orderings.** An input whose shape carries a `TraversalOrdering` is read in its logical
  layout; outputs carry no ordering.

---

## 2. API

### Shifts and boundary conditions

```java
CollectionProducer shift(Producer<PackedCollection> field, int axis, int offset, GridBoundary boundary)
```

The result at index `i` along `axis` is the field at `i + offset` (so `offset = +1` reads the next
cell). `|offset|` may not exceed the length of the axis. The `|offset|` layers of cells that enter
from beyond the edge are **ghost cells** supplied by a `GridBoundary`:

| Boundary | Ghost cells | Discrete meaning |
|----------|-------------|------------------|
| `GridBoundary.periodic()` | the cells from the opposite edge | the grid wraps around |
| `GridBoundary.clamped()` | copies of the adjacent edge cell | zero gradient across the edge (homogeneous Neumann) |
| `GridBoundary.fixed(v)` | the value `v` | Dirichlet: the grid points are the *interior* nodes, and the boundary nodes one spacing outside the first and last points hold `v` |

`shift` is a composition of existing operations, not a new kernel: the field is extended with
`concat(axis, field, ghost)` (or `concat(axis, ghost, field)` for a negative offset) and the
result is a `subset` of the extension. Ordering handling and derivatives are therefore those of
`concat` and `subset`. A new boundary condition is a subclass of `GridBoundary` implementing
`ghost(field, axis, depth, upper)`; the layers of the field along an axis are available to it as
`slice(axis, start, length, field)` from `SlicingFeatures`.

Note on `fixed(v)` and cell-centred grids: `fixed(v)` makes the *ghost value* `v`. If the grid
points are cell centres and `v` is meant to hold on the cell face half a spacing outside, the
correct ghost value is `2v - f_edge`, which is a different boundary condition; write it as a
`GridBoundary` subclass if you need it.

### Differences

| Method | Formula | Accuracy |
|--------|---------|----------|
| `forwardDifference(f, axis, h, b)` | `(f[i+1] - f[i]) / h` | 2nd order at the face `i+1/2` |
| `backwardDifference(f, axis, h, b)` | `(f[i] - f[i-1]) / h` | 2nd order at the face `i-1/2` |
| `centralDifference(f, axis, h, b)` | `(f[i+1] - f[i-1]) / 2h` | 2nd order |
| `secondDifference(f, axis, h, b)` | `(f[i+1] - 2f[i] + f[i-1]) / h^2` | 2nd order |

### Vector calculus

| Method | Result |
|--------|--------|
| `gradient(f, b, h...)` | vector field `(d, n0, ...)` of central differences |
| `component(v, k)` | component `k` of a vector field, shape `(n0, ...)` |
| `divergence(v, b, h...)` | scalar field: `sum_k centralDifference(v_k, k)` |
| `laplacian(f, b, h...)` | `sum_k secondDifference(f, k)`: 5-point in 2D, 7-point in 3D |
| `ninePointLaplacian(f, b, h)` | 2D only, equal spacing: `(4(N+S+E+W) + (NE+NW+SE+SW) - 20C) / 6h^2` |

`divergence(gradient(f))` is **not** `laplacian(f)`: it is the wide stencil
`(f[i+2] - 2f[i] + f[i-2]) / 4h^2` per axis, which does not see the highest grid frequency (a
checkerboard is in its null space). Use `laplacian` when you want the Laplacian.

The 9-point stencil is also second order; its leading error is the isotropic `h^2/12 ∇⁴f`, so it
does not favour the grid axes. For a mode `sin(kx x) sin(ky y)` that is
`(kx²+ky²)² h²/12`, against the 5-point stencil's `(kx⁴+ky⁴) h²/12` — the 9-point stencil is not
smaller in error, only rotationally symmetric.

### Height-field curvature

```java
CollectionProducer heightFieldCurvature(Producer<PackedCollection> height, GridBoundary b, double... h)
CollectionProducer linearizedHeightFieldCurvature(Producer<PackedCollection> height, GridBoundary b, double... h)
```

`heightFieldCurvature` returns `kappa = div( grad h / sqrt(1 + |grad h|^2) )` for the surface
`z = h(x)`. **This is the sum of the principal curvatures, i.e. twice the mean curvature**, the
quantity in the Young–Laplace pressure jump `σκ`. Sign: a surface convex upward has negative
`kappa`; the top of a sphere of radius `R` has `kappa = -2/R` (mean curvature magnitude `1/R`).

It is discretised in conservative flux form: for each axis `k` the flux
`h_k / sqrt(1 + |grad h|^2)` is evaluated on both faces of every cell, with the normal derivative
a one-sided difference across the face and each tangential derivative the average of the central
differences of the two cells sharing the face. For small slopes the flux reduces to the one-sided
difference, so the operator reduces **exactly** to the compact `laplacian`, which is what
`linearizedHeightFieldCurvature` returns. Second-order accurate (measured, §4).

### Poisson relaxation, `laplacian(p) = f`

| Method | Result |
|--------|--------|
| `jacobiStep(p, f, b, omega, h...)` | weighted Jacobi: `(1-ω) p + ω (Σ_k (p[+k]+p[-k])/h_k² - f) / Σ_k 2/h_k²` |
| `redBlackGaussSeidelStep(p, f, b, omega, h...)` | one full red-black Gauss–Seidel sweep (SOR for `ω > 1`) |
| `gaussSeidelHalfStep(p, f, b, omega, parity, h...)` | relax only the cells with coordinate-sum parity `parity` |
| `poissonPointUpdate(p, f, b, h...)` | the value each cell would need to satisfy the equation given its neighbours |
| `poissonResidual(p, f, b, h...)` | `f - laplacian(p)` |
| `poissonResidualNorm(p, f, b, h...)` | RMS residual, shape `(1)` |
| `checkerboard(shape, parity)` | mask, `1` where the coordinate sum has the given parity |

The relaxation stencil is the one of `laplacian`, and `b` sets the problem's boundary condition:
`fixed(v)` for Dirichlet, `clamped()` for homogeneous Neumann, `periodic()`. (With Neumann or
periodic boundaries the solution is defined only up to a constant and `f` must have zero mean.)

`redBlackGaussSeidelStep` is a **single producer**: the black half takes the red half as its
input producer rather than a stored collection, so the whole sweep compiles to one kernel. One
sweep reduces the smooth error about as much as two Jacobi steps; with
`ω = 2 / (1 + sin(π h))` (optimal SOR on the unit square) it converges far faster.

**Every relaxation step is globally coupled** (each cell reads its neighbours' current values).
Under the step contract in [simulation-loop-examples.md](simulation-loop-examples.md), write the
result to a next-state collection and copy it back in a later operation of the same step. Never
assign it to `p` in the same operation, and never wrap it in `buffer(n)`/`Loop`.

---

## 3. Worked example: Poisson solve as a `SimulationRunner` system

Solve `laplacian(p) = -2π² sin(πx) sin(πy)` on the unit square with `p = 0` on the boundary
(exact solution `sin(πx) sin(πy)`), recording the residual every 15 sweeps. This is
`FieldCalculusTests.poissonRelaxationConverges`.

```java
int n = 15;                                   // interior nodes per axis
double h = 1.0 / (n + 1);                     // x_i = (i + 1) h
TraversalPolicy shape = shape(n, n);
GridBoundary dirichlet = GridBoundary.fixed(0.0);

PackedCollection f = sin(gridIndex(shape, 0).multiply(h).add(h).multiply(Math.PI))
        .multiply(sin(gridIndex(shape, 1).multiply(h).add(h).multiply(Math.PI)))
        .multiply(-2 * Math.PI * Math.PI).evaluate();
PackedCollection p = new PackedCollection(shape);
PackedCollection next = new PackedCollection(shape);
double omega = 2.0 / (1.0 + Math.sin(Math.PI * h));

Temporal system = () -> {
    OperationList tick = new OperationList("Poisson relaxation");
    tick.add(a("relax", cp(next), redBlackGaussSeidelStep(cp(p), cp(f), dirichlet, omega, h)));
    tick.add(a("copyBack", cp(p), cp(next)));      // copy back in a later operation
    return tick;
};

SimulationRunner runner = new SimulationRunner(system, 1.0);
SimulationRunner.Recording residual =
        runner.record(poissonResidualNorm(cp(p), cp(f), dirichlet, h), 15, 20);
runner.run(300);
PackedCollection norms = residual.getBuffer();   // host read only after run returns
runner.destroy();
```

Measured on this repository's test host (Metal / CPU): after 300 steps the RMS residual is about
`3e-2` for Jacobi and `1.4e-4` for (unweighted) red-black Gauss–Seidel; SOR converges to the
discrete solution, which matches the analytic one to `3e-3`, the expected `O(h²)` discretization
error.

A single relaxation at a **host step boundary** (for example a one-off correction before a
simulation starts) is simply evaluated into a new collection:
`PackedCollection relaxed = jacobiStep(cp(p), cp(f), b, w, h).evaluate();`. Anything more than a
few steps belongs in a `SimulationRunner`, as above; do not write the iteration loop yourself.

A time-stepping system that needs curvature or a Poisson solve per step composes these operators
inside its own `tick()` exactly like this.

---

## 4. Precision and accuracy

- **Order.** `FieldCalculusTests.secondOrderConvergence` measures, on periodic grids of
  16, 32, 64: observed order ≈ 1.98–2.00 for the 5-point and 9-point Laplacians, ≈ 1.92–1.98 for
  `divergence(gradient(...))`, ≈ 1.99 for `heightFieldCurvature` of `0.3 sin x sin y`.
- **Spherical cap.** `heightFieldCurvature` of `sqrt(1 - r²)` on `[-0.4, 0.4]²`, 33×33, clamped:
  max interior error `7e-4` against `-2`.
- **Exact discrete eigenvalues.** On periodic grids the 5-point Laplacian of `sin(kx x) sin(ky y)`
  equals `-4 (sin²(kx h/2) + sin²(ky h/2)) / h²` times the field to rounding; the tests assert this
  to `1e-3`.
- **Boundaries.** Under `periodic()` the operators are second order everywhere. Under `clamped()`
  and `fixed(v)` they are exact discrete operators of the ghost-cell problem in §2, but a
  derivative of a field that does *not* satisfy that boundary condition is first order (or
  wrong) in the first cell from the edge. Analytic checks should exclude a margin of one cell
  (two for curvature).
- **FP32.** Metal computes in single precision. Rounding in a second difference grows like
  `ε |f| / h²`; at `ε ≈ 1e-7` this stays well below truncation error for the grid sizes above
  (n ≲ 100 on an O(1) domain), but very fine grids or many accumulated relaxation sweeps reach the
  rounding floor (the RMS residual stops falling around `1e-4` for an O(1) solution with
  `h = 1/16`). Use a CPU (FP64) backend for validation runs that need more.
- **Building index fields.** `gridIndex` computes `floor(index / stride)`; dividing by the
  stride, rather than multiplying by its reciprocal, keeps exact multiples exact in FP32.

---

## 5. Automatic differentiation

The operators are differentiable. They are compositions of `concat`, `subset`, `constant` and
element-wise arithmetic, all of which implement `delta` (see
[automatic-differentiation.md](automatic-differentiation.md)). `FieldCalculusTests.
laplacianJacobianIsStencil` checks that `laplacian(x, periodic(), 1).delta(x)` on a 4×4 grid is
exactly the stencil matrix (`-4` on the diagonal, `1` at each periodic neighbour). Only the
Laplacian's Jacobian is asserted; the others follow from the same building blocks but are not
individually tested. `jacobianGradient` on large grids materializes an `N × N` Jacobian; for a
backward pass through a stencil prefer the reduced delta (`delta(target, ReduceAxis.OUT, weight)`).

---

## 6. Known limits

1. **Deep nesting compiles slowly in 3D.** Each shift inlines its input's index expression, so a
   composition such as `divergence(gradient(f))` on a 3D grid nests shifts of shifts deeply
   enough that index-expression simplification takes minutes (it exceeded two minutes in
   `FieldCalculusTests.laplacian3D` before the gradient was stored). In 2D the same composition
   compiles in seconds. When composing operators in 3D, store the intermediate field (assign it
   to a collection in an earlier operation of the step) and apply the next operator to that.
2. **The 9-point Laplacian is 2D only** and needs equal spacing on both axes. There is no 19- or
   27-point 3D stencil.
3. **No staggered (MAC) grids.** All operators are collocated: every field lives at the grid
   points. Face-centred velocities for an incompressible projection would need face-offset
   variants of `divergence` and `gradient`.
4. **No multigrid.** Only single-level Jacobi and red-black Gauss–Seidel/SOR are provided; their
   convergence slows like `h²` per sweep on fine grids.
5. **Red-black ordering assumes the compact stencil.** `redBlackGaussSeidelStep` is a true
   Gauss–Seidel sweep only for the 5-/7-point `laplacian` stencil it uses (red cells read only
   black neighbours).

---

## Related files

| File | Content |
|------|---------|
| `compute/algebra/src/main/java/org/almostrealism/calculus/FieldCalculusFeatures.java` | the operators |
| `compute/algebra/src/main/java/org/almostrealism/calculus/GridBoundary.java` | boundary conditions as ghost cells |
| `engine/utils/src/test/java/org/almostrealism/calculus/test/FieldCalculusTests.java` | analytic, convergence, boundary, ordering, relaxation, derivative and backend tests |
| `compute/time/src/main/java/org/almostrealism/time/SimulationRunner.java` | the loop owner for relaxation and time stepping |

## See also

- [Simulation Loop Examples](simulation-loop-examples.md) — the step contract the relaxation steps obey
- [Automatic Differentiation](automatic-differentiation.md)
- [Shape and Traversal](shape-and-traversal.md)
