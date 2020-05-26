package com.getbouncer.cardscan.ui.card

private const val JACCARD_SIMILARITY_THRESHOLD = 0.5

/**
 * Determine if the pan is valid or close to valid.
 */
fun isPossiblyValidPan(pan: String?) = pan?.length ?: 0 >= 7

/**
 * Determine if the pan is not close to being valid.
 */
fun isNotPossiblyValidPan(pan: String?) = pan?.length ?: 0 < 10

/**
 * Determine if a card number possibly matches a required number
 */
fun cardPossiblyMatches(scanned: String?, required: String?): Boolean =
    scanned != null && (required == null || jaccardIndex(scanned, required) > JACCARD_SIMILARITY_THRESHOLD)

/**
 * Calculate the jaccard index (similarity) between two strings. Values can range from 0 (no
 * similarities) to 1 (the same). Note that this does not account for character order, so two
 * strings "abcd" and "bdca" have a jaccard index of 1.
 */
private fun jaccardIndex(string1: String, string2: String): Double {
    val set1 = string1.toSet()
    val set2 = string2.toSet()

    val intersection = set1.intersect(set2)

    return intersection.size.toDouble() / (set1.size + set2.size - intersection.size)
}
