/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers.models.routes

import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpUrl

/**
 * Extracts the model id from a path like `/v1/models/{model_id}`.
 * Returns `null` for the collection path `/v1/models`.
 */
internal fun extractModelIdFromPath(url: TracyHttpUrl): String? {
    val segments = url.pathSegments
    val modelsIndex = segments.indexOf("models")
    if (modelsIndex == -1 || segments.size <= modelsIndex + 1) return null
    return segments[modelsIndex + 1].takeIf { it.isNotBlank() }
}
