# Foresight Wake Training

This directory is a separate, resumable training workspace for a custom openWakeWord model named `hey_foresight`. It does not modify or import the Foresight application under `src/foresight_device`.

## Environments

Keep training dependencies out of the main application environment. For a Windows prototype, prefer Python 3.11 and install `requirements/local.txt` manually in a separate environment. The current Qualcomm Adreno X1-45 GPU is not CUDA-capable; use CPU for small prototype checks and a disposable Linux/NVIDIA CUDA machine for quality training.

The local requirements include CPU-only PyTorch for the tiny benchmark and future prototype data path. A real positive-generation measurement additionally needs a reviewed Piper generator checkout, its matching voice checkpoint and metadata, and eSpeak phonemization support; these are intentionally not downloaded or installed by this skeleton.

## Tiny Positive-Generation Benchmark

This is an opt-in five-clip measurement only. It does not download datasets, augment audio, extract features, or train a model. It reuses the reference notebook's legacy generator path:

- Generator source: `https://github.com/dscripka/piper-sample-generator` checked out locally. The reference notebook does not pin a commit, so record the checkout commit before trusting a result. Its top-level `generate_samples.py` is required by the openWakeWord 0.6-era path.
- Checkpoint: `https://github.com/rhasspy/piper-sample-generator/releases/download/v1.0.0/en-us-libritts-high.pt`, stored as `cache/tools/piper-sample-generator/models/en-us-libritts-high.pt`.
- Metadata: the matching `en-us-libritts-high.pt.json` shipped by that reviewed generator checkout must remain beside the checkpoint. Do not substitute the newer `v2.0.0` LibriTTS-R medium checkpoint: the reference path expects this old checkpoint and metadata pair.
- Phonemization: `scripts/native_espeak.py` calls the Windows x64 eSpeak-NG executable directly. It is injected as the small `espeak_phonemizer` compatibility module only into the Piper benchmark subprocess, so no Python phonemizer package or source-checkout patch is needed.
- PyTorch loading: `scripts/piper_generator_launcher.py` starts the reviewed legacy generator and applies `weights_only=False` only when it loads the explicitly supplied `en-us-libritts-high.pt` checkpoint. It records the checkpoint SHA-256 in the benchmark report and can enforce a previously recorded digest with `--expected-checkpoint-sha256`.

The v1.0.0 checkpoint is approximately 166 MB; its JSON metadata is approximately 21 KB. These are the only assets needed for this benchmark. The generator source is an external reviewed checkout, not a dependency of the Foresight application and not a generated model artifact to commit.

With `.venv-training` activated, provision the reviewed assets manually, inspect the checkout, and run the following. Install the x64 eSpeak-NG package because the training interpreter is x64, even on the ARM64 host:

```powershell
.\.venv-training\Scripts\python.exe -m pip install -r training/wake/requirements/local.txt
git clone https://github.com/dscripka/piper-sample-generator training/wake/cache/tools/piper-sample-generator
Invoke-WebRequest https://github.com/rhasspy/piper-sample-generator/releases/download/v1.0.0/en-us-libritts-high.pt -OutFile training/wake/cache/tools/piper-sample-generator/models/en-us-libritts-high.pt
.\.venv-training\Scripts\python.exe training/wake/scripts/native_espeak.py "hey foresight" --voice en-us --espeak-path "C:\Program Files\eSpeak NG\espeak-ng.exe"
.\.venv-training\Scripts\python.exe training/wake/scripts/piper_generator_launcher.py --trusted-model training/wake/cache/tools/piper-sample-generator/models/en-us-libritts-high.pt --verify-checkpoint
.\.venv-training\Scripts\python.exe training/wake/scripts/piper_generator_launcher.py --generator-path training/wake/cache/tools/piper-sample-generator/generate_samples.py --trusted-model training/wake/cache/tools/piper-sample-generator/models/en-us-libritts-high.pt --preflight-imports
.\.venv-training\Scripts\python.exe training/wake/scripts/benchmark.py --profile prototype --run-id piper-five --measure-positive-generation --sample-count 5 --generator-path training/wake/cache/tools/piper-sample-generator/generate_samples.py --voice-model training/wake/cache/tools/piper-sample-generator/models/en-us-libritts-high.pt --espeak-path "C:\Program Files\eSpeak NG\espeak-ng.exe"
```

