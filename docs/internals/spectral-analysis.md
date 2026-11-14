# Spectral Analysis on Regular Grids

`compute/time` provides FFTs of any dimension, and spectral analysis built on them, for fields
sampled on regular periodic grids. Everything is a `CollectionProducer` graph that compiles to
native kernels on every backend, Metal and OpenCL included; nothing is computed on the host.

| Need | Use |
|------|-----|
| 1-D FFT of one or more complex lines | `FourierTransform`, `fft(bins, input)`, `ifft(bins, input)` |
| FFT along one axis of an N-D complex grid | `fftAxis(complex, axis, inverse)` |
| 2D / 3D / N-D FFT | `fft2d(complex)`, `ifft2d(spectrum)`, `fftn(complex, inverse)` |
| Real ↔ complex | `toComplex(real)`, `realPart(complex)`, `imaginaryPart(complex)` |
| Power, magnitude, phase | `powerSpectrum`, `complexMagnitude`, `phase`, `logMagnitude` |
| Grid geometry (dimensions and spacing) | `new SpectralGrid(shape, spacing...)` |
| Wavenumber of every cell | `grid.wavenumberSquared()`, `grid.wavenumber()` |
| Radially averaged power | `radialPowerSpectrum(power, spacing...)`, `grid.radialAverage(values)`, `grid.radialBin()`, `grid.radialMembership()`, `SpectralGrid.getRadialBinWidth()`, `SpectralGrid.getRadialBinCount()` |
| Dominant wavelength of a field | `dominantWavelength(field, spacing...)`, `dominantWavenumber(field, spacing...)` |
| Peak of any radial spectrum | `spectralPeakBin(spectrum)`, `spectralPeak(spectrum, binWidth)` |
| Periodic Poisson solve `∇²p = f` | `poissonSolve(f, spacing...)`, `grid.inverseLaplacian()` |

The 1-D `fft(bins, input)` and `ifft(bins, input)` are methods of `TemporalFeatures`, and
`FourierTransform` is the computation behind every transform. Everything else in the table is a
default method of `SpectralFeatures` (`org.almostrealism.time`), except the grid geometry, which is a
`SpectralGrid`: its constructor validates the spacing, it derives the radial bin width and
count on the host from the dimensions and spacing alone, and it builds the per-cell graphs that
depend on nothing but the grid (`wavenumberSquared`, `wavenumber`, `radialBin`,
`radialMembership` and `inverseLaplacian`). `TemporalFeatures` extends
`SpectralFeatures`, so any class that implements `TemporalFeatures` (including every
`TestSuiteBase` test) has all of them.

## Conventions

- **Grid and complex grid.** A real grid has shape `[n0, n1, ...]`. A complex grid has an extra
  trailing interleaved (real, imaginary) dimension: `[n0, n1, ..., 2]`. Every transformed axis
  length must be a power of 2.
- **Spacing.** `spacing` is the physical distance between samples along each axis, *in axis
  order*: a `[rows, cols]` grid whose rows run along y takes `(dy, dx)`. A single value applies to
  every axis. Results in wavenumber or wavelength are then in physical units.
- **Normalisation.** Forward transforms are unnormalised,
  `X[k] = Σ_n x[n] e^(-2πi k·n / N)`; inverse transforms carry the full `1 / (n0 · n1 · ...)`, so
  `inverse(forward(x)) == x`. This is the numpy convention.
- **Frequency order.** Index `m` along an axis of length `n` is the signed frequency
  `m < n/2 ? m : m - n` (Nyquist `n/2` maps to `-n/2`), with angular wavenumber `2πm / (n·d)`.
  Spectra stay in this FFT order; there is no shift to centre the zero frequency.

## The FFT kernel

`FourierTransform` compiles an iterative radix-2 Cooley-Tukey FFT. Each kernel thread transforms
one line: it copies the line into the output in bit-reversed order, then runs `log2(N)` butterfly
stages in place, each an ordinary loop in the generated code. There are no function calls and no
recursion in the kernel, so it compiles on every backend. Earlier versions generated recursive
functions, which Metal and OpenCL cannot compile.

A transform of `count` lines may give a **stride** `s` (the five-argument constructor): sample `j`
of line `l` is the complex element `(l / s)·N·s + (l % s) + j·s`. That is how `fftAxis` transforms
a non-trailing axis without transposing. For `[rows, cols, 2]`, the columns pass is
`count = rows, stride = 1` and the rows pass is `count = cols, stride = cols`. The kernel signature
includes the stride, so the two passes never share a compiled kernel. `fftn` is one such pass per
axis: two kernels for 2D, three for 3D. Going from 2D to 3D needs no new code.

