/*
 * Adapted from Jan (https://github.com/janhq/jan), web-app/src/containers/MessageItem.tsx.
 * Copyright 2025 Menlo Research. Apache License 2.0; see NOTICE.
 *
 * Kept: the user bubble, the full-width assistant markdown, and the footer with copy,
 * regenerate and token speed. Dropped: attachments, tool calls, reasoning, versions,
 * citations, edit/delete dialogs and extension menus.
 */
import { memo } from 'react'
import { IconRefresh } from '@tabler/icons-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { CopyButton } from './CopyButton'
import { RenderMarkdown } from './RenderMarkdown'
import TokenSpeedIndicator from './TokenSpeedIndicator'
import type { Message } from '@/stores/chat'

interface MessageItemProps {
  message: Message
  isLastMessage: boolean
  backendName?: string
  onRegenerate?: () => void
}

export const MessageItem = memo(({ message, isLastMessage, backendName, onRegenerate }: MessageItemProps) => {
  const isStreaming = message.status === 'streaming'

  if (message.role === 'user') {
    return (
      <div className="group/message w-full">
        <div className="flex h-full w-full justify-end text-start whitespace-normal wrap-break-word">
          <div className="bg-secondary text-foreground relative inline-block max-w-[80%] rounded-md p-2">
            <div dir="auto" className="select-text whitespace-pre-wrap">
              {message.content}
            </div>
          </div>
        </div>
        <div className="mt-1 flex justify-end opacity-0 transition-opacity group-hover/message:opacity-100 focus-within:opacity-100">
          <CopyButton text={message.content} />
        </div>
      </div>
    )
  }

  return (
    <div className="group/message w-full">
      {backendName && <div className="text-muted-foreground mb-1 text-xs">{backendName}</div>}
      {message.content ? (
        <RenderMarkdown content={message.content} isStreaming={isStreaming} />
      ) : isStreaming ? (
        <div className="flex h-6 items-center gap-1" aria-label="Waiting for the model">
          <span className="bg-muted-foreground size-1.5 animate-bounce rounded-full [animation-delay:-0.3s]" />
          <span className="bg-muted-foreground size-1.5 animate-bounce rounded-full [animation-delay:-0.15s]" />
          <span className="bg-muted-foreground size-1.5 animate-bounce rounded-full" />
        </div>
      ) : null}
      {message.status === 'error' && (
        <div className="border-destructive/40 text-destructive mt-2 rounded-md border px-3 py-2 text-sm">
          {message.error || 'The model returned an error.'}
        </div>
      )}
      {message.status === 'stopped' && <div className="text-muted-foreground mt-1 text-xs">Stopped</div>}
      {!isStreaming && (
        <div
          className={cn(
            'mt-1 flex items-center gap-2',
            !isLastMessage && 'opacity-0 transition-opacity group-hover/message:opacity-100 focus-within:opacity-100'
          )}
        >
          {message.content && <CopyButton text={message.content} />}
          {isLastMessage && onRegenerate && (
            <Button variant="ghost" size="icon-xs" onClick={onRegenerate} aria-label="Regenerate" title="Regenerate">
              <IconRefresh size={16} />
            </Button>
          )}
          <TokenSpeedIndicator tokens={message.tokens} timeMs={message.timeMs} />
        </div>
      )}
    </div>
  )
})
