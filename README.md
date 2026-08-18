# glimmer

A reactive GUI toolkit for [jolt](https://github.com/jolt-lang/jolt). You write
reagent-style components that return hiccup; glimmer renders them to native
widgets and keeps the widget tree in sync as reactive state changes.

This project is the portable half: reactive cells, the component model and the
reconciler. It has no dependencies and knows nothing about any toolkit. The
widgets themselves come from a backend:

- [glimmer-gtk](https://github.com/jolt-lang/glimmer-gtk) — GTK4
- [glimmer-tui](https://github.com/jolt-lang/glimmer-tui) — the terminal, over ncursesw

```clojure
(ns myapp
  (:require [glimmer.ratom :as r :refer [atom]]
            [glimmer.core :as ui]
            [glimmer-gtk.core]))            ; installs the GTK4 backend

(defn counter []
  (let [count (atom 0)]
    (fn []
      [:vbox {:spacing 12}
       [:label {:label (str "Count: " @count)}]
       [:hbox {:spacing 8}
        [:button {:label "- 1" :on-click #(swap! count dec)}]
        [:button {:label "+ 1" :on-click #(swap! count inc)}]
        [:button {:label "reset" :on-click #(reset! count 0)}]]])))

(defn -main [& _]
  (ui/run counter :title "counter" :width 320 :height 160))
```

The outer `let` runs once (so `count` persists); the inner `fn` re-runs whenever
`@count` changes, and glimmer patches the live widgets in place instead of
rebuilding them. Only the backend require names a toolkit — swapping GTK for
another backend leaves the rest of that file alone.

## Running

```sh
jolt test      # the whole suite, headless: no toolkit and no display needed
```

## Components

Two shapes, exactly like reagent:

- **Form-1** — a function returning hiccup. Re-invoked on every render.
- **Form-2** — a function returning a render fn. The outer fn runs once on mount
  (so local atoms persist); the returned fn renders. Use this for component-local
  state.

Components are invoked as `[my-component arg ...]`. They produce no widget of
their own — they expand to native elements — but each one is its own reactive
unit: a cell it reads during render re-runs *only that component*.

## Reactive state

All in `glimmer.ratom`, all read with `@`:

- **`(atom x)`** — a mutable reactive cell. Read with `@`, write with `reset!`/`swap!`.
- **`(cursor src path)`** — a writable lens into the map atom `src` at `path`.
  Reads return `(get-in @src path)`; writes go back through `assoc-in`.
- **`(reaction expr)`** — a read-only derived cell. `expr` runs immediately and
  again whenever a reactive cell it derefs changes. Other components subscribe
  by reading it with `@` during render.

```clojure
(let [state   (atom {:count 0 :name "x"})
      count   (cursor state [:count])
      label   (reaction (str "count is " @count))]
  @label)   ; => "count is 0"
```

### Swapping in a backend

`atom`, `cursor` and `reaction` all implement `glimmer.ratom/IReactiveCell`, the
protocol that `@`, `reset!`, `swap!` and dependency tracking dispatch through.
Implement it to back the reactive atom with something else — for example a
durable atom that persists every mutation to a database:

```clojure
(require '[glimmer.ratom :as r :refer [atom]])

(defrecord DurableAtom [id db state watches]
  r/IReactiveCell
  (-value [_] @state)
  (-reset! [this v]
    (let [old @state]
      (when (not= old v)
        (reset! state v)
        (swap! db assoc id v)          ; persist here
        (r/-notify-watches! this))
      v))
  (-add-watch! [_ w] (swap! watches conj w))
  (-remove-watch! [_ w] (swap! watches disj w))
  (-notify-watches! [this] (doseq [w @watches] (w this))))
```

A backend cell is a drop-in replacement: `@`, `reset!`, `swap!`, `cursor` and
`reaction` all work on it unchanged.

The protocol contract is small: `-value` reads the current value without
registering a watcher; `-reset!` writes the value, notifies watchers only when
it changes, and returns it; `-add-watch!`/`-remove-watch!` manage watcher fns
(each receives the firing cell); `-notify-watches!` fires them. Read-only cells
(reactions) signal by throwing from `-reset!`.

## Hiccup

Elements are `[:tag props? & children]`. `props` is an optional map; children may
be native elements, component invocations (`[my-component arg]`), strings, or
numbers (rendered as labels). `nil` children are skipped and seqs are spliced, so
`(when cond [...])` leaves no hole and `(for [t tasks] [row t])` renders one
widget per task.

Which tags and props exist is the backend's vocabulary — see
[glimmer-gtk](https://github.com/jolt-lang/glimmer-gtk) for the GTK4 set
(`:vbox`, `:label`, `:button`, `:on-click`, …).

`:key` is the one prop the core reads itself: it makes a child's identity stable
across renders. See below.

## Reconciliation

Children are matched **positionally** by default: at each position the existing
widget is reused when the tag matches (props re-applied) and replaced when it
doesn't; surplus children are removed and new ones appended.

When every child in a list carries a `:key`, they are matched by **key** instead.
A row's widget, its signal handlers and its component-local state then follow the
item across insertions, removals and reorders, so a handler may safely close over
per-item state:

```clojure
(into [:vbox {}]
      (for [{:keys [id text]} @items]
        [task-row {:key id} id text]))
```

A key may be given in the props map (`[:label {:key id}]`) or as metadata
(`^{:key id} [:label ...]`), on native elements and component invocations alike.
A list that mixes keyed and unkeyed children falls back to positional matching,
so a missing key never silently mis-reconciles.

## Backends

A backend is a plain map of functions installed with `glimmer.backend/register!`.
It says what a widget is and how to make, patch and arrange one; the core does
the rest. The full contract is in the `glimmer.backend` namespace docstring:

```clojure
{:name           :gtk4
 :create!        (fn [tag props] widget)          ; construct, apply props, wire events
 :apply-props!   (fn [tag widget props])          ; re-apply on re-render
 :append-child!  (fn [parent-tag parent child])
 :remove-child!  (fn [parent-tag parent child])
 :replace-child! (fn [parent-tag parent old new])
 ;; optional
 :reorder-child! (fn [parent-tag parent child sibling])
 :schedule       (fn [work])                      ; run a thunk on the UI thread
 :run            (fn [opts mount-root!])          ; the event loop, backing ui/run
 :text->element  (fn [s] hiccup)}                 ; default [:label {:label s}]
```

A `widget` is whatever the backend wants — a native pointer, a record, a map. The
core never inspects one, it only hands it back.

Three implementations exist to work from: `glimmer-gtk.core` (over GTK4),
`glimmer-tui.core` (over ncursesw, which also has to supply focus and an input
loop), and `glimmer.mock-backend` in this project's tests (an in-memory tree,
which is how the reconciler is tested without a display).

Threading: a toolkit that rejects widget mutation off its main thread sets
`glimmer.backend/loop-running?` while its loop runs and supplies `:schedule`. The
core then defers re-renders onto the UI thread — that is what lets an nREPL eval
on a worker thread mutate a ratom and repaint safely. With no loop running,
renders stay synchronous, which is what makes the tests deterministic.

## Architecture

Three namespaces:

- **`glimmer.ratom`** — reactive cells and auto-dependency tracking, behind the
  `IReactiveCell` protocol (so backends can be swapped in). While a component
  renders, its re-render fn is bound as the current watcher; any cell read with
  `@` during that render subscribes the component to the cell.
- **`glimmer.backend`** — the seam described above: the registry plus the
  dispatch functions the reconciler calls.
- **`glimmer.core`** — the component model, the reconciler (positional and
  keyed), mount/unmount, and `run`/`reload!` on top of the backend's loop.

The reactive model is a port of the one in
[mr-clean](https://github.com/clojure-ic/mr-clean).

## Live development

While an app runs you can mutate a `defonce` reactive cell from the REPL and the
UI repaints. After redefining components, `(glimmer.core/reload!)` re-mounts the
root in the same window; pass a fn to swap the root component itself. State kept
in top-level `defonce` cells survives a reload — state in a component's own `let`
does not.

## Status

Early. The reactive core (`atom`/`cursor`/`reaction`), the component model and
the reconciler (positional, keyed, mount/unmount, subscription teardown) are
covered by the headless test suite; the GTK pipeline is exercised by the smoke
tests in glimmer-gtk.
