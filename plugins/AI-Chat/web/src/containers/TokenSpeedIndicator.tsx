/*
 * Adapted from Jan (https://github.com/janhq/jan), web-app/src/containers/TokenSpeedIndicator.tsx.
 * Copyright 2025 Menlo Research. Apache License 2.0; see NOTICE. Change: takes the token count
 * and duration directly, and always shows the inline form (no interface settings, no popover).
 */
import { memo } from 'react'
import { Gauge } from 'lucide-react'

interface TokenSpeedIndicatorProps {
  tokens?: number
  timeMs?: number
  streaming?: boolean
}

export const TokenSpeedIndicator = memo(({ tokens, timeMs, streaming }: TokenSpeedIndicatorProps) => {
  if (streaming || !tokens) return null
  const speed = timeMs && timeMs > 0 ? Math.round(tokens / (timeMs / 1000)) : 0
  return (
    <div className="flex items-center gap-2 text-muted-foreground text-sm">
      {speed > 0 && (
        <div className="flex items-center gap-1">
          <Gauge size={16} />
          <span>{speed} tokens/s</span>
        </div>
      )}
      <span className="text-muted-foreground">({tokens} tokens)</span>
    </div>
  )
})

export default TokenSpeedIndicator
