/*
 * Copyright 2026 Michael Murray
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

/**
 * DJL integration for the Almost Realism ML framework.
 * <p>
 * Provides a {@link org.almostrealism.ml.SentencePieceTokenizer} backed by the
 * Deep Java Library (DJL) SentencePiece runtime, adapting it to the
 * {@link org.almostrealism.ml.Tokenizer} interface used throughout the framework.
 * All external DJL types are isolated within this package and never leak into
 * higher-level modules.
 */
package org.almostrealism.ml;
