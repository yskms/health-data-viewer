package com.yskms.healthdataviewer.screen.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yskms.healthdataviewer.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 6.2: Custom期間（要件§7.1）の開始日・終了日選択UI。範囲選択専用のコンポーネントには依存せず、
// DatePickerDialogを開始日・終了日それぞれに使う（決定事項7）。選んだ結果start > endになった場合は
// 呼び出し元に渡す前にここで入れ替える。4画面（Weight/Steps/HeartRate/Sleep）で共有する。
private enum class CustomRangeField { START, END }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomRangePicker(range: Pair<LocalDate, LocalDate>?, onRangeChange: (Pair<LocalDate, LocalDate>) -> Unit) {
    val locale = LocalLocale.current.platformLocale
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    var pickerTarget by rememberSaveable { mutableStateOf<CustomRangeField?>(null) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickerTarget = CustomRangeField.START }) {
            Text(text = range?.first?.let(formatter::format) ?: stringResource(id = R.string.detail_custom_not_selected))
        }
        Text(text = "–", style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = { pickerTarget = CustomRangeField.END }) {
            Text(text = range?.second?.let(formatter::format) ?: stringResource(id = R.string.detail_custom_not_selected))
        }
    }

    val target = pickerTarget
    if (target != null) {
        val initialDate = (if (target == CustomRangeField.START) range?.first else range?.second) ?: LocalDate.now()
        val state =
            rememberDatePickerState(
                initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
        DatePickerDialog(
            onDismissRequest = { pickerTarget = null },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        val current = range
                        val updated =
                            when (target) {
                                CustomRangeField.START -> picked to (current?.second ?: picked)
                                CustomRangeField.END -> (current?.first ?: picked) to picked
                            }
                        onRangeChange(if (updated.first > updated.second) updated.second to updated.first else updated)
                    }
                    pickerTarget = null
                }) {
                    Text(text = stringResource(id = R.string.detail_custom_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pickerTarget = null }) {
                    Text(text = stringResource(id = R.string.detail_custom_dialog_dismiss))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
