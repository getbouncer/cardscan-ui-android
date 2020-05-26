package com.getbouncer.cardscan.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Size
import androidx.annotation.RestrictTo
import com.getbouncer.scan.camera.FrameConverter
import com.getbouncer.scan.framework.image.rotate
import com.getbouncer.scan.payment.ml.SSDOcr

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class CardImageFrameConverter(
    private val previewSize: Size,
    private val cardFinder: Rect
) : FrameConverter<Bitmap, SSDOcr.Input>() {
    override fun convert(source: Bitmap, rotationDegrees: Int) =
        SSDOcr.Input(
            fullImage = source.rotate(rotationDegrees.toFloat()),
            previewSize = previewSize,
            cardFinder = cardFinder
        )
}
