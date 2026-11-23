/*
 * Adapted from Jan (https://github.com/janhq/jan), web-app/src/containers/ThreadList.tsx.
 * Copyright 2025 Menlo Research. Apache License 2.0; see NOTICE.
 *
 * Changes for AI Chat: no projects, routing or drag-and-drop; rows select a thread in the
 * local store; rename is an inline field instead of Jan's dialog.
 */
import { useState } from 'react'
import { IconDots, IconPencil, IconPlus, IconTrash } from '@tabler/icons-react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useChat } from '@/stores/chat'

export function ThreadList({ onPicked }: { onPicked?: () => void }) {
  const threads = useChat((s) => s.threads)
  const currentThreadId = useChat((s) => s.currentThreadId)
  const inFlight = useChat((s) => s.inFlight)
  const selectThread = useChat((s) => s.selectThread)
  const newThread = useChat((s) => s.newThread)
  const deleteThread = useChat((s) => s.deleteThread)
  const renameThread = useChat((s) => s.renameThread)
  const [renaming, setRenaming] = useState<string | null>(null)
  const [draft, setDraft] = useState('')

  return (
    <div className="flex h-full flex-col gap-2 p-2">
      <Button
        variant="outline"
        className="justify-start gap-2"
        onClick={() => {
          newThread()
          onPicked?.()
        }}
      >
        <IconPlus size={16} />
        New chat
      </Button>
      <div className="text-muted-foreground px-2 pt-2 text-xs font-medium">Chats</div>
      <div className="min-h-0 flex-1 overflow-y-auto">
        {threads.length === 0 && (
          <div className="text-muted-foreground px-2 py-4 text-sm">No chats yet.</div>
        )}
        {threads.map((thread) => {
          const isSelected = thread.id === currentThreadId
          const isStreaming = inFlight?.threadId === thread.id
          return (
            <div
              key={thread.id}
              className={cn(
                'group/thread relative mb-0.5 flex items-center rounded-md',
                isSelected ? 'bg-foreground/10' : 'hover:bg-foreground/5'
              )}
            >
              {renaming === thread.id ? (
                <input
                  autoFocus
                  className="bg-background border-input mx-1 my-1 w-full rounded-sm border px-2 py-1.5 text-sm outline-none"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onBlur={() => {
                    renameThread(thread.id, draft)
                    setRenaming(null)
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') e.currentTarget.blur()
                    if (e.key === 'Escape') setRenaming(null)
                  }}
                />
              ) : (
                <button
                  type="button"
                  className="flex min-w-0 flex-1 items-center gap-1.5 px-2 py-2 text-left text-sm"
                  onClick={() => {
                    selectThread(thread.id)
                    onPicked?.()
                  }}
                >
                  {isStreaming && <Loader2 className="text-muted-foreground size-3 shrink-0 animate-spin" />}
                  <span className={cn('block truncate', isSelected && 'font-medium')} title={thread.title}>
                    {thread.title}
                  </span>
                </button>
              )}
              {renaming !== thread.id && (
                <DropdownMenu>
                  <DropdownMenuTrigger asChild>
                    <Button variant="ghost" size="icon-xs" className="mr-1 shrink-0" aria-label="More">
                      <IconDots size={16} />
                    </Button>
                  </DropdownMenuTrigger>
                  <DropdownMenuContent className="w-40" align="end">
                    <DropdownMenuItem
                      onSelect={() => {
                        setDraft(thread.title)
                        setRenaming(thread.id)
                      }}
                    >
                      <IconPencil className="size-4" />
                      Rename
                    </DropdownMenuItem>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem variant="destructive" onSelect={() => deleteThread(thread.id)}>
                      <IconTrash className="size-4" />
                      Delete
                    </DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
