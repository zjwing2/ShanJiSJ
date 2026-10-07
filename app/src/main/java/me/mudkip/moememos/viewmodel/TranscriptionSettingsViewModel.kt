package me.mudkip.moememos.viewmodel

import android.app.Application
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.mudkip.moememos.data.local.TranscriptionPrefs
import me.mudkip.moememos.data.transcription.SherpaModelCatalog
import me.mudkip.moememos.data.transcription.TranscriptionManager
import me.mudkip.moememos.data.transcription.TranscriptionModelManager
import javax.inject.Inject

/**
 * 设置页「语音转文字」一节的状态与动作。
 *
 * 单独一个 ViewModel 而不是并进 AccountViewModel：转写与账号、与服务器同步
 * 完全正交，塞进去只会让那个类继续膨胀。
 */
@HiltViewModel
class TranscriptionSettingsViewModel @Inject constructor(
    application: Application,
    private val models: TranscriptionModelManager,
    private val transcription: TranscriptionManager,
) : AndroidViewModel(application) {

    private val context: Application get() = getApplication()

    /** 模型下载/就绪状态，由 [TranscriptionModelManager] 持有。 */
    val modelState: StateFlow<TranscriptionModelManager.State> = models.state

    /** 模型总下载量，用于在按钮上写清楚体积。 */
    val modelTotalMb: Int = SherpaModelCatalog.totalBytes.let { (it / (1024 * 1024)).toInt() }

    var engine by mutableStateOf(TranscriptionPrefs.engine(context))
        private set

    var cloudEndpoint by mutableStateOf(TranscriptionPrefs.cloudEndpoint(context))
        private set

    var cloudApiKey by mutableStateOf(TranscriptionPrefs.cloudApiKey(context))
        private set

    var cloudModel by mutableStateOf(TranscriptionPrefs.cloudModel(context))
        private set

    private var downloadJob: Job? = null

    init {
        // App 启动后第一次进设置页时重新对一次盘：模型可能在应用外被删掉了。
        models.refresh()
    }

    fun onCloudEndpointChange(value: String) {
        cloudEndpoint = value
    }

    fun onCloudApiKeyChange(value: String) {
        cloudApiKey = value
    }

    fun onCloudModelChange(value: String) {
        cloudModel = value
    }

    fun selectEngine(target: TranscriptionPrefs.Engine) {
        if (target == engine) return
        engine = target
        TranscriptionPrefs.setEngine(context, target)
        if (target != TranscriptionPrefs.Engine.ON_DEVICE) {
            // 离开端侧就把那 250 MB 内存还回去，不要留着过夜
            viewModelScope.launch { transcription.releaseOnDeviceModel() }
        }
    }

    fun saveCloudConfig() {
        TranscriptionPrefs.setCloudConfig(context, cloudEndpoint, cloudApiKey, cloudModel)
        // 存回来的是 trim 之后的值，界面要跟数据库保持一致
        cloudEndpoint = TranscriptionPrefs.cloudEndpoint(context)
        cloudApiKey = TranscriptionPrefs.cloudApiKey(context)
        cloudModel = TranscriptionPrefs.cloudModel(context)
    }

    /** 开始（或继续）下载模型。重复点击不会起第二个任务。 */
    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch { models.download() }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    fun deleteModel() {
        downloadJob?.cancel()
        downloadJob = null
        viewModelScope.launch { models.deleteAll() }
    }

    /** 把字节数变成「228 MB」这种一眼能读的量。 */
    fun formatBytes(bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    /** 模型当前占用的磁盘字节数。 */
    fun usedModelBytes(): Long = models.usedBytes()

    override fun onCleared() {
        // 只取消我们自己起的下载任务。已经写进 .part 的字节留在盘上，
        // 下次下载会从那里接着来，不算白下。
        downloadJob?.cancel()
        super.onCleared()
    }
}
