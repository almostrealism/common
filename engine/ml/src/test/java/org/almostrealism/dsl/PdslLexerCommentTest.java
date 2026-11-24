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

package org.almostrealism.dsl;

import org.almostrealism.util.TestSuiteBase;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Tests for {@link PdslLexer} comment handling, focused on block comments that
 * are not closed before the end of the source. A comment's content must never
 * escape into the token stream: an unterminated block comment should consume the
 * rest of the source, leaving only the {@link PdslToken.Type#EOF} sentinel.
 */
public class PdslLexerCommentTest extends TestSuiteBase {

	/** Returns every token the lexer produces for {@code source} except the trailing EOF. */
	private List<PdslToken> contentTokens(String source) {
		return new PdslLexer(source).tokenize().stream()
				.filter(t -> t.getType() != PdslToken.Type.EOF)
				.collect(Collectors.toList());
	}

	/**
	 * A block comment that is never closed must swallow all of its content. The lexer
	 * previously stopped one character before the end of the source, leaking that final
	 * character as a token (e.g. {@code /}{@code *abc} produced an IDENTIFIER {@code c}).
	 */
	@Test(timeout = 5000)
	public void unterminatedBlockCommentLeaksNoToken() {
		List<PdslToken> tokens = contentTokens("/*abc");
		Assert.assertTrue("Unterminated block comment must not leak any token, but produced "
				+ tokens, tokens.isEmpty());
	}

	/**
	 * The same leak, exercised at the end of an otherwise valid program: a trailing,
	 * unterminated block comment must not contribute a spurious identifier token.
	 */
	@Test(timeout = 5000)
	public void trailingUnterminatedBlockCommentLeaksNoToken() {
		List<PdslToken> tokens = contentTokens("weight /* note x");
		Assert.assertEquals("Only the 'weight' keyword should be produced; the unterminated "
				+ "comment must not leak a token, but got " + tokens, 1, tokens.size());
		Assert.assertEquals(PdslToken.Type.WEIGHT, tokens.get(0).getType());
	}

	/**
	 * Control: a properly terminated block comment produces no tokens. This guards the fix
	 * against over-consuming — the terminator and only the terminator ends the comment.
	 */
	@Test(timeout = 5000)
	public void terminatedBlockCommentProducesNoTokens() {
		Assert.assertTrue(contentTokens("/*abc*/").isEmpty());
	}

	/**
	 * Control: code following a terminated block comment is still tokenized. The comment
	 * must end exactly at its {@code *}{@code /}, not swallow what comes after.
	 */
	@Test(timeout = 5000)
	public void codeAfterTerminatedBlockCommentIsTokenized() {
		List<PdslToken> tokens = contentTokens("/*abc*/weight");
		Assert.assertEquals(1, tokens.size());
		Assert.assertEquals(PdslToken.Type.WEIGHT, tokens.get(0).getType());
	}
}
