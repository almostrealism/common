"""Export the caption tokenizer of a CLAP2 checkpoint for the native ``Clap2Tokenizer``.

A CLAP2 checkpoint ships its text tokenizer beside its weights as a tiktoken encoding: the
``r50k_base`` rank file (``open_clip_tiktoken/r50k_base.tiktoken``, one line per token: the base64
of the token's bytes, a space, and its rank, which is also its id) and the encoding definition
(``r50k_base.json``: the split pattern and tiktoken's own special token). The native tokenizer reads
the rank file as it is, so the export is a validated copy: no conversion to another format.

Modes:

  export   validate the checkpoint's rank file against what ``Clap2Tokenizer`` and the text tower
           assume, and copy it to the output path
  probe    write the reference token ids of a list of captions, produced by the reference
           tokenizer (open_clip's ``TikTokenTokenizer`` over the same files), together with the
           smallest rank file that encodes those captions exactly as the full one does; the pair
           is the committed resource the tokenizer-parity test reads in CI

The validation checks that the ranks are exactly ``0 .. n - 1`` with no token repeated, that every
single byte is a token (so no input is ever unknown), and that the encoding's vocabulary ends
below the control tokens the text tower reserves above it (EOS 50257, PAD 50258, BOS 50259).

The probe's smaller rank file keeps every single-byte token and every token whose bytes occur
inside one pre-tokenized piece of a caption, each at its original rank. Encoding a piece only ever
looks up the piece itself and concatenations of adjacent parts of it, all contiguous substrings of
the piece, so the smaller file encodes the probe captions exactly as the full file does.

Usage::

    python export_clap2_tokenizer.py export /path/to/hf/snapshot out/r50k_base.tiktoken
    python export_clap2_tokenizer.py probe /path/to/hf/snapshot out_dir --captions captions.txt
"""
import argparse
import base64
import json
import os
import shutil

#: Control token ids the CLAP2 text tower reserves above the encoding's vocabulary.
EOS_TOKEN = 50257
PAD_TOKEN = 50258
BOS_TOKEN = 50259

#: The captions the probe mode encodes when no caption file is given: the probe vocabulary of
#: ``dump_clap2_reference.py`` and text that exercises each rule of the split pattern.
DEFAULT_CAPTIONS = [
    "A deep kick drum hits.",
    "A snare drum is struck.",
    "A hi-hat cymbal ticks.",
    "A drum beat plays a steady rhythm.",
    "A bass plays low notes.",
    "An electric guitar plays.",
    "An acoustic guitar is plucked.",
    "A piano plays notes.",
    "An organ plays a sustained chord.",
    "A synthesizer plays a lead melody.",
    "A soft synth pad holds a chord.",
    "A string section of violins and cellos plays.",
    "A trumpet or other brass instrument plays.",
    "A flute plays a note.",
    "A saxophone or clarinet plays.",
    "A marimba or xylophone is struck.",
    "A person sings a note.",
    "A bell rings.",
    "A melody of quick notes is played.",
    "An ambient texture of noise and hum.",
    "kick drum",
    "It's the drummer's kit, isn't it? They'll say we've won, I'd guess.",
    "IT'S LOUD: Contractions'Ll Not Merge",
    "808 drums at 120 BPM, 1999 or 2024",
    "café naïve Ünïcödé",
    "  two leading spaces and   three inside  ",
    "line one\nline two\n\n",
    "tab\tseparated\r\nwindows line",
    "hi-hat & snare!!! ...really?! (yes)",
    "<|endoftext|> is plain text here",
    "emoji 🥁 drum 🎹",
    "a",
]


def read_ranks(path):
    """Reads a tiktoken rank file: a list of ``(token bytes, rank)`` in file order."""
    ranks = []
    with open(path, "rb") as f:
        for number, line in enumerate(f, 1):
            if not line.strip():
                continue
            parts = line.split()
            if len(parts) != 2:
                raise ValueError("line %d is not a token and a rank" % number)
            ranks.append((base64.b64decode(parts[0], validate=True), int(parts[1])))
    return ranks


def validate_ranks(ranks):
    """Checks the assumptions of the native tokenizer; returns the vocabulary size."""
    seen = {}
    for token, rank in ranks:
        if token in seen:
            raise ValueError("token %r has ranks %d and %d" % (token, seen[token], rank))
        seen[token] = rank
    ids = sorted(seen.values())
    if ids != list(range(len(ids))):
        raise ValueError("ranks are not exactly 0 .. %d" % (len(ids) - 1))
    missing = [b for b in range(256) if bytes([b]) not in seen]
    if missing:
        raise ValueError("bytes %s are not tokens, so some input could not be encoded" % missing[:8])
    if len(ids) > EOS_TOKEN:
        raise ValueError("the vocabulary of %d tokens reaches the control token %d" % (len(ids), EOS_TOKEN))
    return len(ids)


