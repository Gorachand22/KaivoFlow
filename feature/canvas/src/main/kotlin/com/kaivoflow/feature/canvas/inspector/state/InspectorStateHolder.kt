package com.kaivoflow.feature.canvas.inspector.state

import com.kaivoflow.core.model.ParamValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Everything outside the pure state: storage, evaluation, persistence, test runs. Implemented by the app layer. */
interface InspectorGateway {
    /** Loads what the panel needs; throws when it cannot. */
    suspend fun loadData(request: DataRequest): DataSummary
    /** Pins the shown output and returns the pin, or throws. */
    suspend fun pinCurrent(request: DataRequest): PinInfo
    suspend fun unpin()
    fun runTest(request: TestRequest): Flow<InspectorTestEvent>
    /** The sources a Test Step could take its input from right now, and the sample text for a manual input. */
    suspend fun loadInputCatalog(): InputCatalogInfo = InputCatalogInfo()
    suspend fun evaluatePreview(text: String, itemIndex: Int): PreviewState
    /** Hands a parameter change to the owner of the Flow document. */
    fun persistParam(key: String, value: ParamValue?)
}

/**
 * Runs [InspectorReducer] and executes its effects. Loads are latest-wins per panel, search and
 * preview evaluation are debounced, and a late result of an older load is dropped by the reducer
 * through the load token. Effects meant for the UI (messages, row creation) are on [uiEffects].
 */
class InspectorStateHolder(
    initial: InspectorState,
    private val gateway: InspectorGateway,
    private val scope: CoroutineScope,
    private val reducer: InspectorReducer = InspectorReducer(),
    private val previewDebounceMs: Long = 150,
) {
    private val lock = Any()
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<InspectorState> = _state.asStateFlow()

    private val _ui = MutableSharedFlow<InspectorEffect>(extraBufferCapacity = 32)
    val uiEffects: SharedFlow<InspectorEffect> = _ui.asSharedFlow()

    private val jobs = HashMap<String, Job>()

    fun dispatch(intent: InspectorIntent) {
        val effects = synchronized(lock) {
            val r = reducer.reduce(_state.value, intent)
            _state.value = r.state
            r.effects
        }
        effects.forEach(::run)
    }

    fun close() = synchronized(jobs) { jobs.values.forEach { it.cancel() }; jobs.clear() }

    private fun launchLatest(key: String, block: suspend () -> Unit) {
        synchronized(jobs) {
            jobs[key]?.cancel()
            jobs[key] = scope.launch { block() }
        }
    }

    private fun run(effect: InspectorEffect) {
        when (effect) {
            is InspectorEffect.LoadData -> launchLatest("load:${effect.request.side}") {
                try {
                    dispatch(InspectorIntent.DataLoaded(effect.request.side, effect.token, gateway.loadData(effect.request)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    dispatch(InspectorIntent.DataFailed(effect.request.side, effect.token, e.message ?: "Could not load data"))
                }
            }
            is InspectorEffect.ScheduleSearchCommit -> launchLatest("search:${effect.side}") {
                delay(effect.delayMs)
                dispatch(InspectorIntent.SearchCommitted(effect.side, effect.text))
            }
            is InspectorEffect.EvaluatePreview -> launchLatest("preview:${effect.key}") {
                delay(previewDebounceMs)
                val p = try {
                    gateway.evaluatePreview(effect.text, effect.itemIndex)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    PreviewState.Error(e.message ?: "Preview failed")
                }
                dispatch(InspectorIntent.PreviewComputed(effect.key, p))
            }
            is InspectorEffect.PersistParam -> gateway.persistParam(effect.key, effect.value)
            is InspectorEffect.RunTest -> launchLatest("test") {
                gateway.runTest(effect.request)
                    .catch { dispatch(InspectorIntent.TestEventReceived(InspectorTestEvent.Failed(it.message ?: "Test failed"))) }
                    .collect { dispatch(InspectorIntent.TestEventReceived(it)) }
            }
            InspectorEffect.LoadInputCatalog -> launchLatest("catalog") {
                try {
                    dispatch(InspectorIntent.InputCatalogLoaded(gateway.loadInputCatalog()))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    // The catalog only offers choices; without it the pick stays automatic.
                }
            }
            InspectorEffect.CancelTest -> synchronized(jobs) { jobs.remove("test")?.cancel() }
            is InspectorEffect.PinCurrent -> launchLatest("pin") {
                try {
                    dispatch(InspectorIntent.PinChanged(gateway.pinCurrent(effect.request)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _ui.tryEmit(InspectorEffect.Notify(InspectorMessage.PinFailed))
                }
            }
            InspectorEffect.Unpin -> launchLatest("pin") {
                try {
                    gateway.unpin()
                    dispatch(InspectorIntent.PinChanged(null))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _ui.tryEmit(InspectorEffect.Notify(InspectorMessage.PinFailed))
                }
            }
            is InspectorEffect.Notify, is InspectorEffect.CreateRowFromDrop -> _ui.tryEmit(effect)
        }
    }
}
