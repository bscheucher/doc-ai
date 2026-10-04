# Running a local model on Azure

Whether the `ollama` profile, which cannot hold a usable vision model on this laptop, becomes
viable on Azure instead. Checked against the live subscription on **2026-10-03**; every number and
region below comes from a command quoted here, and the few things that are reasoning rather than
measurement are marked as such.

> doc-ai is a private learning project: one operator, no other consumer. See the note at the top of
> `doc-ai-specs/docs/SPEC.md`.

**Short answer.** Yes — a T4 removes the hardware limit completely, and at €0.36/hour cost is not
what stands in the way. The blocker moves to two things that are not about hardware: the deployed
environment is in a region with no GPU at all, and GPU quota on this subscription is unrequested.
Neither is expensive to clear: the region is an afternoon of work (a new environment), the quota a
day or so of waiting on a request.

---

## 1. Why the laptop cannot do it

Measured on the development machine — RTX 4050, 6 GB VRAM, 15 GB RAM — and recorded here because
it is the reason the question comes up at all:

| Model | Result |
|---|---|
| `gemma3:4b` | loads fully on the GPU (4.6 GB), and scores **1 of 29** compared fields (SPEC §8, §12) |
| `qwen2.5vl:7b` | will not load at any context size |
| `qwen2.5vl:3b` | stays on the CPU, crashed the Ollama server on an image |
| `llava` | ~35 s/page, misreads |
| `claude-sonnet-5` | 29/29 fields, 4/4 classifications on the same documents |

The requirement that blocks `qwen2.5vl:7b` sits in the weights and the vision projector rather than
in the KV cache, so lowering `num-ctx` does not recover it. It is a capacity wall, not a speed
one — which is what makes the T4 in §2.1 the relevant comparison, and why it is a comparison of
VRAM rather than of throughput.

Note what the table does *not* say: `qwen2.5vl:7b` has never been **measured** here. It failed to
load, which is not the same as failing §12.

## 2. The deployed environment has no GPU to add

```bash
for r in germanywestcentral westeurope swedencentral; do
  az containerapp env workload-profile list-supported -l "$r" -o tsv \
    --query "[?contains(properties.category,'GPU')].[name, properties.category, properties.gpus]"
done
```

| Region | GPU workload profiles |
|---|---|
| `germanywestcentral` | **none** |
| `westeurope` | `Consumption-GPU-NC8as-T4` (T4, 16 GB) |
| `swedencentral` | `Consumption-GPU-NC8as-T4`, `Consumption-GPU-NC24-A100` |

The existing environment is in the region without any:

```bash
az containerapp env show -n cae-docai-test -g rg-docai-test \
  --query "{loc:location, profiles:properties.workloadProfiles, vnet:properties.vnetConfiguration}"
# → Germany West Central, one profile "Consumption", vnet null
```

One piece of good news against the lesson learned when this environment was built: an
environment's *networking* is fixed at creation, but its **workload profiles are not**. This is
already a workload-profiles environment, so a GPU profile would be
`az containerapp env workload-profile add` on the running environment — no teardown. It is only the
region that forces a new environment here. West Europe is the nearest one that has a GPU.

### 2.1 The T4 next to the laptop card

Local figures read off the machine with `nvidia-smi` on 2026-10-03; T4 figures from the NVIDIA
datasheet, with cores, memory and GPU count confirmed from the workload-profile output above
(`cores: 8, memoryGiB: 56, gpus: 1`).

| | RTX 4050 Laptop (measured) | T4 in `Consumption-GPU-NC8as-T4` |
|---|---|---|
| VRAM total | 6141 MiB | 16384 MiB |
| VRAM actually free | **5320 MiB** (445 MiB on the display) | ~15 GiB, nothing else on the card |
| Memory bandwidth | ~192 GB/s (96-bit x 16 Gbps, from the 8001 MHz clock) | 320 GB/s (256-bit x 10 Gbps) |
| Architecture / compute capability | Ada, **8.9** | Turing, **7.5** |
| CUDA cores | 2560 | 2560 |
| FP32 | ~12 TFLOPS at a typical ~2.3 GHz boost (105 W max TGP); ~15.9 at the 3105 MHz `clocks.max.sm` ceiling, which it will not sustain | 8.1 TFLOPS |
| FP16 tensor | higher per clock, FP8 available | 65 TFLOPS, no FP8, no BF16 |
| Host RAM beside it | 15 GB | 56 GiB, 8 vCPU |
| Power and sustained clocks | laptop TGP, throttles under load | 70 W datacenter card, steady airflow |
| Cost | free | EUR 0.36/hour while a replica is up |

