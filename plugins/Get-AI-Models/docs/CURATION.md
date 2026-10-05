# Catalog curation record

Why each row in `src/main/assets/catalog/models.json` is there, and what was checked. Update this
file in the same change that edits the catalog.

The catalog is admitted by checklist, not by taste. A candidate `(model, quantization)` file earns a
row only by passing every hard gate:

| # | Gate | How it is proven |
|---|---|---|
| 1 | Instruction-tuned, not a base model | The base repository is the vendor's `-Instruct` / instruct-tuned release |
| 2 | Fully open licence, no restrictions, on the base model **and** the GGUF re-upload | `cardData.license` on both Hugging Face repositories |
| 3 | Loads and generates coherent output in our AI plugin | **Admission test — downstream work** |
| 4 | Passes the fixed tool-use eval at >= 80% | **Admission test — downstream work** |
| 5 | Valid chat template, embedded in or known for the GGUF | `chat_template` in the base repo's `tokenizer_config.json`, or a `chat_template.jinja` |
| 6 | Single file from a reputable uploader, sha256 pinned to a specific revision | Hugging Face tree API: one `.gguf`, no `-00001-of-000NN` parts; URL resolves a commit sha, not `main` |
| 7 | Context window >= 16k tokens | `max_position_embeddings` in the base repo's `config.json` |

Quantization is **not** restricted. `Q4_K_M` and `Q8_0` both appear below; `Q4_0` would be equally
eligible. The gates are about behaviour, not about a preferred format.

## Status of gates 3 and 4

Gates 3 and 4 are behavioural, and the ticket places the harness that proves them outside this
plugin: "a repeatable harness runs a candidate through the project's AI plugin (the llama.cpp
wrapper) ... Building this harness is downstream work, not part of the download plugin."

So the shipped catalog is **the set of candidates that clear every statically checkable gate
(1, 2, 5, 6, 7)**. Gates 3 and 4 are unproven — *not failed* — for every entry below: none has been
run through the harness, so none has been shown either to work or to break.

This is surfaced in the product, not just here. Every entry carries
`"behaviouralGatesVerified": false`, the field is **required** by `CatalogLoader` so a new entry
cannot omit it, and an expanded row shows *"Not yet validated in the AI plugin"* while it is false. A
unit test asserts no entry claims otherwise.

**Unresolved contradiction for the team.** The ticket requires gates 3 and 4 for admission *and*
places the harness that proves them outside this plugin's scope. Both cannot hold while a catalog
ships: the options are to accept the flagged entries as provisional (current state), hold the plugin
until the harness exists, or ship with an empty catalog — which would fail the ticket's own
definition of done ("downloads + verifies a file"). When the harness exists, run it over this list,
flip the flag per entry, and remove anything that fails.

## Verification method

Metadata came from the Hugging Face API, not from model cards or memory:

- `GET /api/models/{repo}` — `sha` (the revision to pin), `cardData.license`, `gated`
- `GET /api/models/{repo}/tree/{sha}?recursive=true` — the exact `size` and `lfs.oid` per file.
  For an LFS file **`lfs.oid` is the SHA-256 of the file content**, which is where each catalog
  `sha256` comes from — no multi-gigabyte download was needed to obtain it.
- `GET /{base}/resolve/main/config.json` — `max_position_embeddings` (gate 7)
- `GET /{base}/resolve/main/tokenizer_config.json` (or `chat_template.jinja`) — gate 5
- `HEAD` on each pinned `resolve/{sha}/{file}` URL — confirmed HTTP 200 with `Content-Length`
  equal to the catalogued `sizeBytes` and `X-Linked-Etag` equal to the catalogued `sha256`.

All 12 entries passed that final HEAD cross-check, re-run after the most recent edit.

## Catalog entries (maintainer-selected)

**This catalog is a maintainer override, not a gate-derived list.** The first six models below were
named explicitly by the ticket owner, replacing the eleven gate-passing entries that shipped
earlier; the seventh was requested later in ADFA-6321. Three of the six break at least one hard gate; those gates are therefore **no longer enforced** in
`CatalogLoader` (licence, 16k context) nor asserted in `CatalogLoaderTest`.

| Entry id | Base model | GGUF uploader | Ctx | Licence | Gate waived |
|---|---|---|---|---|---|
| `qwen2-5-0-5b-q4_k_m` | `Qwen/Qwen2.5-0.5B` | QuantFactory | 32768 | apache-2.0 | **1** (base model) |
| `qwen3-0.6b-q8_0` | `Qwen/Qwen3-0.6B` | Qwen (Alibaba Cloud) | 40960 | apache-2.0 | none |
| `qwen3.5-0.8b-q4_k_m` | `Qwen/Qwen3.5-0.8B` | unsloth | 262144 | apache-2.0 | none |
| `llama-3-2-1b-instruct-q4_k_m` | `meta-llama/Llama-3.2-1B-Instruct` | unsloth | 131072 | llama3.2 | **2** (Llama 3.2 Community Licence; upstream repo gated) |
| `smollm2-360m-instruct-q8_0` | `HuggingFaceTB/SmolLM2-360M-Instruct` | Hugging Face TB | 8192 | apache-2.0 | **7** (8192 context) |
| `h2o-danube3-500m-base-q4_k_m` | `h2oai/h2o-danube3-500m-base` | mradermacher | 8192 | apache-2.0 | **1** (base model), **7** (8192 context) |
| `qwen2-5-coder-1-5b-instruct-q4_k_m` | `Qwen/Qwen2.5-Coder-1.5B-Instruct` | Qwen (Alibaba Cloud) | 32768 | apache-2.0 | none |

