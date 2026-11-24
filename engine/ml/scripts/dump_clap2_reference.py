"""Inspect a CLAP2 checkpoint and dump the weights and reference data the native CLAP2 port is
checked and measured against.

Modes:
  inspect     print the open_clip module tree and every state-dict key with its shape
  rope        print the audio tower's axial rotary angles for a small grid
  details     print the attributes the port depends on that the module tree does not show
  reference   write the native weights and the reference dump for the parity and tagging tests
  compare-htsat
              score the same probe set with a first-generation LAION CLAP checkpoint (HTSAT +
              RoBERTa, through transformers) for comparison; embeddings only, no native weights

The reference mode writes two StateDictionary directories:

  <out>/weights      the checkpoint in the native layout (extract_clap2_weights.native_state)
  <out>/references   for every probe clip, its 32 kHz mono waveform (clip.<name>) and the
                     reference audio embedding (audio.<name>); for every probe tag, its token ids
                     (tokens.<i>) and the reference text embedding (text.<i>); the reference
                     log-mel spectrogram of the first clip (mel.<name>)

and <out>/references/manifest.json, which lists the clips (name, kind, source, the tags that
describe them) and the tags (caption, label) in order.

The probe set is musical: one-shots, loops and stems.

  one-shots   two notes of each of the ten NSynth instrument families (NSynth test split,
              CC BY 4.0, read from a local parquet file of the DynamicSuperb NSynth instrument
              classification set), plus a kick, a snare and a closed hi-hat synthesized here
  loops       a drum loop synthesized here, and a keyboard arpeggio and a bass line sequenced
              from NSynth notes of one instrument
  stems       a string chord, a vocal line and a guitar line sequenced from NSynth notes

Everything is deterministic: the notes are chosen by sorted file name and the synthesis is
seeded, so a second run writes the same dump. Nothing here ships audio into the repository; the
dump is written to <out>, outside it.

Usage::

    python dump_clap2_reference.py reference laion/clap2-xxs-audioxl-s1B \\
        --nsynth /path/to/test-00000-of-00001.parquet --out /path/to/clap2-xxs
"""
import argparse
import io
import json
import os

import numpy as np
import open_clip

SAMPLE_RATE = 32000
LOOP_SECONDS = 4.0
BEAT = 0.5

#: The probe vocabulary: (label, caption). Captions follow the model card's advice: short full
#: sentences in the style of the training captions, no bare labels and no negations.
TAGS = [
    ("kick drum", "A deep kick drum hits."),
    ("snare drum", "A snare drum is struck."),
    ("hi-hat", "A hi-hat cymbal ticks."),
    ("drum beat", "A drum beat plays a steady rhythm."),
    ("bass", "A bass plays low notes."),
    ("electric guitar", "An electric guitar plays."),
    ("acoustic guitar", "An acoustic guitar is plucked."),
    ("piano", "A piano plays notes."),
    ("organ", "An organ plays a sustained chord."),
    ("synth lead", "A synthesizer plays a lead melody."),
    ("synth pad", "A soft synth pad holds a chord."),
    ("strings", "A string section of violins and cellos plays."),
    ("brass", "A trumpet or other brass instrument plays."),
    ("flute", "A flute plays a note."),
    ("reed", "A saxophone or clarinet plays."),
    ("mallet", "A marimba or xylophone is struck."),
    ("vocal", "A person sings a note."),
    ("bell", "A bell rings."),
    ("arpeggio", "A melody of quick notes is played."),
    ("ambient", "An ambient texture of noise and hum."),
]

#: The tags that correctly describe a clip of each NSynth family.
FAMILY_TAGS = {
    "bass": ["bass"],
    "brass": ["brass"],
    "flute": ["flute"],
    "guitar": ["electric guitar", "acoustic guitar"],
    "keyboard": ["piano", "synth lead", "synth pad", "organ"],
    "mallet": ["mallet", "bell"],
    "organ": ["organ"],
    "reed": ["reed"],
    "string": ["strings"],
    "vocal": ["vocal"],
}


def load(repo):
    """Loads a CLAP2 checkpoint from the Hugging Face hub through open_clip."""
    model, preprocess = open_clip.create_model_from_pretrained("hf-hub:" + repo)
    model.eval()
    return model, preprocess


def inspect(repo):
    """Prints the module tree and the state-dict keys of the checkpoint."""
    model, _ = load(repo)
    print(model)
    for key, value in model.state_dict().items():
        print(key, tuple(value.shape))


