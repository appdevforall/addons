package com.itsaky.androidide.plugins.aicore.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptText


/**
 * The agent's prompt as `assets/prompts/` declares it: the wording, and the layout that arranges
 * it. Loaded by [PromptConfigLoader]; immutable, so one instance serves every chat turn.
 *
 * @property identity who the agent is and what it will answer.
 * @property rules the rules, highest priority first.
 * @property tools the wording around the tool list.
 * @property toolCallFormat how to write a tool call as text, taught only under the text protocol.
 * @property ideContext the wording of the IDE CONTEXT block.
 * @property session the wording of the run's own facts: the clock, and that the web is reachable.
 * @property agentLoop what the agent is told after each tool batch.
 * @property approval what the agent is told when the user does not approve a tool call.
 * @property contextFiles the wording around the files the user attached.
 * @property chatTitle what a backend is told when it is asked to name a chat.
 * @property terminalTool what the tool the agent answers the user with is for.
 * @property builtInTools what each of ai-core's own tools is for.
 * @property webSearch what a backend is told when the agent asks it to search the web.
 * @property answerReview what a backend is told when it checks an answer holding code.
 * @property layout where each text goes.
 */
data class AgentPromptConfig(
    val identity: PromptText,
    val rules: List<RuleGroup>,
    val tools: Tools,
    val toolCallFormat: ToolCallFormat,
    val ideContext: IdeContextText,
    val session: SessionText,
    val agentLoop: AgentLoopText,
    val approval: ApprovalText,
    val contextFiles: ContextFilesText,
    val chatTitle: ChatTitleText,
    val terminalTool: ToolText,
    val builtInTools: BuiltInTools,
    val webSearch: WebSearchText,
    val answerReview: AnswerReviewText,
    val layout: Layout,
) {

    /**
     * One priority's rules.
     *
     * @property heading the priority's name, e.g. `CRITICAL`.
     * @property items the rules, one sentence each.
     */
    data class RuleGroup(val heading: PromptText, val items: List<PromptText>)

    /** @property heading what introduces the tool list. */
    data class Tools(val heading: PromptText)

    /**
     * @property instruction the sentence introducing the envelope.
     * @property exampleHeading what introduces [example].
     * @property example one well-formed call.
     */
    data class ToolCallFormat(
        val instruction: PromptText,
        val exampleHeading: PromptText,
        val example: PromptText,
    )

    /** One line per fact the IDE can state; see `ide_context.yml` for what each says. */
    data class IdeContextText(
        val heading: PromptText,
        val currentFile: PromptText,
        val otherFiles: PromptText,
        val moduleSourceDir: PromptText,
        val moduleLayoutDir: PromptText,
        val moduleManifest: PromptText,
        val modulesKnown: PromptText,
        val closing: PromptText,
    )

    /**
     * @property currentTime states the device's date and time.
     * @property webAccess says the web tools are there to be used.
     */
    data class SessionText(
        val currentTime: PromptText,
        val webAccess: PromptText,
    )

    /**
     * One tool's wording, as its definition carries it to the model.
     *
     * @property description what the tool does.
     * @property arguments what each argument means, keyed by argument name; empty for none.
     */
    data class ToolText(val description: PromptText, val arguments: Map<String, PromptText>)

    /**
     * @property byName each built-in tool's wording, keyed by tool name.
     * @property label where they are declared, e.g. `tool_descriptions.yml: built_in_tools`.
     */
    data class BuiltInTools(val byName: Map<String, ToolText>, val label: String)

    /**
     * @property failed a failed tool's result, around its message.
     * @property truncated a result cut to the size limit.
     * @property grounding what every tool-results turn asks of the next reply.
     * @property afterSuccess what to do next when every tool in the batch succeeded.
     * @property afterFailure what to do next otherwise.
     * @property unfinished the turn after a reply that ran tools and then ended without the
     *   terminal tool.
     * @property requiredTool the turn after a reply that answered before calling the tool the run
     *   had to call first, such as a web search before a code review.
     */
    data class AgentLoopText(
        val failed: PromptText,
        val truncated: PromptText,
        val grounding: PromptText,
        val afterSuccess: PromptText,
        val afterFailure: PromptText,
        val unfinished: PromptText,
        val requiredTool: PromptText,
    )

    /**
     * @property denied the user refused the call.
     * @property corrected the user asked for a revision without saying what.
     * @property correctedWithInstruction the user asked for a revision and said what.
     * @property timedOut nobody answered the dialog in time.
     */
    data class ApprovalText(
        val denied: PromptText,
        val corrected: PromptText,
        val correctedWithInstruction: PromptText,
        val timedOut: PromptText,
    )

    /** @property heading what introduces the attached files. */
    data class ContextFilesText(val heading: PromptText)

    /** @property instruction the system prompt of a chat-title request. */
    data class ChatTitleText(val instruction: PromptText)

    /** @property instruction the system prompt of a web search request. */
    data class WebSearchText(val instruction: PromptText)

    /**
     * @property instruction the system prompt of an answer review request.
     * @property noEvidence what the review is shown in place of the evidence when no tool ran.
     */
    data class AnswerReviewText(val instruction: PromptText, val noEvidence: PromptText)

    /**
     * @property systemPrompt the whole prompt, for a backend that supplies none of its own.
     * @property ideContext the IDE CONTEXT block, also appended to a backend's own prompt.
     * @property toolResults the turn that carries a tool batch's results back to the model.
     * @property contextFiles the attached files, appended to the user's message.
     * @property chatTitle the user turn of a chat-title request.
     * @property answerReview the user turn of an answer review request.
     */
    data class Layout(
        val systemPrompt: PromptText,
        val ideContext: PromptText,
        val toolResults: PromptText,
        val contextFiles: PromptText,
        val chatTitle: PromptText,
        val answerReview: PromptText,
    )

    companion object {
        /** The `schema_version` this code reads; bump it when a key is renamed or removed. */
        const val SCHEMA_VERSION = 2
    }
}
