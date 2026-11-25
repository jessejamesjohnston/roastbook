package dev.roastbook.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where a coffee is in its life: DRAFT while its photos are being imported, EXTRACTING while a
 * read is queued or running, LOGGED once read but not yet rated, RATED once it has a score.
 */
enum class CoffeeState { DRAFT, EXTRACTING, LOGGED, RATED }

/** What a photo shows; FRONT is preferred as the hero image. */
enum class PhotoRole { FRONT, BACK, PAMPHLET, BOX, SELLER, OTHER }

/**
 * Which tier produced the structured fields. OCR means the rule-based guess over the recognized
 * text (fields may be blank); LOCAL is the downloadable on-device reader (Gemma via LiteRT-LM).
 */
enum class ExtractionSource { NONE, OCR, NANO, CLOUD, LOCAL }

/**
 * One bag of coffee: its extracted facts, the user's rating, hero colors, extraction bookkeeping,
 * reminders and purchase details, in one row. Columns with their own writers (reminders,
 * enrichment, hero results) also have column-scoped DAO updates; whole-row writes re-read the row
 * in a transaction first, and extraction never overwrites a field listed in [userEdited].
 */
@Entity(
    tableName = "coffee",
    indices = [
        Index("logged_at"),
        Index("overall"),
        Index("aroma"), Index("flavor"), Index("acidity"), Index("body"), Index("finish"),
        Index("state"),
    ],
)
data class CoffeeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
    val state: CoffeeState,

    val roaster: String? = null,
    @ColumnInfo(name = "coffee_name") val coffeeName: String? = null,
    @ColumnInfo(name = "origin_country") val originCountry: String? = null,
    val region: String? = null,
    val producer: String? = null,
    val process: String? = null,
    /** JSON array of strings. */
    val varietal: List<String> = emptyList(),
    @ColumnInfo(name = "elevation_m") val elevationM: Int? = null,
    @ColumnInfo(name = "roast_level") val roastLevel: String? = null,
    @ColumnInfo(name = "roast_date") val roastDate: String? = null,
    @ColumnInfo(name = "bag_size_g") val bagSizeG: Int? = null,
    @ColumnInfo(name = "tasting_notes") val tastingNotes: List<String> = emptyList(),
    @ColumnInfo(name = "bag_description") val bagDescription: String? = null,
    @ColumnInfo(name = "seller_description") val sellerDescription: String? = null,
    val notes: String? = null,

    val aroma: Int? = null,
    val flavor: Int? = null,
    val acidity: Int? = null,
    val body: Int? = null,
    val finish: Int? = null,
    val overall: Float? = null,
    @ColumnInfo(name = "buy_again") val buyAgain: Boolean = false,
    @ColumnInfo(name = "rated_at") val ratedAt: Long? = null,
    @ColumnInfo(name = "last_tasted_at") val lastTastedAt: Long? = null,

    @ColumnInfo(name = "hero_photo_id") val heroPhotoId: String? = null,
    /** Precomputed row colors (ARGB ints), from the tint rule. Null until the hero is processed. */
    @ColumnInfo(name = "tint_light") val tintLight: Int? = null,
    @ColumnInfo(name = "tint_dark") val tintDark: Int? = null,
    @ColumnInfo(name = "accent_light") val accentLight: Int? = null,
    @ColumnInfo(name = "accent_dark") val accentDark: Int? = null,
    @ColumnInfo(name = "palette_fallback") val paletteFallback: Boolean = false,
    /** True once the hero square came from a real segmentation mask (not the centered fallback). */
    @ColumnInfo(name = "hero_segmented", defaultValue = "0") val heroSegmented: Boolean = false,
    @ColumnInfo(name = "hero_attempts", defaultValue = "0") val heroAttempts: Int = 0,

    @ColumnInfo(name = "extraction_source") val extractionSource: ExtractionSource = ExtractionSource.NONE,
    @ColumnInfo(name = "extraction_meta") val extractionMeta: String? = null,
    @ColumnInfo(name = "extraction_attempts") val extractionAttempts: Int = 0,
    @ColumnInfo(name = "ocr_text") val ocrText: String? = null,
    /**
     * While EXTRACTING: the earliest time the scheduler may try this row again (a parked retry while
     * the on-device model downloads). Null = as soon as the app is in the foreground.
     */
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long? = null,
    /** What each tier did on the last read, encoded by [dev.roastbook.app.extraction.ExtractionTrail]. */
    @ColumnInfo(name = "extraction_trail") val extractionTrail: String? = null,

    @ColumnInfo(name = "roaster_url") val roasterUrl: String? = null,
    @ColumnInfo(name = "product_url") val productUrl: String? = null,
    @ColumnInfo(name = "roaster_city") val roasterCity: String? = null,
    @ColumnInfo(name = "enrichment_status") val enrichmentStatus: String? = null,

    @ColumnInfo(name = "source_id") val sourceId: String? = null,
    @ColumnInfo(name = "price_usd") val priceUsd: Float? = null,
    @ColumnInfo(name = "remind_at") val remindAt: Long? = null,
    @ColumnInfo(name = "second_look_at") val secondLookAt: Long? = null,
    @ColumnInfo(name = "second_look_done") val secondLookDone: Boolean = false,

    /**
     * Names of [ExtractedFields] the user has edited by hand (JSON array). Written by the
     * repository's edit path, consulted by the extractor when it merges any tier's result: a listed
     * field keeps the user's value. Sticky: a Reread does not clear it, so a hand edit is never
     * overwritten by a later read (only a fresh import starts it empty).
     */
    @ColumnInfo(name = "user_edited", defaultValue = "[]") val userEdited: List<String> = emptyList(),
)

