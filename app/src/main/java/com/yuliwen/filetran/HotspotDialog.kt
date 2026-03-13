package com.yuliwen.filetran

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HotspotDialog(
    onDismiss: () -> Unit,
    onConfirm: (HotspotConfig) -> Unit,
    preferences: HotspotPreferences
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var ssid by remember { mutableStateOf(HotspotManager.generateRandomSSID()) }
    var password by remember { mutableStateOf(HotspotManager.generateRandomPassword()) }
    var selectedBand by remember { mutableStateOf(WifiBand.BAND_2GHZ) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var nearbyPermissionGranted by remember { mutableStateOf(true) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        nearbyPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result[Manifest.permission.NEARBY_WIFI_DEVICES] == true
        } else {
            result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        }
    }

    LaunchedEffect(Unit) {
        val saved = preferences.getHotspotConfig()
        if (saved != null) {
            ssid = saved.ssid
            password = saved.password
            selectedBand = saved.band
        } else {
            preferences.saveHotspotConfig(ssid, password, selectedBand)
        }

        nearbyPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("热点二维码", fontSize = 22.sp, fontWeight = FontWeight.Bold)

                Text("SSID: $ssid")
                Text("密码: $password")

                Text("频段", fontWeight = FontWeight.Medium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedBand == WifiBand.BAND_2GHZ,
                        onClick = {},
                        label = { Text("2.4GHz") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedBand == WifiBand.BAND_5GHZ,
                        onClick = {},
                        label = { Text("5GHz") },
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedButton(
                    onClick = {
                        val cfg = preferences.getHotspotConfig()
                        if (cfg != null) {
                            ssid = cfg.ssid
                            password = cfg.password
                            selectedBand = cfg.band
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("刷新默认配置")
                }

                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val content = HotspotManager.generateWifiQRCode(ssid, password)
                            qrBitmap = withContext(Dispatchers.Default) {
                                QRCodeGenerator.generateQRCode(content, 512)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("预览 WiFi 二维码")
                }

                qrBitmap?.let { bitmap ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "wifi_qr",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    Text(
                        text = HotspotManager.generateWifiQRCode(ssid, password),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!nearbyPermissionGranted) {
                    Divider()
                    Text("需要授予热点相关权限", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES))
                            } else {
                                permissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("授予权限")
                    }
                }

                Divider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("取消")
                    }
                    Button(
                        onClick = {
                            if (ssid.isBlank() || password.length < 8) return@Button
                            preferences.saveHotspotConfig(ssid, password, selectedBand)
                            onConfirm(HotspotConfig(ssid, password, selectedBand))
                        },
                        modifier = Modifier.weight(1f),
                        enabled = ssid.isNotBlank() && password.length >= 8
                    ) {
                        Text("一键开启热点")
                    }
                }

                Text(
                    text = "将优先尝试应用内开启热点，并自动读取系统实际热点名称/密码生成二维码；失败时自动打开系统热点页。",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
