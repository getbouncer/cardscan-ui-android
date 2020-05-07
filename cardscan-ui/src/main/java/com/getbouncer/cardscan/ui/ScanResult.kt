package com.getbouncer.cardscan.ui

import android.os.Parcelable
import com.getbouncer.scan.payment.card.PaymentCard
import kotlinx.android.parcel.Parcelize

@Parcelize
data class ScanResult(
    val pan: String?,
    val expiryDay: String?,
    val expiryMonth: String?,
    val expiryYear: String?,
    val networkName: String?,
    val cvc: String?,
    val legalName: String?
) : Parcelable

/**
 * Convert a [PaymentCard] to a [ScanResult].
 */
fun PaymentCard.toScanResult(): ScanResult = ScanResult(
    pan = pan,
    expiryDay = expiry?.day,
    expiryMonth = expiry?.month,
    expiryYear = expiry?.year,
    networkName = issuer?.displayName,
    cvc = cvc,
    legalName = legalName
)
