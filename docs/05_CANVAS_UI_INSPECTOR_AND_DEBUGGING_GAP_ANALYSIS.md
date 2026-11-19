# 05. Canvas UI, Inspector (NDV) & Debugging — Gap Analysis & Missing Features

> **Document Type:** Sovereign Architecture & Feature Gap Audit  
> **Language:** Hinglish (Developer Action Guide)  
> **Objective:** Reference repo (`packages/frontend/editor-ui/src/features/ndv`, `execution`) ke mukable hamare KaivoFlow (`:feature:canvas`, `:feature:flows`) mein kya UI & inspection capabilities missing hain aur kal hume kya implement karna hai.

---

## 1. Summary: Abhi Hamare Paas Kya Hai vs Reference System

| UI & Debugging Feature | Reference System (`editor-ui`) | KaivoFlow Aaj Ka Status | Gap Severity |
|---|---|---|---|
| **Node Details View (NDV)** | 3-Column / Tab layout: Input Data (Table/Schema/JSON) \| Parameter Settings \| Output Data | Single bottom sheet inspector (parameters list + basic step data) | 🔴 High |
| **Drag-to-Map Parameter Binding** | Input panel ke kisi bhi field (e.g. `orderId`) par click/drag karke parameter input me expression drop karna | Manually keyboard se `{{ $step("X").item... }}` type karna padta hai | 🔴 High |
| **Real-Time Expression Preview Modal** | Modal jisme user formula type karta hai aur niche real-time evaluated result dikhta hai | Simple text field; live preview nahi milta | 🟡 Medium |
| **Pin Data Button on Inspector** | Input/Output panel par 1-click Pin icon to freeze test payload | Missing | 🔴 High |
| **Execution History Timeline** | Per-execution view: Canvas nodes green/red highlight hote hain with exact run duration (e.g. `12ms`, `350ms`) | Executions screen list hai, lekin canvas replay timeline missing hai | 🟡 Medium |
| **Canvas Mini-Map & Auto-Layout** | Mini-map viewer + "Clean up canvas" auto-align algorithm (Dagre/ELK graph layout) | Manual dragging; auto-arrange layout algorithm missing | 🟢 Low |

---

## 2. Deep Dive: Kya Features Missing Hain (Detail Wise)

### A. The 3-Pane Node Inspector (Table, Schema, JSON)
- **Reference System:**
  - Jab user kisi bhi node par click karta hai, ek clean modal khulta hai:
    - **Left Pane (Input):** Is node ko pichle step se kya mila?
      - `Table View`: Excel-style clean columns.
      - `JSON View`: Formatted syntax-highlighted tree.
      - `Schema View`: Keys aur unki data types (`string`, `number`, `boolean`).
    - **Middle Pane (Parameters):** Node ki configuration fields.
    - **Right Pane (Output):** Jab user "Test Step" dabata hai, yahan live result aata hai with run status & time.
- **KaivoFlow Mein Missing:**
  - KaivoFlow Android app hai (mobile screen constraint).
  - Hum 3-pane side-by-side desktop view nahi kar sakte, lekin hum **Top Tabbed Inspector** kar sakte hain:
    - Tab 1: `Input Data` (Table / JSON switcher)
    - Tab 2: `Configure` (Parameters)
    - Tab 3: `Output Result` (Live test execution)

### B. Drag / Tap-to-Map Expressions
- **Reference System:**
  - User ko syntax yaad rakhne ki zaroorat nahi hoti.
  - Left pane me `customer.name` dikhta hai $\rightarrow$ user uspar tap karta hai $\rightarrow$ parameter box me automatically `{{ $json.customer.name }}` insert ho jata hai!
- **KaivoFlow Mein:**
  - Mobile touch keyboard par curly braces `{{` aur quotes type karna frustrating hota hai.
  - **Solution for KaivoFlow:** Parameter field ke bagal me ek "Insert Field" button hona chahiye jo previous node ke schema keys ka bottom sheet picker open kare!

### C. Live Expression Evaluation Preview
- **Reference System:**
  - Jab user parameter me `{{ $json.price * 1.18 }}` likhta hai:
  - Textbox ke niche ek subtle badge dikhta hai:
    `Result: 118` (green color me)
  - Agar syntax galat ho:
    `Error: price is undefined` (red color me)
- **KaivoFlow Mein:**
  - User ko save karke poora step run karne ke baad hi pata chalta hai ki expression chala ya nahi.

---

## 3. Action Items (Kal Kya Implement Karna Hai)

1. **Tabbed Node Inspector (`:feature:canvas`):**
   - Bottom sheet ko 3 tabs me split karein: `Input` \| `Parameters` \| `Output`.
2. **Schema Key Picker ("Tap-to-Map") (`:feature:canvas`):**
   - Parameter text field ke trailing icon par chip/icon lagayein jo available input fields ki list dikhaye aur 1-tap me expression insert kare.
3. **Live Expression Evaluator Badge (`:feature:canvas`):**
   - Parameter edit karte time debounce ke sath current input item par evaluate karke preview badge dikhayein.
