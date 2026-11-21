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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Java source file in a job's working tree: its repository-relative path
 * and its text, read without compiling it.
 *
 * <p>The answers are lexical. A type mentioned only in a comment still
 * counts as referenced, which errs on the safe side for a caller that
 * withholds the files referring to a type it does not stage.</p>
 *
 * @param path the repository-relative path of the {@code .java} file
 * @param text the file's text
 * @see NewTypeAudit
 */
record JavaSourceFile(String path, String text) {

    /** A Java source's package declaration. */
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /**
     * Reads the source at {@code path} in a working tree.
     *
     * @param workingDirectory the root of the working tree
     * @param path             the repository-relative path of the {@code .java} file
     * @return the source
     * @throws IOException when the file cannot be read
     */
    static JavaSourceFile read(String workingDirectory, String path) throws IOException {
        return new JavaSourceFile(path, Files.readString(Path.of(workingDirectory, path), StandardCharsets.UTF_8));
    }

    /**
     * Returns the package the source declares.
     *
     * @return the package name, or an empty string for the default package
     */
    String packageName() {
        Matcher m = PACKAGE.matcher(text);
        return m.find() ? m.group(1) : "";
    }

    /**
     * Returns the fully-qualified name of the top-level type the file is
     * named for: its package followed by the file's base name.
     *
     * @return the type name
     */
    String namedType() {
        String name = new File(path).getName();
        if (name.endsWith(".java")) name = name.substring(0, name.length() - ".java".length());
        String pkg = packageName();
        return pkg.isEmpty() ? name : pkg + "." + name;
    }

    /**
     * Returns the first of {@code types} this source refers to: by its
     * fully-qualified name (an import or a qualified use), or by its simple
     * name when this source is in the same package or imports that package
     * whole. A mention in a comment counts.
     *
     * @param types fully-qualified type names
     * @return the first referenced type, or {@code null}
     */
    String firstReferenced(Collection<String> types) {
        String pkg = packageName();
        for (String fqn : types) {
            if (mentions(fqn)) return fqn;
            int dot = fqn.lastIndexOf('.');
            String typePkg = dot < 0 ? "" : fqn.substring(0, dot);
            boolean visible = typePkg.equals(pkg) || Pattern.compile(
                    "\\bimport\\s+" + Pattern.quote(typePkg) + "\\s*\\.\\s*\\*\\s*;").matcher(text).find();
            if (visible && mentions(fqn.substring(dot + 1))) return fqn;
        }
        return null;
    }

    /**
     * Returns whether the text contains {@code name} as a whole name: not
     * as part of a longer identifier or a longer qualified name.
     *
     * @param name a simple or qualified name
     * @return {@code true} when the name occurs on its own
     */
    private boolean mentions(String name) {
        return Pattern.compile("(?<![\\w.$])" + Pattern.quote(name) + "(?![\\w$])").matcher(text).find();
    }
}
