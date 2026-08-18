(ns glimmer.ratom-protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.ratom :as r :refer [atom cursor reaction]]))

;; A minimal durable-atom backend: every mutation is persisted to an in-memory
;; "database" (a plain map in a host atom) and the cell implements
;; glimmer.ratom/IReactiveCell so @, reset!, swap!, reactions and cursors all
;; work on it unchanged. This is the seam the protocol is meant to expose.

(defrecord DurableAtom [id store state watches]
  r/IReactiveCell
  (-value [_] @state)
  (-reset! [this v]
    (let [old @state]
      (when (not= old v)
        (reset! state v)
        (swap! store assoc id v)
        (r/-notify-watches! this))
      v))
  (-add-watch! [_ w] (swap! watches conj w))
  (-remove-watch! [_ w] (swap! watches disj w))
  (-notify-watches! [this] (doseq [w @watches] (w this))))

(defn durable-atom [id store v]
  (swap! store assoc id v)
  (->DurableAtom id store (clojure.core/atom v) (clojure.core/atom #{})))

(deftest durable-atom-reads-writes-and-persists
  (let [db (clojure.core/atom {})
        a (durable-atom :count db 0)]
    (is (= 0 @a))
    (reset! a 7)
    (is (= 7 @a))
    (is (= 7 (get @db :count)) "a reset! is persisted to the database")
    (swap! a inc)
    (is (= 8 @a))
    (is (= 8 (get @db :count)) "a swap! is persisted to the database")))

(deftest durable-atom-notifies-watchers-on-change
  (let [db (clojure.core/atom {})
        a (durable-atom :n db 0)
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (swap! fired inc))]
      @a)
    (reset! a 1)
    (is (= 1 @fired) "a watcher registered during deref fires once on change")
    (reset! a 1)
    (is (= 1 @fired) "no fire when the value is unchanged")))

(deftest durable-atom-composes-with-reaction
  (let [db (clojure.core/atom {})
        a (durable-atom :n db 2)
        squared (reaction (* @a @a))]
    (is (= 4 @squared))
    (reset! a 5)
    (is (= 25 @squared) "a reaction recomputes when a durable dependency changes")
    (is (= 5 (get @db :n)))))

(deftest durable-atom-composes-with-cursor
  (let [db (clojure.core/atom {})
        a (durable-atom :m db {:count 0})
        c (cursor a [:count])]
    (is (= 0 @c))
    (reset! c 9)
    (is (= 9 @c))
    (is (= 9 (get-in @db [:m :count])) "a cursor write persists through to the database")))

(deftest unwatch-removes-a-durable-atom-watcher
  (let [db (clojure.core/atom {})
        a (durable-atom :n db 0)
        fired (clojure.core/atom 0)
        w (fn [_] (swap! fired inc))]
    (binding [r/*current-watcher* w] @a)
    (r/unwatch! a w)
    (reset! a 1)
    (is (zero? @fired) "a removed watcher does not fire")))

(deftest satisfies-reactive-cell-protocol
  (testing "built-in cells implement the protocol"
    (is (satisfies? r/IReactiveCell (atom 0)))
    (is (satisfies? r/IReactiveCell (cursor (atom {:x 1}) [:x])))
    (is (satisfies? r/IReactiveCell (reaction 1))))
  (testing "a backend cell implements the protocol"
    (is (satisfies? r/IReactiveCell (durable-atom :n (clojure.core/atom {}) 0))))
  (testing "non-reactive references do not"
    (is (not (satisfies? r/IReactiveCell (clojure.core/atom 0))))))
