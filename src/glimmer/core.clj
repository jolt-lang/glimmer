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
  derefs re-runs that component (and only that component) on change. A widget a
  slot gains after its siblings have theirs is moved to where the slot sits.

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
;;   comp:   {:type :comp :render-fn <fn> :children [atom]}  ; one child = expanded root
;; A nil instance means nothing has been mounted at that position yet. A native
;; instance also keeps its slots' order state (see widget placement below), and
;; a keyed slot its :key and the :index the last keyed pass put it at.

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

;; --- widget placement --------------------------------------------------------
;; A slot can gain a widget after its siblings have theirs: a component that
;; rendered nil renders an element, a slot turns from a component into an
;; element, or a keyed row is created ahead of existing rows. The backend appends
;; it last, so it is moved after the widget of the nearest preceding slot that
;; has one, or first.
;;
;; Every slot has a sparse integer rank, increasing along the container's
;; :children. The order state lives on the container's instance, which outlives
;; its children's renders:
;;   :ranks  {slot rank}, absent while ranks are implicit: (i+1)*stride for the
;;           slot at index i, until a keyed pass first moves a reused slot or
;;           creates one ahead of one. Mounts and tail appends write no ranks.
;;   :tail   an upper bound on the highest rank holding a widget, -1 for none. A
;;           gain at or above it is in order, since ranks are unique; losses may
;;           leave it high, which costs a lookup.
;;   :shown  (sorted-map rank slot) of the slots holding a widget, built on the
;;           first late placement. Hidden slots' entries go stale; lookups skip
;;           and drop them.
;;   :unordered  set while the widgets may not follow the ranks: during a pass
;;           with a move step, and after a failed render or move, until a pass
;;           moves every widget.
;;
;; A pass renders each child with a context {:container :slot :index}, which a
;; component's watcher keeps, so a nested component places into its outermost
;; slot; the index is a hint. A slot the container no longer lists, dropped by a
;; nested pass while it rendered, has no rank and gains no widget.

(def ^:private stride
  "The gap between spread-out ranks: about ten midpoint inserts use one up, and
  the next keyed pass renumbers the container."
  1024)

