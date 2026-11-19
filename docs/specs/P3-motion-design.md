# P3 Motion Design: "Velvet in motion"

Status: implemented in core/design (Motion*.kt, Haptics.kt) and applied to canvas, Flow list, Publish sheet and Reliability Center.
Identity: Velvet Obsidian surfaces, Champagne Gold light. Motion is the gold: it appears where something is alive, and is absent elsewhere.

## 1. What the reference teaches (behaviour only)
n8n's canvas communicates a Run with: a rotating border on the running node (1.5 s), a slower one while waiting (4.5 s), a green border and an item count on the edge after success, a red border on error, and pinned data shown as a distinct state. Save, test and publish are communicated by toasts and button states. Lessons kept: state is readable on the node itself, the edge carries the item count, waiting is slower than running. KaivoFlow's version is original: a travelling gold current on Streams, an expanding gold ring on the running Step, a settle on success, a shake with haptic on error.

## 2. Audit of the previous motion
- Golden Current never lit: the view model fills `RunUiState.activeStreamIds`, but nothing copied it onto `StreamEdgeUiModel.isActive`. Fixed by `deriveLiveStreams` (render/RunVisuals.kt).
- The orb phase was read in composition, so the whole layer recomposed every frame, and the dot grid repainted with it. Now two Canvases; only orbs repaint.
- Draw lambdas allocated Brush, Stroke, Path points and Color per frame. Now cached in `remember`; orbs use PathMeasure.
- Card entrance delay was `55 ms * index` with no cap (1,000 Steps = 55 s) and replayed on every scroll-in. Now capped and once per id.
- Every selected card ran an infinite glow. Now only a running card loops.
- Nothing animated on Flow list, Publish sheet or Reliability; badges swapped abruptly.

## 3. Principles
1. Motion explains state change; it never decorates a static screen (ambient loops are the exception and are Full-only).
2. Gold is the only moving light. Success is a settle, error is a shake; neither flashes.
3. Arrivals decelerate, exits accelerate and are shorter than arrivals.
4. Everything that can be pure is pure; Compose only renders decisions made in MotionLogic.kt.
5. Cheap first: layer transforms, cached brushes, one clock per effect family.

## 4. Tokens (KaivoMotionTokens, MotionTokenValues)
- Durations: instant 90, quick 160, standard 260, emphasized 420, slow 700 ms. Stagger 55 ms per item, total cap 360 ms.
- Easings: silk (FastOutSlowIn), flowing (0.22,0.61,0.36,1), settle (0.2,0,0,1), exit (0.4,0,1,1).
- Springs: press (damping 0.62, stiffness 800), settle (0.5, MediumLow), ring (0.85, Low).
- Scalars: press scale 0.97, settle peak 1.04, entrance rise 12 dp, shake 7 dp.
- Glow: stacked low-alpha gold strokes or one cached radial gradient drawn with alpha, never blur. Rest 0, press 0.35, running 0.55.
- Loops: current period 1400 ms, breathing 2400 ms, shimmer 1300 ms, running ring 1500 ms.
- Elevation: no drop shadows on AMOLED; depth is hairline plus glow.

## 5. Signature moments
| Moment | Treatment | Where |
|---|---|---|
| Run starting | Run button gold sweep, one tick haptic, HUD slides up | CanvasScreen |
| Step running | Expanding gold ring (two when ambient allowed), breathing glow, border to gold | JewelStepCard |
| Success settle | 1.04 overshoot spring, border to success green, badge crossfade | JewelStepCard |
| Error shake | 7 dp shake, border to error, REJECT haptic (API 30+, else long press) | JewelStepCard, RunHud, PublishSheet |
| Data current | 1 to 3 gold orbs travel each Stream feeding the running Step; others dim | StreamCanvasLayer |
| Item-count chip | Pops on a finished Stream, number ticks, "12 items" | StreamCountChip |
| Publish confirmation | Check draws itself, gold sweep, CONFIRM haptic, phase crossfade | PublishSheet |
| Health ring fill | Spring sweep with gold gradient, counter ticks | ReliabilityCenterScreen |
| Sheet enter/exit | Material sheet spring; content phases crossfade and height eases | PublishSheet |
| List entrance | Fade and rise, staggered, once per id | FlowListScreen |
| Press feedback | 0.97 spring scale plus gold hairline glow | kaivoPressable, GoldButton, GhostButton |
| Number tickers | AnimatedCounter, TalkBack reads the final value | RunHud, chip, Reliability |
| Skeleton shimmer | One shared gold-tinted band | SkeletonRows |
| Empty states | Staggered entrance, slow breathing orb | EmptyFlows, CanvasAura |

## 6. Performance budget
- No allocation in draw lambdas: Stroke, Brush, Path, PathMeasure live in `remember` or `drawWithCache`.
- Transforms and alpha only through `graphicsLayer {}`; animated State is read inside layer or draw lambdas, never in composition (exception: AnimatedCounter, which recomposes one Text per integer change).
- Simultaneous infinite animations: at most `MotionPolicy.maxInfiniteAnimations` (High 4, Balanced 3, Low 2, Subtle 1, Off 0). Streams animate at most 24 / 12 / 6 (High / Balanced / Low).
- One clock per family: one phase for all orbs, one for all shimmers. The orb clock exists only while a Stream is flowing.
- Pause: Compose's frame clock stops with the Activity in background; idle screens own no infinite transition.
- Quality tier (`MotionDecisions.qualityTier`): Low when low-RAM, memory class 128 MB or less, or API below 28; Balanced at 192 MB or less; battery saver drops High to Balanced. Low halves stagger step, orbs 3 to 1, drops trails and all ambient loops.
- Target: no dropped frames on a 4 GB mid-range phone with 24 live Streams.

## 7. Accessibility
- `MotionPolicy` resolves: system ANIMATOR_DURATION_SCALE 0 forces Off; battery saver lowers Full to Subtle; the owner's setting (Full, Calm, Off) applies otherwise.
- Off: durations 0, springs snap, loops stop, shakes skipped. State is still shown statically (static orb mid-Stream, static ring, colour change, press glow without scale).
- Calm (Subtle): 0.6x durations, run current kept with one orb, no ambient loops, no shimmer.
- No flashing: no element changes luminance faster than 3 times per second; sweeps are soft bands under 0.4 alpha.
- TalkBack unaffected: counters expose the final value, decorative layers carry no semantics, Step descriptions already announce running, finished and failed.
- Haptics go through View.performHapticFeedback, so the system touch-feedback setting applies.

## 8. Files
core/design: MotionLogic.kt (pure), Motion.kt (tokens, policy locals, prefs), MotionModifiers.kt, MotionComponents.kt, Haptics.kt, KaivoTheme.kt (provides policy). Tests: core/design/src/test/.../MotionLogicTest.kt.
Setting: `MotionPrefs` (SharedPreferences "kaivo.motion"), `MotionSettingsSection()` composable for the Settings screen.
