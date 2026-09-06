# Architectural, Epistemic, and Safety Engineering for Sovereign On-Device Mobile Language Models

The deployment of generative language models directly onto mobile system-on-chips (SoCs) marks a structural divergence from centralized artificial intelligence architectures. While cloud-hosted large language models (LLMs) rely on hyperscale data centers, expansive parameter counts, and server-side safety layers, edge-native computing shifts the execution runtime into an isolated local sandbox. This transition transforms the software design space: it eliminates recurring cloud inference costs, guarantees complete operational continuity under physical air-gaps, and mathematically prevents remote telemetry harvesting.

Yet, executing small language models (SLMs) with one to four billion parameters under the strict physical confines of mobile consumer devices exposes severe engineering tensions. These models exhibit an epistemic deficit, demonstrating shallow parametric recall on specialized technical, historical, and narrative topics. Furthermore, executing models in unmonitored offline environments eliminates centralized content filtering, creating dangerous dual-use capabilities where survival guidance blurs into weaponization hazards.

Overcoming these vulnerabilities requires an integrated engineering approach spanning hybrid retrieval architectures, dynamic parameter-efficient weight adaptation, representation-level safety circuit breaking, and kernel-level memory orchestration.

## User Demographics, Motivations, and Behavioral Archetypes

The demographic base seeking on-device intelligence differs fundamentally from the general consumer profile interacting with web-based artificial intelligence. Large-scale empirical evaluations of user interactions with centralized conversational agents reveal a pervasive privacy paradox: while the majority of users express generalized discomfort regarding surveillance and commercial data scraping, their operational behavior remains largely indiscriminate, frequently leaking sensitive identifiers over unencrypted network boundaries.

In contrast, users who actively seek local-first execution environments exhibit deliberate, non-negotiable preferences for data sovereignty. Research tracking mobile agent interaction trajectories confirms that privacy-conscious individuals consistently choose high-friction paths — such as manually partitioning data stores, auditing background telemetry hooks, and rejecting cloud-based offloading — in order to preserve total data isolation.

The operational intentions driving the adoption of local mobile models coalesce into three distinct behavioral archetypes: data sovereignty maximalists, austere environment operators, and uncensored domain specialists.

**Data sovereignty maximalists** are predominantly professionals handling highly confidential information, such as legal counsel reviewing sensitive privileged discoveries, clinicians analyzing unredacted patient medical charts, or corporate executives conducting localized strategic synthesis. For these users, centralized language models represent systemic legal liabilities, as prompts sent to third-party endpoints risk corporate exposure, commercial training-set ingestion, and third-party subpoena vulnerability. They demand sandboxed environments where raw textual inputs and derived vector representations remain entirely within physical device memory.

**Austere environment operators** encompass wilderness explorers, maritime navigators, disaster response personnel, and military or defense field operators. These users require immediate, reliable computational synthesis in environments characterized by complete electromagnetic silence, intermittent cellular coverage, or infrastructure blackouts. Their applications focus on offline triage: diagnosing engine and mechanical failures, executing emergency first aid, identifying botanical hazards, and synthesizing survival manuals without network uplinks. For this group, high-speed streaming generation and low time-to-first-token are vital operational requirements.

**Uncensored domain specialists and creative practitioners** represent a demographic seeking freedom from corporate alignment guardrails. Centralized commercial models enforce aggressive, blunt moderation filters that routinely generate false positives when prompted with historical warfare, complex creative writing, medical diagnostics, or specialized niche topics. These users turn to edge software such as PocketPal AI and Offline AI Studio to interact with unaligned or customized models, building custom personas and executing long-context roleplay without cloud-enforced session termination or arbitrary topic refusals.

