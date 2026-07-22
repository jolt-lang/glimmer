(ns glimmer.ratom
  "Reactive atoms for glimmer — a reagent-style reactive model over GTK4.

  Three reactive cell kinds, all read with @ and (for the mutable ones) written
  with reset!/swap! exactly like reagent:

    (atom x)        a mutable reactive cell
    (cursor a path) a lens into a map atom by path — writable
    (reaction f)    a read-only derived cell; f is (re)run when its deps change

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

;; --- reactive cells ----------------------------------------------------------
;; Tagged maps rather than records: reactive? dispatches on :glimmer/kind, which
;; is bulletproof and avoids deftype/instance? host edge cases. Mutable state is
;; held in plain host atoms inside the map.
(defn- ratom? [x] (and (map? x) (= :ratom (:glimmer/kind x))))
(defn- cursor? [x] (and (map? x) (= :cursor (:glimmer/kind x))))
(defn- reaction? [x] (and (map? x) (= :reaction (:glimmer/kind x))))
(defn- reactive? [x] (and (map? x) (#{:ratom :cursor :reaction} (:glimmer/kind x))))

(defn- track! [r]
  (when *current-watcher*
    (host-swap! (:watches r) clojure.core/conj *current-watcher*)))

(defn- notify! [r]
  (doseq [w (host-deref (:watches r))]
    (w r)))

(defn unwatch!
  "Remove watcher `w` from reactive cell `r`'s watch set. Used to tear down a
  component's subscriptions when it is unmounted, so a re-mount against a
  long-lived (defonce) cell doesn't leave the old tree's watchers behind. A
  watcher receives the cell it fired on, so it can also unsubscribe itself."
  [r w]
  (when (and (map? r) (:watches r))
    (host-swap! (:watches r) clojure.core/disj w))
  nil)

(defn- cell [state] {:glimmer/kind :ratom :state state :watches (clojure.core/atom #{})})

(defn atom
  "A mutable reactive cell. Read with @, write with reset!/swap!."
  [v]
  (cell (clojure.core/atom v)))

(defn- root-ratom [src] (if (cursor? src) (:src src) src))
(defn- root-path [src] (if (cursor? src) (:path src) []))

(defn cursor
  "A lens into the map atom `src` at `path`. Reads return (get-in @src path);
  reset!/swap! write back through (assoc-in). A cursor over another cursor is
  flattened onto the underlying atom with a concatenated path."
  [src path]
  (let [root (root-ratom src)
        full-path (vec (concat (root-path src) path))
        c {:glimmer/kind :cursor :src root :path full-path :watches (clojure.core/atom #{})}]
    ;; Link cursor -> source: when the source changes, fire the cursor's own
    ;; watchers so components that read this cursor re-render.
    (host-swap! (:watches root) clojure.core/conj (fn [_] (notify! c)))
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
  (let [r {:glimmer/kind :reaction :f f :state (clojure.core/atom nil)
           :watches (clojure.core/atom #{}) :trigger (clojure.core/atom nil)}]
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

;; --- the dispatching deref / reset! / swap! ----------------------------------
(defn- raw-value [r]
  (case (:glimmer/kind r)
    :ratom    (host-deref (:state r))
    :cursor   (get-in (host-deref (:state (:src r))) (:path r))
    :reaction (host-deref (:state r))))

(defn deref
  "Read a reactive cell (registering a watcher if *current-watcher* is bound) or,
  for any other reference type, delegate to the host deref."
  [x]
  (if (reactive? x)
    (do (track! x) (raw-value x))
    (host-deref x)))

(defn reset!
  "Write a value to a reactive cell (RAtom or Cursor), notifying watchers when the
  value actually changes. Delegates to the host reset! for non-reactive refs."
  ([x v]
   (cond
     (ratom? x)    (let [old (raw-value x)]
                     (when (not= old v)
                       (host-reset! (:state x) v)
                       (notify! x))
                     v)
     (cursor? x)   (reset! (:src x) (assoc-in (raw-value (:src x)) (:path x) v))
     (reaction? x) (throw (ex-info "a reaction is read-only" {:cell x}))
     :else         (host-reset! x v))))

(defn swap!
  "Apply f (plus args) to the current value of a reactive cell and reset! it.
  Delegates to the host swap! for non-reactive refs."
  ([x f] (swap! x f nil))
  ([x f & args]
   (cond
     (or (ratom? x) (cursor? x)) (reset! x (apply f (raw-value x) (remove nil? args)))
     (reaction? x) (throw (ex-info "a reaction is read-only" {:cell x}))
     :else (apply host-swap! x f (remove nil? args)))))

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
