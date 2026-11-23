import { create } from 'zustand'
import {
  type Backend,
  type HistoryMessage,
  cancelMessage,
  listBackends,
  loadThreadsJson,
  onBridgeEvent,
  preferredBackend,
  saveThreadsJson,
  sendMessage,
} from '@/lib/bridge'
import { newId } from '@/lib/utils'

export type MessageStatus = 'done' | 'streaming' | 'error' | 'stopped'

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: number
  status: MessageStatus
  /** Which backend answered, for assistant messages. */
  backendId?: string
  tokens?: number
  timeMs?: number
  error?: string
}

export interface Thread {
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: Message[]
}

interface InFlight {
  requestId: string
  threadId: string
  messageId: string
}

interface ChatState {
  threads: Thread[]
  currentThreadId: string | null
  backends: Backend[]
  backendId: string | null
  inFlight: InFlight | null

  init(): void
  refreshBackends(): void
  setBackend(id: string): void
  newThread(): void
  selectThread(id: string): void
  deleteThread(id: string): void
  renameThread(id: string, title: string): void
  send(text: string): void
  stop(): void
  regenerate(): void
}

const STORE_VERSION = 1
const TITLE_LENGTH = 48

function persist(threads: Thread[]) {
  saveThreadsJson(JSON.stringify({ version: STORE_VERSION, threads }))
}

function restore(): Thread[] {
  const json = loadThreadsJson()
  if (!json) return []
  try {
    const parsed = JSON.parse(json) as { version?: number; threads?: Thread[] }
    const threads = Array.isArray(parsed.threads) ? parsed.threads : []
    // A reply that was still streaming when the IDE closed can never finish now.
    for (const t of threads) {
      for (const m of t.messages) {
        if (m.status === 'streaming') m.status = 'stopped'
      }
    }
    return threads
  } catch {
    return []
  }
}

function titleFrom(text: string): string {
  const line = text.trim().split('\n')[0]
  return line.length > TITLE_LENGTH ? line.slice(0, TITLE_LENGTH - 1) + '…' : line || 'New chat'
}

function updateMessage(threads: Thread[], threadId: string, messageId: string, change: (m: Message) => Message): Thread[] {
  return threads.map((t) =>
    t.id !== threadId
      ? t
      : { ...t, updatedAt: Date.now(), messages: t.messages.map((m) => (m.id === messageId ? change(m) : m)) }
  )
}