The benchmark writes up to five reusable WAV files below `cache/benchmarks/piper-five/positive_generation/` and a report below `outputs/prototype/piper-five/reports/`; both locations are ignored. Delete that benchmark cache directory whenever the clips are no longer useful.

## Tiny Augmentation Benchmark

The augmentation benchmark consumes up to ten existing 16 kHz positive WAV clips. It creates one deterministic four-second synthetic ambient-noise WAV and one short synthetic room impulse response under `cache/benchmark_assets/tiny_augmentation/`; it then performs real convolution, SNR-based background mixing, gain, and WAV output. It does not download MIT RIRs, AudioSet, FMA, ACAV100M, or any other corpus.

It uses the already-present `numpy`, `scipy`, and `soundfile` dependencies. `torchaudio`, `audiomentations`, `torch-audiomentations`, `speechbrain`, and `acoustics` are not required for this tiny throughput measurement; they remain deferred with the full training augmentation workflow.

```powershell
.\.venv-training\Scripts\python.exe training/wake/scripts/benchmark.py --profile prototype --run-id augment-five --measure-augmentation --sample-count 5 --augmentation-input-dir training/wake/cache/benchmarks/piper-five/positive_generation
```

The command writes five augmented WAVs to `cache/benchmarks/augment-five/augmentation/`, creates the two small reusable assets under `cache/benchmark_assets/tiny_augmentation/`, and records measured throughput plus prototype/quality projections in `outputs/prototype/augment-five/reports/benchmark.json`. `tracemalloc` reports Python-managed peak allocations only; it is not a full native-process memory measurement.

## Tiny Feature-Extraction Benchmark

The feature benchmark uses the same `openwakeword.utils.AudioFeatures.embed_clips` ONNX path consumed by the eventual training stage. It converts each augmented clip to mono 16-bit PCM, right-pads or truncates it to the trainer's 32,000-sample (two-second) input, computes 32-bin mel frames, then produces the shared 96-dimensional Google `speech_embedding` features. This is not a synthetic substitute.

The training environment already contains the only required packages: `openwakeword==0.6.0`, CPU `onnxruntime`, `numpy`, and `soundfile`. It does not require `torch`, `torchaudio`, a wake-word classifier, VAD, ACAV100M, FMA, AudioSet, or any negative feature array for this benchmark.

Before extraction, explicitly download only these upstream Apache-2.0 openWakeWord v0.5.1 feature graphs into `cache/models/openwakeword-v0.5.1/`:

- `melspectrogram.onnx`, approximately 1.1 MB: 16 kHz PCM to 32-bin mel frames.
- `embedding_model.onnx`, approximately 1.3 MB: mel frames to 96-dimensional speech embeddings.

```powershell
.\.venv-training\Scripts\python.exe training/wake/scripts/extract_features.py --prepare-models --model-dir training/wake/cache/models/openwakeword-v0.5.1
.\.venv-training\Scripts\python.exe training/wake/scripts/benchmark.py --profile prototype --run-id features-five --measure-feature-extraction --sample-count 5 --feature-input-dir training/wake/cache/benchmarks/augment-five/augmentation
```

The first command is the only download and is explicit. The benchmark writes `positive_features.npy` under `cache/benchmarks/features-five/feature_extraction/` and records measured extraction throughput, array shape, output bytes, Python-managed peak allocation, and prototype/quality projections in `outputs/prototype/features-five/reports/benchmark.json`.

