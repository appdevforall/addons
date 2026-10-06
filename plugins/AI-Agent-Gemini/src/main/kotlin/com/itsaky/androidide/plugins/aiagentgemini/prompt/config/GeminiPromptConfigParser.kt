package com.itsaky.androidide.plugins.aiagentgemini.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigDocument
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigException
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigObject
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigParser
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig.Example
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig.Layout
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig.RuleGroup
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig.Section
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig.TextFormat
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig.ToolCallFormat
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig.Tools
import com.itsaky.androidide.plugins.aiagentgemini.prompt.config.GeminiPromptConfig.Workflow

/**
 * Maps the merged config onto a [GeminiPromptConfig]. Strict: a missing, mistyped or unknown key
 * throws [PromptConfigException] naming the file that holds it, so a typo fails on activation.
 */
object GeminiPromptConfigParser : PromptConfigParser<GeminiPromptConfig> {

    /**
     * Parses the config merged from `agent.yml` and its includes; see [PromptConfigLoader].
     *
     * @param document the merged top-level keys and the file each came from.
     * @return the config.
     */
    override fun parse(document: PromptConfigDocument): GeminiPromptConfig =
        document.read {
            val version = int("schema_version")
            if (version != GeminiPromptConfig.SCHEMA_VERSION) {
                val supported = GeminiPromptConfig.SCHEMA_VERSION
                throw invalid("schema_version", "is $version, but this Gemini plugin reads $supported")
            }
            GeminiPromptConfig(
                identity = text("identity"),
                scope = obj("scope").read { section() },
                rules = objects("rules").map { it.read { RuleGroup(text("heading"), texts("items")) } },
                behavior = obj("behavior").read { section() },
                workflow = obj("workflow").read { Workflow(text("heading"), texts("steps"), text("closing")) },
                tools = obj("tools").read { Tools(text("heading")) },
                toolCallFormat = obj("tool_call_format").read {
                    ToolCallFormat(
                        noNarration = text("no_narration"),
                        native = text("native"),
                        text = obj("text").read {
                            TextFormat(
                                instruction = text("instruction"),
                                onlyTheLineRuns = text("only_the_line_runs"),
                                examplesHeading = text("examples_heading"),
                                examples = objects("examples").map { example ->
                                    example.read { Example(text("purpose"), text("call")) }
                                },
                            )
                        },
                    )
                },
                layout = obj("layout").read { Layout(text("system_prompt")) },
            )
        }

    private fun PromptConfigObject.section() = Section(text("heading"), texts("items"))
}
