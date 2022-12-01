package com.riscure.bumper.libclang

import org.junit.jupiter.api.DisplayName
import kotlin.test.*

class ClangDependencyAnalysisTest: LibclangTestBase() {

    @DisplayName("Function with struct local")
    @Test
    fun test00() = bumped("""
        struct S {};
        void f() {
          struct S s;
        }
    """.trimIndent()) { ast, unit ->
        val f = assertNotNull(ast.functions[0])
        val struct = assertNotNull(ast.structs[0])

        val deps = unit.dependencies.ofDecl(f).assertOK()

        assertEquals(1, deps.size)
        assertEquals(struct.mkSymbol(ast.tuid), deps.first())
    }

    @DisplayName("Function with struct parameter")
    @Test
    fun test01() = bumped("""
        struct S {};
        void f(struct S s) {}
    """.trimIndent()) { ast, unit ->
        val f = assertNotNull(ast.functions[0])
        val struct = assertNotNull(ast.structs[0])

        val deps = unit.dependencies.ofDecl(f).assertOK()

        assertEquals(1, deps.size)
        assertEquals(struct.mkSymbol(ast.tuid), deps.first())
    }

    @DisplayName("Function with elaborated and nested struct local")
    @Test
    fun test02() = bumped("""
        struct T {};
        void f(struct S { struct T t; } s) {}
    """.trimIndent()) { ast, unit ->
        val f = assertNotNull(ast.functions[0])
        val struct_S = assertNotNull(ast.structs.find { it.ident == "S" })
        val struct_T = assertNotNull(ast.structs.find { it.ident == "T" })

        val deps1 = unit.dependencies.ofDecl(f).assertOK()
        assertEquals(1, deps1.size)
        assertContains(deps1, struct_S.mkSymbol(ast.tuid))

        val deps2 = unit.dependencies.ofDecl(struct_S).assertOK()
        assertEquals(1, deps2.size)
        assertContains(deps2, struct_T.mkSymbol(ast.tuid))
    }

    @DisplayName("Function with local var of typedeffed type")
    @Test
    fun test04() = bumped("""
        typedef int MyInt;
        void f() {
          while (0) {
            MyInt i;
          }
        }
    """.trimIndent()) { ast, unit ->
        val f = assertNotNull(ast.functions[0])
        val myInt = assertNotNull(ast.typedefs.find { it.ident == "MyInt" })

        val deps1 = unit.dependencies.ofDecl(f).assertOK()
        assertEquals(1, deps1.size)
        assertContains(deps1, myInt.mkSymbol(ast.tuid))
    }
}