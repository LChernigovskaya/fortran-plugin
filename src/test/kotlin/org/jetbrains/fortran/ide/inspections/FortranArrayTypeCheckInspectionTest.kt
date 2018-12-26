package org.jetbrains.fortran.ide.inspections

class FortranArrayTypeCheckInspectionTest()
    : FortranInspectionsBaseTestCase(FortranTypeCheckInspection()) {

    fun testDeclarationIntegerArrayWithDimensionAttribute() = checkByText("""
        program p
        integer, dimension(3) :: a = (/1, 2, 3/)
        end program
    """, true)

    fun testAssignmentIntegerArray() = checkByText("""
        program p
        integer, dimension(3) :: a
        a = (/1, 2, 3/)
        end program
    """, true)

    fun testDeclarationRealToIntegerArray() = checkByText("""
        program p
        integer, dimension(3) :: a
        a = <warning descr="mismatched typesexpected `integer array of shape (1:3)`, found `real array of shape (1:3)`">(/1, 2, 3.1/)</warning>
        end program
    """, true)

    fun testAssignmentIntegerToRealArray() = checkByText("""
        program p
        real, dimension(3) :: a
        a = (/1, 2, 3/)
        end program
    """, true)

    fun testAssignmentMalformedArrayConstructorToCharacterArray() = checkByText("""
        program p
        character, dimension(3) :: a
        a = (/"a", <warning descr="mismatched array constructor element typearray base type: character, element type: integer">2</warning>, "c"/)
        end program
    """, true)

    fun testAssignmentCorrectImplicitDo() = checkByText("""
        program p
        integer, dimension(3) :: a
        a = (/(I, I = 4, 6)/)
        end program
    """, true)

    fun testAssignmentWrongArraySize() = checkByText("""
        program p
        logical, dimension(3) :: a
        a = <warning descr="mismatched typesexpected `logical array of shape (1:3)`, found `logical array of shape (1:2)`">(/.true., .false./)</warning>
        end program
    """, true)

    fun testDoublePrecisionArray() = checkByText("""
        program p
        double precision, dimension(3) :: a
        a = (/1, 1.0, 1.0D0/)
        a = <warning descr="mismatched typesexpected `double precision array of shape (1:3)`, found `logical array of shape (1:3)`">(/.true., .false., .true./)</warning>
        end program
    """, true)

    // TODO incorrect implicit do test

    fun testDeclarationIntegerArrayWithDimensionOperator() = checkByText("""
        program p
        integer :: a
        dimension a(3)
        a = (/1, 2, 3/)
        end program
    """, true)

    fun testDeclarationIntegerArray() = checkByText("""
        program p
        integer a(1:3)
        a = (/1, 2, 3/)
        end program
    """, true)

    fun testDeclarationSomeArrays() = checkByText("""
        program p
        integer a(3)
        integer c
        dimension c(3)
        a = (/1, 2, 3/)
        c = (/1, 2, 3/)
        end program
    """, true)

    fun testInitializationArrayWithCyclicList() = checkByText("""
        program p
        integer :: i, a(4) = (/ 1, (4, i = 2, 4) /)
        a = (/1, 2, 3, 4/)
        end program
    """, true)

    fun testDeclarationRealArrayWithAllocatableAttribute() = checkByText("""
        program p
        real, allocatable :: a(:)
        a = (/1.1, 2.2, 3.2/)
        end program
    """, true)

    fun testDeclarationRealArrayWithAllocatableOperator() = checkByText("""
        program p
        real a
        allocatable a(:)
        a = (/1.1, 2.2, 3.2/)
        end program
    """, true)

    fun testDeclarationRealArrayWithPointerAttribute() = checkByText("""
        program p
        real, pointer :: a(:)
        a = (/1.1, 2.2, 3.2/)
        end program
    """, true)

    fun testDeclarationRealArrayWithPointerOperator() = checkByText("""
        program p
        real a
        pointer a(:)
        a = (/1.1, 2.2, 3.2/)
        end program
    """, true)

    fun testInitialization2DimensionalArrayWithData() = checkByText("""
        program p
        integer b(2, 4)
        data ((b(i, j), j = 1, 4), i = 1, 2) /1, -1, 2, -2, 3, -3, 4, -4/
        end program
    """, true)

    fun testTwoInitialization() = checkByText("""
        program p
        integer, dimension(2) :: a(3)
        a = <warning descr="mismatched typesexpected `integer array of shape (1:2) array of shape (1:3)`, found `integer array of shape (1:2)`">(/1, 2/)</warning>
        end program
    """, true)

    fun testAssignmentRealValueToIntegerArray() = checkByText("""
        program p
        real a(1:3)
        a = (/1.1, 2.1, 3.0/)
        a(1) = 0.5
        end program
    """, true)
}