## Tiny Training Benchmark

The training benchmark uses `openwakeword.train.Model(n_classes=1, input_shape=(16, 96), model_type="dnn", layer_dim=32)` and its real `train_model` loop. It feeds the existing feature arrays through a small local balanced-batch iterator equivalent to the upstream `mmap_batch_generator`; this avoids using the full data pipeline for an already-extracted `[N, 16, 96]` benchmark. It does not use the full `auto_train` workflow, validation datasets, checkpoint averaging, or model export.

It needs the direct `openwakeword.train` imports `torchinfo==1.8.0` and `torchmetrics==1.2.0`, added to `requirements/local.txt`. The five measured positive embeddings are tiled into a 64-example benchmark-positive batch. A separate 64-example Gaussian feature array is created only to satisfy the binary classifier's negative class and is named `benchmark_negative_features_benchmark_only.npy`; it is not representative of real background audio and must never be used for a prototype or quality model.

The legacy `acoustics==0.2.6` import expects `scipy.special.sph_harm`, which modern SciPy renamed to `sph_harm_y` with swapped angular arguments. `scripts/training_compat.py` applies the reference notebook's process-local alias before importing `openwakeword.data`; it does not modify SciPy or site-packages. Check this boundary without training:

```powershell
.\.venv-training\Scripts\python.exe training/wake/scripts/train.py --preflight-imports
```

SpeechBrain 1.1 registers deprecated optional integrations as lazy redirects in
`sys.modules`. PyTorch inspects module metadata while constructing Adam, which
can otherwise activate unused integrations such as `k2` or Hugging Face
`transformers`. `training_compat.py` removes only those lazy redirect entries
inside the benchmark process after openWakeWord has imported its required audio
helpers. It does not install or use those optional integrations.

```powershell
.\.venv-training\Scripts\python.exe -m pip install -r training/wake/requirements/local.txt
.\.venv-training\Scripts\python.exe training/wake/scripts/benchmark.py --profile prototype --run-id train-twenty --measure-training --training-steps 20 --training-positive-features training/wake/cache/benchmarks/features-five/feature_extraction/positive_features.npy
```

The benchmark writes only the two benchmark feature arrays under `cache/benchmarks/train-twenty/training/` and the report under `outputs/prototype/train-twenty/reports/benchmark.json`. It reports startup/dataset time separately from loop time, step throughput, Python-managed peak allocation, and projected 2,000/50,000-step times. No checkpoint, ONNX file, or deployment artifact is produced.

This old Piper generator workflow was built and officially automated for Linux. The local wrapper avoids its fragile Linux-only `ctypes` package, but the generator still has legacy imports (`piper_train`, `torchaudio`, and `webrtcvad`). The launcher uses `weights_only=False` for the reviewed Rhasspy v1.0.0 checkpoint because it is a trusted full pickle; PyTorch warns that this setting can execute arbitrary pickle code, so never use the launcher for an unreviewed checkpoint. Do not patch around later import failures blindly: report the exact error and use WSL/Linux or cloud infrastructure if the reviewed path cannot initialize cleanly.

## Workflow

1. Run `python training/wake/scripts/doctor.py --profile prototype --run-id first-check`.
2. Run `python training/wake/scripts/benchmark.py --profile prototype --run-id first-check`.
3. Run `prepare_assets.py`; it creates directories and does not download data.
4. Review the planned generate, augment, feature, and train manifests before performing expensive work in the separate training environment.
5. Use `export.py --model-path ...` to validate and package a completed ONNX model bundle.
6. Add real recordings under `data/evaluation/`, record an evaluation plan, then deploy only an explicitly selected validated bundle.

Each stage accepts `--profile`, `--run-id`, and `--force`. Small manifests under `outputs/<profile>/<run-id>/manifests/` record a configuration hash, installed package versions, inputs, outputs, and state. Completed stages with existing outputs are skipped unless `--force` is supplied.

