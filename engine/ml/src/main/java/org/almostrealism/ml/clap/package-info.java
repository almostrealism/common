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
 * CLAP2 contrastive audio-text models: an audio tower and a text tower that map audio clips and
 * English captions into one embedding space, so that the cosine similarity of a clip and a caption
 * scores how well the caption describes the clip. This is the basis of zero-shot audio tagging
 * against a vocabulary an application defines and can change without retraining.
 *
 * @see org.almostrealism.ml.clap.Clap2
 * @see org.almostrealism.ml.clap.Clap2AudioEncoder
 * @see org.almostrealism.ml.clap.Clap2TextEncoder
 */
package org.almostrealism.ml.clap;
