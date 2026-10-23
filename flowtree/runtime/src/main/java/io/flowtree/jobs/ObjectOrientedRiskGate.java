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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic pre-filter that decides whether a changed production class is worth a
 * {@link ObjectOrientedDesignRule} review session at all.
 *
 * <p>The design review used to spend one agent session on every changed production class. Most of
 * those classes carry none of the shapes the owner rejects in review, so the sessions found nothing
 * and the cost was almost all waste. This gate reads the same evidence a reviewer reads first — the
 * class name and the lines the branch added — and reports the design signals it sees. Only a class
 * that fires at least one signal is sent to a session; a class that fires none is left alone.</p>
 *
 * <p>The gate is intentionally coarse and errs toward flagging: a false positive costs part of one
 * shared session, while a false negative lets a procedural class reach the pull request. It never
 * decides a class is <em>sound</em> — only that it is not worth a reviewer's attention this time.
 * It performs no git or filesystem access of its own; the rule supplies the added diff lines and
 * the current file content, which keeps the gate a pure function of its inputs and directly
 * testable.</p>
 *
 * @see ObjectOrientedDesignRule
 */
class ObjectOrientedRiskGate {

    /**
     * A design shape the gate recognises in a changed class. Each value names a reason the owner
     * has rejected procedural work in review, so that a session — and the pull request — can see
     * which shapes prompted the review.
     */
    enum Signal {

        /** The class is new on this branch; its whole design is unreviewed. */
        NEW_CLASS("new class"),

        /** The simple name ends in Util, Helper, Model, Block or Config — a name that asserts the class IS something. */
        SUSPICIOUS_NAME("name asserts a type (Util/Helper/Model/Block/Config)"),

        /** The branch added a static member, where behaviour belongs on an instance. */
        NEW_STATIC("added static member"),

        /** The branch added an instanceof test or a cast, which may bypass an interface. */
        INSTANCEOF_OR_CAST("added instanceof or cast"),

        /** The branch passed a lambda or method reference as a call argument, which may hide a subclass. */
        LAMBDA_ARGUMENT("lambda or method reference passed as an argument"),

        /** A public or protected method takes a parameter whose type the class already holds as a field. */
        DERIVABLE_PARAMETER("public method takes a collaborator the class already holds");

        /** Human-readable description used in logs and the review prompt. */
        private final String description;

        /**
         * @param description the human-readable description
         */
        Signal(String description) {
            this.description = description;
        }

        /**
         * Returns this signal's human-readable description.
         *
         * @return the human-readable description of this signal
         */
        String description() {
            return description;
        }
    }

    /** Name suffixes that assert the class IS a particular kind of thing; see {@link Signal#SUSPICIOUS_NAME}. */
    private static final List<String> ASSERTIVE_NAME_SUFFIXES =
            List.of("Util", "Helper", "Model", "Block", "Config");

    /** Matches an added member declaration that introduces a {@code static} member or initializer. */
    private static final Pattern STATIC_MEMBER = Pattern.compile(
            "^(?:(?:public|private|protected|final|abstract|synchronized)\\s+)*static\\b");

    /** Matches a Java cast: a parenthesised capitalised type applied to an operand. */
    // TODO(review): CAST also matches `if (DEBUG) log(...)` / `if (Capitalized) stmt()`; consider tightening to reduce over-flagging.
    private static final Pattern CAST = Pattern.compile(
            "\\(\\s*[A-Z][A-Za-z0-9_.]*(?:<[^()]*>)?\\s*\\)\\s*[A-Za-z0-9_(\"'!]");

    /** Matches a call that receives a lambda argument: an identifier, an open paren, then an arrow. */
    private static final Pattern LAMBDA_ARG = Pattern.compile("\\w\\s*\\([^;]*->");

    /** Matches a call that receives a method-reference argument. */
    private static final Pattern METHOD_REF_ARG = Pattern.compile("\\w\\s*\\([^;]*[\\w.]+::\\w");

    /** Matches a public or protected method declaration and captures its parameter list. */
    private static final Pattern PUBLIC_METHOD = Pattern.compile(
            "\\b(?:public|protected)\\b[^;{(]*\\(([^)]*)\\)");

    /** Matches a field declaration and captures its (capitalised) type's simple name. */
    private static final Pattern FIELD_DECLARATION = Pattern.compile(
            "^(?:public|private|protected)\\s+(?:static\\s+|final\\s+|transient\\s+|volatile\\s+)*"
                    + "([A-Z][A-Za-z0-9_]*)(?:<[^;=]*>)?(?:\\[\\])?\\s+\\w+\\s*[=;]");

    /** Matches the simple type name at the head of a parameter declaration. */
    private static final Pattern PARAMETER_TYPE = Pattern.compile(
            "^(?:final\\s+)?(?:@\\w+\\s+)*([A-Z][A-Za-z0-9_]*)(?:<[^>]*>)?(?:\\[\\])?\\s+\\w+$");

