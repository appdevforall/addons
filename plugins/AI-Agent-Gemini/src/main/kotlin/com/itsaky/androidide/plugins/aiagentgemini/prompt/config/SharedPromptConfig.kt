package com.itsaky.androidide.plugins.aiagentgemini.prompt.config

import com.itsaky.androidide.plugins.ai.prompt.PromptConfigStore



/** This plugin's prompt config, filled on activation and read by every chat turn. */
val sharedPromptConfig: PromptConfigStore<GeminiPromptConfig> = PromptConfigStore(GeminiPromptConfigParser)
