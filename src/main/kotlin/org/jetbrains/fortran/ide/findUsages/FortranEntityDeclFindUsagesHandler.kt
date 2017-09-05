package org.jetbrains.fortran.ide.findUsages

import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.application.runReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import org.jetbrains.fortran.lang.psi.*

class FortranEntityDeclFindUsagesHandler (
        element: FortranEntityDecl,
        factory: FortranFindUsagesHandlerFactory
) : FortranFindUsagesHandler<FortranEntityDecl>(element, factory) {

    override fun createSearcher(element: PsiElement, processor: Processor<UsageInfo>, options: FindUsagesOptions): Searcher {

        var scope : SearchScope = options.searchScope
        val unit = runReadAction{PsiTreeUtil.getParentOfType(element, FortranProgramUnit::class.java)}
        val inter = runReadAction{PsiTreeUtil.getParentOfType(element, FortranInterfaceBody::class.java)}
        if ((runReadAction{ element.parent !is FortranNameStmt } && (unit !is FortranModule)
                && (unit !is FortranSubmodule)) || inter != null) {
            scope = runReadAction { GlobalSearchScope.fileScope(element.containingFile) }
        }
        options.searchScope = scope

        return object: Searcher(element, processor, options) {
            override fun buildTaskList(): Boolean {
                addTask {
                    ReferencesSearch.search(element, options.searchScope).all { processUsage(processor, it) }
                }

                return true
            }
        }
    }
}