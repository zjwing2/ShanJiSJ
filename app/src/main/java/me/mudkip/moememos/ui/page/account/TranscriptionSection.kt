package me.mudkip.moememos.ui.page.account

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mudkip.moememos.R
import me.mudkip.moememos.data.local.TranscriptionPrefs
import me.mudkip.moememos.data.transcription.TranscriptionModelManager
import me.mudkip.moememos.ext.string
import me.mudkip.moememos.viewmodel.TranscriptionSettingsViewModel

/**
 * 本地账号页里的「语音转文字」一节。
 *
 * 三种引擎（关闭 / 端侧离线 / 云端接口）互斥，所以用单选而不是三个独立开关——
 * 独立开关会造出「两个都开」这种没有意义的状态，还得额外解释谁优先。
 *
 * 界面刻意把「音频会不会离开手机」写在选项描述里：这是用户选这条路的
 * 唯一理由，不该藏在文档里。
 */
@Composable
fun TranscriptionSection(
    viewModel: TranscriptionSettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RecordVoiceOver, contentDescription = null)
                Text(
                    R.string.transcription_title.string,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Text(
                R.string.transcription_description.string,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 8.dp)
            )

            Text(
                R.string.transcription_engine_label.string,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 16.dp)
            )

            EngineOption(
                icon = Icons.Outlined.GraphicEq,
                title = R.string.transcription_engine_off.string,
                summary = R.string.transcription_engine_off_summary.string,
                selected = viewModel.engine == TranscriptionPrefs.Engine.OFF,
                onSelect = { viewModel.selectEngine(TranscriptionPrefs.Engine.OFF) },
            )

            EngineOption(
                icon = Icons.Outlined.PhoneAndroid,
                title = R.string.transcription_engine_on_device.string,
                summary = R.string.transcription_engine_on_device_summary.string,
                selected = viewModel.engine == TranscriptionPrefs.Engine.ON_DEVICE,
                onSelect = { viewModel.selectEngine(TranscriptionPrefs.Engine.ON_DEVICE) },
            )

            if (viewModel.engine == TranscriptionPrefs.Engine.ON_DEVICE) {
                OnDeviceModelBlock(viewModel = viewModel, state = modelState)
            }

            EngineOption(
                icon = Icons.Outlined.Cloud,
                title = R.string.transcription_engine_cloud.string,
                summary = R.string.transcription_engine_cloud_summary.string,
                selected = viewModel.engine == TranscriptionPrefs.Engine.CLOUD,
                onSelect = { viewModel.selectEngine(TranscriptionPrefs.Engine.CLOUD) },
            )

            if (viewModel.engine == TranscriptionPrefs.Engine.CLOUD) {
                CloudConfigBlock(viewModel = viewModel)
            }
        }
    }
}

/** 一个引擎选项：单选圆点 + 图标 + 标题 + 一句说明。 */
@Composable
private fun EngineOption(
    icon: ImageVector,
    title: String,
    summary: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(top = 10.dp)
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier
                .padding(top = 12.dp)
                .width(20.dp)
        )
        Column(Modifier.padding(start = 10.dp, top = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * 端侧模型的下载/状态块。
 *
 * 四个状态对应四种该给用户看的东西：没有 → 告诉他多大、点了开始下；
 * 在下 → 进度和取消；好了 → 占用多少、可以删；失败 → 原因和重试。
 */
@Composable
private fun OnDeviceModelBlock(
    viewModel: TranscriptionSettingsViewModel,
    state: TranscriptionModelManager.State,
) {
    val context = LocalContext.current
    val hint = remember(viewModel.modelTotalMb) {
        context.getString(R.string.transcription_model_download_hint, viewModel.modelTotalMb)
    }

    Column(Modifier.padding(start = 52.dp, top = 4.dp, bottom = 8.dp)) {
        when (state) {
            is TranscriptionModelManager.State.Ready -> {
                Text(
                    context.getString(
                        R.string.transcription_model_ready,
                        viewModel.formatBytes(viewModel.usedModelBytes()),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { viewModel.deleteModel() }) {
                    Text(R.string.transcription_model_delete.string)
                }
            }

            is TranscriptionModelManager.State.Downloading -> {
                Text(
                    context.getString(
                        R.string.transcription_model_downloading,
                        state.fileName,
                        (state.fraction * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, end = 12.dp)
                )
                TextButton(onClick = { viewModel.cancelDownload() }) {
                    Text(R.string.transcription_cancel.string)
                }
            }

            is TranscriptionModelManager.State.Failed -> {
                Text(
                    state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp)
                )
                FilledTonalButton(
                    onClick = { viewModel.downloadModel() },
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(
                        context.getString(
                            R.string.transcription_model_download,
                            viewModel.modelTotalMb,
                        )
                    )
                }
            }

            is TranscriptionModelManager.State.NotDownloaded -> {
                Text(
                    R.string.transcription_model_missing.string,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 6.dp, end = 12.dp)
                )
                FilledTonalButton(
                    onClick = { viewModel.downloadModel() },
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        context.getString(
                            R.string.transcription_model_download,
                            viewModel.modelTotalMb,
                        )
                    )
                }
            }
        }
    }
}

/** 云端引擎的配置块。三个字段 + 一句隐私提醒。 */
@Composable
private fun CloudConfigBlock(viewModel: TranscriptionSettingsViewModel) {
    Column(Modifier.padding(start = 52.dp, top = 4.dp, bottom = 8.dp, end = 12.dp)) {
        OutlinedTextField(
            value = viewModel.cloudEndpoint,
            onValueChange = viewModel::onCloudEndpointChange,
            label = { Text(R.string.transcription_cloud_endpoint.string) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
        )
        OutlinedTextField(
            value = viewModel.cloudApiKey,
            onValueChange = viewModel::onCloudApiKeyChange,
            label = { Text(R.string.transcription_cloud_api_key.string) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = viewModel.cloudModel,
            onValueChange = viewModel::onCloudModelChange,
            label = { Text(R.string.transcription_cloud_model_label.string) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            FilledTonalButton(onClick = { viewModel.saveCloudConfig() }) {
                Text(R.string.transcription_cloud_save.string)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            R.string.transcription_cloud_hint.string,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