(defn- slot-position
  "The index of `slot` in the :children of the container map `cm`, or nil when it
  is no longer listed. Tries the hint `i`, then the :index a keyed pass stamped on
  the slot (a watcher's hint goes stale once its keyed row moves), then scans."
  [cm slot i]
  (let [cs (:children cm)]
    (if (and i (identical? slot (get cs i)))
      i
      (let [j (:index @slot)]
        (if (and j (identical? slot (get cs j)))
          j
          (let [k (.indexOf cs slot)] (when-not (neg? k) k)))))))

(defn- slot-rank
  "The rank of `slot` in the container map `cm`, or nil when it is no longer
  listed. Explicit :ranks hold exactly the listed slots."
  [cm slot i]
  (if-let [ranks (:ranks cm)]
    (get ranks slot)
    (when-let [p (slot-position cm slot i)]
      (* stride (inc p)))))

(defn- tail-rank
  "The rank of the last slot of `cs`, up to index `from`, that holds a widget, by
  `ranks` or the implicit ones when nil; -1 when none does."
  [cs ranks from]
  (loop [i (or from -1)]
    (cond (neg? i) -1
          (inst-widget (nth cs i)) (if ranks (get ranks (nth cs i)) (* stride (inc i)))
          :else (recur (dec i)))))

(defn- entry-valid?
  "Whether the index entry `r` -> `slot` still holds in the container map `cm`:
  the slot has that rank and a widget. A slot is cleared as soon as its widget is
  destroyed, so a removed widget never counts."
  [cm r slot]
  (and (if-let [ranks (:ranks cm)]
         (= r (get ranks slot))
         (identical? slot (get (:children cm) (dec (quot r stride)))))
       (inst-widget slot)))

(defn- shown-index
  "The :shown index of the container map `cm`, built from its :children."
  [cm]
  (let [cs (:children cm) ranks (:ranks cm)]
    (reduce (fn [shown i]
              (let [s (nth cs i)]
                (if (inst-widget s) (assoc shown (if ranks (get ranks s) (* stride (inc i))) s) shown)))
            (sorted-map) (range (count cs)))))

(defn- drop-entry
  "`shown` without its entry at rank `r`, when that entry is `slot`'s."
  [shown r slot]
  (if (identical? slot (get shown r)) (dissoc shown r) shown))

(defn- first-valid
  "The first valid index entry of `entries`, and the stale ones before it, which
  the caller drops: revealing every hidden row would otherwise rescan them all."
  [cm entries]
  (loop [es entries stale []]
    (if-let [e (first es)]
      (if (entry-valid? cm (key e) (val e))
        [e stale]
        (recur (next es) (conj stale e)))
      [nil stale])))

(defn- record-gain
  "The container map `m` once `slot` gains a widget at rank `r` in order, or for
  the pass's move step to place (`defer?`), which leaves it :unordered."
  [m r slot defer?]
  (let [m (assoc m :tail (max r (:tail m -1)))
        m (if-let [shown (:shown m)] (assoc m :shown (assoc shown r slot)) m)]
    (if defer? (assoc m :unordered true) m)))

(defn- place-gained!
  "Place `widget`, just appended for `slot` of the container `c` at rank `r`. A
  gain in order, or deferred to the pass's move step, is only recorded. Otherwise
  the widget moves after the nearest preceding slot's widget, or first, when a
  later slot has one. Before the index is first built, the slots from the end are
  checked, since losses leave :tail high. A failed move leaves `c` :unordered."
  [c slot r defer? parent-tag parent-widget widget]
  (let [cm @c]
    (cond
      (or (>= r (:tail cm -1)) defer?)
      (swap! c record-gain r slot defer?)

      (and (nil? (:shown cm))
           (= r (tail-rank (:children cm) (:ranks cm) (dec (count (:children cm))))))
      (swap! c #(assoc % :tail r))

      :else
      (let [built (or (:shown cm) (shown-index cm))
            [prev stale-before] (first-valid cm (rsubseq built < r))
            [later stale-after] (first-valid cm (subseq built > r))]
        ;; With no widget after this one, r is now the highest rank holding one.
        (swap! c (fn [m]
                   (let [shown (reduce (fn [sh e] (drop-entry sh (key e) (val e)))
                                       (or (:shown m) built)
                                       (into stale-before stale-after))]
                     (cond-> (assoc m :shown (assoc shown r slot))
                       (nil? later) (assoc :tail r)))))
        (when later
          (try
            (b/reorder-child! parent-tag parent-widget widget
                              (when prev (inst-widget (val prev))))
            (catch Throwable error
              (swap! c #(assoc % :unordered true))
              (throw error))))))))

(defn- kept-mask
  "Which slots of a reordering keyed pass keep their rank: the reused ones along
  a longest increasing subsequence of their old indices (`old-index`, in new
  order, nil when created). Only the others move, so moving one row costs one
  move; a longest contiguous run would move half the rows for one crossing it."
  [old-index]
  (let [n (count old-index)
        ;; tails[k]: the position whose old index is the smallest that ends an
        ;; increasing run of length k+1. links[j]: the position before j on its run.
        [tails links]
        (loop [j 0 tails [] links []]
          (if (= j n)
            [tails links]
            (if-let [oi (nth old-index j)]
              (let [k (loop [lo 0 hi (count tails)]
                        (if (< lo hi)
                          (let [mid (quot (+ lo hi) 2)]
                            (if (< (nth old-index (nth tails mid)) oi)
                              (recur (inc mid) hi)
                              (recur lo mid)))
                          lo))]
                (recur (inc j) (assoc tails k j)
                       (conj links (when (pos? k) (nth tails (dec k))))))
              (recur (inc j) tails (conj links nil)))))]
    (loop [j (peek tails) mask (vec (repeat n false))]
      (if j (recur (nth links j) (assoc mask j true)) mask))))

(defn- spread-ranks
  "[index rank] pairs for the slots of a keyed pass that `kept` leaves out. A run
  of them is spread evenly over the gap between the ranks of its kept neighbours
  (`kept-rank`, by index), down to 0 before the first and at the stride after the
  last; nil when a gap has too few free integers."
  [n kept kept-rank]
  (loop [j 0 lo-j nil out (transient [])]
    (cond
      (= j n) (persistent! out)
      (nth kept j) (recur (inc j) j out)
      :else
      (let [end (loop [b j] (if (and (< b n) (not (nth kept b))) (recur (inc b)) b))
            run (- end j)
            lo (if lo-j (kept-rank lo-j) 0)
            hi (when (< end n) (kept-rank end))]
        (if (and hi (<= (- hi lo) run))
          nil
          (recur end lo-j
                 (reduce (fn [out t]
                           (conj! out [(+ j t)
                                       (if hi
                                         (+ lo (quot (* (- hi lo) (inc t)) (inc run)))
                                         (+ lo (* stride (inc t))))]))
                         out (range run))))))))

(defn- keyed-order
  "The container map `m` once a keyed pass publishes `child-atoms`. `old-index`
  holds each slot's old index (nil when created), `kept` whether it keeps its
  rank, and `destroyed` the [old-index slot] pairs the pass removed. The others
  are spread between their kept neighbours, which makes ranks explicit; when a
  gap has run out, ranks go back to implicit along the new order, and the index
  is dropped for the next late placement to rebuild. A `reorder?` leaves the
  container :unordered until the pass's move step."
  [m child-atoms old-index kept reorder? destroyed]
  (let [old-ranks (:ranks m)
        n (count child-atoms)
        old-rank (fn [oi slot] (if old-ranks (get old-ranks slot) (* stride (inc oi))))
        last-reused (loop [j (dec n)]
                      (cond (neg? j) nil
                            (nth old-index j) j
                            :else (recur (dec j))))
        ;; With implicit ranks and no reorder, the reused slots come first in
        ;; their old order, so their ranks hold unless a destroy shifted them,
        ;; which takes the last one off its old index.
        implicit? (and (nil? old-ranks) (not reorder?))
        placed (when-not implicit?
                 (spread-ranks n kept #(old-rank (nth old-index %) (nth child-atoms %))))
        ranks (when placed
                (reduce (fn [rs [j rk]] (assoc rs (nth child-atoms j) rk))
                        (if old-ranks
                          (reduce (fn [rs [_ slot]] (dissoc rs slot)) old-ranks destroyed)
                          (persistent!
                            (reduce (fn [rs j]
                                      (if (nth kept j)
                                        (assoc! rs (nth child-atoms j) (* stride (inc (nth old-index j))))
                                        rs))
                                    (transient {}) (range n))))
                        placed))
        keep-index? (if implicit?
                      (or (nil? last-reused) (= last-reused (nth old-index last-reused)))
                      placed)
        shown (when-let [sh (and keep-index? (:shown m))]
                ;; a moved slot that has a widget keeps an entry, at its new rank
                (reduce (fn [sh [j rk]]
                          (let [slot (nth child-atoms j)]
                            (if-let [oi (nth old-index j)]
                              (cond-> (drop-entry sh (old-rank oi slot) slot)
                                (inst-widget slot) (assoc rk slot))
                              sh)))
                        (reduce (fn [sh [oi slot]] (drop-entry sh (old-rank oi slot) slot)) sh destroyed)
                        placed))]
    (assoc m :children child-atoms :ranks ranks :shown shown
             :tail (tail-rank child-atoms ranks last-reused)
             :unordered (or reorder? (:unordered m)))))

(defn- positional-order
  "The container map `m` once a positional pass publishes `reused`. Slots keep
  their index, so it drops explicit ranks a keyed pass left, and the index keyed
  by them: an appended slot's implicit rank could repeat one of them. Truncation
  clamps :tail and drops the removed slots' entries."
  [m reused]
  (let [n (count reused)
        old-n (count (:children m))
        m (assoc m :children reused)]
    (cond
      (:ranks m) (-> m (dissoc :ranks :shown) (assoc :tail (tail-rank reused nil (dec (min n old-n)))))
      (< n old-n) (let [top (* stride n)]
                    (cond-> (assoc m :tail (if (zero? n) -1 (min (:tail m -1) top)))
                      (:shown m) (update :shown #(reduce dissoc % (map key (subseq % > top))))))
      :else m)))

(defn- move-widgets!
  "A pass's move step, once its children have rendered: move each widget after
  the one before it, or first, except the anchors, kept slots whose widget is the
  one `before` holds for them (by index into `published`). With no `before`, or
  once a nested pass replaced the children, every widget moves; a pass replacing
  them during the step stops it. A completed step clears :unordered."
  [parent-widget parent-tag c published before]
  (let [cs (:children @c)
        before (when (identical? cs published) before)
        n (count cs)]
    (loop [j 0 prev nil]
      (cond
        (not (identical? cs (:children @c))) nil
        (= j n) (swap! c #(dissoc % :unordered))
        :else
        (if-let [w (inst-widget (nth cs j))]
          (do (when-not (and before (identical? w (nth before j)))
                (b/reorder-child! parent-tag parent-widget w prev))
              (recur (inc j) w))
          (recur (inc j) prev))))))

(defn- render-slots!
  "Run (render! i slot) for each slot of `published`, the children a pass
  published on the container `c`. A render can re-render the container's owner
  inline, running a nested pass on `c` that drops slots: those are skipped, and
  one dropped while it rendered is disposed and cleared, even when the render
  throws, or a component recreated in it would keep a watcher nothing reaches."
  [c published render!]
  (when (seq published) ; every leaf runs an empty pass: no try for it
    (let [at (volatile! -1)
          listed? (fn [i] (some? (slot-position @c (nth published i) i)))
          settle! (fn [i] (when-not (listed? i)
                            (dispose-tree! (nth published i))
                            (reset! (nth published i) nil)))]
      (try
        (loop [i 0 current? true]
          (when (< i (count published))
            (if (or current? (listed? i))
              (do (vreset! at i)
                  (render! i (nth published i))
                  (let [current? (identical? (:children @c) published)]
                    (when-not current? (settle! i))
                    (recur (inc i) current?)))
              (recur (inc i) false))))
        (catch Throwable error
          (when-not (neg? @at) (settle! @at))
          (throw error))))))

;; --- children passes ---------------------------------------------------------
(defn- reconcile-positional-children!
  "Positionally reconcile `new-children` (a flattened vector of hiccup) against the
  instance's existing children, reusing widgets where the element shape matches at
  a position. The default path when children are not uniformly keyed."
  [parent-widget parent-tag new-children inst-atom]
  (let [cm @inst-atom
        old (:children cm)
        unordered? (:unordered cm)
        n (count new-children)
        ;; reuse the child atom at each position when it exists; else a fresh one
        reused (vec (for [i (range n)]
                      (if (< i (count old)) (nth old i) (atom nil))))]
    ;; remove widgets for children that no longer exist, clearing their slots
    (doseq [i (range n (count old))]
      (dispose-tree! (nth old i))
      (destroy-inst! (nth old i) parent-widget parent-tag)
      (reset! (nth old i) nil))
    ;; Publish slots before rendering: a failed sibling must still be disposable.
    (swap! inst-atom #(positional-order % reused))
    ;; reconcile each child into its (possibly new) atom
    (render-slots! inst-atom reused
                   (fn [i a]
                     (reconcile-el! parent-widget parent-tag (nth new-children i) a
                                    {:container inst-atom :slot a :index i})))
    (when unordered?
      (move-widgets! parent-widget parent-tag inst-atom reused nil))))

(defn- reconcile-keyed-children!
  "Reconcile uniformly-keyed `new-children` (a flattened vector of hiccup, each
  carrying a non-nil :key) by stable identity rather than position. Derives a
  reuse/create/reorder plan from keyed-plan, destroys removed children, reuses
  surviving atoms by key, creates fresh atoms for new keys, re-applies each
  child's hiccup into its atom, and fixes widget order when the plan requires it.
  Stores the child's :key and :index on its instance so the next render can read
  old keys back — that is how a row's widget and signal handlers follow the item
  across add/remove/reorder instead of a stale position. Widgets gained during a
  pass that moves any defer to its move step; a render error skips that step and
  leaves the container :unordered."
  [parent-widget parent-tag new-children inst-atom]
  (let [cm @inst-atom
        old (:children cm)
        unordered? (:unordered cm)
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
      (destroy-inst! (nth old i) parent-widget parent-tag)
      (reset! (nth old i) nil))
    (let [child-atoms (vec (for [slot slots]
                             (if (= :reuse (:kind slot))
                               (get old-by-key (:key slot))
                               (atom nil))))
          old-index (mapv :old-index slots)
          kept (if reorder? (kept-mask old-index) (mapv some? old-index))
          ;; each kept slot's widget before the pass, to tell the ones still in place
          before (when (and reorder? (not unordered?))
                   (mapv (fn [a k?] (when k? (inst-widget a))) child-atoms kept))
          moves? (or reorder? unordered?)]
      ;; Publish slots before rendering: a failed sibling must still be disposable.
      (let [destroyed (mapv (fn [i] [i (nth old i)]) destroy)]
        (swap! inst-atom #(keyed-order % child-atoms old-index kept reorder? destroyed)))
      (render-slots! inst-atom child-atoms
                     (fn [i a]
                       (reconcile-el! parent-widget parent-tag (nth new-children i) a
                                      {:container inst-atom :slot a :index i :defer moves?})
                       (let [k (nth new-keys i)] (swap! a #(assoc % :key k :index i)))))
      (when moves?
        (move-widgets! parent-widget parent-tag inst-atom child-atoms before)))))

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

(defn- reconcile-native!
  [parent-widget parent-tag v inst-atom ctx]
  (let [{:keys [tag props children]} (parse-native v)
        inst @inst-atom
        reuse? (and inst (= (:type inst) :native) (= (:tag inst) tag))
        c (when-not reuse? (:container ctx)) ; no container at the root
        cm (when c @c)
        rank (when c (slot-rank cm (:slot ctx) (:index ctx)))]
    (cond
      reuse?
      (do (b/apply-props! tag (:widget inst) props)
          (swap! inst-atom assoc :props props)
          (reconcile-children! (:widget inst) tag children inst-atom))

      ;; a nested pass dropped the slot while this render was under way
      (and c (nil? rank)) nil

      :else
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
              ;; Clear the slot first, keeping its key, so a failed append
              ;; leaves it holding no removed widget.
              (when inst
                (dispose-tree! inst-atom)
                (destroy-inst! inst-atom parent-widget parent-tag)
                (reset! inst-atom (not-empty (select-keys inst [:key :index]))))
              (b/append-child! parent-tag parent-widget widget)))
          (catch Throwable error
            (b/dispose-widget! widget)
            (throw error)))
        (reset! inst-atom new)
        ;; The append is the only way a slot gains a widget. A gain in order, as
        ;; every one of a mount is, writes over the map read for its rank unless
        ;; the container has changed since; place-gained! rereads it.
        (when (and rank (not replacing?))
          (let [slot (:slot ctx) defer? (:defer ctx)]
            (when-not (and (or defer? (>= rank (:tail cm -1)))
                           (compare-and-set! c cm (record-gain cm rank slot defer?)))
              (place-gained! c slot rank defer? parent-tag parent-widget widget))))
        (reconcile-children! widget tag children inst-atom)))))

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
  callable, so ifn? would treat every Form-1 result as a Form-2 render fn."
  [parent-widget parent-tag v inst-atom ctx]
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
    (swap! inst-atom (fn [c] (cond-> (assoc c :v v :type :comp)
                               (nil? cached-render-fn) (dissoc :render-fn))))
    (let [child-atom (first (:children @inst-atom))
          ;; subscribe: any reactive read during render re-runs just this component.
          ;; The watcher is cached on the instance so it's the SAME fn every render;
          ;; a fresh closure would grow each cell's watch set on every re-render.
          watcher (or (:watcher @inst-atom)
                      ;; Disposal detaches the scope and fences already-queued renders.
                      ;; The watcher places into the slot it was created in; it
                      ;; renders outside any pass, so it never defers to a move step.
                      (let [disposed (atom false)
                            ctx (dissoc ctx :defer)
                            w (make-rerender-watcher
                                (rerender-thunk #(reconcile-comp! %1 %2 %3 %4 ctx)
                                                parent-widget parent-tag inst-atom)
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
      (reconcile-el! parent-widget parent-tag hiccup child-atom ctx))))

(defn- reconcile-el!
  "Reconcile one hiccup element (native, component, or primitive) into inst-atom.
  `ctx` places a widget the element gains among its container's other slots; nil
  at the root."
  [parent-widget parent-tag el inst-atom ctx]
  (cond
    (nil? el) (do (dispose-tree! inst-atom)
                  (destroy-inst! inst-atom parent-widget parent-tag)
                  (reset! inst-atom nil))
    (string? el) (reconcile-native! parent-widget parent-tag (b/text->element el) inst-atom ctx)
    (number? el) (reconcile-native! parent-widget parent-tag (b/text->element (str el)) inst-atom ctx)
    (vector? el)
    (let [head (first el)]
      (cond
        (keyword? head) (reconcile-native! parent-widget parent-tag el inst-atom ctx)
        (fn? head)      (reconcile-comp! parent-widget parent-tag el inst-atom ctx)
        :else (throw (ex-info (str "unsupported hiccup element: " (pr-str el)) {:el el}))))
    :else (reconcile-native! parent-widget parent-tag (b/text->element (pr-str el)) inst-atom ctx)))

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
                             ;; What mount-root! returned before this seam;
                             ;; legacy backends (glimmer-appkit) keep its :inst.
                             @state)
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
