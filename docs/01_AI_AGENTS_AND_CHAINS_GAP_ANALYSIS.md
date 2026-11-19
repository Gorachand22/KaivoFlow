# 01. AI Agents, Chains & Tools Architecture — Gap Analysis & Missing Features

> **Document Type:** Sovereign Architecture & Feature Gap Audit  
> **Language:** Hinglish (Developer Action Guide)  
> **Objective:** Reference repo (`@n8n/nodes-langchain`, `@n8n/ai-workflow-builder.ee`) ke mukable hamare KaivoFlow (`:core:ai`, `:core:nodes:ai`, `:feature:flows:ai`) mein kya features missing hain aur kal hume kya implement karna hai.

---

## 1. Summary: Abhi Hamare Paas Kya Hai vs Reference System

| Feature Area | Reference System (`@n8n`) | KaivoFlow Aaj Ka Status | Gap Severity |
|---|---|---|---|
| **AI Node Architecture** | Canvas par full node family: `Agent`, `Basic LLM Chain`, `OpenAI Assistant`, `Information Extractor` | Sirf chat assistant backend (`AiWorkflowAgent`) aur basic `AgentNode` placeholder hai canvas par | 🔴 High |
| **Sub-Node Canvas Wiring** | Dynamic canvas sub-connections: Model, Memory, Tools, Output Parser alag sub-nodes ki tarah Agent se judte hain | Abhi sub-nodes canvas par visually connect nahi hote; config in-memory parameters mein hoti hai | 🔴 High |
| **Tool Calling System** | Sub-workflow as a Tool (`ToolWorkflow`), Code Tool (`ToolCode`), HTTP Tool, MCP Client Tool, Calculator | Tool execution sirf Flow edit tools tak limited hai; workflow execution as tool missing hai | 🔴 High |
| **Multi-Agent Orchestrator** | `AgentTool` (ek Agent dusre Agent ko tool ki tarah call kar sakta hai) | Missing (Agent nesting ya multi-agent orchestration nahi hai) | 🟡 Medium |
| **Persistent Chat Memory** | `WindowBufferMemory`, `PostgresChat`, `RedisChat`, `MongoChat`, `Motorhead`, `Zep` | In-memory + SQLite AiSession, lekin canvas agent node ke liye pluggable memory sub-node nahi hai | 🟡 Medium |
| **Structured Output Parsers** | `StructuredOutputParser` (JSON Schema enforcement), `ItemListOutputParser`, `AutofixingOutputParser` | Basic Regex/JSON extraction; autofixing output parser loop missing hai | 🔴 High |
| **Vector Store & RAG** | Pinecone, Qdrant, ChromaDB, PGVector, In-Memory vector store, Document Loaders, Text Splitters | Local vector database aur document chunking engine missing hai | 🟡 Medium |
| **First-Class MCP Integration** | `McpClient`, `McpClientTool`, `McpRegistryClientTool`, `McpTrigger` | Browser automation configuration card hai, lekin full MCP client protocol runner missing hai | 🟡 Medium |
| **Fallback Models** | Primary model fail hone par automatic fallback secondary model connect hota hai canvas par | Single model selection; fallback model execution chain missing hai | 🟢 Low |
| **Multimodal Inputs** | Auto pass-through for binary images and binary PDFs to vision-capable LLMs | Sirf text prompts; binary image/file buffer pass-through missing hai | 🟡 Medium |

---

## 2. Deep Dive: Kya Features Missing Hain (Detail Wise)

### A. Canvas-Level Sub-Nodes Architecture (Root Node Pattern)
- **Reference System Kaise Karta Hai:**
  - Canvas par `AI Agent` ek Root Node hota hai.
  - Uske left/bottom me multiple specialized input plugs hote hain:
    1. `ai_languageModel` (Required) $\rightarrow$ Chat model sub-node judta hai (OpenAI, Anthropic, Gemini, Ollama, Groq, etc.)
    2. `ai_memory` (Optional) $\rightarrow$ Memory sub-node judta hai (`Window Buffer`, `Redis`, `Session ID` mapped)
    3. `ai_tool` (Optional, Multi-connection) $\rightarrow$ Multiple tools jud sakte hain (Workflow, Code, HTTP, MCP, Calculator)
    4. `ai_outputParser` (Optional) $\rightarrow$ Output parser sub-node judta hai (`Structured JSON`, `Item List`)
