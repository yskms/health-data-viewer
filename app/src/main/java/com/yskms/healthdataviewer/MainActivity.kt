package com.yskms.healthdataviewer

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yskms.healthdataviewer.healthconnect.HealthConnectAvailability
import com.yskms.healthdataviewer.healthconnect.HealthConnectManager
import com.yskms.healthdataviewer.healthconnect.HealthConnectPermissions
import com.yskms.healthdataviewer.ui.theme.HealthDataViewerTheme

// AppCompatDelegate.setApplicationLocales()（アプリ内言語切替、要件§21）は
// AppCompatActivityを前提とする（D-022）。
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val healthConnectManager = HealthConnectManager(applicationContext)
        setContent {
            HealthDataViewerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HealthConnectStatusScreen(
                        healthConnectManager = healthConnectManager,
                        modifier = Modifier.padding(innerPadding),
                    )
                }
            }
        }
    }
}

@Composable
fun HealthConnectStatusScreen(
    healthConnectManager: HealthConnectManager,
    modifier: Modifier = Modifier,
) {
    val availability = remember { healthConnectManager.availability }
    var hasPermissions by remember { mutableStateOf<Boolean?>(null) }

    val requestPermissions =
        rememberLauncherForActivityResult(
            contract = healthConnectManager.createPermissionRequestContract(),
        ) { granted -> hasPermissions = granted.containsAll(HealthConnectPermissions.WEIGHT) }

    LaunchedEffect(availability) {
        if (availability == HealthConnectAvailability.INSTALLED) {
            hasPermissions = healthConnectManager.hasAllPermissions(HealthConnectPermissions.WEIGHT)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(id = R.string.app_name))

        when (availability) {
            HealthConnectAvailability.INSTALLED -> {
                Text(text = stringResource(id = R.string.health_connect_available))
                val permissionTextRes =
                    when (hasPermissions) {
                        true -> R.string.health_connect_permission_granted
                        false -> R.string.health_connect_permission_not_granted
                        null -> R.string.health_connect_checking
                    }
                Text(text = stringResource(id = permissionTextRes))
                Button(onClick = { requestPermissions.launch(HealthConnectPermissions.WEIGHT) }) {
                    Text(text = stringResource(id = R.string.health_connect_request_permission))
                }
            }
            HealthConnectAvailability.UPDATE_REQUIRED ->
                Text(text = stringResource(id = R.string.health_connect_update_required))
            HealthConnectAvailability.NOT_INSTALLED ->
                Text(text = stringResource(id = R.string.health_connect_not_installed))
        }
    }
}
