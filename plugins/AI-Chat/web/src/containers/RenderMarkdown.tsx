/*
 * Adapted from Jan (https://github.com/janhq/jan), web-app/src/containers/RenderMarkdown.tsx.
 * Copyright 2025 Menlo Research. Licensed under the Apache License, Version 2.0; see NOTICE.
 *
 * Changes for AI Chat: citations, HTML artifacts, mermaid and extension authorship are removed;
 * remote images are always dropped (nothing in a chat may make a network request); links open
 * outside the WebView through the CoGo bridge.
 */
import type { Components, ExtraProps } from 'react-markdown'
import { memo, useDeferredValue, useMemo } from 'react'
import { cn, disableIndentedCodeBlockPlugin } from '@/lib/utils'
import { openExternal } from '@/lib/bridge'
import { defaultRehypePlugins, Streamdown } from 'streamdown'
import { cjk } from '@streamdown/cjk'
import { code } from '@streamdown/code'
import { SKIP, visit } from 'unist-util-visit'
import type { Root } from 'hast'
import remarkGfm from 'remark-gfm'
import remarkMath from 'remark-math'
import rehypeKatex from 'rehype-katex'
import 'katex/dist/katex.min.css'

// Do not resolve model-generated links against the app's own origin.
function isWebLink(href: string | undefined): boolean {
  if (!href || !/^https?:\/\//i.test(href)) return false
  try {
    const { protocol } = new URL(href)
    return protocol === 'http:' || protocol === 'https:'
  } catch {
    return false
  }
}

// The hardener normalizes URLs and annotates rejected anchors with " [blocked]".
// Unwrap anything that is not an absolute http(s) link so only its label survives.
function restrictChatLinks() {
  return (tree: Root) => {
    visit(tree, 'element', (node, index, parent) => {
      if (node.tagName !== 'a') return
      const href = node.properties.href
      if (typeof href === 'string' && isWebLink(href)) return
      if (parent && index !== undefined) {
        parent.children.splice(index, 1, ...node.children)
        return [SKIP, index]
      }
    })
  }
}

// Replaces every <img> whose source is not an inline `data:` image with its
// alt text. Runs after the hardener, which resolves relative and
// protocol-relative sources, so anything left that is not `data:` would make
// a request.
function dropRemoteImages() {
  return (tree: Root) => {
    visit(tree, 'element', (node, index, parent) => {
      if (node.tagName !== 'img' || !parent || index === undefined) return
      const src = node.properties.src
      if (typeof src === 'string' && src.startsWith('data:image/')) return
      const alt = node.properties.alt
      parent.children.splice(
        index,
        1,
        ...(typeof alt === 'string' && alt
          ? [{ type: 'text' as const, value: alt }]
          : [])
      )
      return [SKIP, index]
    })
  }
}

interface MarkdownProps {
  content: string
  className?: string
  isUser?: boolean
  isStreaming?: boolean
  isAnimating?: boolean
}

// Hoisted so their identity is stable across renders — Streamdown is memoized
// with a shallow prop compare, and fresh literals here would defeat it, forcing
// a full re-parse + re-highlight on every streamed token.
const REMARK_PLUGINS = [remarkGfm, remarkMath, disableIndentedCodeBlockPlugin]
const REHYPE_PLUGINS = [rehypeKatex, restrictChatLinks, defaultRehypePlugins.harden, dropRemoteImages]
const STREAMDOWN_PLUGINS = { code, cjk }
const LINK_SAFETY = { enabled: false }

// While streaming, the active (unclosed) code block would be re-highlighted by
// Shiki over its whole contents on every commit — O(n) per render, and the
// highlighted DOM is re-inserted wholesale (slow on webkitgtk). Render plain
// text instead; Streamdown's Shiki CodeBlock takes over once streaming ends.
type CodeProps = React.HTMLAttributes<HTMLElement> & ExtraProps
function StreamingCode({ node, className, children, ...props }: CodeProps) {
  const pos = node?.position
  const isInline = !pos || pos.start.line === pos.end.line
  if (isInline) {
    return (
      <code
        className={cn(
          'rounded bg-muted px-1.5 py-0.5 font-mono text-sm',
          className
        )}
        {...props}
      >
        {children}
      </code>
    )
  }
  return (
    <pre className="my-4 overflow-x-auto rounded-lg border border-border bg-secondary p-4">
      <code className={cn('font-mono text-sm', className)}>{children}</code>
    </pre>
  )
}
const STREAMING_COMPONENTS: Components = { code: StreamingCode }

const ZWSP = '​'

// "word**,**" is neither left- nor right-flanking per CommonMark, so the markers
// render literally; a ZWSP just inside restores flanking (U+200B is treated as
// non-punctuation). Runs after math/code are masked, so '_'/'*' subscripts never
// get a ZWSP — that made KaTeX warn "Unrecognized Unicode character 8203".
const fixEmphasisFlanking = (s: string): string =>
  s.includes('*') || s.includes('_')
    ? s
        .replace(/(?<=[\p{L}\p{N}])(\*\*?|__?)(?=[^\s\p{L}\p{N}*_])/gu, `$1${ZWSP}`)
        .replace(/(?<=[^\s\p{L}\p{N}*_])(\*\*?|__?)(?=[\p{L}\p{N}])/gu, `${ZWSP}$1`)
    : s

// Placeholder-protection pipeline (adapted from llama.cpp's webui / LibreChat):
// remark-math only parses $…$/$$…$$, so brackets need converting — but converting,
// escaping currency, and fixing emphasis would corrupt each other unless code and
// math are first lifted out as opaque tokens. PUA-delimited tokens can't occur in
// model output and are inert to every transform here.
const CODE_BLOCK = /(```[\s\S]*?```|`[^`\n]+`)/g
const BRACKET_MATH =
  /(\$\$[\s\S]*?\$\$|(?<!\\)\\\[[\s\S]*?\\\]|(?<!\\)\\\(.*?\\\))/g
const tok = (kind: 'C' | 'L', n: number) => `\uE000${kind}${n}\uE000`

// Mask genuine inline $…$ math; leave currency/identifiers ($5, a$b) in place.
// Per-line so a stray $ can't swallow the rest.
const maskInlineMath = (content: string, store: string[]): string => {
  if (!content.includes('$')) return content
  return content
    .split('\n')
    .map((line) => {
      if (!line.includes('$')) return line
      let out = ''
      let pos = 0
      while (pos < line.length) {
        const open = line.indexOf('$', pos)
        if (open === -1) {
          out += line.slice(pos)
          break
        }
        const close = line.indexOf('$', open + 1)
        if (close === -1) {
          out += line.slice(pos)
          break
        }

        const before = open > 0 ? line[open - 1] : ''
        const afterOpen = line[open + 1]
        const beforeClose = open + 1 < close ? line[close - 1] : ''
        const afterClose = close + 1 < line.length ? line[close + 1] : ''

        const empty = close === open + 1
        const gluedLeft = /[A-Za-z0-9_$-]/.test(before)
        const looksMoney =
          /[0-9]/.test(afterOpen) &&
          (/[A-Za-z0-9_$-]/.test(afterClose) || beforeClose === ' ')

        if (empty || gluedLeft || looksMoney) {
          out += line.slice(pos, open + 1)
          pos = open + 1
          continue
        }

        out += line.slice(pos, open)
        store.push(line.slice(open, close + 1))
        out += tok('L', store.length - 1)
        pos = close + 1
      }
      return out
    })
    .join('\n')
}

// Cache for normalized LaTeX content
const latexCache = new Map<string, string>()

const normalizeLatex = (input: string): string => {
  if (latexCache.has(input)) return latexCache.get(input)!

  const code: string[] = []
  const math: string[] = []

  let s = input
    .replace(CODE_BLOCK, (m) => tok('C', code.push(m) - 1))
    .replace(BRACKET_MATH, (m) => tok('L', math.push(m) - 1))

  s = maskInlineMath(s, math)
  s = s.replace(/\$(?=\d)/g, '\\$') // leftover currency renders literally
  s = fixEmphasisFlanking(s)

  // Restore math, converting bracket delimiters to $… / $$… for remark-math.
  s = s.replace(/\uE000L(\d+)\uE000/g, (_, n) => {
    const expr = math[Number(n)]
    if (expr.startsWith('\\[')) return `$$\n${expr.slice(2, -2).trim()}\n$$`
    if (expr.startsWith('\\(')) return `$${expr.slice(2, -2).trim()}$`
    return expr
  })
  s = s.replace(/\uE000C(\d+)\uE000/g, (_, n) => code[Number(n)])

  if (latexCache.size > 100) {
    const firstKey = latexCache.keys().next().value || ''
    latexCache.delete(firstKey)
  }
  latexCache.set(input, s)
  return s
}

function Anchor(props: React.AnchorHTMLAttributes<HTMLAnchorElement>) {
  const { href, children } = props
  // Keep the HTTP(S)-only chat policy even if the hardener repairs a URL, and never
  // navigate the WebView itself: open the link in the phone's browser.
  if (!isWebLink(href)) return <>{children}</>
  return (
    <a
      href={href}
      rel="noopener noreferrer"
      onClick={(e) => {
        e.preventDefault()
        openExternal(href!)
      }}
    >
      {children}
    </a>
  )
}
const COMPONENTS: Components = { a: Anchor }
const STREAMING_WITH_LINKS: Components = { ...COMPONENTS, ...STREAMING_COMPONENTS }

function RenderMarkdownComponent({ content, className, isUser, isAnimating, isStreaming }: MarkdownProps) {
  // Coalesce rapid streamed updates: React renders the deferred (older) value
  // while new tokens arrive and skips intermediates under load. Always converges
  // to the latest value. Non-streaming uses content as-is.
  const deferredContent = useDeferredValue(content)
  const effectiveContent = isStreaming ? deferredContent : content

  // normalizeLatex is O(n) over the full string; skip it while streaming.
  const normalizedContent = useMemo(
    () => (isStreaming ? effectiveContent : normalizeLatex(effectiveContent)),
    [effectiveContent, isStreaming]
  )

  return (
    <div dir="auto" className={cn('markdown wrap-break-word select-text', isUser && 'is-user', className)}>
      <Streamdown
        mode={isStreaming ? 'streaming' : 'static'}
        parseIncompleteMarkdown={isStreaming ?? false}
        animate={isStreaming ? false : (isAnimating ?? true)}
        animationDuration={500}
        linkSafety={LINK_SAFETY}
        className={cn('size-full [&>*:first-child]:mt-0 [&>*:last-child]:mb-0', className)}
        remarkPlugins={REMARK_PLUGINS}
        rehypePlugins={REHYPE_PLUGINS}
        components={isStreaming ? STREAMING_WITH_LINKS : COMPONENTS}
        plugins={STREAMDOWN_PLUGINS}
      >
        {normalizedContent}
      </Streamdown>
    </div>
  )
}

export const RenderMarkdown = memo(
  RenderMarkdownComponent,
  (prev, next) => prev.content === next.content && prev.isStreaming === next.isStreaming
)
