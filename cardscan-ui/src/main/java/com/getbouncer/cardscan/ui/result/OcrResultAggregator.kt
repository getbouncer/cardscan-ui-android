package com.getbouncer.cardscan.ui.result

import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.ResultAggregator
import com.getbouncer.scan.framework.ResultAggregatorConfig
import com.getbouncer.scan.payment.analyzer.PaymentCardOcrState
import com.getbouncer.scan.payment.card.isValidPan
import com.getbouncer.scan.payment.ml.PaymentCardOcrResult
import com.getbouncer.scan.payment.ml.PaymentCardPanOcrAnalyzerOutput
import com.getbouncer.scan.payment.ml.SSDOcr
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keep track of the results from the [AnalyzerLoop]. Count the number of times the loop sends each
 * PAN as a result, and when the first result is received.
 *
 * The [listener] will be notified of a result once [requiredAgreementCount] matching results are
 * received or the time since the first result exceeds the
 * [ResultAggregatorConfig.maxTotalAggregationTime].
 */
class OcrResultAggregator(
    config: ResultAggregatorConfig,
    listener: AggregateResultListener<SSDOcr.SSDOcrInput, PaymentCardOcrState, InterimResult, PaymentCardOcrResult>,
    name: String,
    private val requiredAgreementCount: Int? = null
) : ResultAggregator<SSDOcr.SSDOcrInput, PaymentCardOcrState, PaymentCardPanOcrAnalyzerOutput, OcrResultAggregator.InterimResult, PaymentCardOcrResult>(
    config = config,
    listener = listener,
    name = name
) {

    data class InterimResult(
        val analyzerResult: PaymentCardPanOcrAnalyzerOutput,
        val hasValidPan: Boolean
    )

    companion object {
        const val FRAME_TYPE_VALID_NUMBER = "valid_number"
        const val FRAME_TYPE_INVALID_NUMBER = "invalid_number"
    }

    private val storeFieldMutex = Mutex()
    private val panResults = mutableMapOf<String, Int>()

    private val nameResults = mutableMapOf<String, Int>()

    protected var isPanScanningComplete: Boolean = false
    protected var hasName: Boolean = false

    override fun resetAndPause() {
        super.resetAndPause()
        panResults.clear()
    }

    override suspend fun aggregateResult(
        result: PaymentCardPanOcrAnalyzerOutput,
        state: PaymentCardOcrState,
        startAggregationTimer: () -> Unit,
        mustReturnFinal: Boolean,
        updateState: (PaymentCardOcrState) -> Unit
    ): Pair<InterimResult, PaymentCardOcrResult?> {

        val interimResult = InterimResult(
            analyzerResult = result,
            hasValidPan = isValidPan(result.pan)
        )

        val numberCount = if (interimResult.hasValidPan) {
            startAggregationTimer()
            storeField(result.pan, panResults) // This must be last so numberCount is assigned.
        } else 0

        val panExtractionHasMetRequiredAgreementCount =
            if (requiredAgreementCount != null) numberCount >= requiredAgreementCount else false

        if (panExtractionHasMetRequiredAgreementCount) {
            isPanScanningComplete = true
            updateState(state.copy(runOcr = false, runNameExtraction = true))
        }

        val nameNumberCount = if (result.name != null && result.name!!.isNotEmpty()) {
            storeField(result.name, nameResults)
        } else 0

        val nameExtractionHasMetRequiredAgreementCount = nameNumberCount >= 2
        if (nameExtractionHasMetRequiredAgreementCount) {
            hasName = true
        }

        val hasMetRequiredAgreementCount =
            if (requiredAgreementCount != null) numberCount >= requiredAgreementCount else false

        return if (mustReturnFinal || hasMetRequiredAgreementCount) {
            //interimResult to getMostLikelyField(panResults)
            interimResult to PaymentCardOcrResult(
                getMostLikelyField(panResults),
                getMostLikelyField(nameResults, minCount = 2),
                expiry = null
            )
        } else {
            interimResult to null
        }
    }

    private fun <T> getMostLikelyField(storage: Map<T, Int>, minCount: Int = 1): T? {
        val candidate = storage.maxBy { it.value }?.key
        return if (storage[candidate] != null && storage[candidate]!! >= minCount) {
            candidate
        } else null
    }

    private suspend fun <T> storeField(field: T?, storage: MutableMap<T, Int>): Int = storeFieldMutex.withLock {
        if (field != null) {
            val count = 1 + (storage[field] ?: 0)
            storage[field] = count
            count
        } else {
            0
        }
    }

    // TODO: This should store the least blurry images available
    override fun getSaveFrameIdentifier(result: InterimResult, frame: SSDOcr.SSDOcrInput): String? =
        if (result.hasValidPan) {
            FRAME_TYPE_VALID_NUMBER
        } else {
            FRAME_TYPE_INVALID_NUMBER
        }

    override fun getFrameSizeBytes(frame: SSDOcr.SSDOcrInput): Int = frame.fullImage.byteCount
}