| User Archetype | Primary Operational Domain | Hardware & Latency Targets | Dominant Threat Model | Typical Toolchains & Tasks |
| --- | --- | --- | --- | --- |
| Data Sovereignty / Privacy Maximalist | Enterprise IP analysis, privileged legal review, health journaling, local financial modeling. | Zero telemetry leakage, fully auditable local databases, ≤ 2.0 s time-to-first-token. | Remote packet sniffing, provider corpus scraping, subpoena exposure. | On-device parsing of unredacted records, confidential document analysis, offline summarization. |
| Austere Environment / Off-Grid Operator | Wilderness navigation, maritime transit, tactical operations, disaster management. | Total air-gap resilience, extreme battery optimization, rapid cross-domain index switching. | Terrestrial infrastructure collapse, satellite RTT latency, dropped cellular uplinks. | Emergency first aid synthesis, water filtration blueprints, mechanical engine repair. |
| Uncensored / Domain Specialist | Unrestricted fictional writing, pop-culture worldbuilding, fringe academic research. | Zero platform-level content policing, sustained throughput ≥ 20 tokens/s. | Over-refusal, platform censorship false positives, remote session termination. | Interactive agent execution, fictional drafting, obscure historical and pop-culture retrieval. |

Meeting the expectations of these diverse user cohorts requires an application design that diverges sharply from traditional cloud wrappers. Users demand granular control over their runtime environments, including inspectable local storage, transparent memory consumption meters, and modular knowledge expansion paths that do not compromise the device's thermal state or battery reserves.

## Bridging the Epistemic Capacity Deficit in Small Language Models

Deploying language models within consumer mobile memory budgets restricts base architectures to small parameter tiers, generally between 1B and 4B parameters. While architectural techniques like knowledge distillation and groupwise quantization preserve general linguistic fluency and core reasoning capabilities, small parameter volumes suffer from an epistemic capacity deficit. The parameter space is insufficient to compress the long tail of human knowledge.

When queried on highly specific pop-culture properties — such as the narrative timelines or scientific concepts in the manga *Dr. Stone* — or on specialized technical subjects like muon tomography in archaeological excavation, base SLMs exhibit high hallucination rates. In empirical tests, small models often fabricate non-existent mechanisms, such as claiming muon tomography requires injecting particles into living patients via needles. These failures occur because rare, domain-specific tokens are aggressively pruned or poorly separated during pre-training and subsequent low-bit quantization.

### Local Hybrid Retrieval-Augmented Generation

Resolving this factual deficit requires decoupling reasoning capability from factual retention. Instead of expanding the model's parameter footprint, edge applications rely on an embedded, local-first Retrieval-Augmented Generation (RAG) architecture. Under this pattern, long-tail data is externalized into an indexed database embedded directly inside the application sandbox.

Building an embedded vector engine requires resolving edge-specific computational bottlenecks. High-dimensional vector searches frequently cause memory fragmentation, battery drain, and user interface stuttering if unmanaged. Modern embedded frameworks resolve these issues through distinct indexing and quantization strategies.

Extensions like `sqlite-vec` embed vector similarity operations directly into SQLite virtual tables, allowing relational data and embeddings to coexist within a single file. However, `sqlite-vec` predominantly relies on brute-force flat scans, which introduces latency challenges as databases scale beyond tens of thousands of chunks.

To overcome these scaling limits, next-generation embedded kernels such as MonaVec and Zvec introduce specialized quantization and memory-mapped retrieval techniques. MonaVec implements a training-free, data-oblivious quantization core utilizing the Randomized Hadamard Transform (RHDH). The transform projects arbitrary, non-uniform input embeddings toward a standard Gaussian distribution $\mathcal{N}(0, 1)$, enabling direct 4-bit quantization using fixed Lloyd-Max tables without requiring corpus-wide optimization passes.

This achieves an eightfold reduction in vector storage while preserving relative distances. When handling Euclidean distance ($L_2$), MonaVec avoids per-dimension whitening, which distorts the geometric space into Mahalanobis metrics, and instead executes global scalar standardization:

$$x_{\text{std}} = \frac{x - \mu_{\text{global}}}{\sigma_{\text{global}}}$$

This approach uniformly scales all dimensions and preserves true ordinal neighbor rankings.

