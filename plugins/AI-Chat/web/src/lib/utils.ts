/*
 * cn() and disableIndentedCodeBlockPlugin() are from Jan (https://github.com/janhq/jan),
 * web-app/src/lib/utils.ts. Copyright 2025 Menlo Research. Apache License 2.0; see NOTICE.
 */
import { type ClassValue, clsx } from 'clsx'
import { twMerge } from 'tailwind-merge'
import type { Node, Position } from 'unist'
import type { Code, Paragraph, Parent, Text } from 'mdast'
import { visit } from 'unist-util-visit'
import type { VFile } from 'vfile'

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

/**
 * Remark plugin that disables indented code block syntax.
 * Converts indented code blocks to plain text paragraphs,
 * while preserving fenced code blocks with backticks.
 */
export function disableIndentedCodeBlockPlugin() {
  return (tree: Node, file: VFile) => {
    visit(tree, 'code', (node: Code, index, parent: Parent | undefined) => {
      if (node.lang === null && node.meta === null && parent && typeof index === 'number') {
        const nodePosition: Position | undefined = node.position
        if (nodePosition !== undefined && String(file.value).at(nodePosition.start.offset!) !== '`') {
          const textNode: Text = { type: 'text', value: node.value, position: nodePosition }
          const paragraphNode: Paragraph = { type: 'paragraph', children: [textNode], position: nodePosition }
          parent.children[index] = paragraphNode
        }
      }
    })
  }
}

/** A short, sortable, unique id. */
export function newId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 8)
}
