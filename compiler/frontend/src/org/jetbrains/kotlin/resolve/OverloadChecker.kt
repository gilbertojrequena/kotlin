/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.resolve

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.resolve.calls.inference.components.ConstraintInjector
import org.jetbrains.kotlin.resolve.calls.inference.components.SimpleConstraintSystemImpl
import org.jetbrains.kotlin.resolve.calls.results.*
import org.jetbrains.kotlin.resolve.descriptorUtil.builtIns
import org.jetbrains.kotlin.resolve.descriptorUtil.hasLowPriorityInOverloadResolution
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtensionProperty
import org.jetbrains.kotlin.resolve.descriptorUtil.varargParameterPosition
import org.jetbrains.kotlin.types.checker.KotlinTypeRefiner
import org.jetbrains.kotlin.types.error.ErrorUtils
import org.jetbrains.kotlin.types.model.KotlinTypeMarker

object OverloadabilitySpecificityCallbacks : SpecificityComparisonCallbacks {
    override fun isNonSubtypeNotLessSpecific(specific: KotlinTypeMarker, general: KotlinTypeMarker): Boolean =
        false
}

class OverloadChecker(
    private val specificityComparator: TypeSpecificityComparator,
    private val constraintInjector: ConstraintInjector,
    private val kotlinTypeRefiner: KotlinTypeRefiner,
    private val languageVersionSettings: LanguageVersionSettings
) {
    /**
     * Does not check names.
     */
    fun isOverloadable(a: DeclarationDescriptor, b: DeclarationDescriptor): Boolean {
        val aCategory = getDeclarationCategory(a)
        val bCategory = getDeclarationCategory(b)

        if (aCategory != bCategory) return true
        if (a !is CallableDescriptor || b !is CallableDescriptor) return false

        return checkOverloadability(a, b)
    }

    private fun checkOverloadability(a: CallableDescriptor, b: CallableDescriptor): Boolean {
        if (a.hasLowPriorityInOverloadResolution() != b.hasLowPriorityInOverloadResolution()) return true

        // NB this makes generic and non-generic declarations with equivalent signatures non-conflicting
        // E.g., 'fun <T> foo()' and 'fun foo()'.
        // They can be disambiguated by providing explicit type parameters.
        if (a.typeParameters.isEmpty() != b.typeParameters.isEmpty()) return true

        if (a is FunctionDescriptor && ErrorUtils.containsErrorTypeInParameters(a) ||
            b is FunctionDescriptor && ErrorUtils.containsErrorTypeInParameters(b)
        ) return true
        if (a.varargParameterPosition() != b.varargParameterPosition()) return true

        val aSignature = FlatSignature.createFromCallableDescriptor(a)
        val bSignature = FlatSignature.createFromCallableDescriptor(b)

        val builtIns = a.builtIns

        val aIsNotLessSpecificThanB = isSignatureNotLessSpecific(aSignature, bSignature, builtIns)
        val bIsNotLessSpecificThanA = isSignatureNotLessSpecific(bSignature, aSignature, builtIns)

        return !(aIsNotLessSpecificThanB && bIsNotLessSpecificThanA)
    }

    fun isSignatureNotLessSpecific(
        aSignature: FlatSignature<CallableDescriptor>,
        bSignature: FlatSignature<CallableDescriptor>,
        builtIns: KotlinBuiltIns
    ): Boolean {
        val constraintSystemToCheckSpecificity =
            SimpleConstraintSystemImpl(constraintInjector, builtIns, kotlinTypeRefiner, languageVersionSettings)
        return constraintSystemToCheckSpecificity.isSignatureNotLessSpecific(
            aSignature, bSignature, OverloadabilitySpecificityCallbacks, specificityComparator
        )
    }

    private enum class DeclarationCategory {
        TYPE_OR_VALUE,
        FUNCTION,
        EXTENSION_PROPERTY
    }

    private fun getDeclarationCategory(a: DeclarationDescriptor): DeclarationCategory =
        when (a) {
            is PropertyDescriptor ->
                if (a.isExtensionProperty)
                    DeclarationCategory.EXTENSION_PROPERTY
                else
                    DeclarationCategory.TYPE_OR_VALUE
            is FunctionDescriptor ->
                DeclarationCategory.FUNCTION
            is ClassifierDescriptor ->
                DeclarationCategory.TYPE_OR_VALUE
            else ->
                error("Unexpected declaration kind: $a")
        }

}