def rope_layout(repo):
    """Prints the audio tower's axial rotary angles for a small (freq, time) grid.

    The table is the [sin | cos] concatenation timm builds; the angle of each interleaved pair is
    recovered with atan2 so the band layout can be read off directly.
    """
    import torch
    model, _ = load(repo)
    rope = model.audio.encoder.vit.rope
    embed = rope.get_embed(shape=(2, 3))
    sin_emb, cos_emb = embed.chunk(2, -1)
    angles = torch.atan2(sin_emb, cos_emb)
    torch.set_printoptions(precision=4, linewidth=200)
    print("bands", rope.bands)
    print("angles (rows = freq-major patches, columns = head features)")
    print(angles)


def details(repo):
    """Prints the module attributes the native port depends on that the module tree does not show."""
    model, _ = load(repo)
    vit = model.audio.encoder.vit
    block = vit.blocks[0]
    print("audio pre_norm (normalize before proj):", model.audio.pre_norm)
    print("audio mlp gate_last:", block.mlp.gate_last, "chunk_dim:", block.mlp.chunk_dim)
    print("audio norm eps:", block.norm1.eps, vit.norm_pre.eps, vit.norm.eps, vit.attn_pool.norm.eps)
    print("audio attention prefix tokens:", block.attn.num_prefix_tokens, "rotate_half:", block.attn.rotate_half)
    print("audio num_prefix_tokens:", vit.num_prefix_tokens, "pool_include_prefix:", vit.pool_include_prefix)
    print("text pad/bos/eos:", model.text.pad_id, model.text.bos_id, model.text.eos_id)
    print("text norm eps:", model.text.blocks[0].norm1.eps, model.text.ln_final.eps)
    print("logit_scale:", float(model.logit_scale.exp()), "logit_bias:", model.logit_bias)
    tokenizer = open_clip.get_tokenizer("hf-hub:" + repo)
    ids = tokenizer(["A kick drum hits."])[0]
    print("tokens:", ids[:12].tolist(), "length:", ids.shape[0])


def envelope(samples, attack, decay):
    """An exponential-decay amplitude envelope with a linear attack, both in seconds."""
    t = np.arange(samples) / SAMPLE_RATE
    rise = np.clip(t / max(attack, 1e-6), 0, 1)
    return rise * np.exp(-t / decay)


