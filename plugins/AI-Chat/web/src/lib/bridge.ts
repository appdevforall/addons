/**
 * The one boundary between the chat UI and Code on the Go.
 *
 * In the plugin, ChatFragment installs `window.CoGoChat` with addJavascriptInterface. Its
 * methods take and return strings only (JSON where structured), and they run on a WebView
 * binder thread, so none of them blocks on generation: `send` returns at once and the reply
 * streams back through `window.__cogoChatEvent(json)`.
 *
 * Outside the plugin (`npm run dev` in a desktop browser) there is no bridge, and a small mock
 * backend that echoes the prompt takes its place, so the UI can be worked on without a phone.
 */

export interface Backend {
  id: string
  name: string
  available: boolean
  /** The model the backend reports, when it reports one. */
  model?: string | null
  /** A status line ("Model not loaded", "No API key"...), when the backend has one. */
  status?: string | null
}

export type Role = 'user' | 'assistant' | 'system'

export interface HistoryMessage {
  role: Role
  content: string
}

export interface SendOptions {
  temperature?: number
  maxTokens?: number
  systemPrompt?: string
}

export type BridgeEvent =
  | { type: 'token'; requestId: string; text: string }
  | { type: 'done'; requestId: string; text?: string; tokens: number; timeMs: number }
  | { type: 'error'; requestId: string; message: string; kind?: string }

interface NativeBridge {
  listBackends(): string
  preferredBackend(): string
  send(requestId: string, backendId: string, historyJson: string, prompt: string, optionsJson: string): void
  cancel(requestId: string): void
  loadThreads(): string
  saveThreads(json: string): void
  openUrl(url: string): void
  isDarkTheme(): boolean
}

declare global {
  interface Window {
    CoGoChat?: NativeBridge
    __cogoChatEvent?: (json: string) => void
  }
}

type Listener = (e: BridgeEvent) => void
const listeners = new Set<Listener>()

window.__cogoChatEvent = (json: string) => {
  let event: BridgeEvent
  try {
    event = JSON.parse(json)
  } catch {
    return
  }
  listeners.forEach((l) => l(event))
}

export function onBridgeEvent(listener: Listener): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

export const hasNativeBridge = (): boolean => typeof window.CoGoChat !== 'undefined'

export function listBackends(): Backend[] {
  if (!window.CoGoChat) return mock.backends
  try {
    return JSON.parse(window.CoGoChat.listBackends()) as Backend[]
  } catch {
    return []
  }
}

export function preferredBackend(): string | null {
  if (!window.CoGoChat) return mock.backends[0].id
  return window.CoGoChat.preferredBackend() || null
}

export function sendMessage(
  requestId: string,
  backendId: string,
  history: HistoryMessage[],
  prompt: string,
  options: SendOptions = {}
) {
  if (!window.CoGoChat) return mock.send(requestId, prompt)
  window.CoGoChat.send(requestId, backendId, JSON.stringify(history), prompt, JSON.stringify(options))
}

export function cancelMessage(requestId: string) {
  if (!window.CoGoChat) return mock.cancel(requestId)
  window.CoGoChat.cancel(requestId)
}

export function loadThreadsJson(): string | null {
  if (!window.CoGoChat) {
    try {
      return localStorage.getItem('ai-chat-threads')
    } catch {
      return null
    }
  }
  return window.CoGoChat.loadThreads() || null
}

export function saveThreadsJson(json: string) {
  if (!window.CoGoChat) {
    try {
      localStorage.setItem('ai-chat-threads', json)
    } catch {
      /* private window: nothing to keep */
    }
    return
  }
  window.CoGoChat.saveThreads(json)
}

export function openExternal(url: string) {
  if (!window.CoGoChat) {
    window.open(url, '_blank', 'noopener')
    return
  }
  window.CoGoChat.openUrl(url)
}

export function prefersDark(): boolean {
  if (window.CoGoChat) return window.CoGoChat.isDarkTheme()
  return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
}

/** Browser-only stand-in for AI Core: streams the prompt back a word at a time. */
const mock = (() => {
  const timers = new Map<string, number>()
  const emit = (e: BridgeEvent) => window.__cogoChatEvent?.(JSON.stringify(e))
  return {
    backends: [{ id: 'mock', name: 'Mock (browser preview)', available: true, model: 'echo' }] as Backend[],
    send(requestId: string, prompt: string) {
      const words = `You said:\n\n> ${prompt}\n\nHere is some \`code\`:\n\n\`\`\`kotlin\nfun main() = println("hi")\n\`\`\``.split(/(\s+)/)
      let i = 0
      const t0 = performance.now()
      const tick = () => {
        if (i >= words.length) {
          timers.delete(requestId)
          emit({ type: 'done', requestId, tokens: words.length, timeMs: performance.now() - t0 })
          return
        }
        emit({ type: 'token', requestId, text: words[i++] })
        timers.set(requestId, window.setTimeout(tick, 30))
      }
      tick()
    },
    cancel(requestId: string) {
      const t = timers.get(requestId)
      if (t !== undefined) window.clearTimeout(t)
      timers.delete(requestId)
      emit({ type: 'error', requestId, message: 'Stopped', kind: 'CANCELLED' })
    },
  }
})()