Relying entirely on dense vector representations introduces severe edge vulnerabilities. Lightweight on-device embedding models, such as quantized MiniLM or BGE variants running via ONNX Runtime Mobile, lack the dimensional capacity to accurately separate technical terms, serial codes, or niche proper nouns.

To ensure precision, mobile RAG pipelines implement hybrid retrieval that queries an exact lexical index in parallel with the vector space. The relational layer utilizes SQLite's native FTS5 full-text engine with BM25 ranking to retrieve exact keyword hits, while the dense engine retrieves conceptual semantic associations.

The resulting ranked candidates are dynamically merged using Reciprocal Rank Fusion (RRF), expressed as:

$$RRF(r_d, r_s) = \frac{1}{k + r_d} + \frac{1}{k + r_s}$$

where $r_d$ and $r_s$ denote the dense and sparse ordinal ranks, and $k$ represents a smoothing factor (empirically set to 60). This hybrid list can be filtered via a lightweight cross-encoder reranker before the top candidate chunks are formatted and injected into the generative model's active context window.

### Dynamic Low-Rank Adaptation Swapping

While hybrid RAG successfully resolves factual inaccuracies, it is ineffective at altering the model's structural style, tonal characteristics, or procedural formatting without consuming precious context window space. To allow on-the-fly personalization, mobile architectures integrate dynamic Low-Rank Adaptation (LoRA) swapping.

LoRA parameterizes model weight adjustments by decomposing weight updates into low-rank matrices:

$$W = W_0 + \Delta W = W_0 + B \cdot A$$

where $W_0 \in \mathbb{R}^{d \times k}$ represents the frozen base model weights, and $B \in \mathbb{R}^{d \times r}$ and $A \in \mathbb{R}^{r \times k}$ are low-rank adapters with rank $r \ll \min(d, k)$. While server-side deployments often merge adapter weights directly into the base model to avoid runtime branching, mobile architectures retain the quantized base weights ($W_0$) as a static, read-only memory-mapped file.

Individual task adapters — ranging from 10 MB to 60 MB — are stored on local flash storage. Using runtime engines such as llama.cpp or edge-serving frameworks like EdgeLoRA, the system dynamically loads, applies, or unloads adapters from a memory-mapped cache based on user selection or conversational classification.

This allows an application to instantly morph a single underlying base model (such as Qwen 2.5 3B) between a conversational roleplay assistant, an off-grid mechanical diagnostic engine, and a precise language translator in milliseconds, avoiding the latency and memory strain of reloading base weights.

| Epistemic Strategy | Memory Footprint (RAM) | Storage Footprint (Flash) | Query Latency Overhead | Context Overhead | Implementation Surface |
| --- | --- | --- | --- | --- | --- |
| Hybrid Local RAG (FTS5 + sqlite-vec) | 10 MB – 50 MB | 50 MB – 500 MB | 15 ms – 120 ms | High (512 – 2048 tokens) | SQLite virtual tables via Cgo/FFI bindings. |
| Dynamic LoRA Swapping (EdgeLoRA) | 30 MB – 100 MB per active adapter | 15 MB – 60 MB per adapter file | 0.5 ms – 5 ms per switch | Zero tokens consumed | Native inference runtime (llama.cpp, ExecuTorch). |
| Base Weight Hot-Swapping | 1.2 GB – 2.5 GB (replaces model) | 1.0 GB – 3.0 GB per base model | 1.5 s – 6.0 s file reload | Zero tokens consumed | Application lifecycle state manager. |
| Monolithic Model Fine-Tuning | 1.2 GB – 2.5 GB (static allocation) | 1.2 GB – 2.5 GB per specialized build | Zero additional latency | Zero tokens consumed | Pre-compiled static GGUF weight files. |

## Offline Safety Architecture and Dual-Use Hazard Mitigation

Deploying unmonitored language models in disconnected environments introduces severe dual-use risks. In a cloud paradigm, safety moderation is handled by continuous telemetry tracking, external policy checks, IP-based throttling, and automated human-in-the-loop review. In an air-gapped mobile deployment, the execution environment is completely isolated.

