(ns glimmer.ratom
  "Reactive atoms for glimmer — a reagent-style reactive model, with no ties to
  any UI toolkit.

  Three reactive cell kinds, all read with @ and (for the mutable ones) written
  with reset!/swap! exactly like reagent:

    (atom x)        a mutable reactive cell
    (cursor a path) a lens into a map atom by path — writable
    (reaction f)    a read-only derived cell; f is (re)run when its deps change

  All three implement IReactiveCell, the protocol below. That is the seam that
  lets a different backend stand in for the in-memory atom — e.g. a durable atom
  that persists every mutation to a database — while @, reset!, swap!, cursors
  and reactions keep working unchanged.

  Auto-dependency tracking works exactly like reagent/mr-clean: while a component
  renders, glimmer binds *current-watcher*; any reactive cell dereffed during that
  render registers the component's re-render function as a watcher, so a
  reset!/swap! on that cell later re-runs the component.

  Implementation note: jolt's reader hardcodes @x => (clojure.core/deref x) and
  clojure.core/{deref,reset!,swap!} are host primitives bound to a single fixed
  record type. To make @/reset!/swap! work for reactive cells without per-call
  ceremony, this namespace captures the host implementations and rebinds the
  clojure.core vars to dispatching versions that intercept reactive cells and
  delegate to the host for everything else (plain atoms, futures, delays). This
  mirrors how jolt itself chains futures/promises into deref. The rebind is a
  runtime library operation, not a jolt source change."
  (:refer-clojure :exclude [atom deref reset! swap!]))

;; --- host primitives, captured before any rebind -----------------------------
;; These are the values of the clojure.core vars at this namespace's load time —
;; the fully-built host deref (atoms/futures/promises/delays/reduced) and the host
;; reset!/swap!. All host-atom mutation inside this file goes through them so the
;; rebind below can't recurse into our own dispatchers.
(def ^:private host-deref  clojure.core/deref)
(def ^:private host-reset! clojure.core/reset!)
(def ^:private host-swap!  clojure.core/swap!)

;; --- watcher tracking --------------------------------------------------------
;; Bound to a component's re-render function while that component renders; nil
;; outside a render, so plain derefs (tests, host code) pay only a nil check.
(def ^:dynamic *current-watcher* nil)

;; --- the reactive-cell protocol ---------------------------------------------
(defprotocol IReactiveCell
  "The contract every reactive cell implements so @ / reset! / swap! and
  auto-dependency tracking work on it. Implement this to swap in a different
  atom backend, e.g. a durable atom that persists each mutation to a database:

    (defrecord DurableAtom [id db state watches]
      IReactiveCell
      (-value [_] @state)                    ; current value, no watcher registration
      (-reset! [this v] ...)                 ; persist v, notify watchers on change, return v
      (-add-watch! [_ w] ...)                ; register watcher fn w
      (-remove-watch! [_ w] ...)             ; unregister watcher fn w
      (-notify-watches! [this] ...))         ; call each watcher with this cell

  A watcher is a fn of one argument — the cell that fired. Read-only cells
  (reactions) signal by throwing from -reset!."
  (-value [this])
  (-reset! [this new-value])
  (-add-watch! [this watcher])
  (-remove-watch! [this watcher])
  (-notify-watches! [this]))

;; --- built-in reactive cells -------------------------------------------------
;; Records rather than plain maps: protocol dispatch is per-type, so the three
;; cell kinds each extend IReactiveCell below. Keyword lookup (:state, :watches,
;; :src, :path, :f, :trigger) keeps working because records are maps.

(defrecord RAtom [state watches]
  IReactiveCell
  (-value [_] (host-deref state))
  (-reset! [this v]
    (let [old (host-deref state)]
      (when (not= old v)
        (host-reset! state v)
        (-notify-watches! this))
      v))
  (-add-watch! [_ w] (host-swap! watches clojure.core/conj w))
  (-remove-watch! [_ w] (host-swap! watches clojure.core/disj w))
  (-notify-watches! [this]
    (doseq [w (host-deref watches)]
      (w this))))

(defrecord Cursor [src path watches]
  IReactiveCell
  (-value [_] (get-in (-value src) path))
  ;; src is always a reactive cell (cursor flattens to its root), so write back
  ;; through the protocol rather than the public dispatcher.
  (-reset! [_ v] (-reset! src (assoc-in (-value src) path v)))
  (-add-watch! [_ w] (host-swap! watches clojure.core/conj w))
  (-remove-watch! [_ w] (host-swap! watches clojure.core/disj w))
  (-notify-watches! [this]
    (doseq [w (host-deref watches)]
      (w this))))

(defrecord Reaction [f state watches trigger]
  IReactiveCell
  (-value [_] (host-deref state))
  (-reset! [this _] (throw (ex-info "a reaction is read-only" {:cell this})))
  (-add-watch! [_ w] (host-swap! watches clojure.core/conj w))
  (-remove-watch! [_ w] (host-swap! watches clojure.core/disj w))
  (-notify-watches! [this]
    (doseq [w (host-deref watches)]
      (w this))))

(defn- reactive? [x] (satisfies? IReactiveCell x))
(defn- reaction? [x] (instance? Reaction x))
(defn- cursor? [x] (instance? Cursor x))

