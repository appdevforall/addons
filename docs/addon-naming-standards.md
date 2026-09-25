The problem

Every addon has up to six places its name or slug appears: the published name on the site, the name in the Code on the Go manager that installs it, the artifact filename, the GitHub src directory, the documentation HTML filename, and the title shown inside that documentation page. The current addons already show drift: different hyphenation, plurals, abbreviations, or leftover names from earlier drafts. This standard fixes that by making five of the six values derived automatically from one: the GitHub src directory name.

The rules are the same for all four addon types. Only the artifact filename differs by type — a plugin compiles to a .cgp, a template zips to a .cgt (ADFA-6252).

Rules

GitHub src directory. This is the single source of truth, and the only one of the six values that's a human decision. Name it in MixedCase, with words separated by hyphens (e.g., APK-Analyzer, Template-Manager). Everything else below is derived mechanically from this — no judgment calls about which words are "generic" versus part of the addon's identity.

Display name, derived from the GitHub src directory with the area prefix removed:

Replace hyphens with spaces (APK-Analyzer → "APK Analyzer"). The display name then appears verbatim, with no variation, in exactly three places:

The published name on the site

The name in the Code on the Go manager that installs it — the Plugin Manager for a plugin, the Templates manager for a template

The title/heading in the documentation HTML page

Slug, derived from the GitHub src directory:

Lowercase the directory name (APK-Analyzer → apk-analyzer). The slug is then reused exactly in both of these:

the artifact filename → slug.cgp for a plugin, slug.cgt for a template

the documentation HTML filename → slug.html

No addon may have more than one spelling of its own slug across these two. The GitHub src directory itself keeps its own MixedCase spelling because it's the source, not a derived copy.

Worked example: a plugin

Display Name: APK Analyzer

Artifact

Value

GitHub src directory (source of truth)

/plugins/APK-Analyzer

Published name on site

APK Analyzer

Name in the Code on the Go Plugin Manager

APK Analyzer

Title inside documentation HTML

APK Analyzer

Artifact filename

apk-analyzer.cgp

Documentation HTML filename

apk-analyzer.html

Worked example: a template

Display Name: Flutter Templates

Artifact

Value

GitHub src directory (source of truth)

/templates/Flutter-Templates

Published name on site

Flutter Templates

Name in the Code on the Go Templates manager

Flutter Templates

Title inside documentation HTML

Flutter Templates

Artifact filename

flutter-templates.cgt

Documentation HTML filename

flutter-templates.html

Note that the names of the individual templates inside a bundle are a separate matter. Those come from each template/template.json "name" field and are what a user picks on the New Project screen; they are not derived from the directory name. The directory name names the bundle.

Naming guidelines

Leave implementation details out of the directory name. Naming an addon after its implementation locks you in if that implementation ever changes. Put that detail in the documentation body instead.

Drop generic type-words when naming the directory. Words that describe the artifact rather than the addon's identity ("plugin," "CGP," "CGT," "Documentation") shouldn't be part of the directory name. Keep words that are genuinely part of the name even if generic-sounding on their own. E.g., "Manager" stays in "Template-Manager," because the addon's name is genuinely "Template Manager," not just "Template."

Prefer the spelling that most of the addon's existing artifacts already use, unless one spelling is clearly more accurate or more common going forward.

Match number and form exactly. Decide once whether the directory name is singular or plural — that choice propagates automatically to the display name everywhere it's used, so get it right at the source. Flutter-Templates is plural because the bundle genuinely holds five of them.

No stray path fragments. Branch names, parent folders, or build artifacts (e.g., a main/ prefix) should never leak into the GitHub src directory name or the doc path.