`qwen2-5-coder-1-5b-instruct-q4_k_m` was added by ADFA-6321: it is the only code-trained model in
the catalog. Its gates 1, 2, 5, 6 and 7 were re-checked against the API on 2026-10-02, and
`minRamBytes` is 4 GiB because it is the largest file here (1.04 GiB), one step above the 3 GiB of
the ~0.8 GB entries.

Gates 3 and 4 remain unproven for all seven, as before. Gate 5 (chat template) does not apply to the
two base models - they have none, which is part of why they are not usable as assistants. Gate 6
holds for every entry: single file, sha256 pinned to a commit revision, HEAD-verified.

Two entries have no GGUF published by the model's own author, so a third-party conversion is used:
`QuantFactory` for Qwen2.5-0.5B and `mradermacher` for Danube3-500m-base. Both declare the base
model's Apache-2.0 licence. Qwen3.5-0.8B likewise uses `unsloth`.

### Removed at the owner's request

The previous eleven entries (Qwen3 1.7B/4B/8B, Qwen2.5 1.5B, Qwen2.5-Coder 1.5B/7B, Granite 4.0 Micro,
Granite 3.3 2B, SmolLM3 3B, Mistral 7B v0.3 — plus Qwen3 0.6B and Qwen3.5 0.8B, which were kept) all
passed gates 1, 2, 5, 6 and 7. They were removed because the catalog was narrowed to the list above,
not because any of them failed a check. Their verified metadata is recoverable from git history.

## Rejected candidates

Recorded so the same candidates are not re-litigated. None of these are catalog rows.

Each row's evidence was read from the same API, not from recollection.

| Candidate | Gate failed | Detail |
|---|---|---|
| `meta-llama/Llama-3.2-3B-Instruct` (Llama family) | 2 | `license: llama3.2`, `gated: manual`. Community licence with use restrictions; not fully open. |
| `google/gemma-3-4b-it` (Gemma family) | 2 | `license: gemma`, `gated: manual`. Subject to Gemma terms of use. |
| `Qwen/Qwen2.5-3B-Instruct` | 2 | `license: other`, `license_name: qwen-research` — unlike its Apache-2.0 1.5B and 7B siblings. |
| `tiiuae/Falcon3-3B-Instruct` | 2 | `license: other`, `license_name: falcon-llm-license`; carries an acceptable-use policy. |
| `bartowski/microsoft_Phi-4-mini-instruct-GGUF` | 2 | The base model is MIT, but the re-upload repository declares no licence at all. Gate 2 requires the licence on **both**, so it is out until the re-upload states one. |
| `HuggingFaceTB/SmolLM2-1.7B-Instruct` | 7 | `max_position_embeddings: 8192`. |
| `allenai/OLMo-2-1124-7B-Instruct` | 7 | `max_position_embeddings: 4096`. |
| `openai/gpt-oss-20b` | — | Apache-2.0, ungated, 131072 context: it clears every statically checkable gate. Held back on size — roughly 12 GB at MXFP4 is not usable on the phones this plugin targets — pending the deferred runtime-budget gate. Not a licence rejection. |
| `google/gemma-3-270m` | 2, plus unavailable | `license: gemma`, `gated: manual`. **Requested by the owner but not addable:** the repo ships no GGUF, and the only GGUF conversions are of the `-it` variant, which is a different model. Substituting it was not authorised. |
| Any split GGUF (`-00001-of-00002`) | 6 | Explicitly single-file only, so one checksum covers the whole model. |

## Adding an entry

1. Confirm gates 1, 2, 5 and 7 from the base repository, and gate 2 again on the GGUF repository.
2. Read the GGUF repo's current commit `sha`, then take `size` and `lfs.oid` for the one file you
   want from `/api/models/{repo}/tree/{sha}?recursive=true`.
3. Build the URL as `https://huggingface.co/{repo}/resolve/{sha}/{file}` — a commit sha, never
   `main`, or a re-upload would break the pinned checksum.
4. `HEAD` that URL and confirm `Content-Length` matches `size` and `X-Linked-Etag` matches
   `lfs.oid`.
5. Write the strengths-and-weaknesses paragraph for **that quantization**, not for the model family,
   and say what it is bad at.
6. Add the row here, then run `../gradlew test` — `CatalogLoaderTest` re-validates the whole asset.