Core counts are identical and the laptop card is five years newer, so **the T4 is not the faster
GPU.** On raw FP32 the 4050 beats it, and Ada has FP8 and BF16 paths that compute capability 7.5
does not. The T4 wins on bandwidth (1.7x) and on sustained clocks, and loses on silicon generation.

So the T4 is not bought for speed; it is bought for capacity. The failure modes in §1 were never
"too slow" — they were `qwen2.5vl:7b` not loading at any context, and `qwen2.5vl:3b` spilling to the
CPU and taking the Ollama server down with it. 5.3 GB free against ~15 GB is the whole difference.
*Reasoning, not measured:* at Q4 the 7b weights plus the vision projector plus a 16384-token KV
cache should fit on a T4 fully resident, with no display competing for the card. The first deploy
should confirm it with `ollama ps` showing 100% GPU.

Three secondary effects matter more here than the headline specs:

- **56 GiB of host RAM.** The `qwen2.5vl:3b` crash was a CPU-spill failure on a 15 GB machine. With
  56 GiB a spill becomes slow rather than fatal.
- **This workload is prefill-heavy.** A 150 DPI page capped at a 1600 px edge (SPEC §8
  `max-image-edge`) is a large image prompt, and the output is a small JSON object. Prefill is
  compute-bound rather than bandwidth-bound, so the T4's one clear advantage is the one this
  workload leans on least. Take the `llava` figure in §1 as the anchor: 35 s/page on the 4050 does
  not become 3 s/page on a T4. Same ballpark, possibly a little worse per token.
- **Turing is the older end of supported.** CUDA 12 still covers compute capability 7.5, but newer
  flash-attention and quantisation kernels are tuned for Ampere and later. Nothing blocking, just
  less well-trodden than an A100 would be.

If per-page latency ever became the thing to optimise, the A100 profile in Sweden Central is the
honest answer — roughly 2 TB/s and several times the tensor throughput — at EUR 2.16/hour, six
times the T4's price (§4).

## 3. Quota is the actual blocker

Serverless GPU quota starts at zero and has to be requested. On this subscription the quota
provider is not even registered:

```bash
az quota list --scope "/subscriptions/<sub-id>/providers/Microsoft.App/locations/westeurope"
# → ERROR: (MissingRegistrationForResourceProvider) Microsoft.Quota
```

Registering it is one command (`az provider register -n Microsoft.Quota`), not done here only
because everything in this document was kept read-only. Registration is not the hurdle; the
request being granted is. Whether it goes through `az quota` or the portal, it is a request someone
at Microsoft approves, and that is where the day of waiting goes.

The GPU-**VM** alternative is worse, not better:

```bash
az provider show -n Microsoft.Compute --query registrationState -o tsv   # → NotRegistered
az vm list-usage -l westeurope                                          # → []  (consequence of the above)
```

Registering `Microsoft.Compute` is again one command. The hard part is the support ticket for GPU
core quota that follows, which on a small
consumption subscription is not granted as a matter of course. Container Apps serverless GPU is the
shorter road, and it scales to zero, which a VM does not.

## 4. Cost is not the obstacle

Azure retail prices, EUR, `westeurope`, service `Azure Container Apps`:

```bash
curl -sS "https://prices.azure.com/api/retail/prices?currencyCode='EUR'&\$filter=serviceName%20eq%20'Azure%20Container%20Apps'%20and%20armRegionName%20eq%20'westeurope'"
```

| Meter | Price | Per hour |
|---|---|---|
| `Standard NC T4 v3 GPU Usage` | €0.0001 / GPU-second | **≈ €0.36** |
| `Standard NC A100 v4 GPU Usage` | €0.0006 / GPU-second | ≈ €2.16 |
| `Dedicated GPU Usage` | €5.0422 / hour | €5.04 |