/**
 * The fields extraction writes and the Detail editors may overwrite, by name. One list so the
 * repository (recording a hand edit) and the extractor (honoring it) agree on the names.
 */
object ExtractedFields {
    val readers: Map<String, (CoffeeEntity) -> Any?> = linkedMapOf(
        "roaster" to { it.roaster },
        "coffeeName" to { it.coffeeName },
        "originCountry" to { it.originCountry },
        "region" to { it.region },
        "producer" to { it.producer },
        "process" to { it.process },
        "varietal" to { it.varietal },
        "elevationM" to { it.elevationM },
        "roastLevel" to { it.roastLevel },
        "roastDate" to { it.roastDate },
        "bagSizeG" to { it.bagSizeG },
        "tastingNotes" to { it.tastingNotes },
        "bagDescription" to { it.bagDescription },
        "sellerDescription" to { it.sellerDescription },
    )

    /** Names of the extracted fields whose value differs between [before] and [after], in canonical order. */
    fun changed(before: CoffeeEntity, after: CoffeeEntity): List<String> =
        readers.filter { (_, read) -> read(before) != read(after) }.keys.toList()
}

/** One photo of a coffee, ordered by [position]; deleting the coffee deletes its photo rows. */
@Entity(
    tableName = "coffee_photo",
    foreignKeys = [ForeignKey(entity = CoffeeEntity::class, parentColumns = ["id"], childColumns = ["coffee_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("coffee_id")],
)
data class CoffeePhotoEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "coffee_id") val coffeeId: String,
    val role: PhotoRole,
    val position: Int,
    /** Paths relative to filesDir. */
    val original: String,
    val square: String? = null,
    val thumb: String? = null,
    @ColumnInfo(name = "taken_at") val takenAt: Long,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/** A place bags are bought, for the "where you bought it" picklist; [isSeed] marks a starter. */
@Entity(tableName = "source", indices = [Index(value = ["name"], unique = true)])
data class SourceEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "is_seed") val isSeed: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Light projection for History rows. */
data class CoffeeRow(
    val id: String,
    val state: CoffeeState,
    @ColumnInfo(name = "logged_at") val loggedAt: Long,
    val roaster: String?,
    @ColumnInfo(name = "coffee_name") val coffeeName: String?,
    @ColumnInfo(name = "tasting_notes") val tastingNotes: List<String>,
    val overall: Float?,
    @ColumnInfo(name = "buy_again") val buyAgain: Boolean,
    @ColumnInfo(name = "tint_light") val tintLight: Int?,
    @ColumnInfo(name = "tint_dark") val tintDark: Int?,
    @ColumnInfo(name = "accent_light") val accentLight: Int?,
    @ColumnInfo(name = "accent_dark") val accentDark: Int?,
    @ColumnInfo(name = "palette_fallback") val paletteFallback: Boolean,
    @ColumnInfo(name = "extraction_source") val extractionSource: ExtractionSource,
    @ColumnInfo(name = "extraction_attempts") val extractionAttempts: Int,
    @ColumnInfo(name = "extraction_trail") val extractionTrail: String?,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long?,
    @ColumnInfo(name = "hero_thumb") val heroThumb: String?,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
)
