package com.riscure.bumper.coverage

import arrow.core.*
import com.riscure.bumper.highlight.SegmentMarker
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * (Partial) model of a coverage report as generated by llvm-cov export --format=json.
 */
data class Report(
    val files: List<File>
) {
    companion object {

        fun deserialize(reportFile: Path): Either<String, Report> =
            Json
                .parseToJsonElement(reportFile.readText())
                .let { element -> deserialize(element) }

        fun deserialize(reportEl: JsonElement): Either<String, Report> = try {
            // data is an array of 'one or more export objects'
            // according to the coverage export json spec.
            // Unclear what that means to me.
            // For now we just pick the first and supposedly only element.
            val data = reportEl
                .jsonObject["data"]!!
                .jsonArray[0]
            val files = data
                .jsonObject["files"]!!
                .jsonArray.asSequence()
                .map { fileEl ->
                    // parse each file
                    val fileObj = fileEl.jsonObject
                    val segments = fileObj["segments"]!!
                        .jsonArray
                        .map {
                            it
                                .jsonArray
                                .let {
                                    Segment(
                                        it[0].jsonPrimitive.int,
                                        it[1].jsonPrimitive.int,
                                        it[2].jsonPrimitive.int,
                                        it[3].jsonPrimitive.boolean,
                                        it[4].jsonPrimitive.boolean,
                                        it[5].jsonPrimitive.boolean,
                                    )
                                }
                        }

                    File(segments, fileObj["filename"]!!.jsonPrimitive.content)
                }

            Report(files.toList()).right()
        } catch (err: NullPointerException) {
            "Failed to deserialize JSON coverage report: incomplete object.".left()
        } catch (err: IndexOutOfBoundsException) {
            "Failed to deserialize JSON coverage report: incomplete tuple.".left()
        }
    }
}

data class File(
    val segments: List<Segment>,
    val path: String
)

data class Segment(
    val line: Int,
    val column: Int,
    val executionCount: Int,
    val hasCount: Boolean,
    val isRegionEntry: Boolean,
    val isGapRegion: Boolean
) {
    val marker get() = SegmentMarker(
        line,
        column,
        if (hasCount) executionCount.some() else none()
    )
}