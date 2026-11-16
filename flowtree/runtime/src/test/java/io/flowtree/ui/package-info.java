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
 * Unit tests for the display-independent members of {@link io.flowtree.ui} — the
 * {@link io.flowtree.ui.NetworkTreeNode} tree adapter and the {@link io.flowtree.ui.NetworkClient}
 * socket client. The Swing dialogs in this package construct a {@link javax.swing.JFrame} in their
 * constructors and so cannot be exercised in a headless environment.
 */
package io.flowtree.ui;
