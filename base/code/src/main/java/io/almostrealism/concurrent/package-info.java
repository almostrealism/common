/*
 * Copyright 2025 Michael Murray
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
 * Concurrency primitives for coordinating asynchronous hardware executions.
 *
 * <p>This package provides lightweight synchronization abstractions used to chain
 * GPU/CPU executions together without blocking the calling thread unnecessarily.</p>
 *
 * <p>The foundational {@link io.almostrealism.streams.Semaphore} — a completion token
 * returned by {@link io.almostrealism.code.Execution#accept} that callers can wait on —
 * is defined in {@code ar-relation} (alongside
 * {@link io.almostrealism.streams.StreamingEvaluable}) so it carries no dependency on the
 * operation-metadata model. This package contributes the metadata-aware extensions:</p>
 *
 * <ul>
 *   <li>{@link io.almostrealism.concurrent.OperationSemaphore} — a
 *       {@link io.almostrealism.streams.Semaphore} that additionally carries the
 *       {@link io.almostrealism.profile.OperationMetadata} of the operation waiting on it,
 *       and defines the metadata-attributing merge
 *       ({@link io.almostrealism.concurrent.OperationSemaphore#all})</li>
 *   <li>{@link io.almostrealism.concurrent.DefaultLatchSemaphore} — a {@link java.util.concurrent.CountDownLatch}-based
 *       implementation of {@code OperationSemaphore}</li>
 * </ul>
 */
package io.almostrealism.concurrent;
