package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.tool.sources.ContributedToolHandler
import com.itsaky.androidide.plugins.aicore.tool.sources.PromptToolBudget
import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore

/**
 * One immutable snapshot of the agent's tool set: the router, the executor built from it, and the
 * grammar derived from it.
 *
 * They are swapped together, behind a single reference, because a rebuild that replaced the router
 * without the grammar would leave a token mask that forbids every newly contributed tool — a green
 * build whose only symptom is "the model ignores the tools", visible on device alone. For the same
 * reason the prompt's tool list is built here rather than per message: grammar and prompt have to
 * describe one tool set, and they only do if one snapshot holds both.
 *
 * @property router routes a tool call to its handler.
 * @property executor runs a batch of calls against [router].
 * @property grammar the local backend's GBNF for exactly the tools [promptTools] describes.
 * @property promptTools the tool list as the prompt will see it, [PromptToolBudget] already applied.
 * @param compactVariant this same handler set with only [PromptToolBudget.COMPACT_BUILT_INS] among the
 *   built-ins, built on first use; null on the compact snapshot itself.
 */
class AgentTools private constructor(
    val router: ToolRouter,
    val executor: Executor,
    val grammar: String,
    val promptTools: PromptToolBudget.Budgeted,
    private val compactVariant: Lazy<AgentTools>?,
) {

    /** The handlers contributed by plugins, as opposed to this plugin's own. */
    val contributedHandlers: List<ContributedToolHandler> =
        router.getAllHandlers().filterIsInstance<ContributedToolHandler>()

    /**
     * The snapshot one run should use, for the backend it is about to talk to.
     *
     * Asked per run, not per rebuild: the user can switch backends between messages, and the same
     * snapshot is also what a natively calling backend is sent. The compact snapshot is built from
     * the very handlers this one was, so a tool source that changed since cannot make the two
     * disagree, and it brings its own grammar, so the model is never allowed a tool its prompt omits.
     *
     * @param callsToolsNatively whether the backend carries tool calls in its provider's own API.
     * @param contextWindowTokens the window the backend reports; 0 when it reports none.
     * @return this snapshot, or its compact variant; the same instance for the same answer.
     * @see PromptToolBudget.isCompact
     */
    fun forBackend(callsToolsNatively: Boolean, contextWindowTokens: Int): AgentTools =
        if (PromptToolBudget.isCompact(callsToolsNatively, contextWindowTokens)) {
            compactVariant?.value ?: this
        } else {
            this
        }

    companion object {

        /**
         * Builds a tool set from the built-in handlers plus everything currently contributed.
         *
         * Built-ins are reserved first, so a source can never shadow one. An in-flight run holds
         * its own snapshot and finishes against the tool set it started with — that is deliberate,
         * not a leak: swapping a run's executor mid-batch is what would need explaining.
         *
         * @param builtInHandlers this plugin's own handlers.
         * @param store the registered tool sources.
         * @param approvalManager the approval gate the executor consults.
         * @param toolExecutionTracker optional per-tool timing sink.
         * @param terminalTool the tool the model answers with; reserved but not routed.
         * @return the new snapshot.
         */
        fun build(
            builtInHandlers: List<ToolHandler>,
            store: ToolSourceStore,
            approvalManager: ToolApprovalManager,
            toolExecutionTracker: ToolExecutionTracker? = null,
            terminalTool: String,
        ): AgentTools {
            val reserved = builtInHandlers.mapTo(mutableSetOf()) { it.toolName }
            reserved += terminalTool

            val handlers = builtInHandlers + store.handlers(reserved)
            return assemble(handlers, approvalManager, toolExecutionTracker, terminalTool, builtIns = null)
        }

        /**
         * Builds one snapshot from a fixed handler list.
         *
         * @param handlers every handler, built-in and contributed, as already collected.
         * @param builtIns the built-in names to offer, or null for all; see [PromptToolBudget.apply].
         * @return the snapshot; a full one carries its compact variant, built from the same [handlers].
         */
        private fun assemble(
            handlers: List<ToolHandler>,
            approvalManager: ToolApprovalManager,
            toolExecutionTracker: ToolExecutionTracker?,
            terminalTool: String,
            builtIns: Set<String>?,
        ): AgentTools {
            // The grammar is built from the budgeted list, not from every handler: a name the
            // token mask permits but the prompt never mentioned is a name the model cannot use,
            // and one the prompt mentions but the mask forbids is a tool it cannot call.
            val promptTools = PromptToolBudget.apply(
                handlers,
                builtIns,
                maxContributed = if (builtIns == null) {
                    PromptToolBudget.MAX_CONTRIBUTED_TOOLS
                } else {
                    PromptToolBudget.COMPACT_MAX_CONTRIBUTED_TOOLS
                },
            )
            val admitted = promptTools.definitions.mapTo(mutableSetOf()) { it.name }
            // Routing stays unbudgeted; only what a failure offers back is narrowed to that set.
            val router = ToolRouter(handlers, suggestableNames = admitted)
            val grammar = ToolCallGrammar.build(admitted.toList() + terminalTool)
            val compactVariant = if (builtIns == null) {
                lazy {
                    assemble(
                        handlers, approvalManager, toolExecutionTracker, terminalTool,
                        builtIns = PromptToolBudget.COMPACT_BUILT_INS,
                    )
                }
            } else {
                null
            }
            return AgentTools(
                router = router,
                executor = Executor(router, approvalManager, toolExecutionTracker),
                grammar = grammar,
                promptTools = promptTools,
                compactVariant = compactVariant,
            )
        }
    }
}
