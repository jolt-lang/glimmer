(ns glimmer.backend
  "The seam between glimmer's portable reactive core and a concrete UI toolkit.

  glimmer.core owns the component model and the reconciler: it turns hiccup into
  an instance tree and decides when an element must be created, patched, moved or
  dropped. It knows nothing about what an element *is*. A backend fills that in —
  it maps hiccup tags to native widgets, applies props, manages container
  children, and (optionally) owns the UI event loop. The GTK4 backend lives in
  the separate glimmer-gtk project; a Qt or TUI backend implements the same map
  of functions and the whole reactive core is reused unchanged.

  A backend is a plain map, installed with `register!`. Required keys:

    :name           a keyword identifying the backend, e.g. :gtk4
    :create!        (fn [tag props] -> widget)   construct, apply props, wire events
    :apply-props!   (fn [tag widget props])      re-apply props on re-render
    :append-child!  (fn [parent-tag parent child])
    :remove-child!  (fn [parent-tag parent child])
    :replace-child! (fn [parent-tag parent old-child new-child])

  Optional keys:

    :reorder-child! (fn [parent-tag parent child sibling]) move `child` to sit
                    immediately after `sibling` (nil = first position). Backends
                    whose containers have no meaningful order may omit it; keyed
                    reconciliation then still reuses widgets, it just can't
                    reorder them.
    :schedule       (fn [work]) run a zero-arg thunk on the UI thread. Needed by
                    backends that set `loop-running?`; the default runs `work`
                    inline, which is what a headless backend wants.
    :run            (fn [opts mount-root!]) start the app: create a top-level
                    container, call (mount-root! container container-tag) to
                    mount the root component into it, then run the event loop
                    (blocking) with `loop-running?` set. Backing glimmer.core/run.
    :text->element  (fn [s] -> hiccup) how a bare string or number child renders.
                    Defaults to [:label {:label s}].

  A `widget` is whatever the backend wants it to be — a native pointer, a record,
  a map. The core never inspects one; it only hands it back."
  (:refer-clojure :exclude [run]))

;; The installed backend map, or nil when none has been registered yet. A single
;; global rather than a dynamic var: a process renders with one toolkit, and the
;; reconciler reaches for it from callbacks the toolkit itself invokes, where a
;; thread-local binding would not be in scope.
(defonce ^:private impl (atom nil))

;; Set true by a backend while its event loop owns the main thread. The
;; reconciler reads it to decide whether a reactive change can re-render inline
;; (headless, or before the loop starts) or must be marshalled onto the UI thread
;; via `schedule` — most toolkits reject widget mutation from other threads.
(defonce loop-running? (atom false))

(defn register!
  "Install `backend` (a map, see the namespace docstring) as the current backend.
  Requiring a backend namespace normally does this for you."
  [backend]
  (reset! impl backend)
  nil)

(defn current
  "The installed backend map, or nil."
  []
  @impl)

(defn installed?
  "True once a backend has been registered."
  []
  (some? @impl))

(defn- op [k]
  (let [b @impl]
    (when (nil? b)
      (throw (ex-info (str "glimmer: no backend registered — require a backend "
                           "namespace (e.g. glimmer-gtk.core) before rendering")
                      {:op k})))
    (or (get b k)
        (throw (ex-info (str "glimmer backend " (:name b) " does not implement " k)
                        {:backend (:name b) :op k})))))

;; --- element lifecycle -------------------------------------------------------
(defn create!
  "Construct a fresh widget for `tag` with `props`, wiring any :on-* handlers.
  Children are NOT added here — the reconciler appends them, so it can reuse
  existing children across renders."
  [tag props]
  ((op :create!) tag props))

(defn apply-props!
  "Re-apply `props` to an existing widget (the re-render path)."
  [tag widget props]
  ((op :apply-props!) tag widget props))

;; --- container children ------------------------------------------------------
(defn append-child!
  "Add `child` to the end of `parent`."
  [parent-tag parent child]
  ((op :append-child!) parent-tag parent child))

(defn remove-child!
  "Remove `child` from `parent`."
  [parent-tag parent child]
  ((op :remove-child!) parent-tag parent child))

(defn replace-child!
  "Replace `old-child` with `new-child` at the same position in `parent`."
  [parent-tag parent old-child new-child]
  ((op :replace-child!) parent-tag parent old-child new-child))

(defn reorder-child!
  "Move `child` to sit immediately after `sibling` (nil = first position) within
  `parent`. A no-op when the backend has no ordered containers."
  [parent-tag parent child sibling]
  (when-let [f (:reorder-child! @impl)]
    (f parent-tag parent child sibling))
  nil)

;; --- UI thread & event loop --------------------------------------------------
(defn schedule
  "Run zero-arg `work` on the UI thread, asynchronously. Falls back to running it
  inline when the backend has no loop to post onto."
  [work]
  (if-let [f (:schedule @impl)]
    (f work)
    (work)))

(defn text->element
  "The hiccup a bare string or number child renders as."
  [s]
  (if-let [f (:text->element @impl)]
    (f s)
    [:label {:label s}]))

(defn run
  "Start the backend's application loop. `mount-root!` is called with the
  top-level container widget and its tag once it exists; the loop then blocks."
  [opts mount-root!]
  ((op :run) opts mount-root!))