The application must enforce content safety natively, preventing the generation of dangerous blueprints — such as improvised kinetic explosives, chemical precursors, or biological toxins — without relying on external network validation.

### The Survival Versus Weaponization Paradox

The operational boundary between constructive off-grid survival guidance and hazardous weaponization is mathematically narrow. An offline emergency assistant must possess knowledge of field chemistry, such as purifying water using coagulants, synthesizing basic agricultural fertilizers, and creating disinfectants or topical antiseptics.

However, the precursor chemicals, stoichiometric ratios, and thermal steps involved in producing domestic nitrate fertilizers or industrial cleaning solutions closely mirror the production processes for improvised explosive devices (IEDs).

Similarly, clinical triage guidance concerning emergency pharmacology shares technical foundations with the synthesis of lethal chemical substances. If an application enforces blunt safety filters, it risks triggering false-positive refusals during critical survival emergencies. Conversely, insufficient safety controls expose the application to malicious exploitation. Resolving this conflict demands a defense-in-depth architecture spanning multiple execution tiers.

### Multi-Tiered On-Device Defense Implementation

The on-device safety architecture distributes defensive layers across deterministic pre-inference checks, specialized edge classification models, dynamic representation steering, and permanent weight-level unlearning.

The first line of defense operates prior to neural inference using a deterministic lexical scanning engine. Incoming text prompts pass through an optimized Aho-Corasick pattern-matching automaton paired with regular expression scanners. This engine screens for specific hazardous markers, including known military explosive formulations, biological weapon terminology, and chemical precursor supply lists.

Because the Aho-Corasick automaton operates in deterministic linear time relative to input length, it processes text in under one millisecond with negligible memory usage. Queries that match verified hazardous synthesis requests trigger an immediate application-level abort, preventing malicious prompts from waking the heavier transformer runtime.

Prompts that pass the deterministic filter are evaluated by an on-device safety classification model. The reference model for this tier is Meta's Llama Guard 3-1B-INT4, optimized for mobile CPUs via PyTorch's ExecuTorch runtime and an XNNPACK backend delegate. The model was compressed from a standard Llama 3.2 1B base through structured pruning: decoder blocks were reduced from 16 to 12, the multi-layer perceptron (MLP) width was trimmed from 8192 to 6400, and the output vocabulary layer was pruned from 128,000 tokens down to the 20 tokens required to output safety determinations.

Quantization-aware training (QAT) reduces the model to 4-bit weights and 8-bit dynamic activations, yielding a self-contained runtime footprint of approximately 440 MB.

Executing on standard ARM mobile CPUs, Llama Guard 3-1B-INT4 achieves processing speeds exceeding 30 tokens per second and a time-to-first-token under 2.5 seconds, matching the classification accuracy of cloud moderation APIs across the 13 hazard categories defined by the MLCommons taxonomy.

While external guard models inspect surface-level prompts, they remain susceptible to complex jailbreaks, such as adversarial suffixes, cipher encoding, or hypothetical roleplay framing. To protect against these attacks, the generative model integrates representation-level circuit breakers trained via Low-Rank Representation Adaptation (LoRRA).

Traditional safety fine-tuning merely trains a model to predict conversational refusal tokens (e.g., "I cannot assist with that request"), leaving the underlying dangerous conceptual representations accessible within internal layers. Circuit breaking instead identifies the hidden activation vectors that process dangerous concepts. During safety training, the model processes paired datasets: a retain set containing safe, functional knowledge ($D_r$), and a circuit-breaker set containing actionable hazardous requests ($D_s$).

The optimization objective applies a retention loss to maintain standard capabilities on safe queries, coupled with a rerouting loss that forces activations associated with dangerous concepts into orthogonal, incoherent vector directions.

During inference, if an adversarial prompt circumvents input guardrails and attempts to steer the model toward generating weapon blueprints, the internal hidden state $h(x)$ traverses toward the prohibited conceptual subspace. The circuit breaker actively interrupts this trajectory within the network's forward pass:

$$h'(x) = h(x) - \beta \cdot \langle h(x), v_{\text{harmful}} \rangle v_{\text{harmful}}$$

This operation projects out the harmful latent component $v_{\text{harmful}}$, instantly disrupting the generative sequence and rendering subsequent token generation incoherent. Because circuit breakers operate directly on internal layer activations, this defense is attack-agnostic, adds zero auxiliary memory overhead, and maintains standard model utility across benchmark tasks.

The final line of defense applies weight-level machine unlearning to permanently remove hazardous capabilities prior to binary distribution. Using benchmarks such as the Weapons of Mass Destruction Proxy (WMDP) — which tests for hazardous capabilities across chemical, biological, and cyber domains — engineers deploy Representation Misdirection for Unlearning (RMU).

RMU alters the underlying parameter weights, eliminating the latent factual knowledge required to synthesize dangerous chemical agents or construct biological weapons. Once unlearned, the base model cannot generate actionable weaponization instructions, even if a user bypasses software sandboxes or roots the mobile host device.

| Defensive Tier | Mechanism & Framework | Latency Impact | Memory / Flash Footprint | Adversarial Resilience | Utility Impact |
| --- | --- | --- | --- | --- | --- |
| Deterministic Trie | Aho-Corasick string matching against precursor blacklists. | <1.0 ms | <2 MB (RAM & Disk) | Low; vulnerable to leetspeak, Base64, and ciphers. | None; only fires on exact toxic matches. |
| Auxiliary Guard Model | Llama Guard 3-1B-INT4 via ExecuTorch (XNNPACK). | 15 ms – 150 ms | ~440 MB RAM / Flash | Moderate; catches complex prompt injections. | Low; occasional false-positive refusals. |
| Circuit Breaking (LoRRA) | Hidden state orthogonal rerouting via RepE. | Zero penalty (in-flight forward pass) | Zero additional RAM (weights modified) | High; robust against unseen jailbreak attacks. | Negligible; <0.5% degradation on general benchmarks. |
| Machine Unlearning (RMU) | Weight-level erasure targeting WMDP corpora. | Zero penalty (permanent weight edit) | Zero additional RAM (weights modified) | Very High; parametric knowledge is permanently excised. | Low to Moderate; risks slight regression in organic chemistry. |

## Systems Engineering, Hardware Governors, and Regulatory Governance

Transitioning an on-device language model into a stable mobile product requires operating within the strict execution environments enforced by mobile operating systems. While desktop platforms mitigate memory pressure by spilling excess allocations onto secondary disk swap files, mobile kernels treat memory overruns aggressively. On Apple iOS and Google Android, unmanaged memory expansion leads to immediate, non-catchable process termination by operating system daemons.

### Kernel Memory Watchdogs and Resource Ceilings

Under Apple iOS, physical system memory is shared across the CPU, the GPU, and the Apple Neural Engine via a Unified Memory Architecture (UMA). To guarantee interface responsiveness and preserve background system tasks, the Darwin kernel relies on the `jetsam` watchdog daemon.

Even on modern devices equipped with 8 GB or 12 GB of physical RAM, `jetsam` enforces dynamic per-process memory limits. A foreground application that exceeds this threshold — frequently between 3.0 GB and 4.5 GB — is immediately terminated via a `SIGKILL` signal.

Because `jetsam` operates at the kernel level, it generates no standard language-level exceptions, crashing the app directly back to the springboard without an opportunity to clean up active state. Android enforces similar memory constraints through its low-memory killer daemon (`lmkd`), which evaluates process out-of-memory scores and terminates memory-heavy applications to prevent Application Not Responding (ANR) conditions.

To maintain long-context conversations without exceeding operating system thresholds, mobile runtimes implement strict memory governance techniques.

A major point of optimization is the key-value (KV) attention cache. In standard 16-bit floating-point (FP16) precision, cache memory scales linearly with sequence length:

$$\text{Memory}_{\text{KV}} = 2 \times 2 \times n_{\text{layers}} \times n_{\text{heads}} \times d_{\text{head}} \times L \times \text{bytes\_per\_element}$$

