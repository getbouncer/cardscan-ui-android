package com.getbouncer.cardscan.ui.analyzer

import com.getbouncer.scan.framework.Analyzer
import com.getbouncer.scan.framework.AnalyzerFactory
import com.getbouncer.scan.payment.ml.SSDOcr
import com.getbouncer.scan.payment.ml.CardDetect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async

data class CardDetectOcrLoopState(
    val runOcr: Boolean,
    val runCardDetect: Boolean
)

class CardAnalyzer private constructor(
    private val coroutineScope: CoroutineScope,
    private val ssdOcr: SSDOcr?,
    private val cardDetect: CardDetect?
) : Analyzer<SSDOcr.Input, CardDetectOcrLoopState, CardAnalyzer.Prediction> {

    data class Prediction(val side: CardDetect.Prediction?, val pan: SSDOcr.Prediction?)

    override val name: String = "card_side_analyzer"

    override suspend fun analyze(data: SSDOcr.Input, state: CardDetectOcrLoopState): Prediction {
        val cardDetectFuture = if (state.runCardDetect && cardDetect != null) {
            coroutineScope.async { cardDetect.analyze(CardDetect.Input(
                fullImage = data.fullImage,
                previewSize = data.previewSize,
                cardFinder = data.cardFinder
            ), Unit) }
        } else {
            null
        }

        val ocrFuture = if (state.runOcr && ssdOcr != null) {
            coroutineScope.async { ssdOcr.analyze(data, Unit) }
        } else {
            null
        }

        return Prediction(cardDetectFuture?.await(), ocrFuture?.await())
    }

    class Factory(
        private val coroutineScope: CoroutineScope,
        private val ssdOcrFactory: SSDOcr.Factory,
        private val cardDetectFactory: CardDetect.Factory
    ) : AnalyzerFactory<CardAnalyzer> {
        override val isThreadSafe: Boolean = ssdOcrFactory.isThreadSafe && cardDetectFactory.isThreadSafe

        override suspend fun newInstance(): CardAnalyzer? = CardAnalyzer(
            coroutineScope,
            ssdOcrFactory.newInstance(),
            cardDetectFactory.newInstance()
        )
    }
}
