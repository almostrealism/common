# An Invalid Kernel: LayerNorm Reading gamma/beta From the Wrong Memory

**Mode:** instruction-set reuse **OFF** (so this is *not* a reuse bug). Two separate compiles of the
same graph diverge (`maxAbsDiff = 6.1e-4`) purely because of argument aggregation / buffer layout.

**Computation `C`:** a LayerNorm output, `C(x, gamma, beta)[i] = (x[i] − mean[i/32]) /
sqrt(var[i/32] + 1e-5) * gamma[i%32] + beta[i%32]`, where `gamma = preNorm_g` (32 elements) and
`beta = preNorm_b` (32 elements) are distinct weight reservations. Both compiles produce a kernel for
this `C`; they implement the *identical* arithmetic but fetch `gamma`/`beta` from *different* memory,
so they produce different values — at least one is not `C`.

## The two kernels (extracted with the profile analyzer `get_source`)

### Correct layout — compile A
- File: `engine/ml/results/determinism/reuseOff_divergent_A.xml`
- Node key: `7894` — `f_collectionAddComputation_2678`, `add (9, 32)`
- 5 buffers. `gamma`/`beta` live in a **dedicated** buffer `_2675_v1213`:

```c
_2678_v1221[gid + outOff] =
   ( ( -(_2658_v1192[gid/32 + o0] / 32.0) + _2661_v1197[gid + o1] )      // (x − mean)
       / pow(_2669_v1201[gid/32 + o2] / 32.0 + 1.0E-5, 0.5) )           // / sqrt(var + eps)
   * _2675_v1213[(gid % 32)      + o3]                                  // * gamma[c]
   + _2675_v1213[(gid % 32) + 32 + o3];                                 // + beta[c]
```
`_2661_v1197` (buffer 1) is the input `x`; `_2675_v1213` (buffer 3) holds `[gamma(32), beta(32)]`.

### Invalid layout — compile B
- File: `engine/ml/results/determinism/reuseOff_divergent_B.xml`
- Node key: `12024` — `f_collectionAddComputation_3771`, `add (9, 32)`
- 4 buffers. `x`, `gamma`, and `beta` are all read from **one** buffer `_3754_v2040`, with `gamma`/`beta`
  at the **hardcoded** offsets `+288` and `+320`:

```c
_3771_v2065[gid + outOff] =
   ( ( -(_3751_v2035[gid/32 + o0] / 32.0) + _3754_v2040[gid + o1] )      // x = _3754_v2040[0..287]
       / pow(_3762_v2046[gid/32 + o2] / 32.0 + 1.0E-5, 0.5) )
   * _3754_v2040[(gid % 32) + o1 + 288]                                  // gamma at x_buffer + 288
   + _3754_v2040[(gid % 32) + o1 + 320];                                 // beta  at x_buffer + 320
```

`288 = 9 × 32` is exactly the input element count, so `gamma`/`beta` are addressed as if they were
appended to `x` in the *same* reservation. The kernel reads the true size into `_3754_v2040Size`
(`= sizeArr[1]`) and then never uses it — the `+288`/`+320` are compile-time constants.

## Why this is the invalid one

`gamma` (`preNorm_g`) and `beta` (`preNorm_b`) are their own reservations; the correct kernel must read
them from those reservations (compile A's `_2675_v1213`). Compile B instead reads them from
`x_buffer + 288/+320`. That is correct **only** if aggregation physically placed `preNorm_g`/`preNorm_b`
immediately after the 288-element input inside `_3754_v2040`. The divergence proves the data those
offsets point to is not the same `gamma`/`beta` that compile A reads — so kernel B computes
`(x − mean)/sqrt(var+eps) * W + V` for some `W`,`V` that are not `preNorm_g`,`preNorm_b`, i.e. not `C`.

The companion file `INVALID_KERNEL_AGGREGATION_COMPILATION.md` explains how the compilation produced
the `+288`/`+320` layout and why it differs between two compiles of the identical graph.
