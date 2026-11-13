package com.kaivoflow.feature.flows

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaivoflow.core.data.FlowRepository
import com.kaivoflow.core.data.FlowStructureBundle
import com.kaivoflow.core.nodes.NodeCatalog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** UI-only state; no Flow is saved, executed, or scheduled until the user confirms import. */
data class FlowTransferState(
    val busy: Boolean = false,
    val importPreview: FlowStructureBundle.Preview? = null,
    val exportDraft: ExportDraft? = null,
    val message: String? = null,
)
data class ExportDraft(val name: String, val bytes: ByteArray)

@HiltViewModel
class FlowTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flows: FlowRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(FlowTransferState())
    val state: StateFlow<FlowTransferState> = mutableState.asStateFlow()

    fun prepareExport(id: String) {
        if (state.value.busy) return
        mutableState.value = FlowTransferState(busy = true)
        viewModelScope.launch {
            try {
                val flow = flows.flow(id) ?: error(context.getString(R.string.transfer_missing_flow))
                val bytes = withContext(Dispatchers.Default) { FlowStructureBundle.export(flow) }
                mutableState.value = FlowTransferState(exportDraft = ExportDraft(flow.name, bytes))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }

    fun writeExport(uri: Uri?) {
        val draft = state.value.exportDraft ?: return
        if (uri == null) { dismiss(); return }
        mutableState.value = FlowTransferState(busy = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(draft.bytes) }
                        ?: error(context.getString(R.string.transfer_file_open_error))
                }
                mutableState.value = FlowTransferState(message = context.getString(R.string.transfer_export_success))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }

    fun readImport(uri: Uri?) {
        if (uri == null || state.value.busy) return
        mutableState.value = FlowTransferState(busy = true)
        viewModelScope.launch {
            try {
                val preview = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (output.size() + n > FlowStructureBundle.MAX_BYTES) error(context.getString(R.string.transfer_too_large))
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    } ?: error(context.getString(R.string.transfer_file_open_error))
                    FlowStructureBundle.preview(bytes, NodeCatalog.registry().specs.map { it.id }.toSet())
                }
                mutableState.value = FlowTransferState(importPreview = preview)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }

    fun confirmImport(onSaved: (String) -> Unit) {
        val preview = state.value.importPreview ?: return
        if (state.value.busy) return
        mutableState.value = FlowTransferState(busy = true)
        viewModelScope.launch {
            try {
                flows.save(preview.flow) // The one write happens only after explicit confirmation.
                mutableState.value = FlowTransferState()
                onSaved(preview.flow.id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { showError(e) }
        }
    }

    fun dismiss() { mutableState.value = FlowTransferState() }
    private fun showError(e: Exception) {
        mutableState.value = FlowTransferState(message = e.message ?: context.getString(R.string.transfer_generic_error))
    }
}

/** File pickers are provided by Android; no broad file permission is needed. */
@Composable
fun FlowTransferHost(
    onImported: (String) -> Unit,
    viewModel: FlowTransferViewModel = hiltViewModel(),
    content: @Composable (onImport: () -> Unit, onExport: (String) -> Unit) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { viewModel.readImport(it) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { viewModel.writeExport(it) }
    content(
        { if (!state.busy) importPicker.launch(arrayOf("application/json", "text/plain")) },
        { id -> if (!state.busy) viewModel.prepareExport(id) },
    )

    val defaultFilename = stringResource(R.string.transfer_default_filename)
    val noneText = stringResource(R.string.transfer_none)
    state.exportDraft?.let { draft ->
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.transfer_export_title)) },
            text = { Text(stringResource(R.string.transfer_export_warning, draft.name)) },
            confirmButton = { TextButton(onClick = { exportPicker.launch(defaultFilename) }) { Text(stringResource(R.string.transfer_choose_file)) } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.transfer_cancel)) } },
        )
    }
    state.importPreview?.let { preview ->
        val unknown = preview.unsupportedTypes.take(12).joinToString(", ").ifBlank { noneText }
        val types = preview.flow.steps.map { it.type }.distinct().take(12).joinToString(", ").ifBlank { noneText }
        AlertDialog(
            onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.transfer_import_title)) },
            text = { Text(stringResource(R.string.transfer_import_summary, preview.flow.name, preview.flow.steps.size, preview.flow.streams.size, types, unknown), modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { viewModel.confirmImport(onImported) }) { Text(stringResource(R.string.transfer_import_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.transfer_cancel)) } },
        )
    }
    state.message?.let { message ->
        AlertDialog(onDismissRequest = viewModel::dismiss,
            title = { Text(stringResource(R.string.transfer_result_title)) }, text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismiss) { Text(stringResource(R.string.transfer_ok)) } })
    }
}