(defn- track! [r]
  (when *current-watcher*
    (-add-watch! r *current-watcher*)))

(defn- notify! [r]
  (-notify-watches! r))

(defn unwatch!
  "Remove watcher `w` from reactive cell `r`'s watch set. Used to tear down a
  component's subscriptions when it is unmounted, so a re-mount against a
  long-lived (defonce) cell doesn't leave the old tree's watchers behind. A
  watcher receives the cell it fired on, so it can also unsubscribe itself."
  [r w]
  (when (reactive? r)
    (-remove-watch! r w))
  nil)

;; --- the dispatching deref / reset! / swap! ----------------------------------
(defn deref
  "Read a reactive cell (registering a watcher if *current-watcher* is bound) or,
  for any other reference type, delegate to the host deref."
  [x]
  (if (reactive? x)
    (do (track! x) (-value x))
    (host-deref x)))

(defn reset!
  "Write a value to a reactive cell, notifying watchers when the value actually
  changes. Delegates to the host reset! for non-reactive refs. Read-only cells
  (reactions) throw."
  ([x v]
   (if (reactive? x)
     (-reset! x v)
     (host-reset! x v))))

(defn swap!
  "Apply f (plus args) to the current value of a reactive cell and reset! it.
  Delegates to the host swap! for non-reactive refs."
  ;; Each arity forwards its own args. An earlier version had the 2-arity call
  ;; (swap! x f nil) and the varargs arity strip nils back out, which silently
  ;; dropped LEGITIMATE nil arguments: (swap! a assoc :k nil) became
  ;; (assoc @a :k) — an odd key/val count. Anything storing a nil through swap!
  ;; hit it; the reconciler storing a nil :key for an unkeyed child did.
  ([x f]
   (cond
     (reaction? x) (throw (ex-info "a reaction is read-only" {:cell x}))
     (reactive? x) (reset! x (f (-value x)))
     :else (host-swap! x f)))
  ([x f & args]
   (cond
     (reaction? x) (throw (ex-info "a reaction is read-only" {:cell x}))
     (reactive? x) (reset! x (apply f (-value x) args))
     :else (apply host-swap! x f args))))

;; --- constructors ------------------------------------------------------------
(defn atom
  "A mutable reactive cell. Read with @, write with reset!/swap!."
  [v]
  (->RAtom (clojure.core/atom v) (clojure.core/atom #{})))

(defn- root-ratom [src] (if (cursor? src) (:src src) src))
(defn- root-path [src] (if (cursor? src) (:path src) []))

(defn cursor
  "A lens into the map atom `src` at `path`. Reads return (get-in @src path);
  reset!/swap! write back through (assoc-in). A cursor over another cursor is
  flattened onto the underlying atom with a concatenated path. `src` may be any
  IReactiveCell holding a map."
  [src path]
  (let [root (root-ratom src)
        full-path (vec (concat (root-path src) path))
        c (->Cursor root full-path (clojure.core/atom #{}))]
    ;; Link cursor -> source: when the source changes, fire the cursor's own
    ;; watchers so components that read this cursor re-render.
    (-add-watch! root (fn [_] (notify! c)))
    c))

(defn- recompute! [r]
  ;; Run the reaction body under *current-watcher* so every reactive it derefs
  ;; registers this recompute as a watcher; recompute fires (and re-tracks) when
  ;; any dependency later changes.
  (binding [*current-watcher* (host-deref (:trigger r))]
    (let [old (host-deref (:state r))
          new ((:f r))]
      (host-reset! (:state r) new)
      (when (not= old new)
        (notify! r)))))

(defn- make-reaction
  "A read-only derived cell whose value is (f). f is run immediately and again
  whenever a reactive cell it derefs changes. Other components can subscribe to
  a reaction just like any reactive (read it with @ during render)."
  [f]
  (let [r (->Reaction f (clojure.core/atom nil)
                      (clojure.core/atom #{}) (clojure.core/atom nil))]
    ;; One stable watcher fn per reaction, reused on every recompute so a reaction
    ;; registers exactly one watch per dependency. A fresh closure per recompute
    ;; would grow the dep watch set every change -> runaway render storm.
    (host-reset! (:trigger r) (fn [_] (recompute! r)))
    (recompute! r)
    r))

(defmacro reaction
  "A read-only derived cell whose value is the (re)computed `body`. body is run
  immediately and again whenever a reactive cell it derefs during evaluation
  changes, e.g. (def sq (reaction (* @a @a))). Other components subscribe to a
  reaction by reading it with @ during render, exactly like an atom."
  [& body]
  `(make-reaction (clojure.core/fn [] ~@body)))

;; --- rebind clojure.core so @ / reset! / swap! work everywhere ---------------
;; The reader emits (clojure.core/deref x) for @, so rebinding the var is the
;; only way to make @ track reactive cells. reset!/swap! are rebund for symmetry
;; so component bodies read naturally. Each rebinding delegates to the captured
;; host fn for every non-reactive reference, so plain atoms, futures and delays
;; keep their full host behaviour.
(defonce ^:private _installed
  (do (alter-var-root #'clojure.core/deref  (fn [_] deref))
      (alter-var-root #'clojure.core/reset! (fn [_] reset!))
      (alter-var-root #'clojure.core/swap!  (fn [_] swap!))
      true))