export const useChat = create<ChatState>((set, get) => {
  /** Sends the conversation up to and including the last user message, into a new assistant message. */
  const startReply = (threadId: string) => {
    const { backendId, threads } = get()
    const thread = threads.find((t) => t.id === threadId)
    if (!thread || !backendId) return
    const lastUser = [...thread.messages].reverse().find((m) => m.role === 'user')
    if (!lastUser) return
    const history: HistoryMessage[] = thread.messages
      .slice(0, thread.messages.indexOf(lastUser))
      .filter((m) => m.status === 'done' && m.content.trim())
      .map((m) => ({ role: m.role, content: m.content }))

    const reply: Message = {
      id: newId(),
      role: 'assistant',
      content: '',
      createdAt: Date.now(),
      status: 'streaming',
      backendId,
    }
    const requestId = newId()
    set({
      threads: get().threads.map((t) =>
        t.id === threadId ? { ...t, updatedAt: Date.now(), messages: [...t.messages, reply] } : t
      ),
      inFlight: { requestId, threadId, messageId: reply.id },
    })
    sendMessage(requestId, backendId, history, lastUser.content)
  }

  onBridgeEvent((e) => {
    const inFlight = get().inFlight
    if (!inFlight || e.requestId !== inFlight.requestId) return
    const { threadId, messageId } = inFlight
    if (e.type === 'token') {
      set({ threads: updateMessage(get().threads, threadId, messageId, (m) => ({ ...m, content: m.content + e.text })) })
      return
    }
    const threads =
      e.type === 'done'
        ? updateMessage(get().threads, threadId, messageId, (m) => ({
            ...m,
            // Backends that do not stream send the whole reply with `done`.
            content: m.content || e.text || '',
            status: 'done',
            tokens: e.tokens,
            timeMs: e.timeMs,
          }))
        : updateMessage(get().threads, threadId, messageId, (m) => ({
            ...m,
            status: e.kind === 'CANCELLED' ? 'stopped' : 'error',
            error: e.kind === 'CANCELLED' ? undefined : e.message,
          }))
    set({ threads, inFlight: null })
    persist(threads)
  })

  return {
    threads: [],
    currentThreadId: null,
    backends: [],
    backendId: null,
    inFlight: null,

    init() {
      const threads = restore().sort((a, b) => b.updatedAt - a.updatedAt)
      set({ threads, currentThreadId: threads[0]?.id ?? null })
      get().refreshBackends()
    },

    refreshBackends() {
      const backends = listBackends()
      const current = get().backendId
      const keep = backends.find((b) => b.id === current)
      const preferred = preferredBackend()
      const pick = keep?.id ?? backends.find((b) => b.id === preferred)?.id ?? backends.find((b) => b.available)?.id ?? backends[0]?.id ?? null
      set({ backends, backendId: pick })
    },

    setBackend(id) {
      set({ backendId: id })
    },

    newThread() {
      const current = get().threads.find((t) => t.id === get().currentThreadId)
      // Do not stack up empty chats.
      if (current && current.messages.length === 0) return
      const thread: Thread = { id: newId(), title: 'New chat', createdAt: Date.now(), updatedAt: Date.now(), messages: [] }
      set({ threads: [thread, ...get().threads], currentThreadId: thread.id })
    },

    selectThread(id) {
      set({ currentThreadId: id })
    },

    deleteThread(id) {
      if (get().inFlight?.threadId === id) get().stop()
      const threads = get().threads.filter((t) => t.id !== id)
      set({ threads, currentThreadId: get().currentThreadId === id ? (threads[0]?.id ?? null) : get().currentThreadId })
      persist(threads)
    },

    renameThread(id, title) {
      const threads = get().threads.map((t) => (t.id === id ? { ...t, title: title.trim() || t.title } : t))
      set({ threads })
      persist(threads)
    },

    send(text) {
      const content = text.trim()
      if (!content || get().inFlight || !get().backendId) return
      let threadId = get().currentThreadId
      let threads = get().threads
      if (!threadId || !threads.some((t) => t.id === threadId)) {
        const thread: Thread = { id: newId(), title: 'New chat', createdAt: Date.now(), updatedAt: Date.now(), messages: [] }
        threads = [thread, ...threads]
        threadId = thread.id
      }
      const user: Message = { id: newId(), role: 'user', content, createdAt: Date.now(), status: 'done' }
      threads = threads.map((t) =>
        t.id === threadId
          ? {
              ...t,
              title: t.messages.length === 0 ? titleFrom(content) : t.title,
              updatedAt: Date.now(),
              messages: [...t.messages, user],
            }
          : t
      )
      // Most recent first, as Jan's thread list shows them.
      threads.sort((a, b) => b.updatedAt - a.updatedAt)
      set({ threads, currentThreadId: threadId })
      persist(threads)
      startReply(threadId)
    },

    stop() {
      const inFlight = get().inFlight
      if (inFlight) cancelMessage(inFlight.requestId)
    },

    regenerate() {
      const { currentThreadId, inFlight } = get()
      if (!currentThreadId || inFlight) return
      // Drop the last assistant reply and ask again.
      const threads = get().threads.map((t) => {
        if (t.id !== currentThreadId) return t
        const last = t.messages[t.messages.length - 1]
        return last?.role === 'assistant' ? { ...t, messages: t.messages.slice(0, -1) } : t
      })
      set({ threads })
      startReply(currentThreadId)
    },
  }
})
