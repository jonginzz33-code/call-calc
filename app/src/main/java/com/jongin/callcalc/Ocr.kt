package com.jongin.callcalc

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.jongin.callcalc.core.TextItem
import java.util.concurrent.Executors

/** OCR 결과: 글자 덩어리(줄 단위)와 걸린 시간 */
data class OcrResult(val items: List<TextItem>, val loadMs: Long, val ocrMs: Long, val width: Int, val height: Int)

/** ML Kit 한국어 글자 인식 (앱에 포함된 모델, 인터넷 안 씀) */
object Ocr {
    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun read(context: Context, uri: Uri, done: (Result<OcrResult>) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val t0 = SystemClock.elapsedRealtime()
            val image = try {
                InputImage.fromFilePath(app, uri)
            } catch (e: Exception) {
                main.post { done(Result.failure(e)) }
                return@execute
            }
            val t1 = SystemClock.elapsedRealtime()
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    val t2 = SystemClock.elapsedRealtime()
                    val items = text.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val b = line.boundingBox ?: return@mapNotNull null
                            TextItem(line.text, b.left, b.top, b.right, b.bottom)
                        }
                    }
                    done(Result.success(OcrResult(items, t1 - t0, t2 - t1, image.width, image.height)))
                }
                .addOnFailureListener { done(Result.failure(it)) }
        }
    }
}
