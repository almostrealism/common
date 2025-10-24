# AR-ML Development Notes for Claude Code

## Hardware Acceleration Setup

⚠️ **CRITICAL**: Before running any hardware-accelerated code in the ar-ml package, you MUST set the following environment variables:

```bash
export AR_HARDWARE_LIBS=/home/developer/.libs/
export AR_HARDWARE_DRIVER=native
```

### Setup Instructions

1. **Create the hardware libs directory** (if not already present):
   ```bash
   mkdir -p /home/developer/.libs
   ```

2. **Set environment variables** before running Java code:
   ```bash
   export AR_HARDWARE_LIBS=/home/developer/.libs/
   export AR_HARDWARE_DRIVER=native
   ```

3. **For Maven tests**, add to `pom.xml` or set in your shell:
   ```xml
   <systemPropertyVariables>
     <AR_HARDWARE_LIBS>/home/developer/.libs/</AR_HARDWARE_LIBS>
     <AR_HARDWARE_DRIVER>native</AR_HARDWARE_DRIVER>
   </systemPropertyVariables>
   ```

### What These Variables Do

- **`AR_HARDWARE_LIBS`**: Specifies the directory where hardware acceleration libraries (JNI .so files, OpenCL kernels, etc.) will be generated and loaded from
- **`AR_HARDWARE_DRIVER`**: Specifies which hardware backend to use:
  - `native`: Standard JNI operations with runtime-generated native code
  - `opencl`: OpenCL acceleration (CPU/GPU)
  - `metal`: Metal GPU acceleration (Apple Silicon)
  - `external`: Generated executable approach

### Common Issues

❌ **Forgetting to set these variables** will result in:
- Runtime errors when trying to compile operations
- Missing library errors
- Failures during model inference

✅ **Always verify** these are set before running:
```bash
echo $AR_HARDWARE_LIBS
echo $AR_HARDWARE_DRIVER
```

## Qwen3 Implementation

See [PLAN.md](./PLAN.md) for the complete implementation plan for Qwen3-Instruct-2507 4B.

### Quick Start

1. Ensure hardware acceleration is configured (see above)
2. Follow the implementation phases in PLAN.md
3. Start with Phase 1: Core Architecture Setup

### Development Workflow

1. **Before coding**: Set environment variables
2. **During development**: Run tests frequently with proper env vars
3. **Before committing**: Verify all tests pass with hardware acceleration enabled

## Package Structure

```
org.almostrealism.ml/
├── qwen3/
│   ├── Qwen3Config.java
│   ├── Qwen3Weights.java
│   ├── Qwen3.java
│   └── Qwen3Tokenizer.java
├── AttentionFeatures.java
├── LayerFeatures.java
├── AutoregressiveModel.java
└── BPE.java
```

## Testing

Run tests with environment variables:
```bash
export AR_HARDWARE_LIBS=/home/developer/.libs/
export AR_HARDWARE_DRIVER=native
mvn test -pl ml
```

## References

- [Qwen3 Technical Report](../qwen3/Qwen3_Technical_Report.pdf)
- [Llama2 Reference Implementation](../../llama2/)
- [AR Framework Documentation](../../README.md)
