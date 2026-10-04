# Azure alternatives to the hosted model

What Azure offers for the work doc-ai does, and which of it would be worth trying. A companion to
`SELF_HOSTED_MODEL_ON_AZURE.md`, which covers only the option of running Ollama on an Azure GPU.
Researched on **2026-10-04** from public documentation and announcements. Nothing here was
deployed or measured, so read every quality claim as a claim, not a result.

> doc-ai is a private learning project: one operator, no other consumer. See the note at the top of
> `doc-ai-specs/docs/SPEC.md`.

**Short answer.** Azure has four realistic options. The cheapest one to try is a GPT vision model in
an Azure OpenAI **EU Data Zone** deployment: one Spring AI profile and one §12 eval run. The most
interesting one is **Content Understanding**, a managed service that does most of what doc-ai
builds. Claude through Azure (Microsoft Foundry) does **not** help, because it does not keep data
in the EU today.

---

## 1. What a replacement has to cover

| doc-ai does | SPEC | Replaceable by an Azure service? |
|---|---|---|
| Classify a page into Krankenstand, Zeitbestaetigung, Kompetenzprofil or UNBEKANNT | §3.2 | yes |
| Extract the fields for each type | §3.3–§3.5 | yes |
| Never extract diagnoses | CLAUDE.md | depends on the option, see §2 and §4 |
| Validate (`NAME_WEICHT_AB`, `manuellePruefung`, SVNR checksum) | §4 | **no**, this stays in doc-ai whatever reads the page |
| REST contract, JWT security | §3, §7 | no, this stays in doc-ai |

So every option below replaces at most the `ai` layer and some of `klassifikation`. The API, the
validation and the security stay.

## 2. The options

### 2.1 Azure OpenAI vision model in an EU Data Zone

Same architecture as today with a different model behind Spring AI's `ChatClient`. A new profile
would sit next to `anthropic` and `ollama`, and the prompts and the §12 eval would carry over
unchanged.

- **Data protection:** Microsoft is the processor, and a Data Zone deployment keeps processing in
  the EU. Of all the options that leave Azure in charge, this is the strongest position for health
  data under GDPR Art. 9. For sensitive data, the abuse-monitoring settings are worth checking too
  (Microsoft can review prompts unless modified abuse monitoring is approved).
- **Effort:** small. Spring AI 1.1 has an Azure OpenAI integration, but it is a dependency SPEC §9
  does not list.
- **Unknown:** how a GPT vision model does on these documents compared with Sonnet's 29/29. The
  eval answers that; nothing else will.

### 2.2 Azure Content Understanding

A managed service for what doc-ai builds by hand: you define a field schema, and it classifies,
splits multi-document files and extracts, returning a confidence score and the location on the
page for each value. Generally available since November 2025 (API `2025-11-01`); Java SDK
generally available since March 2026; an August 2026 update refreshed 1.0 and opened a 2.0 preview.

- **What would change:** most of the `ai` layer goes. doc-ai shrinks to the REST contract, mapping
  the service's result onto the SPEC records, and validation.
- **Data protection:** it uses generative models underneath, so the Art. 9 question becomes which
  model it runs on and in which region. Check that before sending anything real.
- **Diagnoses:** controlled by the schema, so it returns only fields you define. Whether the model
  ever puts a diagnosis into a free-text field is still something to test.
- **Effort:** large. A new SDK, a schema per document type, and a rewrite of the extraction path.
  As a learning exercise, this is the one that shows how much of doc-ai a managed service makes
  unnecessary.

### 2.3 Azure Document Intelligence

The classic, non-LLM route, formerly Form Recognizer: a **custom classification model** for the
document type, and a **custom neural** (or template) **extraction model** per type, trained on
labelled samples.

- **Strengths:** deterministic, billed per page, confidence per field. Some models also ship as
  containers that can run elsewhere. Diagnoses are a non-issue: it only returns fields someone
  labelled.