Parallelism is across lines. A single long line is transformed by one thread, so a 2D `N × N`
transform runs `N` threads per pass. A per-stage formulation (one thread per butterfly, one
kernel per stage) would parallelise single long transforms better. It has not been needed yet.

## Radial spectrum and dominant wavelength

`radialPowerSpectrum(power, spacing...)` averages a power spectrum over shells of constant `|k|`.
Bin `b` is the mean power of the cells whose `|k| / dk` rounds to `b`, where
`dk = 2π / max(n_a · d_a)` is the fundamental wavenumber of the longest grid extent. There are
`SpectralGrid.getRadialBinCount()` bins, enough to reach the smallest Nyquist wavenumber `min(π / d_a)`: `n/2 + 1`
for an `n × n` grid. Cells beyond the last bin (the spectrum's corners) are ignored. Bin `b`
represents wavenumber `b·dk`, i.e. wavelength `2π / (b·dk)`.

`dominantWavenumber(field, spacing...)`:

1. removes the field's mean, divides it by its largest absolute deviation, and takes the power
   spectrum of its `fftn` (the estimate does not depend on amplitude, and without the division
   the power of a small-amplitude field underflows to zero on an FP32 backend; a constant field
   has no dominant wavenumber and yields `NaN`);
2. finds the peak shell with `spectralPeakBin` (bin 0, the mean, is excluded);
3. returns the power-weighted mean of the **exact** `|k|` of the cells within one bin of the peak,
   then repeats this once with the window re-centred on that first estimate (±1.5 `dk`).

Weighting the exact per-cell `|k|`, instead of interpolating between bin centres, gives sub-bin
precision that does not depend on how the grid's wavevectors fall into shells. That matters when
the axes have different lengths or spacings. On such a grid (128 × 128, `dy = 0.25`,
`dx = 0.3`), a parabola through the bin centres was 6% off where the centroid is 2% off.
`spectralPeak(spectrum, binWidth)` is still available for radial spectra that do not come from a
grid: it fits a parabola through the peak bin and its neighbours, which is exact for a locally
quadratic spectrum.

`dominantWavelength` is `2π / dominantWavenumber`.

## Spectral Poisson solve

`poissonSolve(f, spacing...)` solves `∇²p = f` on the periodic grid: `fftn(f)` is multiplied by
the Laplacian's inverse symbol `-1/|k|²` and transformed back. The `k = 0` mode is set to zero
explicitly (a periodic solution exists only for zero-mean `f`; the symbol is
`SpectralGrid.inverseLaplacian()`), so the result has zero mean and
any mean in `f` is discarded. `|k|²` is the exact continuous symbol, so the solve is spectrally
accurate for band-limited data. It is not the inverse of a finite-difference Laplacian stencil.

## Worked example

```java
public class PatternAnalysis implements SpectralFeatures {
    /** Dominant wavelength of a height field h on a periodic [rows, cols] grid. */
    public CollectionProducer wavelength(Producer<PackedCollection> h, double dy, double dx) {
        return dominantWavelength(h, dy, dx);   // single-element producer, units of dx/dy
    }

    /** Pressure p with laplacian(p) = divergence on the same grid. */
    public CollectionProducer pressure(Producer<PackedCollection> divergence, double dy, double dx) {
        return poissonSolve(divergence, dy, dx);
    }

    /** Power spectrum in FFT order, shaped [rows, cols]. */
    public CollectionProducer spectrum(Producer<PackedCollection> h) {
        return powerSpectrum(fft2d(toComplex(h)));
    }
}

// At a host boundary (once per frame, or in a test):
double lambda = analysis.wavelength(cp(height), dy, dx).evaluate().toDouble(0);
```

## Precision and tolerances

Measured by `SpectralFeaturesTest` (`engine/utils`) on an FP32 configuration, which is what Metal
always uses and what every backend uses on a host configured for FP32. With FP64 the errors are
near double-precision rounding.

| Check | Observed (FP32) | Test tolerance |
|-------|-----------------|----------------|
| 1-D round trip, 8 × 512, CPU / GPU | 3.6e-7 / 4.8e-7 abs. | 1e-4 abs. |
| 2D round trip, 64 × 128, CPU / GPU | 4.5e-7 / 6.0e-7 abs. | 1e-4 abs. |
| 3D round trip, 8 × 16 × 32 | 2.7e-7 abs. | 1e-4 abs. |
| Forward FFT vs. direct DFT, N = 64 | 2.3e-6 abs. (values up to ~40) | 1e-3 abs. |
| Plane-wave bin energy | 1 part in 4e6 | 1e-3 rel., > 99.99% of energy in the two bins |
| Poisson manufactured solution, 64 × 64 | 2.4e-7 abs. | 1e-4 abs. |
| Dominant wavelength, noisy plane wave (λ = 7.3, between bins) | ~1% | 3% |
| Dominant wavelength, hexagonal (λ = 6, anisotropic grid) | ~2% | 3% |

Twiddle factors are computed directly as `cos`/`sin` of each angle, not by recurrence, so
rounding does not accumulate along a stage. Round-trip error grows roughly with `log2(N)`
single-precision roundings. The wavelength errors are dominated by spectral leakage, not
precision: the planted waves are deliberately not grid harmonics. A pattern that is periodic on
the grid puts its power in exact cells, and the estimate is then exact to rounding.

## Size limits

- **Flat indices are decoded in floating point.** `SpectralGrid.wavenumberSquared` turns a flat cell index
  into per-axis coordinates with `mod` and `divide`, which are exact in FP32 only for indices
  below `2^24` (`SpectralGrid.MAX_EXACT_INDEX`). A grid with more cells is rejected with an
  `IllegalArgumentException`, not left to compute wrong wavenumbers. `poissonSolve` decodes one
  index per interleaved complex element (`SpectralGrid.inverseLaplacian`), so its limit is `2^23` cells.
- **The radial spectrum uses a dense membership matrix.** `radialPowerSpectrum` averages shells
  through `[bins, cells]` intermediates (`SpectralGrid.radialMembership()`), so memory grows as `bins * cells`. A grid whose matrix
  cannot be addressed with an `int` index (for example `256^3`, which has 129 bins) is rejected.
  A segmented reduction would remove this limit; it is tracked as a follow-up.

## Gotchas found while building this

- **Element-wise producers traverse per element.** `repeat(n, p)` repeats along `p`'s traversal
  axis, and `sum(p)` / `mean(p)` reduce over its traversal item. For an element-wise result such
  as `floor(sqrt(...))`, the item is one element: `repeat` interleaves instead of tiling, and
  `mean` returns the input unchanged. Call `reshape(size)` first to reset the traversal axis to 0.
- **Isolate the reduction itself.** A reduction over the whole grid (a sum, a radial spectrum,
  an `indexOfMax`) that is embedded in a consumer is unrolled into it. Several readers multiply
  that cost, and the generated code can become too large to compile.
  `CollectionFeatures.isolated(producer)` computes it once, as its own kernel. Isolate the
  reduction, not an expression that wraps it. `mean(x)` is `sum(x).divide(n)`, so
  `isolated(mean(x))` still embeds the 16384-term sum inside the divide. On a 128 × 128 grid
  that cost about 70 s of scope building, found with an `ar-jmx` thread dump. The fix is
  `isolated(isolated(x.sum()).divide(c(n)))`.
- **`indexOfMax` of a `[B]` vector** treats each element as its own group. Reshape to
  `shape(1, B).traverse(1)` to get one group.
- **`TestFeatures.assertSimilar` accepts NaN.** Use `Assert.assertEquals(msg, expected, actual,
  delta)` when a computation could produce NaN.
- **Host-generated noise.** `randn(shape, mean, std, new Random(seed))` feeding a large FFT graph
  failed to compile on Metal. Evaluate it first, or build noise on the device (see
  `SpectralFeaturesTest.noise`).

## Related files

| File | Contents |
|------|----------|
| `compute/time/.../time/computations/FourierTransform.java` | Iterative strided FFT kernel |
| `compute/time/.../time/SpectralFeatures.java` | N-D FFT, spectra, wavelength, Poisson |
| `compute/time/.../time/SpectralGrid.java` | Grid dimensions and spacing, radial bin width and count, per-cell wavenumber, bin and inverse-Laplacian graphs |
| `compute/algebra/.../collect/CollectionFeatures.java` | `isolated(producer)` |
| `engine/utils/src/test/.../time/computations/test/SpectralFeaturesTest.java` | Tests above |
| `engine/utils/src/test/.../time/computations/test/FourierTransformTests.java` | 1-D tests, incl. `compileGpu` |
