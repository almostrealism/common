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
 * Text tokenization infrastructure shared across model implementations.
 *
 * <p>This package turns a {@code String} into a sequence of integer token ids and back. It covers
 * two BPE schemes and a vocabulary-free byte tokenizer:</p>
 * <ul>
 *   <li><b>Byte-level BPE</b> (GPT-2, Qwen, Llama): every byte is remapped to a printable Unicode
 *       character through {@link org.almostrealism.ml.tokenization.ByteLevelEncoder} and BPE merges
 *       over those characters.</li>
 *   <li><b>SentencePiece-style BPE</b> (Gemma, Llama, Mistral): a word boundary is marked by
 *       {@code U+2581} rather than a byte remapping, and a character outside the vocabulary falls
 *       back to one {@code <0xNN>} token per UTF-8 byte.</li>
 *   <li><b>Byte tokenization</b>: a fixed 256-symbol vocabulary in which a token id is the unsigned
 *       UTF-8 byte value, needing no vocabulary file.</li>
 * </ul>
 *
 * <h2>Key Classes</h2>
 * <ul>
 *   <li>{@link org.almostrealism.ml.tokenization.ByteTokenizer} - the fixed 256-symbol byte
 *       tokenizer, used by the self-hosted language model</li>
 *   <li>{@link org.almostrealism.ml.tokenization.ByteLevelBPETokenizer} - abstract base for byte
 *       pair encoding that combines added-token splitting, pre-tokenization and BPE merges</li>
 *   <li>{@link org.almostrealism.ml.tokenization.SentencePieceBPETokenizer} - the SentencePiece
 *       scheme ({@code U+2581} boundary, {@code <0xNN>} byte fallback), reading the {@code ARTK}
 *       binary written by {@code scripts/export_tokenizer.py}</li>
 *   <li>{@link org.almostrealism.ml.tokenization.ByteLevelEncoder} - GPT-2 byte-to-Unicode
 *       mapping that allows BPE to operate on arbitrary byte sequences</li>
 *   <li>{@link org.almostrealism.ml.tokenization.RegexPreTokenizer} - splits input text
 *       into pre-tokens using the GPT-2/Qwen regex pattern</li>
 *   <li>{@link org.almostrealism.ml.tokenization.PreTokenizer} - interface for
 *       pre-tokenization strategies</li>
 * </ul>
 *
 * @see org.almostrealism.ml.Tokenizer
 * @see org.almostrealism.ml.qwen3.Qwen3Tokenizer
 */
package org.almostrealism.ml.tokenization;