## Assets And Models

The prototype profile intentionally excludes FMA and ACAV100M assets. No script downloads large datasets automatically. Cache contents, generated WAVs, features, checkpoints, and ONNX artifacts are developer-local.

The default benchmark reports only a measured tiny PyTorch CPU operation when available. It measures real positive generation only with explicit `--measure-positive-generation`, `--generator-path`, and `--voice-model` arguments, limits that run to one through ten clips, and writes measured total seconds, clips per second, seconds per clip, and projections for the prototype and quality clip counts.

The training target is only `hey foresight`. The reference notebook is an ignored non-source artifact for audit purposes; do not execute it directly on Windows.

Deployment copies `hey_foresight.onnx`, plus `hey_foresight.onnx.data` when present, into `models/wake/` without overwriting existing files. Set `FORESIGHT_WAKE_MODEL_PATH` to the deployed `.onnx` file. The runtime wake adapter remains independent of this training workflow.

## Prototype-v1 Manual Run

`prototype-v1` is a laboratory prototype, not production wake-word quality. LibriSpeech
`test-clean` is intentionally limited speech-negative coverage, and the synthetic ambient
fallback is weaker than real workspace recordings. Do not deploy until evaluation includes
real workspace positives and negatives. Threshold `0.5` is only an evaluation starting point;
the scripts never change the runtime threshold or `FORESIGHT_WAKE_MODEL_PATH` automatically.
Deployment additionally requires the evaluation report to reference the exact SHA-256 of the
export manifest currently being deployed; matching filenames alone are not accepted.

```powershell
.\.venv-training\Scripts\Activate.ps1
.\.venv-training\Scripts\python.exe training/wake/scripts/doctor.py --profile prototype --run-id prototype-v1
.\.venv-training\Scripts\python.exe training/wake/scripts/prepare_assets.py --profile prototype --run-id prototype-v1 --execute --download-librispeech
.\.venv-training\Scripts\python.exe training/wake/scripts/prepare_negatives.py --profile prototype --run-id prototype-v1 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/generate_positives.py --profile prototype --run-id prototype-v1 --execute --espeak-path "C:\Program Files\eSpeak NG\espeak-ng.exe"
.\.venv-training\Scripts\python.exe training/wake/scripts/augment.py --profile prototype --run-id prototype-v1 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/extract_features.py --profile prototype --run-id prototype-v1 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/train.py --profile prototype --run-id prototype-v1 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/export.py --profile prototype --run-id prototype-v1 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/record_evaluation.py positive --count 25
.\.venv-training\Scripts\python.exe training/wake/scripts/record_evaluation.py ordinary_speech --count 20
.\.venv-training\Scripts\python.exe training/wake/scripts/record_evaluation.py ambient --count 10
.\.venv-training\Scripts\python.exe training/wake/scripts/record_evaluation.py tv_background --count 10
.\.venv-training\Scripts\python.exe training/wake/scripts/record_evaluation.py noise --count 10
.\.venv-training\Scripts\python.exe training/wake/scripts/evaluate.py --profile prototype --run-id prototype-v1 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/deploy.py --profile prototype --run-id prototype-v1 --model-path training/wake/outputs/prototype/prototype-v1/artifacts/hey_foresight.onnx --deploy
```

Outputs are cached below `cache/{positives,negatives,augmented,features}/prototype/prototype-v1/`.
Training, export validation, and evaluation reports are under
`outputs/prototype/prototype-v1/{artifacts,reports,manifests}/`. Generation is about 97 minutes,
augmentation about 113 minutes, feature extraction about 0.165 seconds per clip, and training
about 33 seconds plus 35 seconds. Expect roughly 3.5-4.5 hours before manual evaluation and a
conservative 1-2 GB cache footprint, depending on negative clips and disk contention.

