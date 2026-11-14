# KaivoFlow — Agent Rules & Design System Guidelines

> **Project Principle:** KaivoFlow is a sovereign, serverless visual workflow automation platform for Android. Everything that decides *what happens* is pure Kotlin and unit-tested. Platform code is a thin adapter.

---

## 1. Design System & Tokens
* **Theme:** Velvet Obsidian (Dark slate/obsidian base `#0B0F19`) with Champagne Gold (`#D4AF37` / `#E5C158`) and Jewel Accents.
* **Touch Targets:** Minimum 48x48 dp touch target on all interactive elements.
* **Dynamic Type:** All text sizes must use `sp` units and respect system font scaling (test at 1.3x and 2.0x font scale without text truncation or clipping).
* **System Insets:** Handle `WindowInsets` for status bars, navigation bars, and IME (keyboard). Use `Scaffold` with `contentPadding` properly applied to scrolling containers.
* **Jewel Tone Categories:**
  * Sparks (Triggers): Amber / Gold (`#F59E0B`)
  * Logic: Amethyst / Purple (`#8B5CF6`)
  * Transform: Emerald / Cyan (`#10B981`)
  * AI: Sapphire / Electric Indigo (`#3B82F6`)
  * Device: Ruby / Rose (`#F43F5E`)
  * SaaS: Topaz / Teal (`#06B6D4`)

---

## 2. Canvas Performance Contract
* **World Coordinates:** Step positions `(x, y)` live strictly in Canvas (World) coordinates.
* **Single Transform Layer:** Pan and zoom are rendered on ONE `graphicsLayer` applying `translationX/Y` and `scaleX/Y`. Never recompose cards during pan or zoom.
* **Drag Math:** When dragging a node or port, convert screen delta to world delta: `worldDelta = screenDelta / zoomScale`.
* **Culling:** Only compose and render cards that intersect the current viewport + 120 dp buffer.

---

## 3. Engine & Node Execution Contract
* **Immutability:** `Flow`, `StepNode`, `StreamEdge`, and `FlowData` are immutable data classes.
* **Data Model:** Data travels as `List<FlowData>` through numbered ports.
* **Trigger Seeding:** Triggers emit real payloads so downstream steps receive items and execute.
* **Pure Logic Tests:** Put business logic and node executions in pure Kotlin modules (`:core:engine`, `:core:nodes`, `:core:model`, `:core:expressions`) with JVM unit tests.
