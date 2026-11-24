"""Score the reference embeddings of a CLAP2 reference dump against its probe tags.

Reads the dump ``dump_clap2_reference.py reference`` writes and reports, from the REFERENCE
model's embeddings (no native code involved), each clip's top tags and the top-1 / top-3 accuracy
overall and per clip kind, raw and with each tag's mean score over the clips subtracted (the
calibration the CLAP2 model card recommends). The native measurement
(``Clap2TaggingSpikeTest``) reports the same figures from the native towers, so the two can be
compared directly.

Usage::

    python score_clap2_reference.py /path/to/references
"""
import argparse
import json
import os

import numpy as np

import safetensors_extractor as core

TOP = 3


def accuracy(clips, labels, scores):
    """{kind: (count, top1 hits, top3 hits)} over all clips and per kind."""
    result = {}
    for kind in ("all", "one-shot", "loop", "stem"):
        rows = [i for i, c in enumerate(clips) if kind in ("all", c["kind"])]
        if not rows:
            continue
        top1 = top3 = 0
        for i in rows:
            order = np.argsort(-scores[i])
            truth = set(clips[i]["tags"])
            top1 += labels[order[0]] in truth
            top3 += any(labels[k] in truth for k in order[:TOP])
        result[kind] = (len(rows), top1, top3)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("references", help="the references directory of a dump")
    args = parser.parse_args()

    with open(os.path.join(args.references, "manifest.json")) as f:
        manifest = json.load(f)
    stages = core.read_state_dictionary(args.references)
    labels = [t["label"] for t in manifest["tags"]]
    clips = manifest["clips"]

    def unit(v):
        return v / np.linalg.norm(v)

    text = np.stack([unit(stages["text.%d" % i]) for i in range(len(labels))])
    audio = np.stack([unit(stages["audio." + c["name"]]) for c in clips])
    scores = audio @ text.T
    centered = scores - scores.mean(axis=0, keepdims=True)

    print("checkpoint:", manifest["repo"])
    for i, clip in enumerate(clips):
        order = np.argsort(-scores[i])[:TOP]
        print("  %-34s %-9s raw top: %s" % (clip["name"], clip["kind"],
                                            ", ".join("%s=%.3f" % (labels[k], scores[i, k]) for k in order)))
    for name, s in (("raw", scores), ("mean-centered", centered)):
        for kind, (count, top1, top3) in accuracy(clips, labels, s).items():
            print("%s %s: top1 %d/%d top3 %d/%d" % (name, kind, top1, count, top3, count))
    best = scores.max(axis=1)
    print("best cosine per clip: min %.3f median %.3f max %.3f" % (best.min(), np.median(best), best.max()))
    thresholds(clips, labels, scores, centered)


def thresholds(clips, labels, scores, centered):
    """Precision and recall of suggesting every (clip, tag) pair whose score clears a threshold.

    A suggestion is correct when the tag is one of the clip's describing tags. Recall counts the
    clips that receive at least one correct suggestion. Reported for the raw cosine and for the
    mean-centered score, at evenly spaced thresholds across each score's range.
    """
    truth = np.array([[label in set(c["tags"]) for label in labels] for c in clips])
    positives = truth.sum()
    for name, s in (("cosine", scores), ("centered", centered)):
        print("threshold table (%s): threshold, suggestions, precision, pair recall, clips with a correct tag" % name)
        for threshold in np.linspace(np.percentile(s, 50), s.max(), 8):
            chosen = s >= threshold
            count = int(chosen.sum())
            correct = int((chosen & truth).sum())
            clip_hits = int((chosen & truth).any(axis=1).sum())
            precision = correct / count if count else float("nan")
            print("  %.3f  %3d  %.2f  %.2f  %d/%d" % (threshold, count, precision, correct / positives,
                                                   clip_hits, len(clips)))


if __name__ == "__main__":
    main()
