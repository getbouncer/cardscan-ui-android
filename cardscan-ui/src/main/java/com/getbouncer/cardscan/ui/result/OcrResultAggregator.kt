package com.getbouncer.cardscan.ui.result

import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.ResultAggregator
import com.getbouncer.scan.framework.ResultAggregatorConfig
import com.getbouncer.scan.payment.analyzer.PaymentCardOcrAnalyzer
import com.getbouncer.scan.payment.analyzer.PaymentCardOcrState
import com.getbouncer.scan.payment.card.isValidPan
import com.getbouncer.scan.payment.ml.SSDOcr
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock


data class PaymentCardOcrResult(val pan: String?, val name: String?, val expiry: String?)

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
    listener: AggregateResultListener<SSDOcr.Input, PaymentCardOcrState, InterimResult, PaymentCardOcrResult>,
    name: String,
    private val requiredAgreementCount: Int? = null,
    private val isNameExtractionEnabled: Boolean = false
) : ResultAggregator<SSDOcr.Input, PaymentCardOcrState, PaymentCardOcrAnalyzer.Prediction, OcrResultAggregator.InterimResult, PaymentCardOcrResult>(
    config = config,
    listener = listener,
    name = name
) {

    data class InterimResult(
        val analyzerResult: PaymentCardOcrAnalyzer.Prediction,
        val hasValidPan: Boolean
    )

    companion object {
        const val FRAME_TYPE_VALID_NUMBER = "valid_number"
        const val FRAME_TYPE_INVALID_NUMBER = "invalid_number"
        private const val NAME_UNAVAILABLE_RESPONSE = "<Insufficient API key permissions>"
    }

    private val storeFieldMutex = Mutex()
    private val panResults = mutableMapOf<String, Int>()
    private val nameResults = mutableMapOf<String, Int>()

    private var isPanScanningComplete: Boolean = false
    private var isNameFound: Boolean = false

    override fun resetAndPause() {
        super.resetAndPause()
        panResults.clear()
    }

    override suspend fun aggregateResult(
        result: PaymentCardOcrAnalyzer.Prediction,
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

        if (!isPanScanningComplete && requiredAgreementCount != null && numberCount >= requiredAgreementCount) {
            isPanScanningComplete = true
            updateState(state.copy(runOcr = false, runNameExtraction = true))
        }

        val nameCount = if (result.name?.isNotEmpty() == true) {
            storeField(result.name, nameResults)
        } else 0

        if (!isNameFound && nameCount >= 2) {
            isNameFound = true
        }

        val isNameExtractionAvailable = isNameExtractionEnabled && result.isNameExtractionAvailable

        return if (mustReturnFinal || (isPanScanningComplete && (!isNameExtractionAvailable || isNameFound))) {
            val name = if (!result.isNameExtractionAvailable && isNameExtractionEnabled) {
                NAME_UNAVAILABLE_RESPONSE
            } else {
                getMostLikelyField(nameResults, minCount = 2)
            }
            interimResult to PaymentCardOcrResult(
                getMostLikelyField(panResults),
                name,
                expiry = null
            )
        } else {
            interimResult to null
        }
    }

    private fun <T> getMostLikelyField(storage: Map<T, Int>, minCount: Int = 1): T? {
        val candidate = storage.maxBy { it.value }?.key
        return if (storage[candidate] ?: 0 >= minCount) {
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
    override fun getSaveFrameIdentifier(result: InterimResult, frame: SSDOcr.Input): String? =
        if (result.hasValidPan) {
            FRAME_TYPE_VALID_NUMBER
        } else {
            FRAME_TYPE_INVALID_NUMBER
        }

    override fun getFrameSizeBytes(frame: SSDOcr.Input): Int = frame.fullImage.byteCount
}
