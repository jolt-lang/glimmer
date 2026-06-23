# glimmer

A reactive GUI toolkit for [jolt](https://github.com/jolt-lang/jolt) over **GTK4**.
You write reagent-style components that return hiccup; glimmer renders them to
native GTK widgets and keeps the widget tree in sync as reactive state changes.

```clojure
(ns myapp
  (:require [glimmer.ratom :as r :refer [atom]]
            [glimmer.core :as ui]))

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
`@count` changes, and glimmer patches the live GTK widgets in place instead of
rebuilding them.

## Requirements

GTK4 and GLib must be installed.

- macOS: `brew install gtk4`
- Linux: `apt install libgtk-4-dev` (or your distro's equivalent)

The native libraries (`glib-2.0`, `gobject-2.0`, `gio-2.0`, `gtk-4`) are declared
in `deps.edn` under `:jolt/native` and loaded automatically when the namespaces
are required.

## Running

```sh
joltc test      # unit tests (glimmer.ratom — no display needed)
joltc smoke     # reactivity smoke against the live GTK loop (needs a display)
joltc counter   # interactive counter demo (opens a window, blocks)
joltc todo      # interactive todo demo (opens a window, blocks)
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

## Hiccup reference

Elements are `[:tag props? & children]`. `props` is an optional map; children
may be native elements, component invocations (`[my-component arg]`), strings, or
numbers (rendered as labels). `nil` children are skipped.

**Containers:** `:window` (single child), `:box` (`:orientation :horizontal|:vertical`),
`:hbox`, `:vbox`, `:frame` (single child, with an optional `:label`).

**Leaf widgets:** `:button`, `:label`, `:entry`, `:checkbutton`, `:separator`.

**Common props (apply to every widget):** margins and alignment, resolved from
idiomatic keywords at runtime (see [Enum constants](#enum-constants) below):

- `:margin` (all four sides), or `:margin-start`/`:margin-end`/`:margin-top`/`:margin-bottom`
- `:halign`/`:valign` — one of `:fill :start :end :center :baseline-fill :baseline-center`
- `:hexpand`/`:vexpand` — boolean

**Per-tag props:**

- Window: `:title`, `:width`, `:height`, `:visible`
- Box: `:orientation`, `:spacing`, `:homogeneous`
- Button: `:label`, `:tooltip`, `:sensitive`
- Label: `:label`/`:text`, `:markup` (Pango markup), `:xalign` (0.0–1.0)
- Entry: `:text`, `:placeholder`, `:sensitive`
- Checkbutton: `:label`, `:active`
- Frame: `:label`

**Events:**

- `:on-click` — button clicked. Handler takes no args.
- `:on-change` — entry text changed. Handler receives the current text.
- `:on-activate` — entry activated (Enter). Handler takes no args.
- `:on-toggled` — checkbutton toggled. Handler takes no args.

Signals are connected once at mount. Handlers should close over reactive cells
(not values), so the first render's closure stays correct for the widget's life.

## Enum constants

GTK enum/flag values (`GTK_ALIGN_START`, `GTK_ORIENTATION_VERTICAL`, …) are
resolved **at runtime from keyword nicks**, not maintained as a constant table.
Every GObject enum registers its members with a lowercase nick that *is* a
Clojure keyword (`:start`, `:fill`, `:horizontal`); `glimmer.genum` looks the nick
up in the GObject type registry and returns its integer value. So you write
`:halign :start`, `:orientation :vertical` — no `GTK_ALIGN_*` constants anywhere
in the library. A raw integer also works as a fallback.

One wrinkle: a type is only in the registry once its owning widget class has
initialized, so enum props are applied in the re-render path (where the widget
already exists). Plain `#define` numeric macros (rare here) can't be resolved
this way and are declared explicitly where truly needed.

## Reconciliation

Children are matched **positionally**: at each position the existing widget is
reused when the tag matches (props re-applied) and replaced when it doesn't;
surplus children are removed, new ones appended. Keyed reordering is not yet
supported — render lists in a stable order.

## Architecture

Five namespaces:

- **`glimmer.ratom`** — reactive cells and auto-dependency tracking. While a
  component renders, its re-render fn is bound as the current watcher; any cell
  read with `@` during that render subscribes the component to the cell.
- **`glimmer.ffi`** — thin `defcfn` bindings to GTK4 / GLib. No logic.
- **`glimmer.genum`** — resolves GObject enum members from keyword nicks
  (`:start`, `:fill`) to their integer values at runtime via the GObject type
  registry, so the library needs no enum-constant tables.
- **`glimmer.widget`** — hiccup to GTK: tag to constructor, props to setters,
  `:on-*` to GTK signals wired through `foreign-callable`.
- **`glimmer.core`** — the component model, positional reconciler, and the
  `g_application_run` app loop.

The reactive model is a port of the one in
[mr-clean](https://github.com/clojure-ic/mr-clean).

## Status

Early. The widget set is small and the reconciler is positional. The reactive
core (`atom`/`cursor`/`reaction`), the component model, and the GTK pipeline
(mount, re-render on change, clean shutdown) are exercised by the unit tests and
the reactivity smoke test.
