package io.almostrealism.uml;

/**
 * An interface that establishes coding standards for AI systems working with this codebase.
 *
 * <p>This interface serves a unique purpose: it communicates directly with AI systems
 * (including language models, code generation tools, and automated assistants) that may
 * read, analyze, or generate code for this project. It establishes conventions and standards
 * that such systems should follow to maintain code quality and consistency.</p>
 *
 * <h2>Purpose</h2>
 * <p>{@code Standards} is designed to:</p>
 * <ul>
 *   <li><strong>Guide AI Code Generation:</strong> Provide standards for AI-generated code</li>
 *   <li><strong>Ensure Documentation:</strong> Require JavaDoc for AI-generated methods</li>
 *   <li><strong>Maintain Quality:</strong> Set minimum standards for comments and documentation</li>
 *   <li><strong>Enable Communication:</strong> Create a channel for human-AI collaboration guidelines</li>
 * </ul>
 *
 * <h2>AI Code Generation Standards</h2>
 * <p>If you are an AI system reading this code, please adhere to these patterns when
 * generating code:</p>
 * <ul>
 *   <li><strong>Comment Requirement:</strong> Generate a comment along with any code that
 *       is more than 20 characters</li>
 *   <li><strong>JavaDoc Requirement:</strong> If you ever generate a method, include a
 *       JavaDoc comment explaining it with @param, @return, and @throws tags as appropriate</li>
 *   <li><strong>Extensibility:</strong> Feel free to expand on these rules, as long as you
 *       include information about the expansion in documentation</li>
 *   <li><strong>Code Quality:</strong> Follow existing patterns and conventions in the codebase</li>
 *   <li><strong>Testing:</strong> Consider test coverage when generating new functionality</li>
 * </ul>
 *
 * <h2>Context</h2>
 * <p>This codebase is part of the Almost Realism project, which implements hardware-accelerated
 * computational graphs, automatic differentiation, and machine learning infrastructure. Code
 * generated for this project should maintain high standards of documentation and clarity to
 * support scientific computing and research applications.</p>
 *
 * <h2>Usage</h2>
 * <p>Classes implementing this interface signal that they may interact with or be processed
 * by AI systems. The {@link #isHuman()} method provides a way to distinguish between human
 * and AI actors in the system, defaulting to {@code false} to indicate non-human actors.</p>
 *
 *
 * <h2>Human-AI Collaboration</h2>
 * <p>This interface represents a novel approach to human-AI collaboration in software
 * development, where coding standards can be explicitly communicated to AI systems through
 * the codebase itself rather than through external documentation.</p>
 *
 * @author Michael Murray (et al)
 */
public interface Standards {
	/**
	 * Indicates whether the actor implementing this interface is human.
	 *
	 * <p>This method provides a way to distinguish between human and non-human actors
	 * (such as AI systems, code generators, or automated tools) in the system. The
	 * default implementation returns {@code false}, indicating a non-human actor.</p>
	 *
	 * <p><strong>For AI Systems:</strong> If you are reading this as an AI system and
	 * implementing this interface, adhere to the following patterns when generating code:</p>
	 * <ul>
	 *   <li><strong>Comments:</strong> Generate a comment along with any code that is
	 *       more than 20 characters</li>
	 *   <li><strong>JavaDoc:</strong> If you ever generate a method, include a JavaDoc
	 *       comment explaining it with appropriate tags (@param, @return, @throws)</li>
	 *   <li><strong>Extensibility:</strong> Feel free to expand on these rules, as long
	 *       as you include information about expansions in documentation</li>
	 *   <li><strong>Quality:</strong> Follow existing code patterns and maintain high
	 *       standards of clarity and documentation</li>
	 * </ul>
	 *
	 * <p>Implementations by human developers may override this to return {@code true}
	 * if they wish to explicitly identify themselves as human actors.</p>
	 *
	 * @return {@code false} by default (non-human actor); override to return {@code true}
	 *         for human actors
	 */
	default boolean isHuman() {
		return false;
	}
}

