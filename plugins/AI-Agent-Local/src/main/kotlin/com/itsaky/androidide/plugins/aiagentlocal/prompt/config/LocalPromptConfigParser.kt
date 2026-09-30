package com.itsaky.androidide.plugins.aiagentlocal.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigDocument
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigException
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigLoader
import com.itsaky.androidide.plugins.ai.prompt.PromptConfigParser
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig.Example
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig.Layout
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig.RuleGroup
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig.TextFormat
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig.ToolCallFormat
import com.itsaky.androidide.plugins.aiagentlocal.prompt.config.LocalPromptConfig.Tools

/**
 * Maps the merged config onto a [LocalPromptConfig]. Strict: a missing, mistyped or unknown key
 * throws [PromptConfigException] naming the file that holds it, so a typo fails on activation.
 */
object LocalPromptConfigParser : PromptConfigParser<LocalPromptConfig> {

    /**
     * Parses the config merged from `agent.yml` and its includes; see [PromptConfigLoader].
     *
     * @param document the merged top-level keys and the file each came from.
     * @return the config.
     */
    override fun parse(document: PromptConfigDocument): LocalPromptConfig =
        document.read {
            val version = int("schema_version")
            if (version != LocalPromptConfig.SCHEMA_VERSION) {
                val supported = LocalPromptConfig.SCHEMA_VERSION
                throw invalid("schema_version", "is $version, but this local-model plugin reads $supported")
            }
            LocalPromptConfig(
                identity = text("identity"),
                rules = objects("rules").map { it.read { RuleGroup(text("heading"), texts("items")) } },
                tools = obj("tools").read { Tools(text("heading")) },
                toolCallFormat = obj("tool_call_format").read {
                    ToolCallFormat(
                        text = obj("text").read {
                            TextFormat(
                                instruction = text("instruction"),
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
}
