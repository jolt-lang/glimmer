# glimmer

A reactive GUI toolkit for [jolt](../jolt) over **GTK4** — reagent-style
components that render to native widgets, with automatic dependency tracking.
Local state lives in reactive atoms; `reset!`/`swap!` on a cell re-renders just
the components that read it, and the live GTK widget tree is patched in place
instead of rebuilt.

```clojure
(ns myapp
  (:require [glimmer.ratom :refer [atom]]        ; a reactive atom
            [glimmer.core :as ui]))

(defn counter []
  (let [count (atom 0)]                          ; local state, created once
    (fn []                                       ; render fn — re-runs on @count change
      [:vbox {:spacing 12}
       [:label {:label (str "Count: " @count)}]
       [:hbox {:spacing 8}
        [:button {:label "− 1" :on-click #(swap! count dec)}]
        [:button {:label "+ 1" :on-click #(swap! count inc)}]]])))

(defn -main [& _]
  (ui/run counter :title "counter" :width 320 :height 160))
```

## Install

GTK4 and GLib must be installed.

- macOS: `brew install gtk4` (Homebrew's `/opt/homebrew/lib` is searched first).
- Linux: `apt install libgtk-4-dev` (or your distro's equivalent).

The native libraries (`glib-2.0`, `gobject-2.0`, `gio-2.0`, `gtk-4`) are declared
in `deps.edn` under `:jolt/native` and loaded automatically when the namespaces
are required.

## Tasks

```
joltc test      # unit tests (glimmer.ratom — no display needed)
joltc smoke     # reactivity smoke against the live GTK loop (needs a display)
joltc counter   # interactive counter demo (opens a window, blocks)
joltc todo      # interactive todo demo (opens a window, blocks)
```

## How it works

Three layers:

- **`glimmer.ratom`** — reactive atoms, cursors, and reactions. While a component
  renders, `*current-watcher*` is bound; any cell read with `@` during that render
  subscribes the component to the cell, so a later `reset!`/`swap!` re-runs it.
- **`glimmer.ffi`** — thin `defcfn` bindings to GTK4 / GLib. No logic.
- **`glimmer.widget` / `glimmer.core`** — hiccup → GTK: a tag → widget constructor,
  props → setter calls, `:on-*` keys → GTK signals wired through `foreign-callable`,
  plus the component model (Form-1 and Form-2, like reagent) and a positional
  reconciler that reuses widgets where the shape matches and re-applies props.

### Why `@` works on reactive atoms

jolt's reader lowers `@x` to `(clojure.core/deref x)`, and
`clojure.core/{deref,reset!,swap!}` are host primitives bound to a single built-in
record type — so a custom `deftype` implementing the atom protocols can't hook into
`@`. `glimmer.ratom` instead captures the host implementations and rebinds the
`clojure.core` vars to dispatching versions that intercept reactive cells and
delegate to the host for everything else (plain atoms, futures, delays). This is
the same mechanism jolt itself uses to chain futures/promises into `deref`, and it
is a runtime library operation — no change to jolt's source.

### Signals and the blocking main loop

`g_application_run` blocks running the GTK main loop, so it is declared
`:blocking`, and every callback (the `:activate` handler, widget signals, the
auto-quit timeout) is a `:collect-safe` `foreign-callable` — exactly the pattern
described in `jolt.ffi`'s docstring.

## Hiccup reference

Elements are `[:tag props? & children]`. `props` is an optional map; `children`
may be native elements, component invocations (`[my-component arg]`), strings, or
numbers (rendered as labels). `nil` children are skipped.

- Tags: `:window`, `:box` (with `:orientation :horizontal|:vertical`), `:hbox`,
  `:vbox`, `:button`, `:label`, `:entry`, `:checkbutton`, `:separator`.
- Common props: `:title`, `:width`/`:height` (window), `:spacing`,
  `:orientation`, `:homogeneous` (box), `:label`/`:text`, `:active`
  (checkbutton), `:visible`, `:sensitive`, `:tooltip`.
- Events: `:on-click` (button), `:on-change` (entry — handler receives the text),
  `:on-activate` (entry), `:on-toggled` (checkbutton).

### Components

- **Form-1** — a function returning hiccup; re-invoked on every render.
- **Form-2** — a function returning a render fn; the outer fn runs once (so local
  atoms persist), the inner fn renders.

### Reconciliation

Children are matched **positionally**: at each position the existing widget is
reused when the tag matches (props re-applied) and replaced when it doesn't;
surplus children are removed, new ones appended. Keyed reordering is not yet
supported.

## Status

Early. The widget set is small and the reconciler is positional. The reactive core
(`atom`/`cursor`/`reaction`), the component model, and the GTK pipeline (mount,
re-render on change, clean shutdown) are exercised by the unit tests and the
reactivity smoke.
