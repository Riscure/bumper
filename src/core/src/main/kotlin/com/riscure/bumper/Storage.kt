package com.riscure.bumper

import arrow.core.*
import com.riscure.Digest
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory

interface Storage {
    /**
     * Returns a path that is stable and unique for the given inputs.
     */
    fun inputAddressed(prefix: String, vararg inputs: Digest, suffix: String = ""): Path

    companion object {
        /**
         * An instance of Storage using temporary files.
         */
        @JvmStatic
        fun temporary(prefix: String = ""): Either<Throwable, FileStorage> = Either.catch {
            FileStorage(createTempDirectory(prefix = prefix))
        }

        /**
         * An instance of Storage using a given directory.
         */
        @JvmStatic
        fun directory(path: Path): Either<Throwable, FileStorage> = Either.catch {
            path.createDirectories()
            FileStorage(path)
        }
    }

}

class FileStorage(val directory: Path): Storage {
    override fun inputAddressed(prefix: String, vararg inputs: Digest, suffix: String): Path {
        val hash = Digest.combineAll(inputs.toList()).makeKey(15)
        return directory.resolve("${prefix}-${hash}${suffix}")
    }
}

