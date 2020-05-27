package com.getbouncer.cardscan.ui.result

import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.ResultAggregator
import com.getbouncer.scan.framework.ResultAggregatorConfig
import com.getbouncer.scan.payment.card.isValidPan
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
    listener: AggregateResultListener<SSDOcr.Input, Unit, InterimResult, String>,
    name: String,
    private val requiredAgreementCount: Int? = null
) : ResultAggregator<SSDOcr.Input, Unit, SSDOcr.Prediction, OcrResultAggregator.InterimResult, String>(
    config = config,
    listener = listener,
    name = name
) {

    data class InterimResult(
        val analyzerResult: SSDOcr.Prediction,
        val hasValidPan: Boolean
    )

    companion object {
        const val FRAME_TYPE_VALID_NUMBER = "valid_number"
        const val FRAME_TYPE_INVALID_NUMBER = "invalid_number"
    }

    private val storeFieldMutex = Mutex()
    private val panResults = mutableMapOf<String, Int>()

    override fun resetAndPause() {
        super.resetAndPause()
        panResults.clear()
    }

    override suspend fun aggregateResult(
        result: SSDOcr.Prediction,
        state: Unit,
        mustReturnFinal: Boolean,
        updateState: (Unit) -> Unit
    ): Pair<InterimResult, String?> {
        val interimResult = InterimResult(
            analyzerResult = result,
            hasValidPan = isValidPan(result.pan)
        )

        val numberCount = if (interimResult.hasValidPan) {
            storeField(result.pan, panResults) // This must be last so numberCount is assigned.
        } else 0

        val hasMetRequiredAgreementCount =
            if (requiredAgreementCount != null) numberCount >= requiredAgreementCount else false

        return if (mustReturnFinal || hasMetRequiredAgreementCount) {
            interimResult to getMostLikelyField(panResults)
        } else {
            interimResult to null
        }
    }

    private fun <T> getMostLikelyField(storage: Map<T, Int>): T? = storage.maxBy { it.value }?.key

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
