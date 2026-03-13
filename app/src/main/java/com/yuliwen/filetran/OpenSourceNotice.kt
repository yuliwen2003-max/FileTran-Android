package com.yuliwen.filetran

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceNoticeSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 680.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("开源声明", fontWeight = FontWeight.Bold)
            Text("本应用使用了以下开源项目。分发时请保留对应许可证文本与版权声明。")

            NoticeEntry(
                title = "1) sz3/libcimbar",
                license = "MPL-2.0",
                source = "https://github.com/sz3/libcimbar",
                localLicense = "airgap/src/main/cpp/cfc/libcimbar/LICENSE",
                assetLicense = "assets/licenses/libcimbar-MPL-2.0.txt"
            )
            NoticeEntry(
                title = "2) sz3/cfc",
                license = "MIT",
                source = "https://github.com/sz3/cfc",
                localLicense = "_upstream/cfc-master/LICENSE",
                assetLicense = "assets/licenses/cfc-MIT.txt"
            )
            NoticeEntry(
                title = "3) sz3/cimbar",
                license = "MIT",
                source = "https://github.com/sz3/cimbar",
                assetLicense = "assets/licenses/cimbar-MIT.txt"
            )
            NoticeEntry(
                title = "4) xdsopl/robot36",
                license = "0BSD",
                source = "https://github.com/xdsopl/robot36",
                localLicense = ".tmp_robot36_src/robot36-2/LICENSE",
                assetLicense = "assets/licenses/robot36-0BSD.txt"
            )
            NoticeEntry(
                title = "5) LibreSpeed/speedtest",
                license = "LGPL-3.0-or-later",
                source = "https://github.com/librespeed/speedtest",
                assetLicense = "assets/licenses/librespeed-LGPL-3.0.txt"
            )
            Text("说明: 本应用的 Liber Speed Test 页面使用 LibreSpeed 的测速实现思路与许可证文本。")

            NoticeEntry(
                title = "6) vastsa/FileCodeBox",
                license = "LGPL-3.0",
                source = "https://github.com/vastsa/FileCodeBox",
                assetLicense = "assets/licenses/filecodebox-LGPL-3.0.txt"
            )
            Text("说明: 本应用的文件快递柜(FileCodeBox)实验页参考并适配了 FileCodeBox 的开源方案。")

            NoticeEntry(
                title = "7) KnightWhoSayNi/android-iperf",
                license = "MIT",
                source = "https://github.com/KnightWhoSayNi/android-iperf",
                assetLicense = "assets/licenses/android-iperf-MIT.txt"
            )
            Text("说明: 本应用的 iperf 实验页参考了 android-iperf 的 Android 集成方式。")

            NoticeEntry(
                title = "8) 其他依赖",
                license = "Apache-2.0 / BSD-3-Clause",
                source = "okhttp: https://github.com/square/okhttp | dnsjava: https://github.com/dnsjava/dnsjava"
            )

            Text("重点义务", fontWeight = FontWeight.SemiBold)
            Text("- MIT / 0BSD / BSD: 分发时保留版权与许可证文本。")
            Text("- LGPL-3.0 / LGPL-3.0-or-later: 分发时保留许可证文本，并在需要时提供可替换/可重链接的合规信息。")
            Text("- MPL-2.0: 若修改了 MPL 覆盖文件并分发，需要公开对应修改文件。")

            Text("完整声明见项目根目录 THIRD_PARTY_NOTICES.md。", fontWeight = FontWeight.SemiBold)

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("我已知悉")
            }
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("关闭")
            }
        }
    }
}

@Composable
private fun NoticeEntry(
    title: String,
    license: String,
    source: String,
    localLicense: String? = null,
    assetLicense: String? = null
) {
    Text(title, fontWeight = FontWeight.SemiBold)
    Text("许可证: $license")
    Text("来源: $source")
    localLicense?.let { Text("本地许可证: $it") }
    assetLicense?.let { Text("APK内置: $it") }
}
