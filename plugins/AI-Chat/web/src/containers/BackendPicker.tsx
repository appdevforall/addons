/*
 * In the place of Jan's ComposerModelPicker (https://github.com/janhq/jan,
 * web-app/src/containers/ComposerModelPicker.tsx; Apache License 2.0, see NOTICE), whose look
 * it follows: a compact chip in the composer that opens a menu. It lists AI Core's backends
 * (Local, OpenAI, Gemini, Claude...) instead of Jan's providers and models.
 */
import { IconCheck, IconChevronDown, IconCpu } from '@tabler/icons-react'
import { cn } from '@/lib/utils'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useChat } from '@/stores/chat'

export function BackendPicker() {
  const backends = useChat((s) => s.backends)
  const backendId = useChat((s) => s.backendId)
  const setBackend = useChat((s) => s.setBackend)
  const refresh = useChat((s) => s.refreshBackends)
  const isStreaming = useChat((s) => s.inFlight !== null)
  const current = backends.find((b) => b.id === backendId)

  return (
    <DropdownMenu onOpenChange={(open) => open && refresh()}>
      <DropdownMenuTrigger
        disabled={isStreaming}
        className="text-muted-foreground hover:text-foreground hover:bg-foreground/5 flex min-w-0 shrink items-center gap-1.5 rounded-full px-2.5 py-1.5 text-sm disabled:opacity-60"
      >
        <IconCpu size={16} className="shrink-0" />
        <span className="truncate">
          {current ? (current.model ? `${current.name} · ${current.model}` : current.name) : 'No backend'}
        </span>
        <IconChevronDown size={14} className="shrink-0" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-64">
        <DropdownMenuLabel>AI backend</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {backends.length === 0 && (
          <div className="text-muted-foreground px-2 py-2 text-sm">
            No backends. Install AI Core and a backend such as AI Agent Local from the Addons Manager.
          </div>
        )}
        {backends.map((b) => (
          <DropdownMenuItem key={b.id} onSelect={() => setBackend(b.id)} className="items-start">
            <IconCheck size={16} className={cn('mt-0.5 shrink-0', b.id === backendId ? 'opacity-100' : 'opacity-0')} />
            <div className="min-w-0">
              <div className="truncate">{b.name}</div>
              {(b.model || b.status || !b.available) && (
                <div className="text-muted-foreground truncate text-xs">
                  {[b.model, b.status ?? (b.available ? null : 'Not ready')].filter(Boolean).join(' · ')}
                </div>
              )}
            </div>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
