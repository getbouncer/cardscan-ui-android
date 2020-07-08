package com.getbouncer.cardscan.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import androidx.lifecycle.LifecycleOwner
import com.getbouncer.cardscan.ui.analyzer.NameAndExpiryAnalyzer
import com.getbouncer.cardscan.ui.analyzer.PaymentCardOcrAnalyzer
import com.getbouncer.cardscan.ui.analyzer.PaymentCardOcrState
import com.getbouncer.cardscan.ui.result.OcrResultAggregator
import com.getbouncer.cardscan.ui.result.PaymentCardOcrResult
import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.AnalyzerLoopErrorListener
import com.getbouncer.scan.framework.AnalyzerPoolFactory
import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.ProcessBoundAnalyzerLoop
import com.getbouncer.scan.framework.ResultAggregatorConfig
import com.getbouncer.scan.framework.time.Clock
import com.getbouncer.scan.framework.time.seconds
import com.getbouncer.scan.framework.util.cacheFirstResultSuspend
import com.getbouncer.scan.payment.ml.AlphabetDetect
import com.getbouncer.scan.payment.ml.ExpiryDetect
import com.getbouncer.scan.payment.ml.SSDOcr
import com.getbouncer.scan.payment.ml.TextDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * This class contains the scanning logic required for analyzing a credit card for scanning purposes.
 */
class CardScanFlow(
    private val enableNameExtraction: Boolean,
    private val enableExpiryExtraction: Boolean,
    private val resultListener: AggregateResultListener<SSDOcr.Input, PaymentCardOcrState, OcrResultAggregator.InterimResult, PaymentCardOcrResult>,
    private val errorListener: AnalyzerLoopErrorListener
) {

    companion object {

        /**
         * This field represents whether the flow was initialized with name and expiry enabled.
         */
        var attemptedNameAndExpiryInitialization = false
            private set

        /**
         * Warm up the analyzers for card scanner. This method is optional, but will increase the
         * speed at which the scan occurs.
         *
         * @param context: A context to use for warming up the analyzers.
         */
        @JvmStatic
        fun warmUp(context: Context, apiKey: String, initializeNameAndExpiryExtraction: Boolean) {
            Config.apiKey = apiKey

            GlobalScope.launch(Dispatchers.Default) {
                getAnalyzerPool(context, initializeNameAndExpiryExtraction)
            }
        }

        private val getAnalyzerPool = cacheFirstResultSuspend { context: Context, enableNameOrExpiryExtraction: Boolean ->
            val nameDetect = if (enableNameOrExpiryExtraction) {
                attemptedNameAndExpiryInitialization = true
                NameAndExpiryAnalyzer.Factory(
                    TextDetector.Factory(context, TextDetector.ModelLoader(context)),
                    AlphabetDetect.Factory(context, AlphabetDetect.ModelLoader(context)),
                    ExpiryDetect.Factory(context, ExpiryDetect.ModelLoader(context))
                )
            } else {
                null
            }

            AnalyzerPoolFactory(
                PaymentCardOcrAnalyzer.Factory(SSDOcr.Factory(context, SSDOcr.ModelLoader(context)), nameDetect)
            ).buildAnalyzerPool()
        }
    }

    /**
     * If this is true, do not start the flow.
     */
    private var canceled = false

    private lateinit var mainLoopResultAggregator: OcrResultAggregator
    private var mainLoopJob: Job? = null

    /**
     * Start the image processing flow for scanning a card.
     *
     * @param context: The context used to download analyzers if needed
     * @param imageStream: The flow of images to process
     * @param previewSize: The size of the preview frame where the view finder is located
     */
    fun startFlow(
        context: Context,
        imageStream: Flow<Bitmap>,
        previewSize: Size,
        viewFinder: Rect,
        lifecycleOwner: LifecycleOwner,
        coroutineScope: CoroutineScope
    ) {
        if (canceled) {
            return
        }

        mainLoopResultAggregator = OcrResultAggregator(
            config = ResultAggregatorConfig.Builder()
                .withMaxTotalAggregationTime(if (enableNameExtraction || enableExpiryExtraction) 15.seconds else 2.seconds)
                .withDefaultMaxSavedFrames(0)
                .build(),
            listener = resultListener,
            requiredPanAgreementCount = if (enableNameExtraction || enableExpiryExtraction) 2 else 5,
            requiredNameAgreementCount = 2,
            requiredExpiryAgreementCount = 3,
            isNameExtractionEnabled = enableNameExtraction,
            isExpiryExtractionEnabled = enableExpiryExtraction,
            initialState = PaymentCardOcrState(
                runOcr = true,
                runNameExtraction = false,
                runExpiryExtraction = false
            )
        )

        // make this result aggregator pause and reset when the lifecycle pauses.
        mainLoopResultAggregator.bindToLifecycle(lifecycleOwner)

        val mainLoop = ProcessBoundAnalyzerLoop(
            analyzerPool = runBlocking { getAnalyzerPool(context, attemptedNameAndExpiryInitialization) },
            resultHandler = mainLoopResultAggregator,
            name = "main_loop",
            analyzerLoopErrorListener = errorListener
        )

        mainLoop.subscribeTo(
            flow = imageStream.map {
                SSDOcr.Input(
                    fullImage = it,
                    previewSize = previewSize,
                    cardFinder = viewFinder,
                    capturedAt = Clock.markNow()
                )
            },
            processingCoroutineScope = coroutineScope
        )
    }

    /**
     * In the event that the scan cannot complete, halt the flow to halt analyzers and free up CPU and memory.
     */
    fun cancelFlow() {
        canceled = true
        if (::mainLoopResultAggregator.isInitialized) {
            mainLoopResultAggregator.cancel()
        }

        mainLoopJob?.apply { if (isActive) { cancel() } }
    }
}
