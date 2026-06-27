# The Reused Kernel That Is Unsafe To Reuse

## Where it is

- **Profile file on disk:** `engine/ml/results/determinism/divergent_attnOnly_odd9_A.xml`
  (the cold compile; the same kernel object is reused by the warm compile, where it appears in
  `divergent_attnOnly_odd9_B.xml` tagged `| [MTL]`, i.e. executed-via-reuse).
- **Profile-analyzer node key:** `10832`
- **Kernel:** `f_collectionConcatenateComputation_3388` — the rotary-embedding concatenation
  (`concat (1, 2, 9, 16)`), produced from a `CollectionConcatenateComputation`.

Retrieved with `mcp__ar-profile-analyzer__get_source path:".../divergent_attnOnly_odd9_A.xml"
node_key:"10832"`.

## The kernel source (Metal), with line numbers

```
 1  #include <metal_stdlib>
 …  using metal::… ;                          (lines 2–15: the std imports)
16
17  [[kernel]] void f_collectionConcatenateComputation_3388(
17      device float *_3388_v1716 [[buffer(0)]],   // output
17      device float *_3388_v1717 [[buffer(1)]],   // input A = delegate(f_collectionAddComputation_3385)
17      device float *_3388_v1718 [[buffer(2)]],   // input B = delegate(f_packedCollectionSubset_3387)
17      device int *offsetArr [[buffer(3)]],
17      device int *sizeArr [[buffer(4)]],
17      uint global_id [[thread_position_in_grid]], uint global_count [[threads_per_grid]]) {
18    int _3388_v1716Offset = (int) offsetArr[0];
19    int _3388_v1717Offset = (int) offsetArr[1];
20    int _3388_v1718Offset = (int) offsetArr[2];
21    int _3388_v1716Size = (int) sizeArr[0];     // read, never used
22    int _3388_v1717Size = (int) sizeArr[1];     // read, never used
23    int _3388_v1718Size = (int) sizeArr[2];     // read, never used
24    _3388_v1716[((long)global_id) + _3388_v1716Offset] =
24        ((((long)global_id) % 16) < 8)
24          ? _3388_v1717[((((((long)global_id) / 16) * 8) + (((long)global_id) % 16))           % 144) + _3388_v1717Offset]
24          : _3388_v1718[((((((((long)global_id) / 16) * 8) + ((((long)global_id) % 16) - 8))   % 144) + 144) % 144) + _3388_v1718Offset];
25
26  }
```

## The line that makes it unsafe for reuse

**Line 24.**

Line 24 computes every output index from **compile-time-baked dimensions**, not from the runtime
arguments:

| literal on line 24 | what it is | where it came from at compile time |
|---|---|---|
| `% 16` | size of the concatenation axis in the *output* | output shape `(…,16)` |
| `< 8`, `* 8` | size each input contributes along that axis (half of 16) | each input's axis length = 8 |
| `% 144` | number of elements in **one input reservation** (9 × 16 = 144) | each input's element count |
| `+ 144) % 144` | wrap for the second operand, same input element count | each input's element count |

Crucially, the kernel **reads the runtime sizes on lines 21–23** (`_3388_v1716Size`,
`_3388_v1717Size`, `_3388_v1718Size`) **and never references them again.** Only the three
**offsets** (lines 18–20) are honored at runtime; the **shape/geometry** is frozen into the
constants `16`, `8`, `144` on line 24.

Therefore this compiled Scope is a correct function **only** for operands whose shapes match the ones
present when it was compiled. It is *not* a function of `sizeArr`. Reusing it for a concatenation
whose operands have any other shape (a different axis size, a different per-input contribution, or a
different per-input element count) makes line 24 read the wrong source elements — silently, because
the runtime sizes that would have told it the truth are read into dead variables on lines 21–23.

This is the literal form of the contradiction raised in review: the cache advertises this kernel as
"reusable for these inputs" via its signature, but line 24 proves it is reusable *only* for inputs of
one specific geometry. The signature is broader than the kernel's actual validity domain. The exact
signature derivation — every node that fed it, and why the geometry on line 24 is **not** encoded in
it — is in the companion file `REUSE_SIGNATURE_DERIVATION.md`.

> The same pattern (read the runtime size, then ignore it in favor of a baked-in count) is present in
> the reused reduction kernels in the same profiles — e.g. `f_collectionSumComputation_3329`
> (node `10486`) hardcodes the reduction width/stride `32` while reading `sizeArr[1]` into an unused
> `…Size` variable. The concatenation kernel is the clearest instance because it bakes in three
> distinct geometric constants.
