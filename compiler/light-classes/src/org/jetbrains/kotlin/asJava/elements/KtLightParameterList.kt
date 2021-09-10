/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.asJava.elements

import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiParameterList
import org.jetbrains.kotlin.asJava.classes.lazyPub
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

class KtLightParameterList(
    private val parent: KtLightMethod,
    private val parametersCount: Int,
    computeParameters: () -> List<PsiParameter>
) : KtLightElementBase(parent), PsiParameterList {

    override val kotlinOrigin: KtElement? get() = parent.kotlinOrigin.safeAs<KtFunction>()?.valueParameterList

    private val _parameters: Array<PsiParameter> by lazyPub { computeParameters().toTypedArray() }
    override fun getParameters() = _parameters

    override fun getParameterIndex(parameter: PsiParameter) = _parameters.indexOf(parameter)

    override fun getParametersCount() = parametersCount

    override fun accept(visitor: PsiElementVisitor) {
        if (visitor is JavaElementVisitor) {
            visitor.visitParameterList(this)
        }
    }

    override fun equals(other: Any?): Boolean = other === this ||
            other is KtLightParameterList &&
            other.parametersCount == parametersCount &&
            other.parent == parent

    override fun hashCode(): Int = parent.hashCode()
}
