/*
 * Copyright 2026 Michael Murray
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Byte-level BPE tokenization infrastructure shared across model implementations.
 *
 * <p>This package provides the tokenization building blocks used by GPT-2, Qwen, Llama,
 * and similar byte-level BPE models. It implements the standard GPT-2 byte-to-Unicode
 * encoding and the BPE merge algorithm with a regex-based pre-tokenizer.</p>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.ml.tokenization.ByteLevelEncoder} - GPT-2 byte-to-Unicode
 *       mapping that allows BPE to operate on arbitrary byte sequences</li>
 *   <li>{@link org.almostrealism.ml.tokenization.RegexPreTokenizer} - Splits input text
 *       into pre-tokens using the GPT-2/Qwen regex pattern</li>
 *   <li>{@link org.almostrealism.ml.tokenization.PreTokenizer} - Interface for
 *       pre-tokenization strategies</li>
 *   <li>{@link org.almostrealism.ml.tokenization.ByteLevelBPETokenizer} - Base class
 *       for byte-level BPE tokenizers that combines pre-tokenization with BPE merges</li>
 * </ul>
 *
 * @see org.almostrealism.ml.Tokenizer
 * @see org.almostrealism.ml.qwen3.Qwen3Tokenizer
 */
package org.almostrealism.ml.tokenization;
