/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.core

import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Appends a given [interceptor] into a copy of [interceptors]
 * if the same instance/an instance of the same type isn't found.
 *
 * Otherwise, returns **a copy of [interceptors]** unmodified.
 *
 * Note: types are compared via `it.javaClass.name`.
 */
fun patchInterceptors(interceptors: List<Interceptor>, interceptor: Interceptor): List<Interceptor> {
    val copy = interceptors.toMutableList()
    patchInterceptorsInplace(copy, interceptor)
    return copy
}

internal fun patchInterceptorsInplace(interceptors: MutableList<Interceptor>, interceptor: Interceptor) {
    val interceptorExists = interceptors.any {
        it == interceptor || it.javaClass.name == interceptor.javaClass.name
    }
    if (!interceptorExists) {
        interceptors.add(interceptor)
    }
}

/**
 * Walks all declared fields (including superclass fields) of [instance], patching any
 * [OkHttpClient] field found with a copy that carries [interceptor], and recursing into
 * fields whose runtime class name starts with `"com.anthropic"` to reach nested SDK
 * sub-client structures.
 *
 * The [visited] set uses identity hash codes to guard against cycles. This is a best-effort
 * fallback for SDK layouts where the standard field-name path in
 * [patchOpenAICompatibleClient] does not apply.
 *
 * @return `true` if at least one [OkHttpClient] field was reached (already patched or
 *   successfully written); `false` if no [OkHttpClient] was reachable from [instance].
 */
fun patchClientByFieldScan(
    instance: Any,
    interceptor: Interceptor,
    visited: MutableSet<Int> = mutableSetOf(),
): Boolean {
    if (!visited.add(System.identityHashCode(instance))) return false

    var found = false
    var cls: Class<*>? = instance.javaClass
    while (cls != null && cls != Any::class.java) {
        for (field in cls.declaredFields) {
            val value = try {
                field.isAccessible = true
                field.get(instance)
            } catch (_: Exception) {
                continue
            } ?: continue

            when {
                value is OkHttpClient -> {
                    if (value.interceptors.none { it.javaClass.name == interceptor.javaClass.name }) {
                        val newClient = value.newBuilder().addInterceptor(interceptor).build()
                        try {
                            field.set(instance, newClient)
                        } catch (_: Exception) {
                            // skip final or module-inaccessible fields
                        }
                    }
                    found = true
                }
                value.javaClass.name.startsWith("com.anthropic") -> {
                    if (patchClientByFieldScan(value, interceptor, visited)) found = true
                }
            }
        }
        cls = cls.superclass
    }
    return found
}