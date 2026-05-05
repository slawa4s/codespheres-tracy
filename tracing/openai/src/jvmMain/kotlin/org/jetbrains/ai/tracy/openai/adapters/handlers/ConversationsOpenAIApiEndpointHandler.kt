/*
 * Copyright © 2026 JetBrains s.r.o. and contributors.
 * Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.ai.tracy.openai.adapters.handlers

import io.opentelemetry.api.trace.Span
import mu.KotlinLogging
import org.jetbrains.ai.tracy.core.adapters.handlers.EndpointApiHandler
import org.jetbrains.ai.tracy.core.adapters.media.MediaContentExtractor
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpRequest
import org.jetbrains.ai.tracy.core.http.protocol.TracyHttpResponse

/**
 * Handler for OpenAI Conversations API.
 *
 * Handles tracing for conversations endpoints, extracting common request/response
 * attributes such as model and operation name via [OpenAIApiUtils].
 *
 * See: [OpenAI API Reference](https://platform.openai.com/docs/api-reference)
 */
internal class ConversationsOpenAIApiEndpointHandler(
    @Suppress("UNUSED_PARAMETER") extractor: MediaContentExtractor
) : EndpointApiHandler {

    override fun handleRequestAttributes(span: Span, request: TracyHttpRequest) {
        OpenAIApiUtils.setCommonRequestAttributes(span, request)
    }

    override fun handleResponseAttributes(span: Span, response: TracyHttpResponse) {
        // Common response attributes (model, id, object) are set by the adapter
        // before this handler is invoked; no additional attributes to extract here.
    }

    override fun handleStreaming(span: Span, events: String) {
        logger.warn { "Conversations API streaming is not yet supported" }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
