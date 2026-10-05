package com.itsaky.androidide.plugins.aiagentlocal.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptText


/**
 * The local model's system prompt as `assets/prompts/` declares it: the wording, and the layout
 * that arranges it. Loaded by [PromptConfigLoader]; immutable, so one instance serves every request.
 *
 * @property identity who the agent is.
 * @property rules the rules, in the order they are sent.
 * @property tools the wording around the tool list.
 * @property toolCallFormat how to call a tool; a small model calls through the text protocol only.
 * @property layout where each text goes.
 */
data class LocalPromptConfig(
    val identity: PromptText,
    val rules: List<RuleGroup>,
    val tools: Tools,
    val toolCallFormat: ToolCallFormat,
    val layout: Layout,
) {

    /**
     * One group of rules.
     *
     * @property heading the group's name, e.g. `Rules`.
     * @property items the rules, one sentence each.
     */
    data class RuleGroup(val heading: PromptText, val items: List<PromptText>)

    /** @property heading what introduces the tool list. */
    data class Tools(val heading: PromptText)

    /** @property text how to call when calls travel in the reply, the only way this backend calls. */
    data class ToolCallFormat(val text: TextFormat)

    /**
     * @property instruction the sentence introducing the envelope.
     * @property examplesHeading what introduces [examples].
     * @property examples well-formed calls, each with what it is for.
     */
    data class TextFormat(
        val instruction: PromptText,
        val examplesHeading: PromptText,
        val examples: List<Example>,
    )

    /**
     * @property purpose what the call does, and which tool does it.
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
