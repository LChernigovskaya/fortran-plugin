package org.jetbrains.fortran.ide.findUsages

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import org.jetbrains.fortran.lang.lexer.FortranLexer
import org.jetbrains.fortran.lang.psi.FortranConstructNameDecl
import org.jetbrains.fortran.lang.psi.FortranLabelDecl
import org.jetbrains.fortran.lang.psi.FortranTokenType
import org.jetbrains.fortran.lang.psi.ext.FortranNamedElement
import org.jetbrains.fortran.lang.psi.impl.FortranConstructNameDeclImplMixin


abstract class BaseFortranFindUsagesProvider : FindUsagesProvider {
    protected abstract val isFixedFormFortran: Boolean

    override fun getWordsScanner(): WordsScanner? = null

    override fun canFindUsagesFor(psiElement: PsiElement): Boolean {
        return psiElement is FortranNamedElement
    }

    override fun getHelpId(psiElement: PsiElement): String? {
        return null
    }

    override fun getType(element: PsiElement): String {
        if (element is FortranLabelDecl) {
            return "Fortran numerical label"
        } else if (element is FortranConstructNameDecl) {
            return "Construct name"
        } else {
            return ""
        }
    }

    override fun getDescriptiveName(element: PsiElement): String {
        if (element is FortranLabelDecl) {
            return element.text
        } else if (element is FortranConstructNameDeclImplMixin) {
            return element.gelLabelValue()
        } else {
            return ""
        }
    }

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String {
        if (element is FortranLabelDecl) {
            return element.parent.text
        } else if (element is FortranConstructNameDeclImplMixin) {
            return element.parent.text
        } else {
            return ""
        }
    }
}