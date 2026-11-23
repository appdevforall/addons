/*
 * From Jan (https://github.com/janhq/jan), web-app/src/containers/CopyButton.tsx.
 * Copyright 2025 Menlo Research. Apache License 2.0; see NOTICE. Change: English strings
 * in place of Jan's i18n.
 */
import { Button } from '@/components/ui/button'
import { IconCopy, IconCopyCheck } from '@tabler/icons-react'
import { useState } from 'react'

export const CopyButton = ({ text }: { text: string }) => {
  const [copied, setCopied] = useState(false)

  const handleCopy = () => {
    navigator.clipboard.writeText(text)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <Button
      variant="ghost"
      size="icon-xs"
      onClick={handleCopy}
      aria-label={copied ? 'Copied' : 'Copy'}
      title={copied ? 'Copied' : 'Copy'}
    >
      {copied ? (
        <IconCopyCheck size={16} className="text-primary" aria-hidden="true" />
      ) : (
        <IconCopy size={16} aria-hidden="true" />
      )}
    </Button>
  )
}
