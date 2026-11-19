# Advanced Agentic Architecture & Ask AI Enhancement Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Elevate KaivoFlow's Ask AI assistant to an enterprise-grade agentic architecture featuring intelligent intent triage, an iterative self-healing validation loop, multi-turn conversation compaction, expanded provider presets (Groq, DeepSeek, OpenRouter), and a comprehensive `+` action hub in the chat input bar (Attach files, Connect services, Tool/Browser connectors, and Agent preferences).

**Architecture:** Pure Kotlin sovereign architecture with a multi-stage agent pipeline: a lightweight Intent Triage classifier routes prompts between pure advisory assistance and flow modifications; an iterative self-correction loop in `AssistantTurn` feeds validation errors directly back to the LLM; conversation memory is preserved via architectural compaction rather than hard truncation; and Compose UI surfaces the full-featured action menu on the `+` button.

**Tech Stack:** Kotlin 2.0, Android Jetpack Compose, Coroutines, StateFlow, Room SQLite, Material 3.

---

## Global Constraints

- Sovereign, local-first on Android. No backend server dependencies.
- Zero mention of any external brand name in code, comments, or commit messages.
- Backward-compatible with existing flows, sessions, and credential encryption.
- Maintain pure Kotlin separation in `:core:ai` and thin adapters in `:feature:flows`.

---

## Review Focus

1. **Prompt Triage Accuracy**: Pure knowledge or diagnostic queries must not generate empty or corrupt workflow proposal cards.
2. **Self-Healing Loop**: If the LLM generates a step with missing required params or unconnected ports, the error feedback fed back into the loop must guide it to fix the issue within its turn limit.
3. **Memory Compaction Stability**: Summarized older turns must keep crucial requirements without exceeding context limits or crashing on empty sessions.
4. **Action Menu Usability**: The `+` menu options (Attach files, Connect services, Connect tools/browser, Preferences) must smoothly link to their respective sheets and flows without keyboard clipping.
5. **Cold-Start Persistence**: Selected models and provider configs across all new provider presets must survive app kills and reboots.

---

## Tasks

### Task 1: Intent Triage Engine (`AiTriageClassifier.kt`)

**Files:**
- Create: `core/ai/src/main/kotlin/com/kaivoflow/core/ai/flowedit/AiTriageClassifier.kt`
- Modify: `core/ai/src/main/kotlin/com/kaivoflow/core/ai/flowedit/AssistantTurn.kt`
- Test: `core/ai/src/test/kotlin/com/kaivoflow/core/ai/flowedit/AiTriageClassifierTest.kt`

- [ ] Step 1: Write unit tests in `AiTriageClassifierTest.kt` testing prompt categorization (`KnowledgeQuestion`, `WorkflowMutation`, `DirectGreeting`).
- [ ] Step 2: Implement `AiTriageClassifier.kt` with pattern-matching heuristics and structured intent extraction.
- [ ] Step 3: Integrate triage in `AssistantTurn.kt` so knowledge questions receive tailored explanatory answers without draft mutation churn.
- [ ] Step 4: Run unit tests to verify all cases pass.

---

### Task 2: In-Loop Self-Healing Validation & Auto-Correction

**Files:**
- Modify: `core/ai/src/main/kotlin/com/kaivoflow/core/ai/flowedit/FlowEditTools.kt`
- Modify: `core/ai/src/main/kotlin/com/kaivoflow/core/ai/flowedit/AssistantTurn.kt`
- Modify: `core/ai/src/main/kotlin/com/kaivoflow/core/ai/flowedit/FlowProposal.kt`

- [ ] Step 1: Add a programmatic `validate_draft` tool in `FlowEditTools.kt` that the model can invoke to verify connections and parameters.
- [ ] Step 2: In `AssistantTurn.kt`, if the LLM attempts to conclude with a draft that has validation errors (unconnected steps, missing trigger), inject the validation feedback directly back as a system observation and run an auto-correct iteration (up to 2 retry iterations).
- [ ] Step 3: Verify with unit tests that an initially invalid draft recovers automatically.

