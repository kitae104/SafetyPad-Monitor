/**
 * Above a breakpoint, detail panels sit beside their list/map and are already in view (Tailwind `xl` = 1280px,
 * `lg` = 1024px; pass the one the page's grid uses). Below it they are stacked underneath, often screens away
 * from what the user just tapped.
 */
export const sideBySide = (minWidth = 1280) => window.matchMedia(`(min-width: ${minWidth}px)`).matches

/** Stacked layouts only: smoothly bring `el` into view and focus it so screen readers announce it. */
export function revealPanel(el: HTMLElement | null, minWidth = 1280) {
  if (!el || sideBySide(minWidth)) return
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  el.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' })
  el.focus({ preventScroll: true })
}
