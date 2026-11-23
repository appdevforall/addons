/*
 * Adapted from Jan (https://github.com/janhq/jan), web-app/src/containers/ChatInput.tsx.
 * Copyright 2025 Menlo Research. Apache License 2.0; see NOTICE.
 *
 * Kept: the rounded composer, the autosizing textarea, Enter to send (Shift+Enter for a new
 * line), and the send/stop button. Dropped: attachments, slash commands, tools, queueing,
 * the moving streaming border and the sampler popover.
 */
import { useRef, useState } from 'react'
import TextareaAutosize from 'react-textarea-autosize'
import { IconArrowUp, IconPlayerStopFilled } from '@tabler/icons-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { useChat } from '@/stores/chat'
import { BackendPicker } from './BackendPicker'

export function ChatInput() {
  const [prompt, setPrompt] = useState('')
  const [isFocused, setIsFocused] = useState(false)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const send = useChat((s) => s.send)
  const stop = useChat((s) => s.stop)
  const isStreaming = useChat((s) => s.inFlight !== null)
  const backend = useChat((s) => s.backends.find((b) => b.id === s.backendId))
  const canSend = prompt.trim().length > 0 && !isStreaming && !!backend

  const submit = () => {
    if (!canSend) return
    send(prompt)
    setPrompt('')
  }

  return (
    <div className="@container/composer relative">
      <div className="relative overflow-hidden rounded-3xl p-0.5">
        <div
          className={cn(
            'border-input dark:bg-input/30 relative z-20 rounded-3xl border bg-white px-0 transition-[border-color,box-shadow] duration-200 ease-out',
            isFocused && 'border-ring dark:border-ring/60',
            isStreaming && 'border-primary/60'
          )}
        >
          <TextareaAutosize
            dir="auto"
            ref={textareaRef}
            minRows={2}
            maxRows={8}
            value={prompt}
            placeholder={backend ? `Ask ${backend.name}…` : 'Install an AI backend to start chatting'}
            onFocus={() => setIsFocused(true)}
            onBlur={() => setIsFocused(false)}
            onChange={(e) => setPrompt(e.target.value)}
            onKeyDown={(e) => {
              // Phones have no Shift: Enter there inserts a new line, and the button sends.
              const hasHardwareKeyboard = window.matchMedia?.('(pointer: fine)').matches
              if (e.key === 'Enter' && !e.shiftKey && hasHardwareKeyboard && !e.nativeEvent.isComposing) {
                e.preventDefault()
                submit()
              }
            }}
            className="placeholder:text-muted-foreground w-full resize-none bg-transparent px-4 pt-4 text-base leading-6 outline-none"
          />
          <div className="flex w-full flex-nowrap items-center justify-between gap-2 px-2 pb-2">
            <BackendPicker />
            {isStreaming ? (
              <Button size="icon" className="shrink-0 rounded-full" onClick={stop} aria-label="Stop" title="Stop">
                <IconPlayerStopFilled size={16} />
              </Button>
            ) : (
              <Button
                size="icon"
                className="shrink-0 rounded-full"
                disabled={!canSend}
                onClick={submit}
                aria-label="Send"
                title="Send"
              >
                <IconArrowUp size={18} />
              </Button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
