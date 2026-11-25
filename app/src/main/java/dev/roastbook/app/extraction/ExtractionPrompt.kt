package dev.roastbook.app.extraction

/**
 * The one prompt every model tier gets: the JSON keys and what each field means, plus the photos'
 * OCR text labeled by photo (capped at [MAX_OCR_CHARS]). The strict retry adds a JSON-only
 * reminder.
 */
object ExtractionPrompt {
    const val MAX_OCR_CHARS = 6000

    fun build(pages: List<OcrPage>, strictRetry: Boolean = false): String {
        val hasSeller = pages.any { it.isSeller }
        val ocr = pages.joinToString("\n") { p ->
            "--- ${p.label}${if (p.isSeller) " (a screenshot of the SELLER's description, not the bag)" else ""} ---\n${p.text}"
        }.take(MAX_OCR_CHARS)
        val intro = if (hasSeller) {
            "These are photos of one bag of roasted coffee; one of them is a screenshot of the seller's own description of it."
        } else {
            "These are photos of one bag of roasted coffee."
        }
        return buildString {
            append(intro).append(' ')
            append(
                """
                Read all text and return ONLY a JSON object with these keys: roaster, coffee_name,
                origin_country, region, producer, process, varietal (array), elevation_m (number),
                roast_level, roast_date, bag_size_g (number), tasting_notes (array), bag_description,
                seller_description.
                Field meanings: process is how the cherries were processed (washed, natural, honey,
                anaerobic, or the exact phrase printed); varietal is the coffee plant variety (for
                example Castillo, Caturra, Bourbon, Pacamara, Gesha, SL28), never a flavor;
                elevation_m is the growing altitude, often printed as MASL; tasting_notes are flavor
                descriptors taken only from an explicit tasting notes or flavor line, never from
                marketing taglines; producer is a farmer, farm, or cooperative name; roaster is the
                company that roasted it (not a store or subscription); bag_size_g is the net weight
                in grams (convert oz: 1 oz = 28.35 g); bag_description is the descriptive paragraph
                printed on the bag, verbatim but with obvious OCR errors corrected; seller_description
                is the seller's screenshot text, if any, verbatim with OCR errors corrected.
                Use null for anything not printed. No prose, no markdown.
                """.trimIndent().replace('\n', ' ')
            )
            if (strictRetry) append(" Your previous answer was not valid JSON. Return exactly one JSON object and nothing else.")
            if (ocr.isNotBlank()) {
                append("\n\nText recognized by OCR from the photos (may contain errors; where the photos are readable they are authoritative):\n")
                append(ocr)
            }
        }
    }
}
