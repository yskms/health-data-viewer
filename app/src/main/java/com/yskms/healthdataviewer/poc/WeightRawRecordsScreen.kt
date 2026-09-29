package com.yskms.healthdataviewer.poc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.records.WeightRecord
import com.yskms.healthdataviewer.R
import com.yskms.healthdataviewer.healthconnect.DataOriginNameResolver
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.WeightRecordsResult
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// WBS 2.1（PoC 1）専用の生データ一覧画面。WBS 6.2/6.3で正式なDetail画面に置き換えられる前提のため、
// 画面回転時（Activity再生成）に一覧を保持せず再取得する簡略化を許容している
// （Weightは件数が少なく再取得コストが低いため。大量データを扱うデータ型の状態保持はPoC 3で検討）。
@Composable
fun WeightRawRecordsScreen(
    healthConnectManager: HealthConnectManager,
    historyPermissionGranted: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var retryKey by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<WeightRecordsResult?>(null) }

    LaunchedEffect(retryKey) {
        result = null
        result = healthConnectManager.readAllWeightRecords(historyPermissionGranted)
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(id = R.string.poc_weight_back))
        }
        Text(text = stringResource(id = R.string.poc_weight_title), style = MaterialTheme.typography.titleLarge)

        when (val currentResult = result) {
            null -> {
                CircularProgressIndicator()
                Text(text = stringResource(id = R.string.poc_weight_loading))
            }
            WeightRecordsResult.Failure -> {
                Text(text = stringResource(id = R.string.poc_weight_error))
                Button(onClick = { retryKey++ }) {
                    Text(text = stringResource(id = R.string.poc_weight_retry))
                }
            }
            is WeightRecordsResult.Success -> {
                WeightRecordsList(records = currentResult.records, historyLimited = currentResult.historyLimited)
            }
        }
    }
}

@Composable
private fun WeightRecordsList(records: List<WeightRecord>, historyLimited: Boolean) {
    val context = LocalContext.current
    // Locale.getDefault()はComposeの再コンポジションを発火しない（NonObservableLocale）ため、
    // 言語切替（要件§13）に追従できるLocalLocaleを使う。
    val locale = LocalLocale.current.platformLocale

    if (records.isEmpty()) {
        Text(text = stringResource(id = R.string.poc_weight_empty))
        return
    }

    // 同じpackageNameを行ごとに何度も解決しないよう、一覧に含まれる分だけ先にまとめて解決する。
    val appNames =
        remember(records) {
            records.map { it.metadata.dataOrigin.packageName }.distinct()
                .associateWith { packageName -> DataOriginNameResolver.resolve(context, packageName) }
        }

    Text(text = pluralStringResource(id = R.plurals.poc_weight_record_count, count = records.size, records.size))
    if (historyLimited) {
        Text(text = stringResource(id = R.string.poc_weight_history_limited_notice))
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(records, key = { it.metadata.id }) { record ->
            val zone = record.zoneOffset ?: ZoneId.systemDefault()
            val formattedDateTime =
                DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                    .withLocale(locale)
                    .format(record.time.atZone(zone))
            // Raw画面は重複も含めて見せることが目的（D-007）のため、わずかな値の違いが丸めで
            // 同じに見えないよう、小数1桁ではなく2桁で表示する（PoC 2.2で同日複数レコードの扱いを検討する際、
            // 値の違いを確認できるようにするため）。
            val formattedWeight = String.format(locale, "%.2f kg", record.weight.inKilograms)
            val sourceName = appNames[record.metadata.dataOrigin.packageName].orEmpty()

            Column {
                Text(text = "$formattedDateTime  $formattedWeight")
                Text(text = sourceName, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
