(ns glimmer.ratom-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer.ratom :as r :refer [atom cursor reaction]]))

;; NOTE: requiring glimmer.ratom rebinds clojure.core/deref, reset! and swap!
;; so @, reset! and swap! work uniformly over reactive cells and plain host
;; atoms (the rebound ops delegate to the host for non-reactive values). The
;; plain atoms created below via clojure.core/atom therefore still behave.

(deftest ratom-read-and-write
  (testing "an atom reads its initial value"
    (is (= 0 @(atom 0))))
  (testing "reset! updates the value"
    (let [a (atom :a)]
      (reset! a :b)
      (is (= :b @a))))
  (testing "swap! applies a function"
    (let [a (atom 5)]
      (swap! a inc)
      (is (= 6 @a))
      (swap! a + 10 20)
      (is (= 36 @a)))))

(deftest host-atoms-still-work
  ;; host atoms must keep working through the rebound @ / reset! / swap!.
  (let [a (clojure.core/atom 1)]
    (is (= 1 @a))
    (reset! a 2)
    (is (= 2 @a))
    (swap! a * 3)
    (is (= 6 @a))))

(deftest watcher-fires-on-change
  (let [a (atom 0)
        fired (clojure.core/atom 0)
        watch (fn [_] (swap! fired inc))]
    ;; simulate a render: while *current-watcher* is bound, deref registers it.
    (binding [r/*current-watcher* watch]
      @a)                                   ; register
    (is (zero? @fired) "no fires before a change")
    (reset! a 1)
    (is (= 1 @fired) "the watcher fires once on change")
    (reset! a 1)
    (is (= 1 @fired) "no fire when value is unchanged")))

(deftest watcher-registered-once
  (let [a (atom 0)
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (swap! fired inc))]
      @a @a @a)                             ; three reads, one watcher
    (reset! a 1)
    (is (= 1 @fired) "repeated derefs register the watcher only once")))

(deftest cursor-reads-and-writes-the-source
  (let [a (atom {:name "jolt" :count 0})
        c (cursor a [:count])]
    (is (= 0 @c))
    (reset! c 7)
    (is (= 7 @c))
    (is (= 7 (:count @a)) "writing the cursor updates the source path")
    (swap! c inc)
    (is (= 8 (:count @a)))))

(deftest cursor-propagates-to-its-watchers
  (let [a (atom {:x 1})
        c (cursor a [:x])
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (swap! fired inc))]
      @c)                                   ; register a watcher on the cursor
    (reset! a {:x 2})                       ; mutate the source directly
    (is (= 1 @fired) "a cursor watcher fires when its source changes")))

(deftest reaction-derives-and-recomputes
  (let [a (atom 2)
        ;; a reaction runs its body, tracking every reactive it derefs.
        squared (reaction (* @a @a))]
    (is (= 4 @squared))
    (reset! a 5)
    (is (= 25 @squared) "the reaction recomputes when a dependency changes")
    (reset! a 5)
    (is (= 25 @squared) "no recompute when the dependency value is unchanged")))

(deftest reaction-notifies-its-own-watchers
  (let [a (atom 1)
        doubled (reaction (* 2 @a))
        fired (clojure.core/atom 0)]
    (binding [r/*current-watcher* (fn [_] (swap! fired inc))]
      @doubled)                             ; subscribe to the reaction
    (reset! a 10)
    (is (= 1 @fired) "a reaction subscriber fires when the reaction recomputes")))

(deftest cursor-and-reaction-compose
  (let [state (atom {:todos [{:done false} {:done true} {:done false}]})
        done-count (reaction (count (filter :done (:todos @state))))]
    (is (= 1 @done-count))
    (swap! state update-in [:todos 0 :done] not)   ; mark first todo done
    (is (= 2 @done-count) "writing the source recomputes the derived reaction")))