In a 3B parameter model operating over an 8,192-token context window, an uncompressed FP16 cache consumes upwards of 2.4 GB of RAM, rapidly triggering watchdog terminations. Runtimes mitigate this using asymmetric KV-cache quantization, storing attention keys at 8-bit precision (`q8_0`) to preserve positional fidelity while quantizing attention values at 4-bit precision (`q4_0`).

This hybrid format cuts cache memory by more than 60% without noticeable degradation in token perplexity, allowing the context window to expand safely within memory limits.

In addition to cache compression, mobile runtimes enforce memory buffer limits. For example, capping internal Metal buffer pools to static ceilings (such as 20 MB) prevents memory spikes during prompt pre-fill passes.

Furthermore, multimodal processing pipelines must enforce strict mutual exclusion. When executing multi-turn voice conversations, the speech-to-text model (e.g., Whisper or Qwen3-ASR) must be completely unloaded from memory before the generative LLM initializes, ensuring two large neural architectures never occupy the memory bus simultaneously.

### Commercial Store Compliance and Sideloading Ecosystems

Commercial app deployment introduces regulatory friction between platform safety policies and the open-source software ecosystem. Apple's App Store Review Guidelines require generative AI applications to integrate content moderation tools that prevent the output of harmful, illegal, or non-consensual content.

In cloud-backed applications, developers satisfy this policy by pointing to server-side moderation APIs. For local-first software, developers must package native on-device moderation mechanisms — such as the deterministic filters or quantized guard models detailed above — to pass review.

However, apps such as PocketPal AI and Offline AI Studio maintain compliance while offering native integrations with external repositories like the Hugging Face Hub. These applications achieve compliance by shipping with a vetted, safety-aligned default model that satisfies platform safety rules out of the box.

Concurrently, they provide file import interfaces that permit advanced users to sideload arbitrary third-party `.gguf` weight files from local device storage or direct web URLs.

From an architectural and legal standpoint, this mirrors the computing model of web browsers or media players: the application provides a compliant, secure sandbox runtime, while the user assumes responsibility for external content loaded into that runtime. This separation enables developers to build open-source local AI tooling while meeting the safety standards of major app distribution platforms.

| Hardware Platform | Target Parameter Tier | Optimal Quantization Format | Generation Speed | RAM Consumption | Acceleration Architecture |
| --- | --- | --- | --- | --- | --- |
| Apple Silicon (A17 Pro / A18 / M-Series) | 3B – 8B parameters (e.g., Llama 3.2 3B, Qwen 2.5 7B) | Q4_K_M to Q8_0 (weights), Asymmetric INT8/INT4 (KV) | 25 – 45 tokens/s | 2.2 GB – 4.8 GB | Metal Performance Shaders (MPS) & Apple Neural Engine via MLX/CoreML. |
| Qualcomm Snapdragon (8 Gen 3 / 8 Elite) | 1.5B – 4B parameters (e.g., Phi-3 Mini, Gemma 2 2B) | INT4 Groupwise (weights), INT8 dynamic (activations) | 20 – 35 tokens/s | 1.4 GB – 2.8 GB | Qualcomm Hexagon NPU via LiteRT / ExecuTorch XNNPACK. |
| Google Tensor (G4 / G5) | 1B – 3B parameters (e.g., Danube, Llama 3.2 1B) | INT4 / Block Floating Point (Q4_0) | 15 – 30 tokens/s | 1.1 GB – 2.1 GB | EdgeTPU via Google LiteRT (TensorFlow Lite runtime). |
| Legacy / Mid-Tier ARM (6 GB RAM Android) | 1B – 1.5B parameters (e.g., Qwen 2.5 1.5B) | Q3_K_M to Q4_0 (pruned architectures) | 6 – 14 tokens/s | 800 MB – 1.3 GB | Multithreaded ARM NEON CPU vector intrinsics. |

## Architectural Directives for Sovereign Mobile Systems

