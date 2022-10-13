package com.riscure.langs.c.pp

import arrow.core.*
import com.riscure.langs.c.ast.TopLevel
import com.riscure.langs.c.ast.TranslationUnit
import com.riscure.langs.c.parser.clang.ClangParser
import java.io.StringWriter
import java.nio.charset.Charset
import kotlin.io.path.*
import kotlin.test.*

internal class AstWriterTest {
    fun literal(input: String, transform: (unit: TranslationUnit) -> TranslationUnit = {it}): String {
        val s = StringWriter()

        val file = createTempFile(suffix = ".c").apply {
            writeText(input)
        }

        val parser = ClangParser()
        val ast = parser.parse(file.toFile())
            .flatMap { it.ast() }
            .getOrHandle { throw it }

        val extractor = Extractor(file.toFile(), Charset.defaultCharset())
        fun bodyPrinter(tl : TopLevel) =
            extractor
                .rhsOf(tl)
                .map { Writer { output -> output.print(it) }}

        AstWriters { bodyPrinter(it) }
            .print(transform(ast))
            .getOrHandle { throw it }
            .write(Printer.of(s))

        return s.toString()
    }

    @Test
    fun main() {
        val input = """
            int main(int argc, char* argv[]) {
                int x;
            }
        """.trimIndent()

        assertEquals(input, literal(input))

    }

    @Test
    fun short() {
        val input = """
            int f(short s) {}
        """.trimIndent()

        assertEquals(input, literal(input))
    }

    @Test
    fun longlongPointer() {
        val input = """
            int f(long long* l) {}
        """.trimIndent()

        assertEquals(input, literal(input))
    }

    @Test
    fun varWithoutRhs() {
        val input = """
            int x;
        """.trimIndent()

        assertEquals(input, literal(input))
    }

    @Test
    fun varWithRhs() {
        val input = """
            int x = 42;
        """.trimIndent()

        assertEquals(input, literal(input))
    }


    @Test
    fun constSizeArray() {
        val input = """
            int xs[1] = { 42 };
            int ys[1][2];
            int zs[1][2][3];
            int* zs[1][2][3];
        """.trimIndent()

        assertEquals(input, literal(input))
    }

    @Test
    fun arrayReturnType() {
        val input = """
            int* f();
            int** g();
            int*** h();
        """.trimIndent()

        assertEquals(input, literal(input));

        assertEquals(
            2,
            literal(input) { ast ->
                TranslationUnit(ast.decls.filter { it.name != "g" })
            }.lines().size
        )
    }
}