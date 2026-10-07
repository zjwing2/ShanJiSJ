package me.mudkip.moememos.data.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型清单的自检。
 *
 * 这份清单里的三样东西是**下发给运行期去执行的**：下载地址、字节数、SHA-256。
 * 任何一处打错字都不会在编译期报错，只会在用户点了「下载模型」之后、
 * 或者在 ONNX Runtime 加载到一半时炸掉。所以在这里把不变量钉住。
 */
class SherpaModelCatalogTest {

    @Test
    fun containsTheThreeFilesTheEngineNeeds() {
        val names = SherpaModelCatalog.files.map { it.fileName }.toSet()
        assertEquals(
            setOf("model.int8.onnx", "tokens.txt", "silero_vad.onnx"),
            names,
        )
    }

    @Test
    fun hashesAreWellFormed() {
        val hex = Regex("^[0-9a-f]{64}$")
        SherpaModelCatalog.files.forEach { file ->
            assertTrue(
                "${file.fileName} 的 sha256 不是 64 位小写十六进制：${file.sha256}",
                hex.matches(file.sha256),
            )
        }
    }

    @Test
    fun everyFileHasAtLeastOneHttpsMirror() {
        SherpaModelCatalog.files.forEach { file ->
            assertTrue("${file.fileName} 没有下载源", file.urls.isNotEmpty())
            file.urls.forEach { url ->
                assertTrue("${file.fileName} 的源不是 https：$url", url.startsWith("https://"))
            }
        }
    }

    @Test
    fun fileNamesAreUnique() {
        val names = SherpaModelCatalog.files.map { it.fileName }
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun sizesArePositiveAndMatchTheTotal() {
        SherpaModelCatalog.files.forEach { file ->
            assertTrue("${file.fileName} 的字节数不是正数", file.sizeBytes > 0)
        }
        assertEquals(
            SherpaModelCatalog.files.sumOf { it.sizeBytes },
            SherpaModelCatalog.totalBytes,
        )
    }

    @Test
    fun senseVoiceModelIsTheQuantisedOne() {
        val model = SherpaModelCatalog.file("model.int8.onnx")
        assertNotNull(model)
        // fp32 版本约 894 MB。选了 int8 就该是 228 MB 这一档；
        // 若有人误换回 fp32，这里会先炸，而不是等用户下完 894 MB 才发现。
        assertEquals(239_233_841L, model!!.sizeBytes)
        assertTrue(model.sizeMb in 220L..235L)
    }

    @Test
    fun theVocabIsTinyComparedToTheWeights() {
        val tokens = SherpaModelCatalog.file("tokens.txt")
        assertNotNull(tokens)
        // 词表只有几百 KB。若这条断言挂了，多半是下载地址指向了主模型。
        assertTrue(tokens!!.sizeBytes < 1024L * 1024L)
    }

    @Test
    fun totalDownloadIsRoughly229Megabytes() {
        // 界面上要如实写出这个数字，所以它本身也值得被守住
        assertEquals(229L, SherpaModelCatalog.totalBytes / (1024 * 1024))
    }

    @Test
    fun unknownFileNameYieldsNull() {
        assertEquals(null, SherpaModelCatalog.file("nope.onnx"))
    }
}
