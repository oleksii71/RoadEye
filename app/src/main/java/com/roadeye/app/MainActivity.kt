package com.roadeye.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.roadeye.app.camera.RoadEyeOverlayService
import com.roadeye.app.ui.theme.RoadEyeTheme

private const val TAG = "RoadEyeCamera"

/**
 * MainActivity — только про разрешения. Как только оба разрешения
 * (камера + показ поверх других приложений) выданы, Activity сама
 * запускает RoadEyeOverlayService и закрывается (finish()) — экран
 * разрешений не показывается вообще, если разрешения уже были выданы
 * раньше (обычный случай при повторном запуске). Дальше вся видимая
 * часть RoadEye — это плавающее окошко из сервиса, с картинкой и
 * кнопкой выключения.
 */
class MainActivity : ComponentActivity() {

    private var cameraGranted = mutableStateOf(false)
    private var overlayGranted = mutableStateOf(false)

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraGranted.value = granted
        }

    // Разрешение на геолокацию — для ограничения детекции по скорости
    // (< 5 км/ч, экономия заряда). НЕ блокирует запуск сервиса и не
    // показывается на PermissionsScreen — если пользователь откажет,
    // приложение просто теряет эту оптимизацию и продолжает детектировать
    // знаки всегда (fail-open, см. SpeedProvider.kt). Запрашивается один
    // раз, best-effort, параллельно с обязательными разрешениями.
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d(TAG, "ACCESS_FINE_LOCATION ${if (granted) "granted" else "denied"}")
        }

    // SYSTEM_ALERT_WINDOW нельзя запросить обычным диалогом разрешений —
    // только через отдельный экран настроек, результат проверяем сами
    // после возврата (onResume), API не даёт callback с итогом напрямую.
    private val overlaySettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            overlayGranted.value = hasOverlayPermission()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "MAIN ACTIVITY STARTED")

        cameraGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        overlayGranted.value = hasOverlayPermission()

        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        setContent {
            RoadEyeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val camGranted by cameraGranted
                    val ovGranted by overlayGranted

                    // Как только оба разрешения выданы (были выданы
                    // заранее, либо только что) — сразу стартуем сервис
                    // и закрываем эту Activity, экран разрешений
                    // пользователь в этом случае вообще не видит.
                    LaunchedEffect(camGranted, ovGranted) {
                        if (camGranted && ovGranted) {
                            startOverlayService()
                            finish()
                        }
                    }

                    if (!camGranted || !ovGranted) {
                        PermissionsScreen(
                            cameraGranted = camGranted,
                            overlayGranted = ovGranted,
                            onRequestCamera = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            onRequestOverlay = { requestOverlayPermission() }
                        )
                    }
                    // Если оба разрешения уже есть — ничего не рисуем,
                    // LaunchedEffect выше сразу закроет Activity.
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        cameraGranted.value = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        overlayGranted.value = hasOverlayPermission()
    }

    private fun hasOverlayPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlaySettingsLauncher.launch(intent)
    }

    private fun startOverlayService() {
        val intent = Intent(this, RoadEyeOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}


@androidx.compose.runtime.Composable
private fun PermissionsScreen(
    cameraGranted: Boolean,
    overlayGranted: Boolean,
    onRequestCamera: () -> Unit,
    onRequestOverlay: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "RoadEye",
                style = MaterialTheme.typography.headlineMedium
            )

            PermissionRow(
                label = "Доступ к камере",
                granted = cameraGranted,
                onRequest = onRequestCamera
            )

            PermissionRow(
                label = "Показ поверх других приложений",
                granted = overlayGranted,
                onRequest = onRequestOverlay
            )

            Text(
                text = "Выдайте оба разрешения — дальше RoadEye запустится " +
                        "автоматически, этот экран больше не понадобится.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@androidx.compose.runtime.Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = "$label: ${if (granted) "✅ выдано" else "❌ не выдано"}")
        if (!granted) {
            Button(onClick = onRequest) {
                Text("Выдать")
            }
        }
    }
}