Building high-performance, safe, on-device language assistants requires an integrated approach that addresses both hardware constraints and safety requirements. The fundamental finding across edge AI research is that treating a mobile model as an isolated, general-purpose oracle is mathematically unsustainable. The limited parameter capacity of mobile architectures cannot encode all long-tail human knowledge while running within strict memory and thermal limits.

A resilient mobile AI architecture separates operational responsibilities across distinct layers:

- General linguistic reasoning and task execution are handled by a compact, quantized base SLM (1B to 3B parameters).
- Fact-based knowledge is externalized into an embedded hybrid retrieval engine that pairs SQLite FTS5 lexical matching with quantized dense vector lookups.
- Behavioral and contextual specialization is provided via modular, dynamic LoRA adapters swapped on demand without base model reloads.
- Operational safety is enforced through a multi-tiered pipeline: fast deterministic pattern matching, a compact on-device guard classifier (e.g., Llama Guard 3-1B-INT4), activation-layer circuit breakers trained via representation engineering, and weight-level unlearning targeting hazardous dual-use knowledge.
- Operating system stability is maintained through asymmetric KV-cache quantization, static buffer allocations, and mutually exclusive execution of multimodal components to avoid triggering kernel memory watchdogs.

By adopting this layered architecture, developers can build edge-native AI systems that provide low-latency performance, protect user privacy, and operate reliably within the hardware limits of modern mobile devices.

## Works Cited

