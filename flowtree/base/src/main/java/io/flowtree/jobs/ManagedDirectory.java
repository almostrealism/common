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

package io.flowtree.jobs;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * A directory tree that the code which creates it on a node is also
 * responsible for deleting — a job's Python virtual environment, or a job's
 * private space in the local Maven repository.
 *
 * <p>Such a directory is often written by processes other than the one
 * deleting it (a build still finishing, another job sweeping abandoned
 * spaces), so {@link #delete()} tolerates entries that vanish while it walks
 * the tree.</p>
 *
 * @author Michael Murray
 */
public class ManagedDirectory {

    /** The root of the tree. */
    private final Path path;

    /**
     * Creates a handle on the tree rooted at {@code path}. Nothing is created
     * on disk.
     *
     * @param path the root of the tree
     */
    public ManagedDirectory(Path path) {
        this.path = path;
    }

    /**
     * Returns the root of the tree.
     *
     * @return the directory's path
     */
    public Path getPath() {
        return path;
    }

    /**
     * Deletes the tree, if it exists.
     *
     * <p>Walks with {@link Files#walkFileTree} and deletes each entry on the way
     * out, tolerating entries that vanish concurrently: a {@link Files#walk}
     * stream would throw if a file it had listed was removed by another process
     * before it was reached, whereas the visitor treats an already-gone entry as
     * success — which, for a delete, it is.</p>
     *
     * @throws IOException if the tree cannot be traversed
     */
    public void delete() throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exc)
                    throws IOException {
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