A serverless T4 scaled to zero between runs makes a full §12 eval pass over the nine fixtures cost
cents. The T4 is the right size; the A100 buys headroom this workload has no use for.

*Not verified:* the API returns the GPU-second prices as exactly `0.0001` and `0.0006`, which looks
rounded at the source; anything from €0.00005 to €0.00015 per second would display that way, so the
T4 hour could be anywhere from about €0.18 to €0.54. Nor is it clear whether the GPU-second meter
covers the profile's vCPU and memory or bills them on top. Read the first invoice rather than this
table.

## 5. The shape to deploy

Reasoning, not yet built:

- **One new environment in West Europe** with the T4 workload profile added. The Germany West
  Central environment keeps running; this is additive.
- **Two apps in it.** `doc-ai` on `Consumption` with external ingress, as today. `ollama` on the
  GPU profile with `ingress.external=false` and `minReplicas: 0`.
- **App-level internal ingress is not the environment-level choice** that forced the rebuild on
  2026-10-01 (before PR #15). An app with `external=false` in an externally-reachable, VNet-less environment is
  reachable only from inside that environment. That keeps an unauthenticated Ollama endpoint off
  the public internet — Ollama has no auth of its own, so public ingress on it would be an open
  model endpoint. *Confirm this on first deploy rather than trusting it here.*
- **`OLLAMA_BASE_URL` points at the internal FQDN.** Already an environment variable
  (`src/main/resources/application.yml:99`), so this is configuration, not code. `app.bicep` needs
  `springProfilesActive = 'prod,ollama'` and that one variable.
- **Model weights have to be resident**, baked into the image or on a mounted Azure File share.
  Otherwise every scale-from-zero re-pulls several GB before the first token, which turns a cold
  start into minutes.

## 6. Whether it is worth doing

Not for cost or latency. At this volume both are cents per eval pass (§4), so cost decides
nothing either way, and the hosted call is faster than a cold T4.

The reason is SPEC §7 and the GDPR Art. 9 note in the README: a Krankenstandsbestätigung is health
data, and the hosted provider must not see a real one before data protection has approved it. A
model inside the subscription is the only configuration in which real documents could be processed
at all. That is the argument, and it is a real one.

Against it: `gemma3:4b` scored 1/29, and there is no evidence yet that a 7B vision model does
materially better on German structured extraction. It might land nearer 1/29 than 29/29.

**What would settle it** is the eval, not an opinion. §12 and `eval/` already exist, `OLLAMA_BASE_URL`
already redirects the profile, and €0.36/hour is cheap enough that measuring `qwen2.5vl:7b`
properly is a smaller commitment than continuing to guess about it. Until it clears §12 close to
the hosted result on the same documents, SPEC §8 stands: the local profile is a development path,
not an extraction path.

---

## Appendix — the commands, in order

```bash
az account show --query "{name:name, id:id, state:state}"
az containerapp env list -o table
az containerapp env show -n cae-docai-test -g rg-docai-test \
  --query "{loc:location, profiles:properties.workloadProfiles, vnet:properties.vnetConfiguration}"
for r in germanywestcentral westeurope swedencentral; do
  az containerapp env workload-profile list-supported -l "$r" -o tsv \
    --query "[?contains(properties.category,'GPU')].[name, properties.category, properties.gpus]"
done
az quota list --scope "/subscriptions/<sub-id>/providers/Microsoft.App/locations/westeurope"
az provider show -n Microsoft.Compute --query registrationState -o tsv
az vm list-usage -l westeurope
curl -sS "https://prices.azure.com/api/retail/prices?currencyCode='EUR'&\$filter=serviceName%20eq%20'Azure%20Container%20Apps'%20and%20armRegionName%20eq%20'westeurope'"
```

And locally, for the §2.1 comparison:

```bash
nvidia-smi --format=csv --query-gpu=\
name,memory.total,memory.free,power.max_limit,clocks.max.sm,clocks.max.memory,compute_cap
```

All read-only. Nothing in this document changed the subscription, and no GPU has been deployed.
