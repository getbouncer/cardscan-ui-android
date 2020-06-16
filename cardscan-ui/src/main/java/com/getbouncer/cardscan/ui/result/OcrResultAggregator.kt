package com.getbouncer.cardscan.ui.result

import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.ResultAggregator
import com.getbouncer.scan.framework.ResultAggregatorConfig
import com.getbouncer.scan.framework.ResultCounter
import com.getbouncer.scan.payment.analyzer.PaymentCardOcrAnalyzer
import com.getbouncer.scan.payment.analyzer.PaymentCardOcrState
import com.getbouncer.scan.payment.card.isValidPan
import com.getbouncer.scan.payment.ml.ExpiryDetect
import com.getbouncer.scan.payment.ml.SSDOcr

data class PaymentCardOcrResult(
    val pan: String?,
    val name: String?,
    val expiry: ExpiryDetect.Expiry?,
    val errorString: String?
)

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
    private val isNameExtractionEnabled: Boolean = false,
    private val isExpiryExtractionEnabled: Boolean = false
) : ResultAggregator<SSDOcr.Input, PaymentCardOcrState, PaymentCardOcrAnalyzer.Prediction, OcrResultAggregator.InterimResult, PaymentCardOcrResult>(
    config = config,
    listener = listener,
    name = name
) {

    data class InterimResult(
        val analyzerResult: PaymentCardOcrAnalyzer.Prediction,
        val mostLikelyPan: String?,
        val hasValidPan: Boolean
    )

    companion object {
        const val FRAME_TYPE_VALID_NUMBER = "valid_number"
        const val FRAME_TYPE_INVALID_NUMBER = "invalid_number"
        const val NAME_OR_EXPIRY_UNAVAILABLE_RESPONSE = "<Insufficient API key permissions>"
    }

    private val panResults = ResultCounter<String>()
    private val nameResults = ResultCounter<String>()
    private val expiryResults = ResultCounter<ExpiryDetect.Expiry>()

    private var isPanScanningComplete: Boolean = false
    private var isNameFound: Boolean = false
    private var isExpiryFound: Boolean = false

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

        updatePanState(result, state, startAggregationTimer, updateState)
        updateNameState(result.name)
        updateExpiryState(result.expiry)

        val isNameExtractionAvailable = isNameExtractionEnabled && result.isNameAndExpiryExtractionAvailable
        val isExpiryExtractionAvailable = isExpiryExtractionEnabled && result.isNameAndExpiryExtractionAvailable

        return if (mustReturnFinal ||
            (isPanScanningComplete &&
                    (!isNameExtractionAvailable || isNameFound) &&
                    (!isExpiryExtractionAvailable || isExpiryFound)
            )
        ) {
            val finalName = if (!result.isNameAndExpiryExtractionAvailable && isNameExtractionEnabled) {
                NAME_OR_EXPIRY_UNAVAILABLE_RESPONSE
            } else {
                nameResults.getMostLikelyResult(minCount = 2)
            }

            val errorString = if (!result.isNameAndExpiryExtractionAvailable && isNameExtractionEnabled) {
                NAME_OR_EXPIRY_UNAVAILABLE_RESPONSE
            } else null

            interimResult to PaymentCardOcrResult(
                panResults.getMostLikelyResult(),
                finalName,
                expiry = expiryResults.getMostLikelyResult(minCount = 2),
                errorString = errorString
            )
        } else {
            interimResult to null
        }
    }

    private suspend fun updatePanState(
        result: PaymentCardOcrAnalyzer.Prediction,
        state: PaymentCardOcrState,
        startAggregationTimer: () -> Unit,
        updateState: (PaymentCardOcrState) -> Unit
    ) {
        val pan = result.pan
        val numberCount = if (pan != null && isValidPan(result.pan)) {
            startAggregationTimer()
            panResults.countResult(pan) // This must be last so numberCount is assigned.
        } else 0

        if (!isPanScanningComplete && requiredAgreementCount != null && numberCount >= requiredAgreementCount) {
            isPanScanningComplete = true
            updateState(state.copy(
                runOcr = false,
                runNameExtraction = isNameExtractionEnabled,
                runExpiryExtraction = isExpiryExtractionEnabled
            ))
        }
    }

    /**
     * Updates internal counter for expiry, and associated states for finishing the aggregator
     */
    private suspend fun updateNameState(name: String?) {
        val nameCount = if (name?.isNotEmpty() == true) {
            nameResults.countResult(name)
        } else 0

        if (!isNameFound && nameCount >= 2) {
            isNameFound = true
        }

        if (!isNameFound && nameCount >= 2) {
            isNameFound = true
        }
    }


    /**
     * Updates internal counter for expiry, and associated states for finishing the aggregator
     */
    private suspend fun updateExpiryState(expiry: ExpiryDetect.Expiry?) {
        val expiryCount = if (expiry != null) {
            expiryResults.countResult(expiry)
        } else {
            0
        }
        if (!isExpiryFound && expiryCount >= 2) {
            isExpiryFound = true
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
