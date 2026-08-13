# Frontend manual regression checklist

The zero-build Vue frontend has three test layers:

1. JUnit checks static assets, CSP, same-origin requests, and i18n dictionary
   structure during `./mvnw test`.
2. `scripts/frontend-regression/check.mjs` drives a local browser when rendering
   or interaction is required.
3. This checklist covers visual quality and workflows that still require human
   judgment.

Use only an isolated development port and database. Never test against port 8080
or `data/board`; follow the isolation rules in `AGENTS.md`.

## Layout and responsive behavior

- [ ] Test desktop 1440×900, tablet 768×1024, and mobile 375×812.
- [ ] Add a task with a 100+ character title, long description, and long URL;
      confirm wrapping and clipping do not break the layout.
- [ ] Open a dependency graph with at least 37 nodes at all three sizes; confirm it
      remains navigable and nodes do not overlap beyond recognition.

## Keyboard and focus

- [ ] Open a task drawer, press Escape, and confirm focus returns to the element
      that opened it.
- [ ] Continue with Tab and confirm focus order resumes from that element instead
      of the top of the page.

## Filters and URL state

- [ ] Exercise project prefix, category, assignee, prerequisite, and claimable
      filters; confirm the query string updates.
- [ ] Reload the complete filtered URL and confirm both controls and results are
      restored.

## History and evidence

- [ ] Confirm a task with multiple transitions shows complete oldest-to-newest
      history.
- [ ] Confirm BLOCKED evidence, including `blockingTasks`, is readable.
- [ ] Confirm structured completion evidence from `complete_task` renders correctly.

## Dependency graph

- [ ] Edges point from prerequisite to dependent task.
- [ ] Isolated nodes remain visible.
- [ ] Expand/collapse and category filters update both nodes and edges correctly.

## SSE behavior

- [ ] With the board open, claim or complete a task from another client and confirm
      the page updates without a reload.
- [ ] Restart the isolated development server by its exact PID. Confirm connection
      state recovers and current filter/URL state is preserved.

## Network behavior

- [ ] Clear the browser Network panel and reload once. Confirm each REST resource is
      requested once, with no duplicate initial fetches.

## Language quality

- [ ] Review the project list, board, dependency graph, empty-state onboarding,
      task drawer, errors, confirmations, blockers, and completion evidence in both
      English and Traditional Chinese.
- [ ] Confirm longer English labels do not stretch buttons, badges, or columns.
- [ ] Confirm blocker and notification fallback messages remain meaningful for
      free-form backend values.
- [ ] Trigger a real BLOCKED desktop notification in both languages and verify all
      title, ID, and project interpolations.
- [ ] Check translated `aria-label` and `title` attributes with keyboard navigation
      and a screen reader when accessibility-related text changes.

## Already automated

Do not repeat these manually unless their tests fail:

- CSP headers and absence of external HTTP resources;
- same-origin availability of Vue, fonts, JavaScript, CSS, API, and SSE resources;
- i18n key parity, placeholder parity, empty values, untranslated English values,
  and invalid static `t('key')` references;
- language switching, persisted locale, browser-language detection, unsupported
  locale fallback, raw-key detection, and interpolation rendering in
  `scripts/frontend-regression/check.mjs`.
