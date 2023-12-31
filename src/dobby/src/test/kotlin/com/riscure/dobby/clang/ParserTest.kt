package com.riscure.dobby.clang

import arrow.core.*
import kotlin.test.*

internal class ParserTest {
    @Test
    fun optimizationToggle01() {
        val O0 = ClangParser.parseArguments(listOf("-O0"))
        assertTrue(O0.isRight())

        val args = (O0 as Either.Right).value.optArgs
        assertEquals(1, args.size)
        assertEquals("O0", args[0].opt.name)
    }

    @Test
    fun optimizationToggle02() {
        val line = ClangParser.parseArguments(listOf("-O", "1"))
        assertTrue(line.isRight())

        val cmd = (line as Either.Right).value
        assertEquals(listOf("O"), cmd.optArgs.map { it.opt.name })
        cmd.optArgs.map { it.values }.forEach { assertTrue(it[0].isEmpty()) }
        assertEquals(listOf("1"), cmd.positionalArgs)
    }

    @Test
    fun optimizationToggle03() {
        val O1 = ClangParser.parseArguments(listOf("-O=1"))
        assertTrue(O1.isRight())

        val args = (O1 as Either.Right).value.optArgs
        assertEquals(1, args.size)
        assertEquals("O", args[0].opt.name)

        // this is not a valid value for the option,
        // but the point is that we don't interpret the =,
        // because neither does clang for this option.
        assertEquals(listOf("=1"), args[0].values)
    }

    @Test
    fun xargJoinedAndSep() {
        val xarch = ClangParser.parseArguments(listOf("-Xarch_x", "y"))
        assertTrue(xarch.isRight())

        val args = (xarch as Either.Right).value.optArgs
        assertEquals(1, args.size)
        assertEquals("Xarch_", args[0].opt.name)
        assertEquals(listOf("x", "y"), args[0].values)
    }

    @Test
    fun mcpu() {
        val mcpu = ClangParser.parseArguments(listOf("-mcpu=hexagonv5"))
        assertTrue(mcpu.isRight())

        val args = (mcpu as Either.Right).value.optArgs
        assertEquals(1, args.size)
        assertEquals("mcpu=", args[0].opt.name)
        assertEquals(listOf("hexagonv5"), args[0].values)
    }

    @Test
    fun mcpuSeparate() {
        val ugh = ClangParser.parseArguments(listOf("-mcpu", "hexagonv5"))
        assertTrue(ugh.isLeft())
    }

    @Test
    fun objcmt() {
        val objcmt = ClangParser.parseArguments(listOf("-objcmt-migrate-literals"))
        assertTrue(objcmt.isRight())

        val cmd = (objcmt as Either.Right).value
        assertEquals("objcmt-migrate-literals", cmd.optArgs[0].opt.name)

        val o = ClangParser.parseArguments(listOf("-objcmt-migrate-literal"))
        assertTrue(o.isRight())
        val cmd2 = (o as Either.Right).value
        assertEquals("o", cmd2.optArgs[0].opt.name)
        assertEquals(listOf("bjcmt-migrate-literal"), cmd2.optArgs[0].values)
    }

    @Test
    fun xclang() {
        val result = ClangParser.parseArguments(listOf("-Xclang", "-load", "-Xclang", "/some/path.so" ))
        val cmd = assertIs<Either.Right<Command>>(result).value
        assertEquals(2, cmd.optArgs.size)
    }

    //--------------------------------------------------
    // some tickets that we want to be fixed using dobby
    //--------------------------------------------------

    // https://jira.riscure.com/browse/TC-2572
    @Test
    fun tc2572() {
        val args = listOf("-c", "-o", "src/ArrayBoundaries.o", "-mv5", "/home/arjen/true-code/Demo/codebase/ArrayBoundaries.c")
        val result = ClangParser.parseArguments(args)

        assertTrue(result.isRight())
        val cmd = (result as Either.Right).value

        assertEquals(3, cmd.optArgs.size)
        assertEquals(1, cmd.positionalArgs.size)
    }

    // https://jira.riscure.com/browse/TC-2574
    @Test
    fun tc2574() {
        val args = listOf(
            "-c", "--output", "src/ArrayBoundaries.o", "--prefix", "wolla",
            "-sectcreate", "<arg1>", "<arg2>", "<arg3>", "src/ArrayBoundaries.c")

        val result = ClangParser.parseArguments(args)

        assertTrue(result.isRight())
        val cmd = (result as Either.Right).value

        assertEquals(4, cmd.optArgs.size)
        assertEquals(1, cmd.positionalArgs.size)

        val sectcreate = cmd.optArgs[3]
        assertEquals(listOf("<arg1>", "<arg2>", "<arg3>"), sectcreate.values)
    }

}