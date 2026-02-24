package org.almostrealism.ml;

public class BPE {
	/**
	 * Find the first match for text in vocab,
	 * return that index (or -1 if not found).
	 */
	public static int lookup(String text, String[] vocab, int vocabSize) {
		for (int i = 0; i < vocabSize; i++) {
			if (text.equals(vocab[i])) {
				return i;
			}
		}

		return -1;
	}

	public static int encode(String text, String[] vocab, float[] vocabScores, int vocabSize, int[] tokens) {
		int tokenCount = 0;

		for (int i = 0; i < text.length(); ++i) {
			char c = text.charAt(i);
			String singleChar = String.valueOf(c);

			int id = lookup(singleChar, vocab, vocabSize);
			if (id == -1) throw new RuntimeException();

			tokens[tokenCount] = id;
			tokenCount++;
		}

		// merge the best consecutive pair each iteration
		w: while (true) {
			float bestScore = -1e10f;
			int bestId = -1;
			int bestIndex = -1;

			for (int i = 0; i < tokenCount - 1; i++) {
				String buf = vocab[tokens[i]] + vocab[tokens[i + 1]];

				int id = lookup(buf, vocab, vocabSize);
				if (id != -1 && vocabScores[id] > bestScore) {
					bestScore = vocabScores[id];
					bestId = id;
					bestIndex = i;
				}
			}

			if (bestIndex == -1) {
				break w;
			}

			tokens[bestIndex] = bestId;

			for (int i = bestIndex + 1; i < tokenCount - 1; i++) {
				tokens[i] = tokens[i + 1];
			}

			tokenCount--;
		}

		return tokenCount;
	}
}
