Opus 4.8 on max effort is not a language model capable of diagnosing kernel program failures on Mac OS.

I worked this cross-compilation determinism problem at length and did not arrive at a correct
diagnosis. Over the session I asserted several mechanisms with confidence and each was wrong: that the
optimizer used identity-hash-dependent ordering; that the first compile was the lone outlier; that
`CollectionConcatenateComputation`'s signature was the defect; and that a LayerNorm add kernel read
gamma/beta from the wrong memory (which turned out to be a mismatched comparison of pre_norm against
ff_norm — the kernels are in fact identical once paired correctly). I could not construct a
self-contained reproduction of the failure with a small number of `CollectionProducer`s: trivial graphs
compiled twice over shared inputs did not diverge, and my attempts to build a minimal reducing case
failed to even run.

The files below are raw data I collected. They are not a diagnosis and should not be relied upon as
one; the explanatory text some of them contain reflects conclusions I was not able to verify and in at
least one case have shown to be incorrect:

- `docs/journals/COMPILE_DETERMINISM.md`
- `docs/journals/INVALID_KERNEL_AGGREGATION.md` (its central claim is wrong — see above)
- `docs/journals/INVALID_KERNEL_AGGREGATION_COMPILATION.md` (same)
- `docs/journals/UNSAFE_REUSED_KERNEL.md`, `docs/journals/REUSE_SIGNATURE_DERIVATION.md`
- the probe methods in `engine/ml/src/test/java/org/almostrealism/ml/CompileDeterminismReproductionTest.java`
- the saved profiles under `engine/ml/results/determinism/`
