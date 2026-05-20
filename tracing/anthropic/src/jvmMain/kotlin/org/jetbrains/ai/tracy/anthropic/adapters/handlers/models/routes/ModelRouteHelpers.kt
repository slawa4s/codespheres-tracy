/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.anthropic.adapters.handlers.models.routes

import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Extracts the model alias from a path like `/v1/models/{model_alias}`.
 *
 * Returns `null` for the list collection path `/v1/models`.
 */
internal fun extractModelAliasFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val modelsIndex = segments.indexOf("models")
    if (modelsIndex == -1) return null
    val after = segments.drop(modelsIndex + 1).filter { it.isNotBlank() }
    return after.firstOrNull()
}