- **Hamara KaivoFlow Abhi Kya Karta Hai:**
  - Hamare paas `core/nodes/ai/agents/AgentNode.kt` hai jisme slots defined hain (`AiSlots.kt`), lekin canvas par user in sub-nodes ko visually drop karke wire nahi kar sakta.
  - Ask AI assistant UI (`feature:flows:ai`) alag screen pe chalta hai, aur canvas ka Agent node alag hai.

### B. Sub-Workflow as a Tool (`ToolWorkflow`)
- **Reference System:**
  - AI Agent kisi bhi user-created workflow ko ek Tool ki tarah call kar sakta hai!
  - User specify karta hai: Sub-workflow name, description ("Use this workflow to check stock availability in CRM"), aur input schema parameters.
  - Agent runtime par decide karta hai ki kab sub-workflow call karna hai, arguments pass karta hai, sub-workflow execute hota hai, aur result Agent ko return hota hai.
- **KaivoFlow Mein Missing:**
  - Hamare agent ke paas local Flow ko tool ki tarah execute karne ka koi adapter nahi hai (`RunFlowTool`). Agar user chahe ki "Telegram message aane par Agent CRM workflow run kare", toh agent workflow trigger nahi kar sakta.

### C. Code Tool (`ToolCode`)
- **Reference System:**
  - Agent code likhkar sandboxed runtime mein execute kar sakta hai (`JavaScript` ya `Python`).
  - Agar agent ko dynamic calculation, text transformation ya JSON restructure karna ho, toh woh Code Tool execute karta hai.
- **KaivoFlow Mein Missing:**
  - Hamare paas `code.javascript` node canvas par hai, lekin Agent usse tool execution loop ke dauran dynamically invoke nahi kar sakta.

### D. Auto-Fixing Output Parser (`OutputParserAutofixing`)
- **Reference System:**
  - Agar user chahe ki Agent se strict JSON format mile:
    ```json
    { "leadScore": 85, "sentiment": "positive", "recommendedAction": "call_immediately" }
    ```
  - Agar LLM malformed JSON ya missing fields return karta hai, toh `OutputParserAutofixing` error message aur invalid output ko wapas LLM ko bhejta hai:
    *"Your output threw error: Expected field 'leadScore'. Fix the JSON and reply only with valid JSON."*
  - Yeh automatic retry karta hai bina workflow crash kiye.
- **KaivoFlow Mein Missing:**
  - Hamare paas `OutputParser.kt` mein basic JSON parser hai, lekin validation failure par self-healing re-prompting loop nahi hai.

### E. Multi-Agent Orchestrator (`AgentTool`)
- **Reference System:**
  - Ek Master Agent (Orchestrator) do ya teen Specialist Agents ko call kar sakta hai:
    - Specialist 1: *Database Researcher Agent*
    - Specialist 2: *Email Drafting Agent*
    - Master Agent decides kisko kab call karna hai.
- **KaivoFlow Mein Missing:**
  - Abhi single-agent execution hai. Multi-agent chaining architecture missing hai.

### F. Force Tool Call on First Iteration
- **Reference System:**
  - Chote local models (7B, 14B Qwen/Mistral on Ollama) tool calling skip karke direct answer de dete hain.
  - Reference system mein option hota hai: `forceToolCallOnFirstIteration = true`, jo OpenAI compatibility API par `tool_choice: "required"` set karta hai first turn ke liye.
- **KaivoFlow Mein Missing:**
  - Hamare provider implementation mein `tool_choice` strictly force karne ka option missing hai.

---

## 3. Action Items (Kal Kya Implement Karna Hai)

1. **`FlowAsToolAdapter` Banayein (`:core:ai`):**
   - Ek dynamic tool wrapper jo kisi bhi saved KaivoFlow `Flow` ko input parameters ke sath execute kare aur output JSON return kare.
2. **`SelfHealingOutputParser` Banayein (`:core:ai`):**
   - Output validation error par automatic retry loop with error context prompt.
3. **`ForceToolChoice` Config Add Karein (`:core:ai:Providers`):**
   - Provider payload mein first iteration ke liye tool calling force karne ka parameter.
4. **Canvas Sub-Node Socket Visuals (`:feature:canvas`):**
   - Canvas par Agent node ke niche 4 sub-plugs render karna (Model, Memory, Tool, Output Parser).
