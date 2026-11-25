package dev.roastbook.app.ui.rating

/** A partly filled rating carried from the History sheet's "Add details" into the Detail editor. */
data class RatingHandoff(val coffeeId: String, val draft: RatingDraft)