1. PersonaMQA: Context-Aware Persona Inference Engine for Privacy, https://www.techrxiv.org/doi/pdf/10.36227/techrxiv.177015933.33204191
2. Why Your Favorite Databases Are Quietly Winning the Vector War, https://blog.devwithawais.com/why-your-favorite-databases-are-quietly-winning-the-vector-war-543123607ba3
3. pocketpal-ai/README.md at main - GitHub, https://github.com/a-ghorbani/pocketpal-ai/blob/main/README.md
4. Llama Guard 3-1B-INT4 - arXiv, https://arxiv.org/pdf/2411.17713
5. Igor Fedorov | alphaXiv, https://www.alphaxiv.org/@igor-fedorov
6. PocketPal AI — Bringing Small Language Models to Your Cellphone, https://jmlbeaujour.medium.com/pocketpal-ai-bringing-small-language-models-to-your-cellphone-5ae486902f1b
7. Improving Alignment and Robustness with Circuit Breakers - arXiv, https://arxiv.org/pdf/2406.04313
8. The WMDP Benchmark: Measuring and Reducing ... - GitHub, https://github.com/centerforaisafety/wmdp
9. What is the best architecture for integrating local LLM inference and RAG on mobile devices, https://discuss.huggingface.co/t/what-is-the-best-architecture-for-integrating-local-llm-inference-and-rag-on-mobile-devices/174270
10. How Circuit Breakers Improve Alignment and Robustness of LLMs, https://medium.com/@2oliver.ricken/how-circuit-breakers-improve-alignment-and-robustness-of-llms-dc785d56f97a
11. An Efficient Multi-Tenant LLM Serving System on Edge Devices - arXiv, https://arxiv.org/html/2507.01438v1
12. onLM — Offline AI Assistant - App Store - Apple, https://apps.apple.com/us/app/onlm-offline-ai-assistant/id6760297856
13. A Survey of U.S. Users' Privacy Perceptions in LLM Chatbots, https://www.ndss-symposium.org/wp-content/uploads/usec26-5.pdf
14. Text-Based Personas for Simulating User Privacy Decisions - arXiv, https://arxiv.org/html/2603.19791v1
15. Mobile GUI Agent Privacy Personalization with Trajectory Induced, https://arxiv.org/html/2604.11259v1
16. PocketPal AI: A small language modeling chat tool for offline use on, https://aisharenet.com/en/pocketpal-ai/
17. Llama-Guard-3-1B Free Chat Online - skywork.ai, https://skywork.ai/blog/models/llama-guard-3-1b-free-chat-online-skywork-ai/
18. PocketPal AI Is the Easiest Way to Run AI Models Locally ... - TechPP, https://techpp.com/2025/08/20/pocketpal-ai-run-ai-models-locally-on-android-iphone/
19. PocketPal AI Updates: Edit Messages, Regenerate, and UI ... - Reddit, https://www.reddit.com/r/LocalLLaMA/comments/1hbo2nz/pocketpal_ai_updates_edit_messages_regenerate_and/
20. Llama Guard 3 1B - Telnyx, https://telnyx.com/llm-library/llama-guard-3-1b
21. Anomaly detections for GenAI inputs | ai-data-science - Oracle Blogs, https://blogs.oracle.com/ai-and-datascience/anomaly-detections-for-genai-inputs
22. Offline AI Studio - App Store - Apple, https://apps.apple.com/us/app/offline-ai-studio/id6761210483
23. GSoC 2026: Opportunities for the AI projects - #16, https://discourse.joplinapp.org/t/gsoc-2026-opportunities-for-the-ai-projects/49228/16
24. (PDF) Llama Guard 3-1B-INT4: Compact and Efficient Safeguard for Human-AI Conversations, https://www.researchgate.net/publication/386210883_Llama_Guard_3-1B-INT4_Compact_and_Efficient_Safeguard_for_Human-AI_Conversations
25. 1 Introduction - arXiv, https://arxiv.org/html/2606.19458
26. On-device vector databases in 2026 - ObjectBox, https://objectbox.io/262454-2/
27. The Complete Guide to Zvec — Is Alibaba's 'SQLite for Vector DBs', https://note.com/ai_driven/n/n7344e9bfe230?hl=en
28. LoRA & QLoRA Fine-Tuning: Build Custom LLMs on a Single GPU, https://www.meta-intelligence.tech/en/insight-lora-finetuning
29. llama.cpp: C++ Engine for LLM Inference - Emergent Mind, https://www.emergentmind.com/topics/llama-cpp
30. Model Configuration - LocalAI, https://localai.io/docs/advanced/model-configuration/
31. Improving Alignment and Robustness with Circuit Breakers, https://openreview.net/forum?id=IbIB8SBKFV
32. Improving Alignment and Robustness with Circuit Breakers | alphaXiv, https://www.alphaxiv.org/abs/2406.04313v4
33. From Representation Engineering to Circuit Breaking: Toward, https://www.cs.cmu.edu/~csd-phd-blog/2025/representation-engineering/
34. TOFU: A Task of Fictitious Unlearning for LLMs — Case Study, https://medium.com/@jolalf/tofu-a-task-of-fictitious-unlearning-for-llms-case-study-4fa3e72d0bcf
35. WMDP Leaderboard - LLM Stats, https://llm-stats.com/benchmarks/wmdp
36. Chapter 4: Scaling Evals with SLMs - Galileo AI, https://galileo.ai/eval-engineering-book/scaling-evals-with-slms
37. Real-time WebGL VRAM purging & Garbage Collection, https://www.reddit.com/r/GraphicsProgramming/comments/1uzy5oe/realtime_webgl_vram_purging_garbage_collection/
38. Two years ago my wife's photos came out blurry, so I built her an app, https://www.reddit.com/r/iosapps/comments/1vcv1lg/two_years_ago_my_wifes_photos_came_out_blurry_so/
39. AICoven Local is a Swift client that lets you run an AI model - GitHub, https://github.com/lepapillonterrible/aicoven-local-opensource
40. llama-cpp | Skills Marketplace - LobeHub, https://lobehub.com/skills/tdimino-claude-code-minoan-llama-cpp
41. GitHub - craftogrammer/llama.cpp-adaptive-turboquant, https://github.com/craftogrammer/llama.cpp-adaptive-turboquant
42. Running Local AI: Mastering Llama.cpp from Zero to Production, https://atalupadhyay.wordpress.com/2026/04/01/running-local-ai-mastering-llama-cpp-from-zero-to-production/
43. Apple App Store Submission Guide 2026 - Go Tech Solutions, https://gotechsolutions.co/blog/apple-app-store-submission-guide-2026/