    /**
     * Reports which design signals a changed class fires.
     *
     * @param simpleClassName the class's simple name (no package), e.g. {@code "SlidingWindow"}
     * @param newClass        whether the class is new on the branch
     * @param addedLines      the lines the branch added, with the unified-diff {@code +} already stripped
     * @param content         the class's current full source, or {@code null} when it cannot be read
     * @return the signals that fired, in {@link Signal} declaration order; empty when none did
     */
    EnumSet<Signal> evaluate(String simpleClassName, boolean newClass, List<String> addedLines, String content) {
        EnumSet<Signal> signals = EnumSet.noneOf(Signal.class);
        if (newClass) signals.add(Signal.NEW_CLASS);
        if (hasAssertiveName(simpleClassName)) signals.add(Signal.SUSPICIOUS_NAME);

        List<String> added = addedLines == null ? List.of() : addedLines;
        for (String line : added) {
            String code = stripComment(line).trim();
            if (code.isEmpty()) continue;
            if (STATIC_MEMBER.matcher(code).find()) signals.add(Signal.NEW_STATIC);
            if (hasInstanceofOrCast(code)) signals.add(Signal.INSTANCEOF_OR_CAST);
            if (LAMBDA_ARG.matcher(code).find() || METHOD_REF_ARG.matcher(code).find()) {
                signals.add(Signal.LAMBDA_ARGUMENT);
            }
        }

        if (hasDerivableParameter(added, content)) signals.add(Signal.DERIVABLE_PARAMETER);
        return signals;
    }

    /**
     * @param simpleClassName the class's simple name
     * @return whether the name ends in one of {@link #ASSERTIVE_NAME_SUFFIXES}
     */
    private boolean hasAssertiveName(String simpleClassName) {
        if (simpleClassName == null) return false;
        for (String suffix : ASSERTIVE_NAME_SUFFIXES) {
            if (simpleClassName.endsWith(suffix)) return true;
        }
        return false;
    }

    /**
     * @param code one line of source, comments stripped
     * @return whether the line adds an {@code instanceof} test or a cast
     */
    private boolean hasInstanceofOrCast(String code) {
        if (code.matches(".*\\binstanceof\\b.*")) return true;
        return CAST.matcher(code).find();
    }

    /**
     * Reports whether any added public or protected method takes a parameter whose type the class
     * already holds as a field — the "own your collaborators" violation, where the receiver should
     * derive the argument from its own state rather than accept it.
     *
     * @param addedLines the added source lines, comments included
     * @param content    the class's current full source, or {@code null}
     * @return whether a derivable parameter was found
     */
    private boolean hasDerivableParameter(List<String> addedLines, String content) {
        if (content == null) return false;
        List<String> fieldTypes = fieldTypes(content);
        if (fieldTypes.isEmpty()) return false;
        for (String line : addedLines) {
            Matcher method = PUBLIC_METHOD.matcher(stripComment(line));
            if (!method.find()) continue;
            for (String type : parameterTypes(method.group(1))) {
                if (fieldTypes.contains(type)) return true;
            }
        }
        return false;
    }

    /**
     * @param content the class's full source
     * @return the simple type names of its declared fields, in source order
     */
    private List<String> fieldTypes(String content) {
        List<String> types = new ArrayList<>();
        for (String line : content.split("\n")) {
            Matcher m = FIELD_DECLARATION.matcher(line.trim());
            if (m.find()) types.add(m.group(1));
        }
        return types;
    }

    /**
     * @param parameterList the text between a method declaration's parentheses
     * @return the simple type names of its parameters that are reference types
     */
    private List<String> parameterTypes(String parameterList) {
        List<String> types = new ArrayList<>();
        if (parameterList == null || parameterList.isBlank()) return types;
        for (String parameter : splitParameters(parameterList)) {
            Matcher m = PARAMETER_TYPE.matcher(parameter.trim());
            if (m.matches()) types.add(m.group(1));
        }
        return types;
    }

    /**
     * Splits a parameter list on the commas that separate parameters, ignoring commas nested inside
     * generic type arguments so that {@code Map<String, Integer> m} is one parameter, not two.
     *
     * @param parameterList the text between a method declaration's parentheses
     * @return the individual parameter declarations
     */
    private List<String> splitParameters(String parameterList) {
        List<String> parameters = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < parameterList.length(); i++) {
            char c = parameterList.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                parameters.add(parameterList.substring(start, i));
                start = i + 1;
            }
        }
        parameters.add(parameterList.substring(start));
        return parameters;
    }

    /**
     * Removes a trailing {@code //} line comment so a comment's words are never read as code.
     *
     * @param line one line of source
     * @return the line up to any {@code //}
     */
    private String stripComment(String line) {
        int comment = line.indexOf("//");
        return comment >= 0 ? line.substring(0, comment) : line;
    }
}
