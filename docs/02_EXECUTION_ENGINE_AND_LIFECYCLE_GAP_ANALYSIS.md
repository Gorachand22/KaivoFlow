# 02. Execution Engine & Lifecycle — Gap Analysis & Missing Features

> **Document Type:** Sovereign Architecture & Feature Gap Audit  
> **Language:** Hinglish (Developer Action Guide)  
> **Objective:** Reference repo (`workflow`, `core`, `execution-engine`) ke mukable hamare KaivoFlow (`:core:engine`, `:core:work`) ke execution engine mein kya features missing hain aur kal hume kya implement karna hai.

---

## 1. Summary: Abhi Hamare Paas Kya Hai vs Reference System

| Feature Area | Reference System (`packages/workflow`, `core`) | KaivoFlow Aaj Ka Status | Gap Severity |
|---|---|---|---|
| **Looping Architecture** | `SplitInBatches` node (Dual output: `loop` + `done`, cycle detection, execution stack) | Single-pass DAG engine; canvas par backward loop edge allow nahi hota | 🔴 High |
| **Wait Node Lifecycle** | 3 modes: Time interval, Target datetime, Webhook callback resume (sleeps & wakes up) | Basic in-thread `delay` Step; async persistent wait + webhook resume missing | 🔴 High |
| **Sub-Workflow Execution** | `ExecuteWorkflow` node (runs another workflow, passes inputs, waits for return items) | Missing (Flow cannot invoke another Flow) | 🔴 High |
| **Global Error Workflow** | Workflow setting `errorWorkflow`: Crash par automatic error workflow trigger hota hai | Flow runner sirf local error return karta hai; global error handler trigger missing | 🟡 Medium |
| **Continue on Fail** | Per-node setting: agar step fail ho toh workflow ko crash na kare aur error item downstream bheje | Missing (step fail hone par poora Flow execute band kar deta hai) | 🟡 Medium |
| **Retry on Fail** | Per-node setting: `maxTries` (1-5) + `waitBetweenTries` (ms) automatic retry | Missing | 🟡 Medium |
| **Binary Data Streaming** | `binary` property in items: buffers, mime types, file streaming without base64 heap explosion | String/JSON only; binary streams storage engine missing | 🟡 Medium |
| **Execution Cancellation** | Real-time cancellation bus (`execution-cancellation.ts`) to kill running jobs immediately | Coroutine cancellation partially works, but no atomic state persistence on cancel | 🟢 Low |

---

## 2. Deep Dive: Kya Features Missing Hain (Detail Wise)

### A. Loop Over Items (`SplitInBatches`) Engine Support
- **Reference System:**
  - `SplitInBatches` node ke do outputs hote hain:
    - **`loop` output**: Har iteration par `batchSize` (e.g. 1 item) nikal kar aage bhejta hai. Downstream steps kaam karte hain aur unka output wapas `SplitInBatches` ke input me loop-back karta hai.
    - **`done` output**: Jab saare items process ho jate hain, toh engine automatically `done` branch trigger karta hai.
  - Engine mein execution stack aur iteration counter track hota hai taaki infinite loop na bane.
- **KaivoFlow Mein Missing:**
  - Hamara `FlowRunner.kt` ek strict DAG (Directed Acyclic Graph) expect karta hai. Agar koi connection piche loop kare, toh dependency resolution ya cycle detection usse block kar deti hai.
  - Hume engine mein `IterationContext` aur `LoopStack` add karna padega.

### B. Asynchronous Wait & Webhook Callback Resume
- **Reference System:**
  - Jab workflow me `Wait` node aata hai with mode: `resume: 'webhook'`:
    1. Engine current execution state (saare upstream items, run data) database me save karta hai.
    2. Ek unique resume webhook endpoint generate hota hai: `/webhook-waiting/:executionId`.
    3. Workflow execution process terminate/sleep ho jata hai (zero memory/CPU consumption!).
    4. Jab external system (ya human approval) us webhook par POST karta hai, engine state restore karke usi exact node se aage flow continue karta hai.
- **KaivoFlow Mein Missing:**
  - KaivoFlow Android par chalta hai. Agar 2 ghante wait karna ho, toh process background me kill ho sakti hai.
  - Hume Android `WorkManager` ke sath integration chahiye jo execution snapshot ko Room DB me save kare aur scheduled alarm ya incoming intent se revive kare.

### C. Sub-Workflow Execution (`ExecuteWorkflow`)
- **Reference System:**
  - `ExecuteWorkflow` step kisi dusre Flow ki ID leta hai.
  - Parent Flow ka data child Flow ke Trigger me inject hota hai.
  - Child Flow execute hota hai aur uska final node ka output Parent Flow me as output items return hota hai.
- **KaivoFlow Mein Missing:**
  - Hamare paas modular flow execution nahi hai. User ko sab kuch ek hi giant flow me banana padta hai.

### D. Per-Node Resilience (`continueOnFail` & `maxTries`)
- **Reference System:**
  - Har node ke settings menu mein:
    - **Continue on Fail**: Agar HTTP request 500 error throw kare, toh workflow stop nahi hota; item me `error: { message, status }` inject hoke downstream chalta hai.
    - **Retry on Fail**: Exponential backoff ke sath 3 baar automatic retry.
- **KaivoFlow Mein Missing:**
  - Hamare `StepExecutor` me agar koi exception throw ho, toh entire `FlowRunner` fail ho jata hai.

---

## 3. Action Items (Kal Kya Implement Karna Hai)

1. **`LoopContext` in `FlowRunner` (`:core:engine`):**
   - Loop back edge allow karein aur per-item batching state maintain karein.
2. **`ExecuteWorkflowStep` Banayein (`:core:nodes`):**
   - Sub-flow invocation runner with parameter passing.
3. **`NodeExecutionOptions` Add Karein (`:core:model`):**
   - Har Step me `continueOnFail: Boolean`, `retryOnFail: Int`, `retryDelayMs: Long` support.
4. **`ErrorFlowTrigger` Runner (`:core:work`):**
   - Flow failure event bus par trigger listener jo error flow run kare.
