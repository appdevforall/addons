import { useEffect, useState } from 'react'
import { StickToBottom } from 'use-stick-to-bottom'
import { IconLayoutSidebar, IconMessagePlus } from '@tabler/icons-react'
import { Button } from '@/components/ui/button'
import { TooltipProvider } from '@/components/ui/tooltip'
import { ChatInput } from '@/containers/ChatInput'
import { MessageItem } from '@/containers/MessageItem'
import { ThreadList } from '@/containers/ThreadList'
import { prefersDark } from '@/lib/bridge'
import { cn } from '@/lib/utils'
import { useChat } from '@/stores/chat'

/** Wide enough to keep the thread list open beside the chat (the Fold unfolded, a tablet). */
const WIDE = '(min-width: 768px)'

function useIsWide() {
  const [wide, setWide] = useState(() => window.matchMedia(WIDE).matches)
  useEffect(() => {
    const mq = window.matchMedia(WIDE)
    const onChange = () => setWide(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return wide
}

function EmptyState() {
  const backend = useChat((s) => s.backends.find((b) => b.id === s.backendId))
  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 px-6 text-center">
      <div className="text-2xl font-medium">How can I help?</div>
      <div className="text-muted-foreground max-w-sm text-sm">
        {backend
          ? `Messages go to ${backend.name}${backend.model ? ` (${backend.model})` : ''}. Change it below.`
          : 'Install AI Core and an AI backend from the Addons Manager to start chatting.'}
      </div>
    </div>
  )
}

function Conversation() {
  const thread = useChat((s) => s.threads.find((t) => t.id === s.currentThreadId))
  const backends = useChat((s) => s.backends)
  const regenerate = useChat((s) => s.regenerate)
  const isStreaming = useChat((s) => s.inFlight !== null)

  if (!thread || thread.messages.length === 0) return <EmptyState />

  return (
    <StickToBottom className="relative h-full overflow-y-auto" resize="smooth" initial="instant">
      <StickToBottom.Content className="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-6">
        {thread.messages.map((m, i) => (
          <MessageItem
            key={m.id}
            message={m}
            isLastMessage={i === thread.messages.length - 1}
            backendName={m.role === 'assistant' ? backends.find((b) => b.id === m.backendId)?.name : undefined}
            onRegenerate={!isStreaming && m.role === 'assistant' ? regenerate : undefined}
          />
        ))}
      </StickToBottom.Content>
    </StickToBottom>
  )
}

export default function App() {
  const init = useChat((s) => s.init)
  const newThread = useChat((s) => s.newThread)
  const title = useChat((s) => s.threads.find((t) => t.id === s.currentThreadId)?.title ?? 'New chat')
  const isWide = useIsWide()
  const [drawerOpen, setDrawerOpen] = useState(false)

  useEffect(() => {
    init()
    document.documentElement.classList.toggle('dark', prefersDark())
  }, [init])

  return (
    <TooltipProvider>
      <div className="bg-background text-foreground flex h-full w-full overflow-hidden">
        {isWide && (
          <aside className="border-border h-full w-64 shrink-0 border-r">
            <ThreadList />
          </aside>
        )}
        {!isWide && (
          <>
            <div
              className={cn(
                'fixed inset-0 z-(--layer-panel) bg-black/40 transition-opacity',
                drawerOpen ? 'opacity-100' : 'pointer-events-none opacity-0'
              )}
              onClick={() => setDrawerOpen(false)}
            />
            <aside
              className={cn(
                'bg-background border-border fixed inset-y-0 left-0 z-(--layer-panel) w-72 max-w-[85%] border-r transition-transform',
                drawerOpen ? 'translate-x-0' : '-translate-x-full'
              )}
            >
              <ThreadList onPicked={() => setDrawerOpen(false)} />
            </aside>
          </>
        )}

        <main className="flex h-full min-w-0 flex-1 flex-col">
          <header className="border-border flex h-12 shrink-0 items-center gap-1 border-b px-2">
            {!isWide && (
              <Button variant="ghost" size="icon" onClick={() => setDrawerOpen(true)} aria-label="Chats">
                <IconLayoutSidebar size={18} />
              </Button>
            )}
            <div className="min-w-0 flex-1 truncate px-2 font-medium">{title}</div>
            <Button variant="ghost" size="icon" onClick={newThread} aria-label="New chat" title="New chat">
              <IconMessagePlus size={18} />
            </Button>
          </header>
          <div className="min-h-0 flex-1">
            <Conversation />
          </div>
          <div className="mx-auto w-full max-w-3xl shrink-0 px-3 pt-1 pb-3">
            <ChatInput />
          </div>
        </main>
      </div>
    </TooltipProvider>
  )
}
