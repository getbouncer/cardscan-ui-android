package com.getbouncer.cardscan.ui.result

import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.ResultAggregator
import com.getbouncer.scan.framework.ResultAggregatorConfig
import com.getbouncer.scan.framework.ResultCounter
import com.getbouncer.scan.payment.analyzer.PaymentCardOcrAnalyzer
import com.getbouncer.scan.payment.analyzer.PaymentCardOcrState
import com.getbouncer.scan.payment.card.isValidPan
import com.getbouncer.scan.payment.ml.SSDOcr

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
    private val requiredAgreementCount: Int? = null,
    private val isNameExtractionEnabled: Boolean = false
) : ResultAggregator<SSDOcr.Input, PaymentCardOcrState, PaymentCardOcrAnalyzer.Prediction, OcrResultAggregator.InterimResult, PaymentCardOcrResult>(
    config = config,
    listener = listener
) {

    data class InterimResult(
        val analyzerResult: PaymentCardOcrAnalyzer.Prediction,
        val mostLikelyPan: String?,
        val hasValidPan: Boolean
    )

    companion object {
        const val FRAME_TYPE_VALID_NUMBER = "valid_number"
        const val FRAME_TYPE_INVALID_NUMBER = "invalid_number"
        const val NAME_UNAVAILABLE_RESPONSE = "<Insufficient API key permissions>"
    }

    override val name: String = "ocr_result_aggregator"

    private val panResults = ResultCounter<String>()
    private val nameResults = ResultCounter<String>()

    private var isPanScanningComplete: Boolean = false
    private var isNameFound: Boolean = false

    override suspend fun reset() {
        super.reset()
        panResults.reset()
        nameResults.reset()
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
            mostLikelyPan = panResults.getMostLikelyResult(),
            hasValidPan = isValidPan(result.pan)
        )

        val pan = result.pan
        val numberCount = if (pan != null && interimResult.hasValidPan) {
            startAggregationTimer()
            panResults.countResult(pan) // This must be last so numberCount is assigned.
        } else 0

        if (!isPanScanningComplete && requiredAgreementCount != null && numberCount >= requiredAgreementCount) {
            isPanScanningComplete = true
            updateState(state.copy(runOcr = false, runNameExtraction = true))
        }

        val name = result.name
        val nameCount = if (name?.isNotEmpty() == true) {
            nameResults.countResult(name)
        } else 0

        if (!isNameFound && nameCount >= 2) {
            isNameFound = true
        }

        val isNameExtractionAvailable = isNameExtractionEnabled && result.isNameExtractionAvailable

        return if (mustReturnFinal || (isPanScanningComplete && (!isNameExtractionAvailable || isNameFound))) {
            val finalName = if (!result.isNameExtractionAvailable && isNameExtractionEnabled) {
                NAME_UNAVAILABLE_RESPONSE
            } else {
                nameResults.getMostLikelyResult(minCount = 2)
            }
            interimResult to PaymentCardOcrResult(
                panResults.getMostLikelyResult(),
                finalName,
                expiry = null
            )
        } else {
            interimResult to null
        }
    }

    // TODO: This should identify the least blurry images and store them in their own identifier
    override fun getSaveFrameIdentifier(result: InterimResult, frame: SSDOcr.Input): String? =
        if (result.hasValidPan) {
            FRAME_TYPE_VALID_NUMBER
        } else {
            FRAME_TYPE_INVALID_NUMBER
        }

    override fun getFrameSizeBytes(frame: SSDOcr.Input): Int = frame.fullImage.byteCount
}
