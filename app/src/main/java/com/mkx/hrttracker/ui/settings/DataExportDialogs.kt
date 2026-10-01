package com.mkx.hrttracker.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mkx.hrttracker.R
import com.mkx.hrttracker.data.export.DataExportSummary
import com.mkx.hrttracker.data.export.JsonExportFormat
import com.mkx.hrttracker.ui.components.HazeAlertDialog

/** Whether the chosen JSON format will be written to a file or copied. */
internal enum class DataExportJsonDialogMode {
    SAVE,
    COPY,
}

/**
 * Picks which JSON shape to export or copy.
 *
 * The Oyama shape cannot carry everything Featherline holds, so the dialog states
 * the cost **before** the user commits: how many records that format would leave
 * out, and the one-sentence rule behind it. An export that quietly drops half a
 * history is worse than one that refuses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DataExportJsonDialog(
    mode: DataExportJsonDialogMode,
    selectedFormat: JsonExportFormat,
    onSelectFormat: (JsonExportFormat) -> Unit,
    summary: DataExportSummary?,
    isBusy: Boolean,
    onDismissRequest: () -> Unit,
    onConfirm: () -> Unit,
) {
    HazeAlertDialog(
        onDismissRequest = { if (!isBusy) onDismissRequest() },
        title = { Text(text = stringResource(R.string.settings_export_json_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                JsonFormatOption(
                    title = stringResource(R.string.settings_export_json_format_oyama_title),
                    supporting = stringResource(R.string.settings_export_json_format_oyama_supporting),
                    selected = selectedFormat == JsonExportFormat.OYAMA,
                    enabled = !isBusy,
                    onSelect = { onSelectFormat(JsonExportFormat.OYAMA) },
                )
                OyamaOmissionNotice(summary = summary)
                JsonFormatOption(
                    title = stringResource(R.string.settings_export_json_format_native_title),
                    supporting = stringResource(R.string.settings_export_json_format_native_supporting),
                    selected = selectedFormat == JsonExportFormat.NATIVE,
                    enabled = !isBusy,
                    onSelect = { onSelectFormat(JsonExportFormat.NATIVE) },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { if (!isBusy) onDismissRequest() }) {
                Text(text = stringResource(R.string.cancel))
            }
        },
        confirmButton = {
            TextButton(onClick = { if (!isBusy) onConfirm() }) {
                Text(
                    text = stringResource(
                        if (mode == DataExportJsonDialogMode.SAVE) {
                            R.string.settings_export_json_action_save
                        } else {
                            R.string.settings_export_json_action_copy
                        }
                    )
                )
            }
        },
    )
}

/**
 * How many records the Oyama shape would drop, indented under the option that
 * causes it. Renders nothing until the counts have loaded, so the dialog never
 * shows a stale or invented number.
 */
@Composable
private fun OyamaOmissionNotice(summary: DataExportSummary?) {
    if (summary == null) {
        return
    }
    val message = if (summary.hasOmissions) {
        stringResource(
            R.string.settings_export_json_lossy_warning,
            summary.omittedDoseCount,
            summary.omittedLabCount,
        )
    } else {
        stringResource(R.string.settings_export_json_lossy_warning_none)
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(start = 40.dp, end = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun JsonFormatOption(
    title: String,
    supporting: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // The whole row is the hit target, so the button itself takes no clicks.
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
