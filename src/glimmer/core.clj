(ns glimmer.core
  "The component model and reconciler for glimmer.

  Components return hiccup — vectors of [:tag props? children...] where children
  may themselves be native elements ([keyword ...]) or component invocations
  ([fn args...]). Strings/numbers become labels. nil children are skipped.

  Two component shapes, as in reagent:
    Form-1: (defn greeting [name] [:label {:label (str \"hi \" name)}])
            — called fresh on every render.
    Form-2: (defn counter []
              (let [n (atom 0)]            ; local state, created once
                (fn [] [:button {:label (str @n) :on-click #(swap! n inc)}])))
            — the outer fn runs once (on mount); the returned fn renders.

  Reconciling is positional: at each child position the existing widget is reused
  when the tag matches (props re-applied) and replaced when it doesn't; surplus
  children are removed and new ones appended. Components are transparent — they
  produce no widget of their own, just expand to native elements — but each one
  binds glimmer.ratom/*current-watcher* around its render so any reactive cell it
  derefs re-runs that component (and only that component) on change.

  This namespace is toolkit-independent. Everything native — what a widget is,
  how props are applied, how children are attached, what the event loop looks
  like — is reached through glimmer.backend, so the same reconciler drives GTK
  (glimmer-gtk), and any Qt or TUI backend that implements the same map of
  functions."
  (:require [glimmer.backend :as b]
            [glimmer.ratom :as r]))

;; --- reactive re-render marshalling (live REPL development) ------------------
;; While an app runs, the backend's event loop owns the main thread. notify! runs
;; a changed cell's watchers synchronously on the caller's thread, so a ratom
;; mutation made off the main thread (an nREPL eval on its worker thread, or any
;; future) would otherwise reconcile — calling into the toolkit — off the main
;; thread, which most toolkits reject (AppKit does, on macOS). While the loop
;; runs we defer each component's re-render onto it via backend/schedule.
;; Headless, with no loop running, re-renders stay synchronous exactly as before.

;; The running app's mounted root, recorded by run so reload! can re-render it in
;; the same window (REPL hot-reload). Holds the current run's root-state atom,
;; or nil when no app is running. Backend lifecycle callbacks own that atom.
(defonce ^:private live-root (atom nil))

(defn on-gui
  "Run zero-arg `work` on the UI thread — asynchronously, on the next iteration
  of the event loop — while an app is running. Use it from the nREPL REPL (or any
  off-main-thread code) to safely touch widgets directly (e.g. re-mount a root
  after redefining its component), since toolkits reject off-main-thread widget
  mutation. Reactive updates don't need this: a plain swap!/reset! on a ratom the
  UI derefs already re-renders on the UI thread. Runs `work` inline when no loop
  is running (so it is usable headless and in tests)."
  [work]
  (if (not @b/loop-running?) (work) (b/schedule work)))

(defn make-rerender-watcher
  "Return the per-component reactive watcher that re-runs `render` when a cell it
  derefs changes. When `running?` (an atom) is truthy the change is posted onto
  the UI thread via `schedule` (work->any) instead of rendered inline, and a
  pending flag coalesces a burst of changes into a single deferred render.
  `running?`/`schedule` default to the live backend so a running app marshals
  while headless tests render inline; they are exposed as args so the decision is
  unit-testable without a toolkit.

  `disposed` (an atom) is set true when the component is unmounted. Once disposed
  the watcher never renders again (its widgets are gone) and, on the next change,
  removes itself from the notifying cell — so re-mounting against a long-lived
  (defonce) cell doesn't leave the old tree rendering into destroyed widgets or
  pile up dead watchers. Component subscription scopes also detach every tracked
  cell immediately at disposal, so that pruning is only a backstop."
  ([render] (make-rerender-watcher render b/loop-running? b/schedule (atom false)))
  ([render running? schedule] (make-rerender-watcher render running? schedule (atom false)))
  ([render running? schedule disposed]
   (let [pending (atom false)
         self (atom nil)]
     (reset! self
             (fn [cell]
               (if @disposed
                 (r/unwatch! cell @self)
                 (if (not @running?)
                   (render)
                   (when (compare-and-set! pending false true)
                     (schedule (fn [] (reset! pending false) (when-not @disposed (render)))))))))
     @self)))

;; --- hiccup parsing ----------------------------------------------------------
(defn- parse-native [v]
  (let [tag (first v) body (next v)
        props (when (and (seq body) (map? (first body))) (first body))]
    (if props
      {:tag tag :props (dissoc props :key) :children (vec (rest body))}
      {:tag tag :props {} :children (vec body)})))

;; A parent's child forms can mix elements, nil holes (`(when cond ...)`) and
;; spliced seqs (`(for ...)`, list). Normalize to a flat vector of elements
;; before positional diffing: splice (possibly nested) seqs, drop nils. A bare
;; vector is NOT spliced — it is itself one child element (standard hiccup).
(defn- walk-child
  [acc x]
  (cond
    (nil? x)  acc
    (seq? x)  (reduce walk-child acc x)
    :else     (conj acc x)))

(defn flatten-children
  "Normalize a parent's child forms into a flat vector of hiccup elements.
  Splices (possibly nested) seqs and drops nils, so `(for [t tasks] [row t])`
  renders one widget per task and `(when cond [...])` leaves no hole. Bare
  vectors are kept as single children."
  [xs]
  (reduce walk-child [] xs))

;; --- keyed children (pure planning) ------------------------------------------
;; Keyed reconciliation matches children by a stable :key instead of by position,
;; so list items can be added, removed or reordered without a handler capturing a
;; stale index. The planning below is pure (operates on keys, not widgets) so it
;; is unit-testable headlessly; the widget wiring lives in reconcile-children!.

(defn child-key
  "The stable identity of a child element used by keyed reconciliation, or nil
  when the child is unkeyed. Taken from :key in the element's props map (a native
  [:tag {:key k}] or a component invocation [f {:key k} ...]) or, failing that,
  from ^{:key k} metadata on the vector — mirroring reagent. Non-vector children
  (strings, numbers, nil) are never keyed."
  [el]
  (when (vector? el)
    (or (-> el meta :key)
        (let [body (next el)]
          (when (and (seq body) (map? (first body)))
            (:key (first body)))))))

(defn keyed?
  "True when every child in `children` (a flat list of hiccup elements) carries a
  non-nil key. An empty list is not keyed — the positional path handles the
  no-children case. Mixed keyed/unkeyed lists fall back to positional so an
  accidental missing key never silently mis-reconciles."
  [children]
  (boolean (and (seq children)
                (every? #(some? (child-key %)) children))))

(defn- strictly-increasing? [xs]
  ;; `(apply < [])` throws in jolt (< needs >=1 arg), so treat empty as vacuously
  ;; increasing — a list with no reused widgets needs no reorder.
  (or (empty? xs) (apply < xs)))

(defn keyed-plan
  "Pure diff of old vs new child keys -> a reconcile plan the keyed path applies
  against real widgets. `old-keys`/`new-keys` are the keys of the previously-
  mounted and newly-requested children, in order. Returns a map:
    :destroy  [old-index ...]                                keys gone from new
    :slots    [{:key, :kind :reuse|:create, :old-index?}]    in target order
    :reorder? boolean                                         widget reorder needed
  :reorder? is false when surviving widgets already sit in the right relative
  order with new ones appended after them (containers append to the tail); true
  otherwise — e.g. survivors were reordered, or a new item must appear before an
  existing one."
  [old-keys new-keys]
  (let [old-idx  (into {} (map-indexed (fn [i k] [k i]) old-keys))
        new-set  (set new-keys)
        destroy  (vec (for [[i k] (map-indexed vector old-keys)
                            :when (not (new-set k))] i))
        slots    (vec (for [k new-keys]
                        (if-let [oi (old-idx k)]
                          {:key k :kind :reuse :old-index oi}
                          {:key k :kind :create})))
        reuse-old (vec (keep :old-index (filter #(= :reuse (:kind %)) slots)))
        create-pos (keep-indexed (fn [i s] (when (= :create (:kind s)) i)) slots)
        reuse-pos  (keep-indexed (fn [i s] (when (= :reuse (:kind s)) i)) slots)
        create-before-reuse (and (seq create-pos) (seq reuse-pos)
                                 (< (apply min create-pos) (apply max reuse-pos)))
        reorder? (boolean (or (not (strictly-increasing? reuse-old)) create-before-reuse))]
    {:destroy destroy :slots slots :reorder? reorder?}))

;; --- instance tree -----------------------------------------------------------
;; Each rendered element has an atom holding its instance state:
;;   native: {:type :native :tag :button :props {} :widget <w> :children [atom...]}
;;   comp:   {:type :comp :render-fn <fn> :parent <atom> :children [atom]}  ; one child = expanded root
;; A nil instance means nothing has been mounted at that position yet. A
;; component's :parent is the instance whose child slot it fills, so a re-render
;; that contributes a widget where there was none can find the slot order of the
;; container it appends to and place the widget among its siblings. While a
;; native instance's children pass runs it also carries :fresh-slots, the slot
;; atoms that pass created.

(declare reconcile-el!)

(defn- inst-widget
  "The widget an instance contributes to its parent container: a native
  instance's own :widget, or — for a component, which owns no widget — the widget
  of its single expanded child (recursing, since a component can expand to another
  component). nil if nothing is mounted yet."
  [inst-atom]
  (when inst-atom
    (let [inst @inst-atom]
      (or (:widget inst)
          (some-> (first (:children inst)) inst-widget)))))

(defn- destroy-inst!
  "Remove the widget an instance contributes from `parent-widget`. A native
  instance owns its widget: removing it unparents the whole subtree, so we stop —
  recursing into its children would ask an already-finalized container to remove
  children it no longer has (the source of GTK_IS_BOX criticals under GTK). A
  component owns no widget, so its expanded children are parented in the
  component's own container; recurse with the same parent to remove them from
  there."
  [inst-atom parent-widget parent-tag]
  (when inst-atom
    (let [inst @inst-atom
          widget (:widget inst)]
      (if widget
        (b/remove-child! parent-tag parent-widget widget)
        (doseq [child (:children inst)]
          (destroy-inst! child parent-widget parent-tag))))))

(defn- dispose-tree!
  "Mark every component watcher in the instance subtree disposed, so it stops
  rendering and immediately detaches its reactive subscriptions. Pure
  bookkeeping (no widget calls), so unlike destroy-inst! it recurses the whole
  tree regardless of which instances own widgets. Called wherever a subtree is
  removed (unmount, surplus/removed children) so subscriptions don't outlive the
  widgets — which matters most when re-mounting against a long-lived (defonce)
  cell."
  [inst-atom]
  (when inst-atom
    (let [inst @inst-atom]
      (when-let [d (:disposed inst)] (reset! d true))
      (r/clear-subscriptions! (:subscription-scope inst))
      (doseq [child (:children inst)]
        (dispose-tree! child)))))

(defn- with-fresh-slots
  "Run `render!`, a parent's children pass, with `fresh` — the slot atoms the
  pass created — recorded on the parent instance for its duration. A widget
  appended for a fresh slot needs no placement: in a positional pass the slots
  after it are fresh too and hold no widgets yet, and a keyed pass that creates
  a slot ahead of reused ones reorders afterwards. Skipping them keeps a mount
  linear in the number of children instead of scanning the slots after each."
  [inst-atom fresh render!]
  (swap! inst-atom assoc :fresh-slots fresh)
  (try (render!)
       (finally (swap! inst-atom dissoc :fresh-slots))))

(defn- reconcile-positional-children!
  "Positionally reconcile `new-children` (a flattened vector of hiccup) against the
  instance's existing children, reusing widgets where the element shape matches at
  a position. The default path when children are not uniformly keyed."
  [parent-widget parent-tag new-children inst-atom]
  (let [old (:children @inst-atom)
        n (count new-children)
        ;; reuse the child atom at each position when it exists; else a fresh one
        reused (vec (for [i (range n)]
                      (if (< i (count old)) (nth old i) (atom nil))))]
    ;; remove widgets for children that no longer exist
    (doseq [i (range n (count old))]
      (dispose-tree! (nth old i))
      (destroy-inst! (nth old i) parent-widget parent-tag))
    ;; Publish slots before rendering: a failed sibling must still be disposable.
    (swap! inst-atom assoc :children reused)
    ;; reconcile each child into its (possibly new) atom
    (with-fresh-slots inst-atom (set (subvec reused (min n (count old))))
      (fn []
        (doseq [i (range n)]
          (reconcile-el! parent-widget parent-tag (nth new-children i) (nth reused i) inst-atom))))))

(defn- reorder-keyed-widgets!
  "Force `child-atoms`' widgets into the given order within the parent, moving
  each widget to sit after the previous one (the first goes to position 0).
  Called only when keyed-plan says a reorder is needed. Repositioning does not
  recreate widgets, so signal handlers and the instance atoms stay intact. A
  keyed component that rendered nil contributes no widget, so its slot is
  skipped and the next present widget goes after the nearest present one
  before it, never after a nil handle. A no-op for backends without ordered
  containers."
  [parent-widget parent-tag child-atoms]
  ;; resolve each child to its contributed widget — a keyed child may be a
  ;; component, whose widget lives one level down on its expanded child, or
  ;; nothing at all when the component rendered nil.
  (let [widgets (vec (keep inst-widget child-atoms))
        n (count widgets)]
    (when (pos? n)
      (doseq [i (range n)]
        (let [w (nth widgets i)
              prev (when (pos? i) (nth widgets (dec i)))]
          (b/reorder-child! parent-tag parent-widget w prev))))))

(defn- reconcile-keyed-children!
  "Reconcile uniformly-keyed `new-children` (a flattened vector of hiccup, each
  carrying a non-nil :key) by stable identity rather than position. Derives a
  reuse/create/reorder plan from keyed-plan, destroys removed children, reuses
  surviving atoms by key, creates fresh atoms for new keys, re-applies each
  child's hiccup into its atom, and fixes widget order when the plan requires it.
  Stores the child's :key on its instance so the next render can read old keys
  back — that is how a row's widget and signal handlers follow the item across
  add/remove/reorder instead of a stale position."
  [parent-widget parent-tag new-children inst-atom]
  (let [old (:children @inst-atom)
        ;; raw old keys keep nils on purpose: a nil-keyed legacy child (mounted
        ;; before this parent became keyed) is absent from new-set, so keyed-plan
        ;; destroys it rather than orphaning its widget in the container.
        old-keys (vec (map (fn [a] (:key @a)) old))
        new-keys (vec (map child-key new-children))
        plan (keyed-plan old-keys new-keys)
        destroy (:destroy plan)
        slots (:slots plan)
        reorder? (:reorder? plan)
        ;; reuse lookups are by key; nil-keyed old children are never reused
        old-by-key (into {} (keep (fn [a] (when-let [k (:key @a)] [k a])) old))]
    (doseq [i destroy]
      (dispose-tree! (nth old i))
      (destroy-inst! (nth old i) parent-widget parent-tag))
    (let [child-atoms (vec (for [slot slots]
                             (if (= :reuse (:kind slot))
                               (get old-by-key (:key slot))
                               (atom nil))))]
      ;; Publish slots before rendering: a failed sibling must still be disposable.
      (swap! inst-atom assoc :children child-atoms)
      (with-fresh-slots inst-atom (set (remove (set old) child-atoms))
        (fn []
          (doseq [i (range (count child-atoms))]
            (let [a (nth child-atoms i)]
              (reconcile-el! parent-widget parent-tag (nth new-children i) a inst-atom)
              (swap! a assoc :key (nth new-keys i))))))
      (when reorder?
        (reorder-keyed-widgets! parent-widget parent-tag child-atoms)))))

(defn- reconcile-children!
  "Reconcile `new-children` (hiccup) against the instance's existing children.
  When every child carries a stable :key, reconcile by identity — a row's widget
  and signal handlers follow its key across add/remove/reorder, so a handler may
  safely close over per-item state. Otherwise fall back to positional matching.
  `new-children` is flattened first."
  [parent-widget parent-tag new-children inst-atom]
  (let [flat (flatten-children new-children)]
    (if (keyed? flat)
      (reconcile-keyed-children! parent-widget parent-tag flat inst-atom)
      (reconcile-positional-children! parent-widget parent-tag flat inst-atom))))

(defn- container-slot
  "The slot `inst-atom` fills in its nearest native ancestor, as [native-inst
  slot]: `parent-inst` is the instance `inst-atom` is a child of, and the walk
  follows :parent links up through the components between them, since a
  component fills its own slot with whatever its expanded child contributes.
  nil for the root, whose container is not an instance."
  [inst-atom parent-inst]
  (loop [slot inst-atom parent parent-inst]
    (when parent
      (if (= :native (:type @parent))
        [parent slot]
        (recur parent (:parent @parent))))))

(defn- place-appended-widget!
  "Move `widget`, just appended to `parent-widget` on behalf of `inst-atom`, to
  where its slot order puts it: after the widget of the nearest preceding slot
  that contributes one, or first when none does. A slot the running children
  pass created is skipped without a look at its siblings (see with-fresh-slots),
  so a mount appends in order and moves nothing; otherwise nothing moves unless
  a later slot contributes a widget — the case when a component that rendered
  nil re-renders to an element, or a slot turns from a component into a native
  element. Backends without :reorder-child! keep the appended order."
  [parent-widget parent-tag widget inst-atom parent-inst]
  (when-let [[native slot] (container-slot inst-atom parent-inst)]
    (when-not (contains? (:fresh-slots @native) slot)
      (let [slots (:children @native)
            idx (first (keep-indexed (fn [i a] (when (identical? a slot) i)) slots))]
        (when (and idx (some inst-widget (subvec slots (inc idx))))
          (b/reorder-child! parent-tag parent-widget widget
                            (some inst-widget (reverse (subvec slots 0 idx)))))))))

(defn- reconcile-native!
  [parent-widget parent-tag v inst-atom parent-inst]
  (let [{:keys [tag props children]} (parse-native v)
        inst @inst-atom]
    (if (and inst (= (:type inst) :native) (= (:tag inst) tag))
      (do (b/apply-props! tag (:widget inst) props)
          (swap! inst-atom assoc :props props))
      (let [widget (b/create! tag props)
            new {:type :native :tag tag :props props :widget widget :children []}
            replacing? (and inst (= (:type inst) :native))]
        (try
          (if replacing?
            (do
              ;; native -> native of another tag: the old widget goes, and the
              ;; component watchers in its subtree must go with it, or they keep
              ;; rendering into a widget that is no longer in the tree.
              (dispose-tree! inst-atom)
              (b/replace-child! parent-tag parent-widget (:widget inst) widget))
            (do
              ;; comp -> native: the component owned no widget, but its expanded
              ;; children are parented here and its watchers are live. Tear both
              ;; down or the old subtree keeps rendering beside the new widget.
              (when inst
                (dispose-tree! inst-atom)
                (destroy-inst! inst-atom parent-widget parent-tag))
              (b/append-child! parent-tag parent-widget widget)))
          (catch Throwable error
            (b/dispose-widget! widget)
            (throw error)))
        (reset! inst-atom new)
        ;; A replacement keeps its position; an appended widget sits behind the
        ;; siblings that already had theirs when this slot gained one later.
        (when-not replacing?
          (place-appended-widget! parent-widget parent-tag widget inst-atom parent-inst))))
    (reconcile-children! (:widget @inst-atom) tag children inst-atom)))

(defn rerender-thunk
  "The zero-arg closure a component's watcher runs to re-render it: re-reconciles
  the instance's last stored invocation (:v). No-ops when the instance no longer
  holds one — a fresh slot, a reset skeleton, or an unmounted tree. A stale
  watcher firing after its instance was reset used to re-render (:v nil) = nil,
  which reconcile-comp! read as a component invocation [nil] and crashed invoking
  nil. `reconcile` is passed in so the guard is testable without a backend."
  [reconcile parent-widget parent-tag inst-atom]
  (fn [] (when-let [v (:v @inst-atom)]
           (reconcile parent-widget parent-tag v inst-atom))))

(defn- reconcile-comp!
  "Expand a component invocation into native hiccup and reconcile its single child.
  Each component renders exactly once per render: a Form-2 component's outer fn
  runs only on mount (its returned render fn is cached); a Form-1 component's fn
  is re-applied each render. We classify with fn?, not ifn? — a hiccup vector is
  callable, so ifn? would treat every Form-1 result as a Form-2 render fn.
  `parent-inst` is the instance whose child slot this component fills."
  [parent-widget parent-tag v inst-atom parent-inst]
  (let [f (first v)
        ;; strip a leading {:key k} so it never reaches the component fn — mirrors
        ;; parse-native. A key-only map drops entirely; a map mixing :key with real
        ;; props drops just :key. Non-map / key-less leading args are untouched.
        args (let [raw (rest v)]
               (if (and (seq raw) (map? (first raw)) (contains? (first raw) :key))
                 (let [m (dissoc (first raw) :key)]
                   (if (seq m) (cons m (rest raw)) (rest raw)))
                 raw))
        cur @inst-atom
        cached-render-fn (when (= f (first (:v cur))) (:render-fn cur))]
    ;; native -> comp: a component has no widget of its own, so a native element
    ;; previously mounted here would be left parented behind the expanded child.
    ;; Reset to a fresh comp skeleton — its native :type/:tag/:widget must NOT
    ;; survive, or a later comp->native transition would mistake the stale :type
    ;; and deref a nil :widget.
    (when (and (:widget cur) (not= :comp (:type cur)))
      ;; dispose before removing and resetting: the reset replaces the map that
      ;; holds the subtree's :disposed atoms, so a dispose after it can no longer
      ;; reach them and their watchers would keep firing against the reset instance.
      (dispose-tree! inst-atom)
      (b/remove-child! parent-tag parent-widget (:widget cur))
      (reset! inst-atom {:children [(atom nil)]}))
    ;; ensure exactly one child slot for the expanded root. Use `seq`, not
    ;; truthiness: a native leaf carries :children [], which is truthy but empty —
    ;; (first []) is nil, and reconcile-el! would deref it (the clear-all crash).
    (swap! inst-atom (fn [c] (if (seq (:children c)) c (assoc c :children [(atom nil)]))))
    ;; stash the current invocation so the watcher always uses fresh args.
    ;; Without this a Form-1 child that subscribes to its own reactives
    ;; re-renders with the initial v from mount, overwriting parent updates.
    ;; A different component fn at this slot must not keep the previous one's
    ;; Form-2 render fn, or the next re-render would draw the old component.
    ;; The parent link lets the expanded child find its slot when it appears.
    (swap! inst-atom (fn [c] (cond-> (assoc c :v v :type :comp :parent parent-inst)
                               (nil? cached-render-fn) (dissoc :render-fn))))
    (let [child-atom (first (:children @inst-atom))
          ;; subscribe: any reactive read during render re-runs just this component.
          ;; The watcher is cached on the instance so it's the SAME fn every render;
          ;; a fresh closure would grow each cell's watch set on every re-render.
          watcher (or (:watcher @inst-atom)
                      ;; Disposal detaches the scope and fences already-queued renders.
                      (let [disposed (atom false)
                            ;; A re-render fills the slot the component was mounted in.
                            rerender (fn [pw pt v ia] (reconcile-comp! pw pt v ia (:parent @ia)))
                            w (make-rerender-watcher
                                (rerender-thunk rerender parent-widget parent-tag inst-atom)
                                b/loop-running? b/schedule disposed)]
                        (swap! inst-atom assoc :watcher w :disposed disposed
                               :subscription-scope (r/watch-scope w)) w))
          scope (:subscription-scope @inst-atom)
          _ (r/clear-subscriptions! scope)
          hiccup (binding [r/*current-watcher* watcher r/*watch-scope* scope]
                   (if cached-render-fn
                     (cached-render-fn)                       ; Form-2: cached render fn
                     (let [result (apply f args)]             ; first invocation
                       (if (fn? result)
                         (do (swap! inst-atom assoc :type :comp :render-fn result) (result))
                         result))))]                          ; Form-1: hiccup, used directly
      (reconcile-el! parent-widget parent-tag hiccup child-atom inst-atom))))

(defn- reconcile-el!
  "Reconcile one hiccup element (native, component, or primitive) into inst-atom,
  a child slot of `parent-inst` (nil for the root, whose container is not an
  instance)."
  [parent-widget parent-tag el inst-atom parent-inst]
  (cond
    (nil? el) (do (dispose-tree! inst-atom)
                  (destroy-inst! inst-atom parent-widget parent-tag)
                  (reset! inst-atom nil))
    (string? el) (reconcile-native! parent-widget parent-tag (b/text->element el) inst-atom parent-inst)
    (number? el) (reconcile-native! parent-widget parent-tag (b/text->element (str el)) inst-atom parent-inst)
    (vector? el)
    (let [head (first el)]
      (cond
        (keyword? head) (reconcile-native! parent-widget parent-tag el inst-atom parent-inst)
        (fn? head)      (reconcile-comp! parent-widget parent-tag el inst-atom parent-inst)
        :else (throw (ex-info (str "unsupported hiccup element: " (pr-str el)) {:el el}))))
    :else (reconcile-native! parent-widget parent-tag (b/text->element (pr-str el)) inst-atom parent-inst)))

;; --- public API --------------------------------------------------------------
(defn mount
  "Mount `hiccup` (a native element, a component invocation [fn args...], or a
  primitive) into a container widget. Returns the root instance atom. Wrap a
  plain function in a vector ([my-app]) so it reconciles as a reactive component.
  If rendering fails part-way, the subtree created so far is disposed and its
  subscriptions detached before the error propagates."
  [container-widget container-tag hiccup]
  (let [root (atom nil)]
    (try
      (reconcile-el! container-widget container-tag hiccup root nil)
      root
      (catch Throwable error
        (dispose-tree! root)
        (try (destroy-inst! root container-widget container-tag)
             (catch Throwable _ nil))
        (throw error)))))

(defn unmount!
  "Remove a mounted root (the atom returned by mount) from its container, tearing
  down its reactive subscriptions first so nothing renders into the removed tree.
  The root atom is cleared even when the backend fails to remove the widget, so
  a retry or abandon! afterwards is a no-op."
  [root-inst-atom container-widget container-tag]
  (dispose-tree! root-inst-atom)
  (try (destroy-inst! root-inst-atom container-widget container-tag)
       (finally (reset! root-inst-atom nil))))

(defn abandon!
  "Detach a mounted root's subscriptions without touching native widgets.
  The owner must release its native arena afterward. Idempotent; for backends
  managing independent roots when native destruction or a callback fails."
  [root-inst-atom]
  (dispose-tree! root-inst-atom)
  (reset! root-inst-atom nil)
  nil)

(defn run
  "Run an application with the installed backend. The backend creates a top-level
  window, `root-fn` (a thunk returning hiccup) is mounted into it as a reactive
  component, and the event loop runs until the app quits.

  Options are passed through to the backend; the ones every backend understands:
    :app-id        application id       (default \"glimmer.app\")
    :title         window title         (default \"glimmer\")
    :width         window width in px   (default 400)
    :height        window height in px  (default 300)
    :auto-quit-ms  if set, quit the loop after this many milliseconds
                   (smoke/automated tests).

  A backend that implements :run-lifecycle receives explicit :mount!, :unmount!
  and :abandon! callbacks and owns the root's lifetime (see
  glimmer.backend/run-lifecycle); a legacy :run backend keeps its mount-root!
  contract. Either way the root's subscriptions are detached when run returns.
  A later run replaces the root that reload! targets once its own root has
  mounted; one the backend rejects before that leaves the live run in place.

  Requiring a backend namespace installs it — see glimmer-gtk.core for GTK4."
  [root-fn & {:as opts}]
  (let [state (atom nil)
        dispose! (fn [native?]
                   ;; :inst is nil while reload! replaces the root and after a
                   ;; replacement root threw: the run still owns its window then.
                   (when-let [{:keys [window tag inst]} @state]
                     (reset! state nil)
                     (when inst
                       (if native?
                         (unmount! inst window tag)
                         (do (dispose-tree! inst) (reset! inst nil))))))
        lifecycle {:mount! (fn [window tag]
                             (when @state (dispose! true))
                             (reset! state {:window window :tag tag
                                            :inst (mount window tag [root-fn])
                                            :root-fn root-fn})
                             ;; A later run takes over live-root once its root has
                             ;; mounted, so a run the backend rejects before that
                             ;; (Qt: a session is already active) leaves the live
                             ;; run's reload! target in place. The finalizer below
                             ;; releases only its own.
                             (reset! live-root state)
                             nil)
                   :unmount! #(dispose! true)
                   :abandon! #(dispose! false)}]
    (try
      (b/run-lifecycle (or opts {}) lifecycle)
      (finally
        ;; Legacy backends may already have destroyed their native container.
        ;; Qt unmounts explicitly, on its GUI thread, before closing the engine.
        (dispose! false)
        (compare-and-set! live-root state nil)))))

(defn reload!
  "Re-mount the running app's root into its existing window, on the UI thread.
  Call this from the nREPL REPL after redefining components to see the changes in
  the SAME window, instead of opening a new one with run.

  With no argument it re-mounts the current root component. That re-runs the root
  and re-resolves the child components it references, so redefinitions of those
  children take effect. To swap the root component itself (e.g. after redefining
  it), pass the new fn: (glimmer.core/reload! my-app).

  Re-mounting rebuilds the component tree, so state held in a component's own let
  is reset. Keep state that should survive a reload in a top-level defonce reactive
  cell (atom/cursor/reaction) that the components read — reagent-style. The old
  tree's subscriptions are detached before the new tree mounts, so those defonce
  cells carry exactly the live tree's subscriptions across reloads. A reload
  belongs to the run that was live when it was requested: one queued before that
  run exits does not touch a later run. A new root that throws while mounting
  leaves the window empty but still the run's, with the previous root fn in
  place: reload! again, with a corrected fn or none, to fill it. No-op when no
  app is running."
  ([] (reload! nil))
  ([new-root-fn]
   (if-let [state @live-root]
     (on-gui
       (fn []
         (when-let [{:keys [window tag inst] :as st} @state]
           (let [f (or new-root-fn (:root-fn st))]
             ;; Only the instance leaves the state while the root is replaced:
             ;; the run keeps its window, tag and previous root fn, so a new
             ;; root that throws while mounting leaves an empty window a later
             ;; reload! fills. The new instance and fn are published together
             ;; once the mount has succeeded.
             (swap! state assoc :inst nil)
             (when inst (unmount! inst window tag))
             (let [new-inst (mount window tag [f])]
               (swap! state assoc :inst new-inst :root-fn f))))))
     (println "glimmer.core/reload!: no running app to reload"))
   nil))
