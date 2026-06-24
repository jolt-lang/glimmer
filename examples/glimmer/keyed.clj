(ns glimmer.keyed
  "Non-interactive proof that glimmer's keyed reconciliation reuses widgets across
  add/remove/reorder instead of recreating them by position. Mounts a vbox of
  labels keyed by a stable :id, then mutates the backing list — reverse, remove a
  middle item, insert at the front — and after each mutation checks two things
  against the live GTK tree:

    1. surviving keys keep the SAME widget pointer (reuse, not recreate), so a
       row's signal handlers and local state would follow its key; and
    2. GTK's actual child order (walked via get_first_child/get_next_sibling)
       matches the requested item order.

  Reconciliation is synchronous (a ratom write notifies watchers inline), so each
  mutation is fully reconciled by the time we assert. Run via the :keyed task;
  needs a display. Exits non-zero on any failed check."
  (:require [glimmer.ratom :refer [atom]]
            [glimmer.core :as ui]
            [glimmer.widget :as w]
            [glimmer.ffi :as g]
            [jolt.ffi :as ffi]))

(def items (atom [{:id :a :text "alpha"}
                  {:id :b :text "bravo"}
                  {:id :c :text "charlie"}]))

(def failures (atom []))
(def result (atom :pending))

(defn app []
  (into [:vbox {:spacing 6 :margin 16}]
        (for [{:keys [id text]} @items]
          [:label {:key id :label text :halign :start}]))) ; keyed by :id

;; --- reading the live tree ---------------------------------------------------
;; GTK child pointers in visual order: first-child, then next-sibling until NULL
;; (pointers are plain numbers; a NULL return reads as 0).
(defn- gtk-children [box]
  (loop [c (g/gtk-widget-get-first-child box) acc []]
    (if (zero? c) acc (recur (g/gtk-widget-get-next-sibling c) (conj acc c)))))

;; The mounted vbox lives one level under the root component (a component owns no
;; widget of its own — its single child atom holds the expanded native vbox).
(defn- vbox-atom [root] (first (:children @root)))
(defn- vbox-widget [root] (:widget @(vbox-atom root)))

;; key -> widget pointer, read from the reconciler's own instance tree.
(defn- key->widget [root]
  (into {} (map (fn [a] (let [i @a] [(:key i) (:widget i)])) (:children @(vbox-atom root)))))

;; --- assertions --------------------------------------------------------------
(defn- record! [ok? label]
  (when-not ok? (swap! failures conj label)))

(defn- check-order!
  "GTK's actual child order matches `ids` (the requested item order), and each id
  still maps to `expected`'s widget pointer (reuse, not recreate)."
  [root ids expected label]
  (let [now (key->widget root)]
    (record! (= (gtk-children (vbox-widget root))
                (mapv now ids))
             (str label " :gtk-order"))
    (doseq [id ids :when (contains? expected id)]
      (record! (= (now id) (expected id))
               (str label " :reuse " id)))))

(defn- run-checks! [root]
  ;; baseline: three rows, in order.
  (let [w0 (key->widget root)]
    (check-order! root [:a :b :c] w0 "baseline")

    ;; reverse — survivors reused, order flipped.
    (reset! items [{:id :c :text "charlie"} {:id :b :text "bravo"} {:id :a :text "alpha"}])
    (check-order! root [:c :b :a] w0 "reverse")

    ;; remove the middle item — :b's widget is gone, :c/:a unchanged.
    (reset! items [{:id :c :text "charlie"} {:id :a :text "alpha"}])
    (check-order! root [:c :a] w0 "remove")
    (record! (not (contains? (key->widget root) :b)) "remove :b-gone")

    ;; insert a fresh item at the front — :c/:a still reused, :d is new.
    (reset! items [{:id :d :text "delta"} {:id :c :text "charlie"} {:id :a :text "alpha"}])
    (check-order! root [:d :c :a] w0 "insert")
    (let [now (key->widget root)]
      (record! (contains? now :d) "insert :d-present")
      (record! (not (contains? w0 (now :d))) "insert :d-is-new"))))

(defn- driver [root app-obj]
  (w/retain-callable!
    (ffi/foreign-callable
      (fn [_]
        (try (run-checks! root)
             (reset! result (if (empty? @failures) :pass :fail))
             (catch :default e
               (reset! result :fail)
               (swap! failures conj (str "threw: " e))))
        (g/g-application-quit app-obj) 0)
      [:pointer] :int :collect-safe)))

(defn -main [& _]
  (try
    (let [app-obj (g/gtk-application-new "glimmer.keyed" g/APPLICATION-DEFAULT-FLAGS)
          activate (fn [_ _]
                     (let [win  (g/gtk-application-window-new app-obj)
                           root (do (g/gtk-window-set-title win "keyed smoke")
                                    (g/gtk-window-set-default-size win 240 180)
                                    (ui/mount win :window [app]))]
                       (g/gtk-window-present win)
                       (g/g-timeout-add 300 (driver root app-obj) ffi/null)))
          activate-cb (w/retain-callable!
                        (ffi/foreign-callable activate [:pointer :pointer] :void :collect-safe))]
      (g/g-signal-connect-data app-obj "activate" activate-cb ffi/null ffi/null g/CONNECT-DEFAULT)
      (g/g-application-run app-obj 0 ffi/null)
      (prn :keyed :result @result :failures @failures)
      (when (not= :pass @result)
        (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1)))))
    (catch :default e
      (prn :keyed-error e)
      (let [exit (resolve 'jolt.host/exit)] (when exit (exit 1))))))
