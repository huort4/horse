# Community launch and maintenance

This is the maintainer handoff for the October 12, 2026 community launch.
Discussions and the posts below are live. Repository documentation and forms
remain **prepared until their PR is merged into the default branch**. Merge is
left to maintainers. Re-check live state before any follow-up action.

## Verified starting point

- Official repository: `scarif-labs/horus`; default branch: `main`, audited at
  `719d4c232e35a9fd7a1170eb58cf907633170b28`.
- Discussions enabled; six default categories; no discussions or pins before
  launch. All six category IDs were preserved.
- No repository contribution guide, code of conduct, security policy, or issue
  and discussion forms; the organization's `.github` repository supplied no
  corresponding defaults. Existing MIT license and README preserved.
- Authenticated GitHub CLI 2.102.0 supports preview discussion creation with
  `--category`, `--title`, and `--body-file`; authenticated account has admin
  permission. Public GraphQL schema has no category-management or discussion
  pinning mutations. The available browser was signed out.
- Private vulnerability reporting and repository content reports were disabled.
  No dedicated private security or moderation contact was found.
- The only open PR at audit time was [Codex installation fix #9](https://github.com/scarif-labs/horus/pull/9).
  It and all existing issues remain untouched.

## Categories and configuration

Retain the existing names and slugs to keep navigation and form matching stable.
Ideas serves the feature-request role, and Q&A serves the help role. There is
no need for duplicate categories or additional sections. Polls is preserved.

| Existing category / slug | Existing emoji | Intended format | Description to set |
| --- | --- | --- | --- |
| Announcements / `announcements` | 📣 | Announcement | Official Horus updates from Scarif Labs maintainers. |
| General / `general` | 💬 | Open discussion | Conversations about using Horus and participating in the project. |
| Q&A / `q-a` | 🙏 | Question and answer | Setup, troubleshooting, and device compatibility questions. Mark helpful answers. |
| Ideas / `ideas` | 💡 | Open discussion | Feature requests and workflow feedback, with use cases and alternatives. |
| Show and tell / `show-and-tell` | 🙌 | Open discussion | Community projects, scripts, and workflows built with Horus. |
| Polls / `polls` | 🗳 | Poll | Existing category; retain its current configuration. |

**PARTIAL:** names, IDs, slugs, emojis, and existing descriptions were read back
from GitHub; Q&A's answerable format was verified. GitHub's public category
schema exposes `isAnswerable`, but does not distinguish announcements from
other non-answerable formats. The other formats need verification in the
category editor; the tailored descriptions above have not been applied.

**BLOCKED:** Development & RFCs is absent. Create one category with emoji 🛠️,
name **Development & RFCs**, description **Architecture, technical proposals,
and contributor workflow improvements.**, and **Open-ended discussion** format.
Do not guess its slug or install a form for it before querying the new category.

### Minimal UI actions

1. Sign in with repository write/maintain/admin access and open
   [Horus Discussions](https://github.com/scarif-labs/horus/discussions).
   Use the pencil beside **Categories** to open the category manager.
2. Edit the five existing non-poll categories above: keep their names and emojis,
   set the listed descriptions, verify the listed formats, and save each one.
   Preserve Polls. Do not delete or recreate any category.
3. Click **New category**, enter the Development & RFCs settings above, and
   create it. Query `discussionCategories { nodes { id name slug isAnswerable } }`
   through `gh api graphql` to capture its actual ID and slug. If a form is
   added later, its filename must match that returned slug.
4. Search for an equivalent developer-experience RFC. If absent, publish
   [the retained draft](developer-experience-rfc.md) in Development & RFCs,
   omitting its draft-status header and separator. Re-check #9's current state
   before posting because the draft references an open PR. Verify the resulting
   title, category, body, and URL by querying GitHub.
5. Open [the welcome announcement](https://github.com/scarif-labs/horus/discussions/12),
   select **Pin discussion** in the sidebar, then confirm **Pin discussion**.
   A global pin is sufficient; verify it appears above the repository discussion
   list and in `pinnedDiscussions`. No pin was applied during this launch.
6. After creating Development & RFCs, update the welcome post's category paragraph
   and contribution guidance to link its verified URL and remove the temporary
   General fallback.

## Published launch topics

Each was created once in its existing category and queried again to verify the
exact title, body, category slug, and returned URL.

- [Welcome to Horus Discussions!](https://github.com/scarif-labs/horus/discussions/12) — Announcements.
- [What are you building with Horus?](https://github.com/scarif-labs/horus/discussions/13) — Show and tell.
- [Linux distribution support: compatibility and priorities](https://github.com/scarif-labs/horus/discussions/14) — Ideas.
- [Horus device compatibility reports](https://github.com/scarif-labs/horus/discussions/15) — Q&A.
- **BLOCKED:** developer-experience RFC retained locally in this directory;
  publication depends on the correct category.

The distribution post distinguishes Alpine in `main`/v1.0.4 from the published
[Debian rootfs asset](https://github.com/scarif-labs/horus/releases/tag/rootfs-debian-13.7-20261005),
which is not an app release. It links #3 and #7 and makes no delivery promise.

## Repository activation and reporting

**PARTIAL:** CONTRIBUTING.md, CODE_OF_CONDUCT.md, SECURITY.md, the bug form,
issue chooser, README community links, and the `ideas`, `q-a`, and
`show-and-tell` discussion forms are prepared for review. Their filenames use
GitHub's actual category slugs. They become available through normal GitHub
community navigation after the PR is merged. General and Announcements retain
freeform composition; Polls does not support category forms.

After review and merge, verify the README links, repository community profile,
issue chooser, and the three **New discussion** forms in the web UI. Check
required fields and help text without submitting dummy issues or discussions.
Until then, default-branch policy URLs and new contribution links are pending
activation; the live posts use existing official resource links.

**PARTIAL:** security policy honestly describes the absence of private reporting.
A repository administrator should open
[Security settings](https://github.com/scarif-labs/horus/settings/security_analysis),
enable **Private vulnerability reporting**, and confirm **Report a vulnerability**
appears on [Security](https://github.com/scarif-labs/horus/security). Then update
the dated policy and the welcome post to the verified private route.
This recommendation has not been applied.

**PARTIAL:** the code of conduct provides participation standards and a
proportionate moderation process, but no confidential maintainer reporting
channel. Maintainers should choose a monitored private contact, verify it, and
add it to the policy. No contact address, staffing, response deadline, security
support window, or reward program is invented. GitHub abuse reports go to
GitHub, not to project maintainers. Repository content reports can be considered
separately; they were not enabled by this change.

## Keep the community useful

Link related Discussions and Issues when questions become reproducible work.
Mark useful Q&A replies as answers, welcome first-time contributors, and follow
the code of conduct when moderating. Avoid turning votes into promises or adding
categories without a clear recurring use. Update compatibility information with
versions and observed evidence rather than a device-support guarantee.

For follow-up publication, query all discussions with pagination and review
semantic equivalents before creating a post. If a creation request returns an
ambiguous error, query again before retrying; never assume failure means the
post does not exist.

## Reference guidance

- [GitHub category management](https://docs.github.com/en/discussions/managing-discussions-for-your-community/managing-categories-for-discussions)
- [GitHub discussion forms](https://docs.github.com/en/discussions/managing-discussions-for-your-community/syntax-for-discussion-category-forms)
- [GitHub issue forms](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-issue-forms)
- [GitHub form schema](https://docs.github.com/en/communities/using-templates-to-encourage-useful-issues-and-pull-requests/syntax-for-githubs-form-schema)
- [GitHub moderation](https://docs.github.com/en/discussions/managing-discussions-for-your-community/moderating-discussions)
- [GitHub pinning](https://docs.github.com/en/discussions/managing-discussions-for-your-community/managing-discussions#pinning-a-discussion-globally)
- [GitHub community health files](https://docs.github.com/en/communities/setting-up-your-project-for-healthy-contributions/creating-a-default-community-health-file)
