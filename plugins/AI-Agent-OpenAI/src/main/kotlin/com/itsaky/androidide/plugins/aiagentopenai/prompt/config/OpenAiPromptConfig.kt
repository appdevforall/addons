package com.itsaky.androidide.plugins.aiagentopenai.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptText


/**
 * OpenAI's system prompt as `assets/prompts/` declares it: the wording, and the layout that
 * arranges it. Loaded by [PromptConfigLoader]; immutable, so one instance serves every request.
 *
 * @property identity who the agent is.
 * @property scope what the agent will answer.
 * @property rules the rules, highest priority first.
 * @property behavior how to go about building or changing something.
 * @property workflow the steps of such a task, in order.
 * @property tools the wording around the tool list.
 * @property toolCallFormat how to call a tool, natively or as text.
 * @property layout where each text goes.
 */
data class OpenAiPromptConfig(
    val identity: PromptText,
    val scope: Section,
    val rules: List<RuleGroup>,
    val behavior: Section,
    val workflow: Workflow,
    val tools: Tools,
    val toolCallFormat: ToolCallFormat,
    val layout: Layout,
) {

    /**
     * A heading and the lines under it.
     *
     * @property heading what the lines are about.
     * @property items one sentence each.
     */
    data class Section(val heading: PromptText, val items: List<PromptText>)

    /**
     * One priority's rules.
     *
     * @property heading the priority's name, e.g. `CRITICAL`.
     * @property items the rules, one sentence each.
     */
    data class RuleGroup(val heading: PromptText, val items: List<PromptText>)

    /**
     * @property heading what introduces the steps.
     * @property steps the steps, numbered in order when rendered.
     * @property closing when to skip them.
     */
    data class Workflow(val heading: PromptText, val steps: List<PromptText>, val closing: PromptText)

    /** @property heading what introduces the tool list. */
    data class Tools(val heading: PromptText)

    /**
     * @property noNarration sent under either format: acting means calling, not describing.
     * @property native how to call under the function-calling API.
     * @property text how to call when calls travel in the reply.
     */
    data class ToolCallFormat(val noNarration: PromptText, val native: PromptText, val text: TextFormat)

    /**
     * @property instruction the sentence introducing the envelope.
     * @property noNativeChannel that a call through the provider's function-calling API is not read.
     * @property onlyTheLineRuns that only the envelope line itself runs a tool.
     * @property examplesHeading what introduces [examples].
     * @property examples well-formed calls, each with what it is for.
     */
    data class TextFormat(
        val instruction: PromptText,
        val noNativeChannel: PromptText,
        val onlyTheLineRuns: PromptText,
        val examplesHeading: PromptText,
        val examples: List<Example>,
    )

    /**
     * @property purpose what the call does.
     * @property call the call, as the model should write it.
     */
    data class Example(val purpose: PromptText, val call: PromptText)

    /** @property systemPrompt the whole prompt; ai-core appends its IDE CONTEXT block after it. */
    data class Layout(val systemPrompt: PromptText)

    companion object {
        /** The `schema_version` this code reads; bump it when a key is renamed or removed. */
        const val SCHEMA_VERSION = 1
    }
}
