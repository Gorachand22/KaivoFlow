# 04. Node Taxonomy & Core Flow Control Nodes — Gap Analysis & Missing Features

> **Document Type:** Sovereign Architecture & Feature Gap Audit  
> **Language:** Hinglish (Developer Action Guide)  
> **Objective:** Reference repo (`nodes-base/nodes`) ke mukable hamare KaivoFlow (`:core:nodes`, `:core:nodes:catalog`) ke node catalog mein kya missing hai aur kal kya implement karna hai.

---

## 1. Summary: Abhi Hamare Paas Kya Hai vs Reference System

| Node / Feature | Reference System (`nodes-base`) | KaivoFlow Aaj Ka Status | Gap Severity |
|---|---|---|---|
| **Merge Node Modes** | 5 distinct modes: `combineByFields` (Join by key), `append`, `combineByPosition` (Zip), `chooseBranch`, `combineBySql` | Basic 2-way fan-in join; key matching / outer join missing | 🔴 High |
| **HTTP Request Depth** | Automated pagination (Next page URL / page count), binary download, OAuth2, SSL client certs | Basic OkHttp GET/POST; automatic pagination & binary file handling missing | 🔴 High |
| **Switch Node** | Multi-branch routing (4+ outputs based on value equality, regex, number comparison) | Simple binary If (True/False); multi-output Switch missing | 🟡 Medium |
| **Code Node Runtime** | Dual engine: JavaScript (isolated V8) + Python (Pyodide / Python task runner) | JavaScript (QuickJS/Rhino) basic support; Python runtime missing | 🟡 Medium |
| **Form Trigger / Node** | Workflow starts from or pauses for a human web form (text, dropdown, file upload) | Missing | 🟡 Medium |
| **Sticky Note** | Visual canvas sticky notes for workflow documentation & team annotations | Missing | 🟢 Low |
| **Device Android Nodes** | N/A (Server-first tool) | KaivoFlow ka USP! `device.battery`, `device.notify`, `device.file` already present | 🌟 KaivoFlow Ahead |

---

## 2. Deep Dive: Kya Features Missing Hain (Detail Wise)

### A. Merge Node Modes (The Multi-Stream Joining Power)
- **Reference System:**
  - Jab do parallel streams (e.g. Branch A: CRM Data, Branch B: Stripe Payment Data) ko milana ho:
  - User choose karta hai:
    1. **`combineByFields`**: SQL JOIN ki tarah dono ko `customerId` par join karta hai (`keepMatches`, `includeNonMatches`).
    2. **`append`**: Dono branches ke list of items ko ek lambi list me jod deta hai.
    3. **`combineByPosition`**: 1st item of A with 1st item of B (Zip operation).
    4. **`chooseBranch`**: Dynamic selection—agar Branch A me data hai toh Branch B ko drop kar do.
- **KaivoFlow Mein Missing:**
  - Hamara current `Merge` implementation sirf streams ko wait karke collect karta hai, key-based joining (`fieldsToMatch`) execute nahi kar pata.

### B. HTTP Request Pagination Support
- **Reference System:**
  - Real-world APIs (GitHub, Stripe, Shopify, REST APIs) data pages me deti hain (e.g. 50 items per page).
  - Reference HTTP Request node me **Pagination** setting hoti hai:
    - Mode: *Response contains next page URL* (e.g. `$response.body.next_page`)
    - Mode: *Page number increment* (e.g. `?page={{$page}}`)
    - Termination: *Stop when response is empty*
  - Node internally loop chala kar saare 500 items ek hi step me fetch karke single array of items emit karta hai!
- **KaivoFlow Mein Missing:**
  - KaivoFlow me user ko manually loop aur counter nodes lagane padte hain pagination ke liye.

### C. Switch Node (Multi-Branching)
- **Reference System:**
  - `Switch` node ke N number of outputs hote hain:
    - Output 0: *Priority == High*
    - Output 1: *Priority == Medium*
    - Output 2: *Priority == Low*
    - Output 3: *Fallback / Default*
- **KaivoFlow Mein:**
  - Abhi sirf 2 outputs hote hain (`true` aur `false`). Agar 4 conditions ho, toh user ko 3 consecutive `If` nodes cascade karne padte hain jo canvas ko messy bana deta hai.

---

## 3. Action Items (Kal Kya Implement Karna Hai)

1. **`MergeStep` Upgrade (`:core:nodes`):**
   - Implement `combineByFields` (Join by matching key) and `append` modes.
2. **`SwitchStep` Node (`:core:nodes`):**
   - Multi-output branching (0..N) with condition list.
3. **`HttpRequest` Pagination Engine (`:core:network`):**
   - Auto-pagination fetcher inside HTTP step executor.