def kick(rng):
    """A synthesized kick: a sine swept from 150 Hz to 45 Hz with a short click."""
    n = int(0.5 * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    frequency = 45 + 105 * np.exp(-t / 0.04)
    phase = 2 * np.pi * np.cumsum(frequency) / SAMPLE_RATE
    click = rng.standard_normal(n) * envelope(n, 0.0005, 0.003) * 0.3
    return 0.9 * np.sin(phase) * envelope(n, 0.001, 0.15) + click


def snare(rng):
    """A synthesized snare: a 190 Hz body under band-limited noise."""
    n = int(0.4 * SAMPLE_RATE)
    t = np.arange(n) / SAMPLE_RATE
    noise = np.diff(rng.standard_normal(n + 1))
    body = np.sin(2 * np.pi * 190 * t) * envelope(n, 0.001, 0.05)
    return 0.4 * body + 0.35 * noise * envelope(n, 0.001, 0.09)


def hihat(rng):
    """A synthesized closed hi-hat: high-passed noise with a very short decay."""
    n = int(0.25 * SAMPLE_RATE)
    noise = rng.standard_normal(n + 2)
    high = noise[2:] - 2 * noise[1:-1] + noise[:-2]
    return 0.15 * high * envelope(n, 0.0005, 0.025)


def place(events, seconds=LOOP_SECONDS):
    """Mixes (onset seconds, waveform) events into one clip of the given length."""
    out = np.zeros(int(seconds * SAMPLE_RATE))
    for onset, sound in events:
        start = int(onset * SAMPLE_RATE)
        end = min(len(out), start + len(sound))
        out[start:end] += sound[:end - start]
    return out


def drum_loop(rng):
    """Two bars at 120 BPM: kicks on 1 and 3, snares on 2 and 4, closed hats on every eighth."""
    k, s, h = kick(rng), snare(rng), hihat(rng)
    events = []
    for beat in range(8):
        events.append((beat * BEAT, k if beat % 2 == 0 else s))
    for eighth in range(16):
        events.append((eighth * BEAT / 2, h))
    return place(events)


def nsynth_notes(path):
    """Reads the NSynth notes of the parquet file: {file name: (family, waveform at 32 kHz)}."""
    import pyarrow.parquet as pq
    import soundfile
    import torch
    from open_clip.audio.dsp import resample

    table = pq.read_table(path, columns=["audio", "file", "label"])
    notes = {}
    for audio, name, label in zip(table.column("audio").to_pylist(), table.column("file").to_pylist(),
                                  table.column("label").to_pylist()):
        data, rate = soundfile.read(io.BytesIO(audio["bytes"]), dtype="float32")
        wave = resample(torch.from_numpy(data).float(), rate, SAMPLE_RATE).numpy()
        notes[name] = (label, wave)
    return notes


def pitch_of(name):
    """The MIDI pitch of an NSynth note name (family_source_instrument-pitch-velocity)."""
    return int(name.split("-")[1])


def instrument_of(name):
    """The instrument of an NSynth note name."""
    return name.split("-")[0]


def one_shots(notes):
    """Two mid-range notes per family: of different sources where the family has them, otherwise
    of different instruments."""
    shots = []
    for family in sorted(FAMILY_TAGS):
        candidates = sorted(n for n, (label, _) in notes.items() if label == family and 48 <= pitch_of(n) <= 72)
        chosen = []
        for distinct in (lambda n: n.split("_")[1], instrument_of):
            for name in candidates:
                if len(chosen) < 2 and name not in chosen and all(distinct(c) != distinct(name) for c in chosen):
                    chosen.append(name)
        for name in chosen:
            shots.append((name, "one-shot", "nsynth:" + name, FAMILY_TAGS[family], notes[name][1][:SAMPLE_RATE * 4]))
    return shots


def sequence(notes, family, intervals, onsets, length=1.0):
    """Sequences notes of one instrument of a family whose pitches cover the given intervals."""
    by_instrument = {}
    for name, (label, _) in notes.items():
        if label == family:
            by_instrument.setdefault(instrument_of(name), {})[pitch_of(name)] = name
    for instrument in sorted(by_instrument):
        pitches = by_instrument[instrument]
        for root in sorted(pitches):
            if all(root + i in pitches for i in intervals):
                names = [pitches[root + i] for i in intervals]
                events = []
                for k, onset in enumerate(onsets):
                    wave = notes[names[k % len(names)]][1][:int(length * SAMPLE_RATE)]
                    events.append((onset, wave * envelope(len(wave), 0.002, length)))
                return instrument, place(events)
    return None, None


def probe_clips(nsynth_path):
    """The probe clips: (name, kind, source, tags, waveform)."""
    rng = np.random.default_rng(1234)
    clips = [
        ("synth_kick", "one-shot", "synthesized", ["kick drum"], kick(rng)),
        ("synth_snare", "one-shot", "synthesized", ["snare drum"], snare(rng)),
        ("synth_hihat", "one-shot", "synthesized", ["hi-hat"], hihat(rng)),
        ("drum_loop", "loop", "synthesized", ["drum beat", "kick drum", "snare drum", "hi-hat"], drum_loop(rng)),
    ]

    notes = nsynth_notes(nsynth_path)
    clips.extend(one_shots(notes))

    eighths = [k * BEAT / 2 for k in range(16)]
    quarters = [k * BEAT for k in range(8)]
    plans = [
        ("keyboard_arpeggio_loop", "loop", "keyboard", [0, 4, 7, 12], eighths, 0.4,
         ["arpeggio", "piano", "synth lead"]),
        ("bass_line_loop", "loop", "bass", [0, 7, 5, 3], quarters, 0.5, ["bass"]),
        ("string_chord_stem", "stem", "string", [0, 4, 7], [0.0, 0.0, 0.0], 4.0, ["strings", "synth pad"]),
        ("vocal_line_stem", "stem", "vocal", [0, 2, 4], [0.0, 1.0, 2.0, 3.0], 1.0, ["vocal"]),
        ("guitar_line_stem", "stem", "guitar", [0, 3, 5, 7], quarters, 0.5,
         ["electric guitar", "acoustic guitar", "arpeggio"]),
    ]
    for name, kind, family, intervals, onsets, length, tags in plans:
        instrument, wave = sequence(notes, family, intervals, onsets, length)
        if wave is not None:
            clips.append((name, kind, "nsynth sequence:" + instrument, tags, wave))

    peak = 0.95
    return [(n, k, s, t, (w / max(np.max(np.abs(w)), 1e-6) * peak).astype(np.float32))
            for n, k, s, t, w in clips]


def caption_tokens(tokenizer, caption, pad_id):
    """The token ids of a caption through its end-of-text token, without the padding."""
    ids = tokenizer([caption])[0].tolist()
    return [i for i in ids if i != pad_id]


def reference(repo, nsynth_path, out_dir):
    """Writes the native weights, the probe clips, and the reference embeddings and log-mel."""
    import torch
    from open_clip.audio.transform import _get_mel
    import safetensors_extractor as core
    import extract_clap2_weights as extractor

    model, preprocess = load(repo)
    state = {k: v.detach().cpu().numpy() for k, v in model.state_dict().items()}
    heads = model.audio.encoder.vit.blocks[0].attn.num_heads
    core.write_state_dictionary(extractor.native_state(state, heads), os.path.join(out_dir, "weights"))

    stages = {}
    manifest = {"repo": repo, "sample_rate": SAMPLE_RATE, "clips": [], "tags": []}
    transform = preprocess()
    clips = probe_clips(nsynth_path)
    with torch.no_grad():
        for name, kind, source, tags, wave in clips:
            patches = transform((torch.from_numpy(wave)[None], SAMPLE_RATE))
            embedding = model.encode_audio({k: v[None] for k, v in patches.items()})[0]
            stages["clip." + name] = wave
            stages["audio." + name] = embedding.numpy()
            manifest["clips"].append({"name": name, "kind": kind, "source": source, "tags": tags,
                                      "samples": int(wave.shape[0])})

        first = clips[0]
        mel_cfg = {"sample_rate": SAMPLE_RATE, "window_size": 1024, "hop_size": 320,
                   "mel_bins": 128, "fmin": 50, "fmax": 16000}
        stages["mel." + first[0]] = _get_mel(torch.from_numpy(first[4]), mel_cfg).numpy()

        tokenizer = open_clip.get_tokenizer("hf-hub:" + repo)
        for index, (label, caption) in enumerate(TAGS):
            ids = caption_tokens(tokenizer, caption, model.text.pad_id)
            text = model.encode_text(torch.tensor([ids + [model.text.pad_id] * (256 - len(ids))]))[0]
            stages["tokens.%d" % index] = np.asarray(ids, dtype=np.float32)
            stages["text.%d" % index] = text.numpy()
            manifest["tags"].append({"label": label, "caption": caption})

    references = os.path.join(out_dir, "references")
    core.dump_reference_activations(stages, references)
    with open(os.path.join(references, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print("Wrote %d clips and %d tags to %s" % (len(clips), len(TAGS), references))


def projected(features):
    """The projected embedding of one input from ``get_audio_features`` / ``get_text_features``,
    which return the tensor itself in older ``transformers`` releases and a model output carrying
    it as ``pooler_output`` in newer ones."""
    tensor = features.pooler_output if hasattr(features, "pooler_output") else features
    return tensor[0].numpy()


def compare_htsat(repo, nsynth_path, out_dir):
    """Scores the probe set with a first-generation LAION CLAP checkpoint (HTSAT audio tower,
    RoBERTa text tower) through ``transformers``, writing the embeddings in the same reference
    layout so ``score_clap2_reference.py`` reports its accuracy beside CLAP2's. Only the reference
    model runs here; these checkpoints have no native port.
    """
    import torch
    from transformers import ClapModel, ClapProcessor
    from open_clip.audio.dsp import resample
    import safetensors_extractor as core

    model = ClapModel.from_pretrained(repo).eval()
    processor = ClapProcessor.from_pretrained(repo)
    rate = processor.feature_extractor.sampling_rate
    stages = {}
    manifest = {"repo": repo, "sample_rate": rate, "clips": [], "tags": []}
    clips = probe_clips(nsynth_path)
    with torch.no_grad():
        for name, kind, source, tags, wave in clips:
            audio = resample(torch.from_numpy(wave), SAMPLE_RATE, rate).numpy()
            inputs = processor(audio=audio, sampling_rate=rate, return_tensors="pt")
            stages["audio." + name] = projected(model.get_audio_features(**inputs))
            manifest["clips"].append({"name": name, "kind": kind, "source": source, "tags": tags,
                                      "samples": int(audio.shape[0])})
        for index, (label, caption) in enumerate(TAGS):
            inputs = processor(text=[caption], return_tensors="pt", padding=True)
            stages["text.%d" % index] = projected(model.get_text_features(**inputs))
            manifest["tags"].append({"label": label, "caption": caption})

    references = os.path.join(out_dir, "references")
    core.dump_reference_activations(stages, references)
    with open(os.path.join(references, "manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print("Wrote %d clips and %d tags to %s" % (len(clips), len(TAGS), references))


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=["inspect", "rope", "details", "reference", "compare-htsat"])
    parser.add_argument("repo", help="hub repository, e.g. laion/clap2-xxs-audioxl-s1B")
    parser.add_argument("--nsynth", help="NSynth test parquet (reference mode)")
    parser.add_argument("--out", help="output directory (reference mode)")
    args = parser.parse_args()
    if args.mode == "inspect":
        inspect(args.repo)
    elif args.mode == "rope":
        rope_layout(args.repo)
    elif args.mode == "details":
        details(args.repo)
    elif args.mode == "reference":
        if not args.nsynth or not args.out:
            parser.error("reference mode needs --nsynth and --out")
        reference(args.repo, args.nsynth, args.out)
    elif args.mode == "compare-htsat":
        if not args.nsynth or not args.out:
            parser.error("compare-htsat mode needs --nsynth and --out")
        compare_htsat(args.repo, args.nsynth, args.out)


if __name__ == "__main__":
    main()