- **Weakness:** training data. The nine fixtures are synthetic and few, and a free-form document
  like a Kompetenzprofil suits a trained layout model less well than a fixed form like a
  Krankenstandsbestätigung does.
- **Cost:** per 1,000 pages for classification and extraction. Training a template model is free;
  neural training is free for the first 10 hours, then USD 3/hour. *Not verified:* the per-page
  prices, which the pricing page did not show when searched. Check it before comparing costs.

### 2.4 Open models in Microsoft Foundry

Models such as Mistral Document AI or Phi multimodal, on serverless endpoints or managed compute.
A middle ground between the T4 plan in `SELF_HOSTED_MODEL_ON_AZURE.md` and a hosted API: no GPU
quota and no Ollama image to manage, but the region and data terms have to be checked per model.
Reachable through Spring AI if the endpoint is OpenAI-compatible; otherwise it needs a client.

## 3. Why Claude in Foundry is not on the list

Claude is available in Microsoft Foundry, so on the surface it looks like the same model, billed
through Azure. As of this writing it runs on **Anthropic-hosted infrastructure**, as a Global
deployment that in practice routes to the US, with Anthropic acting as an independent processor
rather than Microsoft. No European data zone exists for it. Anthropic lists "Microsoft Foundry in
Europe" as *coming 2026*, without a date.

So it changes billing, not data residency, and does nothing for the Art. 9 argument. Re-check when
the EU offering ships; at that point it becomes the smallest change of all, because the model, the
prompts and the eval baseline would stay the same.

## 4. Side by side

| | Change to doc-ai | Data stays in EU | Diagnoses | Training data needed | Biggest unknown |
|---|---|---|---|---|---|
| Azure OpenAI, EU Data Zone | one profile | yes | prompt-controlled, as today | none | extraction quality |
| Content Understanding | rewrite of `ai` | depends on model and region | schema-controlled | a few examples, optional | data terms, cost |
| Document Intelligence | rewrite of `ai` | yes, regional resource | only labelled fields | labelled samples per type | too few samples |
| Open models in Foundry | one profile, maybe a client | per model | prompt-controlled | none | quality and terms per model |
| Claude in Foundry | config only | **no**, not today | as today | none | when the EU offering ships |

## 5. What to do next

1. **Azure OpenAI in an EU Data Zone, measured with §12.** The smallest step, and it turns "a GPT
   model might do" into a number next to Sonnet's 29/29 and `gemma3:4b`'s 1/29.
2. **Content Understanding as a separate experiment**, if the aim is learning what a managed
   service makes unnecessary rather than shipping.
3. Leave Document Intelligence until there are enough real, labelled documents to train on; there
   are not, and there must not be until data protection has approved processing them.

Both 1 and 2 need dependencies SPEC §9 does not list, so per CLAUDE.md that decision comes first.

## Sources

- [Azure Content Understanding is now generally available](https://devblogs.microsoft.com/foundry/azure-content-understanding-is-now-generally-available/) (Nov 2025)
- [Content Understanding – what's new](https://learn.microsoft.com/en-us/azure/ai-services/content-understanding/whats-new)
- [CU 1.0 GA and CU 2.0 public preview](https://espc.tech/?p=36839) (Aug 2026)
- [Document Intelligence pricing](https://azure.microsoft.com/en-gb/pricing/details/ai-document-intelligence/)
- [InfoQ: Claude in Foundry and Europe](https://infoq.com/news/2026/07/claude-foundry-ga-europe/) (Jul 2026)
- [Microsoft Q&A: timeline for Claude in Foundry on Azure EU infrastructure](https://learn.microsoft.com/en-sg/answers/questions/5867930/timeline-for-claude-in-microsoft-foundry-to-run-on)
- [Microsoft Q&A: Claude via Foundry, transfer chain and sub-processor status](https://learn.microsoft.com/en-us/answers/questions/5956927/data-protection-query-anthropic-claude-via-azure-a)
