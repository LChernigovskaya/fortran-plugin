package org.jetbrains.fortran.ide.inspections

class FortranProgramUnitNameTest()
    : FortranInspectionsBaseTestCase(FortranProgramUnitNameMismatchInspection()) {

    fun testIfConstruct() = checkByText("""
        program myProgram
            write(*,*) myFunction()
        contains
            function myFunction()
                myFunction = 1
            end function myFunction
        end program <error descr="Program unit name mismatch">myFunction</error>
    """)

    fun testFix() = checkFixByText("Program unit name fix", """
        program myProgram
            write(*,*) "!"
        end program <error descr="Program unit name mismatch">myFunction<caret></error>
        """, """
        program myProgram
            write(*,*) "!"
        end program myProgram
        """)
}