---

### Task 3: Multi-Turn Conversation Compaction (`ConversationCompactor.kt`)

**Files:**
- Create: `core/ai/src/main/kotlin/com/kaivoflow/core/ai/ConversationCompactor.kt`
- Modify: `core/ai/src/main/kotlin/com/kaivoflow/core/ai/flowedit/AssistantTurn.kt`
- Test: `core/ai/src/test/kotlin/com/kaivoflow/core/ai/ConversationCompactorTest.kt`

- [ ] Step 1: Write unit tests in `ConversationCompactorTest.kt` for compacting multi-turn conversations into an architectural intent summary.
- [ ] Step 2: Implement `ConversationCompactor.kt` to extract key user constraints (triggers, services mentioned, custom parameters) from older messages.
- [ ] Step 3: Wire into `AssistantTurn.kt` replacing the naive 12-message drop with compact summary injection.
- [ ] Step 4: Run unit tests to verify compaction output.

---

### Task 4: Expanded Provider Presets (Groq, DeepSeek, OpenRouter, Mistral)

**Files:**
- Modify: `feature/flows/src/main/kotlin/com/kaivoflow/feature/flows/ai/AiProvider.kt`
- Modify: `core/ai/src/main/kotlin/com/kaivoflow/core/ai/ProviderEndpoints.kt`
- Modify: `core/model/src/main/kotlin/com/kaivoflow/core/model/CredentialTypeInfo.kt`

- [ ] Step 1: Add enum entries in `AiProvider.kt` for `Groq`, `DeepSeek`, `OpenRouter`, `Mistral` with their respective default base URLs and recommended chat models.
- [ ] Step 2: Update `CredentialTypeInfo.kt` with icon keys, display names, and accent colors for the new providers.
- [ ] Step 3: Update `ProviderEndpoints.kt` to resolve correct endpoint styles and authorization headers.
- [ ] Step 4: Verify provider instantiation in unit tests.

---

### Task 5: Chat Input `+` Action Hub & Sheet

**Files:**
- Create: `feature/flows/src/main/kotlin/com/kaivoflow/feature/flows/ai/AiActionMenuSheet.kt`
- Modify: `feature/flows/src/main/kotlin/com/kaivoflow/feature/flows/ai/AiAssistantScreen.kt`
- Modify: `feature/flows/src/main/kotlin/com/kaivoflow/feature/flows/ai/AiAssistantViewModel.kt`

- [ ] Step 1: Create `AiActionMenuSheet.kt` matching the attached visual design:
  - **Attach files**: allows picking a JSON flow or text specification to attach to context.
  - **Connect services**: triggers opening the Sovereign Credentials management sheet.
  - **Connect browser & Tools (MCP)**: shows extensible tool connectors and integration status.
  - **Preferences**: allows tuning assistant temperature, model selection, and execution bounds.
- [ ] Step 2: Add state management in `AiAssistantViewModel.kt` for action sheet visibility, attached context snippets, and agent preferences.
- [ ] Step 3: Update `AiAssistantScreen.kt` to wire the `+` button to open `AiActionMenuSheet`.
- [ ] Step 4: Build and test APK on connected device `7d922506`.

---

### Task 6: End-to-End Verification on Device

**Files:**
- Live validation on physical device `7d922506`.

- [ ] Step 1: Deploy updated build via ADB.
- [ ] Step 2: Tap `+` button in Ask AI input bar and verify all 4 action items appear with accurate styling.
- [ ] Step 3: Test "Connect services" shortcut: verify it opens credential configuration.
- [ ] Step 4: Test asking a pure question (*"How does a Webhook trigger work?"*): verify clean advisory answer without empty workflow card.
- [ ] Step 5: Test building an automation with intentional complexity: verify self-healing validation executes and produces a verified proposal card.
- [ ] Step 6: Verify all logs are clean and finalize goal.
