(ns glimmer.table-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.ratom :as r]
            [glimmer.table :as t]))

(defn- counting
  "An aggregate like agg whose add and sub calls are counted in calls."
  [agg calls]
  (-> agg
      (update :add (fn [f] (fn [acc v] (clojure.core/swap! calls inc) (f acc v))))
      (update :sub (fn [f] (fn [acc v] (clojure.core/swap! calls inc) (f acc v))))))

(deftest table-reads-and-writes
  (let [tb (t/table {:a 1})]
    (is (= {:a 1} @tb))
    (t/put! tb :b 2)
    (is (= {:a 1 :b 2} @tb))
    (t/delete! tb :a)
    (is (= {:b 2} @tb))
    (testing "swap! and reset! work as on any cell"
      (swap! tb assoc :c 3)
      (is (= {:b 2 :c 3} @tb))
      (reset! tb {:z 0})
      (is (= {:z 0} @tb)))))

(deftest table-notifies-watchers-only-on-change
  (let [tb (t/table {:a 1})
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (clojure.core/swap! fired inc))]
      @tb)
    (t/put! tb :a 1)
    (t/delete! tb :missing)
    (is (= 0 @fired))
    (t/put! tb :a 2)
    (is (= 1 @fired))))

(deftest view-maps-and-filters-rows
  (let [tb (t/table {1 {:done false :title "a"} 2 {:done true :title "b"}})
        open (t/view tb (fn [_ v] (when-not (:done v) (:title v))))]
    (is (= {1 "a"} @open))
    (t/put! tb 3 {:done false :title "c"})
    (is (= {1 "a" 3 "c"} @open))
    (t/put! tb 1 {:done true :title "a"})
    (is (= {3 "c"} @open))
    (t/delete! tb 3)
    (is (= {} @open))
    (testing "a write through swap! reaches the view too"
      (swap! tb assoc 4 {:done false :title "d"})
      (is (= {4 "d"} @open)))))

(deftest view-notifies-only-when-its-rows-change
  (let [tb (t/table {1 1 2 2})
        evens (t/view tb (fn [_ v] (when (even? v) v)))
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (clojure.core/swap! fired inc))]
      @evens)
    (t/put! tb 3 3)                     ; odd: not in the view
    (is (= 0 @fired))
    (t/put! tb 4 4)
    (is (= 1 @fired))))

(deftest views-compose
  (let [tb (t/table {1 1 2 2 3 3 4 4})
        evens (t/view tb (fn [_ v] (when (even? v) v)))
        sq (t/view evens (fn [_ v] (* v v)))]
    (is (= {2 4 4 16} @sq))
    (t/put! tb 6 6)
    (is (= {2 4 4 16 6 36} @sq))
    (t/put! tb 2 3)
    (is (= {4 16 6 36} @sq))))

(deftest aggregate-over-a-table
  (let [tb (t/table {:a 1 :b 2})
        total (t/aggregate tb (t/sum-of identity))
        n (t/aggregate tb t/count-of)]
    (is (= 3 @total))
    (is (= 2 @n))
    (t/put! tb :c 10)
    (is (= 13 @total))
    (t/put! tb :a 5)                    ; an update is a sub then an add
    (is (= 17 @total))
    (is (= 3 @n))
    (t/delete! tb :b)
    (is (= 15 @total))
    (is (= 2 @n))
    (reset! tb {})
    (is (= 0 @total))
    (is (= 0 @n))))

(deftest aggregate-work-follows-the-change-not-the-table
  (let [tb (t/table (into {} (map (fn [i] [i i])) (range 1000)))
        calls (clojure.core/atom 0)
        total (t/aggregate tb (counting (t/sum-of identity) calls))]
    (is (= (reduce + (range 1000)) @total))
    (clojure.core/reset! calls 0)
    (t/put! tb 7 1007)
    (is (= (+ 1000 (reduce + (range 1000))) @total))
    (is (= 2 @calls) "one sub for the old row, one add for the new")))

(deftest grouped-aggregate
  (let [tb (t/table {1 {:status :open} 2 {:status :done} 3 {:status :open}})
        by-status (t/aggregate tb t/count-of (fn [_ v] (:status v)))]
    (is (= {:open 2 :done 1} @by-status))
    (t/put! tb 2 {:status :open})
    (is (= {:open 3} @by-status) "an emptied group goes")
    (t/put! tb 4 {:status :blocked})
    (is (= {:open 3 :blocked 1} @by-status))
    (testing "a grouped aggregate is a table to further views"
      (let [busy (t/view by-status (fn [_ n] (when (> n 1) n)))]
        (is (= {:open 3} @busy))
        (t/delete! tb 1)
        (t/delete! tb 3)
        (is (= {} @busy))
        (is (= {:open 1 :blocked 1} @by-status))))))

(deftest derived-cells-are-read-only
  (let [tb (t/table {})
        v (t/view tb (fn [_ v] v))
        a (t/aggregate tb t/count-of)]
    (is (thrown? Exception (reset! v {})))
    (is (thrown? Exception (swap! a inc)))))

(deftest reactions-and-capture-see-derived-cells
  (let [tb (t/table {:a 1})
        total (t/aggregate tb (t/sum-of identity))
        label (r/reaction (str "total " @total))
        [_ cells] (r/capture #(deref total))]
    (is (= [total] cells))
    (is (= "total 1" @label))
    (t/put! tb :b 2)
    (is (= "total 3" @label))))

(deftest concurrent-writes-converge
  (let [tb (t/table {})
        total (t/aggregate tb (t/sum-of identity))
        evens (t/view tb (fn [_ v] (when (even? v) v)))
        n 200
        fs (doall (for [w (range 4)]
                    (future (dotimes [i n]
                              (t/put! tb (mod i 50) (+ (* w n) i))
                              (when (zero? (mod i 7)) (t/delete! tb (mod (* 3 i) 50)))))))]
    (run! deref fs)
    (is (= (reduce + (vals @tb)) @total))
    (is (= (into {} (filter (fn [[_ v]] (even? v))) @tb) @evens))))
