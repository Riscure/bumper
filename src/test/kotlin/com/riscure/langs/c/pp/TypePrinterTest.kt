package com.riscure.langs.c.pp

import arrow.core.*
import com.riscure.langs.c.ast.*
import com.riscure.langs.c.parser.clang.ClangParser
import kotlin.test.*
import java.io.StringWriter
import java.nio.file.Path
import kotlin.io.path.writeText

class TypePrinterTest {

    fun parse(input: String): TopLevel.Typedef {
        val file = kotlin.io.path.createTempFile(suffix = ".c").apply { writeText(input) }.toFile()

        return try {
            // create the parser
            val parser = ClangParser()

            // parse the input
            val ast = parser.parse(file)
                .flatMap { it.ast() }
                .getOrHandle { throw it }

            // get the typedef
            assertEquals(1, ast.decls.size)
            assertIs(ast.decls[0])
        } finally {
            file.delete()
        }
    }

    fun roundtrip(input: String) {
        println("Roundtrip test for: ${input}")
        val typedef1 = parse(input)
        val typedef2 = parse("${Pretty.typedef(typedef1)};")
            .withMeta(typedef1.meta) // not the same

        assertEquals(typedef1, typedef2)
    }

    /* function type */
    @Test
    fun test001() = roundtrip("""
    typedef void f();
    """.trimIndent())

    /* function type returning void pointer */
    @Test
    fun test002() = roundtrip("""
    typedef void *h();
    """.trimIndent())

    /* function pointer with two anonymous arguments*/
    @Test
    fun test003() = roundtrip("""
    typedef void (*g)(int,int);
    """.trimIndent())

   /* function pointer */
   @Test
   fun test004() = roundtrip("""
   typedef void (*f0)();
   """.trimIndent())

   /* array of function pointers */
   @Test
   fun test005() = roundtrip("""
   typedef void (*f1[])();
   """.trimIndent())

    /* pointer ot array of function pointers */
    @Test
    fun test006() = roundtrip("""
    typedef void (*(*f2[1]))();
    """.trimIndent())

    @Test
    fun test007() = roundtrip("""
    typedef void (**f2_alt[1])();
    """.trimIndent())

    /* 2D array of function pointers */
    @Test
    fun test008() = roundtrip("""
    typedef void (*f3[1][2])();
    """.trimIndent())

    /* pointer to 2D array of function pointers */
    @Test
    fun test009() = roundtrip("""
    typedef void (*(*f4[1][2]))();
    """.trimIndent())

    @Test
    fun test010() = roundtrip("""
    typedef void (**f4_alt[1][2])();
    """.trimIndent())

    /* array of function pointers that return an int pointer */
    @Test
    fun test011() = roundtrip("""
    typedef int* (*f5[])();
    """.trimIndent())

    /* function pointer that takes function pointer as argument */
    @Test
    fun test012() = roundtrip("""
    typedef void (*f6)(int (*f)());
    """.trimIndent())

    /* Type of a function with no parameters, returning a pointer to a function with two parameters, returning an int */
    @Test
    fun test013() = roundtrip("""
    typedef int (*broWatVoid())(int, int);
    """.trimIndent())

    /* Same with void argument list indicating no arguments */
    @Test
    fun test014() = roundtrip("""
    typedef int (*broWatVoid(void))(int, int);
    """.trimIndent())

    /* Same with extra parens */
    @Test
    fun test015() = roundtrip("""
    typedef int (*(broWatVoid(void)))(int, int);
    """.trimIndent())

    /* Type of a function with no parameters,
     * returning a pointer to a function with no parameters,
     * returning a pointer to a function with no parameters
     * returning int (this is the last one I promise)
     **/
    @Test
    fun wat() = roundtrip("""
    typedef int (*(*(broWatVoid(void)))(void))(void);
    """.trimIndent())
}