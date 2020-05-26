package com.getbouncer.cardscan.ui.result

import com.getbouncer.cardscan.ui.analyzer.CardAnalyzer
import com.getbouncer.cardscan.ui.analyzer.CardDetectOcrLoopState
import com.getbouncer.cardscan.ui.card.cardPossiblyMatches
import com.getbouncer.cardscan.ui.card.isPossiblyValidPan
import com.getbouncer.scan.framework.AggregateResultListener
import com.getbouncer.scan.framework.ResultAggregator
import com.getbouncer.scan.framework.ResultAggregatorConfig
import com.getbouncer.scan.framework.time.AtomicClockMark
import com.getbouncer.scan.framework.time.Clock
import com.getbouncer.scan.framework.time.ClockMark
import com.getbouncer.scan.framework.time.Duration
import com.getbouncer.scan.framework.time.seconds
import com.getbouncer.scan.payment.card.isValidIin
import com.getbouncer.scan.payment.card.isValidPan
import com.getbouncer.scan.payment.card.isValidPanLastFour
import com.getbouncer.scan.payment.ml.SSDOcr
import com.getbouncer.scan.payment.ml.CardDetect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val OCR_DEBOUNCE_DURATION = 1.seconds

class CardDetectOcrAggregator(
    config: ResultAggregatorConfig,
    listener: AggregateResultListener<SSDOcr.Input, CardDetectOcrLoopState, InterimResult, FinalResult>,
    name: String,
    private val requiredCardIin: String? = null,
    private val requiredCardLastFour: String? = null,
    private val requiredAgreementCount: Int = 3
) : ResultAggregator<SSDOcr.Input, CardDetectOcrLoopState, CardAnalyzer.Prediction, CardDetectOcrAggregator.InterimResult, CardDetectOcrAggregator.FinalResult>(config, listener, name) {

    companion object {
        const val SAVE_FRAME_IDENTIFIER_PAN = "Pan"
        const val SAVE_FRAME_IDENTIFIER_NO_PAN = "NoPan"
    }

    data class InterimResult(val analyzerResult: CardAnalyzer.Prediction?, val side: CardDetect.Prediction?, val matchesRequiredCard: Boolean)

    data class FinalResult(val pan: String?)

    private val panResults = mutableMapOf<String, Int>()

    private val lastValidResult = AtomicClockMark()
    private var previousCardSide: CardDetect.Prediction.Side = CardDetect.Prediction.Side.NO_CARD

    private var ocrLastTurnedOnAt: ClockMark? = null
    private var ocrLastTurnedOffAt: ClockMark? = null

    private val storeFieldMutex = Mutex()

    override suspend fun aggregateResult(
        result: CardAnalyzer.Prediction,
        state: CardDetectOcrLoopState,
        mustReturnFinal: Boolean,
        updateState: (CardDetectOcrLoopState) -> Unit
    ): Pair<InterimResult, FinalResult?> {
        val cardSide = calculateCardSide(result) ?: previousCardSide
        previousCardSide = cardSide
        val pan = result.pan?.pan

        val interimResult = InterimResult(result, result.side, panMatchesRequiredCard(pan))

        val panCount = if (cardSide == CardDetect.Prediction.Side.PAN && isValidPan(pan) && interimResult.matchesRequiredCard) {
            storeField(pan, panResults)
        } else 0

        if (panCount >= requiredAgreementCount) {
            return interimResult to FinalResult(getMostLikelyField(panResults))
        }

        if (cardSide == CardDetect.Prediction.Side.PAN || isPossiblyValidPan(pan)) {
            lastValidResult.setNow()
        }

        if ((cardSide == CardDetect.Prediction.Side.PAN || isPossiblyValidPan(pan)) && !state.runOcr) {
            // If this looks like the PAN side, try to OCR
            tryTurnOcrOn(state, updateState)
        } else if (cardSide != CardDetect.Prediction.Side.PAN && !isPossiblyValidPan(pan) && state.runOcr) {
            // If this looks like the non-pan side or no card, do not OCR
            tryTurnOcrOff(state, updateState)
        }

        if (mustReturnFinal) {
            return interimResult to FinalResult(getMostLikelyField(panResults))
        }

        return interimResult to null
    }

    override fun getSaveFrameIdentifier(
        result: InterimResult,
        frame: SSDOcr.Input
    ): String? = when {
        result.matchesRequiredCard -> SAVE_FRAME_IDENTIFIER_PAN
        result.side?.side == CardDetect.Prediction.Side.PAN -> SAVE_FRAME_IDENTIFIER_NO_PAN
        else -> null
    }

    override fun getFrameSizeBytes(frame: SSDOcr.Input): Int = frame.fullImage.byteCount

    /**
     * Attempt to turn on OCR and turn off card detect. If OCR is already running or was just
     * recently turned off, don't turn it back on.
     */
    private fun tryTurnOcrOn(state: CardDetectOcrLoopState, updateState: (CardDetectOcrLoopState) -> Unit) {
        if (state.runOcr || isOcrRecentlyTurnedOff()) {
            return
        }

        updateState(state.copy(runOcr = true, runCardDetect = false))
        ocrLastTurnedOnAt = Clock.markNow()
    }

    /**
     * Attempt to turn off OCR and turn on card detect. If OCR is not running or was just recently
     * turned on, don't turn it back off.
     */
    private fun tryTurnOcrOff(state: CardDetectOcrLoopState, updateState: (CardDetectOcrLoopState) -> Unit) {
        if (!state.runOcr || isOcrRecentlyTurnedOn()) {
            return
        }

        updateState(state.copy(runOcr = false, runCardDetect = true))
        ocrLastTurnedOffAt = Clock.markNow()
    }

    private fun isOcrRecentlyTurnedOff() = ocrLastTurnedOffAt?.elapsedSince() ?: Duration.INFINITE < OCR_DEBOUNCE_DURATION

    private fun isOcrRecentlyTurnedOn() = ocrLastTurnedOnAt?.elapsedSince() ?: Duration.INFINITE < OCR_DEBOUNCE_DURATION

    /**
     * Determine if the PAN matches the required card IIN and last four.
     */
    private fun panMatchesRequiredCard(pan: String?): Boolean {
        val scannedIin = pan?.take(requiredCardIin?.length ?: 0)
        val scannedLastFour = pan?.takeLast(requiredCardLastFour?.length ?: 0)
        return if (isValidIin(requiredCardIin) && isValidPanLastFour(requiredCardLastFour)) {
            cardPossiblyMatches(scannedIin, requiredCardIin) &&
                cardPossiblyMatches(scannedLastFour, requiredCardLastFour)
        } else if (isValidIin(requiredCardIin)) {
            cardPossiblyMatches(scannedIin, requiredCardIin)
        } else if (isValidPanLastFour(requiredCardLastFour)) {
            cardPossiblyMatches(scannedLastFour, requiredCardLastFour)
        } else {
            pan != null
        }
    }

    /**
     * Determine what side of the card this result has.
     */
    private fun calculateCardSide(result: CardAnalyzer.Prediction): CardDetect.Prediction.Side? =
        when {
            isValidPan(result.pan?.pan) -> CardDetect.Prediction.Side.PAN
            isPossiblyValidPan(result.pan?.pan) -> CardDetect.Prediction.Side.PAN
            result.side == null -> CardDetect.Prediction.Side.NO_CARD
            else -> result.side.side
        }

    private fun <T> getMostLikelyField(storage: Map<T, Int>): T? = storage.maxBy { it.value }?.key

    private suspend fun <T> storeField(field: T?, storage: MutableMap<T, Int>): Int =
        storeFieldMutex.withLock {
            if (field != null) {
                val count = 1 + (storage[field] ?: 0)
                storage[field] = count
                count
            } else {
                0
            }
        }
}
