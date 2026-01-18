# Qwen3 Model

Qwen2.5-0.5B-Instruct implementation for the AR framework.

## Running Prompts

### Option 1: Command Line (Qwen3.main)

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
java -cp target/ar-ml-0.72.jar org.almostrealism.ml.qwen3.Qwen3 \
  <checkpoint.bin> <tokenizer.bin> "<prompt>"
```

### Option 2: JUnit Test (Qwen3GenerationDemo)

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml -Dtest=Qwen3GenerationDemo
```

Edit `Qwen3GenerationDemo.java` to customize prompts.

### Option 3: Programmatic Usage

```java
Qwen3Config config = new Qwen3Config(
    896, 4864, 24, 14, 2, 151936, 32768, true, 1000000.0
);

StateDictionary stateDict = new StateDictionary(WEIGHTS_DIR);
Qwen3Tokenizer tokenizer = new Qwen3Tokenizer(TOKENIZER_PATH);
Qwen3 model = new Qwen3(config, stateDict, tokenizer);

model.setTemperature(0.7);
model.run(100, "Hello", token -> System.out.print(token));

stateDict.destroy();
```

## Validation Test

Quick validation without long compilation:

```bash
export AR_HARDWARE_LIBS=/tmp/ar_libs/ && \
export AR_HARDWARE_DRIVER=native && \
mvn test -pl ml -Dtest=SimpleTransformerValidationTest
```

## Model Configuration

| Parameter | Value |
|-----------|-------|
| Dimensions | 896 |
| Layers | 24 |
| Query Heads | 14 |
| KV Heads | 2 |
| Vocab Size | 151,936 |
| RoPE Theta | 1,000,000 |

## Files

- `STATUS.md` - Implementation status and development history
- `qwen3_weights/` - Model weights (StateDictionary format)
- `qwen3_reference/` - PyTorch reference outputs for validation
