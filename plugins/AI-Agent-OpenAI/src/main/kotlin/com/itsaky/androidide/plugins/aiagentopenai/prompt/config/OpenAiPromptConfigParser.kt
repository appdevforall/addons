package com.itsaky.androidide.plugins.aiagentopenai.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigDocument
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigException
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigObject
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigParser
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig.Example
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig.Layout
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig.RuleGroup
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig.Section
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig.TextFormat
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig.ToolCallFormat
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig.Tools
import com.itsaky.androidide.plugins.aiagentopenai.prompt.config.OpenAiPromptConfig.Workflow

/**
 * Maps the merged config onto a [OpenAiPromptConfig]. Strict: a missing, mistyped or unknown key
 * throws [PromptConfigException] naming the file that holds it, so a typo fails on activation.
 */
object OpenAiPromptConfigParser : PromptConfigParser<OpenAiPromptConfig> {

    /**
     * Parses the config merged from `agent.yml` and its includes; see [PromptConfigLoader].
     *
     * @param document the merged top-level keys and the file each came from.
     * @return the config.
     */
    override fun parse(document: PromptConfigDocument): OpenAiPromptConfig =
        document.read {
            val version = int("schema_version")
            if (version != OpenAiPromptConfig.SCHEMA_VERSION) {
                val supported = OpenAiPromptConfig.SCHEMA_VERSION
                throw invalid("schema_version", "is $version, but this OpenAI plugin reads $supported")
            }
            OpenAiPromptConfig(
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
                                noNativeChannel = text("no_native_channel"),
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
