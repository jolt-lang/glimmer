(ns glimmer.table
  "Keyed collections whose derived cells update by the change, not by the
  whole collection.

  A reaction over a collection, (reaction (count (filter open? @todos))),
  recomputes from scratch on every write: a thousand rows and one changed row
  cost a thousand. The cells here do the work of the change instead, following
  DBSP (Budiu et al., \"Automatic Incremental View Maintenance for Rich Query
  Languages\"): a row-at-a-time map or filter only has to look at the rows that
  changed, and an aggregate whose add has an inverse (a count, a sum) can take
  the old row out and put the new one in.

    (table m)                     a writable map cell, key -> row
    (view src f)                  rows (f k v), dropping nil
    (aggregate src agg)           agg folded over every row
    (aggregate src agg group-fn)  {group agg-over-its-rows}, by (group-fn k v)

  All are reactive cells: read them with @, subscribe to them, use them in
  reactions. Views and grouped aggregates are tables to further views and
  aggregates, so they compose. put! and delete! cost the row they touch;
  swap! and reset! on a table work too but compare the old map with the new to
  find what changed, which looks at every row.

  Derived cells register on their source for good, as cursors and reactions
  do, so make them once (def them) rather than per render.

  Each derived cell reads its source's current value for the keys a write
  touched, inside the swap that updates it, so writes from several threads
  converge on the source's value rather than depending on the order the
  updates arrive in."
  (:require [glimmer.ratom :as r]))

(defprotocol IChanges
  "A cell that reports which keys of its map value a write changed."
  (-listen! [this f] "Call (f keys) after each write that changes those keys."))

(defn- notify!
  "Tell the cell's listeners, then its watchers, that ks changed."
  [cell listeners ks]
  (when (seq ks)
    (doseq [f (clojure.core/deref listeners)] (f ks))
    (r/-notify-watches! cell)))

(defn- changed-keys
  "The keys whose rows differ between maps a and b."
  [a b]
  (when-not (identical? a b)
    (let [gone (reduce-kv (fn [acc k v]
                            (if (and (contains? b k) (= v (get b k)))
                              acc
                              (conj acc k)))
                          [] a)]
      (reduce-kv (fn [acc k _]
                   (if (contains? a k) acc (conj acc k)))
                 gone b))))

(defn- read-only [cell]
  (throw (ex-info "a derived table cell is read-only" {:cell cell})))

;; --- table ---------------------------------------------------------------------

(defrecord Table [state watches listeners]
  r/IReactiveCell
  (-value [_] (clojure.core/deref state))
  (-reset! [this v]
    (let [[old _] (reset-vals! state v)]
      (notify! this listeners (changed-keys old v))
      v))
  (-add-watch! [_ w] (swap-vals! watches conj w) nil)
  (-remove-watch! [_ w] (swap-vals! watches disj w) nil)
  (-notify-watches! [this]
    (doseq [w (clojure.core/deref watches)] (w this)))
  r/IAtomicSwap
  (-swap! [this f]
    (let [[old new] (swap-vals! state f)]
      (notify! this listeners (changed-keys old new))
      new))
  IChanges
  (-listen! [_ f] (swap-vals! listeners conj f) nil))

(defn table
  "A writable reactive map, key -> row, that views and aggregates can follow
  by the change. Write rows with put! and delete!."
  ([] (table {}))
  ([m] (->Table (clojure.core/atom (or m {})) (clojure.core/atom #{})
                (clojure.core/atom []))))

(defn put!
  "Set row k of table tb to v."
  [tb k v]
  (let [[old _] (swap-vals! (:state tb) assoc k v)]
    (when-not (and (contains? old k) (= v (get old k)))
      (notify! tb (:listeners tb) [k]))
    v))

(defn delete!
  "Remove row k from table tb."
  [tb k]
  (let [[old _] (swap-vals! (:state tb) dissoc k)]
    (when (contains? old k)
      (notify! tb (:listeners tb) [k]))
    nil))

;; --- view ------------------------------------------------------------------------

(defrecord View [src f state watches listeners]
  r/IReactiveCell
  (-value [_] (clojure.core/deref state))
  (-reset! [this _] (read-only this))
  (-add-watch! [_ w] (swap-vals! watches conj w) nil)
  (-remove-watch! [_ w] (swap-vals! watches disj w) nil)
  (-notify-watches! [this]
    (doseq [w (clojure.core/deref watches)] (w this)))
  r/IAtomicSwap
  (-swap! [this _] (read-only this))
  IChanges
  (-listen! [_ g] (swap-vals! listeners conj g) nil))

(defn- refresh-rows
  "m with rows ks recomputed from the source map sv."
  [m f sv ks]
  (reduce (fn [m k]
            (let [v (if (contains? sv k) (f k (get sv k)) nil)]
              (if (nil? v) (dissoc m k) (assoc m k v))))
          m ks))

(defn view
  "A read-only table of (f k v) for each row of src (a table, a view or a
  grouped aggregate), leaving out rows where f returns nil. A write to src
  recomputes only the rows it touched."
  [src f]
  (let [vw (->View src f (clojure.core/atom {}) (clojure.core/atom #{})
                   (clojure.core/atom []))
        state (:state vw)]
    (-listen! src (fn [ks]
                    (let [[old new] (swap-vals! state
                                                #(refresh-rows % f (r/-value src) ks))]
                      (notify! vw (:listeners vw)
                               (filterv #(not= (get old % ::none) (get new % ::none)) ks)))))
    ;; after listening, so a write landing in between is not missed
    (swap-vals! state (fn [_] (let [sv (r/-value src)]
                                (refresh-rows {} f sv (keys sv)))))
    vw))

;; --- aggregate ---------------------------------------------------------------------

(def count-of
  "Counts rows."
  {:init 0 :add (fn [acc _] (inc acc)) :sub (fn [acc _] (dec acc))})

(defn sum-of
  "Sums (f row)."
  [f]
  {:init 0 :add (fn [acc v] (+ acc (f v))) :sub (fn [acc v] (- acc (f v)))})

(defrecord Aggregate [src agg group-fn state watches listeners]
  r/IReactiveCell
  (-value [_] (:value (clojure.core/deref state)))
  (-reset! [this _] (read-only this))
  (-add-watch! [_ w] (swap-vals! watches conj w) nil)
  (-remove-watch! [_ w] (swap-vals! watches disj w) nil)
  (-notify-watches! [this]
    (doseq [w (clojure.core/deref watches)] (w this)))
  r/IAtomicSwap
  (-swap! [this _] (read-only this))
  IChanges
  (-listen! [_ g] (swap-vals! listeners conj g) nil))

(defn- fold-rows
  "Aggregate state s with rows ks taken from the source map sv. :seen keeps
  each row as it was counted, with its group, so it can be taken out again;
  :groups keeps each group's row count and accumulator, and a group whose
  count reaches zero is dropped rather than kept at init. :touched is the
  groups this step changed."
  [{:keys [init add sub]} group-fn sv s ks]
  (reduce
   (fn [{:keys [seen groups touched] :as s} k]
     (let [[g0 v0 :as was] (get seen k)
           now? (contains? sv k)
           v (get sv k)]
       (if (and was now? (= v v0))
         s
         (let [groups (if was
                        (let [{:keys [n acc]} (get groups g0)]
                          (if (= 1 n)
                            (dissoc groups g0)
                            (assoc groups g0 {:n (dec n) :acc (sub acc v0)})))
                        groups)
               touched (if was (conj touched g0) touched)]
           (if now?
             (let [g (group-fn k v)
                   {:keys [n acc] :or {n 0 acc init}} (get groups g)]
               (assoc s
                      :seen (assoc seen k [g v])
                      :groups (assoc groups g {:n (inc n) :acc (add acc v)})
                      :touched (conj touched g)))
             (assoc s :seen (dissoc seen k) :groups groups :touched touched))))))
   (assoc s :touched #{})
   ks))

(defn- group-values
  "The {group acc} map v with the touched groups brought up to date."
  [v groups touched]
  (reduce (fn [v g]
            (if-let [e (get groups g)]
              (assoc v g (:acc e))
              (dissoc v g)))
          v touched))

(defn aggregate
  "A read-only cell folding agg over the rows of src (a table, a view or a
  grouped aggregate). agg is {:init x :add (fn [acc row]) :sub (fn [acc row])},
  where sub undoes add; count-of and sum-of are two. A write to src costs a sub
  for each old row it touched and an add for each new one.

  With group-fn, (fn [k row]) -> group, the value is a map of group to the
  fold over that group's rows, and an empty group is left out. That map is a
  table to further views and aggregates."
  ([src agg] (aggregate src agg nil))
  ([src agg group-fn]
   (let [gf (or group-fn (constantly ::all))
         a (->Aggregate src agg group-fn
                        (clojure.core/atom {:seen {} :groups {} :touched #{}
                                            :value (if group-fn {} (:init agg))})
                        (clojure.core/atom #{}) (clojure.core/atom []))
         state (:state a)
         step (fn [s sv ks]
                (let [{:keys [groups touched] :as s} (fold-rows agg gf sv s ks)]
                  (assoc s :value (if group-fn
                                    (group-values (:value s) groups touched)
                                    (get-in groups [::all :acc] (:init agg))))))]
     (-listen! src (fn [ks]
                     (let [[old new] (swap-vals! state #(step % (r/-value src) ks))
                           ov (:value old) nv (:value new)]
                       (when-not (identical? ov nv)
                         (if group-fn
                           (notify! a (:listeners a)
                                    (filterv #(not= (get ov % ::none) (get nv % ::none))
                                             (:touched new)))
                           (when (not= ov nv) (r/-notify-watches! a)))))))
     (swap-vals! state (fn [s] (let [sv (r/-value src)] (step s sv (keys sv)))))
     a)))
