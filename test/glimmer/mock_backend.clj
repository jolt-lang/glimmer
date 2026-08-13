(ns glimmer.mock-backend
  "An in-memory backend, so the reconciler can be exercised headlessly — and so
  the backend contract in glimmer.backend has a second implementation besides
  GTK, which is the point of the split.

  A widget is an atom holding {:id n :tag k :props m :children [widget...]}. The
  :id is a process-unique number: it is how a test asserts that a widget was
  REUSED across a render rather than recreated, since the instance tree hands
  back the same atom either way.

  Containers hold an ordered vector of children, so append/remove/replace/reorder
  all behave like a GtkBox. Leaf tags are not special-cased — nothing stops a
  test from appending to a :label — because the reconciler only ever appends to
  something it was told is a container."
  (:require [glimmer.backend :as b]))

(defonce ^:private next-id (atom 0))

(defn widget
  "A fresh mock widget. Also the backend's :create! (props are stored, not
  interpreted — :on-* handlers included, so a test can fire one by hand)."
  [tag props]
  (atom {:id (swap! next-id inc) :tag tag :props props :children []}))

(defn- apply-props!
  ;; merge, not replace: a real toolkit setter only touches the props it is
  ;; given, and unmentioned ones keep their previous value.
  [_tag w props]
  (swap! w update :props merge props)
  nil)

(defn- index-of [children child]
  (first (keep-indexed (fn [i c] (when (= (:id @c) (:id @child)) i)) children)))

(defn- append-child! [_parent-tag parent child]
  (swap! parent update :children conj child)
  nil)

(defn- remove-child! [_parent-tag parent child]
  (swap! parent update :children
         (fn [cs] (vec (remove #(= (:id @%) (:id @child)) cs))))
  nil)

(defn- replace-child! [_parent-tag parent old-child new-child]
  (swap! parent update :children
         (fn [cs]
           (if-let [i (index-of cs old-child)]
             (assoc cs i new-child)
             (conj cs new-child))))
  nil)

(defn- reorder-child! [_parent-tag parent child sibling]
  (swap! parent update :children
         (fn [cs]
           (let [without (vec (remove #(= (:id @%) (:id @child)) cs))
                 pos (if sibling
                       (if-let [i (index-of without sibling)] (inc i) (count without))
                       0)]
             (vec (concat (subvec without 0 pos) [child] (subvec without pos))))))
  nil)

(def backend
  {:name           :mock
   :create!        widget
   :apply-props!   apply-props!
   :append-child!  append-child!
   :remove-child!  remove-child!
   :replace-child! replace-child!
   :reorder-child! reorder-child!})

(defn install! [] (b/register! backend) nil)

;; --- reading the rendered tree ----------------------------------------------
(defn children
  "The widget's children, in order."
  [w] (:children @w))

(defn ids
  "The :id of each of the widget's children, in order — the identity assertion
  for reuse across renders."
  [w] (mapv (fn [c] (:id @c)) (children w)))

(defn tags
  "The tag of each of the widget's children, in order."
  [w] (mapv (fn [c] (:tag @c)) (children w)))

(defn tree
  "A plain-data snapshot of `w` — {:tag :props :children [...]}, ids omitted — for
  comparing a whole rendered subtree in one assertion."
  [w]
  (let [{:keys [tag props]} @w]
    {:tag tag :props props :children (mapv tree (children w))}))