def write_ranks(ranks, path):
    """Writes ``(token bytes, rank)`` pairs as a tiktoken rank file, in rank order."""
    with open(path, "wb") as f:
        for token, rank in sorted(ranks, key=lambda pair: pair[1]):
            f.write(base64.b64encode(token) + b" " + str(rank).encode() + b"\n")


def subset_for(ranks, pieces):
    """The ranks that encode the given pre-tokenized pieces exactly as the full ranks do: every
    single byte, and every token whose bytes occur within one piece."""
    substrings = set()
    for piece in pieces:
        data = piece.encode("utf-8")
        for start in range(len(data)):
            for end in range(start + 1, len(data) + 1):
                substrings.add(data[start:end])
    return [(token, rank) for token, rank in ranks if len(token) == 1 or token in substrings]


def checkpoint_files(checkpoint):
    """The rank file and encoding definition a checkpoint directory's open_clip config names."""
    with open(os.path.join(checkpoint, "open_clip_config.json")) as f:
        text = json.load(f)["model_cfg"]["text_cfg"]
    if text.get("tokenizer_type") != "tiktoken" or text.get("tiktoken_name") != "r50k_base":
        raise ValueError("the checkpoint's tokenizer is not the tiktoken r50k_base encoding")
    return (os.path.join(checkpoint, text["tiktoken_bpe_path"]),
            os.path.join(checkpoint, text["tiktoken_config_path"]))


def export(checkpoint, out_file):
    """Validates the checkpoint's rank file and copies it to ``out_file``."""
    rank_file, _ = checkpoint_files(checkpoint)
    size = validate_ranks(read_ranks(rank_file))
    os.makedirs(os.path.dirname(os.path.abspath(out_file)), exist_ok=True)
    shutil.copyfile(rank_file, out_file)
    print("Wrote the %d-token r50k_base rank file to %s" % (size, out_file))


def probe(checkpoint, out_dir, captions):
    """Writes the reference ids of the captions and the smallest rank file that encodes them."""
    import regex
    from open_clip.tokenizer import TikTokenTokenizer

    rank_file, config_file = checkpoint_files(checkpoint)
    ranks = read_ranks(rank_file)
    validate_ranks(ranks)
    with open(config_file) as f:
        pattern = regex.compile(json.load(f)["pat_str"])

    reference = TikTokenTokenizer("r50k_base", context_length=256, bpe_path=rank_file,
                                  encoding_config_path=config_file)
    if (reference.eot_token_id, reference.pad_token_id, reference.bos_token_id) != (EOS_TOKEN, PAD_TOKEN, BOS_TOKEN):
        raise ValueError("the reference tokenizer reserves different control tokens")

    pieces = [piece for caption in captions for piece in pattern.findall(caption)]
    expected = [{"text": caption, "ids": [i for i in reference([caption])[0].tolist() if i != PAD_TOKEN]}
                for caption in captions]

    os.makedirs(out_dir, exist_ok=True)
    subset = subset_for(ranks, pieces)
    write_ranks(subset, os.path.join(out_dir, "r50k_probe.tiktoken"))
    with open(os.path.join(out_dir, "r50k_probe_ids.json"), "w", encoding="utf-8") as f:
        json.dump({"encoding": "r50k_base", "captions": expected}, f, indent=1, ensure_ascii=False)
        f.write("\n")
    print("Wrote %d reference captions and a %d-token rank file to %s" % (len(expected), len(subset), out_dir))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["export", "probe"])
    parser.add_argument("checkpoint", help="checkpoint directory holding open_clip_config.json")
    parser.add_argument("out", help="output rank file (export) or directory (probe)")
    parser.add_argument("--captions", help="UTF-8 file of captions, one per line (probe mode)")
    args = parser.parse_args()
    if args.mode == "export":
        export(args.checkpoint, args.out)
    else:
        captions = DEFAULT_CAPTIONS
        if args.captions:
            with open(args.captions, encoding="utf-8") as f:
                captions = [line.rstrip("\n") for line in f if line.strip()]
        probe(args.checkpoint, args.out, captions)


if __name__ == "__main__":
    main()