Record evaluation WAVs with `record_evaluation.py` before evaluation. The suggested 25 positive,
20 ordinary-speech, and ten each ambient, TV-background, and noise clips are initial prototype
counts, not a production-quality benchmark. Evaluation refuses to complete without at least one
positive and one negative WAV; every scored WAV is hashed as an evaluation input, so adding or
editing a recording invalidates the previous completed evaluation.

## Prototype-v2 Real-Positive Anchor

`prototype-v2` leaves `prototype-v1` unchanged and adds a small real-audio anchor to the
same DNN architecture. The existing sorted evaluation recordings are assigned deterministically:
`positive_001.wav` through `positive_015.wav` train, `positive_016.wav` through
`positive_020.wav` validate, and `positive_021.wav` through `positive_025.wav` remain held out.
The held-out recordings are never trained on and are the only positive recordings evaluated by
the v2 evaluation stage. Existing ordinary-speech, ambient, TV-background, and noise recordings
remain held-out negatives.

Five room-convolution/background-mixing/gain variants are produced for each real train and
validation recording: 75 augmented real train clips and 25 augmented real validation clips.
The merged feature arrays therefore contain 3,090 train positives (3,000 synthetic, 15 raw real,
75 augmented real) and 530 validation positives (500 synthetic, 5 raw real, 25 augmented real).
The 3,000 legitimate training negatives remain unchanged, so the training batch is slightly
positive-heavy rather than exactly balanced.

```powershell
.\.venv-training\Scripts\python.exe training/wake/scripts/prepare_assets.py --profile prototype-v2 --run-id prototype-v2 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/prepare_negatives.py --profile prototype-v2 --run-id prototype-v2 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/generate_positives.py --profile prototype-v2 --run-id prototype-v2 --execute --espeak-path "C:\Program Files\eSpeak NG\espeak-ng.exe"
.\.venv-training\Scripts\python.exe training/wake/scripts/prepare_real_positives.py --profile prototype-v2 --run-id prototype-v2 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/augment.py --profile prototype-v2 --run-id prototype-v2 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/extract_features.py --profile prototype-v2 --run-id prototype-v2 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/train.py --profile prototype-v2 --run-id prototype-v2 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/export.py --profile prototype-v2 --run-id prototype-v2 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/diagnose_model.py --profile prototype-v2 --run-id prototype-v2
.\.venv-training\Scripts\python.exe training/wake/scripts/evaluate.py --profile prototype-v2 --run-id prototype-v2 --execute
```

Do not deploy v2 automatically. Based on v1 measurements, synthetic generation and augmentation
remain roughly 3.5 hours; the additional 100 real-audio variants add about 3-4 minutes, feature
extraction across the 7,120 v2 clips is about 20 minutes, and the 2,000-step CPU training run is
about a minute. Inspect the
separate source-group and held-out score distributions in `model_diagnostic.json` before any
deployment decision.

## Fresh Field Evaluation

`data/field_evaluation/` is evaluation-only and is never read by positive splitting, generation,
augmentation, training feature extraction, or training. It is separate from `data/evaluation/`,
which remains the historical v2 train/validation/held-out split. The frozen v2 model can therefore
be tested on genuinely fresh recordings without contaminating any prior result.

Use the field recorder with `laptop_mic` for the next collection session. It starts capture before
the cue, keeps one second of pre-roll and post-roll by default, then places a fixed two-second
analysis window around the cue. This preserves realistic surrounding audio without tightly trimming
speech. Each take must be explicitly accepted or rejected after optional playback; only accepted
takes with metadata are eligible for field evaluation.

