package org.jetbrains.fortran.lang.resolve.ref

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.fortran.lang.psi.*
import org.jetbrains.fortran.lang.psi.ext.FortranNamedElement
import org.jetbrains.fortran.lang.psi.mixin.FortranDataPathImplMixin
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.fortran.FortranFileType
import org.jetbrains.fortran.FortranFixedFormFileType
import org.jetbrains.fortran.lang.psi.ext.FortranEntitiesOwner
import org.jetbrains.fortran.lang.psi.mixin.FortranSubModuleImplMixin

class FortranPathReferenceImpl(element: FortranDataPathImplMixin) :
        FortranReferenceBase<FortranDataPathImplMixin>(element), FortranReference {

    override val FortranDataPathImplMixin.referenceAnchor: PsiElement get() = referenceNameElement

    override fun getVariants(): Array<Any> = emptyArray()

    override fun isReferenceTo(element: PsiElement?): Boolean {
     /*   val stmt = element?.parent
        val file = stmt?.parent?.parent
        if (stmt is FortranNameStmtImpl && (file is FortranFile || file is FortranFixedFormFile) ) {
            return true
        }*/
        return super.isReferenceTo(element)
    }

    override fun resolveInner(): List<FortranNamedElement> {
        // module rename
        val useStmt = PsiTreeUtil.getParentOfType(element, FortranUseStmt::class.java)
        if (useStmt != null && element.parent is FortranRenameStmt) {
            return resolveModuleRename(useStmt)
        }

        val programUnit = PsiTreeUtil.getParentOfType(element, FortranProgramUnit::class.java) ?: return emptyList()
        // types should be done first of all
        if (element is FortranTypeName) {
            return resolveTypes(programUnit)
        }

        // inside interface
        val interfaceBody = PsiTreeUtil.getParentOfType(element, FortranInterfaceBody::class.java)
        if (interfaceBody != null) {
            return collectNamesInInterfaceBody(interfaceBody)
        }

        // resolve paths like a%b%c
        if (element.firstChild is FortranDataPath)
        {
            return resolveDifficultPath()
        }

        return resolveName(programUnit)
    }

  // private methods which a parts of the resolveInner
    private fun resolveModuleRename(useStmt: FortranUseStmt) =
          useStmt.dataPath!!.reference.multiResolve().filterNotNull()
          .map { PsiTreeUtil.getParentOfType(it, FortranModule::class.java) }.filterNotNull()
          .flatMap { findNamePsiInModule(it, mutableSetOf(), false) }.toList()


    private fun resolveTypes(programUnit: FortranProgramUnit) : List<FortranNamedElement> {
        val types = programUnit.types.filter { element.referenceName.equals(it.name, true) }
                .toMutableSet()
        val outerProgramUnit : FortranProgramUnit
        // if we are internal program unit
        if (programUnit.parent is FortranModuleSubprogramPart
                || programUnit.parent is FortranInternalSubprogramPart) {
            outerProgramUnit = PsiTreeUtil.getParentOfType(programUnit, FortranProgramUnit::class.java) ?: programUnit
            types.addAll(outerProgramUnit.types.filter { element.referenceName.equals(it.name, true) })
        } else {
            outerProgramUnit = programUnit
        }

        types.addAll(collectRenamedNames(programUnit).filter { element.referenceName.equals(it.name, true) })

        // modules
        if (element.parent !is FortranUseStmt) { // We do not need recursion here
            val allModules = collectAllUseStmts(programUnit, outerProgramUnit)
            for (module in allModules) {
                types.addAll(importNamesFromModule(module, mutableSetOf(), true).toList())
            }
        }

        // submodules
        if (outerProgramUnit is FortranModule) {
            types.addAll(findSubModulesInProjectFiles(outerProgramUnit.name, null).filterNotNull()
                    .flatMap { findNamePsiInModule(it, mutableSetOf(), true) }.toList())
        } else if (outerProgramUnit is FortranSubModuleImplMixin && outerProgramUnit.name?.count { it == ':' } == 1) {
            types.addAll(findSubModulesInProjectFiles(outerProgramUnit.getModuleName(), outerProgramUnit.getPersonalName()).filterNotNull()
                    .flatMap { findNamePsiInModule(it, mutableSetOf(), true) }.toList())
        }
        return types.toList()
    }

    private fun resolveDifficultPath() : List<FortranNamedElement> {
        val innerPart = (element.firstChild.reference as FortranPathReferenceImpl).multiResolve().filterNotNull()

        val innerPartTypeStmt = innerPart.map { PsiTreeUtil.getParentOfType(it, FortranTypeDeclarationStmt::class.java)
                ?: PsiTreeUtil.getParentOfType(it, FortranDataComponentDefStmt::class.java) }.firstOrNull()

        val innerType = if (innerPartTypeStmt is FortranTypeDeclarationStmt) {
            innerPartTypeStmt.derivedTypeSpec?.typeName
        } else if (innerPartTypeStmt is FortranDataComponentDefStmt){
            innerPartTypeStmt.derivedTypeSpec?.typeName
        } else null

        if (innerType == null) {
            return emptyList()
        } else {
            // we should find type declaration. If it is in use -> rename we'll search for the origin name (several times)
            var type = innerType.reference.multiResolve().firstOrNull()
            while (type?.parent is FortranRenameStmt) {
                type = (type.parent as FortranRenameStmt).dataPath?.reference?.multiResolve()?.firstOrNull()
            }

            return PsiTreeUtil.getParentOfType(type, FortranDerivedTypeDef::class.java)?.variables
                    ?.filter { element.referenceName.equals(it.name, true) }?.toMutableList() ?: emptyList()
        }
    }

    private fun resolveName(programUnit: FortranProgramUnit) : List<FortranNamedElement> {
        val outerProgramUnit : FortranProgramUnit
        // local variables
        val names = programUnit.variables.filter { element.referenceName.equals(it.name, true) }
                .toMutableSet()
        if (element.referenceName.equals(programUnit.unit?.name, true) ) names.add(programUnit.unit as FortranNamedElement)
        // interfaces
        names.addAll(PsiTreeUtil.getStubChildrenOfTypeAsList(PsiTreeUtil.getStubChildOfType(programUnit, FortranBlock::class.java), FortranInterfaceBlock::class.java)
              .flatMap{ (it as FortranInterfaceBlock).subprograms.filter{ element.referenceName.equals(it.name, true) } })
        // enums
        names.addAll(PsiTreeUtil.getStubChildrenOfTypeAsList(PsiTreeUtil.getStubChildOfType(programUnit, FortranBlock::class.java), FortranEnumDef::class.java)
                .flatMap { (it as FortranEnumDef).variables.filter{ element.referenceName.equals(it.name, true) } })

        // if we are real program unit
        if (programUnit.parent !is FortranModuleSubprogramPart
                && programUnit.parent !is FortranInternalSubprogramPart) {
            outerProgramUnit = programUnit
            names.addAll(programUnit.subprograms.filter { element.referenceName.equals(it.name, true) })
        } else {
            outerProgramUnit = PsiTreeUtil.getParentOfType(programUnit, FortranProgramUnit::class.java) ?: programUnit
            names.addAll(outerProgramUnit.variables.filter { element.referenceName.equals(it.name, true) })
            names.addAll(outerProgramUnit.subprograms.filter { element.referenceName.equals(it.name, true) })
            // interfaces
            names.addAll(PsiTreeUtil.getStubChildrenOfTypeAsList(PsiTreeUtil.getStubChildOfType(outerProgramUnit, FortranBlock::class.java), FortranInterfaceBlock::class.java)
                    .flatMap{ (it as FortranInterfaceBlock).subprograms.filter{ element.referenceName.equals(it.name, true) } })
            // enums
            names.addAll(PsiTreeUtil.getStubChildrenOfTypeAsList(PsiTreeUtil.getStubChildOfType(outerProgramUnit, FortranBlock::class.java), FortranEnumDef::class.java)
                    .flatMap { (it as FortranEnumDef).variables.filter{ element.referenceName.equals(it.name, true) } })
        }

        names.addAll(resolveInProjectFiles())
        names.addAll(collectRenamedNames(programUnit).filter { element.referenceName.equals(it.name, true) })

        // modules
        if (element.parent !is FortranUseStmt) { // We do not need recursion here
            val allModules = collectAllUseStmts(programUnit, outerProgramUnit)
            for (module in allModules) {
                names.addAll(importNamesFromModule(module, mutableSetOf(), false).toList())
            }
        }

        // submodules
        if (outerProgramUnit is FortranModule) {
            names.addAll(findSubModulesInProjectFiles(outerProgramUnit.name, null).filterNotNull()
                    .flatMap { findNamePsiInModule(it, mutableSetOf(), false) }.toList())
        } else if (outerProgramUnit is FortranSubModuleImplMixin && outerProgramUnit.name?.count { it == ':' } == 1) {
            names.addAll(findSubModulesInProjectFiles(outerProgramUnit.getModuleName(), outerProgramUnit.getPersonalName()).filterNotNull()
                    .flatMap { findNamePsiInModule(it, mutableSetOf(), false) }.toList())
        }

        // let's check interfaces here. Maybe some declarations are excessive
        if (names.any{ it.parent is FortranInterfaceStmt }) {
            names.removeIf { it.parent !is FortranInterfaceStmt }
        }

        // let's try to live without real declaration
        if (names.isEmpty()) {
            val implicitStmts = PsiTreeUtil.findChildrenOfType(programUnit, FortranImplicitStmt::class.java)
            if (implicitStmts.isEmpty() || implicitStmts.all { !it.implicitSpecList.isEmpty()}) {
                val firstUsage = PsiTreeUtil.findChildrenOfType(outerProgramUnit.firstChild, FortranDataPath::class.java)
                        .filterImplicitDeclaration() ?:
                        PsiTreeUtil.findChildrenOfType(PsiTreeUtil.findChildOfType(outerProgramUnit, FortranBlock::class.java ), FortranDataPath::class.java)
                        .filterImplicitDeclaration() ?:
                        PsiTreeUtil.findChildrenOfType(programUnit, FortranDataPath::class.java)
                        .filterImplicitDeclaration()
                if (firstUsage != null) {
                    names.add(firstUsage)
                }
            }
        }
        return names.toList()
    }

    // global search
    private fun resolveInProjectFiles() : MutableSet<FortranNamedElement> {
        val result : MutableSet<FortranNamedElement> = mutableSetOf()

        val vFiles = collectAllProjectFiles()
        for (file in vFiles) {
            val psiFile = PsiManager.getInstance(element.project).findFile(file)
            val programUnits = programUnitsFromFile(psiFile).filter { it.name.equals(element.referenceName, true) }
            result.addAll(programUnits.map { it.unit }.filterNotNull())
            result.addAll(programUnits.filterIsInstance(FortranFunctionSubprogram::class.java)
                    .flatMap { f -> f.variables.filter { element.referenceName.equals(it.name, true) } }
                    .filterNotNull())
        }
        return result
    }

    private fun findSubModulesInProjectFiles(moduleName : String?, subModuleName : String?) : MutableSet<FortranSubmodule> {
        val result : MutableSet<FortranSubmodule> = mutableSetOf()
        if (moduleName == null) return result
        val vFiles = collectAllProjectFiles()

        vFiles.map { programUnitsFromFile(PsiManager.getInstance(element.project).findFile(it)) }
                .forEach { programUnits -> result.addAll(
                        programUnits.filterIsInstance(FortranSubModuleImplMixin::class.java)
                                .filter {
                                    moduleName.equals(it.getModuleName(), true)
                                            && (subModuleName == null || it.getSubModuleName().equals(subModuleName, true))
                        }.filterNotNull()
                )}
        return result
    }

    fun findNamePsiInModule(module : FortranProgramUnit?, allSeenModules : MutableSet<String>,
                          onlyTypes : Boolean) : MutableSet<FortranNamedElement> {
        if (module == null || module.name == null || (module !is FortranModule && module !is FortranSubmodule)) {
            return mutableSetOf()
        }
        // loop check
        if (allSeenModules.contains(module.name!!.toLowerCase())) return mutableSetOf()
        allSeenModules.add(module.name!!.toLowerCase())

        val allNames: MutableSet<FortranNamedElement> = mutableSetOf()

        val allModules = collectAllUseStmts(module)
        allModules.forEach{ allNames.addAll(importNamesFromModule(it, allSeenModules, onlyTypes))}
        allNames.addAll(collectRenamedNames(module).filter { element.referenceName.equals(it.name, true) })


        if (!onlyTypes) {
            allNames.addAll(module.subprograms.filter { element.referenceName.equals(it.name, true) })
        }
        allNames.addAll(module.types.filter { element.referenceName.equals(it.name, true) }
                .plus(module.variables.filter { element.referenceName.equals(it.name, true) } )
                .plus(PsiTreeUtil.findChildrenOfType(PsiTreeUtil.findChildOfType(module, FortranBlock::class.java),
                        FortranInterfaceBlock::class.java )
                        .flatMap { it.subprograms.filter { element.referenceName.equals(it.name, true) } })
                .plus(PsiTreeUtil.findChildrenOfType( if (module is FortranModule) module.block else (module as FortranSubmodule).block,
                        FortranEnumDef::class.java).flatMap { (it as FortranEnumDef).variables.filter{ element.referenceName.equals(it.name, true) } }))
        // interfaces
        allNames.addAll(PsiTreeUtil.getStubChildrenOfTypeAsList(PsiTreeUtil.getStubChildOfType(module, FortranBlock::class.java), FortranInterfaceBlock::class.java)
                .flatMap{ (it as FortranInterfaceBlock).subprograms.filter{ element.referenceName.equals(it.name, true) } })
        // enums
        allNames.addAll(PsiTreeUtil.getStubChildrenOfTypeAsList(PsiTreeUtil.getStubChildOfType(module, FortranBlock::class.java), FortranEnumDef::class.java)
                .flatMap { (it as FortranEnumDef).variables.filter{ element.referenceName.equals(it.name, true) } })


        // submodules
        if (module is FortranModule) {
            allNames.addAll(findSubModulesInProjectFiles(module.name, null).filterNotNull()
                    .flatMap { findNamePsiInModule(it, mutableSetOf(), onlyTypes) }.toList())
        } else if ((module as FortranSubModuleImplMixin).name?.count { it == ':' } == 1){
            allNames.addAll(findSubModulesInProjectFiles(module.getModuleName(), module.getPersonalName())
                    .filterNotNull().flatMap { findNamePsiInModule(it, mutableSetOf(), onlyTypes) }.toList())
        }
        allSeenModules.remove(module.name!!.toLowerCase())
        return allNames
    }

    // util methods
    fun collectAllUseStmts(programUnit: FortranProgramUnit, outerProgramUnit: FortranProgramUnit)
            : MutableSet<FortranDataPath> {
        val allModules = outerProgramUnit.usedModules.toMutableSet()
        if (programUnit != outerProgramUnit) allModules.addAll(programUnit.usedModules)
        return allModules
    }

    fun collectAllUseStmts(programUnit: FortranProgramUnit?) : MutableSet<FortranDataPath> {
        return if (programUnit != null) collectAllUseStmts(programUnit, programUnit) else emptySet<FortranDataPath>().toMutableSet()
    }


    fun collectAllProjectFiles() = FileTypeIndex.getFiles(FortranFileType, GlobalSearchScope.projectScope(element.project))
        .plus(FileTypeIndex.getFiles(FortranFixedFormFileType, GlobalSearchScope.projectScope(element.project)))

    fun programUnitsFromFile(psiFile : PsiFile?) = if (psiFile is FortranFile) {
        psiFile.programUnits
    } else {
        (psiFile as? FortranFixedFormFile)?.programUnits ?: emptyArray()
    }

    fun collectRenamedNames(programUnit : FortranProgramUnit) : MutableSet<FortranNamedElement> {
        val useStmts = PsiTreeUtil.getStubChildrenOfTypeAsList(
                PsiTreeUtil.getChildOfType(programUnit, FortranBlock::class.java),
                FortranUseStmt::class.java)
        return useStmts.flatMap { PsiTreeUtil.getStubChildrenOfTypeAsList(it, FortranRenameStmt::class.java) }
                .map { it.entityDecl }.filterNotNull()
        .plus(useStmts.flatMap { PsiTreeUtil.getStubChildrenOfTypeAsList(it, FortranOnlyStmt::class.java) }
                .flatMap { PsiTreeUtil.getStubChildrenOfTypeAsList(it, FortranRenameStmt::class.java) }
                .map { it.entityDecl }.filterNotNull()).toMutableSet()
    }

    fun importNamesFromModule(module : FortranDataPath, allSeenModules : MutableSet<String>,
                               onlyTypes : Boolean ) : MutableSet<FortranNamedElement> {
        val result: MutableSet<FortranNamedElement> = mutableSetOf()

            val onlyIsUsed = !(module.parent as FortranUseStmt).onlyStmtList.isEmpty()
            val onlyList = (module.parent as FortranUseStmt).onlyStmtList
                    .map { it.entityDecl?.name?.toLowerCase() }.filterNotNull()
            val renameList = (module.parent as FortranUseStmt).renameStmtList
                    .plus((module.parent as FortranUseStmt).onlyStmtList.map { it.renameStmt })
                    .map { it?.dataPath?.referenceName?.toLowerCase() }.filterNotNull()

            result.addAll(module.reference.multiResolve().flatMap {
                findNamePsiInModule(PsiTreeUtil.getParentOfType(it, FortranModule::class.java), allSeenModules, onlyTypes)
            }.filter {
                element.referenceName.equals(it.name, true)
                        && element.referenceName.toLowerCase() !in renameList
                        && (!onlyIsUsed || element.referenceName.toLowerCase() in onlyList)
            })


        return result
    }

    fun collectNamesInInterfaceBody(body : FortranInterfaceBody) : List<FortranNamedElement> {
        return PsiTreeUtil.getStubChildrenOfTypeAsList(body.block, FortranTypeDeclarationStmt::class.java)
                .flatMap { PsiTreeUtil.getStubChildrenOfTypeAsList(it, FortranEntityDecl::class.java) }
                .plus(PsiTreeUtil.getStubChildrenOfTypeAsList(body.functionStmt, FortranEntityDecl::class.java))
                .plus(PsiTreeUtil.getStubChildrenOfTypeAsList(body.subroutineStmt, FortranEntityDecl::class.java))
                .filter{ element.referenceName.equals(it.name, true) }
    }

    fun <T : FortranDataPath> kotlin.collections.Iterable<T>.filterImplicitDeclaration(): T? =
        filter{ PsiTreeUtil.getParentOfType(it, FortranEntitiesOwner::class.java) is FortranProgramUnit }
        .filter { it.name?.equals(element.name, true) ?: false }
        .filter { (it as FortranDataPath).firstChild !is FortranDataPath }
        .toMutableList().firstOrNull()

}


