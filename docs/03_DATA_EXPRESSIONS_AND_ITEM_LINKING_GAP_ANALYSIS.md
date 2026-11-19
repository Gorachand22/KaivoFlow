# 03. Data Architecture, Expressions & Item Linking — Gap Analysis & Missing Features

> **Document Type:** Sovereign Architecture & Feature Gap Audit  
> **Language:** Hinglish (Developer Action Guide)  
> **Objective:** Reference repo (`workflow/src/workflow-data-proxy.ts`, `paired-item-memo.ts`, `pinData`) ke mukable hamare KaivoFlow (`:core:expressions`, `:core:engine:UniversalExpressions.kt`) mein kya features missing hain aur kal hume kya implement karna hai.

---

## 1. Summary: Abhi Hamare Paas Kya Hai vs Reference System

| Feature Area | Reference System (`workflow`) | KaivoFlow Aaj Ka Status | Gap Severity |
|---|---|---|---|
| **Item Linking (Paired Items)** | `pairedItem` DAG backtracking: Har output item janta hai ki woh upstream node ke kis item se bana hai | Partial/Flat: `$step("X")` item 0 ya whole list deta hai; branching ke baad item tracking missing | 🔴 High |
| **Pinned Data (Mock Canvas Testing)** | User canvas par kisi bhi node ka output JSON "Pin" kar sakta hai; downstream nodes bina upstream run kiye test hote hain | Missing: Har test run ke liye entire upstream execute karna padta hai | 🔴 High |
| **DateTime & Duration Helpers** | `$now`, `$today`, Luxon functions (`.plus({ days: 1 })`, `.format('yyyy-MM-dd')`, `.diff()`) | System epoch timestamp milta hai; human date formatting helpers limited hain | 🟡 Medium |
| **JMESPath Querying** | `$jmespath($json, "[?status == 'active'].id")` for rich JSON transformations in expressions | Basic JSON pointer / dot navigation; complex array filtering syntax missing | 🟡 Medium |
| **Internal Data Tables** | Built-in Sovereign Data Tables (CRUD without external SQLite/Postgres) | Android Room database hai, lekin user-facing customizable Data Tables feature nahi hai | 🟡 Medium |
| **Expression Autocomplete & Scope** | Canvas inspector me parameter input par real-time drag-and-drop & live expression preview with current item | Inspector text fields hain, lekin live value evaluation preview missing hai | 🔴 High |

---

## 2. Deep Dive: Kya Features Missing Hain (Detail Wise)

### A. The Paired Items Problem (Silent Data Corruption Without It)
- **Reference System Ka Killer Feature:**
  - Socho ek Flow me:
    1. Step 1 (Fetch Customers): 10 customers return karta hai.
    2. Step 2 (Filter Active): Sirf 3 active customers bachte hain (Customer index 2, 5, 8).
    3. Step 3 (Enrichment / HTTP): In 3 customers ke orders fetch karta hai.
    4. Step 4 (Send Email): Email body me `{{ $('Fetch Customers').item.json.email }}` likha hai.
  - Reference system me **Step 4 automatically Customer 2, 5, aur 8 ka email match karta hai** kyunki har item me `pairedItem: { item: 2 }` ka pointer hota hai!
- **KaivoFlow Mein Problem:**
  - KaivoFlow me agar filter ke baad 3 items bache, aur downstream Step 1 ko access kare:
    - Ya toh woh `item[0]` dega (jo galat hai!)
    - Ya array out of bounds ya mismatched customer email bhejdega!
  - **Conclusion:** Paired Items tracking ke bina multi-item automated workflows unreliable ho jate hain.

### B. Pinned Data (Canvas Testing Game-Changer)
- **Reference System:**
  - Jab user ek complex workflow build kar raha hota hai (e.g. Webhook $\rightarrow$ Filter $\rightarrow$ AI Agent $\rightarrow$ Database Update):
  - Har baar AI Agent test karne ke liye naya real Webhook bhejna boring aur slow hota hai.
  - User Webhook node par **"Pin Data"** button dabata hai.
  - Ab woh data freeze ho gaya. Jab bhi user Canvas par "Test Step" dabata hai, engine pinned data se test karta hai bina real API call kiye!
- **KaivoFlow Mein Missing:**
  - Hamare paas `StepDataPolicy.kt` aur `SparkSamples.kt` hai, lekin canvas inspector par user-friendly "Pin Data / Unpin Data" toggle button aur persistent pin storage missing hai.

### C. Rich DateTime Manipulation (`$now`, `$today`)
- **Reference System:**
  - Expressions mein:
    - `{{ $now.plus({ hours: 2 }).toISODate() }}`
    - `{{ $today.minus({ days: 7 }) }}`
    - `{{ $now.toFormat('dd/MM/yyyy HH:mm') }}`
- **KaivoFlow Mein:**
  - Abhi Java `System.currentTimeMillis()` milta hai. User ko simple date format ke liye complex JavaScript likhna padta hai.

---

## 3. Action Items (Kal Kya Implement Karna Hai)

1. **`PairedItemTracker` Engine Model (`:core:model` & `:core:engine`):**
   - Har `EngineItem` me `pairedSource: SourceItemRef(nodeId, itemIndex)` add karein.
   - `$step("Node").item` expression resolver ko paired index backtracking sikhayein.
2. **Pinned Data Storage & UI (`:core:data` & `:feature:canvas`):**
   - Flow model me `pinnedData: Map<String, List<EngineItem>>` add karein.
   - Canvas Node Inspector me "Pin Data" toggle button render karein.
3. **Expression DateTime Extensions (`:core:expressions`):**
   - Kotlin `java.time` based `$now` object expose karein with `.plusDays()`, `.format()`, etc.