```powershell
.\.venv-training\Scripts\python.exe training/wake/scripts/record_field_evaluation.py positive --count 25 --source-device laptop_mic
.\.venv-training\Scripts\python.exe training/wake/scripts/record_field_evaluation.py ordinary_speech --count 25 --source-device laptop_mic
.\.venv-training\Scripts\python.exe training/wake/scripts/record_field_evaluation.py ambient --count 10 --source-device laptop_mic
.\.venv-training\Scripts\python.exe training/wake/scripts/record_field_evaluation.py tv_background --count 10 --source-device laptop_mic
.\.venv-training\Scripts\python.exe training/wake/scripts/record_field_evaluation.py noise --count 10 --source-device laptop_mic
.\.venv-training\Scripts\python.exe training/wake/scripts/evaluate_field.py --profile prototype-v2 --run-id prototype-v2 --execute
```

The field report groups scores by source device and negative category. It reports recall, false
negatives, false activations, false-positive rate, and precision at thresholds `0.10` through
`0.60`, plus score ranges and overlap. It only reports candidate thresholds for review; it never
changes a runtime threshold, model path, model, checkpoint, or deployment state.

## Prototype-v3 Independent Real Positives

`prototype-v3` is a separate future run. It must not read `data/field_evaluation/`, which remains
frozen test data. The completed collection contains 100 accepted training utterances and 20 accepted validation
utterances under `data/real_positive_v3/{train,validation}/`. They must be independently spoken
recordings, not repeated augmentations of a small source set. Metadata retains source device and
collection conditions, so future `gopro_mic` and `future_wearable_mic` domains can be compared
without mixing recordings.

Record in varied batches across distance, orientation, pace, volume, emphasis, room condition, and
mild background conditions. For example, repeat the following command with distinct condition
values while preserving the completed 100/20 independent split:

```powershell
.\.venv-training\Scripts\python.exe training/wake/scripts/record_real_positives_v3.py train --count 20 --source-device laptop_mic --distance near --head-orientation facing --speaking-pace normal --volume normal --emphasis neutral --room-condition quiet_room --background-condition quiet
.\.venv-training\Scripts\python.exe training/wake/scripts/record_real_positives_v3.py validation --count 20 --source-device laptop_mic --distance medium --head-orientation turned --speaking-pace varied --volume soft --emphasis varied --room-condition living_room --background-condition mild_noise
```

V3 uses 1,500/250 synthetic train/validation positives, plus 100/20 independent raw real positives
and three variants per real recording. In training only, the combined raw-and-augmented real feature
groups are repeated three times: 1,200 of 2,700 positive feature rows are real-derived (about 44%).
Validation is not oversampled and contains 330 positive rows. The same DNN, 3,000/500 legitimate negative source, and 2,000 steps
remain unchanged.

```powershell
.\.venv-training\Scripts\python.exe training/wake/scripts/prepare_assets.py --profile prototype-v3 --run-id prototype-v3 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/prepare_negatives.py --profile prototype-v3 --run-id prototype-v3 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/generate_positives.py --profile prototype-v3 --run-id prototype-v3 --execute --espeak-path "C:\Program Files\eSpeak NG\espeak-ng.exe"
.\.venv-training\Scripts\python.exe training/wake/scripts/prepare_real_positives_v3.py --profile prototype-v3 --run-id prototype-v3 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/augment.py --profile prototype-v3 --run-id prototype-v3 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/extract_features.py --profile prototype-v3 --run-id prototype-v3 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/train.py --profile prototype-v3 --run-id prototype-v3 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/export.py --profile prototype-v3 --run-id prototype-v3 --execute
.\.venv-training\Scripts\python.exe training/wake/scripts/diagnose_model.py --profile prototype-v3 --run-id prototype-v3
.\.venv-training\Scripts\python.exe training/wake/scripts/evaluate_field.py --profile prototype-v3 --run-id prototype-v3 --execute
```

Based on prior local measurements, v3 synthetic generation is about 49 minutes, synthetic-plus-real
augmentation about 68 minutes, feature extraction about 16 minutes across its positive and negative
groups, and training roughly one minute. Treat the approximately 2.2-hour preprocessing estimate as
a laboratory estimate only; do not deploy automatically.
