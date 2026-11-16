package com.kaivoflow.feature.canvas.inspector.params

import androidx.compose.runtime.Stable
import com.kaivoflow.core.model.CredentialRef
import com.kaivoflow.core.model.ParamPath

/** One entry of a resource list (spreadsheet, channel, folder...). */
data class ResourceItem(val id: String, val name: String, val url: String? = null)

sealed interface ResourceListState {
    data object Idle : ResourceListState
    data object Loading : ResourceListState
    data object Empty : ResourceListState
    data class Failed(val message: String) : ResourceListState
    /** The Step's credential is not set: offer a button to pick one. */
    data object NeedsCredential : ResourceListState
    data class Ready(val items: List<ResourceItem>) : ResourceListState
}

/** Everything the widgets need from the host besides the schema and values. All defaults are inert so a preview sets only what it shows. */
@Stable
class ParamFormEnv(
    val expr: ExprFieldEnv? = null,
    /** Vault entries without secrets; null while loading. */
    val credentials: List<CredentialRef>? = null,
    val onAddCredential: () -> Unit = {},
    val onNoticeAction: (String) -> Unit = {},
    val optionsFor: (ParamPath) -> OptionsState? = { null },
    val onRetryOptions: (ParamPath) -> Unit = {},
    val resourcesFor: (ParamPath) -> ResourceListState = { ResourceListState.Idle },
    val onLoadResources: (ParamPath, String) -> Unit = { _, _ -> },
    /** Binary property names of the input schema, for the file reference's "From data". */
    val binaryProperties: List<String> = emptyList(),
    val onPickDeviceFile: (ParamPath) -> Unit = {},
    val recentColors: List<String> = emptyList(),
)
